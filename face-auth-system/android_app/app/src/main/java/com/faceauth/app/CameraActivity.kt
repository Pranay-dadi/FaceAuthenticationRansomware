package com.faceauth.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.faceauth.app.databinding.ActivityCameraBinding
import com.faceauth.app.ml.FaceClassifier
import com.faceauth.app.util.AuthLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live camera screen.
 *
 * Pipeline per frame:
 *  1. ML Kit face detection → confirms a face is present, returns box in
 *     UPRIGHT (post-rotation) coordinate space.
 *  2. Full YUV frame → RGB Bitmap → rotate to upright orientation so it
 *     matches the ML Kit coordinate space.
 *  3. Crop to the detected face (padded ~30% per side) → resize 224×224.
 *     This matches the tight, face-dominant framing the classifier was
 *     trained on (see dataset_generation/generate_synthetic.py).
 *  4. FaceClassifier.classify() → class_a / class_b probabilities
 *  5. 5-frame majority vote → navigate to success or fail screen
 *
 * NOTE: earlier versions of this activity classified the full, unrotated
 * frame directly. That caused two compounding problems:
 *   - Training data was built from tight face crops, but a full camera
 *     frame at normal authentication distance is mostly background —
 *     a domain mismatch that suppressed class_a confidence.
 *   - The raw YUV→Bitmap conversion is in SENSOR orientation, not the
 *     rotated/upright orientation ML Kit reports boxes in, so on
 *     portrait-mode front cameras the classifier was effectively being
 *     fed a sideways image.
 * Both are fixed below: rotate first, then crop to the face box, then
 * classify.
 */
