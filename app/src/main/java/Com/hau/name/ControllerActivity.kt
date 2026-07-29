package Com.hau.name

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

/**
 * Máy A (máy điều khiển).
 * Người dùng nhập mã 6 số do Máy B cung cấp, sau đó bắt đầu ghép nối
 * WebRTC qua Firebase để nhận luồng video màn hình và gửi lệnh chạm.
 */
class ControllerActivity : AppCompatActivity() {

    private val database = FirebaseDatabase.getInstance().reference
    private var connectedRoomCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        val editCode = findViewById<EditText>(R.id.edit_pairing_code)
        val btnConnect = findViewById<Button>(R.id.btn_connect)

        btnConnect.setOnClickListener {
            val code = editCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "Mã phải gồm 6 chữ số", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectWithCode(code)
        }
    }

    private fun connectWithCode(code: String) {
        database.child("rooms").child(code).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                Toast.makeText(this, "Mã không tồn tại hoặc đã hết hạn", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            val status = snapshot.child("status").getValue(String::class.java)
            if (status == "ended") {
                Toast.makeText(this, "Phiên này đã kết thúc", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            connectedRoomCode = code
            startWebRTCHandshake(code)
        }
    }

    private fun startWebRTCHandshake(code: String) {
        // TODO: khởi tạo PeerConnection, gửi/nhận offer-answer-ICE qua
        // database.child("rooms").child(code), sau đó gắn video track nhận
        // được vào SurfaceViewRenderer trong remote_view_container.
        Toast.makeText(this, "Đang kết nối tới máy $code...", Toast.LENGTH_SHORT).show()
    }

    /**
     * Gọi hàm này từ listener chạm trên SurfaceViewRenderer hiển thị màn hình Máy B.
     * Tọa độ được chuẩn hóa về tỉ lệ 0.0–1.0 để không phụ thuộc độ phân giải máy.
     */
    private fun sendTapCommand(xRatio: Float, yRatio: Float) {
        val code = connectedRoomCode ?: return
        database.child("rooms").child(code).child("controlCommand").setValue(
            mapOf(
                "type" to "tap",
                "x" to xRatio,
                "y" to yRatio,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }
}
