package com.clarklevis.dsh.android.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** SwiftUI HarnessAnimatedBackground 的 Android 对位实现。 */
@Composable
internal fun HarnessAnimatedBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val liquidGlassFrameSignal = LocalDshGlassFrameSignal.current
    val motionEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
    var active by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, motionEnabled) {
        if (!active || !motionEnabled) {
            elapsed = 0f
            return@LaunchedEffect
        }
        val started = System.nanoTime()
        while (true) {
            elapsed = ((System.nanoTime() - started) / 1_000_000_000.0 % 4096.0).toFloat()
            liquidGlassFrameSignal?.longValue = (elapsed * 20f).toLong()
            delay(33)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DshColors.Navy)
            .semantics { hideFromAccessibility() }
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            FluidField(elapsed)
        } else {
            FluidFallback(elapsed)
        }
        TechnicalGrid()
        WhaleParticleField(elapsed, !motionEnabled)
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.58f to DshColors.Navy.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.58f)
                )
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FluidField(time: Float) {
    val brush = remember { HarnessFluidBrush() }
    brush.time = time
    Canvas(Modifier.fillMaxSize()) { drawRect(brush) }
}

@Composable
private fun FluidFallback(time: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val x = size.width * (0.46f + sin(time * 0.075f) * 0.08f)
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF234B79), Color(0xFF101F3B), Color(0xFF010510)),
                center = Offset(x, size.height * 0.28f),
                radius = max(size.width, size.height) * 0.82f
            )
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class HarnessFluidBrush : ShaderBrush() {
    private val runtime = RuntimeShader(HARNESS_FLUID_SHADER)
    var time: Float = 0f

    override fun createShader(size: Size): Shader {
        runtime.setFloatUniform("size", size.width, size.height)
        runtime.setFloatUniform("time", time)
        return runtime
    }
}

@Composable
private fun TechnicalGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 42.dp.toPx()
        var x = -spacing
        while (x <= size.width + spacing) {
            drawLine(Color.White.copy(alpha = 0.043f), Offset(x, 0f), Offset(x, size.height), 0.55.dp.toPx())
            x += spacing
        }
        var y = -spacing
        while (y <= size.height + spacing) {
            drawLine(Color.White.copy(alpha = 0.043f), Offset(0f, y), Offset(size.width, y), 0.55.dp.toPx())
            y += spacing
        }
        val columns = ceil(size.width / spacing).toInt() + 1
        val rows = ceil(size.height / spacing).toInt() + 1
        repeat(rows) { row ->
            repeat(columns) { column ->
                if ((row * 11 + column * 7) % 13 == 0) {
                    drawRect(
                        Color.White.copy(alpha = 0.085f),
                        Offset(column * spacing - 1.1.dp.toPx(), row * spacing - 1.1.dp.toPx()),
                        Size(2.2.dp.toPx(), 2.2.dp.toPx())
                    )
                }
            }
        }
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.56f to Color.Transparent,
                1f to DshColors.Navy
            ),
            blendMode = BlendMode.SrcOver
        )
    }
}