@androidx.camera.core.ExperimentalGetImage
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG            = "CameraActivity"
        private const val BUFFER_SIZE    = 5     // frames before committing decision
        private const val MIN_FACE_SIZE  = 0.10f
        private const val FACE_CROP_PAD  = 0.30f // padding fraction added to each side of the face box
    }

    private lateinit var binding    : ActivityCameraBinding
    private lateinit var cameraExec : ExecutorService
    private lateinit var classifier : FaceClassifier

    @Volatile private var analysing    = false
    @Volatile private var decisionMade = false

    private val frameBuffer = ArrayDeque<FaceClassifier.Result>()

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build()
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding     = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        classifier  = FaceClassifier(this)
        cameraExec  = Executors.newSingleThreadExecutor()
        startCamera()
        binding.btnCancel.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExec.shutdown()
        classifier.close()
        faceDetector.close()
    }

    // ── Camera setup ───────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview  = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(cameraExec, ::analyseFrame) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                Log.d(TAG, "Camera bound successfully")
            }.onFailure { e ->
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Camera failed: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Frame analysis ─────────────────────────────────────────────────────

    private fun analyseFrame(proxy: ImageProxy) {
        if (analysing || decisionMade) {
            proxy.close()
            return
        }
        analysing = true

        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            analysing = false
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage, proxy.imageInfo.rotationDegrees
        )

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces -> onFacesDetected(faces, proxy) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detection error: ${e.message}")
                proxy.close()
                analysing = false
            }
    }

    private fun onFacesDetected(faces: List<Face>, proxy: ImageProxy) {
        // ── No face ───────────────────────────────────────────────────────
        if (faces.isEmpty()) {
            runOnUiThread {
                binding.tvStatus.text     = "No face detected — centre your face"
                binding.tvConfidence.text = ""
                binding.overlay.setData(emptyList(), proxy.width, proxy.height, "", false, 0)
            }
            proxy.close()
            analysing = false
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees

        // ── Convert full YUV frame to RGB Bitmap, then rotate upright ──────
        // After rotation, this bitmap is in the SAME coordinate space as
        // the ML Kit face boxes (which are already reported post-rotation).
        val rawBitmap = yuvToBitmap(proxy)
        if (rawBitmap == null) {
            Log.e(TAG, "YUV→Bitmap conversion failed")
            proxy.close()
            analysing = false
            return
        }
        val fullBitmap = rotateBitmap(rawBitmap, rotation)

        // ── Crop to the largest detected face, padded, then classify ──────
        val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }!!
        val cropRect = padAndClamp(face.boundingBox, fullBitmap.width, fullBitmap.height, FACE_CROP_PAD)

        if (cropRect.width() < 10 || cropRect.height() < 10) {
            // Degenerate box (face right at frame edge) — skip this frame
            fullBitmap.recycle()
            proxy.close()
            analysing = false
            return
        }

        val faceCrop = Bitmap.createBitmap(
            fullBitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()
        )
        fullBitmap.recycle()

        val inputBitmap = Bitmap.createScaledBitmap(
            faceCrop, FaceClassifier.INPUT_SIZE, FaceClassifier.INPUT_SIZE, true
        )
        faceCrop.recycle()

        val result = classifier.classify(inputBitmap)
        inputBitmap.recycle()

        // ── Update UI ─────────────────────────────────────────────────────
        runOnUiThread {
            binding.overlay.setData(
                faces, proxy.width, proxy.height,
                result.label, result.isClassB, rotation
            )
            binding.tvStatus.text = when {
                result.isClassB -> "⚠  Unauthorised face detected"
                result.isClassA -> "✓  Authorised face detected"
                else            -> "Scanning…  (hold still)"
            }
            binding.tvConfidence.text =
                "A: ${"%.1f".format(result.classAConf * 100)}%   " +
                "B: ${"%.1f".format(result.classBConf * 100)}%"
        }

        // ── Accumulate and decide ─────────────────────────────────────────
        frameBuffer.addLast(result)
        if (frameBuffer.size >= BUFFER_SIZE) {
            commitDecision(frameBuffer.toList())
            frameBuffer.clear()
        }

        proxy.close()
        analysing = false
    }

    // ── Decision logic ─────────────────────────────────────────────────────

    private fun commitDecision(buffer: List<FaceClassifier.Result>) {
        val aVotes = buffer.count { it.isClassA }
        val bVotes = buffer.count { it.isClassB }
        Log.d(TAG, "Buffer decision: A=$aVotes  B=$bVotes  of ${buffer.size}")

        when {
            bVotes > buffer.size / 2 -> {
                val best = buffer.filter { it.isClassB }.maxByOrNull { it.classBConf }!!
                decisionMade = true
                AuthLogger.log(this, "INTRUSION_DETECTED", "class_b", best.classBConf)
                runOnUiThread { navigateFail(best.classBConf) }
            }
            aVotes > buffer.size / 2 -> {
                val best = buffer.filter { it.isClassA }.maxByOrNull { it.classAConf }!!
                decisionMade = true
                AuthLogger.log(this, "AUTH_SUCCESS", "class_a", best.classAConf)
                runOnUiThread { navigateSuccess(best.classAConf) }
            }
            else -> {
                // Ambiguous — clear buffer and keep scanning
                frameBuffer.clear()
                Log.d(TAG, "Ambiguous buffer cleared, keep scanning")
            }
        }
    }

    private fun navigateSuccess(conf: Float) {
        startActivity(Intent(this, AuthSuccessActivity::class.java).apply {
            putExtra("conf", conf)
            putExtra("ts",   System.currentTimeMillis())
        })
        finish()
    }

    private fun navigateFail(conf: Float) {
        startActivity(Intent(this, AuthFailActivity::class.java).apply {
            putExtra("conf", conf)
            putExtra("ts",   System.currentTimeMillis())
        })
        finish()
    }

    // ── YUV → Bitmap ───────────────────────────────────────────────────────

    /**
     * Converts a YUV_420_888 ImageProxy to an RGB Bitmap via NV21.
     *
     * The resulting Bitmap is in SENSOR orientation (not yet rotated) —
     * callers must pass it through rotateBitmap() using
     * proxy.imageInfo.rotationDegrees before using it alongside ML Kit
     * face boxes, which are reported in the rotated/upright space.
     *
     * NV21 layout: [Y plane] [V plane] [U plane]
     * uPlane and vPlane may be interleaved in YUV_420_888; we copy them
     * independently to produce strict NV21.
     */
    private fun yuvToBitmap(proxy: ImageProxy): Bitmap? = runCatching {
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yPlane.buffer.get(nv21, 0,         ySize)
        vPlane.buffer.get(nv21, ySize,     vSize)
        uPlane.buffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21,
            proxy.width, proxy.height, null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, proxy.width, proxy.height), 90, out)
        val bytes = out.toByteArray()

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.onFailure { e ->
        Log.e(TAG, "YUV conversion failed: ${e.message}", e)
    }.getOrNull()

    /**
     * Rotates a Bitmap by [degrees] (CameraX's reported rotationDegrees)
     * so it matches the upright orientation ML Kit's face boxes use.
     * No-op (returns the same bitmap) when degrees == 0.
     */
    private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /**
     * Pads a face bounding box by [padFrac] on each side and clamps the
     * result to the image bounds [0,w) x [0,h). Ensures the crop passed
     * to the classifier resembles the framing used in training data
     * (tight, face-dominant, with a small margin) rather than either a
     * pixel-exact face box or the full background-heavy camera frame.
     */
    private fun padAndClamp(box: Rect, w: Int, h: Int, padFrac: Float): Rect {
        val padX = (box.width()  * padFrac).toInt()
        val padY = (box.height() * padFrac).toInt()
        return Rect(
            (box.left   - padX).coerceIn(0, w),
            (box.top    - padY).coerceIn(0, h),
            (box.right  + padX).coerceIn(0, w),
            (box.bottom + padY).coerceIn(0, h)
        )
    }
}