package com.muguang.album.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.muguang.album.core.*
import java.nio.FloatBuffer
import kotlin.math.*

private class OnnxModel(context:Context, asset:String):AutoCloseable {
    private val environment=OrtEnvironment.getEnvironment()
    private val options=OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }
    private val session=environment.createSession(context.assets.open("models/$asset").use { it.readBytes() },options)
    override fun close() { session.close(); options.close() }
    fun run(data:FloatArray, width:Int, height:Int, consume:(FloatBuffer,LongArray)->Unit) {
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),longArrayOf(1,3,height.toLong(),width.toLong())).use { input ->
            session.run(mapOf(session.inputNames.first() to input),setOf(session.outputNames.first())).use { result ->
                val output=result[0] as OnnxTensor
                consume(output.floatBuffer,output.info.shape)
            }
        }
    }
}

/** YOLOv8 single-class exports, [1,5,N], with explicit 640-square letterboxing. */
class AnimeDetector(context:Context, face:Boolean):AutoCloseable {
    private val model=OnnxModel(context,if(face) "anime_face_v1_4_n.onnx" else "anime_person_v1_1_n.onnx")
    private val confidence=if(face) .278f else .327f
    private val nmsThreshold=if(face) .7f else .5f
    override fun close() = model.close()
    fun detect(image:Bitmap):List<Detection> {
        val size=640
        val ratio=min(size.toFloat()/image.width,size.toFloat()/image.height)
        val width=(image.width*ratio).roundToInt().coerceIn(1,size)
        val height=(image.height*ratio).roundToInt().coerceIn(1,size)
        val left=(size-width)/2; val top=(size-height)/2
        val padded=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(padded); canvas.drawColor(Color.rgb(114,114,114))
            canvas.drawBitmap(image,null,android.graphics.Rect(left,top,left+width,top+height),Paint(Paint.FILTER_BITMAP_FLAG))
            val data=rgbTensor(padded)
            val candidates=mutableListOf<Detection>()
            model.run(data,size,size) { output,shape ->
                check(shape.size==3 && shape[1]==5L) { "动漫模型输出格式不匹配" }
                val count=shape[2].toInt()
                for(i in 0 until count) {
                    val score=output[4*count+i]; if(score<confidence) continue
                    val cx=output[i]; val cy=output[count+i]; val w=output[2*count+i]; val h=output[3*count+i]
                    val x0=((cx-w/2-left)/width).coerceIn(0f,1f); val y0=((cy-h/2-top)/height).coerceIn(0f,1f)
                    val x1=((cx+w/2-left)/width).coerceIn(0f,1f); val y1=((cy+h/2-top)/height).coerceIn(0f,1f)
                    if(x1>x0 && y1>y0) candidates+=Detection(SafeRegion(x0,y0,x1-x0,y1-y0),score)
                }
            }
            val accepted=mutableListOf<Detection>()
            for(candidate in candidates.sortedByDescending { it.confidence }) {
                if(accepted.none { iou(it.region,candidate.region)>nmsThreshold }) accepted+=candidate
                if(accepted.size>=50) break
            }
            return accepted
        } finally { padded.recycle() }
    }
}

private fun iou(a:SafeRegion,b:SafeRegion):Float {
    val intersection=(min(a.x+a.width,b.x+b.width)-max(a.x,b.x)).coerceAtLeast(0f)*
        (min(a.y+a.height,b.y+b.height)-max(a.y,b.y)).coerceAtLeast(0f)
    return intersection/(a.width*a.height+b.width*b.height-intersection).coerceAtLeast(.000001f)
}

private fun rgbTensor(bitmap:Bitmap,standardize:Boolean=false):FloatArray {
    val count=bitmap.width*bitmap.height; val pixels=IntArray(count)
    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    val data=FloatArray(count*3)
    val mean=floatArrayOf(.485f,.456f,.406f); val std=floatArrayOf(.229f,.224f,.225f)
    val maximum=if(standardize) pixels.maxOf { maxOf((it shr 16) and 255,(it shr 8) and 255,it and 255) }.coerceAtLeast(1).toFloat() else 255f
    for(i in pixels.indices) for(c in 0..2) {
        val value=((pixels[i] shr (16-c*8)) and 255)/maximum
        data[c*count+i]=if(standardize) (value-mean[c])/std[c] else value
    }
    return data
}

class LearnedSubjectLocator(context:Context):SubjectLocator,AutoCloseable {
    private val model=OnnxModel(context,"u2netp.onnx")
    override fun close() = model.close()
    override fun locate(image:Bitmap):SafeRegion? {
        val size=320; val resized=Bitmap.createScaledBitmap(image,size,size,true)
        try {
            val data=rgbTensor(resized,true)
            var region:SafeRegion?=null
            model.run(data,size,size) { output,_ ->
                val values=FloatArray(size*size); output.get(values)
                val low=values.minOrNull()!!; val high=values.maxOrNull()!!
                if(!low.isFinite() || !high.isFinite() || high-low<.05f || high<.4f) return@run
                val normalized=FloatArray(values.size) { (values[it]-low)/(high-low) }
                val seen=BooleanArray(values.size); val queue=IntArray(values.size)
                data class Component(val region:SafeRegion,val mass:Float,val count:Int)
                val components=mutableListOf<Component>()
                for(start in normalized.indices) {
                    if(seen[start] || normalized[start]<.55f) continue
                    var head=0; var tail=1; queue[0]=start; seen[start]=true
                    var minX=size; var minY=size; var maxX=0; var maxY=0; var mass=0f
                    while(head<tail) {
                        val index=queue[head++]; val x=index%size; val y=index/size
                        minX=min(minX,x); minY=min(minY,y); maxX=max(maxX,x); maxY=max(maxY,y); mass+=normalized[index]
                        for(neighbor in intArrayOf(if(x>0) index-1 else -1,if(x<size-1) index+1 else -1,if(y>0) index-size else -1,if(y<size-1) index+size else -1)) {
                            if(neighbor>=0 && !seen[neighbor] && normalized[neighbor]>=.55f) { seen[neighbor]=true; queue[tail++]=neighbor }
                        }
                    }
                    if(tail>=size*size*.008f) components+=Component(SafeRegion(minX.toFloat()/size,minY.toFloat()/size,(maxX-minX+1f)/size,(maxY-minY+1f)/size),mass,tail)
                }
                val ranked=components.sortedByDescending { it.mass }; val best=ranked.firstOrNull() ?: return@run
                if(best.count>size*size*.75f || (ranked.size>1 && ranked[1].mass>best.mass*.6f)) return@run
                region=best.region
            }
            return region
        } finally { if(resized!==image) resized.recycle() }
    }
}
