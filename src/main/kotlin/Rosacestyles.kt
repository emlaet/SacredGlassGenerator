import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.polygonize.Polygonizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// ROSACES B ET C (dessins retenus le 1er nov. 2026)
// --------------------------------------------------
//
// Trois familles de composition, chacune avec sa propre logique (règle 7),
// portées du prototype Python (claude/prototype_rosace_trois_styles.py,
// documents du projet : design_B2, design_C2, design_C4) :
//
// - B, CURVILIGNE (flamboyant → Art nouveau) : remplage en pierre fait de
//   courbes ; 12 soufflets jointifs en forme de flamme (avec un soufflet
//   intérieur), mouchettes entre eux, hexalobe central ; couronne de 24
//   lancettes à tête en accolade (une traverse droite + une traverse en arc),
//   petits points dorés au bord. Pas de croix.
// - C « DALLE » (contemporain) : pas de pierre ; meneaux radiaux épais (un sur
//   deux plus marqué) et anneaux inégaux, panneaux droits ; croix claire dans
//   les quatre axes, jusqu'au centre.
// - C « ARCADES » (contemporain) : pas de pierre ; secteurs de largeurs
//   différentes (panneau large sur l'axe), lancettes à tête en plein cintre,
//   un oculus par secteur coupé par un rayon ; croix portée par les panneaux
//   des axes.
//
// Couleurs : une RosacePalette (VitrailConfig.rosacePalette, Rosace.kt) ;
// chaque pièce porte son RÔLE (out0, mid1, cen0, gold…), la couleur en
// découle. Aucun tirage aléatoire.
//
// Plombs hiérarchisés, comme dans le prototype : les plombs entre les pièces
// sont les FINS (0,6 × la largeur de la recette) ; par-dessus, les plombs
// secondaires (1,0 ×) et de structure (B : 1,6 × autour de chaque ouverture
// de la pierre ; C « dalle » : 2,4 × ; C « arcades » : 2,2 ×).
//
// Différences avec le prototype :
// - les pièces « à trous » (une pièce qui en entoure d'autres) n'existent
//   pas dans le générateur : elles sont recoupées par un rayon passant par
//   chaque trou (B : soufflets et bande des points dorés ; C « arcades » :
//   panneau autour de chaque oculus), ce qui garde la symétrie ;
// - les dessins C sont agrandis de 0,965 à 1 : le verre va jusqu'au contour
//   extérieur (dans le prototype, un mince anneau vide restait autour).
//
// Réglés pour 12 rotations (les dessins validés) ; d'autres nombres sont
// possibles mais n'ont pas été évalués. La croix des dessins C demande un
// multiple de 4.

/** Rosace B, curviligne. Voir l'en-tête de RosaceStyles.kt. */
data class CurvilinearRosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(StyledRosace.Style.CURVILINEAR, rotations).place(width, height, sizeRatio, orientationDegrees)
}

/** Rosace B « orientale » (brief du 2 nov. 2026) : la périphérie de B, avec
 *  un nouveau motif central. variant : ORIENTAL_ALMONDS (B1, amandes),
 *  ORIENTAL_STAR (B2, étoile entrelacée, médaillon en étoile),
 *  ORIENTAL_STAR_FOIL (B2, médaillon architectural en quadrilobe),
 *  ORIENTAL_RINGS (B3, anneaux) ou ORIENTAL_INTERLACE (B3, vrai entrelacs). */
data class OrientalRosaceCompositionSystem(
    val variant: StyledRosace.Style = StyledRosace.Style.ORIENTAL_ALMONDS,
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(variant, rotations).place(width, height, sizeRatio, orientationDegrees)
}

/** Rosace C contemporaine, sans damier (brief du 2 nov. 2026). Quatre axes :
 *  le nombre de rotations n'intervient pas. */
data class ContemporaryRosaceCompositionSystem(
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(StyledRosace.Style.CONTEMPORARY, 12).place(width, height, sizeRatio, orientationDegrees)
}

/** Rosace E, Art nouveau (brief du 2 nov. 2026). */
data class ArtNouveauRosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(StyledRosace.Style.ART_NOUVEAU, rotations).place(width, height, sizeRatio, orientationDegrees)
}

/** Rosace C « dalle ». Voir l'en-tête de RosaceStyles.kt. */
data class SlabRosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(StyledRosace.Style.SLAB, rotations).place(width, height, sizeRatio, orientationDegrees)
}

/** Rosace C « arcades ». Voir l'en-tête de RosaceStyles.kt. */
data class ArcadeRosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {
    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide =
        StyledRosace.of(StyledRosace.Style.ARCADE, rotations).place(width, height, sizeRatio, orientationDegrees)
}

// ================================================================ outils (JTS)

private val SGF = GeometryFactory()
private val sqrt3 = sqrt(3.0)

private fun sPolar(r: Double, a: Double) = Coordinate(r * cos(a), r * sin(a))
private fun sLine(cs: List<Coordinate>): LineString = SGF.createLineString(cs.toTypedArray())
private fun sPoint(c: Coordinate) = SGF.createPoint(c)
private fun sRound(x: Double): Long = Math.rint(x).toLong()   // arrondi « à la Python »
private fun sUnion(gs: List<Geometry>): Geometry = OverlayNGRobust.union(gs)
private fun sInter(a: Geometry, b: Geometry): Geometry = OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION)
private fun sDiff(a: Geometry, b: Geometry): Geometry = OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE)
private fun sRotate(g: Geometry, a: Double): Geometry = AffineTransformation.rotationInstance(a).transform(g)
private fun sMirror(g: Geometry): Geometry = AffineTransformation.scaleInstance(1.0, -1.0).transform(g)

private fun sPolygons(g: Geometry): List<Polygon> = when (g) {
    is Polygon -> if (g.isEmpty) emptyList() else listOf(g)
    is GeometryCollection -> (0 until g.numGeometries).flatMap { sPolygons(g.getGeometryN(it)) }
    else -> emptyList()
}

private fun sLineStrings(g: Geometry): List<LineString> = when (g) {
    is LineString -> if (g.isEmpty) emptyList() else listOf(g)
    is GeometryCollection -> (0 until g.numGeometries).flatMap { sLineStrings(g.getGeometryN(it)) }
    else -> emptyList()
}

/** Cercle (polyligne fermée), n sommets arrondi au multiple de 48. */
private fun sCirc(c: Coordinate, r: Double, n: Int): LineString {
    val nn = 48 * maxOf(1L, sRound(n / 48.0)).toInt()
    val pts = (0 until nn).map { k -> Coordinate(c.x + r * cos(2 * PI * k / nn), c.y + r * sin(2 * PI * k / nn)) }
    return sLine(pts + pts[0])
}

/** Courbe de Bézier cubique échantillonnée (n + 1 points). */
private fun sBez(p0: Coordinate, p1: Coordinate, p2: Coordinate, p3: Coordinate, n: Int = 48): List<Coordinate> =
    (0..n).map { k ->
        val t = k.toDouble() / n
        val u = 1 - t
        Coordinate(
            u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y
        )
    }

/** Contour d'un polylobe inscrit dans un cercle de rayon r. */
private fun sFoil(c: Coordinate, r: Double, lobes: Int, a0: Double, cusp: Double = 0.62): LineString {
    val rho = r * (1 - cusp) * 1.15
    val d = r - rho
    val parts = mutableListOf<Geometry>(sPoint(c).buffer(r * cusp * 0.95, 48))
    for (k in 0 until lobes) {
        val a = a0 + 2 * PI * k / lobes
        parts.add(sPoint(Coordinate(c.x + d * cos(a), c.y + d * sin(a))).buffer(rho, 48))
    }
    val shape = sPolygons(sUnion(parts)).maxByOrNull { it.area }!!
    return sLine(shape.exteriorRing.coordinates.toList())
}

