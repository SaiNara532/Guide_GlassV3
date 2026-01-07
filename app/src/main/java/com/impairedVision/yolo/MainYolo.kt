package com.impairedVision.yolo

import android.graphics.*
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.impairedVision.R
import com.impairedVision.tts.SpeechHelper // [NEW] Import your helper
import com.google.mlkit.vision.common.InputImage // [NEW] ML Kit
import com.google.mlkit.vision.text.TextRecognition // [NEW] ML Kit
import com.google.mlkit.vision.text.latin.TextRecognizerOptions // [NEW] ML Kit
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.util.concurrent.Executors // [NEW] For background thread
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class MainYolo : ComponentActivity() {

    companion object {
        private const val IMG_SIZE = 640
        private const val CONF_THRESH = 0.50f // [TWEAKED] Increased slightly to reduce spam
        private const val IOU_THRESH = 0.45f
        private const val TAG = "YOLO"
        private const val REQ_CAMERA = 11
    }

    private lateinit var tflite: Interpreter
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var labels: List<String> = emptyList()

    // [NEW] - Helpers for Intelligence
    private lateinit var speechHelper: SpeechHelper
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // [NEW] - Background Executor to keep UI smooth
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // [NEW] - Counter to control how often we read text
    private var frameCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yolo)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)

        // [NEW] Initialize Speech
        speechHelper = SpeechHelper(this)

        previewView.post {
            val rect = RectF(0f, 0f, previewView.width.toFloat(), previewView.height.toFloat())
            overlay.setImageInfo(rect, IMG_SIZE)
        }

        labels = try {
            FileUtil.loadLabels(this, "coco_labels.txt")
        } catch (_: Exception) {
            emptyList()
        }

        val mapped = FileUtil.loadMappedFile(this, "yolov8n_float32.tflite")
        tflite = Interpreter(mapped)

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startCamera()
        }
    }

    // [NEW] Clean up resources
    override fun onDestroy() {
        super.onDestroy()
        speechHelper.shutdown()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        perms: Array<out String>,
        res: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(code, perms as Array<String>, res)
        if (code == REQ_CAMERA && res.isNotEmpty()
            && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // [CHANGED] Use cameraExecutor (background thread) instead of MainExecutor
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    // Convert to Bitmap once
                    val bmp = imageProxyToBitmap(imageProxy)

                    // 1. Run YOLO (Visual Object Detection)
                    runYoloOnBitmap(bmp)

                    // 2. Run OCR (Text Reading) - Every 30 frames (approx 1 sec)
                    if (frameCounter % 30 == 0) {
                        runOcrOnBitmap(bmp)
                    }
                    frameCounter++

                } catch (e: Exception) {
                    Log.e(TAG, "Analyzer error", e)
                } finally {
                    imageProxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // [NEW] The OCR Function
    private fun runOcrOnBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                // Only speak if text is substantial (more than 2 chars)
                if (text.isNotBlank() && text.length > 2) {
                    Log.d(TAG, "OCR Found: $text")
                    // "Reading..." gives the user context that it's text, not an object
                    speechHelper.speak("Reading: $text")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR Failed", e)
            }
    }

    private fun runYoloOnBitmap(src: Bitmap) {
        val lb = letterbox(src, IMG_SIZE, IMG_SIZE).first
        val input = bitmapToNHWC(lb)

        val outShape = tflite.getOutputTensor(0).shape()
        val output: Any = when {
            outShape contentEquals intArrayOf(1, 8400, 84) -> Array(1) { Array(8400) { FloatArray(84) } }
            outShape contentEquals intArrayOf(1, 84, 8400) -> Array(1) { Array(84) { FloatArray(8400) } }
            else -> throw IllegalStateException("Unexpected output shape: ${outShape.contentToString()}")
        }

        tflite.run(input, output)

        val dets = decodeDetections(output, outShape, CONF_THRESH)
        val kept: List<Detection> = nonMaxSuppression(dets, IOU_THRESH)

        runOnUiThread {
            overlay.setDetections(kept)
            overlay.postInvalidateOnAnimation()
        }

        if (kept.isNotEmpty()) {
            val topDet = kept.maxByOrNull { it.score }
            topDet?.let { d ->
                val name = if (d.cls in labels.indices) labels[d.cls] else "Object"

                // Pass to SpeechHelper (it handles the cooldown so we don't spam)
                speechHelper.speak(name)
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val bytes = out.toByteArray()
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining(); val uSize = u.remaining(); val vSize = v.remaining()
        val data = ByteArray(ySize + uSize + vSize)
        y.get(data, 0, ySize)
        v.get(data, ySize, vSize)
        u.get(data, ySize + vSize, uSize)
        return data
    }

    // --- Helper functions ---

    private fun letterbox(src: Bitmap, newW: Int, newH: Int): Pair<Bitmap, FloatArray> {
        val scale = min(newW / src.width.toFloat(), newH / src.height.toFloat())
        val rw = (src.width * scale).toInt()
        val rh = (src.height * scale).toInt()
        val offX = (newW - rw) / 2f
        val offY = (newH - rh) / 2f
        val out = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, null, RectF(offX, offY, offX + rw, offY + rh), Paint(Paint.ANTI_ALIAS_FLAG))
        return out to floatArrayOf(offX, offY, scale)
    }

    private fun bitmapToNHWC(bm: Bitmap): Array<Array<Array<FloatArray>>> {
        val w = bm.width; val h = bm.height
        val out = Array(1) { Array(h) { Array(w) { FloatArray(3) } } }
        val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[i++]
            out[0][y][x][0] = ((p ushr 16) and 0xFF) / 255f
            out[0][y][x][1] = ((p ushr 8) and 0xFF) / 255f
            out[0][y][x][2] = (p and 0xFF) / 255f
        }
        return out
    }

    private fun decodeDetections(output: Any, outShape: IntArray, conf: Float): MutableList<Detection> {
        val dets = mutableListOf<Detection>()
        if (outShape contentEquals intArrayOf(1, 8400, 84)) {
            val preds = (output as Array<Array<FloatArray>>)[0]
            for (p in preds) addIfConfident(p, dets, conf)
        } else {
            val ch = (output as Array<Array<FloatArray>>)[0]
            val n = ch[0].size
            for (i in 0 until n) {
                val p = FloatArray(84) { c -> ch[c][i] }
                addIfConfident(p, dets, conf)
            }
        }
        return dets
    }

    private fun addIfConfident(p: FloatArray, dets: MutableList<Detection>, conf: Float) {
        val obj = sigmoid(p[4])
        if (obj <= 1e-6) return
        var best = 0f; var cls = 0
        for (c in 4 until 84) {
            val sc = obj * sigmoid(p[c])
            if (sc > best) { best = sc; cls = c - 4 }
        }
        if (best < conf) return

        val cx = p[0]; val cy = p[1]; val w = p[2]; val h = p[3]
        val (cxPx, cyPx, wPx, hPx) =
            if (cx <= 1f && cy <= 1f && w <= 1f && h <= 1f)
                Quad(cx * IMG_SIZE, cy * IMG_SIZE, w * IMG_SIZE, h * IMG_SIZE)
            else
                Quad(cx, cy, w, h)

        val box = xywh2xyxy(cxPx, cyPx, wPx, hPx)
        clamp(box, IMG_SIZE.toFloat(), IMG_SIZE.toFloat())
        dets.add(Detection(box, best, cls))
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private fun nonMaxSuppression(dets: List<Detection>, iouThresh: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val a = sorted.removeAt(0)
            kept.add(a)
            val it = sorted.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (iou(a.box, b.box) > iouThresh) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = max(a.left, b.left); val it = max(a.top, b.top)
        val ir = min(a.right, b.right); val ib = min(a.bottom, b.bottom)
        val iw = max(0f, ir - il); val ih = max(0f, ib - it)
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun xywh2xyxy(cx: Float, cy: Float, w: Float, h: Float) =
        RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

    private fun clamp(r: RectF, w: Float, h: Float) {
        r.left = r.left.coerceIn(0f, w)
        r.top = r.top.coerceIn(0f, h)
        r.right = r.right.coerceIn(0f, w)
        r.bottom = r.bottom.coerceIn(0f, h)
    }

    data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
}
