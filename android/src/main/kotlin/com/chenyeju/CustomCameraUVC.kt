/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chenyeju

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.MultiCameraClient.Companion.CAPTURE_TIMES_OUT_SEC
import com.jiangdg.ausbc.MultiCameraClient.Companion.MAX_NV21_DATA
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.MediaUtils
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.uvc.IButtonCallback
import com.jiangdg.uvc.IFrameCallback
import com.jiangdg.uvc.UVCCamera
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * UVC Camera
 *
 * @author Created by jiangdg on 2023/1/15
 */
class CameraUVC(ctx: Context, device: UsbDevice, private val params: Any?) :
        MultiCameraClient.ICamera(ctx, device) {
    private var mUvcCamera: UVCCamera? = null
    private var mFlashInterface: android.hardware.usb.UsbInterface? = null
    private var mIsFlashOn: Boolean = false
    private val mCameraPreviewSize by lazy { arrayListOf<PreviewSize>() }
    companion object {
        private const val TAG = "CameraUVC"
    }

    // Variabel ML Kit
    private var isDetectingFace = false
    private val faceDetector =
            FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                            .build()
            )

    // Listener untuk mengirim hasil ke Flutter
    interface OnFaceDetectedListener {
        fun onFaceDetected(facesJson: String)
    }
    var faceListener: OnFaceDetectedListener? = null

    // private val frameCallBack = IFrameCallback { frame ->
    //     frame?.apply {
    //         frame.position(0)
    //         val data = ByteArray(capacity())
    //         get(data)
    //         mCameraRequest?.apply {
    //             if (data.size != previewWidth * previewHeight * 3 / 2) {
    //                 return@IFrameCallback
    //             }
    //             // for preview callback
    //             mPreviewDataCbList.forEach { cb ->
    //                 cb?.onPreviewData(
    //                         data,
    //                         previewWidth,
    //                         previewHeight,
    //                         IPreviewDataCallBack.DataFormat.NV21
    //                 )
    //             }
    //             // for image
    //             if (mNV21DataQueue.size >= MAX_NV21_DATA) {
    //                 mNV21DataQueue.removeLast()
    //             }
    //             mNV21DataQueue.offerFirst(data)
    //             // for video
    //             // avoid preview size changed
    //             putVideoData(data)
    //         }
    //     }
    // }

    private val frameCallBack = IFrameCallback { frame ->
        frame?.apply {
            frame.position(0)
            val data = ByteArray(capacity())
            get(data)
            mCameraRequest?.apply {
                // Validasi ukuran frame NV21
                if (data.size != previewWidth * previewHeight * 3 / 2) {
                    return@IFrameCallback
                }

                // --- KODE ASLI BAWAAN LIBRARY (JANGAN DIHAPUS) ---
                // for preview callback
                mPreviewDataCbList.forEach { cb ->
                    cb?.onPreviewData(
                            data,
                            previewWidth,
                            previewHeight,
                            IPreviewDataCallBack.DataFormat.NV21
                    )
                }
                // for image
                if (mNV21DataQueue.size >= MAX_NV21_DATA) {
                    mNV21DataQueue.removeLast()
                }
                mNV21DataQueue.offerFirst(data)
                // for video
                // avoid preview size changed
                putVideoData(data)
                // -------------------------------------------------

                // 👇 ================================================== 👇
                // 👇 --- KODE TAMBAHAN: GOOGLE ML KIT FACE DETECTION --- 👇
                // 👇 ================================================== 👇
                if (!isDetectingFace && faceListener != null) {
                    isDetectingFace = true // Kunci agar frame berikutnya tidak menumpuk
                    try {
                        // 1. Ubah byte array NV21 dari USB menjadi format InputImage ML Kit
                        val image =
                                InputImage.fromByteArray(
                                        data,
                                        previewWidth,
                                        previewHeight,
                                        180,
                                        InputImage.IMAGE_FORMAT_NV21
                                )

                        // 2. Proses gambar menggunakan ML Kit
                        faceDetector
                                .process(image)
                                .addOnSuccessListener { faces ->
                                    // 3. Jika wajah ditemukan, buat format JSON kordinatnya

                                    Logger.d(TAG, "ML KIT FOUND: ${faces.size} faces") 

                                    val jsonArray = JSONArray()
                                    for (face in faces) {
                                        val bounds = face.boundingBox
                                        val jsonObj = JSONObject()
                                        jsonObj.put("left", bounds.left)
                                        jsonObj.put("top", bounds.top)
                                        jsonObj.put("right", bounds.right)
                                        jsonObj.put("bottom", bounds.bottom)
                                        jsonArray.put(jsonObj)
                                    }

                                    // 4. Kirim string JSON ke listener (nanti diteruskan ke
                                    // Flutter)
                                    faceListener?.onFaceDetected(jsonArray.toString())
                                }
                                .addOnFailureListener { e ->
                                    Logger.e(TAG, "ML Kit Face Detection failed", e)
                                }
                                .addOnCompleteListener {
                                    // 5. Buka kunci agar frame selanjutnya bisa diproses
                                    isDetectingFace = false
                                }
                    } catch (e: Exception) {
                        Logger.e(TAG, "ML Kit Exception", e)
                        isDetectingFace = false
                    }
                }
                // 👆 ================================================== 👆
            }
        }
    }

    override fun getAllPreviewSizes(aspectRatio: Double?): MutableList<PreviewSize> {
        val previewSizeList = arrayListOf<PreviewSize>()
        if (mUvcCamera?.supportedSizeList?.isNotEmpty() == true) {
                    mUvcCamera?.supportedSizeList
                } else {
                    mUvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)
                }
                ?.let { sizeList ->
                    if (mCameraPreviewSize.isEmpty()) {
                        mCameraPreviewSize.clear()
                        sizeList.forEach { size ->
                            val width = size.width
                            val height = size.height
                            mCameraPreviewSize.add(PreviewSize(width, height))
                        }
                    }
                    mCameraPreviewSize
                }
                ?.onEach { size ->
                    val width = size.width
                    val height = size.height
                    val ratio = width.toDouble() / height
                    if (aspectRatio == null || aspectRatio == ratio) {
                        previewSizeList.add(PreviewSize(width, height))
                    }
                }
        if (Utils.debugCamera) {
            Logger.i(TAG, "aspect ratio = $aspectRatio, getAllPreviewSizes = $previewSizeList, ")
        }

        return previewSizeList
    }

    override fun <T> openCameraInternal(cameraView: T) {
        if (Utils.isTargetSdkOverP(ctx) && !CameraUtils.hasCameraPermission(ctx)) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Has no CAMERA permission.")
            Logger.e(
                    TAG,
                    "open camera failed, need Manifest.permission.CAMERA permission when targetSdk>=28"
            )
            return
        }
        if (mCtrlBlock == null) {
            closeCamera()
            postStateEvent(ICameraStateCallBack.State.ERROR, "Usb control block can not be null ")
            return
        }
        // 1. create a UVCCamera
        val request = mCameraRequest!!
        try {
            mUvcCamera = UVCCamera().apply { open(mCtrlBlock) }
        } catch (e: Exception) {
            closeCamera()
            postStateEvent(
                    ICameraStateCallBack.State.ERROR,
                    "open camera failed ${e.localizedMessage}"
            )
            Logger.e(TAG, "open camera failed.", e)
        }

        var minFps = 10
        var maxFps = 60
        var frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
        var bandwidthFactor = UVCCamera.DEFAULT_BANDWIDTH

        if (params is Map<*, *>) {
            minFps = (params["minFps"] as? Number)?.toInt() ?: minFps
            maxFps = (params["maxFps"] as? Number)?.toInt() ?: maxFps
            frameFormat = (params["frameFormat"] as? Number)?.toInt() ?: frameFormat
            bandwidthFactor = (params["bandwidthFactor"] as? Number)?.toFloat() ?: bandwidthFactor
        }

        // 2. set preview size and register preview callback
        var previewSize =
                getSuitableSize(request.previewWidth, request.previewHeight).apply {
                    mCameraRequest!!.previewWidth = width
                    mCameraRequest!!.previewHeight = height
                }

        try {
            Logger.i(TAG, "getSuitableSize: $previewSize")
            if (!isPreviewSizeSupported(previewSize)) {
                closeCamera()
                postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                Logger.e(
                        TAG,
                        "open camera failed, preview size($previewSize) unsupported-> ${mUvcCamera?.supportedSizeList}"
                )
                return
            }
            initEncodeProcessor(previewSize.width, previewSize.height)
            // if give custom minFps or maxFps or unsupported preview size
            // this method will fail
            mUvcCamera?.setPreviewSize(
                    previewSize.width,
                    previewSize.height,
                    minFps,
                    maxFps,
                    frameFormat,
                    bandwidthFactor
            )
        } catch (e: Exception) {
            try {
                previewSize =
                        getSuitableSize(request.previewWidth, request.previewHeight).apply {
                            mCameraRequest!!.previewWidth = width
                            mCameraRequest!!.previewHeight = height
                        }
                if (!isPreviewSizeSupported(previewSize)) {
                    postStateEvent(ICameraStateCallBack.State.ERROR, "unsupported preview size")
                    closeCamera()
                    Logger.e(
                            TAG,
                            "open camera failed, preview size($previewSize) unsupported-> ${mUvcCamera?.supportedSizeList}"
                    )
                    return
                }
                Logger.e(TAG, " setPreviewSize failed, try to use yuv format...")
                mUvcCamera?.setPreviewSize(
                        previewSize.width,
                        previewSize.height,
                        minFps,
                        maxFps,
                        UVCCamera.FRAME_FORMAT_YUYV,
                        UVCCamera.DEFAULT_BANDWIDTH
                )
            } catch (e: Exception) {
                closeCamera()
                postStateEvent(ICameraStateCallBack.State.ERROR, "err: ${e.localizedMessage}")
                Logger.e(TAG, " setPreviewSize failed, even using yuv format", e)
                return
            }
        }
        // if not opengl render or opengl render with preview callback
        // there should opened
        if (!isNeedGLESRender ||
                        mCameraRequest!!.isRawPreviewData ||
                        mCameraRequest!!.isCaptureRawImage
        ) {
            mUvcCamera?.setFrameCallback(frameCallBack, UVCCamera.PIXEL_FORMAT_YUV420SP)
        }
        // 3. start preview
        when (cameraView) {
            is Surface -> {
                mUvcCamera?.setPreviewDisplay(cameraView)
            }
            is SurfaceTexture -> {
                mUvcCamera?.setPreviewTexture(cameraView)
            }
            is SurfaceView -> {
                mUvcCamera?.setPreviewDisplay(cameraView.holder)
            }
            is TextureView -> {
                mUvcCamera?.setPreviewTexture(cameraView.surfaceTexture)
            }
            else -> {
                throw IllegalStateException(
                        "Only support Surface or SurfaceTexture or SurfaceView or TextureView or GLSurfaceView--$cameraView"
                )
            }
        }
        mUvcCamera?.autoFocus = true
        mUvcCamera?.autoWhiteBlance = true
        mUvcCamera?.startPreview()
        mUvcCamera?.updateCameraParams()
        isPreviewed = true
        postStateEvent(ICameraStateCallBack.State.OPENED)

        forceResetHardware()

        if (Utils.debugCamera) {
            Logger.i(TAG, " start preview, name = ${device.deviceName}, preview=$previewSize")
        }
    }

    override fun closeCameraInternal() {

        if (mIsFlashOn) {
            Logger.d(TAG, "Flash is still ON. Forcing OFF before close...")
            try {
                val dataOff = byteArrayOf(0x00, 0x00)
                mCtrlBlock?.connection?.controlTransfer(0x21, 0x01, 0x0100, 0x0200, dataOff, 2, 200)
                mIsFlashOn = false // Reset penanda
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to turn off flash before closing", e)
            }
        }

        // try {
        //     mFlashInterface?.let { intf ->
        //         mCtrlBlock?.connection?.releaseInterface(intf)
        //         mFlashInterface = null
        //         Logger.d(TAG, "Flash interface released safely before closing.")
        //     }
        // } catch (e: Exception) {
        //     Logger.e(TAG, "Error releasing flash interface", e)
        // }
        postStateEvent(ICameraStateCallBack.State.CLOSED)
        isPreviewed = false
        releaseEncodeProcessor()
        mUvcCamera?.destroy()
        mUvcCamera = null
        if (Utils.debugCamera) {
            Logger.i(TAG, " stop preview, name = ${device.deviceName}")
        }
    }

    override fun captureImageInternal(savePath: String?, callback: ICaptureCallBack) {
        mSaveImageExecutor.submit {
            if (!CameraUtils.hasStoragePermission(ctx)) {
                mMainHandler.post { callback.onError("have no storage permission") }
                Logger.e(TAG, "open camera failed, have no storage permission")
                return@submit
            }
            if (!isPreviewed) {
                mMainHandler.post { callback.onError("camera not previewing") }
                Logger.i(TAG, "captureImageInternal failed, camera not previewing")
                return@submit
            }
            val data = mNV21DataQueue.pollFirst(CAPTURE_TIMES_OUT_SEC, TimeUnit.SECONDS)
            if (data == null) {
                mMainHandler.post { callback.onError("Times out") }
                Logger.i(TAG, "captureImageInternal failed, times out.")
                return@submit
            }
            mMainHandler.post { callback.onBegin() }
            val date = mDateFormat.format(System.currentTimeMillis())
            val title = savePath ?: "IMG_UVC_$date"
            val displayName = savePath ?: "$title.jpg"
            val path = savePath ?: "$mCameraDir/$displayName"
            val location = Utils.getGpsLocation(ctx)
            val width = mCameraRequest!!.previewWidth
            val height = mCameraRequest!!.previewHeight
            val ret = MediaUtils.saveYuv2Jpeg(path, data, width, height)
            if (!ret) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                mMainHandler.post { callback.onError("save yuv to jpeg failed.") }
                Logger.w(TAG, "save yuv to jpeg failed.")
                return@submit
            }
            val values = ContentValues()
            values.put(MediaStore.Images.ImageColumns.TITLE, title)
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, displayName)
            values.put(MediaStore.Images.ImageColumns.DATA, path)
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, date)
            ctx.contentResolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            mMainHandler.post { callback.onComplete(path) }
            if (Utils.debugCamera) {
                Logger.i(TAG, "captureImageInternal save path = $path")
            }
        }
    }

    /**
     * Is mic supported
     *
     * @return true camera support mic
     */
    fun isMicSupported() = CameraUtils.isCameraContainsMic(this.device)

    /**
     * Send camera command
     *
     * This method cannot be verified, please use it with caution
     */
    fun sendCameraCommand(command: Int) {
        mCameraHandler?.post { mUvcCamera?.sendCommand(command) }
    }

    /**
     * Set auto focus
     *
     * @param enable true enable auto focus
     */
    fun setAutoFocus(enable: Boolean) {
        mUvcCamera?.autoFocus = enable
    }

    /**
     * Get auto focus
     *
     * @return true enable auto focus
     */
    fun getAutoFocus() = mUvcCamera?.autoFocus

    /** Reset auto focus */
    fun resetAutoFocus() {
        mUvcCamera?.resetFocus()
    }

    /**
     * Set auto white balance
     *
     * @param autoWhiteBalance true enable auto white balance
     */
    fun setAutoWhiteBalance(autoWhiteBalance: Boolean) {
        mUvcCamera?.autoWhiteBlance = autoWhiteBalance
    }

    /**
     * Get auto white balance
     *
     * @return true enable auto white balance
     */
    fun getAutoWhiteBalance() = mUvcCamera?.autoWhiteBlance

    /**
     * Set zoom
     *
     * @param zoom zoom value, 0 means reset
     */
    fun setZoom(zoom: Int) {
        mUvcCamera?.zoom = zoom
    }

    /** Get zoom */
    fun getZoom() = mUvcCamera?.zoom

    /** Reset zoom */
    fun resetZoom() {
        mUvcCamera?.resetZoom()
    }

    /**
     * Set gain
     *
     * @param gain gain value, 0 means reset
     */
    fun setGain(gain: Int) {
        mUvcCamera?.gain = gain
    }

    /** Get gain */
    fun getGain() = mUvcCamera?.gain

    /** Reset gain */
    fun resetGain() {
        mUvcCamera?.resetGain()
    }

    /**
     * Set gamma
     *
     * @param gamma gamma value, 0 means reset
     */
    fun setGamma(gamma: Int) {
        mUvcCamera?.gamma = gamma
    }

    /** Get gamma */
    fun getGamma() = mUvcCamera?.gamma

    /** Reset gamma */
    fun resetGamma() {
        mUvcCamera?.resetGamma()
    }

    /**
     * Set brightness
     *
     * @param brightness brightness value, 0 means reset
     */
    fun setBrightness(brightness: Int) {
        mUvcCamera?.brightness = brightness
    }

    /** Get brightness */
    fun getBrightness() = mUvcCamera?.brightness

    /** Reset brightnes */
    fun resetBrightness() {
        mUvcCamera?.resetBrightness()
    }

    /**
     * Set contrast
     *
     * @param contrast contrast value, 0 means reset
     */
    fun setContrast(contrast: Int) {
        mUvcCamera?.contrast = contrast
    }

    /** Get contrast */
    fun getContrast() = mUvcCamera?.contrast

    /** Reset contrast */
    fun resetContrast() {
        mUvcCamera?.resetContrast()
    }

    /**
     * Set sharpness
     *
     * @param sharpness sharpness value, 0 means reset
     */
    fun setSharpness(sharpness: Int) {
        mUvcCamera?.sharpness = sharpness
    }

    /** Get sharpness */
    fun getSharpness() = mUvcCamera?.sharpness

    /** Reset sharpness */
    fun resetSharpness() {
        mUvcCamera?.resetSharpness()
    }

    /// 设置硬件按钮回调
    fun setButtonCallback(callback: IButtonCallback?) {
        mUvcCamera?.setButtonCallback(callback)
    }

    /**
     * Set saturation
     *
     * @param saturation saturation value, 0 means reset
     */
    fun setSaturation(saturation: Int) {
        mUvcCamera?.saturation = saturation
    }

    /** Get saturation */
    fun getSaturation() = mUvcCamera?.saturation

    /** Reset saturation */
    fun resetSaturation() {
        mUvcCamera?.resetSaturation()
    }

    /**
     * Set hue
     *
     * @param hue hue value, 0 means reset
     */
    fun setHue(hue: Int) {
        mUvcCamera?.hue = hue
    }

    /**
     * Set Flashlight (via Backlight Compensation mapping) Unit 2, Selector 1, 2-byte payload
     * @param isOn true to turn on, false to turn off
     */
    fun setFlashlight(isOn: Boolean) {
        val uvc = mUvcCamera ?: return

        mCameraHandler?.post {
            try {
                // 1. Bobol variabel mNativePtr (Pointer C++)
                val ptrField = UVCCamera::class.java.getDeclaredField("mNativePtr")
                ptrField.isAccessible = true
                val nativePtr = ptrField.getLong(uvc)

                if (nativePtr == 0L) return@post

                // 2. Bobol fungsi rahasia nativeSetBacklightComp di C++
                val method =
                        UVCCamera::class.java.getDeclaredMethod(
                                "nativeSetBacklightComp",
                                Long::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                        )
                method.isAccessible = true

                // 3. Eksekusi fungsi C++ secara langsung! (1 = ON, 0 = OFF)
                val value = if (isOn) 1 else 0
                val ret = method.invoke(null, nativePtr, value) as Int

                // 4. Update status
                mIsFlashOn = isOn
                Logger.d(TAG, "Native Flashlight set to $isOn, Result: $ret")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to toggle Native Flashlight via Reflection", e)
            }
        }
    }

    // --- TAMBAHKAN FUNGSI INI ---
    private fun forceResetHardware() {
        mCameraHandler?.post {
            try {
                Logger.d(TAG, "Forcing hardware reset from Native...")

                // 1. Matikan Flash (Panggil fungsi ninja reflection yang kita buat)
                setFlashlight(false)

                // 2. Reset Filter ke default (50 atau nilai tengah)
                mUvcCamera?.brightness = 50
                mUvcCamera?.contrast = 50
                mUvcCamera?.saturation = 50

                Logger.d(TAG, "Hardware reset complete.")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to reset hardware on startup", e)
            }
        }
    }

    /** Get hue */
    fun getHue() = mUvcCamera?.hue

    /** Reset saturation */
    fun resetHue() {
        mUvcCamera?.resetHue()
    }
}
