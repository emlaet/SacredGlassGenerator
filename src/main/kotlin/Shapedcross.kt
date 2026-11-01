import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// CROIX DESSINÉES — croix tréflée, croix celtique
// --------------------------------------------------
//
// Deuxième famille de croix, à côté de la croix rayonnante
// (RadiantCrossCompositionSystem), dont les bras sont des secteurs en
// éventail. Ici la croix a une FORME dessinée — des bras droits de
// largeur constante, terminés par des lobes (croix tréflée) ou réunis
// par un anneau (croix celtique) — et les rayons de lumière colorés
// passent DERRIÈRE elle.
//
// - Croix tréflée : chaque bras se termine par trois lobes arrondis,
//   comme un trèfle (en héraldique anglaise « cross bottony » : « a cross
//   having each arm terminating in three rounded lobes, forming a sort of
//   trefoil », Webster's 1913). Le lobe central et les deux lobes
//   latéraux sont trois pièces, séparées par des plombs qui partent du
//   cœur du trèfle.
// - Croix celtique : croix latine dont le croisement est entouré d'un
//   anneau (« a Latin cross with a nimbus surrounding the intersection of
//   the arms and stem »). L'anneau passe derrière les bras ; l'intérieur
//   de l'anneau est garni de pièces de couleur (la « gloire »), les rayons
//   partent de l'extérieur de l'anneau.
//
// Construction (même principe que la baie, Bay.kt) : toutes les lignes
// utiles — contours des bras, des lobes, du médaillon et de l'anneau
// (cercles approchés par des polygones), coupes des bras, bords et coupes
// des rayons — sont versées dans un arrangement plan
// (polygonizeSegments, Arrangement.kt), qui calcule toutes les faces
// avec des sommets partagés. Chaque face reçoit ensuite l'identifiant de
// la pièce à laquelle elle appartient (bras, lobe, anneau, rayon…) ; les
// faces d'une même pièce sont réunies (les plombs intérieurs inutiles
// disparaissent). Les pièces voisines partagent ainsi exactement leurs
// sommets (règle 3 du projet).
//
// Toutes les dimensions sont des fractions de maxRadius, lui-même
// proportionnel au canevas : même dessin à l'aperçu et à l'export.

enum class ShapedCrossStyle {
    /** Bras terminés par trois lobes arrondis (trèfle). */
    TREFOIL,
    /** Croix latine dont le croisement est entouré d'un anneau. */
    CELTIC,
    /**
     * Croix « rondouillarde » : quatre bras larges aux bouts arrondis,
     * raccordés par des creux arrondis — une silhouette pleine et douce,
     * proche d'un quatre-feuilles.
     */
    ROUNDED
}

enum class ShapedCrossRole {
    /** Médaillon central (couleur : crossMedallionShades). */
    MEDALLION,
    /** Bras et lobes (couleur : crossArmShades, blanc cassé). */
    ARM,
    /** Anneau de la croix celtique (bras, ou or si ringGold). */
    RING,
    /** Pièces de couleur à l'intérieur de l'anneau (palette de la saison). */
    GLORY,
    /** Rayons de lumière (palette de la saison). */
    RAY
}

class ShapedCrossGuide(
    val pieces: List<List<Vector2>>,
    val roles: List<ShapedCrossRole>,
    val ringGold: Boolean,
    /**
     * Croix rondouillarde en couleurs : groupe de chaque pièce (0 : pièces
     * du cœur, autour du médaillon ; 1 et 2 : les deux moitiés du bout de
     * chaque bras ; −1 : autre) et teintes de chaque groupe (null : bras en
     * blanc cassé).
     */
    val tiers: List<Int> = emptyList(),
    val tierShades: List<List<ColorRGBa>>? = null,
    /** Couleur imposée de chaque pièce (motifs de la croix rondouillarde, RoundedPatterns.kt) ; null sinon. */
    val pieceShades: List<List<ColorRGBa>>? = null,
    /** Centre de la croix (répartition radiale des couleurs, RadialLuminousPaletteSystem). */
    val center: Vector2? = null,
    /** Longueur maximale des rayons de lumière depuis le centre (0 : pas de rayons). */
    val rayRadius: Double = 0.0,
    /** Style de la croix (répartition radiale propre à chaque croix, voir LuminousScheme). */
    val style: ShapedCrossStyle? = null
) : CompositionGuide()

