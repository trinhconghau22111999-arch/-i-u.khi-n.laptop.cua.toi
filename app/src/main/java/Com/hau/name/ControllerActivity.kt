package Com.hau.name

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import Com.hau.name.webrtc.PeerConnectionManager
import Com.hau.name.webrtc.SignalingClient
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.EglBase
import org.webrtc.RendererCommon
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

    // Kích thước khung hình video thật (độ phân giải màn hình Máy B), khác với kích thước
    // của SurfaceViewRenderer trên màn hình Máy A — cần để tính đúng vùng video hiển thị
    // (trừ phần viền đen letterbox) khi map toạ độ chạm.
    @Volatile private var remoteFrameWidth: Int = 0
    @Volatile private var remoteFrameHeight: Int = 0

    // Chiều cao navigation bar (3 nút ≡ ○ ↩) của Máy A — event.y từ touch được tính từ
    // góc trên-trái màn hình vật lý (bao gồm vùng nav bar), nhưng view layout bị Android
    // đẩy lên trên nav bar theo insets → tọa độ chạm bị lệch xuống đúng bằng navBarHeight.
    // Đọc qua WindowInsets (API 23+) thay vì Resources.getDimensionPixelSize vì giá trị
    // Resources có thể sai trên máy dùng gesture navigation (nav bar = 0) hoặc tablet.
    @Volatile private var navBarHeight: Int = 0

    // Views
    private lateinit var layoutCodeEntry: android.widget.LinearLayout
    private lateinit var remoteViewContainer: FrameLayout
    private lateinit var editCode: EditText
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        eglBase = EglBase.create()

        // Đọc navigation bar inset sớm nhất có thể — ViewCompat.setOnApplyWindowInsetsListener
        // được gọi lại mỗi khi insets thay đổi (xoay màn hình, bật/tắt gesture nav...) nên
        // navBarHeight luôn phản ánh đúng trạng thái hiện tại.
        val rootView = window.decorView.rootView
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            navBarHeight = navInsets.bottom
            insets
        }

        layoutCodeEntry = findViewById(R.id.layout_code_entry)
        remoteViewContainer = findViewById(R.id.remote_view_container)
        editCode = findViewById(R.id.edit_pairing_code)
        btnConnect = findViewById(R.id.btn_connect)

        // Nút ngắt kết nối trong màn hình điều khiển
        findViewById<Button>(R.id.btn_disconnect).setOnClickListener {
            connectedRoomCode?.let { code ->
                database.child("rooms").child(code).child("status").setValue("ended")
            }
            showCodeEntry()
        }

        // Thêm SurfaceViewRenderer vào container để render video Máy B
        remoteRenderer = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        remoteViewContainer.addView(remoteRenderer)
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteRenderer.init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {}
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                // rotation 90/270 -> chiều rộng/cao thực tế bị hoán đổi khi hiển thị
                if (rotation == 90 || rotation == 270) {
                    remoteFrameWidth = videoHeight
                    remoteFrameHeight = videoWidth
                } else {
                    remoteFrameWidth = videoWidth
                    remoteFrameHeight = videoHeight
                }
            }
        })
        setupTouchHandling()

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

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private val commandsRef get() = connectedRoomCode?.let {
        database.child("rooms").child(it).child("controlCommands")
    }

    private fun setupTouchHandling() {
        remoteRenderer.setOnTouchListener { view, event ->
            val rect = videoRectRatio(view.width, view.height)
                ?: VideoRect(0f, 0f, view.width.toFloat(), view.height.toFloat())

            // event.y được đo từ góc trên-trái màn hình VẬT LÝ (bao gồm navigation bar),
            // nhưng view.height KHÔNG tính navigation bar (bị Android đẩy lên trên insets).
            // → phải trừ đi navBarHeight để event.y và view height cùng hệ quy chiếu.
            // navBarHeight = 0 trên máy dùng gesture navigation (full-screen swipe), nên
            // phép trừ này an toàn cho cả 2 kiểu nav.
            val adjustedY = event.y - navBarHeight

            fun toRatio(px: Float, origin: Float, size: Float) = (px - origin) / size

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    swipeStartX = toRatio(event.x, rect.left, rect.width).coerceIn(0f, 1f)
                    swipeStartY = toRatio(adjustedY, rect.top, rect.height).coerceIn(0f, 1f)
                    swipeStartTime = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val xEnd = toRatio(event.x, rect.left, rect.width).coerceIn(0f, 1f)
                    val yEnd = toRatio(adjustedY, rect.top, rect.height).coerceIn(0f, 1f)
                    val dx = xEnd - swipeStartX
                    val dy = yEnd - swipeStartY
                    val duration = System.currentTimeMillis() - swipeStartTime
                    val distPx = kotlin.math.sqrt(
                        (dx * rect.width) * (dx * rect.width) +
                        (dy * rect.height) * (dy * rect.height)
                    ).toDouble()
                    if (distPx < 20) {
                        sendCommand(mapOf("type" to "tap", "x" to swipeStartX, "y" to swipeStartY))
                    } else {
                        sendCommand(mapOf("type" to "swipe",
                            "x" to swipeStartX, "y" to swipeStartY,
                            "x2" to xEnd, "y2" to yEnd,
                            "duration" to duration.coerceIn(80L, 1500L)))
                    }
                }
            }
            true
        }
    }

    private data class VideoRect(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * Tính vùng hiển thị thật của video (loại trừ viền đen letterbox) bên trong SurfaceViewRenderer,
     * dựa trên tỉ lệ khung hình thật của Máy B (remoteFrameWidth/Height) so với kích thước view.
     * Trả về null nếu chưa nhận được khung hình nào (chưa biết tỉ lệ thật).
     */
    private fun videoRectRatio(viewWidth: Int, viewHeight: Int): VideoRect? {
        val frameW = remoteFrameWidth
        val frameH = remoteFrameHeight
        if (frameW <= 0 || frameH <= 0 || viewWidth <= 0 || viewHeight <= 0) return null

        val videoAspect = frameW.toFloat() / frameH.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        return if (videoAspect > viewAspect) {
            // Video "rộng" hơn view -> lấp đầy chiều rộng, chừa viền đen trên/dưới
            val displayedHeight = viewWidth / videoAspect
            val top = (viewHeight - displayedHeight) / 2f
            VideoRect(left = 0f, top = top, width = viewWidth.toFloat(), height = displayedHeight)
        } else {
            // Video "cao" hơn view -> lấp đầy chiều cao, chừa viền đen trái/phải
            val displayedWidth = viewHeight * videoAspect
            val left = (viewWidth - displayedWidth) / 2f
            VideoRect(left = left, top = 0f, width = displayedWidth, height = viewHeight.toFloat())
        }
    }

    private fun sendCommand(data: Map<String, Any>) {
        // push() thay vì setValue() để tránh replay lệnh cũ khi InputInjectionService
        // reconnect vào Firebase — ChildEventListener chỉ nhận lệnh MỚI thêm vào.
        commandsRef?.push()?.setValue(data)
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Gui lenh chạm thất bại: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Không gửi được lệnh chạm: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
