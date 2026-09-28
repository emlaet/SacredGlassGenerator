import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import kotlin.math.sqrt

// --------------------------------------------------
// ARÊTES COURBES (utilitaires partagés)
// --------------------------------------------------
//
// Ces fonctions n'appartiennent pas à un seul système : elles sont
// utilisées à la fois par le remplissage des cellules (Verre,
// createCurvedContour), par le Plomb (Leading.kt, tracé des arêtes)
// et par la Palette (Palette.kt, détection des cellules voisines via
// edgeKey).
//
// Point essentiel : getEdgeCurve() met chaque courbe en cache sous
// une clé symétrique (edgeKey). Le remplissage d'une cellule et le
// trait de plomb qui la borde utilisent donc EXACTEMENT la même
// courbe, et deux cellules voisines partagent la même frontière.
//
// ⚠ Les coefficients de courbure (* 0.20) ont supprimé un artefact
// réel de jonction (pointe noire parasite). Ne pas les augmenter
// tant que le système de jonctions n'est pas plus sophistiqué.

data class EdgeCurve(
    val start: Vector2,
    val control1: Vector2,
    val control2: Vector2,
    val end: Vector2
)

fun edgeKey(a: Vector2, b: Vector2): String {
    val keyA = "%.3f_%.3f".format(a.x, a.y)
    val keyB = "%.3f_%.3f".format(b.x, b.y)

    return if (keyA < keyB) {
        "$keyA|$keyB"
    } else {
        "$keyB|$keyA"
    }
}

fun getEdgeCurve(
    a: Vector2,
    b: Vector2,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double
): EdgeCurve {

    val key = edgeKey(a, b)

    edgeCurveCache[key]?.let { cachedCurve ->

        if (cachedCurve.start == a && cachedCurve.end == b) {
            return cachedCurve
        }

        return EdgeCurve(
            start = a,
            control1 = cachedCurve.control2,
            control2 = cachedCurve.control1,
            end = b
        )
    }

    val dx = b.x - a.x
    val dy = b.y - a.y

    val length = sqrt(dx * dx + dy * dy)

    if (length == 0.0) {
        val curve = EdgeCurve(start = a, control1 = a, control2 = b, end = b)
        edgeCurveCache[key] = curve
        return curve
    }

    val tangentX = dx / length
    val tangentY = dy / length

    val normalX = -tangentY
    val normalY = tangentX

    val direction = if (key.hashCode() and 1 == 0) 1.0 else -1.0

    val controlDistance = length * 0.33

    val offset1 = curvatureAmount * direction * 0.20
    val offset2 = curvatureAmount * direction * 0.20

    val control1 = Vector2(
        a.x + tangentX * controlDistance + normalX * offset1,
        a.y + tangentY * controlDistance + normalY * offset1
    )

    val control2 = Vector2(
        b.x - tangentX * controlDistance + normalX * offset2,
        b.y - tangentY * controlDistance + normalY * offset2
    )

    val curve = EdgeCurve(start = a, control1 = control1, control2 = control2, end = b)

    edgeCurveCache[key] = curve

    return curve
}

fun createCurvedContour(
    cell: List<Vector2>,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double
): ShapeContour {

    if (cell.size < 3) return ShapeContour.EMPTY

    val segments = mutableListOf<Segment2D>()

    for (i in cell.indices) {

        val a = cell[i]
        val b = cell[(i + 1) % cell.size]

        val curve = getEdgeCurve(a, b, edgeCurveCache, curvatureAmount)

        segments.add(Segment2D(curve.start, curve.control1, curve.control2, curve.end))
    }

    return ShapeContour.fromSegments(segments, closed = true)
}