/**
 * Croix dessinée (tréflée ou celtique) devant des rayons colorés.
 *
 * - style : TREFOIL ou CELTIC.
 * - numberOfRays, angleIrregularity, min/maxDivisionsPerRay : rayons de
 *   lumière, comme pour la croix rayonnante.
 * - lightLengthMinRatio / lightLengthMaxRatio : longueur des rayons, en
 *   part de maxRadius, mesurée depuis le centre.
 * - ringGold (croix celtique) : anneau doré (réglage par défaut) ou,
 *   avec false, en blanc cassé comme les bras. L'anneau est souvent lu
 *   comme un nimbe, et le nimbe est d'ordinaire doré dans l'art chrétien.
 * - armHalfWidthRatio : demi-largeur des bras (croix celtique : 0,10,
 *   élargie après un premier essai à 0,075 jugé trop grêle à côté de
 *   l'anneau et des rayons).
 * - lobeRadiusRatio, lobeSpreadRatio (croix tréflée) : taille des lobes
 *   et écartement des lobes latéraux.
 * - showRays : false pour la croix seule, sans rayons.
 *
 * Centre de la croix à 42 % de la hauteur, comme la croix rayonnante :
 * la baie (emblemOffsetRatio…) se règle de la même façon.
 */
class ShapedCrossCompositionSystem(
    private val style: ShapedCrossStyle = ShapedCrossStyle.TREFOIL,
    private val numberOfRays: Int = 30,
    private val angleIrregularity: Double = 0.30,
    private val minDivisionsPerRay: Int = 2,
    private val maxDivisionsPerRay: Int = 3,
    private val lightLengthMinRatio: Double = when (style) {
        ShapedCrossStyle.CELTIC -> 0.58
        ShapedCrossStyle.ROUNDED -> 0.80
        ShapedCrossStyle.TREFOIL -> 0.40
    },
    private val lightLengthMaxRatio: Double = when (style) {
        ShapedCrossStyle.CELTIC -> 0.78
        ShapedCrossStyle.ROUNDED -> 0.98
        ShapedCrossStyle.TREFOIL -> 0.58
    },
    private val ringGold: Boolean = true,
    /** Demi-largeur des bras, en part de maxRadius. */
    private val armHalfWidthRatio: Double = when (style) {
        ShapedCrossStyle.CELTIC -> 0.10
        ShapedCrossStyle.ROUNDED -> 0.36
        ShapedCrossStyle.TREFOIL -> 0.085
    },
    /** Croix tréflée : rayon des lobes, en part de maxRadius. */
    private val lobeRadiusRatio: Double = 0.10,
    /** Croix tréflée : écart des lobes latéraux par rapport à l'axe du bras, en rayons de lobe. */
    private val lobeSpreadRatio: Double = 1.25,
    /** false : pas de rayons (la croix seule ; l'intérieur de l'anneau celtique reste garni). */
    private val showRays: Boolean = true,
    /**
     * true : croix grecque, quatre bras égaux, centrée (réglage par défaut
     * des croix tréflée et rondouillarde, comme la croix de saint Maurice) ;
     * false : croix latine, bras du bas plus long, centre à 42 % de la
     * hauteur (réglage par défaut de la croix celtique).
     */
    private val equalArms: Boolean = style != ShapedCrossStyle.CELTIC,
    /**
     * Croix rondouillarde : teintes des trois groupes de pièces (cœur, puis
     * les deux moitiés du bout des bras) — voir roundedCrossShades. null :
     * croix en blanc cassé.
     */
    private val tierShades: List<List<ColorRGBa>>? = null,
    /**
     * Croix rondouillarde : dessin intérieur (RoundedPatterns.kt) — GEMS,
     * LANCETS ou RHOMBI, avec les couleurs patternColors (roundedCrossColors).
     * Ces motifs n'ont pas de rayons. PLAIN : découpe simple.
     */
    private val pattern: RoundedPattern = RoundedPattern.PLAIN,
    private val patternColors: RoundedCrossColors? = null
) : CompositionSystem {

    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide {
        val center = Vector2(width / 2.0, height * (if (equalArms) 0.50 else 0.42))
        val maxRadius = min(width, height) * 0.46
        if (style == ShapedCrossStyle.ROUNDED && pattern != RoundedPattern.PLAIN) {
            requireNotNull(patternColors) { "Motif de croix rondouillarde : patternColors manquant (roundedCrossColors)" }
            return buildRoundedPattern(pattern, center, maxRadius, patternColors)
        }
        return buildShapedCross(
            style, center, maxRadius, numberOfRays, angleIrregularity,
            minDivisionsPerRay, maxDivisionsPerRay, lightLengthMinRatio, lightLengthMaxRatio,
            ringGold, armHalfWidthRatio, lobeRadiusRatio, lobeSpreadRatio, showRays, equalArms, tierShades, random
        )
    }
}

