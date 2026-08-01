using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace WindowsAgent;

/// <summary>
/// Trao đổi tín hiệu WebRTC (offer/answer/ICE) qua Firebase Realtime Database REST API,
/// dùng ĐÚNG schema mà app Android (SignalingClient.kt) đang dùng, để 2 bên tương thích:
///
///   rooms/{code}/
///     status              : "waiting" | "connected" | "ended"
///     consentGivenAt       : long (ms)
///     offer                : { type, sdp }        -- agent (host) ghi
///     answer                : { type, sdp }        -- điện thoại (controller) ghi
///     iceCandidatesHost/{id}: { sdpMid, sdpMLineIndex, candidate }  -- agent ghi
///     iceCandidatesCtrl/{id}: { sdpMid, sdpMLineIndex, candidate }  -- điện thoại ghi
///
/// Agent Windows luôn đóng vai "Máy B" (host, isHost = true).
/// </summary>
public sealed class FirebaseSignaling : IAsyncDisposable
{
    private readonly string _baseUrl; // vd: https://checkinonline-785d5-default-rtdb.asia-southeast1.firebasedatabase.app
    private readonly string _roomCode;
    private readonly HttpClient _http = new();
    private CancellationTokenSource? _listenCts;

    public event Action<string>? AnswerReceived;         // sdp
    public event Action<string, int, string>? IceCandidateReceived; // sdpMid, sdpMLineIndex, candidate
    public event Action? RemoteEnded;

    public FirebaseSignaling(string firebaseDbUrl, string roomCode)
    {
        _baseUrl = firebaseDbUrl.TrimEnd('/');
        _roomCode = roomCode;
    }

    private string RoomUrl(string path = "") =>
        string.IsNullOrEmpty(path)
            ? $"{_baseUrl}/rooms/{_roomCode}.json"
            : $"{_baseUrl}/rooms/{_roomCode}/{path}.json";

