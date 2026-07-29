package Com.hau.name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Chạy trên Máy B. Vai trò DUY NHẤT của service này là thực hiện thao tác
 * chạm/vuốt theo tọa độ mà Máy A gửi tới, sau khi người dùng đã tự bật dịch
 * vụ này thủ công trong Cài đặt > Hỗ trợ (Android bắt buộc bước thủ công này,
 * không có API nào tự động bật hộ).
 *
 * Cố ý KHÔNG có logic nào ở đây dùng để phát hiện/chặn hành vi của người
 * dùng thật trên Máy B (ví dụ: tự mở camera, tự bấm Home...). Bất kỳ hành vi
 * ẩn giấu như vậy sẽ vi phạm nguyên tắc minh bạch của toàn bộ ứng dụng.
 */
class InputInjectionService : AccessibilityService() {

    private val database = FirebaseDatabase.getInstance().reference
    private var roomCode: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // roomCode nên được lấy từ SharedPreferences do ConsentActivity lưu lại
        // khi tạo mã, để service biết đang lắng nghe phòng nào.
        roomCode = getSharedPreferences("remote_assist", MODE_PRIVATE)
            .getString("active_room_code", null)
        roomCode?.let { listenForControlCommands(it) }
    }

    private fun listenForControlCommands(code: String) {
        database.child("rooms").child(code).child("controlCommand")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val type = snapshot.child("type").getValue(String::class.java) ?: return
                    val x = snapshot.child("x").getValue(Double::class.java) ?: return
                    val y = snapshot.child("y").getValue(Double::class.java) ?: return
                    if (type == "tap") {
                        performTap(x.toFloat(), y.toFloat())
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun performTap(xRatio: Float, yRatio: Float) {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(xRatio * metrics.widthPixels, yRatio * metrics.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // Không dùng sự kiện accessibility để giám sát hay chặn ứng dụng nào khác.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