/** Les pièces sont construites par la composition : la segmentation les renvoie telles quelles. */
class ShapedCrossSegmentationSystem : SegmentationSystem {
    override fun segment(guide: CompositionGuide, width: Double, height: Double, random: Random): List<List<Vector2>> {
        require(guide is ShapedCrossGuide) { "ShapedCrossSegmentationSystem nécessite un ShapedCrossGuide" }
        return guide.pieces
    }
}

/**
 * Couleurs propres à la croix : bras et lobes en blanc cassé
 * (armShades), médaillon (medallionShades), anneau en blanc cassé ou en
 * or. Les rayons et la gloire gardent les couleurs de la palette.
 * ⚠ Consomme des tirages de random (appelée depuis computeVitrail,
 * Renderer.kt, au même endroit que applyRadiantCrossColors).
 */
fun applyShapedCrossColors(
    guide: ShapedCrossGuide,
    cellColors: MutableList<ColorRGBa>,
    armShades: List<ColorRGBa>,
    medallionShades: List<ColorRGBa>,
    random: Random
) {
    fun pick(list: List<ColorRGBa>) = list[random.nextInt(list.size)]
    guide.roles.forEachIndexed { i, role ->
        val imposed = guide.pieceShades?.getOrNull(i)
        if (imposed != null) { cellColors[i] = pick(imposed); return@forEachIndexed }
        when (role) {
            ShapedCrossRole.MEDALLION -> cellColors[i] = pick(medallionShades)
            ShapedCrossRole.ARM -> {
                val tier = guide.tiers.getOrElse(i) { -1 }
                cellColors[i] = pick(if (guide.tierShades != null && tier >= 0) guide.tierShades[tier] else armShades)
            }
            // anneau doré : l'or vif de la bordure des baies (BayStyle.BORDER_GOLD),
            // le même pour toutes les palettes (cohérence entre les designs)
            ShapedCrossRole.RING -> cellColors[i] = pick(if (guide.ringGold) BayStyle.BORDER_GOLD else armShades)
            ShapedCrossRole.GLORY, ShapedCrossRole.RAY -> {}
        }
    }
}

/** Répartition des couleurs de la saison dans la croix rondouillarde (sans rayons). */
enum class RoundedCrossScheme {
    /** Cœur dans la couleur principale de la saison, bouts des bras plus clairs. */
    CORE,
    /** Cœur blanc cassé, bouts des bras dans la couleur de la saison (une croix de lumière aux extrémités colorées). */
    TIPS,
    /** Cœur clair ; bouts des bras en deux tons, couleur de la saison et ton profond, alternés comme les pales d'un moulin. */
    PINWHEEL,
    /** Cœur dans la couleur de la saison ; bouts des bras alternés ton clair et blanc cassé. */
    PINWHEEL_LIGHT
}

/**
 * Teintes des trois groupes de pièces de la croix rondouillarde (cœur, puis
 * les deux moitiés du bout de chaque bras), tirées de la palette :
 * - principale : coloredMainShades (Palette.kt) ;
 * - claire : la famille la plus présente parmi celles de luminance entre
 *   0,45 et 0,85 (à défaut, la plus claire) ;
 * - profonde : la plus sombre des familles de luminance au moins 0,12 ;
 * - blanc cassé : celui des bras des croix (CROSS_ARM_SHADES).
 */
fun LiturgicalPalette.roundedCrossShades(scheme: RoundedCrossScheme): List<List<ColorRGBa>> {
    fun lum(c: ColorRGBa) = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b
    val main = coloredMainShades()
    val others = families.filter { it.shades != main }
    val light = (others.filter { lum(it.shades[0]) in 0.45..0.85 }.maxByOrNull { it.weight }
        ?: others.maxByOrNull { lum(it.shades[0]) }!!).shades
    val deep = (others.filter { lum(it.shades[0]) >= 0.12 }.minByOrNull { lum(it.shades[0]) } ?: others.first()).shades
    val ivory = LiturgicalPalettes.CROSS_ARM_SHADES
    return when (scheme) {
        RoundedCrossScheme.CORE -> listOf(main, light, light)
        RoundedCrossScheme.TIPS -> listOf(ivory, main, main)
        RoundedCrossScheme.PINWHEEL -> listOf(light, main, deep)
        RoundedCrossScheme.PINWHEEL_LIGHT -> listOf(main, light, ivory)
    }
}

