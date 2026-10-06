import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
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
    val lightDirection: Vector2 = Vector2(-0.35, -1.0),
    /** Extrémités de chaque arête de plomb. BUTT (coupe droite, valeur
     *  par défaut d'OpenRNDR) garde la croix et l'ange tels qu'ils ont
     *  été validés. ROUND supprime les fines coutures claires qui
     *  apparaissent quand une ligne est faite de nombreuses petites
     *  arêtes (arcs du motif raccordable) : aux jonctions, les bords
     *  anticrénelés de deux arêtes coupées droit ne se couvrent pas
     *  entièrement, et le fond transparent transparaît. Une extrémité
     *  ronde recouvre la jonction, comme une soudure d'étain. */
    val lineCap: LineCap = LineCap.BUTT
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
            curvatureAmount,
            lineCap = style.lineCap
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
                lightDirection = style.lightDirection,
                lineCap = style.lineCap
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
    lightDirection: Vector2 = Vector2.ZERO,
    lineCap: LineCap = LineCap.BUTT
) {

    // isolated : le style de trait (dont lineCap) est rétabli après le
    // tracé, pour ne pas affecter ce qui est dessiné ensuite.
    drawer.isolated {

        val drawnEdges = mutableSetOf<String>()

        drawer.fill = null
        drawer.stroke = strokeColor
        drawer.strokeWeight = strokeWeight
        drawer.lineCap = lineCap

        // Arêtes droites (curvatureAmount = 0 : motifs raccordables, dont
        // les courbes sont faites de nombreux petits segments) : on trace
        // des LIGNES CONTINUES d'une jonction à l'autre plutôt que chaque
        // petit segment séparément — plus rapide, et sans coutures aux
        // raccords des segments. Les compositions à arêtes courbes (croix,
        // ange…) ne passent pas par ici : leur rendu ne change pas.
        if (curvatureAmount == 0.0 && offsetDistance == 0.0) {
            drawStraightEdgeChains(drawer, cells)
            return@isolated
        }

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
}

/**
 * Trace les arêtes (droites) des cellules sous forme de lignes continues :
 * chaque arête une seule fois, enchaînée avec ses voisines tant que le
 * sommet commun n'est pas une jonction (3 arêtes ou plus) ni un angle vif
 * (plus de 60°) — les extrémités rondes du plomb dessinent alors l'angle.
 */
private fun drawStraightEdgeChains(drawer: Drawer, cells: List<List<Vector2>>) {

    // Sommets fusionnés au millième de pixel.
    val ids = HashMap<Long, Int>()
    val points = mutableListOf<Vector2>()
    fun id(p: Vector2): Int {
        val key = Math.round(p.x * 1000.0) * 1_000_000_000L + Math.round(p.y * 1000.0)
        return ids.getOrPut(key) { points.add(p); points.size - 1 }
    }
    val neighbors = mutableListOf<MutableList<Int>>()
    val edges = HashSet<Long>()
    fun edgeId(a: Int, b: Int) = minOf(a, b).toLong() * 4_000_000_000L + maxOf(a, b)
    for (cell in cells) {
        if (cell.size < 3) continue
        for (i in cell.indices) {
            val a = id(cell[i])
            val b = id(cell[(i + 1) % cell.size])
            if (a == b) continue
            while (neighbors.size < points.size) neighbors.add(mutableListOf())
            if (edges.add(edgeId(a, b))) {
                neighbors[a].add(b)
                neighbors[b].add(a)
            }
        }
    }
    while (neighbors.size < points.size) neighbors.add(mutableListOf())

    val used = HashSet<Long>()

    fun sharpTurn(prev: Int, v: Int, next: Int): Boolean {
        val p = points[prev]; val q = points[v]; val r = points[next]
        val ax = q.x - p.x; val ay = q.y - p.y
        val bx = r.x - q.x; val by = r.y - q.y
        val la = kotlin.math.hypot(ax, ay); val lb = kotlin.math.hypot(bx, by)
        if (la == 0.0 || lb == 0.0) return false
        return (ax * bx + ay * by) / (la * lb) < 0.5      // plus de 60°
    }

    val chains = mutableListOf<Pair<List<Int>, Boolean>>()   // (sommets, fermée ?)

    fun walk(start: Int, first: Int): List<Int> {
        val path = mutableListOf(start, first)
        used.add(edgeId(start, first))
        var prev = start
        var v = first
        while (neighbors[v].size == 2) {
            val next = if (neighbors[v][0] == prev) neighbors[v][1] else neighbors[v][0]
            if (!used.add(edgeId(v, next))) break
            if (sharpTurn(prev, v, next)) {
                chains.add(path.toList() to false)
                path.clear()
                path.add(v)
            }
            path.add(next)
            prev = v
            v = next
        }
        return path
    }

    // Lignes qui partent d'une jonction ou d'une extrémité…
    for (v in points.indices) {
        if (neighbors[v].size == 2) continue
        for (n in neighbors[v]) {
            if (edgeId(v, n) in used) continue
            chains.add(walk(v, n) to false)
        }
    }
    // …puis boucles fermées (tous les sommets à 2 voisins).
    for (v in points.indices) {
        for (n in neighbors[v]) {
            if (edgeId(v, n) in used) continue
            val path = walk(v, n)
            chains.add(path to (path.size > 3 && path.first() == path.last()))
        }
    }

    for ((path, closed) in chains) {
        if (path.size < 2) continue
        val pts = path.map { points[it] }
        val segments = (0 until pts.size - 1).map { k ->
            val a = pts[k]
            val b = pts[k + 1]
            Segment2D(
                a,
                Vector2(a.x + (b.x - a.x) / 3.0, a.y + (b.y - a.y) / 3.0),
                Vector2(a.x + 2.0 * (b.x - a.x) / 3.0, a.y + 2.0 * (b.y - a.y) / 3.0),
                b
            )
        }
        drawer.contour(ShapeContour.fromSegments(segments, closed = closed))
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