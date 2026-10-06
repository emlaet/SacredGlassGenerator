import org.openrndr.math.Vector2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// --------------------------------------------------
// ARRANGEMENT PLAN — découpe de polygones par des lignes quelconques
// --------------------------------------------------
//
// Outils géométriques génériques utilisés par les compositions à
// « lignes maîtresses + éclats » (PeriodicShardsSegmentationSystem,
// Segmentation.kt), mais sans rien de propre à un style :
//
// - polygonizeSegments() : à partir d'un ensemble de segments qui se
//   croisent (ondulations, droites, cercles approchés par des segments),
//   calcule toutes les faces fermées qu'ils délimitent — l'équivalent
//   du « polygonize » des bibliothèques de géométrie.
// - splitPolygonByPolyline() : découpe un polygone (convexe ou non) le
//   long d'une ligne brisée, en autant de morceaux que nécessaire.
//
// Principe (graphe planaire) : chaque segment est coupé à tous ses
// points d'intersection avec les autres ; les sommets très proches sont
// fusionnés (clé arrondie au millième de pixel) ; les bouts de lignes
// qui ne ferment rien (sommets de degré 1) sont retirés ; puis chaque
// face est parcourue en tournant toujours du même côté à chaque sommet.
// Les faces de surface positive sont les faces fermées ; la face
// extérieure (surface négative) est écartée.

data class Segment(val a: Vector2, val b: Vector2)

/** Aire signée (formule du lacet). */
fun signedArea(polygon: List<Vector2>): Double {
    var sum = 0.0
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        sum += p.x * q.y - q.x * p.y
    }
    return sum / 2.0
}

fun polygonAreaAbs(polygon: List<Vector2>) = abs(signedArea(polygon))

fun polygonPerimeterLength(polygon: List<Vector2>): Double {
    var sum = 0.0
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        sum += sqrt((q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y))
    }
    return sum
}

/** Compacité 4πA/P² : 1 pour un disque, proche de 0 pour une écharde. */
fun polygonCompactnessRatio(polygon: List<Vector2>): Double {
    val perimeter = polygonPerimeterLength(polygon)
    if (perimeter <= 0.0) return 0.0
    return 4.0 * Math.PI * polygonAreaAbs(polygon) / (perimeter * perimeter)
}

/** Point dans polygone (lancer de rayon, règle pair-impair). */
fun polygonContains(polygon: List<Vector2>, point: Vector2): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.y > point.y) != (pj.y > point.y)) {
            val xCross = (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
            if (point.x < xCross) inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * Un point garanti à l'intérieur du polygone (le centroïde ne l'est pas
 * toujours pour une forme concave) : milieu d'une arête, légèrement
 * décalé vers l'intérieur. Déterministe.
 */
fun interiorPoint(polygon: List<Vector2>): Vector2 {
    val orientation = if (signedArea(polygon) >= 0.0) 1.0 else -1.0
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6) continue
        val eps = min(0.05, length * 0.01)
        val candidate = Vector2(
            (a.x + b.x) / 2.0 - dy / length * eps * orientation,
            (a.y + b.y) / 2.0 + dx / length * eps * orientation
        )
        if (polygonContains(polygon, candidate)) return candidate
    }
    return polygonCentroid(polygon)
}

fun polygonBounds(polygon: List<Vector2>): DoubleArray {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (p in polygon) {
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minY = min(minY, p.y); maxY = max(maxY, p.y)
    }
    return doubleArrayOf(minX, minY, maxX, maxY)
}

/**
 * Découpe un segment au rectangle [minX, maxX] × [minY, maxY]
 * (Liang–Barsky). Renvoie null s'il est entièrement dehors.
 */
