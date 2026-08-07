# Windows Agent (điều khiển laptop bằng điện thoại)

Chương trình console chạy trên **Windows** đóng vai "Máy B" (máy bị điều khiển),
tương đương `ConsentActivity` + `RemoteHostService` + `InputInjectionService`
bên Android, nhưng cho laptop. Điện thoại dùng `ControllerActivity` sẵn có để
kết nối — **không cần sửa gì phía app Android để dùng chung với agent này**,
vì cả hai nói cùng một giao thức signaling/lệnh qua Firebase Realtime Database.

## Trạng thái build

Mình không có Windows/.NET SDK trong sandbox nên không tự build/chạy thử trực
tiếp được. Thay vào đó, workflow `.github/workflows/build-windows-agent.yml`
build thật trên runner `windows-latest` mỗi khi có commit đụng tới thư mục
này. Lần chạy đầu tiên có vài lỗi biên dịch (sai API `VideoFormat`, thiếu
`AllowUnsafeBlocks`, kiểu `ushort` cho ICE mline index) — đã sửa ở commit
"Fix windows-agent compile errors from first CI run".

**Trước khi coi là dùng được, hãy vào tab Actions của repo, mở lần chạy
"Build Windows Agent" gần nhất và xem có dấu ✅ xanh không.** Nếu vẫn còn lỗi
đỏ, dán nguyên log lỗi vào đây (mình không đọc được Actions log tự động) để
sửa tiếp — không đoán mò khi chưa thấy lỗi thật.

**Rủi ro cao nhất ở lần thêm code gần nhất (Vai 2 — điều khiển máy khác):**
khối decode video trong `ControllerSession.cs` (`OnVideoFrameReceived` +
`VpxVideoEncoder.DecodeVideo`) được viết theo suy đoán API thật của
SIPSorcery/SIPSorceryMedia.Encoders, chưa build/test được vì không có mạng để
tra NuGet lúc viết. Nếu build lỗi ở đúng khối này, gửi log lỗi để sửa đúng
chỗ.

Cách chạy khi đã có `WindowsAgent.exe` (tải từ Artifacts của lần build ✅):

1. Chạy `WindowsAgent.exe` trực tiếp (self-contained, không cần cài .NET) —
   hoặc `cd windows-agent && dotnet restore && dotnet build && dotnet run`
   nếu bạn tự build trên máy Windows có .NET 8 SDK.
2. Windows Defender/SmartScreen nhiều khả năng sẽ cảnh báo vì exe build từ CI
   chưa được ký số (unsigned) — chọn "More info → Run anyway" nếu bạn tự tin
   vào nguồn gốc file (do chính bạn build từ repo này).
3. Nếu máy có tường lửa/antivirus chặn `SendInput`/chụp màn hình, cần cho phép
   thủ công.

## Hai chiều điều khiển

Chạy `WindowsAgent.exe` sẽ hỏi bạn chọn 1 trong 2 vai:

**Vai 1 — Laptop bị điều khiển** (điện thoại điều khiển laptop):
1. Chọn `1` → hiện mã 6 số.
2. Trên điện thoại, mở app → "Điều khiển máy khác" → nhập mã đó.
3. WebRTC kết nối trực tiếp (qua STUN/TURN công khai), agent bắt đầu gửi hình
   màn hình, điện thoại gửi lệnh chạm/vuốt (chuột) và gõ chữ (bàn phím).
4. Đóng cửa sổ console hoặc Ctrl+C = kết thúc phiên ngay lập tức.

**Vai 2 — Laptop điều khiển máy khác** (laptop điều khiển điện thoại hoặc
laptop khác đang chạy vai 1):
1. Chọn `2` → nhập mã 6 số của máy cần điều khiển (lấy từ ConsentActivity bên
   Android, hoặc từ vai 1 của một agent Windows khác).
2. Một cửa sổ (`RemoteVideoForm`) hiện ra, chiếu màn hình máy kia lên.
3. Click chuột = tap, kéo chuột = vuốt (swipe) — quy đổi ra toạ độ tỷ lệ 0..1
   giống hệt cơ chế `ControllerActivity.kt`, nên phía bị điều khiển (điện
   thoại hay laptop) không cần biết ai đang điều khiển mình.
4. Đóng cửa sổ đó = ngắt kết nối.

⚠️ Vai 2 chưa hỗ trợ gửi lệnh gõ chữ (`text`/`key`) tới điện thoại — chỉ có
chuột/chạm. Gõ chữ vào một Android khác đòi hỏi sửa thêm
`InputInjectionService.kt` để nhận và set text vào ô đang focus qua
AccessibilityNodeInfo, việc này chưa làm ở lượt này.

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
- Chưa có UI đồ hoạ dạng system tray/nút bật-tắt cho vai 1 (host) — vẫn là
  console app. Vai 2 (controller) đã có 1 cửa sổ hiển thị hình
  (`RemoteVideoForm`) nhưng còn tối giản.
- Chưa xử lý đa màn hình (multi-monitor) — hiện chỉ capture màn hình chính.
- Con số FPS/bitrate mới đặt tạm giống mặc định bên Android, chưa tối ưu riêng
  cho laptop (thường mạnh hơn điện thoại, có thể tăng chất lượng lên được).
- Vai 2 (laptop điều khiển máy khác) chưa gửi được lệnh gõ chữ (`text`/`key`)
  tới điện thoại — chỉ có chuột/chạm (xem ghi chú ở mục "Hai chiều điều
  khiển" phía trên).
- Vai 2 chưa build/test được (xem mục "Trạng thái build" phía trên) — phần
  decode video là rủi ro cao nhất.
