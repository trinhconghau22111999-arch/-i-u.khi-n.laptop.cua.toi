using WindowsAgent;

// URL Realtime Database của project Firebase hiện tại (checkinonline-785d5) — lấy từ
// project_info.firebase_url trong app/google-services.json, PHẢI khớp với app Android
// để 2 bên gặp nhau trong cùng "rooms/{code}".
const string FIREBASE_DB_URL = "https://checkinonline-785d5-default-rtdb.asia-southeast1.firebasedatabase.app";

Console.WriteLine("=== Windows Remote Control Agent ===");
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
