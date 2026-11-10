package com.muguang.album.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.muguang.album.core.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class Detection(val region: SafeRegion, val confidence: Float)
interface ImageClassifier { fun classify(image: Bitmap): String }
interface PersonDetector { fun detect(image: Bitmap): List<Detection> }
interface FaceDetector { fun detect(image: Bitmap): List<Detection> }
interface SubjectLocator { fun locate(image: Bitmap): SafeRegion? }

/** Initial thresholds for M5 sample tuning, not measured accuracy guarantees. */
object AnalysisThresholds {
    const val CLASS_CONFIDENCE = .85
    const val CLASS_MARGIN = .20
    const val PERSON_CONFIDENCE = .55f
    const val PERSON_MAIN_AREA = .045f
    const val PERSON_CENTRAL_AREA = .02f
    const val SALIENCY_TOP_TENTH_MASS = .28
}

data class OrientedImage(val bitmap: Bitmap, val width: Int, val height: Int, val orientation: Int)

/** Decode one sampled bitmap and apply all eight EXIF orientations exactly once. */
fun decodeOriented(context: Context, uri: String, maxSide: Int = 640): OrientedImage {
    val resolver = context.contentResolver
    val parsed = Uri.parse(uri)
    val orientation = resolver.openInputStream(parsed)!!.use {
        try { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: java.io.IOException) { 1 }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(parsed)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片损坏或设备不支持此格式" }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val raw = resolver.openInputStream(parsed)!!.use { BitmapFactory.decodeStream(it, null, options) } ?: error("图片解码失败")
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        }
    }
    val bitmap = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    val swapped = orientation in 5..8
    return OrientedImage(bitmap, if (swapped) bounds.outHeight else bounds.outWidth, if (swapped) bounds.outWidth else bounds.outHeight, orientation)
}

private fun model(context: Context, name: String): Interpreter {
    val bytes = context.assets.open("models/$name").use { it.readBytes() }
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    return Interpreter(buffer, Interpreter.Options().setNumThreads(1))
}

class EfficientNetClassifier(context: Context) : ImageClassifier {
    private val interpreter = model(context, "image_medium_efficientnet_b0_fp32.tflite")
    override fun classify(image: Bitmap): String {
        val resized = Bitmap.createScaledBitmap(image, 224, 224, true)
        val pixels = IntArray(224 * 224); resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
        val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        pixels.forEach { pixel -> for (c in 0..2) input.putFloat((((pixel shr (16 - c * 8)) and 255) / 255f - mean[c]) / std[c]) }
        if (resized !== image) resized.recycle()
        input.rewind()
        val output = Array(1) { FloatArray(3) }; interpreter.run(input, output)
        val logits = output[0]; val peak = logits.maxOrNull()!!
        val exp = logits.map { exp((it - peak).toDouble()) }; val sum = exp.sum()
        val probabilities = exp.map { it / sum }; val index = probabilities.indices.maxBy { probabilities[it] }
        if (probabilities[index] < AnalysisThresholds.CLASS_CONFIDENCE || probabilities.sortedDescending().let { it[0] - it[1] } < AnalysisThresholds.CLASS_MARGIN) return "UNKNOWN"
        return if (index == 1) "REAL" else "ART"
    }
}

class EfficientDetPersonDetector(context: Context) : PersonDetector {
    private val interpreter = model(context, "efficientdet_lite0_int8.tflite")
    override fun detect(image: Bitmap): List<Detection> {
        val inputTensor = interpreter.getInputTensor(0)
        val size = inputTensor.shape()[1]
        val resized = Bitmap.createScaledBitmap(image, size, size, true)
        val pixels = IntArray(size * size); resized.getPixels(pixels, 0, size, 0, 0, size, size)
        val input = ByteBuffer.allocateDirect(size * size * 3)
        pixels.forEach { input.put(((it shr 16) and 255).toByte()); input.put(((it shr 8) and 255).toByte()); input.put((it and 255).toByte()) }
        if (resized !== image) resized.recycle()
        input.rewind()
        val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
            val tensor = interpreter.getOutputTensor(index)
            ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs.mapValues { it.value as Any })
        // Model output contract is pinned and recorded in models/detector_manifest.json.
        val boxes = outputs.getValue(0).apply { rewind() }.asFloatBuffer()
        val classes = outputs.getValue(1).apply { rewind() }.asFloatBuffer()
        val scores = outputs.getValue(2).apply { rewind() }.asFloatBuffer()
        val count = outputs.getValue(3).apply { rewind() }.float.toInt().coerceIn(0, scores.capacity())
        return (0 until count).mapNotNull { i ->
            if (classes[i].roundToInt() != 0 || scores[i] < AnalysisThresholds.PERSON_CONFIDENCE) return@mapNotNull null
            val top = boxes[i * 4].coerceIn(0f, 1f); val left = boxes[i * 4 + 1].coerceIn(0f, 1f)
            val bottom = boxes[i * 4 + 2].coerceIn(top, 1f); val right = boxes[i * 4 + 3].coerceIn(left, 1f)
            Detection(SafeRegion(left, top, right - left, bottom - top), scores[i])
        }
    }
}

