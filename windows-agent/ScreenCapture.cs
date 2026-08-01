using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;

namespace WindowsAgent;

/// <summary>
/// Chụp màn hình chính định kỳ (GDI BitBlt qua CopyFromScreen), co về cạnh dài tối đa
/// (giống CAPTURE_MAX_DIMENSION/CAPTURE_FPS bên RemoteHostService.kt để đồng bộ triết lý:
/// giảm tải encoder + băng thông, tọa độ điều khiển vẫn đúng vì luôn dùng TỶ LỆ 0..1),
/// rồi mã hoá VP8 và đẩy vào PeerConnection qua VideoEncoderEndPoint.
/// </summary>
public sealed class ScreenCapture : IDisposable
{
    // Cùng giá trị mặc định như bên Android, có thể chỉnh nếu máy yếu/mạng chậm.
    private const int CAPTURE_MAX_DIMENSION = 1280;
    private const int CAPTURE_FPS = 20;

    private readonly VpxVideoEncoder _encoder = new();
    private readonly System.Timers.Timer _timer;
    private readonly int _screenWidth;
    private readonly int _screenHeight;
    private readonly int _outWidth;
    private readonly int _outHeight;
    private bool _running;

    public event Action<byte[]>? EncodedFrameReady; // VP8 payload sẵn sàng gửi qua RTP

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);
    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;

    public ScreenCapture()
    {
        // Dùng GetSystemMetrics thay vì System.Windows.Forms để không phải phụ thuộc WinForms
        // chỉ vì 2 con số kích thước màn hình.
        _screenWidth = GetSystemMetrics(SM_CXSCREEN);
        _screenHeight = GetSystemMetrics(SM_CYSCREEN);

        var longSide = Math.Max(_screenWidth, _screenHeight);
        double scale = longSide > CAPTURE_MAX_DIMENSION ? (double)CAPTURE_MAX_DIMENSION / longSide : 1.0;
        _outWidth = ((int)(_screenWidth * scale)) & ~1;   // số chẵn, bắt buộc với encoder
        _outHeight = ((int)(_screenHeight * scale)) & ~1;

        _timer = new System.Timers.Timer(1000.0 / CAPTURE_FPS) { AutoReset = true };
        _timer.Elapsed += (_, _) => CaptureAndEncodeOnce();
    }

    public void Start()
    {
        if (_running) return;
        _running = true;
        _timer.Start();
    }

    public void Stop()
    {
        _running = false;
        _timer.Stop();
    }

    private void CaptureAndEncodeOnce()
    {
        if (!_running) return;
        try
        {
            using var full = new Bitmap(_screenWidth, _screenHeight, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(full))
            {
                g.CopyFromScreen(0, 0, 0, 0, new Size(_screenWidth, _screenHeight));
            }

            using var scaled = new Bitmap(full, new Size(_outWidth, _outHeight));
            var i420 = BitmapToI420(scaled);

            // VpxVideoEncoder.EncodeVideo trả về danh sách payload đã đóng gói VP8.
            var encoded = _encoder.EncodeVideo(_outWidth, _outHeight, i420, VideoPixelFormatsEnum.I420, VideoCodecsEnum.VP8);
            if (encoded != null) EncodedFrameReady?.Invoke(encoded);
        }
        catch
        {
            // Bỏ qua lỗi 1 khung hình đơn lẻ (vd. màn hình đổi độ phân giải giữa chừng) —
            // không được để ngoại lệ làm chết timer, khung kế tiếp sẽ tự thử lại.
        }
    }

    /// <summary>Chuyển Bitmap 32bppArgb sang buffer I420 (Y + U + V phẳng) mà encoder VP8 cần.</summary>
    private static byte[] BitmapToI420(Bitmap bmp)
    {
        int w = bmp.Width, h = bmp.Height;
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int frameSize = w * h * 3 / 2;
            var i420 = new byte[frameSize];
            int ySize = w * h;
            int uvSize = ySize / 4;

            unsafe
            {
                byte* src = (byte*)data.Scan0;
                int stride = data.Stride;

                for (int y = 0; y < h; y++)
                {
                    byte* row = src + y * stride;
                    for (int x = 0; x < w; x++)
                    {
                        byte b = row[x * 4 + 0], g = row[x * 4 + 1], r = row[x * 4 + 2];
                        i420[y * w + x] = (byte)Math.Clamp((0.299 * r + 0.587 * g + 0.114 * b), 0, 255);
                    }
                }

                for (int y = 0; y < h; y += 2)
                {
                    byte* row = src + y * stride;
                    for (int x = 0; x < w; x += 2)
                    {
                        byte b = row[x * 4 + 0], g = row[x * 4 + 1], r = row[x * 4 + 2];
                        double u = -0.169 * r - 0.331 * g + 0.5 * b + 128;
                        double v = 0.5 * r - 0.419 * g - 0.081 * b + 128;
                        int uvIndex = (y / 2) * (w / 2) + (x / 2);
                        i420[ySize + uvIndex] = (byte)Math.Clamp(u, 0, 255);
                        i420[ySize + uvSize + uvIndex] = (byte)Math.Clamp(v, 0, 255);
                    }
                }
            }
            return i420;
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }

    public void Dispose()
    {
        Stop();
        _timer.Dispose();
        _encoder.Dispose();
    }
}
