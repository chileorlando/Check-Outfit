package com.example.virtualfittingroom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlayImageView: ImageView
    
    private var poseLandmarker: PoseLandmarker? = null
    private var currentOutfit: Bitmap? = null
    private val outfits = mapOf(
        "tshirt" to R.drawable.tshirt,
        "dress" to R.drawable.dress,
        "jacket" to R.drawable.jacket
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.previewView)
        overlayImageView = findViewById(R.id.overlayImageView)
        
        // Setup buttons
        findViewById<Button>(R.id.btnTshirt).setOnClickListener { setOutfit("tshirt") }
        findViewById<Button>(R.id.btnDress).setOnClickListener { setOutfit("dress") }
        findViewById<Button>(R.id.btnJacket).setOnClickListener { setOutfit("jacket") }
        findViewById<Button>(R.id.btnRemove).setOnClickListener { removeOutfit() }
        
        // Initialize MediaPipe Pose Landmarker
        setupPoseLandmarker()
        
        // Request camera permissions and start camera
        if (PermissionHelper.hasCameraPermission(this)) {
            startCamera()
        } else {
            PermissionHelper.requestCameraPermission(this)
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }
    
    private fun setupPoseLandmarker() {
        try {
            val baseOptions = com.google.mediapipe.tasks.core.BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .build()
            
            val options = com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::processPoseResult)
                .build()
            
            poseLandmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            Log.e("VirtualFittingRoom", "Error setting up PoseLandmarker: ${e.message}")
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("VirtualFittingRoom", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        
        val bitmap = Bitmap.createBitmap(
            mediaImage.width,
            mediaImage.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            copyPixelsFromBuffer(mediaImage.planes[0].buffer)
        }
        
        // Rotate bitmap if needed
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        
        // Convert to MP Image
        val mpImage = com.google.mediapipe.tasks.vision.core.Image(
            rotatedBitmap,
            imageProxy.imageInfo.rotationDegrees
        )
        
        poseLandmarker?.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
        
        imageProxy.close()
    }
    
    private fun processPoseResult(result: PoseLandmarkerResult?, input: com.google.mediapipe.tasks.vision.core.Image) {
        if (result == null || result.landmarks().isEmpty() || currentOutfit == null) {
            runOnUiThread { overlayImageView.setImageBitmap(null) }
            return
        }
        
        val landmarks = result.landmarks()[0] // Get first detected person
        
        // Get key points
        val leftShoulder = landmarks[11]  // LEFT_SHOULDER
        val rightShoulder = landmarks[12] // RIGHT_SHOULDER
        val leftHip = landmarks[23]       // LEFT_HIP
        
        // Calculate body dimensions
        val shoulderWidth = Math.abs(leftShoulder.x() - rightShoulder.x()) * input.width
        val torsoLength = Math.abs(leftShoulder.y() - leftHip.y()) * input.height
        
        // Scale outfit
        val outfit = currentOutfit!!
        val aspectRatio = outfit.width.toFloat() / outfit.height
        val newWidth = (shoulderWidth * 1.5f).toInt()
        val newHeight = (newWidth / aspectRatio).toInt()
        
        val scaledOutfit = Bitmap.createScaledBitmap(outfit, newWidth, newHeight, true)
        
        // Position the outfit
        val xPos = (rightShoulder.x() * input.width - shoulderWidth * 0.25f).toInt()
        val yPos = (leftShoulder.y() * input.height).toInt()
        
        // Create overlay bitmap
        val overlayBitmap = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(overlayBitmap)
        
        // Draw outfit
        canvas.drawBitmap(scaledOutfit, xPos.toFloat(), yPos.toFloat(), null)
        
        runOnUiThread {
            overlayImageView.setImageBitmap(overlayBitmap)
        }
    }
    
    private fun setOutfit(outfitName: String) {
        val resId = outfits[outfitName] ?: return
        currentOutfit = BitmapFactory.decodeResource(resources, resId)
    }
    
    private fun removeOutfit() {
        currentOutfit = null
        runOnUiThread { overlayImageView.setImageBitmap(null) }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarker?.close()
    }
}
