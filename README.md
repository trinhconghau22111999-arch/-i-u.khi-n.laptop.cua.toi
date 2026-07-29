# Remote Assist

Ứng dụng hỗ trợ điều khiển từ xa minh bạch: một máy có thể xem và điều khiển
máy kia **chỉ sau khi** người dùng máy bị điều khiển chủ động đồng ý và tạo mã
ghép nối. Không có hành vi ẩn giấu — mọi quyền đều xin qua hộp thoại hệ thống
chuẩn của Android, và banner "Đang chia sẻ màn hình" luôn hiển thị trong suốt
phiên làm việc kèm nút ngắt kết nối.

## Kiến trúc

- **Máy B (được điều khiển):** `ConsentActivity` → `RemoteHostService` →
  `InputInjectionService`
- **Máy A (điều khiển):** `ControllerActivity`
- **Signaling:** Firebase Realtime Database (không cần tự dựng server)
- **Truyền video:** WebRTC

## Bước 1 — Tạo dự án Firebase

Dự án Firebase của bạn: **checkinonline-785d5**
Package name Android đã đăng ký: **Com.hau.name**

1. File `app/google-services.json` đã được đặt sẵn trong project này.
2. Trong Firebase Console → **Realtime Database** → **Create database** →
   chọn chế độ **test mode** để bắt đầu (sau đó siết lại rule khi dùng thật,
   xem gợi ý rule bên dưới).

### Gợi ý Realtime Database Rules (siết bảo mật cơ bản)

```json
{
  "rules": {
    "rooms": {
      "$roomCode": {
        ".read": true,
        ".write": true,
        ".validate": "newData.hasChildren(['status'])"
      }
    }
  }
}
```

Đây là mức tối thiểu để chạy demo. Khi triển khai thật, nên thêm Firebase
Authentication (ẩn danh) và giới hạn quyền ghi theo UID để tránh người lạ
ghi đè phòng của người khác.

## Bước 2 — Build APK bằng GitHub Actions (không cần máy tính cài Android Studio)

1. Tạo repo GitHub mới, đẩy toàn bộ thư mục này lên (file `app/google-services.json`
   sẽ tự động bị Git bỏ qua nhờ `.gitignore` — không lo lộ lên repo public).
2. Vào repo → **Settings → Secrets and variables → Actions → New repository
   secret**, đặt tên `GOOGLE_SERVICES_JSON_BASE64`, dán chuỗi base64 sau vào
   (đã được tạo sẵn từ file bạn cung cấp, không cần tự mã hóa lại):

   ```
   ewogICJwcm9qZWN0X2luZm8iOiB7CiAgICAicHJvamVjdF9udW1iZXIiOiAiNDg5NzIxODI4NTI4IiwKICAgICJmaXJlYmFzZV91cmwiOiAiaHR0cHM6Ly9jaGVja2lub25saW5lLTc4NWQ1LWRlZmF1bHQtcnRkYi5hc2lhLXNvdXRoZWFzdDEuZmlyZWJhc2VkYXRhYmFzZS5hcHAiLAogICAgInByb2plY3RfaWQiOiAiY2hlY2tpbm9ubGluZS03ODVkNSIsCiAgICAic3RvcmFnZV9idWNrZXQiOiAiY2hlY2tpbm9ubGluZS03ODVkNS5maXJlYmFzZXN0b3JhZ2UuYXBwIgogIH0sCiAgImNsaWVudCI6IFsKICAgIHsKICAgICAgImNsaWVudF9pbmZvIjogewogICAgICAgICJtb2JpbGVzZGtfYXBwX2lkIjogIjE6NDg5NzIxODI4NTI4OmFuZHJvaWQ6ZjAyN2RjMTk4ZjBmM2ZlYmFhNTc3NSIsCiAgICAgICAgImFuZHJvaWRfY2xpZW50X2luZm8iOiB7CiAgICAgICAgICAicGFja2FnZV9uYW1lIjogIkNvbS5oYXUubmFtZSIKICAgICAgICB9CiAgICAgIH0sCiAgICAgICJvYXV0aF9jbGllbnQiOiBbXSwKICAgICAgImFwaV9rZXkiOiBbCiAgICAgICAgewogICAgICAgICAgImN1cnJlbnRfa2V5IjogIkFJemFTeURFWVFfaXlUOWRFRGNFOGpYRDJRRTBuYjlHNDM5eXY1MCIKICAgICAgICB9CiAgICAgIF0sCiAgICAgICJzZXJ2aWNlcyI6IHsKICAgICAgICAiYXBwaW52aXRlX3NlcnZpY2UiOiB7CiAgICAgICAgICAib3RoZXJfcGxhdGZvcm1fb2F1dGhfY2xpZW50IjogW10KICAgICAgICB9CiAgICAgIH0KICAgIH0KICBdLAogICJjb25maWd1cmF0aW9uX3ZlcnNpb24iOiAiMSIKfQ==
   ```

3. Vào tab **Actions**, chạy workflow **Build Debug APK** (hoặc chỉ cần push
   lên nhánh `main`).
4. Sau khi build xong, mở run vừa chạy → mục **Artifacts** → tải
   `remote-assist-debug-apk` về, giải nén ra file `.apk`, cài vào 2 máy.

## Bước 3 — Cài đặt trên 2 máy

**Máy B (máy sẽ cho phép điều khiển):**
1. Mở app → chọn "Cho phép máy khác điều khiển máy này".
2. Đọc kỹ nội dung, tick vào ô đồng ý.
3. Bấm "Tạo mã ghép nối" → cấp quyền quay màn hình khi Android hỏi.
4. Vào **Cài đặt → Hỗ trợ tiếp cận (Accessibility) → Ứng dụng đã cài đặt →
   Remote Assist** → bật thủ công (bước này Android bắt buộc, không thể tự
   động hóa).
5. Đọc mã 6 số hiện trên màn hình cho người cần điều khiển.

**Máy A (máy điều khiển):**
1. Mở app → chọn "Điều khiển một máy khác".
2. Nhập mã 6 số → bấm Kết nối.

## Những phần cần hoàn thiện thêm (đánh dấu TODO trong code)

Bộ khung này đã có đủ: cấu trúc Gradle, Firebase, Manifest, quyền, luồng xin
đồng ý, AccessibilityService gửi/nhận lệnh chạm. Phần **stream video WebRTC
thật** (khởi tạo `PeerConnection`, `VideoCapturer` từ `MediaProjection`, trao
đổi offer/answer/ICE candidates) được đánh dấu `// TODO` trong:

- `RemoteHostService.kt`
- `ControllerActivity.kt`

Đây là phần code dài nhất của dự án (thường 200–400 dòng cho một
implementation WebRTC đầy đủ) — nếu bạn muốn, mình có thể viết tiếp phần này
ở lượt sau.

## Nguyên tắc thiết kế bắt buộc giữ nguyên khi bạn chỉnh sửa

- Nút "Tạo mã" luôn bị khóa cho tới khi người dùng B tự tick đồng ý.
- Notification "Đang chia sẻ màn hình" luôn `setOngoing(true)`, không được ẩn.
- `InputInjectionService` chỉ chuyển tiếp tọa độ chạm, không được thêm logic
  phát hiện/chặn hành vi của người dùng máy B.
- Mã ghép nối nên có thời hạn (gợi ý: dùng Cloud Function hoặc client tự xóa
  room sau vài phút không kết nối) để tránh bị dùng lại.