object NativeFaces {
    init { System.loadLibrary("album_faces") }
    external fun detect(bgr: ByteArray, width: Int, height: Int): FloatArray
}

class BundledFaceDetector : FaceDetector {
    override fun detect(image: Bitmap): List<Detection> {
        val pixels = IntArray(image.width * image.height); image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val bgr = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { i, pixel -> bgr[i*3] = pixel.toByte(); bgr[i*3+1] = (pixel shr 8).toByte(); bgr[i*3+2] = (pixel shr 16).toByte() }
        return NativeFaces.detect(bgr, image.width, image.height).toList().chunked(5).map {
            val x = it[0].coerceIn(0f, 1f); val y = it[1].coerceIn(0f, 1f)
            Detection(SafeRegion(x, y, it[2].coerceIn(0f, 1f-x), it[3].coerceIn(0f, 1f-y)), it[4])
        }
    }
}

/** OpenCV spectral residual algorithm; expressed with core/imgproc ops since the
 * Android Maven distribution omits the contrib saliency Java wrapper. */
class SpectralResidualLocator : SubjectLocator {
    init { check(OpenCVLoader.initLocal()) { "OpenCV 初始化失败" } }
    override fun locate(image: Bitmap): SafeRegion? {
        val mats = mutableListOf<Mat>()
        fun mat() = Mat().also { mats += it }
        try {
            val rgba = mat(); Utils.bitmapToMat(image, rgba)
            val gray = mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val small = mat(); Imgproc.resize(gray, small, Size(64.0, 64.0)); small.convertTo(small, CvType.CV_32F)
            val mean = MatOfDouble(); val stddev = MatOfDouble()
            try { Core.meanStdDev(small, mean, stddev); if (stddev.toArray()[0] < 2.0) return null }
            finally { mean.release(); stddev.release() }
            val complex = mat(); Core.dft(small, complex, Core.DFT_COMPLEX_OUTPUT, 0)
            val planes = mutableListOf<Mat>(); Core.split(complex, planes); mats += planes
            val magnitude = mat(); val phase = mat(); Core.cartToPolar(planes[0], planes[1], magnitude, phase)
            Core.add(magnitude, Scalar(1e-8), magnitude); Core.log(magnitude, magnitude)
            val average = mat(); Imgproc.blur(magnitude, average, Size(3.0, 3.0))
            Core.subtract(magnitude, average, magnitude); Core.exp(magnitude, magnitude)
            Core.polarToCart(magnitude, phase, planes[0], planes[1]); Core.merge(planes, complex)
            Core.idft(complex, complex, Core.DFT_SCALE, 0); Core.split(complex, planes)
            // split may allocate new native matrices, include all of them in cleanup.
            planes.forEach { if (it !in mats) mats += it }
            Core.magnitude(planes[0], planes[1], magnitude); Core.multiply(magnitude, magnitude, magnitude)
            Imgproc.GaussianBlur(magnitude, magnitude, Size(9.0, 9.0), 2.5)
            val range = Core.minMaxLoc(magnitude)
            if (range.maxVal - range.minVal < 1e-10) return null
            Core.normalize(magnitude, magnitude, 0.0, 1.0, Core.NORM_MINMAX)
            val data = FloatArray(4096); magnitude.get(0, 0, data)
            val total = data.sum().toDouble()
            if (total <= 0 || data.sortedDescending().take(410).sum() / total < AnalysisThresholds.SALIENCY_TOP_TENTH_MASS) return null
            val mask = mat(); Imgproc.threshold(magnitude, mask, .45, 255.0, Imgproc.THRESH_BINARY); mask.convertTo(mask, CvType.CV_8U)
            val labels = mat(); val stats = mat(); val centers = mat()
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centers)
            val best = (1 until count).maxByOrNull { stats.get(it, Imgproc.CC_STAT_AREA)[0] } ?: return null
            val x = stats.get(best, Imgproc.CC_STAT_LEFT)[0].toFloat() / 64
            val y = stats.get(best, Imgproc.CC_STAT_TOP)[0].toFloat() / 64
            val w = stats.get(best, Imgproc.CC_STAT_WIDTH)[0].toFloat() / 64
            val h = stats.get(best, Imgproc.CC_STAT_HEIGHT)[0].toFloat() / 64
            val area = stats.get(best, Imgproc.CC_STAT_AREA)[0] / 4096
            if (area !in .008.. .5 || w * h > .65f || x + w/2 !in .04f.. .96f || y + h/2 !in .04f.. .96f) return null
            return SafeRegion(x, y, w, h)
        } finally { mats.forEach { it.release() } }
    }
}