fun clipSegmentToRect(s: Segment, minX: Double, minY: Double, maxX: Double, maxY: Double): Segment? {
    var t0 = 0.0
    var t1 = 1.0
    val dx = s.b.x - s.a.x
    val dy = s.b.y - s.a.y
    val p = doubleArrayOf(-dx, dx, -dy, dy)
    val q = doubleArrayOf(s.a.x - minX, maxX - s.a.x, s.a.y - minY, maxY - s.a.y)
    for (i in 0 until 4) {
        if (p[i] == 0.0) {
            if (q[i] < 0.0) return null
        } else {
            val r = q[i] / p[i]
            if (p[i] < 0.0) { if (r > t1) return null; if (r > t0) t0 = r }
            else { if (r < t0) return null; if (r < t1) t1 = r }
        }
    }
    return Segment(
        Vector2(s.a.x + t0 * dx, s.a.y + t0 * dy),
        Vector2(s.a.x + t1 * dx, s.a.y + t1 * dy)
    )
}

fun polylineSegments(points: List<Vector2>): List<Segment> =
    (0 until points.size - 1).map { Segment(points[it], points[it + 1]) }

fun polygonEdgeSegments(polygon: List<Vector2>): List<Segment> =
    polygon.indices.map { Segment(polygon[it], polygon[(it + 1) % polygon.size]) }

/**
 * Toutes les faces fermées délimitées par les segments. Les segments
 * qui ne ferment rien sont ignorés.
 *
 * Toutes les tolérances sont RELATIVES à la taille de l'ensemble des
 * segments : on raisonne en « unités » valant 1/1000 de la plus grande
 * dimension. minArea est exprimée en unités² ; deux sommets à moins
 * d'un millième d'unité sont fusionnés. Ainsi, la même figure à
 * l'aperçu (768 px) et à l'export (plusieurs milliers de px) donne
 * exactement les mêmes faces, même quand trois lignes se croisent
 * presque au même point.
 */
