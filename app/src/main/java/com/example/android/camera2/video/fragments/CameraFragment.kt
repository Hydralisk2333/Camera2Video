/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Range
import android.view.*
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.video.ClientThread
import com.example.android.camera2.video.GlobalConfig.MAX_COUNT
import com.example.android.camera2.video.R
import com.example.android.camera2.video.ReceiveCallback
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraFragment : Fragment() {

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private lateinit var outputFile: File

    private var clientThread: ClientThread? = null
    private var receiveCallback: ReceiveCallback? = null
    private var personId: Int = 0
    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */

    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        outputFile = createFile(requireContext(), ".mp4", "null")
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /** Saves the video recording */
    private lateinit var recorder: MediaRecorder

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */

    private lateinit var animationTask: Runnable

    /** Where the camera preview is displayed */
    private lateinit var viewFinder: AutoFitSurfaceView
    private lateinit var buttonConnect: Button

    /** Overlay on top of the camera preview */
//    private lateinit var overlay: View

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    private var curCount: Int = 0
    private var leftCount: Int = MAX_COUNT

    private lateinit var sentArray: ArrayList<String>
    private lateinit var condArray: ArrayList<String>
    private lateinit var lastFile: File

    private lateinit var focusRect: Rect

//    private lateinit var hint_view: TextView

    private fun initSelfArray(context: Context) {
        val fileNameId: Int = R.raw.condense
        val sentenceId: Int = R.raw.sentence
        condArray = loadFromFile(context, fileNameId)
        sentArray = loadFromFile(context, sentenceId)

    }

    /** Requests used for preview only in the [CameraCaptureSession] */
    // 这两个request都是CaptureRequest.Builder类型的
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(viewFinder.holder.surface)
//            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            addTarget(viewFinder.holder.surface)
            addTarget(recorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
        }.build()
    }

    private val focusRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(viewFinder.holder.surface)
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(focusRect, 1000)))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(focusRect, 1000)))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            Toast.makeText(context, "on touch", Toast.LENGTH_SHORT).show()
        }.build()
    }

    /**
     * 获取点击区域
     * @param x：手指触摸点x坐标
     * @param y: 手指触摸点y坐标
     */

    private fun getFocusRect( x: Int, y: Int): Rect {

        val outSize = Rect()
        activity?.getWindowManager()?.getDefaultDisplay()?.getRectSize(outSize)
        val left = outSize.left
        val top = outSize.top
        val right = outSize.right
        val bottom = outSize.bottom
        Log.d(TAG, "left = $left,top = $top,right = $right,bottom = $bottom")
        //left = 0,top = 0,right = 1440,bottom = 2768
        val tWidth = right - left
        val tHeight = bottom - top

        val screenW = Math.min(tWidth, tHeight)
        val screenH = Math.max(tWidth, tHeight)

        //因为获取的SCALER_CROP_REGION是宽大于高的，也就是默认横屏模式，竖屏模式需要对调width和height
        val previewSize = getPreviewOutputSize(
                viewFinder.display, characteristics, SurfaceHolder::class.java)
        var realPreviewWidth = previewSize.height
        var realPreviewHeight = previewSize.width

        //根据预览像素与拍照最大像素的比例，调整手指点击的对焦区域的位置
        val focusX = realPreviewWidth.toFloat() / screenW * x
        val focusY = realPreviewHeight.toFloat() / screenH * y

        //获取SCALER_CROP_REGION，也就是拍照最大像素的Rect
        val totalPicSize = previewRequest.get(CaptureRequest.SCALER_CROP_REGION)

        //计算出摄像头剪裁区域偏移量
        val cutDx = (totalPicSize?.height()?.minus(previewSize.height))?.div(2)

        //我们默认使用10dp的大小，也就是默认的对焦区域长宽是10dp，这个数值可以根据需要调节
        val width = 50
        val height = 50

        //返回最终对焦区域Rect
        return Rect(focusY.toInt(), focusX.toInt() + cutDx!!, (focusY + height).toInt(), (focusX + cutDx + width).toInt())
    }
    /**
     * 获取点击区域
     * @param x：手指触摸点x坐标
     * @param y: 手指触摸点y坐标
     */

    private var recordingStartMillis: Long = 0L

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //想要socket连接成功就必须加这几行代码
        if (Build.VERSION.SDK_INT > 9) {
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)
        }

        viewFinder = view.findViewById(R.id.view_finder)
        buttonConnect = view.findViewById(R.id.button_connect)

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    //Int) = Unit 是个什么鬼
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
//                viewFinder.post { initializeCamera() }
                initializeCamera()

            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (args.fps > 0) setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(viewFinder.holder.surface, recorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        session.setRepeatingRequest(previewRequest, null, cameraHandler)

        //新加的一些变量
        initSelfArray(requireContext())

        viewFinder.setOnTouchListener { view, motionEvent ->
            val curX = motionEvent.getX()
            val curY = motionEvent.getY()
            focusRect = getFocusRect(curX.toInt(), curY.toInt())
            session.capture(focusRequest, null, cameraHandler)
            true
        }

        buttonConnect.setOnClickListener {
            if (clientThread == null){
                val ip:String = eip?.text.toString()
                val sport:String = eport?.text.toString()
                val port:Int = sport.toInt()
                clientThread = ClientThread(ip, port, receiveCallback)
                clientThread!!.start()
            }
        }
        receiveCallback = ReceiveCallback {
            when (it) {
                "connect" -> hint_view?.setText("当前状态:已连接")
                "disconnect" -> {
                    hint_view?.setText("当前状态:未连接")
                }
                "start" -> {
                    val curSent:String = getFileName();
                    outputFile = createFile(requireContext(), ".mp4", curSent)
                    lastFile = outputFile
                    recorder = createRecorder(recorderSurface)

                    // Prevents screen rotation during the video recording
                    requireActivity().requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    // Start recording repeating requests, which will stop the ongoing preview
                    //  repeating requests without having to explicitly call `session.stopRepeating`
                    session.setRepeatingRequest(recordRequest, null, cameraHandler)

                    // Finalizes recorder setup and starts recording
                    recorder.apply {
                        // Sets output orientation based on current sensor value at start time
                        relativeOrientation.value?.let { setOrientationHint(it) }
                        recorder.prepare()
                        start()
                    }
                    recordingStartMillis = System.currentTimeMillis()
                    Log.d(TAG, "Recording started")

                    // Starts recording animation
//                    overlay.post(animationTask)
                    overlay.post {
                        overlay.visibility = View.VISIBLE
                    }
                    lip_rect.post{
                        lip_rect.background = context?.resources?.getDrawable(R.drawable.edge_start)
                    }
                }
                "end" -> {
                    // Unlocks screen rotation after recording finished
                    requireActivity().requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
//                    val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
//                    if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
//                        delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
//                    }

                    Log.d(TAG, "Recording stopped. Output file: $outputFile")
                    recorder.stop()
                    recorder.release()

                    overlay.post {
                        overlay.visibility = View.INVISIBLE
                    }
                    lip_rect.post{
                        lip_rect.background = context?.resources?.getDrawable(R.drawable.edge_stop)
                    }
                    changeNum(1)
                }
                "back" -> {
                    changeNum(0)
                    val curSent:String = getFileName()
                    lastFile = createFile(requireContext(), ".mp4", curSent)
                    if (lastFile.exists()){
                        lastFile.delete()
                    }
                }
                "ahead" -> {
                    changeNum(1)
                }
                else -> {
                    val re = Regex("[0-9]+")
                    val isNum: Boolean = re.matches(it)
                    if (isNum) {
                        personId = it.toInt()
                    }
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder.release()
        recorderSurface.release()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String, videoName:String): File {

            val fileName:String
            if (videoName != "null"){
                fileName = videoName + extension
                val dirNames = fileName.split('/')
                val dirName = dirNames.get(0);

                val dirFile = File(context.getExternalFilesDir(""), dirName)
                if (!dirFile.mkdirs()){
                    Log.e("create dir error", "error")
                }
            }
            else{
                fileName = videoName + extension
            }
            val file = File(context.getExternalFilesDir(""), fileName)
            return file
        }

        private fun loadFromFile(context: Context, fileId: Int): ArrayList<String> {
            val inputStream = context.resources.openRawResource(fileId)
            var result:ArrayList<String> = ArrayList()

            inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
//                TedSDKLog.d(TAG, "sms: $it")
                    if (!it.isEmpty()) {
                        result.add(it)
                    }
                }
            }
            print(result)
            return result
        }
    }

    fun getFileName():String {
        val fileName = "${personId}/${condArray.get(curCount)}_${leftCount}"
        return fileName
    }

    fun changeNum(flag: Int) {
        if (flag > 0) {
            if (curCount >= sentArray.size) {
                return
            }
            if (leftCount == 1) {
                curCount++
                leftCount = MAX_COUNT
            } else {
                leftCount--
            }
        } else {
            if (leftCount == MAX_COUNT) {
                if (curCount > 0) {
                    curCount--
                    leftCount = 1
                }
            } else {
                leftCount++
            }
        }
    }

}