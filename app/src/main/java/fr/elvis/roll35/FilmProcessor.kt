package fr.elvis.roll35

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

/**
 * Filtre du viseur : CameraX envoie ici chaque image de la caméra, on la
 * « développe » avec la pellicule choisie sur le processeur graphique, puis on
 * l'envoie à l'écran. Tout se passe sur un thread dédié.
 */
class FilmProcessor : SurfaceProcessor {

    private val thread = HandlerThread("Roll35-Viseur").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { handler.post(it) }

    private var core: GlCore? = null
    private var shader: FilmShader? = null
    private var released = false

    private class Input(
        val tex: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val width: Int,
        val height: Int,
    )

    private class Output(val egl: EGLSurface, val width: Int, val height: Int)

    private var input: Input? = null
    private val outputs = LinkedHashMap<SurfaceOutput, Output>()

    private val stMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var frame = 0

    init {
        handler.post {
            try {
                core = GlCore()
                shader = FilmShader(external = true)
            } catch (t: Throwable) {
                Log.e(TAG, "OpenGL indisponible", t)
            }
        }
    }

    /** Change la pellicule du viseur, sans couper la caméra. */
    fun setFilm(film: Film?) {
        handler.post {
            val core = core ?: return@post
            val shader = shader ?: return@post
            core.makeCurrent(core.pbuffer)
            shader.setFilm(film)
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        val core = core
        if (released || core == null) {
            request.willNotProvideSurface()
            return
        }
        core.makeCurrent(core.pbuffer)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        val oes = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(oes, tex)
        GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val w = request.resolution.width
        val h = request.resolution.height
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(w, h)
        val surface = Surface(st)
        val inp = Input(tex, st, surface, w, h)
        st.setOnFrameAvailableListener({ drawFrame(inp) }, handler)
        input = inp

        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            surface.release()
            st.release()
            this.core?.let {
                it.makeCurrent(it.pbuffer)
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            if (input === inp) input = null
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        val core = core
        if (released || core == null) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) {
            outputs.remove(surfaceOutput)?.let { core.destroySurface(it.egl) }
            surfaceOutput.close()
        }
        val egl = core.createWindowSurface(surface)
        if (egl == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Surface d'affichage refusée")
            return
        }
        outputs[surfaceOutput] = Output(egl, surfaceOutput.size.width, surfaceOutput.size.height)
    }

    private fun drawFrame(inp: Input) {
        val core = core ?: return
        val shader = shader ?: return
        if (released) return
        core.makeCurrent(core.pbuffer)
        try {
            inp.surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            return // surface déjà libérée
        }
        if (input !== inp) return
        inp.surfaceTexture.getTransformMatrix(stMatrix)
        frame = (frame + 1) % 997

        for ((out, target) in outputs) {
            if (!core.makeCurrent(target.egl)) continue
            out.updateTransformMatrix(outMatrix, stMatrix)
            GLES20.glViewport(0, 0, target.width, target.height)
            shader.draw(
                inp.tex, outMatrix, target.width, target.height,
                inp.width, inp.height, frame * 0.731f,
            )
            core.setPresentationTime(target.egl, inp.surfaceTexture.timestamp)
            core.swap(target.egl)
        }
    }

    fun release() {
        handler.post {
            released = true
            val core = core
            if (core != null) {
                for ((out, target) in outputs) {
                    core.destroySurface(target.egl)
                    out.close()
                }
                outputs.clear()
                core.release()
            }
            this.core = null
            shader = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "Roll35"
    }
}