fun polygonizeSegments(segments: List<Segment>, minArea: Double = 1e-3): List<List<Vector2>> {

    if (segments.isEmpty()) return emptyList()
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (s in segments) {
        minX = min(minX, min(s.a.x, s.b.x)); maxX = max(maxX, max(s.a.x, s.b.x))
        minY = min(minY, min(s.a.y, s.b.y)); maxY = max(maxY, max(s.a.y, s.b.y))
    }
    val unit = max(max(maxX - minX, maxY - minY) / 1000.0, 1e-12)

    val input = segments.filter { abs(it.a.x - it.b.x) + abs(it.a.y - it.b.y) > 1e-9 * unit }
    if (input.isEmpty()) return emptyList()

    // --- 1. Intersections, avec une grille de hachage pour ne tester
    //        que les segments proches.
    val gridCount = max(1, min(256, sqrt(input.size.toDouble()).toInt()))
    val cellW = max((maxX - minX) / gridCount, 1e-6)
    val cellH = max((maxY - minY) / gridCount, 1e-6)
    val grid = HashMap<Int, MutableList<Int>>()
    fun cellIndex(ix: Int, iy: Int) = iy * (gridCount + 1) + ix
    input.forEachIndexed { index, s ->
        val x0 = floor((min(s.a.x, s.b.x) - minX) / cellW).toInt().coerceIn(0, gridCount)
        val x1 = floor((max(s.a.x, s.b.x) - minX) / cellW).toInt().coerceIn(0, gridCount)
        val y0 = floor((min(s.a.y, s.b.y) - minY) / cellH).toInt().coerceIn(0, gridCount)
        val y1 = floor((max(s.a.y, s.b.y) - minY) / cellH).toInt().coerceIn(0, gridCount)
        for (ix in x0..x1) for (iy in y0..y1) {
            grid.getOrPut(cellIndex(ix, iy)) { mutableListOf() }.add(index)
        }
    }

    val splitParams = Array(input.size) { mutableListOf(0.0, 1.0) }
    val tested = HashSet<Long>()
    for (bucket in grid.values) {
        for (m in bucket.indices) for (n in m + 1 until bucket.size) {
            val i = min(bucket[m], bucket[n])
            val j = max(bucket[m], bucket[n])
            if (!tested.add(i.toLong() * input.size + j)) continue
            val s = input[i]; val t = input[j]
            val rx = s.b.x - s.a.x; val ry = s.b.y - s.a.y
            val sx = t.b.x - t.a.x; val sy = t.b.y - t.a.y
            val denom = rx * sy - ry * sx
            if (abs(denom) < 1e-12 * unit * unit) continue   // parallèles (chevauchements ignorés)
            val qx = t.a.x - s.a.x; val qy = t.a.y - s.a.y
            val u = (qx * sy - qy * sx) / denom
            val v = (qx * ry - qy * rx) / denom
            val e = 1e-9
            if (u < -e || u > 1 + e || v < -e || v > 1 + e) continue
            splitParams[i].add(u.coerceIn(0.0, 1.0))
            splitParams[j].add(v.coerceIn(0.0, 1.0))
        }
    }

    // --- 2. Sommets fusionnés (clé au millième d'unité) et arêtes.
    val vertexIds = HashMap<Long, Int>()
    val vertices = mutableListOf<Vector2>()
    fun vertexOf(p: Vector2): Int {
        // Coordonnées au millième d'unité, comptées depuis le coin
        // (minX, minY) : entre 0 et 1 000 000 environ, ce qui garantit une
        // clé unique sur un Long.
        val key = Math.round((p.x - minX) / unit * 1000.0) * 100_000_000L + Math.round((p.y - minY) / unit * 1000.0)
        return vertexIds.getOrPut(key) { vertices.add(p); vertices.size - 1 }
    }
    val adjacency = HashMap<Int, MutableSet<Int>>()
    input.forEachIndexed { index, s ->
        val params = splitParams[index].distinct().sorted()
        var previous = -1
        for (t in params) {
            val id = vertexOf(Vector2(s.a.x + t * (s.b.x - s.a.x), s.a.y + t * (s.b.y - s.a.y)))
            if (previous != -1 && previous != id) {
                adjacency.getOrPut(previous) { mutableSetOf() }.add(id)
                adjacency.getOrPut(id) { mutableSetOf() }.add(previous)
            }
            previous = id
        }
    }

    // --- 3. Retrait des bouts de lignes qui ne ferment rien.
    val queue = ArrayDeque(adjacency.filter { it.value.size <= 1 }.keys)
    while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        val neighbors = adjacency[v] ?: continue
        for (w in neighbors.toList()) {
            adjacency[w]?.remove(v)
            if ((adjacency[w]?.size ?: 0) == 1) queue.addLast(w)
        }
        adjacency.remove(v)
    }

    // --- 4. Voisins triés par angle, puis parcours des faces.
    val sortedNeighbors = HashMap<Int, IntArray>()
    for ((v, neighbors) in adjacency) {
        val p = vertices[v]
        sortedNeighbors[v] = neighbors.sortedBy { atan2(vertices[it].y - p.y, vertices[it].x - p.x) }.toIntArray()
    }

    val visited = HashSet<Long>()
    val total = vertices.size.toLong()
    val faces = mutableListOf<List<Vector2>>()

    for ((u0, neighbors0) in sortedNeighbors) for (v0 in neighbors0) {
        if (visited.contains(u0 * total + v0)) continue
        val face = mutableListOf<Int>()
        var u = u0
        var v = v0
        var guard = 0
        while (guard++ < 100000) {
            visited.add(u * total + v)
            face.add(u)
            val nb = sortedNeighbors[v] ?: break
            val i = nb.indexOf(u)
            val w = nb[(i - 1 + nb.size) % nb.size]
            u = v
            v = w
            if (u == u0 && v == v0) break
        }
        if (face.size >= 3) {
            val polygon = face.map { vertices[it] }
            if (signedArea(polygon) > minArea * unit * unit) faces.add(canonicalRotation(polygon))
        }
    }

    // Ordre indépendant des tables de hachage ET de l'échelle : l'aperçu
    // (768 px) et l'export (plusieurs milliers de px) doivent parcourir
    // les faces dans le même ordre, sinon les tirages aléatoires qui
    // suivent ne tomberaient pas sur les mêmes pièces.
    return faces.sortedWith(compareBy({ polygonCentroid(it).y }, { polygonCentroid(it).x }))
}

