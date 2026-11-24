package fr.elvis.roll35

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Le « développement » sur le processeur graphique : table de couleurs 3D,
 * halo rouge et grain. Le même code sert au viseur (texture caméra) et aux
 * photos enregistrées (image chargée en mémoire), pour un rendu identique.
 *
 * À utiliser uniquement sur le thread qui possède le contexte OpenGL.
 */
class FilmShader(external: Boolean) {

    private val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D

    private val program: Int
    private val aPos: Int
    private val uTexMatrix: Int
    private val uTex: Int
    private val uLut: Int
    private val uLutOn: Int
    private val uGrain: Int
    private val uGrainSize: Int
    private val uSeed: Int
    private val uHalo: Int
    private val uHaloRadius: Int
    private val lutTex: Int

    private var lutOn = false
    private var grain = 0f
    private var grainLarge = false
    private var halo = 0f

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    init {
        val samplerType = if (external) "samplerExternalOES" else "sampler2D"
        val ext = if (external) "#extension GL_OES_EGL_image_external_essl3 : require\n" else ""
        program = link(VERTEX, "#version 300 es\n$ext" + FRAGMENT.replace("SAMPLER", samplerType))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutOn = GLES20.glGetUniformLocation(program, "uLutOn")
        uGrain = GLES20.glGetUniformLocation(program, "uGrain")
        uGrainSize = GLES20.glGetUniformLocation(program, "uGrainSize")
        uSeed = GLES20.glGetUniformLocation(program, "uSeed")
        uHalo = GLES20.glGetUniformLocation(program, "uHalo")
        uHaloRadius = GLES20.glGetUniformLocation(program, "uHaloRadius")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        lutTex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        uploadLut(LutBuilder.build(null))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Change de pellicule (`null` = image d'origine). */
    fun setFilm(film: Film?) {
        if (film == null) {
            lutOn = false
            grain = 0f
            halo = 0f
            return
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        uploadLut(LutBuilder.build(film))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        lutOn = true
        grain = film.grain
        grainLarge = film.grainLarge
        halo = film.halation
    }

    private fun uploadLut(data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val n = LutBuilder.SIZE
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES20.GL_RGB, GLES20.GL_FLOAT, buf,
        )
    }

    /**
     * Dessine l'image [inputTex] (taille [inW]×[inH]) dans la surface courante
     * (taille [outW]×[outH]). [seed] change à chaque image pour animer le grain.
     */
    fun draw(
        inputTex: Int,
        texMatrix: FloatArray,
        outW: Int,
        outH: Int,
        inW: Int,
        inH: Int,
        seed: Float,
    ) {
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, inputTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uLutOn, if (lutOn) 1f else 0f)
        GLES20.glUniform1f(uGrain, grain)
        // Grain proportionnel à l'image : même aspect dans le viseur et sur la photo.
        val grainSize = max(1f, min(outW, outH) / 1080f) * (if (grainLarge) 2f else 1f)
        GLES20.glUniform1f(uGrainSize, grainSize)
        GLES20.glUniform1f(uSeed, seed)
        GLES20.glUniform1f(uHalo, halo)
        val r = 0.007f
        GLES20.glUniform2f(uHaloRadius, r, r * inW / inH.toFloat())

        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader : $log")
        }
        return shader
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Programme : " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private companion object {
        val VERTEX = """
            #version 300 es
            in vec4 aPos;
            uniform mat4 uTexMatrix;
            out vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4((aPos.xy + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        // Le « #version » est ajouté devant, avec l'extension caméra si besoin.
        val FRAGMENT = """
            precision highp float;
            precision highp sampler3D;
            uniform SAMPLER uTex;
            uniform sampler3D uLut;
            uniform float uLutOn;
            uniform float uGrain;
            uniform float uGrainSize;
            uniform float uSeed;
            uniform float uHalo;
            uniform vec2 uHaloRadius;
            in vec2 vTex;
            out vec4 outColor;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 film(vec3 c) {
                c = clamp(c, 0.0, 1.0);
                return texture(uLut, c * (32.0 / 33.0) + 0.5 / 33.0).rgb;
            }

            void main() {
                vec3 src = texture(uTex, vTex).rgb;
                vec3 c = uLutOn > 0.5 ? film(src) : src;

                // Halo rouge : lueur autour des zones très claires.
                if (uHalo > 0.0) {
                    float h = 0.0;
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5235988;
                        vec2 d = vec2(cos(a), sin(a)) * uHaloRadius;
                        h += smoothstep(0.80, 1.0, luma(texture(uTex, vTex + d).rgb));
                        h += 0.6 * smoothstep(0.80, 1.0, luma(texture(uTex, vTex + d * 2.2).rgb));
                    }
                    h /= 19.2;
                    vec3 glow = vec3(1.0, 0.28, 0.12) * h * uHalo;
                    c = 1.0 - (1.0 - c) * (1.0 - glow);
                }

                // Grain : bruit qui change à chaque image, surtout dans les tons moyens.
                if (uGrain > 0.0) {
                    vec2 cell = floor(gl_FragCoord.xy / uGrainSize);
                    float n = hash(cell + uSeed) + hash(cell * 1.37 + uSeed * 1.7) - 1.0;
                    float y = luma(c);
                    c += n * uGrain * (0.35 + 2.6 * y * (1.0 - y));
                }

                outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