@Composable
private fun WhaleParticleField(time: Float, isStatic: Boolean) {
    val particles = remember { makeWhaleParticles() }
    Canvas(Modifier.fillMaxSize()) {
        if (particles.isEmpty()) return@Canvas
        val square = max(size.width * 1.15f, size.height * 0.48f)
        val center = Offset(size.width * 0.55f, size.height * 0.34f)
        val baseDot = max(1.45.dp.toPx(), square / 220f)
        val t = if (isStatic) 0f else time
        particles.forEach { particle ->
            val tail = smoothstep(0.54f, 0.94f, particle.x)
            val edgeDrift = 0.35f + 0.65f * particle.edge
            val driftX = sin(t * 0.50f + particle.phase * 0.53f) * 2.8f * edgeDrift
            val driftY = cos(t * 0.42f + particle.phase * 0.71f) * 2.6f * edgeDrift
            val tailWave = sin(t * 1.10f - particle.x * 7f) * 7f * tail
            val shimmer = 0.90f + 0.10f * sin(t * 1.5f + particle.x * 15f + particle.y * 9f)
            val point = Offset(
                center.x + (particle.x - 0.5f) * square + driftX,
                center.y + (particle.y - 0.5f) * square + driftY + tailWave
            )
            val dot = baseDot * (0.72f + particle.luminance * 0.58f)
            val alpha = ((0.10f + particle.luminance * 0.38f) * shimmer).coerceIn(0f, 1f) * 0.56f
            drawRoundRect(
                Color(
                    red = (0.73f + 0.15f * particle.light).coerceIn(0f, 1f),
                    green = (0.79f + 0.12f * particle.light).coerceIn(0f, 1f),
                    blue = (0.92f + 0.08f * particle.light).coerceIn(0f, 1f),
                    alpha = alpha
                ),
                topLeft = Offset(point.x - dot / 2f, point.y - dot / 2f),
                size = Size(dot, dot),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(dot * 0.18f)
            )
        }
    }
}

internal data class WhaleParticle(
    val x: Float,
    val y: Float,
    val luminance: Float,
    val edge: Float,
    val phase: Float,
    val light: Float
)

internal fun makeWhaleParticles(sampleSize: Int = 60): List<WhaleParticle> {
    val path = requireNotNull(PathParser.createPathFromPathData(WHALE_PATH))
    val bitmap = createBitmap(sampleSize, sampleSize)
    val scale = minOf(
        sampleSize / WHALE_VIEWPORT_WIDTH,
        sampleSize / WHALE_VIEWPORT_HEIGHT
    )
    val offsetX = (sampleSize - WHALE_VIEWPORT_WIDTH * scale) / 2f
    val offsetY = (sampleSize - WHALE_VIEWPORT_HEIGHT * scale) / 2f
    val fittedPath = android.graphics.Path(path).apply {
        transform(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(offsetX, offsetY)
            }
        )
        fillType = android.graphics.Path.FillType.EVEN_ODD
    }
    AndroidCanvas(bitmap).apply {
        drawColor(AndroidColor.BLACK)
        drawPath(
            fittedPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                style = Paint.Style.FILL
            }
        )
    }
    val pixels = IntArray(sampleSize * sampleSize)
    bitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
    bitmap.recycle()
    val luminance = Array(sampleSize) { y ->
        FloatArray(sampleSize) { x -> AndroidColor.red(pixels[y * sampleSize + x]) / 255f }
    }
    val inside = Array(sampleSize) { y ->
        BooleanArray(sampleSize) { x -> luminance[y][x] > WHALE_LUMINANCE_THRESHOLD }
    }
    fun contains(x: Int, y: Int) = x in 0 until sampleSize && y in 0 until sampleSize && inside[y][x]
    val result = mutableListOf<WhaleParticle>()
    repeat(sampleSize) { y ->
        repeat(sampleSize) { x ->
            if (!inside[y][x]) return@repeat
            var hasNeighbour = false
            for (dy in -2..2) for (dx in -2..2) {
                if ((dx != 0 || dy != 0) && contains(x + dx, y + dy)) hasNeighbour = true
            }
            if (!hasNeighbour) return@repeat
            var missing = 0
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && !contains(x + dx, y + dy)) missing++
            }
            val nx = (x + 0.5f) / sampleSize
            val ny = (y + 0.5f) / sampleSize
            val light = max(0f, 1f - hypot(nx - 0.30f, ny - 0.28f) / 0.92f)
            result += WhaleParticle(
                nx,
                ny,
                luminance[y][x],
                missing / 8f,
                result.size.toFloat(),
                light
            )
        }
    }
    return result
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private const val WHALE_VIEWPORT_WIDTH = 24f
private const val WHALE_VIEWPORT_HEIGHT = 18f
private const val WHALE_LUMINANCE_THRESHOLD = 0.2f

