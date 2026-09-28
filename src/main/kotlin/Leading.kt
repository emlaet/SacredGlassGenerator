import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import kotlin.math.sqrt

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
 * Plomb actuel : trait noir uniforme (drawCurvedEdges), avec
 * un reflet optionnel décalé perpendiculairement (offsetEdgeCurve,
 * plus bas dans ce fichier) du côté qui fait face à lightDirection. Validé
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

        drawCurvedEdges(
            drawer,
            cells,
            style.color,
            style.width,
            edgeCurveCache,
            curvatureAmount
        )

        style.highlightColor?.let { highlight ->
            drawCurvedEdges(
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

// --------------------------------------------------
// OUTILS DE TRACÉ DU PLOMB
// --------------------------------------------------

/**
 * Trace toutes les arêtes des cellules, chacune UNE SEULE fois
 * (déduplication par edgeKey), avec la même courbe que le remplissage
 * (getEdgeCurve, EdgeCurves.kt). Anciennement nommée
 * drawCurvedVoronoiEdges : elle sert à tous les styles de
 * segmentation, pas seulement au Voronoï.
 */
fun drawCurvedEdges(
    drawer: Drawer,
    cells: List<List<Vector2>>,
    strokeColor: ColorRGBa,
    strokeWeight: Double,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double,
    offsetDistance: Double = 0.0,
    lightDirection: Vector2 = Vector2.ZERO
) {

    val drawnEdges = mutableSetOf<String>()

    drawer.fill = null
    drawer.stroke = strokeColor
    drawer.strokeWeight = strokeWeight

    for (cell in cells) {

        if (cell.size < 3) continue

        for (i in cell.indices) {

            val a = cell[i]
            val b = cell[(i + 1) % cell.size]
            val key = edgeKey(a, b)

            if (drawnEdges.add(key)) {

                var curve = getEdgeCurve(a, b, edgeCurveCache, curvatureAmount)

                if (offsetDistance != 0.0) {
                    curve = offsetEdgeCurve(curve, offsetDistance, lightDirection)
                }

                val contour = ShapeContour.fromSegments(
                    listOf(Segment2D(curve.start, curve.control1, curve.control2, curve.end)),
                    closed = false
                )

                drawer.contour(contour)
            }
        }
    }
}

/**
 * Décale une EdgeCurve perpendiculairement à sa direction générale
 * (corde start→end), toujours du côté qui fait face à lightDirection.
 * Utilisé par le Système 4 (Plomb) pour placer un reflet cohérent
 * avec une unique source de lumière sur tout le tableau — le même
 * lightDirection que celui utilisé par le Système 5 (Verre), pour que
 * les deux systèmes racontent la même histoire de lumière.
 *
 * Approximation : les 4 points de contrôle sont décalés du même
 * vecteur (celui de la corde), plutôt qu'un vrai offset de courbe de
 * Bézier (mathématiquement plus complexe). Suffisant visuellement
 * tant que curvatureAmount reste modéré (validé en Python jusqu'à
 * curvatureAmount=2.0, le réglage actuel).
 */
fun offsetEdgeCurve(
    curve: EdgeCurve,
    offsetDistance: Double,
    lightDirection: Vector2
): EdgeCurve {

    val dx = curve.end.x - curve.start.x
    val dy = curve.end.y - curve.start.y

    val length = sqrt(dx * dx + dy * dy)

    if (length == 0.0) {
        return curve
    }

    val tangentX = dx / length
    val tangentY = dy / length

    val normalX = -tangentY
    val normalY = tangentX

    val dot = normalX * lightDirection.x + normalY * lightDirection.y
    val sign = if (dot >= 0.0) 1.0 else -1.0

    val offsetX = normalX * offsetDistance * sign
    val offsetY = normalY * offsetDistance * sign

    return EdgeCurve(
        start = Vector2(curve.start.x + offsetX, curve.start.y + offsetY),
        control1 = Vector2(curve.control1.x + offsetX, curve.control1.y + offsetY),
        control2 = Vector2(curve.control2.x + offsetX, curve.control2.y + offsetY),
        end = Vector2(curve.end.x + offsetX, curve.end.y + offsetY)
    )
}