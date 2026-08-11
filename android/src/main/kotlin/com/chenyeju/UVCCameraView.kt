package com.chenyeju

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.util.Log // Import untuk log waktu
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import com.chenyeju.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.SettableFuture
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IButtonCallback
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class UVCCameraView(
        private val mContext: Context,
        private val mChannel: MethodChannel,
        private val params: Any?
) : PlatformView, PermissionResultListener, ICameraStateCallBack {
    private var mViewBinding = ActivityMainBinding.inflate(LayoutInflater.from(mContext))
    private var mActivity: Activity? = getActivityFromContext(mContext)
    private var mCameraView: IAspectRatio? = null
    private var mCameraClient: MultiCameraClient? = null
    private val mCameraMap = hashMapOf<Int, MultiCameraClient.ICamera>()
    private var mCurrentCamera: SettableFuture<MultiCameraClient.ICamera>? = null
    private var isCapturingVideoOrAudio: Boolean = false
    private val mRequestPermission: AtomicBoolean by lazy { AtomicBoolean(false) }

    companion object {
        private const val TAG = "CameraView"
    }

    override fun getView(): View {
        return mViewBinding.root
    }

    private fun setCameraERRORState(msg: String? = null) {
        mChannel.invokeMethod("CameraState", "ERROR:$msg")
    }

    fun initCamera() {
        val startInit = System.currentTimeMillis()
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] initCamera: Mulai menyiapkan TextureView")

        checkCameraPermission()
        val cameraView = AspectRatioTextureView(mContext)
        handleTextureView(cameraView)
        mCameraView = cameraView
        cameraView.also { view ->
            mViewBinding.fragmentContainer.apply {
                removeAllViews()
                addView(view, getViewLayoutParams(this))
            }
        }

        val duration = System.currentTimeMillis() - startInit
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] initCamera: Selesai dalam $duration ms")
    }

    fun openUVCCamera() {
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] openUVCCamera: Dipicu dari Flutter")
        checkCameraPermission()
        openCamera()
    }

    override fun dispose() {
        unRegisterMultiCamera()
        mViewBinding.fragmentContainer.removeAllViews()
    }

    override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> handleCameraOpened()
            ICameraStateCallBack.State.CLOSED -> handleCameraClosed()
            ICameraStateCallBack.State.ERROR -> handleCameraError(msg)
        }
        Logger.i(TAG, "------>CameraState: $code")
    }

    private fun handleCameraError(msg: String?) {
        mChannel.invokeMethod("CameraState", "ERROR:$msg")
    }

    private fun handleCameraClosed() {
        mChannel.invokeMethod("CameraState", "CLOSED")
    }

    private fun handleCameraOpened() {
        mChannel.invokeMethod("CameraState", "OPENED")
        setButtonCallback()
    }

    fun registerMultiCamera() {
        mCameraClient =
                MultiCameraClient(
                        view.context,
                        object : IDeviceConnectCallBack {

                            override fun onAttachDev(device: UsbDevice?) {
                                device ?: return

                                // 👇 --- PENJAGA GERBANG USB (GUARD CLAUSE) --- 👇
                                if (device.vendorId != 3141) {
                                    return
                                }
                                // 👆 ------------------------------------------ 👆

                                view.context.let {
                                    if (mCameraMap.containsKey(device.deviceId)) {
                                        return
                                    }
                                    generateCamera(it, device).apply {
                                        mCameraMap[device.deviceId] = this
                                    }
                                    if (mRequestPermission.get()) {
                                        return@let
                                    }
                                    getDefaultCamera()?.apply {
                                        if (vendorId == device.vendorId &&
                                                        productId == device.productId
                                        ) {
                                            Logger.i(
                                                    TAG,
                                                    "default camera pid: $productId, vid: $vendorId"
                                            )
                                            requestPermission(device)
                                        }
                                        return@let
                                    }
                                    requestPermission(device)
                                }
                            }

                            override fun onDetachDec(device: UsbDevice?) {
                                mCameraMap.remove(device?.deviceId)?.apply {
                                    setUsbControlBlock(null)
                                }
                                mRequestPermission.set(false)
                                try {
                                    mCurrentCamera?.cancel(true)
                                    mCurrentCamera = null
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun onConnectDev(
                                    device: UsbDevice?,
                                    ctrlBlock: USBMonitor.UsbControlBlock?
                            ) {
                                device ?: return
                                ctrlBlock ?: return
                                view.context ?: return
                                
                                android.util.Log.d("SONIX_PERF", "[UVCCameraView] onConnectDev: USB Handshake Sukses. Menyiapkan instansiasi...")
                                
                                mCameraMap[device.deviceId]
                                        ?.apply { setUsbControlBlock(ctrlBlock) }
                                        ?.also { camera ->
                                            val startSetup = System.currentTimeMillis()
                                            try {
                                                mCurrentCamera?.cancel(true)
                                                mCurrentCamera = null
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                            mCurrentCamera = SettableFuture()
                                            mCurrentCamera?.set(camera)
                                            
                                            android.util.Log.d("SONIX_PERF", "[UVCCameraView] onConnectDev: Memanggil openCamera(mCameraView)")
                                            openCamera(mCameraView)
                                            
                                            Logger.i(
                                                    TAG,
                                                    "camera connection. pid: ${device.productId}, vid: ${device.vendorId}"
                                            )
                                            val duration = System.currentTimeMillis() - startSetup
                                            android.util.Log.d("SONIX_PERF", "[UVCCameraView] onConnectDev setup took $duration ms")
                                        }
                            }

                            override fun onDisConnectDec(
                                    device: UsbDevice?,
                                    ctrlBlock: USBMonitor.UsbControlBlock?
                            ) {
                                closeCamera()
                                mRequestPermission.set(false)
                            }

                            override fun onCancelDev(device: UsbDevice?) {
                                mRequestPermission.set(false)
                                try {
                                    mCurrentCamera?.cancel(true)
                                    mCurrentCamera = null
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                )
        mCameraClient?.register()
    }

    fun unRegisterMultiCamera() {
        mCameraMap.values.forEach { it.closeCamera() }
        mCameraMap.clear()
        mCameraClient?.unRegister()
        mCameraClient?.destroy()
        mCameraClient = null
    }
    private fun handleTextureView(textureView: TextureView) {
        textureView.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                    ) {
                        registerMultiCamera()
                        checkCamera()
                    }

                    override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                    ) {
                        surfaceSizeChanged(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        unRegisterMultiCamera()
                        return false
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
    }

    private fun checkCamera() {
        if (mCameraClient?.getDeviceList()?.isEmpty() == true) {
            setCameraERRORState("未检测到设备")
        }
    }

    override fun onPermissionResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        if (requestCode == 1230) {
            val index = permissions.indexOf(Manifest.permission.CAMERA)
            if (index >= 0 && grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                registerMultiCamera()
            } else {
                callFlutter("设备权限被拒绝")
                setCameraERRORState(msg = "设备权限被拒绝")
            }
        }
    }
    private fun checkCameraPermission(): Boolean {
        if (mActivity == null) {
            return false
        }
        val hasCameraPermission =
                PermissionChecker.checkSelfPermission(mActivity!!, Manifest.permission.CAMERA)
        val hasStoragePermission =
                PermissionChecker.checkSelfPermission(
                        mActivity!!,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                )

        if (hasCameraPermission != PermissionChecker.PERMISSION_GRANTED ||
                        hasStoragePermission != PermissionChecker.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                    mActivity!!,
                    arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                    1230
            )
            return false
        }
        return true
    }

    private fun callFlutter(msg: String, type: String? = null) {
        val data = HashMap<String, String>()
        data["type"] = type ?: "msg"
        data["msg"] = msg
        mChannel.invokeMethod("callFlutter", data)
    }

    fun getAllPreviewSizes(): String? {
        val previewSizes = getCurrentCamera()?.getAllPreviewSizes()
        if (previewSizes.isNullOrEmpty()) {
            callFlutter("Get camera preview size failed")
            return null
        }
        return Gson().toJson(previewSizes)
    }

    fun updateResolution(arguments: Any?) {
        val map = arguments as HashMap<*, *>
        val width = map["width"] as Int
        val height = map["height"] as Int
        getCurrentCamera()?.updateResolution(width, height)
    }

    fun getCurrentCameraRequestParameters(): String? {
        val size = getCurrentCamera()?.getCameraRequest()
        if (size == null) {
            callFlutter("Get camera info failed")
            return null
        }
        return Gson().toJson(size)
    }

    private fun getActivityFromContext(context: Context?): Activity? {
        if (context == null) {
            return null
        }
        if (context is Activity) {
            return context
        }
        if (context is Application || context is Service) {
            return null
        }
        var c = context
        while (c != null) {
            if (c is ContextWrapper) {
                c = c.baseContext
                if (c is Activity) {
                    return c
                }
            } else {
                return null
            }
        }
        return null
    }

    // --- INSTRUMEN LOG: MENGUKUR DURASI BLOKING FUTURE.GET ---
    private fun getCurrentCamera(): MultiCameraClient.ICamera? {
        val startGet = System.currentTimeMillis()
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] getCurrentCamera: Memanggil mCurrentCamera?.get() (Future bloking)...")
        return try {
            val camera = mCurrentCamera?.get(2, TimeUnit.SECONDS)
            val duration = System.currentTimeMillis() - startGet
            android.util.Log.d("SONIX_PERF", "[UVCCameraView] getCurrentCamera: get() berhasil diselesaikan dalam $duration ms")
            camera
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startGet
            android.util.Log.e("SONIX_PERF", "[UVCCameraView] getCurrentCamera: get() GAGAL/TIMEOUT dalam $duration ms", e)
            e.printStackTrace()
            null
        }
    }

    fun requestPermission(device: UsbDevice?) {
        mRequestPermission.set(true)
        mCameraClient?.requestPermission(device)
    }

    fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        val camera = CameraUVC(ctx, device, params)

        camera.faceListener =
                object : CameraUVC.OnFaceDetectedListener {
                    override fun onFaceDetected(facesJson: String) {
                        Log.d("MLKIT_DEBUG", "Sending to Flutter: $facesJson")
                        Handler(Looper.getMainLooper()).post {
                            mChannel.invokeMethod("onFaceDetected", facesJson)
                        }
                    }
                }

        return camera
    }

    fun getDefaultCamera(): UsbDevice? = null
    fun getDefaultEffect() = getCurrentCamera()?.getDefaultEffect()

    private fun captureImage(callBack: ICaptureCallBack, savePath: String? = null) {
        getCurrentCamera()?.captureImage(callBack, savePath)
    }

    fun captureVideoStop() {
        getCurrentCamera()?.captureVideoStop()
    }
    private fun captureVideoStart(
            callBack: ICaptureCallBack,
            path: String? = null,
            durationInSec: Long = 0L
    ) {
        getCurrentCamera()?.captureVideoStart(callBack, path, durationInSec)
    }

    fun switchCamera(usbDevice: UsbDevice) {
        getCurrentCamera()?.closeCamera()
        mCurrentCamera = null
        requestPermission(usbDevice)
    }

    fun openCamera(st: IAspectRatio? = null) {
        val startOpen = System.currentTimeMillis()
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] openCamera: Memulai penyiapan preview tingkat Native...")

        when (st) {
            is TextureView, is SurfaceView -> {
                st
            }
            else -> {
                null
            }
        }.apply {
            val startGetCam = System.currentTimeMillis()
            val camera = getCurrentCamera()
            val getCamDuration = System.currentTimeMillis() - startGetCam
            android.util.Log.d("SONIX_PERF", "[UVCCameraView] openCamera: getCurrentCamera() selesai dalam $getCamDuration ms (null? ${camera == null})")
            
            camera?.openCamera(this, getCameraRequest())
            camera?.setCameraStateCallBack(this@UVCCameraView)
        }

        val duration = System.currentTimeMillis() - startOpen
        android.util.Log.d("SONIX_PERF", "[UVCCameraView] openCamera: Selesai dilempar ke C++ dalam $duration ms")
    }

    fun closeCamera() {
        getCurrentCamera()?.closeCamera()
    }

    private fun surfaceSizeChanged(surfaceWidth: Int, surfaceHeight: Int) {
        getCurrentCamera()?.setRenderSize(surfaceWidth, surfaceHeight)
    }

    private fun getViewLayoutParams(viewGroup: ViewGroup): ViewGroup.LayoutParams {
        return when (viewGroup) {
            is FrameLayout -> {
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        getGravity()
                )
            }
            is LinearLayout -> {
                LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        .apply { gravity = getGravity() }
            }
            is RelativeLayout -> {
                RelativeLayout.LayoutParams(
                                RelativeLayout.LayoutParams.MATCH_PARENT,
                                RelativeLayout.LayoutParams.MATCH_PARENT
                        )
                        .apply {
                            when (getGravity()) {
                                Gravity.TOP -> {
                                    addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                                }
                                Gravity.BOTTOM -> {
                                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                                }
                                else -> {
                                    addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                                    addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE)
                                }
                            }
                        }
            }
            else ->
                    throw IllegalArgumentException(
                            "Unsupported container view, " +
                                    "you can use FrameLayout or LinearLayout or RelativeLayout"
                    )
        }
    }

    private fun getGravity() = Gravity.CENTER

    private fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
                .setPreviewWidth(640)
                .setPreviewHeight(480)
                .setRenderMode(CameraRequest.RenderMode.NORMAL) // Render mode normal (cepat)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .setAudioSource(CameraRequest.AudioSource.SOURCE_SYS_MIC)
                .setAspectRatioShow(true)
                .setCaptureRawImage(false)
                .setRawPreviewData(true)
                .create()
    }

    private fun setButtonCallback() {
        getCurrentCamera()?.let { camera ->
            if (camera !is CameraUVC) {
                return@let null
            }
            camera.setButtonCallback(
                    IButtonCallback { button, state -> // 拍照按钮被按下
                        if (button == 1 && state == 1) {
                            takePicture(
                                    object : UVCStringCallback {
                                        override fun onSuccess(path: String) {
                                            mChannel.invokeMethod("takePictureSuccess", path)
                                        }

                                        override fun onError(error: String) {
                                            callFlutter("拍照失败：$error", "onError")
                                        }
                                    }
                            )
                        }
                        Logger.i(TAG, "点击了设备按钮：button=$button state=$state")
                    }
            )
        }
    }
    /** Start capture H264 & AAC only */
    fun captureStreamStart() {
        setEncodeDataCallBack()
        getCurrentCamera()?.captureStreamStart()
    }

    fun captureStreamStop() {
        getCurrentCamera()?.captureStreamStop()
    }

    private fun setEncodeDataCallBack() {
        getCurrentCamera()
                ?.setEncodeDataCallBack(
                        object : IEncodeDataCallBack {
                            override fun onEncodeData(
                                    type: IEncodeDataCallBack.DataType,
                                    buffer: ByteBuffer,
                                    offset: Int,
                                    size: Int,
                                    timestamp: Long
                            ) {
                                val data = ByteArray(size)
                                buffer.get(data, offset, size)
                                val args =
                                        hashMapOf<String, Any>(
                                                "type" to type.name,
                                                "data" to data,
                                                "timestamp" to timestamp
                                        )
                                Handler(Looper.getMainLooper()).post {
                                    mChannel.invokeMethod("onEncodeData", args)
                                }
                            }
                        }
                )
    }

    private fun isCameraOpened() = getCurrentCamera()?.isCameraOpened() ?: false

    fun takePicture(callback: UVCStringCallback) {
        if (!isCameraOpened()) {
            callFlutter("摄像头未打开")
            setCameraERRORState("设备未打开")
            return
        }

        val camera = getCurrentCamera()
        if (camera is CameraUVC) {
            camera.takePictureCustom(callback)
        } else {
            callback.onError("Camera is not CameraUVC")
        }
    }

    fun captureVideo(callback: UVCStringCallback) {
        if (isCapturingVideoOrAudio) {
            captureVideoStop()
            return
        }
        if (!isCameraOpened()) {
            callFlutter("摄像头未打开")
            setCameraERRORState("设备未打开")
            return
        }

        captureVideoStart(
                object : ICaptureCallBack {
                    override fun onBegin() {
                        isCapturingVideoOrAudio = true
                        callFlutter("开始录像")
                    }

                    override fun onError(error: String?) {
                        isCapturingVideoOrAudio = false
                        callback.onError(error ?: "captureVideo error")
                    }

                    override fun onComplete(path: String?) {
                        if (path != null) {
                            callback.onSuccess(path)
                            MediaScannerConnection.scanFile(view.context, arrayOf(path), null) {
                                    mPath,
                                    uri ->
                                println("Media scan completed for file: $mPath with uri: $uri")
                            }
                            isCapturingVideoOrAudio = false
                        } else {
                            isCapturingVideoOrAudio = false
                            callback.onError("未能保存视频")
                        }
                    }
                }
        )
    }

    fun setFlashlight(isOn: Boolean) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) {
            camera.setFlashlight(isOn)
        } else {
            Log.e("CameraView", "Current camera is not CameraUVC")
        }
    }

    fun setAutoFocus(enabled: Boolean) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) {
            camera.setAutoFocus(enabled)
        }
    }

    fun setManualFocus(value: Int) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) {
            camera.setManualFocus(value)
        }
    }

    // --- KONTROL BRIGHTNESS ---
    fun setBrightness(brightness: Int) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) camera.setBrightness(brightness)
    }
    fun getBrightness(): Int {
        val camera = getCurrentCamera()
        return if (camera is CameraUVC) camera.getBrightness() ?: 0 else 0
    }

    // --- KONTROL CONTRAST ---
    fun setContrast(contrast: Int) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) camera.setContrast(contrast)
    }
    fun getContrast(): Int {
        val camera = getCurrentCamera()
        return if (camera is CameraUVC) camera.getContrast() ?: 0 else 0
    }

    // --- KONTROL SATURATION ---
    fun setSaturation(saturation: Int) {
        val camera = getCurrentCamera()
        if (camera is CameraUVC) camera.setSaturation(saturation)
    }
    fun getSaturation(): Int {
        val camera = getCurrentCamera()
        return if (camera is CameraUVC) camera.getSaturation() ?: 0 else 0
    }
}