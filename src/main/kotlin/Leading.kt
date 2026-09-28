import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2

// --------------------------------------------------
// SYSTÈME 4 — PLOMB
// "Comment les morceaux sont-ils assemblés ?"
// --------------------------------------------------

data class LeadStyle(
    val width: Double,
    val color: ColorRGBa = ColorRGBa.BLACK,
    /** Si non-nul, un reflet plus clair est dessiné le long du côté
     *  de chaque ligne de plomb qui fait face à lightDirection —
     *  simule le biseau du plomb sous une lumière unique et cohérente
     *  sur tout le tableau. */
    val highlightColor: ColorRGBa? = null,
    val highlightWidthRatio: Double = 0.35,
    /** Décalage perpendiculaire du reflet, en pixels. */
    val highlightOffset: Double = width * 0.55,
    /** Direction de lumière partagée avec le Système 5 (Verre) — voir
     *  GlassStyle.lightDirection. Seul le SIGNE du produit scalaire
     *  avec la normale de chaque segment compte (pas besoin de
     *  normaliser ce vecteur). */
    val lightDirection: Vector2 = Vector2(-0.35, -1.0)
)

interface LeadSystem {
    fun draw(
        drawer: Drawer,
        cells: List<List<Vector2>>,
        edgeCurveCache: MutableMap<String, EdgeCurve>,
        curvatureAmount: Double,
        style: LeadStyle
    )
}

/**
 * Plomb actuel : trait noir uniforme (drawCurvedVoronoiEdges), avec
 * un reflet optionnel décalé perpendiculairement (offsetEdgeCurve,
 * TemplateProgram.kt) du côté qui fait face à lightDirection. Validé
 * en Python sur la géométrie radiale avant portage : reflet net,
 * cohérent sur tout le tableau, aucun artefact de croisement.
 */
class BasicLeadSystem : LeadSystem {

    override fun draw(
        drawer: Drawer,
        cells: List<List<Vector2>>,
        edgeCurveCache: MutableMap<String, EdgeCurve>,
        curvatureAmount: Double,
        style: LeadStyle
    ) {

        drawCurvedVoronoiEdges(
            drawer,
            cells,
            style.color,
            style.width,
            edgeCurveCache,
            curvatureAmount
        )

        style.highlightColor?.let { highlight ->
            drawCurvedVoronoiEdges(
                drawer,
                cells,
                highlight,
                style.width * style.highlightWidthRatio,
                edgeCurveCache,
                curvatureAmount,
                offsetDistance = style.highlightOffset,
                lightDirection = style.lightDirection
            )
        }
    }
}