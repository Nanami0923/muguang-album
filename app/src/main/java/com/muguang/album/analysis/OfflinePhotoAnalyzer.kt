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

data class Detection(val region: SafeRegion, val confidence: Float, val label: Int = 0)
data class MediumScores(val probabilities: List<Double>) {
    val medium: String get() {
        val ranked=probabilities.indices.sortedByDescending { probabilities[it] }
        if(probabilities[ranked[0]]<AnalysisThresholds.CLASS_CONFIDENCE || probabilities[ranked[0]]-probabilities[ranked[1]]<AnalysisThresholds.CLASS_MARGIN) return "UNKNOWN"
        return listOf("ANIME_ILLUSTRATION","REAL","RENDERED")[ranked[0]]
    }
    val coarse: String get() = when {
        probabilities[1]>=.85 && probabilities[1]-(probabilities[0]+probabilities[2])>=.2 -> "REAL"
        probabilities[0]+probabilities[2]>=.85 && probabilities[0]+probabilities[2]-probabilities[1]>=.2 -> "ART"
        else -> "UNKNOWN"
    }
}
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
    override fun classify(image: Bitmap): String = predict(image).coarse
    fun predict(image: Bitmap): MediumScores {
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
        return MediumScores(exp.map { it / sum })
    }
}

class EfficientDetPersonDetector(context: Context) : PersonDetector {
    private val interpreter = model(context, "efficientdet_lite0_int8.tflite")
    override fun detect(image: Bitmap): List<Detection> = detectObjects(image).filter { it.label==0 && it.confidence>=AnalysisThresholds.PERSON_CONFIDENCE }
    fun detectObjects(image: Bitmap): List<Detection> {
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
            if (scores[i] < .4f) return@mapNotNull null
            val top = boxes[i * 4].coerceIn(0f, 1f); val left = boxes[i * 4 + 1].coerceIn(0f, 1f)
            val bottom = boxes[i * 4 + 2].coerceIn(top, 1f); val right = boxes[i * 4 + 3].coerceIn(left, 1f)
            if(right<=left || bottom<=top) return@mapNotNull null
            Detection(SafeRegion(left, top, right - left, bottom - top), scores[i], classes[i].roundToInt())
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
    companion object { const val VERSION = "v030-medium1-real1-anime1-u2netp1-exif1" }
    private val classifier by lazy { EfficientNetClassifier(context) }
    private val person by lazy { EfficientDetPersonDetector(context) }
    private val face by lazy { BundledFaceDetector() }
    private val animePerson by lazy { AnimeDetector(context,false) }
    private val animeFace by lazy { AnimeDetector(context,true) }
    private val learned by lazy { LearnedSubjectLocator(context) }
    private val spectral by lazy { SpectralResidualLocator() }

    fun fingerprint(uri:String):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))!!.use { stream ->
            val buffer=ByteArray(65536)
            while(true) { val count=stream.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun analyze(uri:String):PhotoMetadata {
        val fingerprint=fingerprint(uri)
        val decoded=decodeOriented(context,uri)
        val bitmap=decoded.bitmap
        val disputes=linkedSetOf<String>()
        fun <T> safely(fallback:T, action:()->T):T = try { action() } catch(_:Exception) {
            disputes+="LOCAL_COMPONENT_ERROR"; fallback
        } catch(_:LinkageError) { disputes+="LOCAL_COMPONENT_ERROR"; fallback }
        try {
            val prediction=safely(MediumScores(listOf(.333,.334,.333))) { classifier.predict(bitmap) }
            val medium=prediction.medium
            if(medium=="UNKNOWN") disputes+="MEDIUM_UNCERTAIN"
            val objects=if(medium!="ANIME_ILLUSTRATION") safely(emptyList<Detection>()) { person.detectObjects(bitmap) } else emptyList()
            val realBodies=objects.filter { it.label==0 && it.confidence>=AnalysisThresholds.PERSON_CONFIDENCE }
            val animeBodies=if(medium!="REAL") safely(emptyList<Detection>()) { animePerson.detect(bitmap) } else emptyList()
            val faces=when(medium) {
                "REAL" -> if(realBodies.isNotEmpty()) safely(emptyList<Detection>()) { face.detect(bitmap) } else emptyList()
                "ANIME_ILLUSTRATION", "RENDERED" -> safely(emptyList<Detection>()) { animeFace.detect(bitmap) }
                else -> emptyList()
            }
            val bodies=if(medium=="REAL") realBodies else animeBodies
            val major=bodies.filter { detection ->
                val r=detection.region; val area=r.width*r.height
                if(medium=="REAL") area>=AnalysisThresholds.PERSON_MAIN_AREA ||
                    (area>=AnalysisThresholds.PERSON_CENTRAL_AREA && r.x+r.width/2 in .2f.. .8f && detection.confidence>=.7f)
                else area>=.12f
            }
            val independentFaces=faces.filter { f -> bodies.none { regionOverlap(it.region,f.region)>.5f } }
            val faceCloseup=independentFaces.filter { it.region.width*it.region.height>=.035f }
            val peopleCount=bodies.size+independentFaces.size
            val people=when { medium=="UNKNOWN" -> "UNKNOWN"; peopleCount==0 -> "NONE"; peopleCount==1 -> "SINGLE"; else -> "MULTIPLE" }
            var composition=if(major.isNotEmpty() || faceCloseup.isNotEmpty()) "PERSON" else "OTHER"
            var source="NONE"
            var region:SafeRegion?=null
            var point:FocusPoint?=null
            if(composition=="PERSON") {
                val protected=major.map { it.region }+faceCloseup.map { it.region }
                region=regionUnion(protected)
                val mainFaces=faces.filter { f -> protected.any { regionOverlap(it,f.region)>.5f } }
                val aim=if(mainFaces.isNotEmpty()) regionUnion(mainFaces.map { it.region }) else region
                point=FocusPoint(aim.x+aim.width/2,aim.y+aim.height/2)
                source=if(medium=="REAL") { if(mainFaces.isNotEmpty()) "FACE" else "PERSON" } else { if(mainFaces.isNotEmpty()) "ANIME_FACE" else "ANIME_PERSON" }
            } else {
                region=safely<SafeRegion?>(null) { learned.locate(bitmap) }
                if(region!=null) {
                    val first=region
                    val auxiliary=objects.filter { it.label!=0 }.maxByOrNull { regionOverlap(it.region,first) }
                    val second=if(auxiliary!=null) auxiliary.region else {
                        val matrix=Matrix().apply { setScale(-1f,1f) }
                        val flipped=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                        try { safely<SafeRegion?>(null) { learned.locate(flipped) }?.let { SafeRegion(1-it.x-it.width,it.y,it.width,it.height) } }
                        finally { if(flipped!==bitmap) flipped.recycle() }
                    }
                    if(second==null || !subjectAgreement(first,second,decoded.width,decoded.height)) disputes+="FOCUS_UNSTABLE"
                    point=FocusPoint(first.x+first.width/2,first.y+first.height/2); source="LEARNED_SUBJECT"
                } else disputes+="NO_RELIABLE_SUBJECT"
                // Visual saliency can support location, but cannot decide whether content is scenery.
                safely<SafeRegion?>(null) { spectral.locate(bitmap) }
                if(peopleCount>0 || objects.none { it.label!=0 }) {
                    composition="UNKNOWN"; disputes+="COMPOSITION_UNCERTAIN"
                }
            }
            if(medium=="UNKNOWN" && realBodies.isNotEmpty() && animeBodies.isNotEmpty()) disputes+="MEDIUM_EVIDENCE_CONFLICT"
            val category=categoryFor(medium,composition)
            val needsAi=disputes.isNotEmpty()
            return PhotoMetadata(orientedWidth=decoded.width,orientedHeight=decoded.height,exifOrientation=decoded.orientation,
                autoCategory=if(medium=="REAL") "REAL" else if(medium=="UNKNOWN" && prediction.coarse=="ART") "ART" else category,
                autoSubCategory=category.takeIf { medium=="REAL" },classificationStatus=if(medium=="UNKNOWN") "UNKNOWN" else "DONE",
                autoX=point?.x,autoY=point?.y,regionX=region?.x,regionY=region?.y,regionWidth=region?.width,regionHeight=region?.height,
                focusSource=source,focusStatus=if(point==null) "NEEDS_MANUAL" else "DETECTED",taskState="DONE",modelVersion=VERSION,
                medium=medium,composition=composition,people=people,presentation=if(point==null) "UNRESOLVED" else "SUBJECT_FOCUS",
                fingerprint=fingerprint,disputeReasons=disputes.joinToString(","),aiStatus=if(needsAi) "WAITING_CONFIG" else "NOT_REQUIRED",
                localEvidence="medium="+prediction.probabilities.joinToString(",") { "%.4f".format(java.util.Locale.US,it) }+";bodies=$peopleCount;focus=$source")
        } finally { bitmap.recycle() }
    }
}
