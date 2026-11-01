import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// --------------------------------------------------
// MOTIFS DE LA CROIX RONDOUILLARDE
// --------------------------------------------------
//
// La croix rondouillarde (ShapedCrossStyle.ROUNDED, ShapedCross.kt) peut
// recevoir un dessin intérieur plus fin, où les couleurs de la saison
// sont réparties avec une hiérarchie (au lieu de grands aplats) :
//
// - GEMS : croix gemmée. Le corps de la croix reste ivoire ; un filet
//   coloré fin suit le contour, et cinq pierres serties (au centre et sur
//   chaque bras) portent la couleur : cœur doré, pétales en amande,
//   couronne de perles. La croix ornée de pierres (crux gemmata) est
//   « typical of Early Christian and Early Medieval art » (Wikipedia,
//   « Crux gemmata ») ; la place des pierres est un choix de composition.
// - LANCETS : lancettes rayonnantes, comme dans une rose : une couronne de
//   petites lancettes autour d'un cœur doré, puis de grandes lancettes
//   vers les bras. Dégradé du clair (centre) au profond (bords).
// - RHOMBI : losanges droits posés en diagonale, autour d'un losange d'or ;
//   même dégradé.
//
// Construction : arrangement plan (polygonizeSegments) puis regroupement
// des faces par pièce, comme ShapedCross.kt. Les pièces trop fines ou
// trop petites pour être coupées dans du verre sont fondues dans leur
// voisine (mergeThinPieces). Toutes les mesures sont des fractions de
// maxRadius : même dessin à l'aperçu et à l'export.

enum class RoundedPattern {
    /** Découpe simple (médaillon, diagonales, coupes des bras) : voir RoundedCrossScheme. */
    PLAIN,
    /** Croix gemmée : corps ivoire, filet coloré, cinq pierres serties. */
    GEMS,
    /** Lancettes rayonnantes, en dégradé. */
    LANCETS,
    /** Losanges droits posés en diagonale, en dégradé autour d'un losange d'or. */
    RHOMBI
}

/** Couleurs d'une saison pour les motifs de la croix rondouillarde. */
data class RoundedCrossColors(
    val main: ColorRGBa,
    val light: ColorRGBa,
    val deep: ColorRGBa,
    val ivory: List<ColorRGBa>,
    val gold: ColorRGBa,
    val cream: ColorRGBa,
    /**
     * Dégradé des motifs LANCETS et RHOMBI, du centre aux bords. null :
     * crème → clair → principal → profond (toutes les palettes, sauf
     * « blanc lumineux » : ocre au centre → blancs aux bords).
     */
    val gradientStops: List<ColorRGBa>? = null
)

/**
 * Couleurs de la palette pour les motifs : principale (coloredMainShades),
 * claire et profonde (même choix que roundedCrossShades), ivoire des bras
 * des croix, or vif de la bordure des baies (BayStyle.BORDER_GOLD), crème.
 */
fun LiturgicalPalette.roundedCrossColors(): RoundedCrossColors {
    val tiers = roundedCrossShades(RoundedCrossScheme.PINWHEEL)   // [claire, principale, profonde]
    val lum = luminous
    if (lum != null) {
        // « Blanc lumineux » (Noël/Pâques) : l'ocre au cœur des pierres et des
        // motifs (il découpe la silhouette), le dégradé va de l'ocre au centre
        // aux blancs vers les bords. Pierres de la croix gemmée : ocre, ocre
        // pâle et champagne ; filet : ocre et ocre pâle alternés.
        val stops = lum.gradient
        return RoundedCrossColors(
            main = lum.ochre[0], light = stops[2], deep = stops[1],
            ivory = LiturgicalPalettes.CROSS_ARM_SHADES,
            gold = lum.ochre[0],
            cream = ColorRGBa.fromHex("#F7EFDD"),
            gradientStops = stops
        )
    }
    return RoundedCrossColors(
        main = tiers[1][0], light = tiers[0][0], deep = tiers[2][0],
        ivory = LiturgicalPalettes.CROSS_ARM_SHADES,
        gold = BayStyle.BORDER_GOLD[0],
        cream = ColorRGBa.fromHex("#F7EFDD")
    )
}

