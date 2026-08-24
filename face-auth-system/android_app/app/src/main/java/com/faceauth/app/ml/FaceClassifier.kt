package com.faceauth.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite wrapper for the MobileNetV2 binary face classifier.
 *
 * Class mapping (matches flow_from_directory alphabetical order):
 *   output index 0 → class_a (authenticated user)
 *   output index 1 → class_b (target / unauthorised)
 *
 * Class B is flagged only when P(class_b) >= CLASS_B_THRESHOLD (0.85).
 *
 * CRITICAL: bitmapToByteBuffer must call buf.rewind() before returning.
 * Without it the ByteBuffer position stays at the END after all putFloat
 * calls; TFLite reads zero bytes and runs inference on garbage, producing
 * random (usually high class_b) output regardless of the actual face.
 */
class FaceClassifier(context: Context) {

    companion object {
        private const val TAG              = "FaceClassifier"
        const val MODEL_FILE               = "face_classifier.tflite"
        const val INPUT_SIZE               = 224
        const val CLASS_A_IDX              = 0   // change to 1 if class_indices shows class_a:1
        const val CLASS_B_IDX              = 1   // change to 0 if class_indices shows class_b:0
        const val CLASS_B_THRESHOLD        = 0.85f
    }

    data class Result(
        val classAConf : Float,
        val classBConf : Float,
        val isClassA   : Boolean,
        val isClassB   : Boolean,
        val label      : String
    )

    private val interpreter: Interpreter

    init {
        val fd     = context.assets.openFd(MODEL_FILE)
        val stream = FileInputStream(fd.fileDescriptor)
        val buffer = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
        stream.close()

        interpreter = Interpreter(buffer, Interpreter.Options().apply {
            setNumThreads(4)
        })

        Log.d(TAG, "Model loaded | input=${interpreter.getInputTensor(0).shape().toList()}" +
                " output=${interpreter.getOutputTensor(0).shape().toList()}")
    }

    fun classify(bitmap: Bitmap): Result {
        // Always scale to model input size
        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) bitmap
                     else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        val inputBuf = bitmapToByteBuffer(scaled)          // position = 0 after rewind
        if (scaled !== bitmap) scaled.recycle()

        val output = Array(1) { FloatArray(2) }
        interpreter.run(inputBuf, output)                  // reads from position 0

        val confA = output[0][CLASS_A_IDX]
        val confB = output[0][CLASS_B_IDX]

        // Threshold gate: Class B only confirmed above 0.85
        val isB = confB >= CLASS_B_THRESHOLD
        val isA = !isB && (confA > confB)

        val label = when {
            isB  -> "Class B  ${"%.1f".format(confB * 100)}%"
            isA  -> "Class A  ${"%.1f".format(confA * 100)}%"
            else -> "Unknown"
        }

        Log.d(TAG, "idx0(A)=${"%.4f".format(confA)}  idx1(B)=${"%.4f".format(confB)}" +
                "  isA=$isA  isB=$isB")

        return Result(confA, confB, isA, isB, label)
    }

    /**
     * Converts a Bitmap to a float32 ByteBuffer in [0, 1] range.
     * The model's first layer multiplies by 255 and calls
     * mobilenet_v2.preprocess_input internally, so [0,1] is the correct range.
     *
     * IMPORTANT: buf.rewind() at the end resets position to 0 so TFLite
     * reads from the beginning of the data, not from the end.
     */
    private fun bitmapToByteBuffer(bmp: Bitmap): ByteBuffer {
        val buf    = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buf.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255f)   // R
            buf.putFloat(((px shr  8) and 0xFF) / 255f)   // G
            buf.putFloat(( px         and 0xFF) / 255f)   // B
        }

        buf.rewind()   // ← CRITICAL: reset position to 0 before TFLite reads it
        return buf
    }

    fun close() {
        interpreter.close()
    }
}