private const val WHALE_PATH = "M22.9168 1.43018C22.6713 1.31018 22.5658 1.53918 22.4223 1.65519C22.3733 1.69269 22.3318 1.74169 22.2903 1.78669C21.9317 2.1697 21.5127 2.42121 20.9657 2.39121C20.1657 2.34621 19.4827 2.59771 18.8787 3.20973C18.7502 2.45521 18.3236 2.0047 17.6746 1.71569C17.3351 1.56568 16.9916 1.41518 16.7536 1.08867C16.5876 .856163 16.5421 .597155 16.4591 .341647C16.4061 .187643 16.3536 .0301382 16.1761 .00363739C15.9836 -.0263635 15.9081 .135141 15.8326 .270145C15.5306 .822162 15.4136 1.43018 15.4251 2.0462C15.4516 3.43174 16.0366 4.53527 17.1991 5.3203C17.3311 5.4103 17.3651 5.5003 17.3236 5.63181C17.2441 5.90231 17.1501 6.16482 17.0671 6.43533C17.0141 6.60784 16.9351 6.64584 16.7501 6.57033C16.1121 6.30383 15.5611 5.90931 15.074 5.4328C14.2475 4.63328 13.5 3.75075 12.568 3.05973C12.349 2.89822 12.13 2.74822 11.9034 2.60522C10.9524 1.68169 12.028 .923165 12.277 .833162C12.5375 .739159 12.3675 .41615 11.5259 .42015C10.6844 .42365 9.91439 .705658 8.93286 1.08117C8.78935 1.13767 8.63835 1.17867 8.48384 1.21267C7.59332 1.04367 6.66829 1.00617 5.70226 1.11517C3.88321 1.31768 2.43016 2.1777 1.36213 3.64575C.0790928 5.4103 -.222916 7.41536 .146595 9.50642C.535106 11.7105 1.66014 13.535 3.38869 14.9616C5.18125 16.4406 7.24581 17.1657 9.60138 17.0266C11.0319 16.9441 12.6245 16.7526 14.421 15.2321C14.874 15.4576 15.3496 15.5476 16.1381 15.6151C16.7456 15.6716 17.3306 15.5851 17.7836 15.4911C18.4931 15.3411 18.4441 14.6841 18.1876 14.5636C16.1081 13.595 16.5646 13.9891 16.1496 13.67C17.2061 12.42 18.8202 10.1979 19.3182 7.17235C19.3672 6.83834 19.4297 6.36783 19.4222 6.09732C19.4182 5.93231 19.4562 5.86831 19.6447 5.84931C20.1657 5.78931 20.6712 5.64681 21.1357 5.3913C22.4833 4.65528 23.0268 3.44624 23.1548 1.9972C23.1738 1.77569 23.1508 1.54668 22.9168 1.43018ZM11.1749 14.4736C9.15936 12.889 8.18184 12.3675 7.77832 12.39C7.40081 12.4125 7.46881 12.8445 7.55182 13.126C7.63882 13.404 7.75182 13.5955 7.91033 13.8396C8.01983 14.0011 8.09533 14.2411 7.80083 14.4216C7.15181 14.8231 6.02327 14.2866 5.97027 14.2601C4.65673 13.4865 3.5587 12.4655 2.78467 11.069C2.03715 9.72493 1.60314 8.28289 1.53164 6.74384C1.51264 6.37233 1.62214 6.24082 1.99215 6.17332C2.47916 6.08332 2.98118 6.06432 3.46769 6.13582C5.52476 6.43633 7.27581 7.35586 8.74385 8.8129C9.58188 9.64243 10.2159 10.634 10.8689 11.6025C11.5634 12.631 12.3105 13.611 13.262 14.4146C13.598 14.6961 13.866 14.9101 14.1225 15.0681C13.349 15.1546 12.058 15.1731 11.1749 14.4746V14.4736ZM12.141 8.25988C12.141 8.09488 12.273 7.96338 12.439 7.96338C12.4765 7.96338 12.5105 7.97088 12.541 7.98188C12.5825 7.99688 12.6205 8.01938 12.6505 8.05338C12.7035 8.10588 12.7335 8.18088 12.7335 8.25988C12.7335 8.42489 12.6015 8.55639 12.4355 8.55639C12.2695 8.55639 12.141 8.42489 12.141 8.25988ZM15.1415 9.79893C14.949 9.87793 14.7565 9.94544 14.5715 9.95294C14.2845 9.96794 13.9715 9.85143 13.8015 9.70893C13.5375 9.48742 13.3485 9.36342 13.2695 8.97691C13.2355 8.8119 13.2545 8.55639 13.2845 8.40989C13.3525 8.09438 13.277 7.89187 13.0545 7.70787C12.8735 7.55786 12.643 7.51636 12.39 7.51636C12.2955 7.51636 12.209 7.47486 12.1445 7.44136C12.039 7.38886 11.9519 7.25735 12.035 7.09585C12.0615 7.04335 12.19 6.91584 12.22 6.89334C12.5635 6.69784 12.9595 6.76184 13.326 6.90834C13.6655 7.04735 13.9225 7.30236 14.292 7.66287C14.6695 8.09838 14.7375 8.21838 14.9525 8.54539C15.1225 8.8009 15.277 9.06341 15.3831 9.36392C15.4471 9.55142 15.3641 9.70493 15.1415 9.79893Z"