/**
 * Fait commencer le polygone par son sommet le plus haut (puis le plus
 * à gauche), sans changer le sens de parcours : une forme donnée a ainsi
 * toujours la même liste de sommets, quelle que soit l'échelle.
 */
fun canonicalRotation(polygon: List<Vector2>): List<Vector2> {
    var best = 0
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[best]
        if (p.y < q.y - 1e-9 || (kotlin.math.abs(p.y - q.y) <= 1e-9 && p.x < q.x)) best = i
    }
    return polygon.drop(best) + polygon.take(best)
}

/**
 * Découpe un polygone le long d'une ligne brisée (ouverte ou fermée).
 * Renvoie les morceaux situés à l'intérieur du polygone ; si la ligne
 * ne le traverse pas, renvoie le polygone tel quel (seul élément).
 */
fun splitPolygonByPolyline(polygon: List<Vector2>, polyline: List<Vector2>): List<List<Vector2>> {
    val b = polygonBounds(polygon)
    // Marge proportionnelle à la pièce (et non en pixels) : même découpe
    // à toutes les échelles.
    val margin = 0.01 * max(b[2] - b[0], b[3] - b[1])
    val cutSegments = polylineSegments(polyline).mapNotNull {
        clipSegmentToRect(it, b[0] - margin, b[1] - margin, b[2] + margin, b[3] + margin)
    }
    if (cutSegments.isEmpty()) return listOf(polygon)

    val faces = polygonizeSegments(polygonEdgeSegments(polygon) + cutSegments, 0.25)
        .filter { polygonContains(polygon, interiorPoint(it)) }

    return if (faces.size <= 1) listOf(polygon) else faces
}

// --------------------------------------------------
// ÉTROITESSE — une pièce que le verrier pourrait couper ?
// --------------------------------------------------
//
// Question posée : « un disque de rayon `radius` peut-il atteindre
// toutes les parties de la pièce ? » (ouverture morphologique). Les
// parties qu'il n'atteint pas forment le RÉSIDU ÉTROIT : pointes trop
// aiguës, goulets, lanières le long d'un arc presque tangent à un
// plomb, pièce entière trop petite pour contenir le disque…
//
// Pour une pointe d'angle α, le résidu vaut r² × (cot(α/2) − (π − α)/2) :
// 0,21 r² pour un angle droit, 1,2 r² à 45°, 1,9 r² à 35°, 2,4 r² à 30°,
// 4,3 r² à 20°. Un seuil de 2 r² accepte donc les pointes jusqu'à 35°
// environ et refuse tout ce qui est plus effilé.
//
// Calcul sur une grille de pas radius / NARROW_GRID_STEPS dont l'origine
// est FIXE (0, 0) : deux pièces (une pièce et une de ses parties) sont
// évaluées sur les mêmes cases, ce qui permet de ne compter que le
// résidu NOUVEAU créé par une coupe (voir newNarrowResidue). La grille
// étant proportionnelle à radius, lui-même proportionnel à la tuile, les
// décisions sont les mêmes à l'aperçu et à l'export.

const val NARROW_GRID_STEPS = 5

/** Clé d'une case (i, j) de la grille globale. */
private fun cellKey(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

private fun pointSegmentDistance(p: Vector2, a: Vector2, b: Vector2): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    var t = if (len2 > 0.0) ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2 else 0.0
    t = t.coerceIn(0.0, 1.0)
    val qx = a.x + t * dx - p.x
    val qy = a.y + t * dy - p.y
    return sqrt(qx * qx + qy * qy)
}

/**
 * Cases (clés de la grille globale) de la pièce que le disque de rayon
 * radius n'atteint pas.
 */