/** Découpe un polygone par des lignes (les pièces voisines partagent exactement leurs arêtes). */
private fun sSplit(poly: Polygon, lines: List<LineString>): List<Polygon> {
    val pb = poly.buffer(1e-9)
    val ls = mutableListOf<Geometry>()
    for (ln in lines) {
        val x = sInter(ln, pb)
        if (!x.isEmpty) ls.addAll(sLineStrings(x))
    }
    if (ls.isEmpty()) return listOf(poly)
    val rings = mutableListOf<Geometry>(sLine(poly.exteriorRing.coordinates.toList()))
    for (i in 0 until poly.numInteriorRing) rings.add(sLine(poly.getInteriorRingN(i).coordinates.toList()))
    val pz = Polygonizer()
    pz.add(sUnion(rings + ls))
    val big = poly.buffer(1e-7)
    val parts = pz.polygons.map { it as Polygon }.filter { it.area > 1e-6 && big.contains(it.interiorPoint) }
    return parts.ifEmpty { listOf(poly) }
}

/** Lignes d'une demi-secteur → disque entier (miroir, puis rotations). */
private fun <T> sReplicate(lines: List<Pair<LineString, T>>, n: Int): List<Pair<LineString, T>> {
    val out = mutableListOf<Pair<LineString, T>>()
    for ((ln, cl) in lines) {
        val m = sMirror(ln)
        for (i in 0 until n) {
            val a = i * 2 * PI / n
            out.add((sRotate(ln, a) as LineString) to cl)
            out.add((sRotate(m, a) as LineString) to cl)
        }
    }
    return out
}

/** Prolonge une ligne ouverte de eps à ses deux bouts : posée sur une autre
 *  ligne, elle la croise franchement (pas d'interstice dû aux arrondis). */
private fun sExtend(ln: LineString, eps: Double = 0.004): LineString {
    if (ln.isClosed) return ln
    val c = ln.coordinates.toList()
    fun ext(a: Coordinate, b: Coordinate): Coordinate {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val d = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
        return Coordinate(a.x + dx / d * eps, a.y + dy / d * eps)
    }
    return sLine(listOf(ext(c[0], c[1])) + c + listOf(ext(c[c.size - 1], c[c.size - 2])))
}

/** Découpe le disque de rayon r par toutes les lignes (dessins C). */
private fun sArrangement(lines: List<LineString>, r: Double = 0.965): List<Polygon> {
    val disc = sPoint(Coordinate(0.0, 0.0)).buffer(r, 720)
    val db = disc.buffer(1e-9)
    val all = lines.map { sInter(sExtend(it), db) } + listOf(sLine((disc as Polygon).exteriorRing.coordinates.toList()))
    val pz = Polygonizer()
    pz.add(sUnion(all))
    val inside = disc.buffer(1e-6)
    return pz.polygons.map { it as Polygon }.filter { it.area > 2e-6 && inside.contains(it.interiorPoint) }
}

/** Point ramené dans la demi-secteur : (rayon, angle local ≥ 0, numéro du secteur). */
private fun sCanon(x: Double, y: Double, n: Int): Triple<Double, Double, Int> {
    val r = hypot(x, y)
    val a = atan2(y, x)
    val step = 2 * PI / n
    val k = sRound(a / step)
    return Triple(r, abs(a - k * step), k.mod(n.toLong()).toInt())
}

/**
 * Pièces sans trou : chaque pièce qui en entoure d'autres est recoupée par
 * des rayons (depuis le centre) passant par ses trous — découpe symétrique.
 * Un trou centré (une couronne autour du centre) est recoupé par des rayons
 * aux angles centredRays (les axes de la rosace). S'il reste un trou (cas
 * non prévu), coupe horizontale de secours.
 */
private fun sWithoutHoles(p: Polygon, centredRays: List<Double>): List<Polygon> {
    if (p.numInteriorRing == 0) return listOf(p)
    fun ray(a: Double) = sLine(listOf(Coordinate(0.0, 0.0), sPolar(1.2, a)))
    val rays = (0 until p.numInteriorRing).mapNotNull { i ->
        val c = p.getInteriorRingN(i).centroid.coordinate
        if (hypot(c.x, c.y) < 1e-3) null else ray(atan2(c.y, c.x))
    }
    // 1. rayons passant par les trous ; 2. s'il reste une couronne autour du
    // centre, rayons sur les axes ; 3. coupe horizontale de secours
    val parts = (if (rays.isEmpty()) listOf(p) else sSplit(p, rays))
        .flatMap { q -> if (q.numInteriorRing == 0) listOf(q) else sSplit(q, centredRays.map { ray(it) }) }
    return parts.flatMap { q ->
        if (q.numInteriorRing == 0) listOf(q) else {
            val hc = q.getInteriorRingN(0).centroid.coordinate
            val env = q.envelopeInternal
            sSplit(q, listOf(sLine(listOf(Coordinate(env.minX - 1, hc.y), Coordinate(env.maxX + 1, hc.y)))))
        }
    }
}

// ================================================================ géométrie

/**
 * Une rosace B ou C, en unités (rayon 1) : pièces, rôle de couleur de
 * chaque pièce, plombs épais. Calculée une fois par style et par nombre de
 * rotations (voir of()).
 */
class StyledRosace private constructor(val style: Style, val rotations: Int) {

    enum class Style {
        /** B, curviligne : soufflets (dessin d'origine). */
        CURVILINEAR,
        /** B1, orientale : amandes à double contour. */
        ORIENTAL_ALMONDS,
        /** B2, orientale : étoile entrelacée, médaillon en étoile rayonnante. */
        ORIENTAL_STAR,
        /** B2, orientale : étoile entrelacée, médaillon architectural (quadrilobe). */
        ORIENTAL_STAR_FOIL,
        /** B3, orientale : chaîne d'anneaux entrelacés (amandes et étoile). */
        ORIENTAL_RINGS,
        /** B3, orientale : vrai entrelacs (guillochis à deux brins autour de disques). */
        ORIENTAL_INTERLACE,
        /** C « dalle » (dessin d'origine, en damier). */
        SLAB,
        /** C contemporaine, sans damier : croix pattée, arcs sur les diagonales. */
        CONTEMPORARY,
        /** C « arcades » (piste D du brief). */
        ARCADE,
        /** E, Art nouveau : coups de fouet, anneau ondulé. */
        ART_NOUVEAU
    }

    /** Pierre sous le verre : les dessins B (et ses variantes). */
    private val stoneStyles = setOf(Style.CURVILINEAR, Style.ORIENTAL_ALMONDS, Style.ORIENTAL_STAR, Style.ORIENTAL_STAR_FOIL, Style.ORIENTAL_RINGS, Style.ORIENTAL_INTERLACE)

    class Lead(val coords: List<Coordinate>, val closed: Boolean, val factor: Double)

    companion object {
        private val cache = mutableMapOf<Pair<Style, Int>, StyledRosace>()

        fun of(style: Style, rotations: Int): StyledRosace = synchronized(cache) {
            cache.getOrPut(style to rotations) { StyledRosace(style, rotations) }
        }

        /** Plombs fins entre les pièces, en multiple de la largeur de la recette. */
        const val FINE = 0.6
    }

    val pieces = mutableListOf<Polygon>()
    val roles = mutableListOf<String>()
    val leads = mutableListOf<Lead>()
    val hasStone: Boolean get() = style in stoneStyles

