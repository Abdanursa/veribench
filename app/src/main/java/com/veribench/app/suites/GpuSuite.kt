package com.veribench.app.suites

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.veribench.app.core.Benchmark
import com.veribench.app.core.Category
import com.veribench.app.core.IterationResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * GPU fragment-shader throughput on an offscreen 1280×720 pbuffer.
 *
 * Verification is different from the CPU tests because GPU float pipelines
 * legitimately differ between vendors: instead of an exact checksum, sampled
 * output pixels are compared against a CPU reference of the same smooth
 * (non-chaotic) function within a tolerance. The shader was chosen so small
 * fp32 differences can't snowball. Pass => the checksum contract value 0.
 *
 * On devices without a usable EGL/ES2 offscreen context (some emulators),
 * setUp throws and the runner reports the test as skipped; the GPU category
 * is then excluded from the total with weights renormalized — never faked.
 */
class GpuShaderBenchmark : Benchmark {
    override val id = "gpu.shader"
    override val name = "Fragment shader throughput"
    override val category = Category.GPU
    override val unit = "Mpix/s"
    override val warmupIterations = 1
    override val measuredIterations = 3
    override val expectedChecksum = 0L // contract: 0 = verified within tolerance

    private val width = 1280
    private val height = 720
    private val iters = 192
    private val framesPerIteration = 30

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var vbo = 0

    override fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "no EGL config"
        }
        val config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "pbuffer creation failed" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        vbo = buildFullscreenTriangle()
        GLES20.glViewport(0, 0, width, height)
    }

    override fun runIteration(): IterationResult {
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uIters"), iters)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glFinish()
        val t0 = System.nanoTime()
        repeat(framesPerIteration) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glFlush()
        }
        GLES20.glFinish()
        val nanos = System.nanoTime() - t0

        val checksum = if (verifyOutput()) 0L else 1L
        val mpix = width.toDouble() * height * framesPerIteration / 1e6
        return IterationResult(mpix / (nanos / 1e9), checksum)
    }

    override fun tearDown() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    /** Samples a 4×4 grid and compares to the CPU reference within tolerance. */
    private fun verifyOutput(): Boolean {
        val pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        for (gy in 0 until 4) {
            for (gx in 0 until 4) {
                val px = (width * (2 * gx + 1)) / 8
                val py = (height * (2 * gy + 1)) / 8
                pixel.clear()
                GLES20.glReadPixels(px, py, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                val u = (px + 0.5f) / width
                val v = (py + 0.5f) / height
                val expected = referenceColor(u, v, iters)
                for (ch in 0 until 3) {
                    val got = (pixel.get(ch).toInt() and 0xff) / 255.0f
                    if (abs(got - expected[ch]) > 10.0f / 255.0f) return false
                }
            }
        }
        return true
    }

    private fun buildProgram(): Int {
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """.trimIndent(),
        )
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun buildFullscreenTriangle(): Int {
        val verts = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts).apply { position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }

    companion object {
        /**
         * The workload: a long, smooth, ALU-bound accumulation. Deliberately
         * non-chaotic — nearby inputs give nearby outputs, so fp32 vendor
         * differences stay inside the verification tolerance.
         */
        private val FRAGMENT_SRC = """
            precision highp float;
            varying vec2 vUv;
            uniform int uIters;
            void main() {
                float accR = 0.0;
                float accG = 0.0;
                float accB = 0.0;
                for (int i = 0; i < 512; i++) {
                    if (i >= uIters) break;
                    float fi = float(i);
                    vec2 q = vUv * (1.0 + fi * 0.05) - vec2(fi * 0.013, fi * 0.007);
                    float d = 1.0 + dot(q, q);
                    accR += 1.0 / d;
                    accG += 1.0 / (d + 0.5);
                    accB += 1.0 / (d + 1.5);
                }
                float s = 2.0 / float(uIters);
                gl_FragColor = vec4(accR * s, accG * s, accB * s, 1.0);
            }
        """.trimIndent()

        /** CPU mirror of the fragment shader, in float to match fp32 behaviour. */
        fun referenceColor(u: Float, v: Float, iters: Int): FloatArray {
            var accR = 0.0f
            var accG = 0.0f
            var accB = 0.0f
            for (i in 0 until iters) {
                val fi = i.toFloat()
                val qx = u * (1.0f + fi * 0.05f) - fi * 0.013f
                val qy = v * (1.0f + fi * 0.05f) - fi * 0.007f
                val d = 1.0f + qx * qx + qy * qy
                accR += 1.0f / d
                accG += 1.0f / (d + 0.5f)
                accB += 1.0f / (d + 1.5f)
            }
            val s = 2.0f / iters
            return floatArrayOf(
                (accR * s).coerceIn(0f, 1f),
                (accG * s).coerceIn(0f, 1f),
                (accB * s).coerceIn(0f, 1f),
            )
        }
    }
}
