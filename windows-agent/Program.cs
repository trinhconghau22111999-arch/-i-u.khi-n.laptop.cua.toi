using WindowsAgent;

// URL Realtime Database của project Firebase hiện tại (checkinonline-785d5) — lấy từ
// project_info.firebase_url trong app/google-services.json, PHẢI khớp với app Android
// để 2 bên gặp nhau trong cùng "rooms/{code}".
const string FIREBASE_DB_URL = "https://checkinonline-785d5-default-rtdb.asia-southeast1.firebasedatabase.app";

Console.WriteLine("=== Windows Remote Control Agent ===");
Console.WriteLine("1) Cho phép máy khác (điện thoại) điều khiển laptop này");
Console.WriteLine("2) Dùng laptop này để điều khiển máy khác (điện thoại hoặc laptop khác)");
Console.Write("Chọn (1/2): ");
var mode = (Console.ReadLine() ?? "").Trim();
Console.WriteLine();

if (mode == "2")
{
    await RunControllerAsync();
}
else
{
    await RunHostAsync();
}

async Task RunHostAsync()
{
    Console.WriteLine("Ứng dụng này cho phép điều khiển máy tính này từ điện thoại qua mã ghép nối.");
    Console.WriteLine("Bạn sẽ thấy rõ khi có phiên đang hoạt động qua dòng trạng thái bên dưới.");
    Console.WriteLine();

    var code = new Random().Next(100000, 1000000).ToString();
    Console.WriteLine($"Mã ghép nối: {code}");
    Console.WriteLine("Nhập mã này vào app điện thoại (chế độ 'Điều khiển máy khác') để kết nối.");
    Console.WriteLine("Nhấn Ctrl+C để kết thúc phiên bất cứ lúc nào.");
    Console.WriteLine();

    await using var session = new HostSession(FIREBASE_DB_URL, code);

    var cts = new CancellationTokenSource();
    Console.CancelKeyPress += (_, e) =>
    {
        e.Cancel = true;
        cts.Cancel();
    };

    await session.StartAsync();
    Console.WriteLine("Đang chờ điện thoại kết nối...");

    try
    {
        await Task.Delay(Timeout.Infinite, cts.Token);
    }
    catch (TaskCanceledException) { }

    Console.WriteLine("Đang kết thúc phiên...");
    await session.EndSessionAsync();
}

async Task RunControllerAsync()
{
    Console.Write("Nhập mã ghép nối 6 số của máy cần điều khiển: ");
    var code = (Console.ReadLine() ?? "").Trim();
    if (code.Length != 6 || !code.All(char.IsDigit))
    {
        Console.WriteLine("Mã phải gồm đúng 6 chữ số.");
        return;
    }

    await using var session = new ControllerSession(FIREBASE_DB_URL, code);
    var connected = await session.StartAsync();
    if (!connected) return;

    Console.WriteLine("Đang mở cửa sổ hiển thị màn hình máy kia... (đóng cửa sổ đó để ngắt kết nối)");

    // Cửa sổ WinForms cần message loop chạy trên 1 thread STA riêng — Console app mặc định
    // không có sẵn thread STA + message loop cho UI, nên tách hẳn ra 1 thread mới ở đây thay
    // vì cố chạy trên thread Main (đang bận vòng lặp async chính).
    var uiThread = new Thread(session.RunUi);
    uiThread.SetApartmentState(ApartmentState.STA);
    uiThread.Start();
    uiThread.Join(); // đợi tới khi người dùng đóng cửa sổ

    Console.WriteLine("Đang kết thúc phiên...");
    await session.EndSessionAsync();
}