    init {
        require(rotations in 4..48) { "rotations doit être entre 4 et 48 (reçu : $rotations)" }
        when (style) {
            Style.CURVILINEAR, Style.ORIENTAL_ALMONDS, Style.ORIENTAL_STAR, Style.ORIENTAL_STAR_FOIL,
            Style.ORIENTAL_RINGS, Style.ORIENTAL_INTERLACE -> buildCurvilinear()
            Style.SLAB -> buildSlab()
            Style.CONTEMPORARY -> buildContemporary()
            Style.ARCADE -> buildArcade()
            Style.ART_NOUVEAU -> buildArtNouveau()
        }
        if (style !in stoneStyles) scaleToUnit(1.0 / 0.965)
    }

    /** Ajoute une pièce (recoupée si elle a des trous), avec son rôle. */
    private fun add(p: Polygon, role: String) {
        for (q in sWithoutHoles(p, (0 until rotations).map { it * 2 * PI / rotations })) {
            pieces.add(q)
            roles.add(role)
        }
    }

    private fun addLead(g: Geometry, factor: Double) {
        for (ls in sLineStrings(g)) leads.add(Lead(ls.coordinates.toList(), ls.isClosed, factor))
        for (p in sPolygons(g)) {
            leads.add(Lead(p.exteriorRing.coordinates.toList(), true, factor))
            for (i in 0 until p.numInteriorRing) leads.add(Lead(p.getInteriorRingN(i).coordinates.toList(), true, factor))
        }
    }

    private fun scaleToUnit(f: Double) {
        val t = AffineTransformation.scaleInstance(f, f)
        for (i in pieces.indices) pieces[i] = t.transform(pieces[i]) as Polygon
        for (i in leads.indices) {
            val l = leads[i]
            leads[i] = Lead(l.coords.map { Coordinate(it.x * f, it.y * f) }, l.closed, l.factor)
        }
    }

    /** La rosace placée dans le cadre (pixels). */
    fun place(width: Double, height: Double, sizeRatio: Double, orientationDegrees: Double): RosaceGuide {
        val pl = RosacePlacement(width, height, sizeRatio, orientationDegrees)
        val cells = pieces.map { pl.cell(it) }
        val unit = pieces.indices.map { RosacePiece(pieces[it], style.name, roles[it], 0.0) }
        val lines = leads.map { l ->
            val cs = if (l.closed && l.coords.size > 1) l.coords.dropLast(1) else l.coords
            RosaceLeadLine(cs.map { pl.map(it.x, it.y) }, l.closed, l.factor)
        }
        return RosaceGuide(cells, null, unit, pl.center, pl.radius, hasStone = hasStone, cellLeadFactor = FINE, leadLines = lines)
    }

    // ------------------------------------------------------------ B — curviligne

    private fun buildCurvilinear() {
        val n = rotations
        val s = 2 * PI / n
        val h = s / 2
        val w1 = 0.020
        val w2 = 0.013
        val w3 = 0.008
        val rC = 0.17
        val rM = 0.60
        val rOut = 0.965
        val o = Coordinate(0.0, 0.0)
        val sk = mutableListOf<Pair<LineString, Double>>(
            sCirc(o, rOut, 720) to w1 * 1.3, sCirc(o, rC, 480) to w1, sCirc(o, rM, 720) to w1
        )
        // motif central (entre le médaillon et le cercle médian) : les soufflets
        // d'origine, ou l'un des trois motifs « orientaux » (B1, B2, B3)
        val motif: CentreMotif = when (style) {
            Style.ORIENTAL_ALMONDS -> AlmondMotif(n, rC, rM, w2, w3)
            Style.ORIENTAL_STAR -> StarMotif(n, rC, rM, w2, w3, starMedallion = true)
            Style.ORIENTAL_STAR_FOIL -> StarMotif(n, rC, rM, w2, w3, starMedallion = false)
            Style.ORIENTAL_RINGS -> RingMotif(n, rC, rM, w2, w3)
            Style.ORIENTAL_INTERLACE -> if (n % 2 == 0) InterlaceMotif(n, rC, rM) else RingMotif(n, rC, rM, w2, w3)
            else -> SouffletMotif(n, rC, rM, w2, w3)
        }
        sk.addAll(motif.lines)
        sk.addAll(motif.medallion)
        val sSpr = 0.80
        for (i in 0 until 2 * n) {
            val a = i * h
            sk.add(sLine(listOf(sPolar(rM, a + h / 2), sPolar(sSpr, a + h / 2))) to w2)
            sk.add(sLine(ogee(sPolar(sSpr, a - h / 2), sPolar(sSpr, a + h / 2), 0.085)) to w2)
            sk.add(sCirc(sPolar(0.905, a + h / 2), 0.032, 96) to w3 * 1.3)
        }
        val stone = sUnion(sk.map { (ln, w) -> ln.buffer(w / 2, 8) })
        val disc = sPoint(o).buffer(rOut, 720)
        val ops = sPolygons(sDiff(disc, stone)).filter { it.area > 3e-5 }
        for (op in ops) {
            val rp = op.interiorPoint.coordinate
            val r = hypot(rp.x, rp.y)
            val a = atan2(rp.y, rp.x)
            var cuts = mutableListOf<LineString>()
            if (r > rM && r < sSpr + 0.02 && op.area > 0.004) {
                // lancette de la couronne : une traverse droite, une traverse en arc
                val ac = sRound(a / h) * h
                cuts.add(sLine(listOf(sPolar(0.71, ac - 0.2), sPolar(0.71, ac + 0.2))))
                cuts.add(sLine((0..24).map { k -> sPolar(0.775, ac - 0.2 + k * 0.4 / 24) }))
            }
            if (r < rC) cuts = motif.medallionCuts(op).toMutableList()
            if (r > rC && r < rM) cuts.addAll(motif.cuts(rp))
            val parts = if (cuts.isEmpty()) listOf(op) else sSplit(op, cuts)
            for (q in parts) {
                val qp = q.interiorPoint.coordinate
                val qr = hypot(qp.x, qp.y)
                val qa = atan2(qp.y, qp.x)
                val role = when {
                    qr < rC -> motif.medallionRole(q, qp)
                    qr < rM -> motif.role(qp)
                    qr < sSpr + 0.09 -> {
                        // dégradé du pied à l'accolade, décalé une lancette sur deux
                        // (étiquettes après @ : voir RosacePalette.overrides)
                        val g = sRound(qa / h).mod(2L)
                        when {
                            qr < 0.71 -> if (g == 0L) "mid0@footA" else "mid1@footB"
                            qr < 0.775 -> if (g == 0L) "acc@middleA" else "mid0@middleB"
                            else -> if (g == 0L) "out1@ogeeA" else "acc@ogeeB"
                        }
                    }
                    else -> {
                        val dotAxis = sRound((qa - h / 2) / h) * h + h / 2
                        val dc = sPolar(0.905, dotAxis)
                        if (hypot(qp.x - dc.x, qp.y - dc.y) < 0.03) "gold" else "deepBig"
                    }
                }
                add(q, role)
            }
            addLead(op, 1.6)   // plomb de structure autour de chaque ouverture
        }
    }

    /** Arc en accolade (à contre-courbe) entre pl et pr, pointe à la hauteur hh vers l'extérieur. */
    private fun ogee(pl: Coordinate, pr: Coordinate, hh: Double, m: Int = 32): List<Coordinate> {
        val mx = (pl.x + pr.x) / 2
        val my = (pl.y + pr.y) / 2
        val rr = hypot(mx, my)
        val ox = mx / rr
        val oy = my / rr
        val apex = Coordinate(mx + ox * hh, my + oy * hh)
        val k = 0.55 * hh
        val left = sBez(pl, Coordinate(pl.x + ox * k, pl.y + oy * k), Coordinate(apex.x - ox * k, apex.y - oy * k), apex, m)
        val right = sBez(apex, Coordinate(apex.x - ox * k, apex.y - oy * k), Coordinate(pr.x + ox * k, pr.y + oy * k), pr, m)
        return left + right.drop(1)
    }

