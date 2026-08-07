using System.Drawing.Imaging;
using System.Net;
using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;

namespace WindowsAgent;

/// <summary>
/// Tương đương ControllerActivity.kt (Máy A) nhưng cho Windows: nhập mã ghép nối của máy
/// kia (điện thoại hoặc laptop khác đang ở chế độ "bị điều khiển"), nhận offer, tạo answer,
/// decode video nhận được rồi hiển thị trong 1 cửa sổ (RemoteVideoForm), và gửi lệnh chuột
/// (tap/swipe) theo toạ độ TỶ LỆ (0..1) khi người dùng click/kéo chuột trong cửa sổ đó.
///
/// ⚠️ KHỐI DECODE VIDEO (OnVideoFrameReceived + VpxVideoEncoder.DecodeVideo bên dưới) LÀ PHẦN
/// RỦI RO NHẤT trong file này. Mình viết dựa theo suy đoán API thật của SIPSorcery/
/// SIPSorceryMedia.Encoders — không có mạng để tra NuGet package lúc viết (giống tình trạng đã
/// nói ở windows-agent/README.md khi viết HostSession/ScreenCapture lần đầu, và lần đó CI đã
/// bắt được vài lỗi biên dịch thật cần sửa). Nếu CI báo lỗi ở khối này, dán nguyên lỗi biên dịch
/// vào chat để sửa tiếp — tên hàm/tham số/kiểu dữ liệu chính xác chỉ biết chắc khi có lỗi thật,
/// đoán lần 2 mà không có lỗi cụ thể dễ sai tiếp.
/// </summary>
public sealed class ControllerSession : IAsyncDisposable
{
    private readonly FirebaseSignaling _signaling;
    private readonly VpxVideoEncoder _codec = new(); // cùng 1 class dùng để encode (ScreenCapture.cs) lẫn decode (ở đây)
    private RTCPeerConnection? _pc;
    private RemoteVideoForm? _form;

    public string RoomCode { get; }

    public ControllerSession(string firebaseDbUrl, string roomCode)
    {
        RoomCode = roomCode;
        _signaling = new FirebaseSignaling(firebaseDbUrl, roomCode, isHost: false);
    }

    /// <summary>Kiểm tra mã hợp lệ rồi dựng PeerConnection + lắng nghe signaling.
    /// Trả về false nếu mã sai/phòng đã hết hạn (không mở cửa sổ trong trường hợp đó).</summary>
    public async Task<bool> StartAsync()
    {
        var (exists, ended) = await _signaling.CheckRoomAsync();
        if (!exists)
        {
            Console.WriteLine("Mã không tồn tại hoặc đã hết hạn.");
            return false;
        }
        if (ended)
        {
            Console.WriteLine("Phiên này đã kết thúc.");
            return false;
        }

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

        // Nhận-only: giống addTransceiver(RECV_ONLY) trong PeerConnectionManager.kt khi isHost=false.
        _pc.addTrack(new MediaStreamTrack(new VideoFormat(VideoCodecsEnum.VP8, 96), MediaStreamStatusEnum.RecvOnly));

        _pc.OnVideoFrameReceived += OnVideoFrameReceived;

        _pc.onicecandidate += (cand) =>
        {
            if (cand == null) return;
            _ = _signaling.SendIceCandidateAsync(cand.sdpMid ?? "0", cand.sdpMLineIndex, cand.candidate);
        };
        _pc.onconnectionstatechange += (state) => Console.WriteLine($"[WebRTC] state = {state}");

        _signaling.OfferReceived += (sdp) =>
        {
            Console.WriteLine("Nhận offer từ máy kia.");
            _pc.setRemoteDescription(new RTCSessionDescriptionInit { type = RTCSdpType.offer, sdp = sdp });
            var answer = _pc.createAnswer();
            _ = _pc.setLocalDescription(answer)
                .ContinueWith(_ => _signaling.SendAnswerAsync(answer.sdp));
        };
        _signaling.IceCandidateReceived += (mid, idx, cand) =>
        {
            _pc?.addIceCandidate(new RTCIceCandidateInit { sdpMid = mid, sdpMLineIndex = (ushort)idx, candidate = cand });
        };
        _signaling.RemoteEnded += () =>
            Console.WriteLine("Máy kia đã ngắt kết nối. Đóng cửa sổ hình để thoát.");

        _signaling.StartListening();
        return true;
    }

