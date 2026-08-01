package Com.hau.name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

private const val TAG = "InputInjectionService"

/**
 * Chạy trên Máy B. Vai trò DUY NHẤT: thực hiện thao tác chạm/vuốt theo tọa độ
 * mà Máy A gửi tới.
 *
 * Các sửa lỗi so với phiên bản cũ:
 * 1. Race condition khi service đã tồn tại từ phiên cũ: onServiceConnected() không
 *    được gọi lại → thêm SharedPreferences.OnSharedPreferenceChangeListener để nhận
 *    roomCode mới ngay khi ConsentActivity lưu vào, bất kể service đang ở trạng thái nào.
 * 2. Lệnh tap cũ bị replay khi reconnect: đổi từ setValue() + ValueEventListener sang
 *    push() + ChildEventListener (addChildEventListener) → chỉ nhận lệnh MỚI, không nhận
 *    lại lệnh cũ từ lần kết nối trước.
 * 3. Hỗ trợ swipe ngoài tap.
 */
class InputInjectionService : AccessibilityService() {

    private val database = FirebaseDatabase.getInstance().reference
    private var roomCode: String? = null
    private var commandListener: ChildEventListener? = null
    private lateinit var prefs: SharedPreferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active_room_code") {
            val newCode = prefs.getString("active_room_code", null)
            if (newCode != roomCode) {
                stopListening()
                roomCode = newCode
                newCode?.let { startListening(it) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("remote_assist", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        roomCode = prefs.getString("active_room_code", null)
        roomCode?.let { startListening(it) }
        Log.d(TAG, "Service connected, roomCode=$roomCode")
    }

    private fun startListening(code: String) {
        val ref = database.child("rooms").child(code).child("controlCommands")
        commandListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleCommand(snapshot)
                // Xóa lệnh sau khi xử lý để tránh tích lũy không giới hạn trong Firebase
                snapshot.ref.removeValue()
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error: ${error.message}")
            }
        }
        ref.addChildEventListener(commandListener!!)
        Log.d(TAG, "Listening for commands on room $code")
    }

    private fun stopListening() {
        commandListener?.let {
            roomCode?.let { code ->
                database.child("rooms").child(code).child("controlCommands")
                    .removeEventListener(it)
            }
        }
        commandListener = null
    }

    private fun handleCommand(snapshot: DataSnapshot) {
        val type = snapshot.child("type").getValue(String::class.java) ?: return
        val x = snapshot.child("x").getValue(Double::class.java) ?: return
        val y = snapshot.child("y").getValue(Double::class.java) ?: return
        val metrics = resources.displayMetrics
        val px = x.toFloat() * metrics.widthPixels
        val py = y.toFloat() * metrics.heightPixels

        when (type) {
            "tap" -> performTap(px, py)
            "swipe" -> {
                val x2 = snapshot.child("x2").getValue(Double::class.java) ?: return
                val y2 = snapshot.child("y2").getValue(Double::class.java) ?: return
                val duration = snapshot.child("duration").getValue(Long::class.java) ?: 300L
                performSwipe(px, py, x2.toFloat() * metrics.widthPixels,
                    y2.toFloat() * metrics.heightPixels, duration)
            }
        }
    }

    private fun performTap(px: Float, py: Float) {
        val path = Path().apply { moveTo(px, py) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopListening()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        super.onDestroy()
    }
}