    // ------------------------------------------------------------ C — « dalle »

    private fun buildSlab() {
        val n = rotations
        val s = 2 * PI / n
        val h = s / 2
        val rr = listOf(0.0, 0.13, 0.33, 0.58, 0.80, 0.965)
        val o = Coordinate(0.0, 0.0)
        val lines = mutableListOf<Pair<LineString, String>>()
        for ((r, cl) in listOf(rr[1] to "second", rr[2] to "struct", rr[3] to "second", rr[4] to "struct")) {
            lines.add(sCirc(o, r, 720) to cl)
        }
        val w3 = 0.30 * h
        val w4a = 0.42 * h
        val w5 = 0.50 * h
        val sec = listOf(
            sLine(listOf(sPolar(rr[1], h), sPolar(rr[2], h))) to "second",
            sLine(listOf(sPolar(rr[2], w3), sPolar(rr[3], w3))) to "second",
            sLine(listOf(sPolar(rr[3], w4a), sPolar(rr[4], w4a))) to "second",
            sLine(listOf(sPolar(0.69, 0.0), sPolar(0.69, h))) to "fine",
            sLine(listOf(sPolar(rr[4], w5), sPolar(rr[5], w5))) to "second"
        )
        lines.addAll(sReplicate(sec, n))
        // meneaux : un sur deux plus marqué
        val big = sLine(listOf(sPolar(rr[2], h), sPolar(rr[5], h)))
        for (i in 0 until n) lines.add((sRotate(big, i * s) as LineString) to (if (i % 2 == 0) "struct" else "second"))
        for (k in 0 until 4) lines.add(sLine(listOf(o, sPolar(rr[1], k * PI / 2))) to "fine")
        val faces = sArrangement(lines.map { it.first })
        val crossOn = n % 4 == 0
        for (f in faces) {
            val p = f.interiorPoint.coordinate
            val (r, la, _) = sCanon(p.x, p.y, n)
            val sectorK = sRound(atan2(p.y, p.x) / s).mod(n.toLong()).toInt()
            val onAxis = crossOn && sectorK % (n / 4) == 0
            val role = when {
                r < rr[1] -> if (r < 0.05) "gold" else "cen0"
                r < rr[2] -> if (onAxis) "cross" else "cen1"
                r < rr[3] -> if (la < w3) (if (onAxis) "cross" else "mid1") else "mid0"
                r < rr[4] -> if (la < w4a) (if (onAxis) "cross" else if (r < 0.69) "mid0" else "out1")
                else (if (r < 0.69) "out0" else "deep")
                else -> if (la < w5) (if (onAxis) "cen1" else "out0") else (if (sectorK % 2 == 0) "acc" else "deep")
            }
            add(f, role)
        }
        addClassLeads(lines, mapOf("struct" to 2.4, "second" to 1.0))
    }

    // ------------------------------------------------------------ C — « arcades »

    private fun buildArcade() {
        val n = rotations
        val s = 2 * PI / n
        val h = s / 2
        val r0 = 0.15
        val r1 = 0.36
        val r2 = 0.52
        val r3 = 0.62
        val r4 = 0.84
        val r5 = 0.965
        val wb = 0.6 * h   // demi-largeur du panneau large
        val o = Coordinate(0.0, 0.0)
        val lines = mutableListOf(
            sCirc(o, r0, 480) to "struct", sCirc(o, r1, 600) to "struct",
            sCirc(o, r3, 720) to "struct", sCirc(o, r4, 720) to "second"
        )
        val pb = sPolar(r2, wb)
        val cc = Coordinate(r2 * cos(wb), 0.0)
        val rad = pb.y
        val ocC = Coordinate(0.73, 0.0)
        val ocR = 0.072
        val sec = listOf(
            sLine(listOf(sPolar(r0, h), sPolar(r5, h))) to "struct",          // frontière de secteur
            sLine(listOf(sPolar(r1, wb), sPolar(r2, wb))) to "second",        // bord de la lancette
            sLine(listOf(sPolar(r3, wb), sPolar(r5, wb))) to "second",        // bord du panneau large
            sLine((0..24).map { k -> val t = PI / 2 * k / 24; Coordinate(cc.x + rad * cos(t), cc.y + rad * sin(t)) }) to "second",  // plein cintre
            sLine(listOf(sPolar(0.44, 0.0), sPolar(0.44, wb))) to "fine",     // traverse
            sLine((0..48).map { k -> val t = PI * k / 48; Coordinate(ocC.x + ocR * cos(t), ocR * sin(t)) }) to "second",  // oculus
            sLine(listOf(Coordinate(ocC.x - ocR, 0.0), Coordinate(ocC.x + ocR, 0.0))) to "fine"
        )
        lines.addAll(sReplicate(sec, n))
        for (k in 0 until 4) lines.add(sLine(listOf(o, sPolar(r0, k * PI / 2))) to "fine")
        val faces = sArrangement(lines.map { it.first })
        val crossOn = n % 4 == 0
        for (f in faces) {
            val p = f.interiorPoint.coordinate
            val (r, la, _) = sCanon(p.x, p.y, n)
            val sectorK = sRound(atan2(p.y, p.x) / s).mod(n.toLong()).toInt()
            val onAxis = crossOn && sectorK % (n / 4) == 0
            val lx = r * cos(la)
            val ly = r * sin(la)
            val wide = la < wb
            val inLancet = wide && (r < r2 || hypot(lx - cc.x, ly - cc.y) < rad)
            val inOc = hypot(lx - ocC.x, ly - ocC.y) < ocR
            val role = when {
                r < r0 -> if (r < 0.05) "gold" else "cen0"
                r < r1 -> if (onAxis) "cross" else "cen1"
                r < r3 -> if (inLancet) (if (onAxis) "cross" else if (r < 0.44) "mid1" else "mid0") else "out1"
                r < r4 -> if (inOc) (if (onAxis) "cross" else "cen1") else if (wide) "mid0" else "out0"
                else -> if (wide) (if (onAxis) "cen1" else "out1") else "deepBig"
            }
            add(f, role)
        }
        addClassLeads(lines, mapOf("struct" to 2.2, "second" to 1.0))
    }

    // ------------------------------------------------------------ C — contemporaine (sans damier)