// --------------------------------------------------
// CONSTRUCTION
// --------------------------------------------------

/** Un bras : direction (unitaire) et longueur depuis le centre jusqu'à son extrémité. */
private class CrossArm(val u: Vector2, val tip: Double) {
    /** Perpendiculaire (u tourné d'un quart de tour). */
    val v = Vector2(-u.y, u.x)
}

private fun buildShapedCross(
    style: ShapedCrossStyle,
    c: Vector2,
    m: Double,
    numberOfRays: Int,
    angleIrregularity: Double,
    minDivisions: Int,
    maxDivisions: Int,
    lightMin: Double,
    lightMax: Double,
    ringGold: Boolean,
    armHalfWidthRatio: Double,
    lobeRadiusRatio: Double,
    lobeSpreadRatio: Double,
    showRays: Boolean,
    equalArms: Boolean,
    tierShades: List<List<ColorRGBa>>?,
    random: Random
): ShapedCrossGuide {

    val trefoil = style == ShapedCrossStyle.TREFOIL
    val celtic = style == ShapedCrossStyle.CELTIC
    val rounded = style == ShapedCrossStyle.ROUNDED
    fun at(arm: CrossArm, s: Double, t: Double) = Vector2(c.x + arm.u.x * s + arm.v.x * t, c.y + arm.u.y * s + arm.v.y * t)
    fun circle(center: Vector2, r: Double, n: Int = 64) =
        (0 until n).map { Vector2(center.x + r * cos(2 * PI * it / n), center.y + r * sin(2 * PI * it / n)) }

    // Proportions (en part de maxRadius). Croix latine : le bras du bas
    // plus long que les autres, celui du haut plus court. Croix grecque :
    // quatre bras égaux.
    // Croix rondouillarde : plus petite quand elle a des rayons, pour qu'ils
    // dépassent nettement de sa silhouette (sinon ils ne font qu'une frange).
    val roundedScale = if (rounded && showRays) 0.68 / 0.80 else 1.0
    val halfWidth = armHalfWidthRatio * m * roundedScale
    // le médaillon doit couvrir les coins intérieurs du croisement des bras
    // (sauf pour la croix rondouillarde, aux bras trop larges : le médaillon
    // y est posé au centre, plus petit que le croisement)
    val medallionR = if (rounded) 0.17 * m * roundedScale else max((if (trefoil) 0.15 else 0.13) * m, halfWidth * sqrt(2.0) + 0.02 * m)
    val latin = listOf(if (trefoil) 0.66 else 0.62, 1.00, if (trefoil) 0.74 else 0.70, if (trefoil) 0.74 else 0.70)
    val greek = if (celtic) 0.75 else 0.80 * roundedScale
    val tips = if (equalArms) List(4) { greek } else latin
    val arms = listOf(
        CrossArm(Vector2(0.0, -1.0), tips[0] * m),   // haut
        CrossArm(Vector2(0.0, 1.0), tips[1] * m),    // bas
        CrossArm(Vector2(-1.0, 0.0), tips[2] * m),   // gauche
        CrossArm(Vector2(1.0, 0.0), tips[3] * m)     // droite
    )
    // Trèfle : lobe central au bout du bras, deux lobes latéraux un peu en
    // retrait et écartés, qui débordent largement du bras.
    val lobeR = lobeRadiusRatio * m
    val sideBack = 1.0 * lobeR
    val sideOut = lobeSpreadRatio * lobeR
    // Anneau celtique
    val ringIn = 0.30 * m
    val ringOut = 0.40 * m

    val segments = mutableListOf<Segment>()
    val medallion = circle(c, medallionR)
    segments += polygonEdgeSegments(medallion)

    // --- Bras : rectangles, lobes, coupes
    val barRects = mutableListOf<List<Vector2>>()
    val lobes = mutableListOf<List<Vector2>>()
    // par bras : positions des coupes transversales du fût (s croissants),
    // et pour le trèfle : début du trèfle, centre du trèfle, angle des
    // coupes entre lobes
    val barCuts = mutableListOf<List<Double>>()
    val trefoilBase = DoubleArray(4)
    val trefoilCenter = DoubleArray(4)
    var cuspAngle = 0.0
    // Croix rondouillarde : un seul contour (voir roundedCrossOutline),
    // coupé par les diagonales (creux entre les bras), une coupe en travers
    // de chaque bras et, au-delà, une coupe le long de l'axe.
    val roundedFillet = 0.07 * m * roundedScale
    val roundedCut = 0.47 * m * roundedScale
    val roundedOutline = if (rounded) roundedCrossOutline(c, arms.map { it.u }, arms[0].tip, halfWidth, roundedFillet) else emptyList()
    if (rounded) {
        segments += polygonEdgeSegments(roundedOutline)
        for (q in 0 until 4) {
            val a = PI / 4 + q * PI / 2
            segments += Segment(
                Vector2(c.x + medallionR * 0.5 * cos(a), c.y + medallionR * 0.5 * sin(a)),
                Vector2(c.x + 0.9 * m * cos(a), c.y + 0.9 * m * sin(a))
            )
        }
        for (arm in arms) {
            segments += Segment(at(arm, roundedCut, -roundedCut), at(arm, roundedCut, roundedCut))
            segments += Segment(at(arm, roundedCut, 0.0), at(arm, arm.tip + 0.05 * m, 0.0))
        }
    }
    for ((k, arm) in arms.withIndex()) {
        if (rounded) break
        val sCentral = arm.tip - lobeR
        val sSide = sCentral - sideBack
        val barEnd = if (trefoil) sCentral else arm.tip
        val rect = listOf(at(arm, 0.0, -halfWidth), at(arm, barEnd, -halfWidth), at(arm, barEnd, halfWidth), at(arm, 0.0, halfWidth))
        barRects += rect
        segments += polygonEdgeSegments(rect)

        val cuts = mutableListOf<Double>()
        if (trefoil) {
            val central = circle(at(arm, sCentral, 0.0), lobeR)
            val left = circle(at(arm, sSide, -sideOut), lobeR)
            val right = circle(at(arm, sSide, sideOut), lobeR)
            lobes += listOf(central, left, right)
            segments += polygonEdgeSegments(central) + polygonEdgeSegments(left) + polygonEdgeSegments(right)
            // Cœur du trèfle (barycentre des trois centres) ; base du trèfle
            // au bas des lobes latéraux.
            val sHeart = (sCentral + 2 * sSide) / 3.0
            val sBase = sSide - lobeR
            trefoilCenter[k] = sHeart
            trefoilBase[k] = sBase
            // Coupe entre le lobe central et chaque lobe latéral : du cœur
            // jusqu'au point de rencontre extérieur des deux cercles.
            val cusp = outerIntersection(Vector2(sCentral, 0.0), Vector2(sSide, sideOut), lobeR)
            cuspAngle = atan2(cusp.y, cusp.x - sHeart)
            val reach = 3.0 * lobeR
            for (side in listOf(-1.0, 1.0)) {
                segments += Segment(at(arm, sHeart, 0.0), at(arm, sHeart + reach * cos(cuspAngle), side * reach * sin(cuspAngle)))
            }
            // Entre les deux lobes latéraux, le long de l'axe, jusqu'à la base
            segments += Segment(at(arm, sHeart, 0.0), at(arm, sBase, 0.0))
            // Base du trèfle
            segments += Segment(at(arm, sBase, -3.0 * lobeR), at(arm, sBase, 3.0 * lobeR))
            // Fût : du médaillon à la base du trèfle
            cuts += evenCuts(medallionR, sBase, 0.16 * m, random)
        } else {
            // Fût coupé au bord intérieur et au bord extérieur de l'anneau
            // (les plombs de l'anneau se prolongent à travers le bras),
            // puis au-delà selon sa longueur.
            cuts += ringIn
            cuts += ringOut
            cuts += evenCuts(ringOut, arm.tip, 0.18 * m, random)
        }
        for (s in cuts) segments += Segment(at(arm, s, -halfWidth), at(arm, s, halfWidth))
        barCuts += cuts.sorted()
    }

    // --- Anneau celtique : deux cercles, coupé à 45° dans chaque quart ;
    // la même coupe traverse la gloire jusqu'au médaillon.
    val ringInner = circle(c, ringIn, 96)
    val ringOuter = circle(c, ringOut, 96)
    if (celtic) {
        segments += polygonEdgeSegments(ringInner) + polygonEdgeSegments(ringOuter)
        for (q in 0 until 4) {
            val a = PI / 4 + q * PI / 2
            segments += Segment(
                Vector2(c.x + medallionR * 0.5 * cos(a), c.y + medallionR * 0.5 * sin(a)),
                Vector2(c.x + ringOut * 1.02 * cos(a), c.y + ringOut * 1.02 * sin(a))
            )
        }
    }

    // Zone « pleine » pour les rayons : ce que les rayons ne traversent pas
    // (la croix ; pour la croix celtique, tout le disque de l'anneau).
    val blockers: List<List<Vector2>> = when (style) {
        ShapedCrossStyle.TREFOIL -> listOf(medallion) + barRects + lobes
        ShapedCrossStyle.CELTIC -> barRects + listOf(ringOuter)
        ShapedCrossStyle.ROUNDED -> listOf(roundedOutline)
    }
    fun blocked(p: Vector2) = blockers.any { polygonContains(it, p) }

    // --- Rayons
    val angles = generateRayAngleBoundaries(numberOfRays, angleIrregularity, random)
    val step = m / 300.0
    val margin = 0.03 * m
    val rayLength = DoubleArray(numberOfRays)
    val rayCuts = mutableListOf<List<Double>>()   // seuils le long de la bissectrice
    val visible = BooleanArray(numberOfRays)
    for (i in 0 until numberOfRays) {
        val a0 = angles[i]; val a1 = angles[i + 1]
        val half = (a1 - a0) / 2
        val wanted = random.nextDouble(lightMin, lightMax) * m
        // Pour quelques directions du secteur : sortie de la zone pleine (rho),
        // puis éventuel retour dedans (lobes, bout des bras).
        var rho = 0.0
        var reentry = Double.MAX_VALUE
        // (pas entiers : mêmes positions relatives à toutes les échelles)
        for (k in 0..6) {
            val a = a0 + (a1 - a0) * k / 6.0
            var n = 0
            while (n < 600 && blocked(Vector2(c.x + n * step * cos(a), c.y + n * step * sin(a)))) n++
            rho = max(rho, n * step)
            var n2 = n + 1
            while (n2 * step < wanted + margin && !blocked(Vector2(c.x + n2 * step * cos(a), c.y + n2 * step * sin(a)))) n2++
            if (n2 * step < wanted + margin) reentry = min(reentry, n2 * step)
        }
        val length = min(wanted, reentry - margin)
        // première coupe au-delà de la zone pleine sur toute la largeur du
        // secteur (la corde à ce rayon ne doit pas toucher la croix)
        val first = rho / cos(half) + margin
        // Tirage toujours effectué (même si le rayon est caché), pour que
        // la suite des tirages ne dépende pas de la forme exacte.
        val radii = generateRayRadii(first, max(length, first + 1e-6), minDivisions, maxDivisions, random)
        // seuil entre deux pas de la marche (0,105 et non 0,10) : un écart
        // tombant pile sur le seuil donnerait des résultats différents à
        // l'aperçu et à l'export
        visible[i] = showRays && length - rho > 0.105 * m
        rayLength[i] = length
        // seuils : coupes intermédiaires (la première, `first`, n'est pas
        // une coupe : la première pièce commence au bord de la croix)
        rayCuts += radii.drop(1).dropLast(1).map { it * cos(half) }
    }
    for (i in 0 until numberOfRays) {
        val prev = (i - 1 + numberOfRays) % numberOfRays
        val reach = max(if (visible[i]) rayLength[i] else 0.0, if (visible[prev]) rayLength[prev] else 0.0)
        if (reach > 0.0) {
            val a = angles[i]
            segments += Segment(c, Vector2(c.x + reach * cos(a), c.y + reach * sin(a)))
        }
        if (!visible[i]) continue
        val a0 = angles[i]; val a1 = angles[i + 1]
        val half = (a1 - a0) / 2
        for (d in rayCuts[i] + rayLength[i] * cos(half)) {
            val r = d / cos(half)
            segments += Segment(Vector2(c.x + r * cos(a0), c.y + r * sin(a0)), Vector2(c.x + r * cos(a1), c.y + r * sin(a1)))
        }
    }

    // --- Identifiant de la pièce qui contient un point (null : hors vitrail)
    fun pieceId(p: Vector2): Pair<String, ShapedCrossRole>? {
        val dx = p.x - c.x; val dy = p.y - c.y
        if (polygonContains(medallion, p)) return "M" to ShapedCrossRole.MEDALLION
        // bras dont l'axe est le plus proche
        val k = arms.indices.maxByOrNull { arms[it].u.x * dx + arms[it].u.y * dy }!!
        val arm = arms[k]
        val s = arm.u.x * dx + arm.u.y * dy
        val t = arm.v.x * dx + arm.v.y * dy
        if (rounded && polygonContains(roundedOutline, p)) {
            if (s < roundedCut) return "A$k:0" to ShapedCrossRole.ARM
            return (if (t < 0) "A$k:L" else "A$k:R") to ShapedCrossRole.ARM
        }
        val inArm = !rounded && polygonContains(barRects[k], p) || (trefoil && (0 until 3).any { polygonContains(lobes[3 * k + it], p) })
        if (inArm) {
            if (trefoil && s > trefoilBase[k]) {
                val phi = atan2(abs(t), s - trefoilCenter[k])
                return if (phi < cuspAngle) "A$k:C" to ShapedCrossRole.ARM
                else (if (t < 0) "A$k:L" else "A$k:R") to ShapedCrossRole.ARM
            }
            val j = barCuts[k].count { it < s }
            return "A$k:$j" to ShapedCrossRole.ARM
        }
        val r = sqrt(dx * dx + dy * dy)
        var theta = atan2(dy, dx)
        if (theta < 0) theta += 2 * PI
        if (celtic) {
            // quart et moitié (coupe à 45°) : angle mesuré depuis le bras de droite
            val q = (theta / (PI / 2)).toInt().coerceIn(0, 3)
            val h = if (theta - q * PI / 2 < PI / 4) 0 else 1
            if (polygonContains(ringOuter, p) && !polygonContains(ringInner, p)) return "R$q:$h" to ShapedCrossRole.RING
            if (polygonContains(ringInner, p)) return "G$q:$h" to ShapedCrossRole.GLORY
        }
        // rayon
        var i = angles.indexOfLast { it <= theta }.coerceIn(0, numberOfRays - 1)
        if (!visible[i]) return null
        val a0 = angles[i]; val a1 = angles[i + 1]
        val mid = (a0 + a1) / 2
        val d = dx * cos(mid) + dy * sin(mid)
        if (d > rayLength[i] * cos((a1 - a0) / 2)) return null
        if (r < 1e-9) return null
        val j = rayCuts[i].count { it < d }
        return "Y$i:$j" to ShapedCrossRole.RAY
    }

    // --- Faces, regroupées par pièce
    val faces = polygonizeSegments(segments)
    val groups = LinkedHashMap<String, MutableList<List<Vector2>>>()
    val roleOf = HashMap<String, ShapedCrossRole>()
    for (face in faces) {
        val id = pieceId(interiorPoint(face)) ?: continue
        groups.getOrPut(id.first) { mutableListOf() }.add(face)
        roleOf[id.first] = id.second
    }
    fun tierOf(id: String): Int = when {
        !rounded || !id.startsWith("A") -> -1
        id.endsWith(":0") -> 0
        id.endsWith(":L") -> 1
        else -> 2
    }
    val pieces = mutableListOf<Triple<List<Vector2>, ShapedCrossRole, Int>>()
    for ((id, group) in groups) {
        val role = roleOf[id]!!
        val merged = mergeFaces(group)
        if (merged != null) pieces += Triple(merged, role, tierOf(id))
        else group.forEach { pieces += Triple(it, role, tierOf(id)) }   // repli : faces séparées
    }

    // Ordre indépendant de l'échelle (les couleurs sont tirées dans cet ordre)
    fun key(p: List<Vector2>): Pair<Long, Long> {
        val g = polygonCentroid(p)
        return Math.round((g.y - c.y) / m * 1e6) to Math.round((g.x - c.x) / m * 1e6)
    }
    val sorted = pieces.sortedWith(compareBy({ key(it.first).first }, { key(it.first).second }))
    return ShapedCrossGuide(
        sorted.map { it.first }, sorted.map { it.second }, ringGold, sorted.map { it.third }, tierShades,
        center = c, rayRadius = if (showRays) lightMax * m else 0.0, style = style
    )
}

