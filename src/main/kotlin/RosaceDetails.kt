import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import java.util.IdentityHashMap
import kotlin.math.roundToLong

// --------------------------------------------------
// DÉTAILS D'ATELIER DES ROSACES (phase 22)
// --------------------------------------------------
//
// Deux détails qui ne relèvent ni du verre ni des couleurs :
//  - les soudures aux croisements des plombs (petits nœuds noirs mats,
//    légèrement plus larges que le plomb, sans aucun reflet) ;
//  - la grisaille : nervures peintes sur le verre givré des pétales
//    (traits fins, semi-transparents, entre le verre et les plombs).
// Aucun tirage aléatoire : l'ordre des tirages de renderVitrail() ne
// change pas. null dans VitrailConfig = rendu actuel.

/** Soudures aux croisements des plombs. */
data class LeadJoints(
    /** Diamètre du nœud par rapport à la largeur du plomb le plus large qui
     *  arrive au croisement (1,0 = aucune surépaisseur). */
    val sizeFactor: Double = 1.3,
    /** Soudures aussi sur le pourtour (jonctions avec le plomb extérieur).
     *  Phase 22 bis : false (elles formaient une rangée de perles). */
    val onRim: Boolean = true
)

/** Grisaille : nervures peintes sur le verre givré des pétales. */
data class PetalGrisaille(
    /** Position des nervures, en part de la demi-largeur du pétale, de
     *  chaque côté de l'axe. */
    val fractions: List<Double> = listOf(0.30, 0.56, 0.80),
    /** Largeur du trait, en part de la largeur du plomb. */
    val width: Double = 0.26,
    /** Opacité du trait (la peinture laisse passer un peu de lumière). */
    val opacity: Double = 0.42,
    /** Couleur de la grisaille. */
    val color: String = "#1E2228",
    /** Seules les pièces de pétale plus claires que ce seuil (clarté OKLab)
     *  reçoivent la grisaille : le verre givré, pas le corps sombre. */
    val minLightness: Double = 0.70,
    /** Phase 22 bis : trait qui s'affine de la base vers la pointe, comme un
     *  coup de pinceau (false = trait d'épaisseur constante). */
    val taper: Boolean = false,
    /** Fin des nervures, en part de l'axe du pétale (0,96 = presque à la pointe). */
    val endT: Double = 0.96,
    /** Irrégularité du tracé, en part de la demi-largeur du pétale (0 = aucune). */
    val wobble: Double = 0.0
)

/** Nervure de grisaille : points et largeur relative en chaque point (1 = pleine). */
class GrisailleLine(val points: List<Vector2>, val widths: List<Double>)

class JointPoint(val p: Vector2, val width: Double)
private val jointCache = IdentityHashMap<List<List<Vector2>>, MutableMap<Triple<Double, Double, Boolean>, List<JointPoint>>>()

/**
 * Soudures : un nœud à chaque sommet partagé par au moins trois pièces
 * (croisement ou jonction en T), et à chaque sommet partagé par deux pièces
 * sur le pourtour (jonction avec le plomb extérieur). Taille : celle du plomb
 * le plus large qui passe par ce point (plombs épais de la rosace, plomb
 * extérieur, sinon plomb fin), multipliée par sizeFactor.
 */
fun drawRosaceLeadJoints(drawer: Drawer, guide: RosaceGuide, cells: List<List<Vector2>>, offset: Vector2,
                         color: ColorRGBa, leadWidth: Double, fineWidth: Double, joints: LeadJoints) {
    val points = rosaceJointPoints(guide, cells, offset, leadWidth, fineWidth, joints.onRim)
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.stroke = null
        drawer.fill = color
        for (j in points) drawer.circle(j.p, 0.5 * j.width * joints.sizeFactor)
    }
}