    /**
     * C, contemporaine, sans damier (brief du 2 nov. 2026). Construction à
     * quatre axes, indépendante du nombre de rotations :
     * - la CROIX : quatre bras lumineux qui s'évasent doucement vers le bord
     *   (côtés légèrement courbes, croix pattée), coupés par les anneaux ;
     * - sur les quatre diagonales : une lancette à tête en arc brisé, puis un
     *   panneau étroit dans la couronne extérieure ;
     * - entre les deux : de grands panneaux trapézoïdaux, sans alternance de
     *   couleurs — un dégradé du centre (rose) vers le bord (grenat), la
     *   couronne extérieure partagée en deux par un anneau (r4).
     */
    private fun buildContemporary() {
        val r0 = 0.12
        val r1 = 0.30
        val r2 = 0.55
        val r3 = 0.78
        val rOut = 0.965
        val hw0 = 0.045       // demi-largeur d'un bras au centre
        val hw1 = 0.135       // demi-largeur d'un bras au bord
        val hb = 0.06         // demi-largeur de la lancette diagonale
        val hp = 0.085        // demi-largeur du panneau diagonal extérieur
        val ratio = 1.4       // arc brisé de la lancette (rayon = ratio × ouverture)
        val archH = 2 * hb * sqrt(ratio * ratio - (ratio - 0.5) * (ratio - 0.5))
        val apex = r3 - 0.02
        val spring = apex - archH
        val arcR = ratio * 2 * hb
        val arcC = Coordinate(spring, hb - arcR)   // centre de l'arc du côté +y
        val o = Coordinate(0.0, 0.0)
        fun armHalf(x: Double) = hw0 + (hw1 - hw0) * ((x - r0) / (rOut - r0)).coerceIn(0.0, 1.0).let { Math.pow(it, 1.6) }
        // bras (repère de l'axe 0) : côtés évasés
        // (part de l'intérieur du cercle r0, pour le croiser franchement : sinon
        // le bras et l'anneau r0–r1 ne forment qu'une seule pièce)
        val side = (0..40).map { k -> val x = r0 - 0.02 + (1.02 - r0 + 0.02) * k / 40; Coordinate(x, armHalf(x)) }
        val axes = (0 until 4).map { it * PI / 2 }
        val diagonals = (0 until 4).map { PI / 4 + it * PI / 2 }
        val lines = mutableListOf<Pair<LineString, String>>()
        val r4 = 0.875       // subdivision de la couronne extérieure
        for ((r, cl) in listOf(r0 to "second", r1 to "struct", r2 to "second", r3 to "struct", r4 to "second")) lines.add(sCirc(o, r, 720) to cl)
        for (a in axes) {
            lines.add((sRotate(sLine(side), a) as LineString) to "struct")
            lines.add((sRotate(sLine(side.map { Coordinate(it.x, -it.y) }), a) as LineString) to "struct")
            lines.add((sRotate(sLine(listOf(o, Coordinate(r0, 0.0))), a) as LineString) to "fine")
        }
        // lancette diagonale (repère de la diagonale) : côtés droits, tête en arc brisé
        val a0 = atan2(hb - arcC.y, spring - arcC.x)
        val a1 = atan2(0.0 - arcC.y, apex - arcC.x)
        val head = (0..24).map { k -> val t = a0 + (a1 - a0) * k / 24; Coordinate(arcC.x + arcR * cos(t), arcC.y + arcR * sin(t)) }
        val lancet = listOf(
            sLine(listOf(Coordinate(r1, hb), Coordinate(spring, hb))), sLine(listOf(Coordinate(r1, -hb), Coordinate(spring, -hb))),
            sLine(head), sLine(head.map { Coordinate(it.x, -it.y) })
        )
        val panel = listOf(sLine(listOf(Coordinate(r3, hp), Coordinate(1.02, hp))), sLine(listOf(Coordinate(r3, -hp), Coordinate(1.02, -hp))))
        for (a in diagonals) for (ln in lancet + panel) lines.add((sRotate(ln, a) as LineString) to "second")
        // séparations entre bras et diagonale (à 22,5° des axes), de l'anneau r1 au bord
        for (k in 0 until 8) {
            val a = PI / 8 + k * PI / 4
            lines.add(sLine(listOf(sPolar(r1, a), sPolar(1.02, a))) to "second")
        }
        val faces = sArrangement(lines.map { it.first })
        for (f in faces) {
            val p = f.interiorPoint.coordinate
            val r = hypot(p.x, p.y)
            val a = atan2(p.y, p.x)
            // repère de l'axe le plus proche, puis de la diagonale la plus proche
            val ka = sRound(a / (PI / 2)) * (PI / 2)
            val xa = p.x * cos(-ka) - p.y * sin(-ka)
            val ya = p.x * sin(-ka) + p.y * cos(-ka)
            val kd = sRound((a - PI / 4) / (PI / 2)) * (PI / 2) + PI / 4
            val xd = p.x * cos(-kd) - p.y * sin(-kd)
            val yd = p.x * sin(-kd) + p.y * cos(-kd)
            val inArm = r > r0 && abs(ya) < armHalf(xa)
            val inLancet = r > r1 && abs(yd) < hb && (xd < spring || hypot(xd - arcC.x, abs(yd) - arcC.y) < arcR)
            val inPanel = r > r3 && abs(yd) < hp
            val role = when {
                r < r0 -> "cen0"
                inArm -> if (r < r3) "cross" else "cen1"
                r < r1 -> "cen1"
                inLancet -> "acc"
                inPanel -> if (r < r4) "acc" else "out1"
                r < r2 -> "mid0"
                r < r3 -> "out1"
                r < r4 -> "out0"
                else -> "deepBig"
            }
            add(f, role)
        }
        addClassLeads(lines, mapOf("struct" to 2.2, "second" to 1.0))
    }

    // ------------------------------------------------------------ E — Art nouveau

    /**
     * E, Art nouveau (brief du 2 nov. 2026), tout en plomb : des FLAMMES (une
     * « gloire »), pas une fleur.
     * - n bandes courbes partent du médaillon et tournent toutes dans le même
     *   sens jusqu'au bord (symétrie de rotation : un mouvement, pas un
     *   miroir) ; leur frontière est une courbe en S (coup de fouet) ;
     * - dans chaque bande, une seconde ligne quitte un bord, traverse la bande
     *   et rejoint l'autre bord en s'y raccordant tangentiellement : elle
     *   partage la bande en deux flammes imbriquées, l'une claire (large près
     *   du centre), l'autre sombre (large vers le bord) ;
     * - deux anneaux (rayons rings) coupent les flammes et donnent la
     *   hiérarchie : cœur lumineux, couronne médiane, bordure ;
     * - un médaillon rond, partagé en quatre par une croix discrète.
     * Réglages : twist (rotation totale d'une bande, en radians), sCurve
     * (amplitude du S).
     */
    private fun buildArtNouveau(twist: Double = 0.45, sCurve: Double = 0.30, rings: Pair<Double, Double> = 0.40 to 0.80) {
        val n = rotations
        val s = 2 * PI / n
        val rm = 0.15         // médaillon
        val rIn = 0.07        // cœur du médaillon
        val r1 = 1.02         // les courbes vont au-delà du bord (coupées par le disque)
        val u0 = 0.12         // la ligne secondaire quitte la frontière k…
        val u1 = 0.88         // … et rejoint la frontière k + 1
        fun th(u: Double) = twist * u + sCurve * sin(PI * u)
        fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
        val o = Coordinate(0.0, 0.0)
        val lines = mutableListOf<Pair<LineString, String>>()
        lines.add(sCirc(o, rm, 480) to "struct")
        lines.add(sCirc(o, rIn, 240) to "second")
        for (k in 0 until 4) lines.add(sLine(listOf(sPolar(rIn, k * PI / 2), sPolar(rm, k * PI / 2))) to "second")
        lines.add(sCirc(o, rings.first, 720) to "second")
        lines.add(sCirc(o, rings.second, 720) to "second")
        val us = (0..160).map { it / 160.0 }
        for (k in 0 until n) {
            val t0 = k * s
            lines.add(sLine(us.map { u -> sPolar(rm + (r1 - rm) * u, t0 + th(u)) }) to "struct")
            lines.add(sLine(us.filter { it in u0..u1 }.map { u -> sPolar(rm + (r1 - rm) * u, t0 + th(u) + s * smooth((u - u0) / (u1 - u0))) }) to "second")
        }
        val faces = sArrangement(lines.map { it.first })
        for (f in faces) {
            val p = f.interiorPoint.coordinate
            val r = hypot(p.x, p.y)
            val a = atan2(p.y, p.x)
            val u = (r - rm) / (r1 - rm)
            // position dans la bande : 0 sur la frontière k, 1 sur la frontière k + 1
            val x = (a - th(u)).mod(s) / s
            val light = x > smooth((u - u0) / (u1 - u0))
            val role = when {
                r < rIn -> "cen0"
                r < rm -> "cen1"
                r < rings.first -> if (light) "mid1" else "acc"
                r < rings.second -> if (light) "mid0" else "out1"
                else -> if (light) "acc" else "out0"
            }
            add(f, role)
        }
        addClassLeads(lines, mapOf("struct" to 2.0, "second" to 1.0))
    }

