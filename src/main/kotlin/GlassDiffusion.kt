import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.Filter
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.filterShaderFromCode
import org.openrndr.draw.isolated
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2

// --------------------------------------------------
// DIFFUSION DE LA LUMIÈRE DANS LE VERRE (phase 19)
// --------------------------------------------------

/**
 * Diffusion de la lumière dans la masse du verre (phase 19 de la rosace C2
 * noire) : une partie de la lumière qui traverse une pièce s'étale dans
 * cette pièce, ce qui adoucit les détails de la texture et fond les
 * passages de clarté, sans changer la clarté moyenne de la pièce (le noir
 * reste aussi noir : la lumière est redistribuée, pas ajoutée).
 *
 * La diffusion reste STRICTEMENT à l'intérieur de chaque pièce : elle
 * s'arrête au premier plomb rencontré et ne passe jamais d'une pièce à
 * l'autre. Les plombs sont dessinés ensuite, opaques, par-dessus : aucune
 * aura ni aucun halo autour d'eux.
 *
 * Mise en œuvre : les pièces sont dessinées dans une image à part, avec une
 * seconde image où chaque pièce a sa propre couleur d'identification ; un
 * flou gaussien en deux passes (horizontale puis verticale) ne prend en
 * compte que les pixels de la même pièce, jusqu'au premier pixel d'une autre
 * pièce. Le résultat (mélange de l'original et du flou) est ensuite posé sur
 * la pierre, à la place du verre dessiné directement.
 *
 * Aucun tirage aléatoire : l'ordre des tirages de renderVitrail() ne change
 * pas. null (VitrailConfig.glassDiffusion) = rendu actuel.
 */
data class GlassDiffusion(
    /** Distance de diffusion, en fraction de la largeur du canevas
     *  (0,01 = 1 %, soit environ 35 px sur un export de 3543 px). */
    val radius: Double = 0.01,
    /** Part de la lumière diffusée (0 = aucune, 1 = entièrement diffusée). */
    val amount: Double = 0.30
)

/** Couleur d'identification d'une pièce (image des identifiants) : 215 codes
 *  bien séparés (6 niveaux par canal), robustes à toute conversion de
 *  couleur ; alpha 0 = hors des pièces. */
fun glassPieceIdColor(drawnIndex: Int): ColorRGBa {
    val code = drawnIndex % 215 + 1
    return ColorRGBa((code % 6) / 5.0, ((code / 6) % 6) / 5.0, (code / 36) / 5.0, 1.0)
}

// Shader en ASCII uniquement (certains pilotes refusent les accents).
private const val GLASS_DIFFUSION_SHADER = """
#ifdef OR_IN_OUT
in vec2 v_texCoord0;
#else
varying vec2 v_texCoord0;
#endif

uniform sampler2D tex0;   // light to diffuse
uniform sampler2D tex1;   // piece ids
uniform sampler2D tex2;   // original glass (final pass)
uniform vec2 textureSize0;
uniform vec2 direction;   // (1, 0) then (0, 1)
uniform float radius;     // in pixels
uniform float amount;     // diffused share (final pass)
uniform int finalPass;

#ifndef OR_GL_FRAGCOLOR
out vec4 o_color;
#endif

bool samePiece(vec4 a, vec4 b) {
    return b.a > 0.5 && all(lessThan(abs(a.rgb - b.rgb), vec3(0.05)));
}

void main() {
    vec4 c = texture(tex0, v_texCoord0);
    vec4 id0 = texture(tex1, v_texCoord0);
    vec4 result = c;
    if (id0.a > 0.5 && radius >= 1.0) {
        vec2 texel = 1.0 / textureSize0;
        float sigma = max(radius * 0.5, 1.0);
        float stepPx = max(radius / 32.0, 1.0);
        vec3 acc = c.rgb;
        float wsum = 1.0;
        // Forward then backward: stop at the first pixel of another piece
        // (the lead stops the diffusion).
        for (int s = 0; s < 2; s++) {
            float sgn = (s == 0) ? 1.0 : -1.0;
            for (int k = 1; k <= 32; k++) {
                float off = floor(float(k) * stepPx + 0.5);
                if (off > radius) break;
                vec2 uv = v_texCoord0 + direction * (sgn * off) * texel;
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
                if (!samePiece(id0, texture(tex1, uv))) break;
                float w = exp(-0.5 * off * off / (sigma * sigma));
                acc += texture(tex0, uv).rgb * w;
                wsum += w;
            }
        }
        vec3 blurred = acc / wsum;
        if (finalPass == 1) {
            vec4 o = texture(tex2, v_texCoord0);
            result = vec4(mix(o.rgb, blurred, amount), o.a);
        } else {
            result = vec4(blurred, c.a);
        }
    }
    o_color = result;
}
"""

private val glassDiffusionFilter: Filter by lazy {
    Filter(filterShaderFromCode(GLASS_DIFFUSION_SHADER, "glass-diffusion"))
}

/** Images de travail de la diffusion : le verre et les identifiants des
 *  pièces, à la taille du canevas. */
class GlassDiffusionLayers(val width: Int, val height: Int) {
    val glass: RenderTarget = renderTarget(width, height) {
        colorBuffer()
        depthBuffer()
    }
    val ids: RenderTarget = renderTarget(width, height) {
        colorBuffer()
        depthBuffer()
    }

    /** Diffuse la lumière dans chaque pièce et pose le résultat sur le
     *  dessin en cours (drawer), puis libère les images de travail. */
    fun diffuseAndDraw(drawer: Drawer, diffusion: GlassDiffusion, radiusPx: Double) {
        val idBuffer = ids.colorBuffer(0)
        idBuffer.filter(MinifyingFilter.NEAREST, MagnifyingFilter.NEAREST)
        val horizontal: ColorBuffer = colorBuffer(width, height)
        val result: ColorBuffer = colorBuffer(width, height)
        val f = glassDiffusionFilter
        f.parameters["radius"] = radiusPx
        f.parameters["amount"] = diffusion.amount.coerceIn(0.0, 1.0)

        f.parameters["direction"] = Vector2(1.0, 0.0)
        f.parameters["finalPass"] = 0
        f.apply(arrayOf(glass.colorBuffer(0), idBuffer, glass.colorBuffer(0)), arrayOf(horizontal))

        f.parameters["direction"] = Vector2(0.0, 1.0)
        f.parameters["finalPass"] = 1
        f.apply(arrayOf(horizontal, idBuffer, glass.colorBuffer(0)), arrayOf(result))

        drawer.isolated {
            shadeStyle = null
            image(result)
        }

        horizontal.destroy()
        result.destroy()
        glass.destroy()
        ids.destroy()
    }
}