/**
 * Contour de la croix rondouillarde : quatre bras de demi-largeur w,
 * terminés par un demi-cercle (bout à la distance tip du centre), et
 * raccordés deux à deux par un congé (creux arrondi) de rayon f, tangent
 * aux deux bras voisins. Les bras doivent être donnés dans l'ordre où
 * l'on tourne (u du bras suivant = perpendiculaire v du bras courant).
 */
internal fun roundedCrossOutline(c: Vector2, directions: List<Vector2>, tip: Double, w: Double, f: Double): List<Vector2> {
    // ordre de rotation : droite, bas, gauche, haut (y vers le bas)
    // Le côté droit du bras doit exister : 2w + f ≤ tip (sinon le contour
    // se recouperait). Avec tip = 0,80 : w ≤ 0,365 pour f = 0,07.
    require(2 * w + f <= tip) { "Croix rondouillarde : bras trop larges (2 × demi-largeur + congé > longueur du bras)" }
    val order = listOf(Vector2(1.0, 0.0), Vector2(0.0, 1.0), Vector2(-1.0, 0.0), Vector2(0.0, -1.0))
    require(directions.all { d -> order.any { it.x == d.x && it.y == d.y } })
    fun p(u: Vector2, v: Vector2, s: Double, t: Double) = Vector2(c.x + u.x * s + v.x * t, c.y + u.y * s + v.y * t)
    val pts = mutableListOf<Vector2>()
    val nCap = 40
    val nFillet = 16
    for (u in order) {
        val v = Vector2(-u.y, u.x)
        // côté t = −w, du congé précédent jusqu'au début du bout arrondi
        pts += p(u, v, w + f, -w)
        pts += p(u, v, tip - w, -w)
        // bout arrondi : demi-cercle de centre (tip − w, 0)
        for (i in 1 until nCap) {
            val a = -PI / 2 + PI * i / nCap
            pts += p(u, v, tip - w + w * cos(a), w * sin(a))
        }
        // côté t = +w, jusqu'au congé
        pts += p(u, v, tip - w, w)
        pts += p(u, v, w + f, w)
        // congé vers le bras suivant : arc de centre (w + f, w + f), du point
        // de tangence sur ce bras (direction −v) à celui sur le bras suivant
        // (direction −u)
        for (i in 1 until nFillet) {
            val a = PI * i / (2.0 * nFillet)
            val dx = -sin(a); val dy = -cos(a)   // de −v (a = 0) à −u (a = π/2)
            pts += p(u, v, w + f + f * dx, w + f + f * dy)
        }
    }
    return pts
}

