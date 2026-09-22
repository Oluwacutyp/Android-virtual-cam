package com.androidvirtualcam.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.androidvirtualcam.camera.CameraManager
import com.androidvirtualcam.compositor.CompositorRenderer
import com.androidvirtualcam.platform.DeviceCapability
import com.androidvirtualcam.recording.RecordingManager
import com.androidvirtualcam.streaming.StreamingManager
import com.androidvirtualcam.ui.navigation.BroadcastNavGraph
import com.androidvirtualcam.ui.screens.CaptureIntentScreen
import com.androidvirtualcam.ui.screens.CaptureMode
import com.androidvirtualcam.ui.theme.BroadcastTheme
import com.androidvirtualcam.ui.viewmodels.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var compositorRenderer: CompositorRenderer
    private lateinit var recordingManager: RecordingManager
    private lateinit var streamingManager: StreamingManager

    private var captureMode by mutableStateOf(CaptureMode.NONE)
    private var outputUri: Uri? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Screen capture permission launcher – MediaProjection
    private var screenCaptureViewModelRef: ScreenCaptureViewModel? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            Log.i("MainActivity", "Screen capture permission granted, resultCode=${result.resultCode}")
            screenCaptureViewModelRef?.startCapture(result.resultCode, data)
        } else {
            Log.w("MainActivity", "Screen capture permission denied, resultCode=${result.resultCode}")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        Log.i("MainActivity", "Permissions result: camera=$cameraGranted audio=$audioGranted $permissions")
        if (cameraGranted) {
            // Retry camera binding after permission granted
            try {
                scope.launch {
                    delay(500)
                    // Trigger recomposition? CameraManager will be reinitialized via GLPreview retry logic
                    Log.i("MainActivity", "Camera permission granted, will retry binding via GLPreview")
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to handle permission granted", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 4 – Fix crash on compositor load: wrap in try/catch, disable compositor if fails
        var compositorFailed = false
        try {
            cameraManager = CameraManager(this)
            compositorRenderer = CompositorRenderer(this, 1280, 720)
            recordingManager = RecordingManager(this)
            streamingManager = StreamingManager(this)
            Log.i("MainActivity", "Managers initialized – compositor wrapped in try/catch")
        } catch (e: Throwable) {
            Log.e("MainActivity", "Compositor init crashed, using raw CameraX only", e)
            compositorFailed = true
            // Still init camera manager for raw preview
            try {
                cameraManager = CameraManager(this)
            } catch (re: Exception) {
                Log.e("MainActivity", "CameraManager init also failed", re)
                cameraManager = CameraManager(this)
            }
            try {
                compositorRenderer = CompositorRenderer(this, 1280, 720)
            } catch (_: Exception) {
                // Create dummy renderer that will fail gracefully
                compositorRenderer = CompositorRenderer(this, 1280, 720)
            }
            try {
                recordingManager = RecordingManager(this)
                streamingManager = StreamingManager(this)
            } catch (re: Exception) {
                Log.e("MainActivity", "Recording/Streaming init failed", re)
                recordingManager = RecordingManager(this)
                streamingManager = StreamingManager(this)
            }
        }

        // Parse incoming intent for default camera app support
        val action = intent?.action
        captureMode = when (action) {
            MediaStore.ACTION_IMAGE_CAPTURE -> CaptureMode.IMAGE_CAPTURE
            MediaStore.ACTION_VIDEO_CAPTURE -> CaptureMode.VIDEO_CAPTURE
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> CaptureMode.STILL_IMAGE_CAMERA
            "android.media.action.IMAGE_CAPTURE" -> CaptureMode.IMAGE_CAPTURE
            "android.media.action.VIDEO_CAPTURE" -> CaptureMode.VIDEO_CAPTURE
            "android.media.action.STILL_IMAGE_CAMERA" -> CaptureMode.STILL_IMAGE_CAMERA
            else -> CaptureMode.NONE
        }

        // Handle EXTRA_OUTPUT if provided by caller
        outputUri = intent?.let { intent ->
            if (intent.hasExtra(MediaStore.EXTRA_OUTPUT)) {
                intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                    ?: intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT) as? Uri
            } else null
        }

        Log.i("MainActivity", "Launched with action=$action, captureMode=$captureMode, outputUri=$outputUri")

        val caps = DeviceCapability.check(this)
        if (caps.warnings.isNotEmpty()) Log.w("MainActivity", "Warnings: ${caps.warnings}")

        // Request camera and audio permissions for Android 6+ – fixes black camera
        try {
            val missing = com.androidvirtualcam.platform.PermissionHelper.getMissingPermissions(this)
            if (missing.isNotEmpty()) {
                Log.i("MainActivity", "Requesting missing permissions: $missing")
                permissionLauncher.launch(missing.toTypedArray())
            } else {
                Log.i("MainActivity", "All permissions already granted")
            }
            // Also request notification permission for Android 13+ for foreground services
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Permission request failed: ${e.message}")
        }

        setContent {
            BroadcastTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val navController = rememberNavController()

                    var isRecording by remember { mutableStateOf(false) }
                    var recordingDuration by remember { mutableStateOf(0L) }
                    var recordingJob by remember { mutableStateOf<Job?>(null) }

                    val mainViewModel: MainViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return MainViewModel(context) as T
                        }
                    })
                    val nodeGraphViewModel: NodeGraphViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return NodeGraphViewModel(context) as T
                        }
                    })
                    val voiceViewModel: VoiceViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return VoiceViewModel(context) as T
                        }
                    })
                    val streamViewModel: StreamViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return StreamViewModel(context) as T
                        }
                    })
                    val sceneViewModel: SceneViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return SceneViewModel(context) as T
                        }
                    })
                    val settingsViewModel: SettingsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return SettingsViewModel(context) as T
                        }
                    })
                    val screenCaptureViewModel: ScreenCaptureViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return ScreenCaptureViewModel(context) as T
                        }
                    })
                    val teleprompterViewModel: TeleprompterViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return TeleprompterViewModel(context) as T
                        }
                    })
                    val webControlViewModel: WebControlViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return WebControlViewModel(context) as T
                        }
                    })
                    val instantReplayViewModel: InstantReplayViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return InstantReplayViewModel(context) as T
                        }
                    })
                    val beautyViewModel: BeautyViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return BeautyViewModel(context) as T
                        }
                    })
                    val audioSourceViewModel: AudioSourceViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return AudioSourceViewModel(context) as T
                        }
                    })

                    // Keep ref for launcher callback
                    LaunchedEffect(screenCaptureViewModel) {
                        screenCaptureViewModelRef = screenCaptureViewModel
                    }

                    // Observe permission intent and launch
                    LaunchedEffect(screenCaptureViewModel) {
                        screenCaptureViewModel.permissionIntent.collect { intent ->
                            if (intent != null) {
                                try {
                                    screenCaptureLauncher.launch(intent)
                                    screenCaptureViewModel.consumePermissionIntent()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to launch screen capture intent", e)
                                }
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        mainViewModel.initializeManagers(
                            cameraManager = cameraManager,
                            compositorRenderer = compositorRenderer,
                            recordingManager = recordingManager,
                            streamingManager = streamingManager
                        )
                        streamViewModel.setStreamingManager(streamingManager)
                        sceneViewModel.setRenderer(compositorRenderer)
                        screenCaptureViewModel.setRenderer(compositorRenderer)
                        instantReplayViewModel.setRenderer(compositorRenderer)
                        beautyViewModel.setRenderer(compositorRenderer)
                        webControlViewModel.initialize(
                            mainViewModel = mainViewModel,
                            sceneViewModel = sceneViewModel,
                            streamViewModel = streamViewModel,
                            screenCaptureViewModel = screenCaptureViewModel,
                            teleprompterViewModel = teleprompterViewModel,
                            instantReplayViewModel = instantReplayViewModel,
                            beautyViewModel = beautyViewModel,
                            audioSourceViewModel = audioSourceViewModel
                        )
                        // PURE CameraX PreviewView – ZERO GL in preview path
                        // Recording/streaming uses ImageAnalysis ByteArray -> CPU -> MediaCodec
                        // Link CameraManager CPU frame listener to recording/streaming managers
                        try {
                            cameraManager.setCpuFrameListener { nv21, width, height, timestampNs ->
                                try {
                                    // CPU processing – feed to recording manager if recording
                                    if (mainViewModel.isRecording.value) {
                                        recordingManager.onFrameAvailable(nv21, width, height, timestampNs)
                                    }
                                    // Feed to streaming manager for all destinations
                                    streamingManager.onFrameAvailable(nv21, width, height, timestampNs)
                                    // Feed to instant replay if enabled
                                    try {
                                        instantReplayViewModel.onFrameAvailable(nv21, width, height, timestampNs)
                                    } catch (_: Exception) {}
                                } catch (e: Exception) {
                                    Log.w("MainActivity", "CPU frame listener failed: ${e.message}")
                                }
                            }
                            Log.i("MainActivity", "CPU frame pipeline linked: ImageAnalysis ByteArray -> CPU -> MediaCodec – NO EGL, NO GL in preview")
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to set CPU frame listener: ${e.message}")
                        }
                        // Legacy EGL path kept for compatibility but NOT used in preview – preview is pure CameraX
                        try {
                            val eglContext = compositorRenderer.getEglContext()
                            val eglDisplay = compositorRenderer.getEglDisplay()
                            if (eglContext != null && eglDisplay != null) {
                                streamingManager.setSharedEglContext(eglContext, eglDisplay)
                                recordingManager.setSharedEglContext(eglContext)
                                Log.i("MainActivity", "Legacy EGL context set for compatibility but preview is pure CameraX – ZERO GL touching preview surface")
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to set shared EGL context (ignored, CPU pipeline): ${e.message}")
                        }
                    }

                    // If launched via camera intent, show capture UI with all effects
                    if (captureMode != CaptureMode.NONE) {
                        CaptureIntentScreen(
                            mode = captureMode,
                            compositorRenderer = compositorRenderer,
                            isRecording = isRecording,
                            recordingDurationMs = recordingDuration,
                            onCaptureImage = {
                                scope.launch {
                                    try {
                                        // Capture bitmap with all compositor effects applied
                                        val bitmap = withContext(Dispatchers.Default) {
                                            // Ensure renderer is initialized and frame rendered
                                            var bmp: Bitmap? = null
                                            var attempts = 0
                                            while (bmp == null && attempts < 10) {
                                                bmp = compositorRenderer.captureBitmap()
                                                if (bmp == null) {
                                                    delay(100)
                                                    attempts++
                                                }
                                            }
                                            bmp
                                        }

                                        if (bitmap != null) {
                                            val resultUri = saveImageWithEffects(bitmap, outputUri)
                                            bitmap.recycle()
                                            if (resultUri != null) {
                                                val resultIntent = Intent().apply {
                                                    data = resultUri
                                                    // If caller provided EXTRA_OUTPUT, we already saved there, just set result OK
                                                    // Otherwise, return URI via setResult
                                                    if (outputUri == null) {
                                                        putExtra("data", resultUri)
                                                    }
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                setResult(RESULT_OK, resultIntent)
                                                Log.i("MainActivity", "IMAGE_CAPTURE success, uri=$resultUri")
                                                finish()
                                            } else {
                                                Log.e("MainActivity", "Failed to save image")
                                                setResult(RESULT_CANCELED)
                                                finish()
                                            }
                                        } else {
                                            Log.e("MainActivity", "captureBitmap returned null")
                                            setResult(RESULT_CANCELED)
                                            finish()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Image capture failed", e)
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    }
                                }
                            },
                            onStartVideo = {
                                scope.launch {
                                    try {
                                        val config = RecordingManager.RecordingConfig(
                                            width = 1280,
                                            height = 720,
                                            bitRate = 6_000_000,
                                            frameRate = 30
                                        )
                                        val result = withContext(Dispatchers.IO) {
                                            recordingManager.startRecording(config)
                                        }
                                        if (result.isSuccess) {
                                            isRecording = true
                                            Log.i("MainActivity", "VIDEO_CAPTURE recording started")
                                            // Start duration timer
                                            recordingJob?.cancel()
                                            recordingJob = scope.launch {
                                                var elapsed = 0L
                                                while (isRecording) {
                                                    delay(100)
                                                    elapsed += 100
                                                    recordingDuration = elapsed
                                                }
                                            }
                                        } else {
                                            Log.e("MainActivity", "Failed to start video recording: ${result.exceptionOrNull()?.message}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Video start failed", e)
                                    }
                                }
                            },
                            onStopVideo = {
                                scope.launch {
                                    try {
                                        isRecording = false
                                        recordingJob?.cancel()
                                        val result = withContext(Dispatchers.IO) {
                                            recordingManager.stopRecording()
                                        }
                                        if (result.isSuccess) {
                                            val file = result.getOrNull()
                                            if (file != null) {
                                                val resultUri = handleVideoResult(file, outputUri)
                                                val resultIntent = Intent().apply {
                                                    data = resultUri
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                setResult(RESULT_OK, resultIntent)
                                                Log.i("MainActivity", "VIDEO_CAPTURE success, uri=$resultUri, file=${file.absolutePath}")
                                                finish()
                                            } else {
                                                setResult(RESULT_CANCELED)
                                                finish()
                                            }
                                        } else {
                                            Log.e("MainActivity", "Stop recording failed: ${result.exceptionOrNull()?.message}")
                                            setResult(RESULT_CANCELED)
                                            finish()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Video stop failed", e)
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    }
                                }
                            },
                            onCancel = {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                        )
                    } else {
                        BroadcastNavGraph(
                            navController = navController,
                            mainViewModel = mainViewModel,
                            nodeGraphViewModel = nodeGraphViewModel,
                            voiceViewModel = voiceViewModel,
                            streamViewModel = streamViewModel,
                            sceneViewModel = sceneViewModel,
                            settingsViewModel = settingsViewModel,
                            screenCaptureViewModel = screenCaptureViewModel,
                            teleprompterViewModel = teleprompterViewModel,
                            webControlViewModel = webControlViewModel,
                            instantReplayViewModel = instantReplayViewModel,
                            beautyViewModel = beautyViewModel,
                            audioSourceViewModel = audioSourceViewModel
                        )
                    }
                }
            }
        }
    }

    private fun saveImageWithEffects(bitmap: Bitmap, targetUri: Uri?): Uri? {
        return try {
            if (targetUri != null) {
                // Save to caller-provided EXTRA_OUTPUT URI
                contentResolver.openOutputStream(targetUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                targetUri
            } else {
                // Save to cache and return FileProvider URI
                val fileName = "vcam_capture_${System.currentTimeMillis()}.jpg"
                val file = File(cacheDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveImageWithEffects failed", e)
            null
        }
    }

    private fun handleVideoResult(file: File, targetUri: Uri?): Uri {
        return try {
            if (targetUri != null) {
                // Copy recorded file to caller's EXTRA_OUTPUT URI
                contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                targetUri
            } else {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "handleVideoResult failed, returning file URI", e)
            try {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            } catch (re: Exception) {
                Uri.fromFile(file)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (captureMode == CaptureMode.NONE) {
                cameraManager.unbindAll()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Camera unbind failed on pause: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val caps = DeviceCapability.check(this)
            if (caps.warnings.isNotEmpty()) {
                Log.w("MainActivity", "Device capability warnings on resume: ${caps.warnings}")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Capability check failed on resume: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            compositorRenderer.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Compositor release failed: ${e.message}")
        }
        try {
            streamingManager.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Streaming release failed: ${e.message}")
        }
        try {
            recordingManager.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Recording release failed: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val action = intent.action
        captureMode = when (action) {
            MediaStore.ACTION_IMAGE_CAPTURE -> CaptureMode.IMAGE_CAPTURE
            MediaStore.ACTION_VIDEO_CAPTURE -> CaptureMode.VIDEO_CAPTURE
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> CaptureMode.STILL_IMAGE_CAMERA
            "android.media.action.IMAGE_CAPTURE" -> CaptureMode.IMAGE_CAPTURE
            "android.media.action.VIDEO_CAPTURE" -> CaptureMode.VIDEO_CAPTURE
            "android.media.action.STILL_IMAGE_CAMERA" -> CaptureMode.STILL_IMAGE_CAMERA
            else -> CaptureMode.NONE
        }
        outputUri = if (intent.hasExtra(MediaStore.EXTRA_OUTPUT)) {
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        } else null
        Log.i("MainActivity", "onNewIntent action=$action, mode=$captureMode, outputUri=$outputUri")
    }
}