    private void OnVideoFrameReceived(IPEndPoint remoteEndPoint, uint timestamp, byte[] payload, VideoFormat format)
    {
        try
        {
            var decodedFrames = _codec.DecodeVideo(payload, VideoPixelFormatsEnum.I420, VideoCodecsEnum.VP8);
            foreach (var frame in decodedFrames)
            {
                var bmp = I420ToBitmap(frame.Sample, frame.Width, frame.Height);
                _form?.UpdateFrame(bmp, frame.Width, frame.Height);
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[Video] Lỗi decode/hiển thị frame: {ex.Message}");
        }
    }

    /// <summary>Chuyển buffer I420 (Y+U+V phẳng, chuẩn BT.601 giới hạn) nhận từ decoder sang
    /// Bitmap 32bppArgb để hiển thị trong PictureBox — phép biến đổi NGƯỢC của BitmapToI420()
    /// trong ScreenCapture.cs.</summary>
    private static unsafe Bitmap I420ToBitmap(byte[] i420, int w, int h)
    {
        var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        try
        {
            int ySize = w * h;
            int uvWidth = w / 2;
            byte* dst = (byte*)data.Scan0;
            int stride = data.Stride;

            for (int y = 0; y < h; y++)
            {
                byte* row = dst + y * stride;
                int uvRow = (y / 2) * uvWidth;
                for (int x = 0; x < w; x++)
                {
                    byte yy = i420[y * w + x];
                    int uvIndex = uvRow + x / 2;
                    byte u = i420[ySize + uvIndex];
                    byte v = i420[ySize + ySize / 4 + uvIndex];

                    double r = yy + 1.402 * (v - 128);
                    double g = yy - 0.344136 * (u - 128) - 0.714136 * (v - 128);
                    double b = yy + 1.772 * (u - 128);

                    row[x * 4 + 0] = (byte)Math.Clamp(b, 0, 255);
                    row[x * 4 + 1] = (byte)Math.Clamp(g, 0, 255);
                    row[x * 4 + 2] = (byte)Math.Clamp(r, 0, 255);
                    row[x * 4 + 3] = 255;
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }
        return bmp;
    }

    /// <summary>PHẢI được gọi trên 1 thread STA riêng do chính người gọi tạo (xem Program.cs) —
    /// dựng cửa sổ hiển thị hình + bắt chuột, và chạy message loop WinForms cho tới khi người
    /// dùng đóng cửa sổ (= ngắt kết nối, tương đương nút "Ngắt kết nối" bên Android).</summary>
    public void RunUi()
    {
        Application.EnableVisualStyles();
        _form = new RemoteVideoForm(SendTap, SendSwipe);
        Application.Run(_form);
    }

    private void SendTap(double x, double y) =>
        _ = _signaling.SendControlCommandAsync(new Dictionary<string, object>
        {
            ["type"] = "tap",
            ["x"] = x,
            ["y"] = y
        });

    private void SendSwipe(double x1, double y1, double x2, double y2, long durationMs) =>
        _ = _signaling.SendControlCommandAsync(new Dictionary<string, object>
        {
            ["type"] = "swipe",
            ["x"] = x1,
            ["y"] = y1,
            ["x2"] = x2,
            ["y2"] = y2,
            ["duration"] = durationMs
        });

    public async Task EndSessionAsync()
    {
        try { await _signaling.MarkEndedAsync(); } catch { /* best-effort, giống Android */ }
        _pc?.close();
    }

    public async ValueTask DisposeAsync()
    {
        _pc?.Dispose();
        _codec.Dispose();
        await _signaling.DisposeAsync();
    }
}