    /** Plombs épais des dessins C : chaque ligne de classe struct ou second,
     *  limitée au disque (les plombs fins sont ceux entre les pièces). */
    private fun addClassLeads(lines: List<Pair<LineString, String>>, factors: Map<String, Double>) {
        val disc = sPoint(Coordinate(0.0, 0.0)).buffer(0.965, 720)
        for ((ln, cl) in lines) {
            val f = factors[cl] ?: continue
            addLead(sInter(ln, disc), f)
        }
    }
}

// ================================================================ motifs centraux de B

/**
 * Motif de la zone centrale de la rosace B (entre le cercle du médaillon,
 * rayon rC, et le cercle médian, rayon rM) : la périphérie de B ne change
 * pas d'une variante à l'autre (brief du 2 nov. 2026).
 */
private abstract class CentreMotif(val n: Int, val rC: Double, val rM: Double) {
    val s = 2 * PI / n
    val h = s / 2
    /** Barres de pierre du motif (disque entier), avec leur largeur. */
    abstract val lines: List<Pair<LineString, Double>>
    /** Rôle de couleur d'une pièce, d'après un point intérieur. */
    abstract fun role(p: Coordinate): String
    /** Plombs supplémentaires dans l'ouverture qui contient ce point (aucun par défaut). */
    open fun cuts(p: Coordinate): List<LineString> = emptyList()

    // Médaillon (dans le cercle de rayon rC). Par défaut, celui de B : un
    // hexalobe ivoire entouré d'un anneau rose très pâle en 6 pièces.
    open val medallion: List<Pair<LineString, Double>> = listOf(sFoil(Coordinate(0.0, 0.0), rC * 0.82, 6, 0.0) to 0.013)
    /** Plombs dans une ouverture du médaillon. */
    open fun medallionCuts(op: Polygon): List<LineString> =
        if (op.contains(sPoint(Coordinate(0.0, 0.0)))) emptyList()
        // anneau autour de l'hexalobe : 6 pièces (une seule pièce allongée
        // s'assombrissait sous le verre)
        else (0 until 6).map { k -> sLine(listOf(Coordinate(0.0, 0.0), sPolar(rC + 0.05, PI / 6 + k * PI / 3))) }
    /** Rôle d'une pièce du médaillon. */
    open fun medallionRole(q: Polygon, p: Coordinate): String = if (q.contains(sPoint(Coordinate(0.0, 0.0)))) "cen0" else "veryPale"

    /** Point ramené dans le repère d'un axe (x le long de l'axe, y latéral).
     *  offset = h : axes entre les secteurs (soufflets, amandes) ; 0 : axes des secteurs. */
    fun local(p: Coordinate, offset: Double): Coordinate {
        val a = atan2(p.y, p.x)
        val k = sRound((a - offset) / s)
        val t = -(k * s + offset)
        return Coordinate(p.x * cos(t) - p.y * sin(t), p.x * sin(t) + p.y * cos(t))
    }

    fun rot(pts: List<Coordinate>, a: Double) = pts.map { Coordinate(it.x * cos(a) - it.y * sin(a), it.x * sin(a) + it.y * cos(a)) }

    /** Une ligne (repère de l'axe) recopiée sur les n axes. */
    fun around(pts: List<Coordinate>, offset: Double, w: Double): List<Pair<LineString, Double>> =
        (0 until n).map { i -> sLine(rot(pts, offset + i * s)) to w }

    fun closed(pts: List<Coordinate>) = pts.dropLast(1) + pts[0]
    fun polygon(pts: List<Coordinate>) = SGF.createPolygon(closed(pts).toTypedArray())
}

/** B — soufflets flamboyants (dessin d'origine). */
private class SouffletMotif(n: Int, rC: Double, rM: Double, w2: Double, w3: Double) : CentreMotif(n, rC, rM) {
    private fun souffle(x0: Double, l: Double, w: Double, m: Int = 40): List<Coordinate> {
        val r1 = sBez(Coordinate(x0, 0.0), Coordinate(x0 + 0.02 * l, 0.75 * w), Coordinate(x0 + 0.30 * l, w), Coordinate(x0 + 0.55 * l, w), m)
        val r2 = sBez(Coordinate(x0 + 0.55 * l, w), Coordinate(x0 + 0.78 * l, w), Coordinate(x0 + 0.86 * l, 0.18 * w), Coordinate(x0 + l, 0.0), m)
        val right = r1 + r2.drop(1)
        return right.reversed().map { Coordinate(it.x, -it.y) } + right.drop(1)
    }
    // (construits sur l'axe h, comme dans le prototype)
    private val souf = closed(rot(souffle(rC, rM - rC, 0.355 * sin(h) + 0.004), h))
    private val souf2 = closed(rot(souffle(0.29, 0.21, 0.047), h))
    private val soufPoly = SGF.createPolygon(souf.toTypedArray()).buffer(0.002)
    private val souf2Poly = SGF.createPolygon(souf2.toTypedArray()).buffer(0.002)
    // (mêmes opérations que le prototype, pour un résultat identique)
    override val lines = listOf(sLine(souf) to w2, sLine(souf2) to w3 * 1.3).flatMap { (ln, w) ->
        (0 until n).map { i -> (sRotate(ln, i * s) as LineString) to w }
    }
    override fun role(p: Coordinate): String {
        val qa = atan2(p.y, p.x)
        val pt = sRotate(sPoint(p), -(sRound((qa - h) / s) * s))
        return when {
            souf2Poly.contains(pt) -> "mid1"
            soufPoly.contains(pt) -> "mid0"
            else -> "acc"   // mouchettes
        }
    }
}

/** Amande (vesica) : deux arcs de cercle de a à b sur l'axe, demi-largeur hw ; anneau fermé. */
private fun vesica(a: Double, b: Double, hw: Double, m: Int = 36): List<Coordinate> {
    val mid = (a + b) / 2
    val half = (b - a) / 2
    val rad = (hw * hw + half * half) / (2 * hw)
    val cy = -(rad - hw)                       // centre de l'arc supérieur
    val t0 = atan2(-cy, a - mid)
    val t1 = atan2(-cy, b - mid)
    val upper = (0..m).map { k -> val t = t0 + (t1 - t0) * k / m; Coordinate(mid + rad * cos(t), cy + rad * sin(t)) }
    val lower = upper.reversed().drop(1).map { Coordinate(it.x, -it.y) }
    return upper + lower   // le dernier point (a, 0) répète le premier
}

/**
 * B1 — AMANDES. Sur chaque axe entre deux secteurs, une grande amande effilée
 * à double contour (une bande, puis une amande intérieure coupée selon son
 * axe) ; entre deux grandes amandes, près du cercle médian, une petite amande.
 * Le fond reste le « grand disque » sombre de B.
 */
