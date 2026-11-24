package fr.elvis.roll35

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Développe une photo en pleine résolution avec la même pellicule que le
 * viseur (même code GPU), puis l'enregistre dans la galerie.
 * Tourne sur son propre thread pour ne pas figer le viseur.
 */
class PhotoDeveloper(private val context: Context) {

    private val thread = HandlerThread("Roll35-Photo").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { handler.post(it) }

    private var core: GlCore? = null
    private var shader: FilmShader? = null
    private var loadedFilm: Film? = null
    private var filmLoaded = false

    /** À appeler sur [executor]. Renvoie l'adresse de la photo enregistrée. */
    fun develop(
        source: Bitmap,
        rotationDegrees: Int,
        film: Film?,
        framing: Framing,
        name: String,
    ): Uri {
        val developed = render(crop(source, rotationDegrees, framing), film)
        val rotated = if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(developed, 0, 0, developed.width, developed.height, m, true)
                .also { developed.recycle() }
        } else {
            developed
        }
        try {
            return save(rotated, name)
        } finally {
            rotated.recycle()
        }
    }

    /** Recadre au centre selon le format (calculé dans le sens final de la photo). */
    private fun crop(source: Bitmap, rotationDegrees: Int, framing: Framing): Bitmap {
        val sideways = rotationDegrees % 180 != 0
        val w = (if (sideways) source.height else source.width).toFloat()
        val h = (if (sideways) source.width else source.height).toFloat()
        val r = framing.fit(w, h)
        // Ramène le rectangle dans le sens de l'image source (non tournée).
        val cw = (if (sideways) r.height() else r.width()).roundToInt().coerceIn(1, source.width)
        val ch = (if (sideways) r.width() else r.height()).roundToInt().coerceIn(1, source.height)
        if (cw == source.width && ch == source.height) return source
        val x = (source.width - cw) / 2
        val y = (source.height - ch) / 2
        return Bitmap.createBitmap(source, x, y, cw, ch).also { if (it !== source) source.recycle() }
    }

    private fun render(source: Bitmap, film: Film?): Bitmap {
        val core = core ?: GlCore().also { core = it }
        core.makeCurrent(core.pbuffer)
        val shader = shader ?: FilmShader(external = false).also { shader = it }
        if (!filmLoaded || loadedFilm !== film) {
            shader.setFilm(film)
            loadedFilm = film
            filmLoaded = true
        }

        // Réduit l'image si elle dépasse ce que le processeur graphique accepte.
        val maxSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        var input = source
        val longest = max(source.width, source.height)
        if (longest > maxSize) {
            val scale = maxSize.toFloat() / longest
            input = Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt(),
                (source.height * scale).roundToInt(),
                true,
            )
            source.recycle()
        }
        val w = input.width
        val h = input.height

        val tex = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (t in tex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        // Image source
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)
        input.recycle()
        // Image de sortie
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[1], 0,
        )
        try {
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Image trop grande pour le processeur graphique"
            }
            GLES20.glViewport(0, 0, w, h)
            shader.draw(tex[0], IDENTITY, w, h, w, h, (System.nanoTime() % 997).toFloat())

            // La ligne 0 lue correspond à la ligne 0 de l'image source : pas besoin de retourner.
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.rewind()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(pixels)
            }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(2, tex, 0)
        }
    }

    private fun save(bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Roll35")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Galerie inaccessible")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Écriture impossible")
            stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun release() {
        handler.post {
            core?.release()
            core = null
            shader = null
            thread.quitSafely()
        }
    }

    private companion object {
        val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    }
}
