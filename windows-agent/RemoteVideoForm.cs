namespace WindowsAgent;

/// <summary>
/// Cửa sổ WinForms tối giản: 1 PictureBox hiển thị khung hình máy kia gửi tới, bắt sự kiện
/// chuột (click = tap, kéo = swipe) rồi quy đổi ra toạ độ TỶ LỆ (0..1) — tương đương phần
/// render SurfaceViewRenderer + setupTouchHandling()/videoRectRatio() trong ControllerActivity.kt.
///
/// Đóng cửa sổ này = ngắt kết nối (giống nút "Ngắt kết nối" bên Android) — do ControllerSession
/// gọi EndSessionAsync() sau khi Application.Run() ở Program.cs trả về.
/// </summary>
public sealed class RemoteVideoForm : Form
{
    private readonly PictureBox _pictureBox = new()
    {
        Dock = DockStyle.Fill,
        SizeMode = PictureBoxSizeMode.Zoom,
        BackColor = Color.Black
    };

    private readonly Action<double, double> _onTap;
    private readonly Action<double, double, double, double, long> _onSwipe;

    // Kích thước khung hình THẬT (độ phân giải máy bị điều khiển gửi lên), khác với kích thước
    // PictureBox trên màn hình laptop — cần để tính đúng vùng hiển thị (trừ viền đen letterbox
    // do SizeMode=Zoom giữ tỉ lệ) khi quy đổi toạ độ chuột, giống videoRectRatio() bên Android.
    private int _frameWidth;
    private int _frameHeight;

    private bool _dragging;
    private double _startX, _startY;
    private long _startTimeMs;

    public RemoteVideoForm(Action<double, double> onTap, Action<double, double, double, double, long> onSwipe)
    {
        _onTap = onTap;
        _onSwipe = onSwipe;

        Text = "Đang điều khiển từ xa — đóng cửa sổ này để ngắt kết nối";
        Width = 1000;
        Height = 650;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(_pictureBox);

        _pictureBox.MouseDown += (_, e) =>
        {
            var (rx, ry) = ToRatio(e.X, e.Y);
            if (rx == null || ry == null) return;
            _dragging = true;
            _startX = rx.Value;
            _startY = ry.Value;
            _startTimeMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        };

        _pictureBox.MouseUp += (_, e) =>
        {
            if (!_dragging) return;
            _dragging = false;

            var (rx, ry) = ToRatio(e.X, e.Y);
            if (rx == null || ry == null) return;

            var durationMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - _startTimeMs;
            var dxPx = (rx.Value - _startX) * _pictureBox.Width;
            var dyPx = (ry.Value - _startY) * _pictureBox.Height;
            var distPx = Math.Sqrt(dxPx * dxPx + dyPx * dyPx);

            // Ngưỡng 8px trên màn hình laptop (chuột chính xác hơn ngón tay) để phân biệt
            // tap và swipe — tương đương ngưỡng 20px (chạm) bên ControllerActivity.kt.
            if (distPx < 8)
                _onTap(_startX, _startY);
            else
                _onSwipe(_startX, _startY, rx.Value, ry.Value, Math.Clamp(durationMs, 80, 1500));
        };
    }

    /// <summary>Quy đổi toạ độ pixel trong PictureBox (đã zoom giữ tỉ lệ, có thể có viền đen)
    /// sang tỷ lệ 0..1 trong khung hình thật. Trả về (null, null) nếu chưa nhận khung hình nào.</summary>
    private (double?, double?) ToRatio(int px, int py)
    {
        if (_frameWidth <= 0 || _frameHeight <= 0 || _pictureBox.Width <= 0 || _pictureBox.Height <= 0)
            return (null, null);

        double videoAspect = (double)_frameWidth / _frameHeight;
        double boxAspect = (double)_pictureBox.Width / _pictureBox.Height;

        double left, top, dispW, dispH;
        if (videoAspect > boxAspect)
        {
            dispW = _pictureBox.Width;
            dispH = _pictureBox.Width / videoAspect;
            left = 0;
            top = (_pictureBox.Height - dispH) / 2.0;
        }
        else
        {
            dispH = _pictureBox.Height;
            dispW = _pictureBox.Height * videoAspect;
            top = 0;
            left = (_pictureBox.Width - dispW) / 2.0;
        }

        if (dispW <= 0 || dispH <= 0) return (null, null);

        double rx = (px - left) / dispW;
        double ry = (py - top) / dispH;
        return (Math.Clamp(rx, 0, 1), Math.Clamp(ry, 0, 1));
    }

    /// <summary>Cập nhật khung hình mới. An toàn gọi từ bất kỳ thread nào (vd. thread nhận
    /// video WebRTC) — tự Invoke lên UI thread nếu cần, giống cách Android cập nhật UI qua
    /// runOnUiThread().</summary>
    public void UpdateFrame(Bitmap bmp, int frameWidth, int frameHeight)
    {
        if (IsDisposed) { bmp.Dispose(); return; }
        _frameWidth = frameWidth;
        _frameHeight = frameHeight;
        try
        {
            if (InvokeRequired) BeginInvoke(() => SetImage(bmp));
            else SetImage(bmp);
        }
        catch (ObjectDisposedException)
        {
            bmp.Dispose();
        }
    }

    private void SetImage(Bitmap bmp)
    {
        var old = _pictureBox.Image;
        _pictureBox.Image = bmp;
        old?.Dispose();
    }
}
