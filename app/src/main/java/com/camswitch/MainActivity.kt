package com.camswitch

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.camswitch.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    private var isVideoMode = false
    private var isRecording = false
    private var isMerging = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isSwitching = false

    private val segments = mutableListOf<File>()
    private var segmentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private var dotAnimator: ObjectAnimator? = null

    private val chronoRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val min = recordingSeconds / 60
            val sec = recordingSeconds % 60
            binding.tvDuration.text = String.format("%02d:%02d", min, sec)
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        File(filesDir, "segments").mkdirs()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)

        setupUI()
    }

    private fun setupUI() {
        binding.btnCapture.setOnClickListener {
            if (isMerging) return@setOnClickListener
            if (isVideoMode) {
                if (isRecording) stopRecordingAndMerge() else startNewRecording()
            } else {
                takePhoto()
            }
        }
        binding.btnSwitch.setOnClickListener {
            if (isMerging) return@setOnClickListener
            switchCamera()
        }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.tabPhoto.setOnClickListener { setMode(false) }
        binding.tabVideo.setOnClickListener { setMode(true) }
    }

    private fun setMode(video: Boolean) {
        if (isRecording || isMerging) return
        isVideoMode = video
        binding.tabPhoto.apply {
            setTextColor(if (!video) getColor(android.R.color.black) else getColor(android.R.color.darker_gray))
            setBackgroundResource(if (!video) R.drawable.tab_selected else android.R.color.transparent)
        }
        binding.tabVideo.apply {
            setTextColor(if (video) getColor(android.R.color.black) else getColor(android.R.color.darker_gray))
            setBackgroundResource(if (video) R.drawable.tab_selected else android.R.color.transparent)
        }
        binding.btnCapture.setImageResource(if (video) R.drawable.ic_record else R.drawable.ic_shutter)
        startCamera()
    }

    // ─── Caméra ──────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cp = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        try {
            cp.unbindAll()
            if (isVideoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                cp.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } else {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cp.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            }
        } catch (e: Exception) {
            showToast("Erreur caméra : ${e.message}")
        }
    }

    // ─── Switch caméra ───────────────────────────────────────────────────────

    private fun switchCamera() {
        if (isSwitching) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        binding.btnSwitch.animate()
            .rotationBy(180f).setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()

        if (isRecording) {
            isSwitching = true
            binding.btnSwitch.isEnabled = false
            // Stopper le segment courant — onSegmentFinalized() sera appelé dans Finalize
            currentRecording?.stop()
            currentRecording = null
        } else {
            bindCameraUseCases()
        }
    }

    // ─── Enregistrement segments ──────────────────────────────────────────────

    private fun startNewRecording() {
        segments.clear()
        segmentIndex = 0
        recordingSeconds = 0
        startSegment()
    }

    private fun startSegment() {
        val vc = videoCapture ?: run {
            handler.postDelayed({ startSegment() }, 150)
            return
        }
        segmentIndex++
        val segFile = File(filesDir, "segments/seg_$segmentIndex.mp4")
        if (segFile.exists()) segFile.delete()
        segments.add(segFile)

        val outputOptions = FileOutputOptions.Builder(segFile).build()

        currentRecording = vc.output
            .prepareRecording(this, outputOptions)
            .apply { withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this), Consumer { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (!isRecording) {
                            isRecording = true
                            runOnUiThread { onRecordingStarted() }
                        }
                        if (isSwitching) {
                            isSwitching = false
                            runOnUiThread { binding.btnSwitch.isEnabled = true }
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            showToast("Erreur segment ${event.error}")
                        }
                        if (isSwitching) {
                            runOnUiThread { onSegmentFinalized() }
                        }
                    }
                }
            })
    }

    private fun onSegmentFinalized() {
        bindCameraUseCases()
        handler.postDelayed({ startSegment() }, 350)
    }

    private fun onRecordingStarted() {
        binding.recIndicator.visibility = View.VISIBLE
        binding.tvDuration.visibility = View.VISIBLE
        binding.btnCapture.setImageResource(R.drawable.ic_stop)
        binding.tabPhoto.isEnabled = false
        binding.tabVideo.isEnabled = false
        dotAnimator?.cancel()
        dotAnimator = ObjectAnimator.ofFloat(binding.recDot, "alpha", 1f, 0f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        handler.removeCallbacks(chronoRunnable)
        handler.postDelayed(chronoRunnable, 1000)
    }

    // ─── Stop + Fusion native Android ────────────────────────────────────────

    private fun stopRecordingAndMerge() {
        isSwitching = false
        isRecording = false
        handler.removeCallbacks(chronoRunnable)
        dotAnimator?.cancel()

        currentRecording?.stop()
        currentRecording = null

        isMerging = true
        binding.recIndicator.visibility = View.GONE
        binding.tvDuration.visibility = View.GONE
        binding.tvMerging.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        binding.btnSwitch.isEnabled = false

        // Attendre que le dernier segment soit fermé puis fusionner
        handler.postDelayed({ doMerge() }, 700)
    }

    private fun doMerge() {
        val validSegments = segments.filter { it.exists() && it.length() > 1000 }

        if (validSegments.isEmpty()) {
            runOnUiThread { onMergeComplete(false) }
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val outputFile = File(cacheDir, "CamSwitch_$timestamp.mp4")

        // Fusion dans un thread de fond
        Thread {
            val success = VideoMerger.merge(validSegments, outputFile)
            if (success && outputFile.exists() && outputFile.length() > 0) {
                saveToGallery(outputFile, timestamp)
            } else {
                runOnUiThread { onMergeComplete(false) }
            }
        }.start()
    }

    private fun saveToGallery(file: File, timestamp: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CamSwitch_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CamSwitch")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        var saved = false
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }
                saved = true
            } catch (e: Exception) {
                saved = false
            }
        }

        // Nettoyer
        segments.forEach { try { it.delete() } catch (e: Exception) { } }
        file.delete()

        runOnUiThread { onMergeComplete(saved) }
    }

    private fun onMergeComplete(success: Boolean) {
        isMerging = false
        binding.tvMerging.visibility = View.GONE
        binding.btnCapture.isEnabled = true
        binding.btnSwitch.isEnabled = true
        binding.btnCapture.setImageResource(R.drawable.ic_record)
        binding.tabPhoto.isEnabled = true
        binding.tabVideo.isEnabled = true
        showToast(if (success) "Vidéo sauvegardée !" else "Erreur lors de l'assemblage")
    }

    // ─── Photo ───────────────────────────────────────────────────────────────

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamSwitch")
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()
        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.previewView.alpha = 0f
                    binding.previewView.animate().alpha(1f).setDuration(150).start()
                    showToast("Photo sauvegardée")
                }
                override fun onError(exc: ImageCaptureException) {
                    showToast("Erreur photo : ${exc.message}")
                }
            })
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = if (isVideoMode) "video/*" else "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(Intent.createChooser(intent, "Ouvrir avec"))
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { showToast("Permissions requises"); finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        currentRecording?.stop()
        cameraExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        dotAnimator?.cancel()
    }
}