private const val HARNESS_FLUID_SHADER = """
uniform float2 size;
uniform float time;
float hash(float2 p){p=fract(p*float2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float noise(float2 p){float2 i=floor(p);float2 f=fract(p);float2 u=f*f*(3.0-2.0*f);return mix(mix(hash(i),hash(i+float2(1,0)),u.x),mix(hash(i+float2(0,1)),hash(i+1.0),u.x),u.y);}
float fbm(float2 p){float v=0.0;float a=.56;for(int o=0;o<4;o++){v+=a*noise(p);p=float2(.80*p.x-.60*p.y,.60*p.x+.80*p.y)*2.03+17.17;a*=.46;}return v;}
half4 main(float2 position){
 float2 safe=max(size,float2(1));float2 uv=position/safe;float aspect=safe.x/safe.y;float t=time*.075;
 float2 p=float2(uv.x*aspect,uv.y)*2.05+float2(-.30,-.08);
 float2 q=float2(fbm(p*.62+float2(0,t)),fbm(p*.62+float2(5.2,1.3-t)));
 float2 r=float2(fbm(p*.72+q*.62+float2(1.7,3.1+t*.7)),fbm(p*.72+q*.62+float2(9.2,5.7-t*.65)));
 float fluid=fbm((p+q*.76+r*.52)*.72+t*.25);float swirl=noise(p*1.25+r*2.1-t*.42);
 float3 black=float3(.002,.008,.018),deep=float3(.102,.220,.439),ocean=float3(.125,.290,.494),mist=float3(.325,.553,.790),warm=float3(.933,.847,.667);
 float3 color=mix(black,deep,smoothstep(.14,.63,fluid));color=mix(color,ocean,smoothstep(.42,.82,fluid+swirl*.18));color=mix(color,mist,smoothstep(.74,.98,swirl)*.18);
 float2 warped=float2(uv.x*aspect,uv.y)+(q-.5)*.085+(r-.5)*.045;
 float ra=length(warped-float2(aspect*.58,.16));float rb=length(warped-float2(aspect*-.20,.13));
 float ribbonA=exp(-pow((ra-(.35+.025*sin(t*1.5)))/.042,2.0));float ribbonB=exp(-pow((rb-(.83+.020*cos(t*1.15)))/.065,2.0));
 float ribbon=saturate(ribbonA*.78+ribbonB*.62);color=mix(color,warm,ribbon*(.48+.24*swirl));
 float glow=smoothstep(.90,.18,length((uv-float2(.58,.32))*float2(.82,1)));color+=deep*glow*.22;
 float vignette=smoothstep(.95,.23,length((uv-.5)*float2(.86,1)));color*=.38+.72*vignette;color+=(hash(position+floor(time*12.0))-.5)*.010;
 return half4(half3(max(color,0.0)),1);
}
"""