private class AlmondMotif(n: Int, rC: Double, rM: Double, w2: Double, w3: Double) : CentreMotif(n, rC, rM) {
    private val outer = vesica(rC + 0.035, rM - 0.015, 0.062)
    private val inner = vesica(rC + 0.085, rM - 0.065, 0.036)
    private val small = vesica(rM - 0.17, rM - 0.015, 0.026)
    private val outerPoly = polygon(outer)
    private val innerPoly = polygon(inner)
    private val smallPoly = polygon(small)
    override val lines = around(outer, h, w2) + around(inner, h, w3 * 1.3) + around(small, 0.0, w3 * 1.3)
    override fun role(p: Coordinate): String {
        val q = sPoint(local(p, h))
        return when {
            innerPoly.contains(q) -> "mid1"
            outerPoly.contains(q) -> "mid0"
            smallPoly.contains(sPoint(local(p, 0.0))) -> "mid1"
            else -> "acc"
        }
    }
    /** Médaillon : une croix de quatre amandes (mandorles) ivoire, sur les
     *  axes, dans un fond rose très pâle. */
    private val crossAlmond = vesica(0.028, rC - 0.022, 0.042)
    override val medallion = (0 until 4).map { k -> sLine(rot(crossAlmond, k * PI / 2)) to w3 * 1.3 }
    override fun medallionCuts(op: Polygon): List<LineString> = emptyList()
    override fun medallionRole(q: Polygon, p: Coordinate): String {
        val a = atan2(p.y, p.x)
        val k = sRound(a / (PI / 2)) * (PI / 2)
        val l = Coordinate(p.x * cos(-k) - p.y * sin(-k), p.x * sin(-k) + p.y * cos(-k))
        return if (polygon(crossAlmond).contains(sPoint(l))) "cen0" else "veryPale"
    }

    /** Amande intérieure coupée selon son axe (subdivision discrète) ; le
     *  fond, qui entoure toutes les amandes, est recoupé le long de leurs axes. */
    override fun cuts(p: Coordinate): List<LineString> {
        val l = local(p, h)
        if (role(p) == "acc") return (0 until 2 * n).map { k -> sLine(listOf(Coordinate(0.0, 0.0), sPolar(1.2, k * h))) }
        if (!innerPoly.contains(sPoint(l))) return emptyList()
        val a = atan2(p.y, p.x)
        val ax = sRound((a - h) / s) * s + h
        return listOf(sLine(listOf(sPolar(rC, ax), sPolar(rM, ax))))
    }
}

/**
 * B2 — ÉTOILE ENTRELACÉE. Les cordes d'un polygone étoilé {n/skip}, tracées
 * comme des rubans (deux barres parallèles), se croisent et dessinent une
 * étoile à n branches ; les rubans semblent se prolonger d'une branche à
 * l'autre. Elles s'arrêtent sur un cercle (rayon rS) qui laisse autour du
 * médaillon une couronne lumineuse, sans croisements serrés.
 */
private class StarMotif(n: Int, rC: Double, rM: Double, w2: Double, w3: Double, val starMedallion: Boolean = true, val skip: Int = 5, val band: Double = 0.013) : CentreMotif(n, rC, rM) {
    private val rS = rC + 0.075
    private val tips = (0 until n).map { j -> sPolar(rM, h + j * s) }
    private val chords = (0 until n).map { j -> tips[j] to tips[(j + skip) % n] }
    private fun offsetLine(a: Coordinate, b: Coordinate, d: Double): LineString {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l = hypot(dx, dy)
        val nx = -dy / l * d
        val ny = dx / l * d
        val ext = 0.2   // prolongée au-delà du cercle médian : la pierre la coupe
        val ux = dx / l * ext
        val uy = dy / l * ext
        return sLine(listOf(Coordinate(a.x + nx - ux, a.y + ny - uy), Coordinate(b.x + nx + ux, b.y + ny + uy)))
    }
    private val ring = sPoint(Coordinate(0.0, 0.0)).buffer(rM + 0.01, 720).let { sDiff(it, sPoint(Coordinate(0.0, 0.0)).buffer(rS, 480)) }
    override val lines = chords.flatMap { (a, b) ->
        listOf(-band, band).flatMap { d -> sLineStrings(sInter(offsetLine(a, b, d), ring)).map { it to w3 * 1.3 } }
    } + listOf(sCirc(Coordinate(0.0, 0.0), rS, 480) to w2)
    /** Distance signée d'un point à la droite ab (positive du côté de q). */
    private fun side(p: Coordinate, a: Coordinate, b: Coordinate, q: Coordinate): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l = hypot(dx, dy)
        val c = (dx * (p.y - a.y) - dy * (p.x - a.x)) / l
        val o = (dx * (q.y - a.y) - dy * (q.x - a.x)) / l
        return if (o >= 0) c else -c
    }
    /** Médaillon : une étoile rayonnante à n branches effilées (l'étoile de
     *  Bethléem), pointes dorées et cœur ivoire, sur un fond rose très pâle. */
    private val starOuter = rC - 0.014
    private val starInner = 0.068
    private val starRing = (0 until 2 * n).map { k -> if (k % 2 == 0) sPolar(starOuter, h + k * h) else sPolar(starInner, h + k * h) }
    private val innerRing = (0 until n).map { k -> sPolar(starInner, h + (2 * k + 1) * h) }
    // Variante « médaillon architectural » : un quadrilobe ivoire (lobes sur
    // les axes, lecture discrète d'une croix), anneau rose très pâle en 4 pièces.
    override val medallion = if (starMedallion) listOf(sLine(starRing + starRing[0]) to w3 * 1.3)
    else listOf(sFoil(Coordinate(0.0, 0.0), rC * 0.82, 4, 0.0) to w2)
    override fun medallionCuts(op: Polygon): List<LineString> {
        val centre = op.contains(sPoint(Coordinate(0.0, 0.0)))
        return when {
            starMedallion -> if (centre) listOf(sLine(innerRing + innerRing[0])) else emptyList()
            centre -> emptyList()
            else -> (0 until 4).map { k -> sLine(listOf(Coordinate(0.0, 0.0), sPolar(rC + 0.05, PI / 4 + k * PI / 2))) }
        }
    }
    override fun medallionRole(q: Polygon, p: Coordinate): String {
        if (!starMedallion) return if (q.contains(sPoint(Coordinate(0.0, 0.0)))) "cen0" else "veryPale"
        val inStar = SGF.createPolygon((starRing + starRing[0]).toTypedArray()).contains(sPoint(p))
        val inCore = SGF.createPolygon((innerRing + innerRing[0]).toTypedArray()).contains(sPoint(p))
        return when {
            inCore -> "cen0"
            inStar -> "gold"
            else -> "veryPale"
        }
    }

    override fun role(p: Coordinate): String {
        if (hypot(p.x, p.y) < rS) return "cen1"                                  // couronne autour du médaillon
        if (chords.any { (a, b) -> abs(side(p, a, b, Coordinate(0.0, 0.0))) < band }) return "mid1"   // rubans
        // branche : entre les deux cordes issues de la pointe la plus proche
        val a = atan2(p.y, p.x)
        val j = sRound((a - h) / s).mod(n.toLong()).toInt()
        val tip = tips[j]
        val onAxis = sPolar(0.5 * rM, h + j * s)
        val inBranch = side(p, tip, tips[(j + skip) % n], onAxis) > 0 && side(p, tip, tips[(j - skip).mod(n)], onAxis) > 0
        return if (inBranch) "mid0" else "acc"
    }
}

/**
 * B3 — ANNEAUX ENTRELACÉS. Une chaîne de n anneaux (cercles de pierre) qui
 * se chevauchent deux à deux : chaque chevauchement forme une amande ; entre
 * la chaîne et le médaillon apparaît une étoile aux branches courbes.
 */