/** Coupes régulières (légèrement irrégulières) d'un intervalle [s0, s1], une pièce environ tous les `target`. */
private fun evenCuts(s0: Double, s1: Double, target: Double, random: Random): List<Double> {
    val n = max(1, ((s1 - s0) / target).roundToInt())
    val share = (s1 - s0) / n
    return (1 until n).map { s0 + it * share + random.nextDouble(-0.15, 0.15) * share }
}

/** Point de rencontre « extérieur » (côté t positif, le plus loin du centre de la croix) de deux cercles de même rayon. */
private fun outerIntersection(a: Vector2, b: Vector2, r: Double): Vector2 {
    val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
    val dx = b.x - a.x; val dy = b.y - a.y
    val d = sqrt(dx * dx + dy * dy)
    val h = sqrt(max(0.0, r * r - d * d / 4))
    val nx = -dy / d; val ny = dx / d
    val p1 = Vector2(mx + nx * h, my + ny * h)
    val p2 = Vector2(mx - nx * h, my - ny * h)
    return if (p1.x + p1.y > p2.x + p2.y) p1 else p2
}

/**
 * Réunit des faces voisines (issues d'un même arrangement, donc à sommets
 * partagés) en un seul polygone : les arêtes parcourues dans les deux sens
 * sont intérieures et disparaissent ; les autres forment le contour. Renvoie
 * null si le résultat n'est pas un contour unique (trou, pièces séparées).
 */