/** Croisements des plombs et largeur du plomb le plus large en chacun. */
fun rosaceJointPoints(guide: RosaceGuide, cells: List<List<Vector2>>, offset: Vector2,
                      leadWidth: Double, fineWidth: Double, onRim: Boolean = true): List<JointPoint> =
    synchronized(jointCache) {
        jointCache.getOrPut(cells) { mutableMapOf() }.getOrPut(Triple(leadWidth, fineWidth, onRim)) {
            val eps = 0.5
            fun key(v: Vector2) = Pair((v.x / eps).roundToLong(), (v.y / eps).roundToLong())
            val owners = HashMap<Pair<Long, Long>, MutableSet<Int>>()
            val pos = HashMap<Pair<Long, Long>, Vector2>()
            cells.forEachIndexed { i, c -> for (v in c) { val k = key(v); owners.getOrPut(k) { mutableSetOf() }.add(i); pos.putIfAbsent(k, v) } }
            val center = Vector2(guide.center.x + offset.x, guide.center.y + offset.y)
            val rMax = cells.flatten().maxOf { (it - center).length }
            val lines = guide.leadLines.filter { it.points.size > 1 && it.factor > 0 }.map { ln ->
                ln.points.map { Vector2(it.x + offset.x, it.y + offset.y) }.let { if (ln.closed) it + it[0] else it } to ln.factor
            }
            fun distToLine(p: Vector2, pts: List<Vector2>): Double {
                var best = Double.MAX_VALUE
                for (k in 0 until pts.size - 1) {
                    val a = pts[k]; val b = pts[k + 1]; val ab = b - a
                    val t = if (ab.squaredLength < 1e-12) 0.0 else ((p - a).dot(ab) / ab.squaredLength).coerceIn(0.0, 1.0)
                    best = minOf(best, (a + ab * t - p).length)
                }
                return best
            }
            owners.mapNotNull { (k, set) ->
                val p = pos[k]!!
                val rim = (p - center).length > rMax - 2.0
                if (rim && !onRim) return@mapNotNull null
                if (set.size < 3 && !(rim && set.size >= 2)) return@mapNotNull null
                var w = fineWidth
                if (rim) w = maxOf(w, leadWidth)
                for ((pts, f) in lines) if (leadWidth * f > w && distToLine(p, pts) < leadWidth * 0.6) w = leadWidth * f
                JointPoint(p, w)
            }
        }
    }

private val grisailleCache = IdentityHashMap<List<List<Vector2>>, MutableMap<PetalGrisaille, List<GrisailleLine>>>()

/**
 * Grisaille : pour chaque pétale (union des pièces de pétale, de flamme et
 * de pointe d'un même quadrant), des nervures parallèles au contour du
 * pétale, de part et d'autre de son axe (de la base vers la pointe), tracées
 * seulement sur le verre givré (pièces de pétale claires), un peu en retrait
 * des plombs.
 */
fun drawRosacePetalGrisaille(drawer: Drawer, guide: RosaceGuide, cells: List<List<Vector2>>, cellColors: List<ColorRGBa>,
                             offset: Vector2, leadWidth: Double, g: PetalGrisaille) {
    val lines = rosaceGrisailleLines(guide, cells, cellColors, offset, g)
    val c = ColorRGBa.fromHex(g.color)
    drawer.isolated {
        drawer.shadeStyle = null
        if (!g.taper) {
            drawer.fill = null
            drawer.lineCap = LineCap.ROUND
            val contours = lines.map { l -> ShapeContour.fromSegments(l.points.zipWithNext { a, b -> Segment2D(a, b) }, closed = false) }
            // Bord adouci de la peinture, puis trait.
            drawer.stroke = c.opacify(g.opacity * 0.25)
            drawer.strokeWeight = leadWidth * g.width * 2.2
            for (l in contours) drawer.contour(l)
            drawer.stroke = c.opacify(g.opacity)
            drawer.strokeWeight = leadWidth * g.width
            for (l in contours) drawer.contour(l)
        } else {
            // Coup de pinceau : bande dont la largeur suit l'affinement.
            drawer.stroke = null
            fun band(l: GrisailleLine, w: Double): ShapeContour? {
                val pts = l.points
                if (pts.size < 2) return null
                val left = mutableListOf<Vector2>(); val right = mutableListOf<Vector2>()
                for (i in pts.indices) {
                    val d = (pts[minOf(i + 1, pts.size - 1)] - pts[maxOf(i - 1, 0)]).normalized
                    val n = Vector2(-d.y, d.x) * (0.5 * w * l.widths[i])
                    left += pts[i] + n; right += pts[i] - n
                }
                val ring = left + right.reversed()
                return ShapeContour.fromSegments(ring.indices.map { k -> Segment2D(ring[k], ring[(k + 1) % ring.size]) }, closed = true)
            }
            drawer.fill = c.opacify(g.opacity * 0.25)
            for (l in lines) band(l, leadWidth * g.width * 2.2)?.let { drawer.contour(it) }
            drawer.fill = c.opacify(g.opacity)
            for (l in lines) band(l, leadWidth * g.width)?.let { drawer.contour(it) }
        }
    }
}

