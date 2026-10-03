package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreehandMaskTest {
    @Test fun freehandSelectionKeepsOnlyPixelsInsideTheUserPolygon() {
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val cropped=cropPngSelection(png,100,100,100f,100f,10f,10f,90f,90f,listOf(Offset(10f,10f),Offset(90f,10f),Offset(10f,90f)))!!
        val image=BitmapFactory.decodeByteArray(cropped,0,cropped.size)
        assertEquals(80,image.width);assertEquals(Color.RED,image.getPixel(10,10));assertEquals(0,Color.alpha(image.getPixel(70,70)))
        image.recycle();bitmap.recycle()
    }
}
