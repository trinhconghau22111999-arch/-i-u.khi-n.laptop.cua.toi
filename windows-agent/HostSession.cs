using System.Text.Json;
using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;

namespace WindowsAgent;

/// <summary>
/// Tương đương RemoteHostService.kt + ConsentActivity.kt gộp lại, nhưng cho Windows:
/// tạo mã ghép nối, dựng PeerConnection, gửi video màn hình, nhận lệnh chuột/bàn phím
/// từ điện thoại rồi thực thi bằng InputInjector.
///
/// Cùng dùng ICE servers công khai (STUN Google + TURN openrelay demo) như bên Android
/// để 2 phía luôn tìm được đường kết nối, kể cả khi khác mạng/NAT.
/// </summary>
public sealed class HostSession : IAsyncDisposable
{
    private readonly FirebaseSignaling _signaling;
    private readonly ScreenCapture _capture = new();
    private RTCPeerConnection? _pc;
    private MediaStreamTrack? _videoTrack;

    public string RoomCode { get; }

    public HostSession(string firebaseDbUrl, string roomCode)
    {
        RoomCode = roomCode;
        _signaling = new FirebaseSignaling(firebaseDbUrl, roomCode);
    }

    public async Task StartAsync()
    {
        await _signaling.CreateRoomAsync();

        var config = new RTCConfiguration
        {
            iceServers = new List<RTCIceServer>
            {
                new() { urls = "stun:stun.l.google.com:19302" },
                new() { urls = "stun:stun1.l.google.com:19302" },
                new() { urls = "turn:openrelay.metered.ca:80", username = "openrelayproject", credential = "openrelayproject" },
                new() { urls = "turn:openrelay.metered.ca:443", username = "openrelayproject", credential = "openrelayproject" },
            }
        };
        _pc = new RTCPeerConnection(config);

        _videoTrack = new MediaStreamTrack(new VideoFormat(VideoCodecsEnum.VP8, 96), MediaStreamStatusEnum.SendOnly);
        _pc.addTrack(_videoTrack);

        _pc.onicecandidate += (cand) =>
        {
            if (cand == null) return;
            _ = _signaling.SendIceCandidateAsync(cand.sdpMid ?? "0", cand.sdpMLineIndex, cand.candidate);
        };

        _pc.onconnectionstatechange += (state) =>
        {
            Console.WriteLine($"[WebRTC] state = {state}");
            if (state == RTCPeerConnectionState.connected)
            {
                _ = _signaling.MarkConnectedAsync();
                _capture.Start();
            }
            else if (state is RTCPeerConnectionState.disconnected or RTCPeerConnectionState.failed or RTCPeerConnectionState.closed)
            {
                _capture.Stop();
            }
        };

        _capture.EncodedFrameReady += (frame) =>
        {
            // Timestamp RTP tăng theo clock 90kHz chuẩn video; SIPSorcery tự tính khi truyền frameDuration.
            _pc?.SendVideo((uint)(90000 / 20), frame);
        };

        _signaling.AnswerReceived += (sdp) =>
        {
            _pc?.setRemoteDescription(new RTCSessionDescriptionInit { type = RTCSdpType.answer, sdp = sdp });
        };
        _signaling.IceCandidateReceived += (mid, idx, cand) =>
        {
            _pc?.addIceCandidate(new RTCIceCandidateInit { sdpMid = mid, sdpMLineIndex = (ushort)idx, candidate = cand });
        };
        _signaling.RemoteEnded += () =>
        {
            Console.WriteLine("Điện thoại đã ngắt kết nối.");
            _capture.Stop();
        };
        _signaling.ControlCommandReceived += OnControlCommand;

        _signaling.StartListening();

        var offer = _pc.createOffer();
        await _pc.setLocalDescription(offer);
        await _signaling.SendOfferAsync(offer.sdp);
    }

    private void OnControlCommand(string commandId, JsonElement cmd)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                if (!cmd.TryGetProperty("type", out var typeEl)) return;
                var type = typeEl.GetString();
                switch (type)
                {
                    case "tap":
                        InputInjector.Click(cmd.GetProperty("x").GetDouble(), cmd.GetProperty("y").GetDouble());
                        break;
                    case "swipe":
                        await InputInjector.DragAsync(
                            cmd.GetProperty("x").GetDouble(), cmd.GetProperty("y").GetDouble(),
                            cmd.GetProperty("x2").GetDouble(), cmd.GetProperty("y2").GetDouble(),
                            cmd.TryGetProperty("duration", out var d) ? d.GetInt64() : 300L);
                        break;
                    case "text":
                        if (cmd.TryGetProperty("text", out var textEl))
                            InputInjector.TypeText(textEl.GetString() ?? "");
                        break;
                    case "key":
                        if (cmd.TryGetProperty("key", out var keyEl))
                            InputInjector.PressKey(keyEl.GetString() ?? "");
                        break;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Command] Lỗi xử lý lệnh {commandId}: {ex.Message}");
            }
            finally
            {
                await _signaling.RemoveCommandAsync(commandId);
            }
        });
    }

    public async Task EndSessionAsync()
    {
        _capture.Stop();
        try { await _signaling.MarkEndedAsync(); } catch { /* best-effort */ }
        _pc?.close();
    }

    public async ValueTask DisposeAsync()
    {
        _capture.Dispose();
        _pc?.Dispose();
        await _signaling.DisposeAsync();
    }
}