class OfflinePhotoAnalyzer(private val context: Context) {
    companion object { const val VERSION = "medium-b0-fp32-v1+det-lite0-int8-v1+libface-v1+spectral-v1+exif-v1" }
    private val classifier: ImageClassifier by lazy { EfficientNetClassifier(context) }
    private val person: PersonDetector by lazy { EfficientDetPersonDetector(context) }
    private val face: FaceDetector by lazy { BundledFaceDetector() }
    private val subject: SubjectLocator by lazy { SpectralResidualLocator() }

    fun analyze(uri: String): PhotoMetadata {
        val decoded = decodeOriented(context, uri)
        val bitmap = decoded.bitmap
        try {
            var category = classifier.classify(bitmap)
            var source = "SALIENCY"
            var region: SafeRegion? = null
            if (category == "REAL") {
                val people = person.detect(bitmap).filter { detection ->
                    val r = detection.region; val area = r.width * r.height
                    area >= AnalysisThresholds.PERSON_MAIN_AREA || (area >= AnalysisThresholds.PERSON_CENTRAL_AREA && r.x + r.width/2 in .2f.. .8f && detection.confidence >= .7f)
                }
                category = if (people.isEmpty()) "REAL_OTHER" else "REAL_PERSON"
                if (people.isNotEmpty()) {
                    val bodies = union(people.map { it.region })
                    val faces = face.detect(bitmap).filter { f -> people.any { p ->
                        val cx = f.region.x + f.region.width/2; val cy = f.region.y + f.region.height/2
                        cx in p.region.x..(p.region.x+p.region.width) && cy in p.region.y..(p.region.y+p.region.height)
                    } }
                    region = if (faces.isNotEmpty()) union(faces.map { it.region }) else bodies
                    source = if (faces.isNotEmpty()) "FACE" else "PERSON"
                    // Preserve the major people, even when the aim point is a face.
                    val point = FocusPoint(region.x + region.width/2, region.y + region.height/2)
                    return result(decoded, category, point, bodies, source)
                }
            }
            region = subject.locate(bitmap)
            val point = region?.let { FocusPoint(it.x + it.width/2, it.y + it.height/2) }
            return result(decoded, category, point, region, if (point != null) source else "NONE")
        } finally { bitmap.recycle() }
    }
    private fun result(image: OrientedImage, category: String, point: FocusPoint?, region: SafeRegion?, source: String) = PhotoMetadata(
        orientedWidth = image.width, orientedHeight = image.height, exifOrientation = image.orientation,
        autoCategory = if (category.startsWith("REAL_")) "REAL" else category,
        autoSubCategory = category.takeIf { it.startsWith("REAL_") }, classificationStatus = "DONE", autoX = point?.x, autoY = point?.y,
        regionX = region?.x, regionY = region?.y, regionWidth = region?.width, regionHeight = region?.height,
        focusSource = source, focusStatus = if (point == null) "NEEDS_MANUAL" else "DETECTED", taskState = "DONE", modelVersion = VERSION)
    private fun union(regions: List<SafeRegion>): SafeRegion {
        val x = regions.minOf { it.x }; val y = regions.minOf { it.y }
        return SafeRegion(x, y, regions.maxOf { it.x+it.width } - x, regions.maxOf { it.y+it.height } - y)
    }
}
