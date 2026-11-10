package com.muguang.album.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.analysis.decodeOriented
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExifCoordinatesTest {
    @Test fun allEightOrientationsMatchDimensionsAndCornerMapping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir,"synthetic-exif.jpg")
        // Original corner order TL, TR, BL, BR: red, green, blue, yellow.
        val mappings = listOf(listOf(0,1,2,3),listOf(1,0,3,2),listOf(3,2,1,0),listOf(2,3,0,1),
            listOf(0,2,1,3),listOf(2,0,3,1),listOf(3,1,2,0),listOf(1,3,0,2))
        val colors = intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        try { for (orientation in 1..8) {
            val raw = Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            for(y in 0 until 40) for(x in 0 until 80) raw.setPixel(x,y,colors[(if(y>=20)2 else 0)+(if(x>=40)1 else 0)])
            file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG,100,it) }; raw.recycle()
            ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
            val image = decodeOriented(context,android.net.Uri.fromFile(file).toString(),100)
            try {
                assertEquals(if(orientation>=5)40 else 80,image.width)
                assertEquals(if(orientation>=5)80 else 40,image.height)
                val b = image.bitmap
                val actual = listOf(b.getPixel(5,5),b.getPixel(b.width-6,5),b.getPixel(5,b.height-6),b.getPixel(b.width-6,b.height-6))
                actual.forEachIndexed { index,pixel ->
                    val expected = colors[mappings[orientation-1][index]]
                    assertTrue("orientation $orientation corner $index", kotlin.math.abs(Color.red(expected)-Color.red(pixel)) <= 8)
                    assertTrue(kotlin.math.abs(Color.green(expected)-Color.green(pixel)) <= 8)
                    assertTrue(kotlin.math.abs(Color.blue(expected)-Color.blue(pixel)) <= 8)
                }
            } finally { image.bitmap.recycle() }
        } } finally { file.delete() }
    }
}
