# Windows Agent (điều khiển laptop bằng điện thoại)

Chương trình console chạy trên **Windows** đóng vai "Máy B" (máy bị điều khiển),
tương đương `ConsentActivity` + `RemoteHostService` + `InputInjectionService`
bên Android, nhưng cho laptop. Điện thoại dùng `ControllerActivity` sẵn có để
kết nối — **không cần sửa gì phía app Android để dùng chung với agent này**,
vì cả hai nói cùng một giao thức signaling/lệnh qua Firebase Realtime Database.

## ⚠️ Quan trọng — mình chưa build/test được phần này

Mình viết code này trong môi trường sandbox **không có Windows, không có .NET SDK,
và không có quyền truy cập NuGet** (chỉ npm/pypi/crates được phép), nên **chưa
compile hay chạy thử được**. Đây là bản khung (skeleton) dựa trên API của
SIPSorcery/SIPSorceryMedia.Encoders — nhiều khả năng cần chỉnh sửa nhỏ (tên
hàm/tham số đúng phiên bản NuGet) khi bạn build thật trên máy Windows. Trước khi
dùng thật, bạn (hoặc mình ở lượt sau nếu bạn dán lỗi build vào đây) cần:

1. Cài **.NET 8 SDK** trên Windows.
2. `cd windows-agent && dotnet restore && dotnet build`
3. Sửa lỗi biên dịch nếu có (thường là do phiên bản NuGet khác chút so với lúc
   mình viết — gửi lại lỗi cho mình, mình sửa tiếp).
4. `dotnet run`

## Cách hoạt động

1. Chạy `WindowsAgent.exe` (hoặc `dotnet run`) trên laptop → hiện mã 6 số.
2. Trên điện thoại, mở app → "Điều khiển máy khác" → nhập mã đó.
3. WebRTC kết nối trực tiếp (qua STUN/TURN công khai), agent bắt đầu gửi hình
   màn hình, điện thoại gửi lệnh chạm/vuốt (chuột) và gõ chữ (bàn phím).
4. Đóng cửa sổ console hoặc Ctrl+C = kết thúc phiên ngay lập tức.

## Lệnh điều khiển hỗ trợ

| type    | Trường dữ liệu                          | Windows thực thi                  |
|---------|------------------------------------------|------------------------------------|
| `tap`   | `x`, `y` (tỷ lệ 0..1)                     | Click chuột trái tại vị trí đó     |
| `swipe` | `x`,`y`,`x2`,`y2`,`duration`              | Kéo chuột (mouse drag)             |
| `text`  | `text` (chuỗi Unicode)                    | Gõ chữ (hỗ trợ tiếng Việt có dấu)  |
| `key`   | `key`: `enter`\|`backspace`\|`esc`\|`tab` | Nhấn phím tương ứng                |

`text`/`key` là lệnh MỚI (không có trong bản Android-điều-khiển-Android cũ) —
xem phần "Bàn phím" đã thêm vào `ControllerActivity` để gửi 2 loại lệnh này.

## Giới hạn hiện tại (chưa làm)

- Chưa capture âm thanh laptop, chỉ có hình + điều khiển chuột/bàn phím.
- Chưa có UI đồ hoạ (system tray icon, nút bật/tắt) — mới là console app; có
  thể nâng cấp lên WPF/WinForms sau nếu bạn cần trải nghiệm thân thiện hơn.
- Chưa xử lý đa màn hình (multi-monitor) — hiện chỉ capture màn hình chính.
- Con số FPS/bitrate mới đặt tạm giống mặc định bên Android, chưa tối ưu riêng
  cho laptop (thường mạnh hơn điện thoại, có thể tăng chất lượng lên được).