private class RingMotif(n: Int, rC: Double, rM: Double, w2: Double, w3: Double) : CentreMotif(n, rC, rM) {
    private val d = (rC + rM) / 2 + 0.005                   // rayon des centres des anneaux
    private val rc = 0.155                                  // rayon d'un anneau
    private val centres = (0 until n).map { j -> sPolar(d, h + j * s) }
    override val lines = centres.map { c -> sCirc(c, rc, 192) to w2 }
    override fun role(p: Coordinate): String {
        val inRings = centres.count { hypot(p.x - it.x, p.y - it.y) < rc }
        val r = hypot(p.x, p.y)
        return when {
            inRings >= 2 -> "mid0"                             // amandes des chevauchements
            inRings == 1 -> "out1"                              // anneaux
            r < d -> "cen1"                                     // étoile autour du médaillon
            else -> "acc"
        }
    }
}

/**
 * B3 — VRAI ENTRELACS : un guillochis à deux brins, comme dans les pavements
 * cosmatesques des églises romaines et l'ornement chrétien d'Orient. Deux
 * rubans font le tour de la rosace en serpentant autour de n disques : chacun
 * passe tantôt à l'extérieur, tantôt à l'intérieur d'un disque, et ils se
 * croisent entre deux disques. À chaque croisement, un ruban passe DESSUS
 * (ses pièces et ses plombs traversent sans interruption) et l'autre DESSOUS
 * (il s'interrompt sur les bords du premier) ; dessus et dessous alternent
 * le long de chaque ruban. Le motif est en verre et en plomb (pas de pierre) ;
 * la pierre ne garde que les cercles du médaillon et du cercle médian.
 * Construction : arcs de cercle autour de chaque disque, reliés par les
 * tangentes intérieures communes, qui se croisent à mi-chemin. n pair.
 */
private class InterlaceMotif(n: Int, rC: Double, rM: Double) : CentreMotif(n, rC, rM) {
    private val d = (rC + rM) / 2 + 0.005                   // rayon des centres des disques
    private val dd = 2 * d * sin(h)                         // distance entre deux disques voisins
    private val bw = 0.034                                  // largeur d'un ruban
    private val rho = minOf(0.075, dd / 2 - bw / 2 - 0.01)  // rayon de la ligne médiane d'un ruban autour d'un disque
    private val rr = rho - bw / 2 - 0.012                   // rayon d'un disque
    private val centres = (0 until n).map { j -> sPolar(d, h + j * s) }
    private val o = Coordinate(0.0, 0.0)

    /** Ligne médiane d'un brin ; first = +1 : passe à l'extérieur du disque 0. */
    private fun strand(first: Int): List<Coordinate> {
        val side = (0 until n).map { j -> if (j % 2 == 0) first else -first }
        val outT = arrayOfNulls<Coordinate>(n)   // point de départ de la tangente vers le disque suivant
        val inT = arrayOfNulls<Coordinate>(n)    // point d'arrivée de la tangente venant du disque précédent
        for (j in 0 until n) {
            val c1 = centres[j]
            val c2 = centres[(j + 1) % n]
            val dist = hypot(c2.x - c1.x, c2.y - c1.y)
            val ux = (c2.x - c1.x) / dist
            val uy = (c2.y - c1.y) / dist
            val mx = (c1.x + c2.x) / 2
            val my = (c1.y + c2.y) / 2
            var nx = -uy
            var ny = ux
            if (nx * mx + ny * my < 0) { nx = -nx; ny = -ny }   // normale vers l'extérieur
            val cb = 2 * rho / dist
            val sb = sqrt(1 - cb * cb)
            val t1 = Coordinate(c1.x + rho * (cb * ux + side[j] * sb * nx), c1.y + rho * (cb * uy + side[j] * sb * ny))
            outT[j] = t1
            inT[(j + 1) % n] = Coordinate(2 * mx - t1.x, 2 * my - t1.y)
        }
        val pts = mutableListOf<Coordinate>()
        for (j in 0 until n) {
            val c = centres[j]
            val a0 = atan2(inT[j]!!.y - c.y, inT[j]!!.x - c.x)
            val a1 = atan2(outT[j]!!.y - c.y, outT[j]!!.x - c.x)
            val radial = atan2(c.y, c.x) + (if (side[j] > 0) 0.0 else PI)   // côté par lequel passer
            // sens de parcours : celui dont le milieu est du bon côté du disque
            val ccw = (a1 - a0).mod(2 * PI)
            val mid = a0 + ccw / 2
            val sweep = if (cos(mid - radial) > 0) ccw else ccw - 2 * PI
            for (k in 0..40) {
                val t = a0 + sweep * k / 40
                pts.add(Coordinate(c.x + rho * cos(t), c.y + rho * sin(t)))
            }
        }
        return pts + pts[0]
    }

    private val lineA = sLine(strand(+1))
    private val lineB = sLine(strand(-1))
    private val params = BufferParameters(16, BufferParameters.CAP_FLAT, BufferParameters.JOIN_ROUND, 5.0)
    private val bandA = BufferOp.bufferOp(lineA, bw / 2, params)
    private val bandB = BufferOp.bufferOp(lineB, bw / 2, params)
    /** Croisement k : entre les disques k et k + 1 ; A passe dessus si k est pair. */
    private fun window(k: Int): Geometry {
        val c1 = centres[k]
        val c2 = centres[(k + 1) % n]
        return sPoint(Coordinate((c1.x + c2.x) / 2, (c1.y + c2.y) / 2)).buffer(bw * 1.6, 32)
    }
    private val aOver = sUnion((0 until n).filter { it % 2 == 0 }.map { sInter(window(it), sInter(bandA, bandB)) })
    private val bOver = sUnion((0 until n).filter { it % 2 == 1 }.map { sInter(window(it), sInter(bandA, bandB)) })

    override val lines = emptyList<Pair<LineString, Double>>()

    /** Plombs : bords visibles des rubans (le ruban du dessous s'interrompt),
     *  disques, et une coupe en travers de chaque ruban au droit de chaque disque. */
    private val leadLines: List<LineString> by lazy {
        val edgesA = sDiff(bandA.boundary, bOver)
        val edgesB = sDiff(bandB.boundary, aOver)
        val bands = sUnion(listOf(bandA, bandB))
        val traverses = centres.flatMap { c ->
            val a = atan2(c.y, c.x)
            sLineStrings(sInter(sLine(listOf(sPolar(d - 0.25, a), sPolar(d + 0.25, a))), bands))
        }
        // Les bords interrompus s'arrêtent sur le bord de l'autre ruban : prolongés
        // de 0,003 pour le croiser franchement (sinon, à un arrondi près, ils ne
        // ferment aucune pièce) ; les petits dépassements sont éliminés à la découpe.
        (sLineStrings(edgesA) + sLineStrings(edgesB) + traverses).map { sExtend(it, 0.003) } + centres.map { sCirc(it, rr, 96) }
    }
    override fun cuts(p: Coordinate): List<LineString> = leadLines

    override fun role(p: Coordinate): String {
        val pt = sPoint(p)
        return when {
            centres.any { hypot(p.x - it.x, p.y - it.y) < rr } -> "out1"   // disques
            aOver.contains(pt) -> "mid0"
            bOver.contains(pt) -> "mid1"
            bandA.contains(pt) -> "mid0"
            bandB.contains(pt) -> "mid1"
            centres.any { hypot(p.x - it.x, p.y - it.y) < rho } -> "veryPale"   // œil autour du disque
            hypot(p.x, p.y) < d -> "cen1"
            else -> "acc"
        }
    }
}