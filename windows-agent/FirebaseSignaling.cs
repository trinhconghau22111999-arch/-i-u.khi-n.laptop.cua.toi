using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace WindowsAgent;

/// <summary>
/// Trao đổi tín hiệu WebRTC (offer/answer/ICE) qua Firebase Realtime Database REST API,
/// dùng ĐÚNG schema mà app Android (SignalingClient.kt) đang dùng, để mọi phía tương thích:
///
///   rooms/{code}/
///     status                 : "waiting" | "connected" | "ended"
///     consentGivenAt         : long (ms)
///     offer                  : { type, sdp }        -- máy "host" (bị điều khiển) ghi
///     answer                 : { type, sdp }        -- máy "controller" (điều khiển) ghi
///     iceCandidatesHost/{id} : { sdpMid, sdpMLineIndex, candidate }  -- host ghi
///     iceCandidatesCtrl/{id} : { sdpMid, sdpMLineIndex, candidate }  -- controller ghi
///     controlCommands/{id}   : lệnh chuột/bàn phím -- controller ghi, host đọc rồi xoá
///
/// [isHost] = true  → agent đóng vai "Máy B" (bị điều khiển) — dùng trong HostSession.
/// [isHost] = false → agent đóng vai "Máy A" (điều khiển máy khác) — dùng trong ControllerSession.
/// Cùng 1 class cho cả 2 vai, y hệt thiết kế SignalingClient.kt bên Android (isHost).
/// </summary>
public sealed class FirebaseSignaling : IAsyncDisposable
{
    private readonly string _baseUrl; // vd: https://checkinonline-785d5-default-rtdb.asia-southeast1.firebasedatabase.app
    private readonly string _roomCode;
    private readonly bool _isHost;
    private readonly HttpClient _http = new();
    private CancellationTokenSource? _listenCts;

    public event Action<string>? OfferReceived;           // sdp -- chỉ phát khi isHost=false
    public event Action<string>? AnswerReceived;          // sdp -- chỉ phát khi isHost=true
    public event Action<string, int, string>? IceCandidateReceived; // sdpMid, sdpMLineIndex, candidate
    public event Action? RemoteEnded;
    public event Action<string /*commandId*/, JsonElement>? ControlCommandReceived; // chỉ phát khi isHost=true

    public FirebaseSignaling(string firebaseDbUrl, string roomCode, bool isHost)
    {
        _baseUrl = firebaseDbUrl.TrimEnd('/');
        _roomCode = roomCode;
        _isHost = isHost;
    }

    private string RoomUrl(string path = "") =>
        string.IsNullOrEmpty(path)
            ? $"{_baseUrl}/rooms/{_roomCode}.json"
            : $"{_baseUrl}/rooms/{_roomCode}/{path}.json";

    private string LocalIcePath => _isHost ? "iceCandidatesHost" : "iceCandidatesCtrl";
    private string RemoteIcePath => _isHost ? "iceCandidatesCtrl" : "iceCandidatesHost";

    /// <summary>Tạo phòng mới với status "waiting" — chỉ gọi khi isHost=true, như ConsentActivity
    /// làm bên Android.</summary>
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

    /// <summary>Kiểm tra phòng có tồn tại/còn hoạt động không — chỉ gọi khi isHost=false, TRƯỚC
    /// khi dựng PeerConnection. Tương đương connectWithCode() trong ControllerActivity.kt.</summary>
    public async Task<(bool exists, bool ended)> CheckRoomAsync()
    {
        var resp = await _http.GetAsync(RoomUrl());
        resp.EnsureSuccessStatusCode();
        var json = await resp.Content.ReadAsStringAsync();
        if (json.Trim() == "null") return (false, false);
        using var doc = JsonDocument.Parse(json);
        var ended = doc.RootElement.TryGetProperty("status", out var st) && st.GetString() == "ended";
        return (true, ended);
    }

    public async Task SendOfferAsync(string sdp)
    {
        var body = JsonSerializer.Serialize(new { type = "offer", sdp });
        var resp = await _http.PutAsync(RoomUrl("offer"), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task SendAnswerAsync(string sdp)
    {
        var body = JsonSerializer.Serialize(new { type = "answer", sdp });
        var resp = await _http.PutAsync(RoomUrl("answer"), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    public async Task SendIceCandidateAsync(string sdpMid, int sdpMLineIndex, string candidate)
    {
        var body = JsonSerializer.Serialize(new { sdpMid, sdpMLineIndex, candidate });
        var resp = await _http.PostAsync(RoomUrl(LocalIcePath), new StringContent(body, Encoding.UTF8, "application/json"));
        resp.EnsureSuccessStatusCode();
    }

    /// <summary>Gửi lệnh chuột/bàn phím lên controlCommands — chỉ gọi khi isHost=false (controller),
    /// tương đương sendCommand() trong ControllerActivity.kt. Dùng POST (push) để không đè lệnh cũ
    /// chưa được host xử lý.</summary>
    public async Task SendControlCommandAsync(Dictionary<string, object> data)
    {
        try
        {
            var body = JsonSerializer.Serialize(data);
            var resp = await _http.PostAsync(RoomUrl("controlCommands"), new StringContent(body, Encoding.UTF8, "application/json"));
            resp.EnsureSuccessStatusCode();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[Command] Gửi lệnh thất bại: {ex.Message}");
        }
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

    /// <summary>
    /// Bắt đầu lắng nghe realtime: offer/answer (tùy vai), ICE của phía kia, status, controlCommands.
    /// Firebase REST hỗ trợ Server-Sent Events (header Accept: text/event-stream) — tương đương
    /// ValueEventListener/ChildEventListener bên SDK Android.
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
                if (_isHost && dataEl.TryGetProperty("answer", out var answerEl) && answerEl.ValueKind == JsonValueKind.Object)
                    EmitAnswer(answerEl);
                if (!_isHost && dataEl.TryGetProperty("offer", out var offerEl) && offerEl.ValueKind == JsonValueKind.Object)
                    EmitOffer(offerEl);
                if (dataEl.TryGetProperty(RemoteIcePath, out var iceEl) && iceEl.ValueKind == JsonValueKind.Object)
                    foreach (var c in iceEl.EnumerateObject()) EmitIce(c.Value);
                if (dataEl.TryGetProperty("status", out var stEl) && stEl.GetString() == "ended")
                    RemoteEnded?.Invoke();
                if (_isHost && dataEl.TryGetProperty("controlCommands", out var cmdsEl) && cmdsEl.ValueKind == JsonValueKind.Object)
                    foreach (var c in cmdsEl.EnumerateObject()) ControlCommandReceived?.Invoke(c.Name, c.Value);
                return;
            }

            if (_isHost && path == "/answer" && dataEl.ValueKind == JsonValueKind.Object) EmitAnswer(dataEl);
            else if (!_isHost && path == "/offer" && dataEl.ValueKind == JsonValueKind.Object) EmitOffer(dataEl);
            else if (path.StartsWith($"/{RemoteIcePath}")) EmitIce(dataEl);
            else if (path == "/status" && dataEl.ValueKind == JsonValueKind.String && dataEl.GetString() == "ended")
                RemoteEnded?.Invoke();
            else if (_isHost && path.StartsWith("/controlCommands/"))
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

    private void EmitOffer(JsonElement offerObj)
    {
        if (offerObj.TryGetProperty("sdp", out var sdpEl))
        {
            var sdp = sdpEl.GetString();
            if (!string.IsNullOrEmpty(sdp)) OfferReceived?.Invoke(sdp);
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