/** Mélange de deux couleurs, construit par fromHex (voir fromHsl, Bay.kt : linéarité). */
private fun mixColor(a: ColorRGBa, b: ColorRGBa, t: Double): ColorRGBa {
    fun h(x: Double) = "%02X".format(Math.round(x.coerceIn(0.0, 1.0) * 255).toInt())
    val u = t.coerceIn(0.0, 1.0)
    return ColorRGBa.fromHex("#" + h(a.r + (b.r - a.r) * u) + h(a.g + (b.g - a.g) * u) + h(a.b + (b.b - a.b) * u))
}

/** Dégradé du centre (t = 0) aux bords (t = 1) : crème → clair → principal → profond (ou gradientStops). */
private fun RoundedCrossColors.gradient(t: Double): ColorRGBa {
    val stops = gradientStops ?: listOf(cream, light, main, deep)
    val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = min(floor(x).toInt(), stops.size - 2)
    return mixColor(stops[i], stops[i + 1], x - i)
}

/** Petite variation stable (0 à 1) tirée de l'identifiant d'une pièce. */
private fun jitter(id: String): Double = ((id.hashCode() * 2654435761L) ushr 8 and 0xFFFF) / 65535.0

internal fun buildRoundedPattern(
    pattern: RoundedPattern,
    c: Vector2,
    m: Double,
    colors: RoundedCrossColors
): ShapedCrossGuide {
    val axes = listOf(Vector2(0.0, -1.0), Vector2(0.0, 1.0), Vector2(-1.0, 0.0), Vector2(1.0, 0.0))
    val tip = 0.80 * m
    val w = 0.36 * m
    val f = 0.07 * m
    val outline = roundedCrossOutline(c, axes, tip, w, f)
    val segments = mutableListOf<Segment>()
    segments += polygonEdgeSegments(outline)

    fun polar(r: Double, a: Double) = Vector2(c.x + r * cos(a), c.y + r * sin(a))
    fun around(center: Vector2, r: Double, n: Int = 96) =
        (0 until n).map { Vector2(center.x + r * cos(2 * PI * it / n), center.y + r * sin(2 * PI * it / n)) }
    fun radial(a: Double, r0: Double, r1: Double, center: Vector2 = c) =
        Segment(Vector2(center.x + r0 * cos(a), center.y + r0 * sin(a)), Vector2(center.x + r1 * cos(a), center.y + r1 * sin(a)))
    fun angleOf(p: Vector2, center: Vector2 = c): Double {
        val a = atan2(p.y - center.y, p.x - center.x)
        return if (a < 0) a + 2 * PI else a
    }

    // Identifiant de la pièce d'un point, et couleur d'une pièce (r : distance
    // de son centre au centre de la croix, en part de maxRadius)
    val idOf: (Vector2) -> String
    val colorOf: (String, Double) -> ColorRGBa

    when (pattern) {
        RoundedPattern.GEMS -> {
            // Filet : bande entre le contour et le même contour rentré de b
            val b = 0.045 * m
            val inner = roundedCrossOutline(c, axes, tip - b, w - b, f + b)
            segments += polygonEdgeSegments(inner)
            val bandPieces = 56
            // coupes du filet décalées d'une demi-pièce : aucune ne tombe sur
            // une diagonale ni sur l'axe d'un bras (deux plombs superposés
            // rendaient le découpage fragile)
            for (i in 0 until bandPieces) {
                val a = 2 * PI * (i + 0.5) / bandPieces
                var n = 0
                while (n < 400 && polygonContains(inner, polar(n * m / 400.0, a))) n++
                segments += radial(a, max(0.0, n * m / 400.0 - 0.01 * m), n * m / 400.0 + b + 0.03 * m)
            }
            // Pierres serties : (centre, rayon, pétales, perles)
            // Les tests d'appartenance se font sur les polygones tracés (et non
            // sur les cercles exacts) : une face bordée par un cercle approché
            // est classée du bon côté.
            class Gem(val center: Vector2, val r: Double, val petals: Int, val pearls: Int) {
                val rho = 0.78 * r
                val heart = (0 until 48).map { Vector2(center.x + 0.20 * r * cos(2 * PI * it / 48), center.y + 0.20 * r * sin(2 * PI * it / 48)) }
                val crown = (0 until 96).map { Vector2(center.x + rho * cos(2 * PI * it / 96), center.y + rho * sin(2 * PI * it / 96)) }
                val disc = (0 until 96).map { Vector2(center.x + r * cos(2 * PI * it / 96), center.y + r * sin(2 * PI * it / 96)) }
                // Cercles des pétales : un peu plus petits que rho / 2, pour ne
                // toucher ni la couronne (contact tangent) ni passer tous par le
                // centre exact — des contacts aussi fragiles changeaient le
                // découpage entre l'aperçu et l'export.
                val pr = 0.96 * rho / 2
                val petalCircles = (0 until petals).map { i ->
                    val a = 2 * PI * i / petals
                    val pc = Vector2(center.x + (pr + 0.02 * rho) * cos(a), center.y + (pr + 0.02 * rho) * sin(a))
                    (0 until 72).map { Vector2(pc.x + pr * cos(2 * PI * it / 72), pc.y + pr * sin(2 * PI * it / 72)) }
                }
            }
            val gems = listOf(Gem(c, 0.24 * m, 8, 24)) + axes.map { Gem(Vector2(c.x + it.x * 0.53 * m, c.y + it.y * 0.53 * m), 0.16 * m, 6, 14) }
            for (g in gems) {
                segments += polygonEdgeSegments(g.heart) + polygonEdgeSegments(g.crown) + polygonEdgeSegments(g.disc)
                g.petalCircles.forEach { segments += polygonEdgeSegments(it) }
                for (i in 0 until g.pearls) segments += radial(2 * PI * i / g.pearls, g.rho * 0.97, g.r * 1.03, g.center)
            }
            // Corps : diagonales, et axe de chaque bras entre les pierres et
            // au-delà de la pierre du bras
            for (q in 0 until 4) segments += radial(PI / 4 + q * PI / 2, 0.235 * m, 0.9 * m)
            for (u in axes) {
                segments += Segment(Vector2(c.x + u.x * 0.235 * m, c.y + u.y * 0.235 * m), Vector2(c.x + u.x * 0.375 * m, c.y + u.y * 0.375 * m))
                segments += Segment(Vector2(c.x + u.x * 0.685 * m, c.y + u.y * 0.685 * m), Vector2(c.x + u.x * (tip + 0.05 * m), c.y + u.y * (tip + 0.05 * m)))
            }
            idOf = { p ->
                if (!polygonContains(inner, p)) "N" + Math.floorMod(floor(angleOf(p) / (2 * PI / bandPieces) - 0.5).toInt(), bandPieces)
                else {
                    val gi = gems.indexOfFirst { polygonContains(it.disc, p) }
                    if (gi >= 0) {
                        val g = gems[gi]
                        val la = angleOf(p, g.center)
                        if (polygonContains(g.heart, p)) "G$gi:C"
                        else if (!polygonContains(g.crown, p)) "G$gi:P" + floor(la / (2 * PI / g.pearls)).toInt()
                        else {
                            // pétales et fonds : chaque face est une pièce ; sa
                            // couleur dépend du nombre de cercles qui la
                            // contiennent (2 ou plus : pétale)
                            val count = (0 until g.petals).count { i -> polygonContains(g.petalCircles[i], p) }
                            "G$gi:X$count"
                        }
                    } else {
                        val dx = p.x - c.x; val dy = p.y - c.y
                        val k = axes.indices.maxByOrNull { axes[it].x * dx + axes[it].y * dy }!!
                        val t = -axes[k].y * dx + axes[k].x * dy
                        "B$k:" + (if (t < 0) "L" else "R")
                    }
                }
            }
            colorOf = { id, _ ->
                when {
                    id.startsWith("N") -> if (id.drop(1).toInt() % 2 == 0) colors.main else colors.deep
                    id.startsWith("G") && id.endsWith(":C") -> colors.gold
                    id.startsWith("G") && id.contains(":P") -> if (id.substringAfter(":P").toInt() % 2 == 0) colors.gold else colors.deep
                    id.startsWith("G") && id.contains(":X") -> when (id.substringAfter(":X").substringBefore("#").toInt()) {
                        0 -> colors.deep
                        1 -> colors.main
                        else -> colors.light
                    }
                    else -> colors.ivory[(jitter(id) * colors.ivory.size).toInt().coerceIn(0, colors.ivory.size - 1)]
                }
            }
        }

        RoundedPattern.LANCETS -> {
            // Réglages du croquis « E1 » préféré (larges lancettes jusqu'au
            // bord, dégradé continu pièce par pièce) : chaque face est une
            // pièce, colorée selon son genre et sa distance au centre.
            val core = 0.08 * m
            val ring = 0.28 * m
            val corePoly = around(c, core, 64)
            val ringPoly = around(c, ring)
            segments += polygonEdgeSegments(corePoly)
            segments += polygonEdgeSegments(ringPoly)
            // lancette : pièce allongée vers l'extérieur, bout arrondi
            fun lancet(a: Double, r0: Double, r1: Double, hw: Double): List<Vector2> {
                val u = Vector2(cos(a), sin(a)); val v = Vector2(-u.y, u.x)
                fun p(s: Double, t: Double) = Vector2(c.x + u.x * s + v.x * t, c.y + u.y * s + v.y * t)
                val pts = mutableListOf(p(r0, -hw), p(r1 - hw, -hw))
                for (i in 1 until 24) { val bb = -PI / 2 + PI * i / 24; pts += p(r1 - hw + hw * cos(bb), hw * sin(bb)) }
                pts += p(r1 - hw, hw); pts += p(r0, hw)
                return pts
            }
            val nSmall = 12
            val nBig = 16
            val hwBig = 0.085 * m
            val small = (0 until nSmall).map { lancet(2 * PI * it / nSmall + PI / nSmall, 0.11 * m, 0.255 * m, 0.034 * m) }
            val big = (0 until nBig).map { lancet(2 * PI * it / nBig, 0.31 * m, 0.79 * m, hwBig) }
            val cut = 0.53 * m
            small.forEach { segments += polygonEdgeSegments(it) }
            big.forEach { segments += polygonEdgeSegments(it) }
            for (i in 0 until nSmall) segments += radial(2 * PI * i / nSmall, core * 0.95, ring * 1.02)
            for (i in 0 until nBig) {
                val a = 2 * PI * i / nBig
                segments += radial(a + PI / nBig, ring * 0.98, 0.82 * m)
                // moitié intérieure de la lancette coupée en deux dans sa longueur,
                // moitié extérieure d'une seule pièce
                segments += radial(a, 0.31 * m - 0.01 * m, cut + 0.005 * m)
                val u = Vector2(cos(a), sin(a)); val v = Vector2(-u.y, u.x)
                segments += Segment(Vector2(c.x + u.x * cut - v.x * hwBig * 1.05, c.y + u.y * cut - v.y * hwBig * 1.05), Vector2(c.x + u.x * cut + v.x * hwBig * 1.05, c.y + u.y * cut + v.y * hwBig * 1.05))
            }
            idOf = { p ->
                when {
                    polygonContains(corePoly, p) -> "C"
                    small.any { polygonContains(it, p) } -> "S"
                    big.any { polygonContains(it, p) } -> "L"
                    else -> "O"
                }
            }
            colorOf = { id, r ->
                when (id[0]) {
                    'C' -> colors.gold
                    'S' -> colors.gradient(0.18)
                    'L' -> colors.gradient(0.30 + 0.75 * (r - 0.31) / 0.48)
                    else -> colors.gradient(min(1.0, 0.55 + 0.6 * r))
                }
            }
        }

        RoundedPattern.RHOMBI -> {
            // Losanges droits, posés en diagonale comme une vitrerie : deux
            // familles de droites parallèles (x + y et x − y constants), le
            // losange central en or. Une première version en spirale (anneaux
            // de losanges autour du centre) faisait « toile d'araignée » :
            // des lignes parallèles évitent cet effet.
            val sp = 0.20 * m
            val kMax = (2.0 * m / sp).toInt() + 2
            val big = 2.0 * m
            for (k in -kMax..kMax) {
                val o = (k + 0.5) * sp
                // x + y = o  et  x − y = o (coordonnées relatives au centre)
                segments += Segment(Vector2(c.x + o / 2 - big, c.y + o / 2 + big), Vector2(c.x + o / 2 + big, c.y + o / 2 - big))
                segments += Segment(Vector2(c.x + o / 2 - big, c.y - o / 2 - big), Vector2(c.x + o / 2 + big, c.y - o / 2 + big))
            }
            idOf = { p ->
                val dx = p.x - c.x; val dy = p.y - c.y
                if (abs(dx + dy) < sp / 2 && abs(dx - dy) < sp / 2) "C" else "R"   // "R" : numéroté plus bas
            }
            colorOf = { id, r ->
                if (id == "C") colors.gold
                else {
                    val t = r / 0.80
                    // une pièce sur cinq environ prend le ton voisin : verre ancien
                    colors.gradient(0.10 + 0.95 * t + if (jitter(id) < 0.2) 0.16 else 0.0)
                }
            }
        }

        RoundedPattern.PLAIN -> error("PLAIN : voir buildShapedCross")
    }

    // --- Faces regroupées par pièce
    val faces = polygonizeSegments(segments).filter { polygonContains(outline, interiorPoint(it)) }
    // RHOMBI : l'identifiant d'une face est sa position (point intérieur) —
    // calculé une seule fois, puis fusion des pièces fines
    val faceIds = faces.map { idOf(interiorPoint(it)) }.toMutableList()
    // Pièces faites d'une seule face (pétales et fonds des pierres,
    // losanges) : identifiées par la position de leur centre, en part de
    // maxRadius — même identifiant à l'aperçu et à l'export (le numéro de
    // la face, lui, pouvait changer d'une échelle à l'autre).
    fun posKey(f: List<Vector2>): String {
        val g = polygonCentroid(f)
        return "#" + Math.round((g.x - c.x) / m * 1e4) + "_" + Math.round((g.y - c.y) / m * 1e4)
    }
    if (pattern == RoundedPattern.GEMS) {
        for (i in faces.indices) if (faceIds[i].contains(":X")) faceIds[i] = faceIds[i] + posKey(faces[i])
    }
    if (pattern == RoundedPattern.RHOMBI) {
        for (i in faces.indices) if (faceIds[i] != "C") faceIds[i] = "R" + posKey(faces[i])
    }
    if (pattern == RoundedPattern.LANCETS) {
        for (i in faces.indices) if (faceIds[i] != "C") faceIds[i] = faceIds[i] + posKey(faces[i])
    }
    val merged = mergeThinPieces(faces, faceIds, m)

    val pieces = mutableListOf<Pair<List<Vector2>, ColorRGBa>>()
    for ((id, group) in merged) {
        val poly = mergeFaces(group)
        val polys = if (poly != null) listOf(poly) else group
        for (pp in polys) {
            val g = polygonCentroid(pp)
            // distance arrondie : la même à l'aperçu et à l'export (un écart d'un
            // milliardième pouvait changer d'une unité le code d'une couleur)
            pieces += pp to colorOf(id, Math.round(g.distanceTo(c) / m * 1e6) / 1e6)
        }
    }
    fun key(p: List<Vector2>): Pair<Long, Long> {
        val g = polygonCentroid(p)
        return Math.round((g.y - c.y) / m * 1e6) to Math.round((g.x - c.x) / m * 1e6)
    }
    val sorted = pieces.sortedWith(compareBy({ key(it.first).first }, { key(it.first).second }))
    return ShapedCrossGuide(
        pieces = sorted.map { it.first },
        roles = sorted.map { ShapedCrossRole.ARM },
        ringGold = false,
        pieceShades = sorted.map { listOf(it.second) },
        center = c,
        style = ShapedCrossStyle.ROUNDED
    )
}