internal fun mergeFaces(faces: List<List<Vector2>>): List<Vector2>? {
    if (faces.size == 1) return faces[0]
    fun key(p: Vector2) = java.lang.Double.doubleToLongBits(p.x).toString() + "_" + java.lang.Double.doubleToLongBits(p.y)
    val point = HashMap<String, Vector2>()
    val directed = HashMap<Pair<String, String>, Int>()
    for (f in faces) {
        val ccw = if (signedArea(f) >= 0) f else f.reversed()
        for (i in ccw.indices) {
            val a = key(ccw[i]); val b = key(ccw[(i + 1) % ccw.size])
            point[a] = ccw[i]; point[b] = ccw[(i + 1) % ccw.size]
            directed[a to b] = (directed[a to b] ?: 0) + 1
        }
    }
    val boundary = directed.keys.filter { (a, b) -> !directed.containsKey(b to a) }
    if (boundary.isEmpty()) return null
    val next = HashMap<String, String>()
    for ((a, b) in boundary) {
        if (next.containsKey(a)) return null   // sommet touché deux fois : contour non simple
        next[a] = b
    }
    val start = boundary.first().first
    val loop = mutableListOf<Vector2>()
    var cur = start
    var guard = 0
    do {
        loop += point[cur]!!
        cur = next[cur] ?: return null
        if (++guard > boundary.size + 1) return null
    } while (cur != start)
    return if (loop.size == boundary.size) canonicalRotation(loop) else null
}