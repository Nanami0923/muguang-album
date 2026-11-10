package com.muguang.album.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.analysis.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineModelsTest {
    @Test fun bundledRuntimesRunOfflineOnSyntheticImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val file = File(context.cacheDir, "synthetic-model-check.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val category = EfficientNetClassifier(context).classify(bitmap)
            assertTrue(category in setOf("REAL", "ART", "UNKNOWN"))
            val people = EfficientDetPersonDetector(context).detect(bitmap)
            assertTrue(people.all { it.region.x in 0f..1f && it.region.y in 0f..1f })
            assertTrue(BundledFaceDetector().detect(bitmap).isEmpty())
            assertNull(SpectralResidualLocator().locate(bitmap))
            AnimeDetector(context,false).use { detector -> assertTrue(detector.detect(bitmap).all { it.region.width>0 && it.region.x+it.region.width<=1.000001f }) }
            AnimeDetector(context,true).use { detector -> assertTrue(detector.detect(bitmap).all { it.region.height>0 && it.region.y+it.region.height<=1.000001f }) }
            LearnedSubjectLocator(context).use { locator -> locator.locate(bitmap)?.let { assertTrue(it.width>0 && it.height>0) } }
            val result = OfflinePhotoAnalyzer(context).analyze(android.net.Uri.fromFile(file).toString())
            assertEquals("DONE", result.taskState)
            assertEquals(320, result.orientedWidth)
            assertEquals(240, result.orientedHeight)
            assertEquals(64,result.fingerprint.length)
        } finally { bitmap.recycle(); file.delete() }
    }
}