    /// <summary>Tạo phòng mới với status "waiting", như ConsentActivity làm bên Android.</summary>
    public async Task CreateRoomAsync()
    {
        var body = JsonSerializer.Serialize(new
        {
            status = "waiting",
            consentGivenAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        });
        var resp = await _http.PutAsync(RoomUrl(), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task SendOfferAsync(string sdp)
    {
        var body = JsonSerializer.Serialize(new { type = "offer", sdp });
        var resp = await _http.PutAsync(RoomUrl("offer"), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task SendIceCandidateAsync(string sdpMid, int sdpMLineIndex, string candidate)
    {
        var body = JsonSerializer.Serialize(new { sdpMid, sdpMLineIndex, candidate });
        var resp = await _http.PostAsync(RoomUrl("iceCandidatesHost"), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task MarkConnectedAsync()
    {
        var resp = await _http.PutAsync(RoomUrl("status"), new StringContent("\"connected\"", Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task MarkEndedAsync()
    {
        var resp = await _http.PutAsync(RoomUrl("status"), new StringContent("\"ended\"", Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public event Action<string /*commandId*/, JsonElement>? ControlCommandReceived;

    /// <summary>
    /// Bắt đầu lắng nghe realtime: answer, iceCandidatesCtrl, status, controlCommands.
    /// Firebase REST hỗ trợ Server-Sent Events (header Accept: text/event-stream) — tương
    /// đương ValueEventListener/ChildEventListener bên SDK Android.
    /// </summary>
    public void StartListening()
    {
        _listenCts = new CancellationTokenSource();
        _ = ListenAsync(RoomUrl(), _listenCts.Token);
    }

    private async Task ListenAsync(string url, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, url);
                req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
                using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
                resp.EnsureSuccessStatusCode();
                using var stream = await resp.Content.ReadAsStreamAsync(ct);
                using var reader = new StreamReader(stream);

                string? eventType = null;
                while (!ct.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(ct);
                    if (line == null) break; // connection dropped, reconnect via outer loop
                    if (line.StartsWith("event: ")) { eventType = line["event: ".Length..].Trim(); continue; }
                    if (line.StartsWith("data: "))
                    {
                        var json = line["data: ".Length..];
                        HandleEvent(eventType, json);
                        eventType = null;
                    }
                }
            }
            catch (OperationCanceledException) { break; }
            catch
            {
                // Mạng chập chờn / mất kết nối tạm thời -> thử lại sau 2s, không throw ra ngoài.
                try { await Task.Delay(2000, ct); } catch { break; }
            }
        }
    }

    private void HandleEvent(string? eventType, string dataJson)
    {
        if (eventType != "put" && eventType != "patch") return;
        try
        {
            using var doc = JsonDocument.Parse(dataJson);
            var root = doc.RootElement;
            if (!root.TryGetProperty("path", out var pathEl) || !root.TryGetProperty("data", out var dataEl))
                return;
            var path = pathEl.GetString() ?? "";

            // path == "/"  -> toàn bộ room thay đổi lần đầu (initial dump khi mới connect SSE)
            if (path == "/" && dataEl.ValueKind == JsonValueKind.Object)
            {
                if (dataEl.TryGetProperty("answer", out var answerEl) && answerEl.ValueKind == JsonValueKind.Object)
                    EmitAnswer(answerEl);
                if (dataEl.TryGetProperty("iceCandidatesCtrl", out var iceEl) && iceEl.ValueKind == JsonValueKind.Object)
                    foreach (var c in iceEl.EnumerateObject()) EmitIce(c.Value);
                if (dataEl.TryGetProperty("status", out var stEl) && stEl.GetString() == "ended")
                    RemoteEnded?.Invoke();
                if (dataEl.TryGetProperty("controlCommands", out var cmdsEl) && cmdsEl.ValueKind == JsonValueKind.Object)
                    foreach (var c in cmdsEl.EnumerateObject()) ControlCommandReceived?.Invoke(c.Name, c.Value);
                return;
            }

            if (path == "/answer" && dataEl.ValueKind == JsonValueKind.Object) EmitAnswer(dataEl);
            else if (path.StartsWith("/iceCandidatesCtrl")) EmitIce(dataEl);
            else if (path == "/status" && dataEl.ValueKind == JsonValueKind.String && dataEl.GetString() == "ended")
                RemoteEnded?.Invoke();
            else if (path.StartsWith("/controlCommands/"))
            {
                var id = path["/controlCommands/".Length..];
                if (dataEl.ValueKind == JsonValueKind.Object)
                    ControlCommandReceived?.Invoke(id, dataEl);
            }
        }
        catch
        {
            // JSON không đúng dạng mong đợi (vd. null khi node bị xoá) -> bỏ qua an toàn.
        }
    }

    private void EmitAnswer(JsonElement answerObj)
    {
        if (answerObj.TryGetProperty("sdp", out var sdpEl))
        {
            var sdp = sdpEl.GetString();
            if (!string.IsNullOrEmpty(sdp)) AnswerReceived?.Invoke(sdp);
        }
    }

    private void EmitIce(JsonElement iceObj)
    {
        if (iceObj.ValueKind != JsonValueKind.Object) return;
        if (iceObj.TryGetProperty("sdpMid", out var midEl) &&
            iceObj.TryGetProperty("sdpMLineIndex", out var idxEl) &&
            iceObj.TryGetProperty("candidate", out var candEl))
        {
            var mid = midEl.GetString();
            var cand = candEl.GetString();
            if (mid != null && cand != null)
                IceCandidateReceived?.Invoke(mid, idxEl.GetInt32(), cand);
        }
    }

    /// <summary>Xoá lệnh điều khiển sau khi đã xử lý, giống snapshot.ref.removeValue() bên Android.</summary>
    public async Task RemoveCommandAsync(string commandId)
    {
        try { await _http.DeleteAsync(RoomUrl($"controlCommands/{commandId}")); } catch { /* best-effort */ }
    }

    public async ValueTask DisposeAsync()
    {
        _listenCts?.Cancel();
        _http.Dispose();
        await Task.CompletedTask;
    }
}
