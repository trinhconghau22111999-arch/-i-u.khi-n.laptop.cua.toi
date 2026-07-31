package Com.hau.name

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

private const val TAG = "ControllerActivity"

/**
 * Máy A (máy điều khiển).
 * 1. Nhập mã 6 số.
 * 2. Lấy offer từ Firebase (do Máy B đã gửi).
 * 3. Tạo answer, trao đổi ICE → WebRTC kết nối.
 * 4. Render video màn hình Máy B lên SurfaceViewRenderer.
 * 5. Mọi thao tác chạm → gửi tọa độ chuẩn hóa lên Firebase → Máy B thực thi.
 */
class ControllerActivity : AppCompatActivity() {

    private val database = FirebaseDatabase.getInstance().reference
    private var connectedRoomCode: String? = null

    private var signalingClient: SignalingClient? = null
    private var peerConnectionManager: PeerConnectionManager? = null
    private lateinit var eglBase: EglBase
    private lateinit var remoteRenderer: SurfaceViewRenderer

    // Views
    private lateinit var layoutCodeEntry: android.widget.LinearLayout
    private lateinit var remoteViewContainer: FrameLayout
    private lateinit var editCode: EditText
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        eglBase = EglBase.create()

        layoutCodeEntry = findViewById(R.id.layout_code_entry)
        remoteViewContainer = findViewById(R.id.remote_view_container)
        editCode = findViewById(R.id.edit_pairing_code)
        btnConnect = findViewById(R.id.btn_connect)

        // Thêm SurfaceViewRenderer vào container để render video Máy B
        remoteRenderer = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        remoteViewContainer.addView(remoteRenderer)
        remoteRenderer.init(eglBase.eglBaseContext, null)

        // Chuyển tiếp thao tác chạm trên màn hình Máy B → gửi lệnh về Máy B
        remoteRenderer.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                sendTapCommand(event.x / view.width, event.y / view.height)
            }
            true
        }

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
        btnConnect.isEnabled = false
        btnConnect.text = "Đang kết nối..."

        database.child("rooms").child(code).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                Toast.makeText(this, "Mã không tồn tại hoặc đã hết hạn", Toast.LENGTH_SHORT).show()
                btnConnect.isEnabled = true; btnConnect.text = "Kết nối"; return@addOnSuccessListener
            }
            val status = snapshot.child("status").getValue(String::class.java)
            if (status == "ended") {
                Toast.makeText(this, "Phiên này đã kết thúc", Toast.LENGTH_SHORT).show()
                btnConnect.isEnabled = true; btnConnect.text = "Kết nối"; return@addOnSuccessListener
            }
            connectedRoomCode = code
            startWebRTC(code)
        }.addOnFailureListener {
            Toast.makeText(this, "Lỗi kết nối Firebase: ${it.message}", Toast.LENGTH_SHORT).show()
            btnConnect.isEnabled = true; btnConnect.text = "Kết nối"
        }
    }

    private fun startWebRTC(code: String) {
        val sigClient = SignalingClient(
            roomCode = code,
            isHost = false,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {
                    Log.d(TAG, "Nhận offer từ Máy B")
                    peerConnectionManager?.handleOffer(sdp)
                }
                override fun onAnswerReceived(sdp: String) {} // controller không nhận answer
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    peerConnectionManager?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() {
                    runOnUiThread {
                        Toast.makeText(this@ControllerActivity, "Máy B đã ngắt kết nối", Toast.LENGTH_SHORT).show()
                        showCodeEntry()
                    }
                }
            }
        )
        signalingClient = sigClient

        val pcm = PeerConnectionManager(
            context = this,
            eglBase = eglBase,
            isHost = false,
            signalingClient = sigClient,
            remoteSink = remoteRenderer,
            onConnected = {
                Log.d(TAG, "WebRTC connected!")
                runOnUiThread {
                    layoutCodeEntry.visibility = android.view.View.GONE
                    remoteViewContainer.visibility = android.view.View.VISIBLE
                    Toast.makeText(this, "Đã kết nối!", Toast.LENGTH_SHORT).show()
                }
            },
            onDisconnected = {
                runOnUiThread {
                    Toast.makeText(this, "Mất kết nối", Toast.LENGTH_SHORT).show()
                    showCodeEntry()
                }
            }
        )
        peerConnectionManager = pcm
        pcm.init()
        // SignalingClient đã start() trong pcm.init() → tự động lắng nghe offer từ Máy B
    }

    private fun sendTapCommand(xRatio: Float, yRatio: Float) {
        val code = connectedRoomCode ?: return
        database.child("rooms").child(code).child("controlCommand").setValue(
            mapOf("type" to "tap", "x" to xRatio, "y" to yRatio,
                "timestamp" to System.currentTimeMillis())
        )
    }

    private fun showCodeEntry() {
        layoutCodeEntry.visibility = android.view.View.VISIBLE
        remoteViewContainer.visibility = android.view.View.GONE
        btnConnect.isEnabled = true
        btnConnect.text = "Kết nối"
        releaseWebRTC()
    }

    private fun releaseWebRTC() {
        signalingClient?.release(); signalingClient = null
        peerConnectionManager?.release(); peerConnectionManager = null
    }

    override fun onDestroy() {
        releaseWebRTC()
        remoteRenderer.release()
        eglBase.release()
        super.onDestroy()
    }
}
