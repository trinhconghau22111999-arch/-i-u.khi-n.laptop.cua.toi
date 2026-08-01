using System.Runtime.InteropServices;

namespace WindowsAgent;

/// <summary>
/// Thực thi lệnh chuột/bàn phím trên Windows bằng SendInput (WinAPI), tương đương vai trò
/// InputInjectionService.kt (AccessibilityService dispatchGesture) bên Android — nhưng ở đây
/// dùng API chuẩn của Windows để điều khiển toàn hệ thống, không cần accessibility permission.
///
/// Toạ độ đầu vào luôn là TỶ LỆ (0..1), giống hệt cách ControllerActivity.kt gửi lên, để
/// điện thoại không cần biết độ phân giải thật của laptop.
/// </summary>
public static class InputInjector
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;

    private const int INPUT_MOUSE = 0;
    private const int INPUT_KEYBOARD = 1;

    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;

    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public InputUnion u;
    }

    private static (int w, int h) ScreenSize() => (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN));

    /// <summary>Quy đổi tỷ lệ (0..1) sang toạ độ "absolute" 0..65535 mà SendInput yêu cầu.</summary>
    private static (int ax, int ay) ToAbsolute(double xRatio, double yRatio)
    {
        var (w, h) = ScreenSize();
        double cx = Math.Clamp(xRatio, 0, 1) * (w - 1);
        double cy = Math.Clamp(yRatio, 0, 1) * (h - 1);
        return ((int)(cx * 65535 / Math.Max(1, w - 1)), (int)(cy * 65535 / Math.Max(1, h - 1)));
    }

    private static void SendMouse(uint flags, int ax = 0, int ay = 0)
    {
        var input = new INPUT
        {
            type = INPUT_MOUSE,
            u = new InputUnion { mi = new MOUSEINPUT { dx = ax, dy = ay, dwFlags = flags } }
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    public static void MoveTo(double xRatio, double yRatio)
    {
        var (ax, ay) = ToAbsolute(xRatio, yRatio);
        SendMouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax, ay);
    }

    public static void Click(double xRatio, double yRatio)
    {
        var (ax, ay) = ToAbsolute(xRatio, yRatio);
        SendMouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax, ay);
        SendMouse(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_ABSOLUTE, ax, ay);
        SendMouse(MOUSEEVENTF_LEFTUP | MOUSEEVENTF_ABSOLUTE, ax, ay);
    }

    /// <summary>Kéo chuột (mouse down -> di chuyển dần -> mouse up), dùng cho lệnh "swipe".</summary>
    public static async Task DragAsync(double x1, double y1, double x2, double y2, long durationMs, CancellationToken ct = default)
    {
        var (ax1, ay1) = ToAbsolute(x1, y1);
        SendMouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax1, ay1);
        SendMouse(MOUSEEVENTF_LEFTDOWN | MOUSEEVENTF_ABSOLUTE, ax1, ay1);

        const int steps = 12;
        var stepDelay = TimeSpan.FromMilliseconds(Math.Clamp(durationMs, 80, 1500) / (double)steps);
        for (int i = 1; i <= steps; i++)
        {
            double t = i / (double)steps;
            var (ax, ay) = ToAbsolute(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
            SendMouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, ax, ay);
            try { await Task.Delay(stepDelay, ct); } catch { break; }
        }

        var (ax2, ay2) = ToAbsolute(x2, y2);
        SendMouse(MOUSEEVENTF_LEFTUP | MOUSEEVENTF_ABSOLUTE, ax2, ay2);
    }

    /// <summary>Gõ một chuỗi Unicode (hỗ trợ tiếng Việt có dấu) bằng KEYEVENTF_UNICODE.</summary>
    public static void TypeText(string text)
    {
        var inputs = new List<INPUT>();
        foreach (var ch in text)
        {
            inputs.Add(new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wScan = ch, dwFlags = KEYEVENTF_UNICODE } } });
            inputs.Add(new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wScan = ch, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } } });
        }
        if (inputs.Count > 0) SendInput((uint)inputs.Count, inputs.ToArray(), Marshal.SizeOf<INPUT>());
    }

    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_BACK = 0x08;
    private const ushort VK_ESCAPE = 0x1B;
    private const ushort VK_TAB = 0x09;

    /// <summary>Gõ phím đặc biệt theo tên: "enter" | "backspace" | "esc" | "tab".</summary>
    public static void PressKey(string keyName)
    {
        ushort vk = keyName switch
        {
            "enter" => VK_RETURN,
            "backspace" => VK_BACK,
            "esc" => VK_ESCAPE,
            "tab" => VK_TAB,
            _ => 0
        };
        if (vk == 0) return;
        var down = new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk } } };
        var up = new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = KEYEVENTF_KEYUP } } };
        SendInput(2, new[] { down, up }, Marshal.SizeOf<INPUT>());
    }
}
