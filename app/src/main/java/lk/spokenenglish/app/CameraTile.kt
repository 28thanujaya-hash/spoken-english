package lk.spokenenglish.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView

/**
 * His own picture in the video call (front camera), like a real video call. Shown only on the phone:
 * nothing from the camera is sent anywhere (the AI never sees it). "Hide" turns it off.
 */
class CameraTile(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    var on = false
        private set

    init { surfaceTextureListener = this }

    fun startCamera() {
        on = true
        if (isAvailable) open()
    }

    fun stopCamera() {
        on = false
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        thread?.quitSafely(); thread = null; handler = null
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!on || device != null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: cm.cameraIdList.firstOrNull() ?: return
        thread = HandlerThread("camera").also { it.start() }
        handler = Handler(thread!!.looper)
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; preview(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); device = null }
            }, handler)
        }
    }

    private fun preview(d: CameraDevice) {
        val tex = surfaceTexture ?: return
        tex.setDefaultBufferSize(640, 480)
        val surface = Surface(tex)
        runCatching {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
                    runCatching { s.setRepeatingRequest(req, null, handler) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, handler)
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (on) open() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stopCamera(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