fun narrowResidueCells(polygon: List<Vector2>, radius: Double): Set<Long> {

    val h = radius / NARROW_GRID_STEPS
    val b = polygonBounds(polygon)
    val i0 = floor(b[0] / h).toInt() - 1
    val j0 = floor(b[1] / h).toInt() - 1
    val i1 = floor(b[2] / h).toInt() + 1
    val j1 = floor(b[3] / h).toInt() + 1
    val nx = i1 - i0 + 1
    val ny = j1 - j0 + 1

    // 1. Cases intérieures (balayage ligne par ligne).
    val inside = BooleanArray(nx * ny)
    val crossings = ArrayList<Double>()
    for (j in 0 until ny) {
        val y = (j0 + j + 0.5) * h
        crossings.clear()
        var k = polygon.lastIndex
        for (m in polygon.indices) {
            val p = polygon[m]
            val q = polygon[k]
            if ((p.y > y) != (q.y > y)) {
                crossings.add(p.x + (y - p.y) * (q.x - p.x) / (q.y - p.y))
            }
            k = m
        }
        crossings.sort()
        var c = 0
        while (c + 1 < crossings.size) {
            val from = kotlin.math.ceil(crossings[c] / h - 0.5).toInt() - i0
            val to = floor(crossings[c + 1] / h - 0.5).toInt() - i0
            for (i in max(from, 0)..min(to, nx - 1)) inside[j * nx + i] = true
            c += 2
        }
    }

    // 2. Centres possibles du disque : cases à distance ≥ radius du bord.
    val centers = ArrayList<Int>()
    for (idx in inside.indices) {
        if (!inside[idx]) continue
        val p = Vector2((i0 + idx % nx + 0.5) * h, (j0 + idx / nx + 0.5) * h)
        var farEnough = true
        var k = polygon.lastIndex
        for (m in polygon.indices) {
            if (pointSegmentDistance(p, polygon[k], polygon[m]) < radius) { farEnough = false; break }
            k = m
        }
        if (farEnough) centers.add(idx)
    }

    // 3. Cases couvertes par au moins un disque.
    val covered = BooleanArray(nx * ny)
    val reach = NARROW_GRID_STEPS
    for (idx in centers) {
        val ci = idx % nx
        val cj = idx / nx
        for (dj in -reach..reach) for (di in -reach..reach) {
            if (di * di + dj * dj > reach * reach) continue
            val i = ci + di
            val j = cj + dj
            if (i in 0 until nx && j in 0 until ny) covered[j * nx + i] = true
        }
    }

    // 4. Résidu = intérieur non couvert.
    val residue = HashSet<Long>()
    for (idx in inside.indices) {
        if (inside[idx] && !covered[idx]) residue.add(cellKey(i0 + idx % nx, j0 + idx / nx))
    }
    return residue
}

/**
 * Aire (en unités radius²) du plus grand amas de résidu étroit présent
 * dans `part` mais pas dans `parentResidue` (le résidu de la pièce
 * avant la coupe) : l'étroitesse CRÉÉE par la coupe. Les pointes
 * aiguës déjà présentes (croisement de deux lignes maîtresses, par
 * exemple) ne sont pas comptées : elles appartiennent au dessin
 * d'ensemble, pas à la coupe.
 */
fun newNarrowResidue(part: List<Vector2>, parentResidue: Set<Long>, radius: Double): Double {

    val fresh = narrowResidueCells(part, radius).filterTo(HashSet()) { it !in parentResidue }
    val seen = HashSet<Long>()
    var largest = 0
    for (start in fresh) {
        if (!seen.add(start)) continue
        var size = 0
        val stack = ArrayDeque<Long>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val key = stack.removeLast()
            size++
            val i = (key shr 32).toInt()
            val j = key.toInt()
            for (dj in -1..1) for (di in -1..1) {
                val n = cellKey(i + di, j + dj)
                if (n in fresh && seen.add(n)) stack.addLast(n)
            }
        }
        largest = max(largest, size)
    }
    // Aire d'une case = (radius / NARROW_GRID_STEPS)². Calcul en entiers
    // puis une seule division exacte : le résultat est rigoureusement le
    // même à l'aperçu et à l'export (un calcul via h * h / radius² peut
    // différer au dernier chiffre et faire basculer une décision prise
    // pile au seuil).
    return largest.toDouble() / (NARROW_GRID_STEPS * NARROW_GRID_STEPS)
}