/**
 * Regroupe les faces par identifiant, puis fond chaque pièce trop fine
 * (compacité < 0,15) ou trop petite (< 0,0012 maxRadius²) dans la pièce
 * voisine avec laquelle elle partage le plus long bord — une pièce que le
 * verrier ne pourrait pas couper. Renvoie identifiant → faces.
 */
private fun mergeThinPieces(faces: List<List<Vector2>>, ids: List<String>, m: Double): Map<String, List<List<Vector2>>> {
    val groups = LinkedHashMap<String, MutableList<Int>>()
    faces.indices.forEach { groups.getOrPut(ids[it]) { mutableListOf() }.add(it) }
    fun ek(a: Vector2, b: Vector2): String {
        val ka = "${a.x}_${a.y}"; val kb = "${b.x}_${b.y}"
        return if (ka < kb) "$ka|$kb" else "$kb|$ka"
    }
    // arête → faces qui la portent
    val edgeFaces = HashMap<String, MutableList<Int>>()
    val edgeLen = HashMap<String, Double>()
    faces.forEachIndexed { fi, f ->
        for (i in f.indices) {
            val a = f[i]; val b = f[(i + 1) % f.size]
            val k = ek(a, b)
            edgeFaces.getOrPut(k) { mutableListOf() }.add(fi)
            edgeLen[k] = a.distanceTo(b)
        }
    }
    val faceGroup = HashMap<Int, String>()
    groups.forEach { (id, fs) -> fs.forEach { faceGroup[it] = id } }
    repeat(3) {
        val thin = groups.entries.map { (id, fs) ->
            val area = fs.sumOf { polygonAreaAbs(faces[it]) }
            val poly = if (fs.size == 1) faces[fs[0]] else mergeFaces(fs.map { faces[it] })
            val compact = poly?.let { polygonCompactnessRatio(it) } ?: 1.0
            Triple(id, area, compact)
        }.filter { it.second < 0.0012 * m * m || it.third < 0.15 }
            .sortedWith(compareBy({ Math.round(it.second / (m * m) * 1e7) }, { it.first }))
        if (thin.isEmpty()) return@repeat
        for ((id, _, _) in thin) {
            val fs = groups[id] ?: continue
            val shared = HashMap<String, Double>()
            for (fi in fs) {
                val f = faces[fi]
                for (i in f.indices) {
                    val k = ek(f[i], f[(i + 1) % f.size])
                    for (other in edgeFaces[k] ?: mutableListOf()) {
                        val og = faceGroup[other] ?: continue
                        // jamais dans le cœur doré : il doit rester rond
                        if (og == "C" || og.endsWith(":C")) continue
                        if (og != id) shared[og] = (shared[og] ?: 0.0) + edgeLen[k]!! / m
                    }
                }
            }
            // le plus long bord commun (arrondi : les égalités se départagent
            // par l'identifiant, de la même façon à toutes les échelles)
            val target = shared.entries.maxWithOrNull(compareBy({ Math.round(it.value * 1e5) }, { it.key }))?.key ?: continue
            groups[target]!!.addAll(fs)
            fs.forEach { faceGroup[it] = target }
            groups.remove(id)
        }
    }
    return groups.mapValues { (_, fs) -> fs.map { faces[it] } }
}