/** Nervures de grisaille, découpées au verre givré des pétales. */
fun rosaceGrisailleLines(guide: RosaceGuide, cells: List<List<Vector2>>, cellColors: List<ColorRGBa>,
                         offset: Vector2, g: PetalGrisaille): List<GrisailleLine> =
    synchronized(grisailleCache) {
        grisailleCache.getOrPut(cells) { mutableMapOf() }.getOrPut(g) {
            val gf = GeometryFactory()
            fun poly(c: List<Vector2>): Polygon = gf.createPolygon((c + c[0]).map { Coordinate(it.x, it.y) }.toTypedArray())
            val center = Vector2(guide.center.x + offset.x, guide.center.y + offset.y)
            val petalRoles = setOf("mid1@petal", "mid0@flame", "gold@petalTip")
            val idx = guide.unitPieces.indices.filter { guide.unitPieces[it].role in petalRoles && it < cells.size }
            fun quadrant(i: Int): Int {
                val c = polygonCentroid(cells[i]) - center
                return ((Math.atan2(c.y, c.x) + 2 * Math.PI) / (Math.PI / 2)).toInt() % 4
            }
            val out = mutableListOf<GrisailleLine>()
            for ((_, members) in idx.groupBy { quadrant(it) }) {
                val union = UnaryUnionOp.union(members.map { poly(cells[it]) as Geometry })
                val frost = members.filter { guide.unitPieces[it].role == "mid1@petal" && OkLab.toLab(cellColors[it])[0] >= g.minLightness }
                if (frost.isEmpty()) continue
                val paintable = UnaryUnionOp.union(frost.map { poly(cells[it]) as Geometry }).buffer(-0.006 * guide.radius)
                val coords = union.coordinates.map { Vector2(it.x, it.y) }
                val base = coords.minByOrNull { (it - center).length }!!
                val tip = coords.maxByOrNull { (it - center).length }!!
                val axis = tip - base
                val len = axis.length
                val dir = axis / len
                val nrm = Vector2(-dir.y, dir.x)
                val steps = 60
                var lineNo = 0
                for (side in listOf(-1.0, 1.0)) for (f in g.fractions) {
                    lineNo++
                    val pts = mutableListOf<Coordinate>()
                    for (s in 0..steps) {
                        val t = 0.04 + (g.endT - 0.04) * s / steps
                        val fw = f + g.wobble * Math.sin(t * 9.0 + lineNo * 1.7) * Math.sin(t * Math.PI)
                        val a = base + axis * t
                        val probe = gf.createLineString(arrayOf(Coordinate(a.x, a.y),
                            Coordinate(a.x + nrm.x * side * len, a.y + nrm.y * side * len)))
                        val hit = probe.intersection(union)
                        val half = if (hit.isEmpty) 0.0 else hit.length
                        if (half <= 0.0) continue
                        val p = a + nrm * (side * fw * half)
                        pts += Coordinate(p.x, p.y)
                    }
                    if (pts.size < 2) continue
                    val clipped = gf.createLineString(pts.toTypedArray()).intersection(paintable)
                    for (i in 0 until clipped.numGeometries) {
                        val ls = clipped.getGeometryN(i) as? LineString ?: continue
                        val v = ls.coordinates.map { Vector2(it.x, it.y) }
                        if (v.size < 2) continue
                        // Affinement selon la position le long de l'axe du pétale.
                        val w = v.map { q ->
                            if (!g.taper) 1.0 else {
                                val t = (q - base).dot(dir) / len
                                Math.pow(((g.endT - t) / (g.endT - 0.04)).coerceIn(0.0, 1.0), 0.7)
                            }
                        }
                        out += GrisailleLine(v, w)
                    }
                }
            }
            out
        }
    }