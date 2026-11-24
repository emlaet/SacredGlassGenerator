import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * C2 NOIRE — les finalistes de la phase 13, implémentés en Kotlin (phase 14).
 *
 * Six variantes de la rosace C2 « Croix fleurie », version A (calice) :
 *   V1_BC, V1_BC_NOIR, OG_K2  — Obsidienne et givre
 *   ON_K2, ON_K4              — Onyx et nacre (ON_K4 : croix moins évasée, CrossFlowerShape.ON1)
 *   BR_C1                     — Black Reliquary (reflet continu sur les pétales)
 *
 * Chaque variante est une recette complète et indépendante (C2NoirVariant) :
 * matières (couleur propre de chaque matière), éclairage (lumière du centre,
 * reflets), textures, plombs et silhouette de la croix. Modifier une variante
 * ne touche aucune autre variante, ni aucune autre palette.
 *
 * Les couleurs reproduisent exactement les aperçus Python qui ont servi au
 * choix (mêmes opérations, dans le même ordre, en OKLab, arrondies à 8 bits à
 * chaque étape). Aucun tirage aléatoire : la variation d'une pièce à l'autre
 * ne dépend que de sa position.
 *
 * Utilisation : VitrailConfig(c2Noir = C2NoirVariants.OG_K2), ou plus
 * simplement c2NoirConfig(config, C2NoirVariants.OG_K2) ; touche « n » de
 * TemplateProgram : export des six variantes (exportC2NoirSet).
 */

// ================================================================ réglages

/** Couleur d'une matière sur les deux faces d'un pétale (de part et d'autre
 *  de la nervure). Faces en miroir : la face claire regarde toujours le bras
 *  vertical de la croix. */
data class FacePair(val light: String, val dark: String)

/** Lumière concentrée autour de l'hostie : +amount (clarté OKLab) au centre,
 *  décroissance gaussienne de largeur width (en part du rayon). */
data class C2Glow(val amount: Double, val width: Double)

/** Finition métallique d'une pièce, appliquée sur sa couleur éclairée :
 *  patine (part de la couleur de patine), variation d'une pièce à l'autre,
 *  reflet froid sur une partie des pièces, lumière intérieure facultative. */
data class C2Metal(
    val patina: String,
    val patinaAmount: Double,
    val variation: Double,
    val coolReflect: String? = null,
    val coolAmount: Double = 0.0,
    val innerLight: Double = 0.0,
    val innerWidth: Double = 1.0
)

/** Reflet CONTINU sur chaque pétale (une seule surface bombée et polie) :
 *  un reflet sur la face claire, une ombre douce sur l'autre face.
 *  Positions et largeurs en part de la demi-largeur du pétale. Calculé dans
 *  le shader (Glass.kt), par-dessus la texture du verre. */
data class C2PetalShade(
    val highlight: Double,
    val highlightPosition: Double,
    val highlightWidth: Double,
    val shadow: Double,
    val shadowPosition: Double,
    val shadowWidth: Double
)

/** Variation de profondeur du fond, lente et irrégulière (Black Reliquary) :
 *  jusqu'à +lighten (zones claires) et −deepen (zones sombres), au-delà du
 *  seuil threshold ; trois pièces d'accent (+accent) ; reflet coloré
 *  (reflect, part reflectAmount) dans les zones claires. */
data class C2FieldDepth(
    val lighten: Double,
    val deepen: Double,
    val threshold: Double,
    val accent: Double,
    val reflect: String?,
    val reflectAmount: Double
)

/** Texture du fond selon les zones (mat / poli) : de low à high, réparti lentement. */
data class C2FieldTexture(val low: Double, val high: Double, val seed: Double)

/** Les matières d'une variante : une classe par famille (chaque famille a sa
 *  propre logique, voir les fonctions de couleur plus bas). */
sealed interface C2NoirMaterials

/** Obsidienne et givre : fond velours d'obsidienne, pétales de givre,
 *  nervure (et éventuellement base des pétales et triangles du calice) en
 *  obsidienne. Lumière issue de l'hostie (C2Glow) sur le fond, la croix et
 *  les pétales. */
data class ObsidianFrostMaterials(
    val fieldTones: List<String>,
    val fieldGlow: C2Glow,
    val fieldGrain: Double,
    val crossColors: List<String>,         // cœur, bras, extrémité (dans la bordure)
    val crossGlow: C2Glow,
    val crossGrain: Double,
    val crossBlue: Double,
    val calyx: String,
    val tip: String,
    val petalGlow: C2Glow,
    val petalGrain: Double,
    val petalBase: FacePair,               // partie du pétale proche du calice (jusqu'à l'arc 0,60)
    val petalTip: FacePair,                // partie extérieure du pétale
    val vein: FacePair,                    // nervure (« flamme »)
    /** true : la base des pétales prend la matière de la nervure (V1-bc). */
    val baseAsVein: Boolean,
    /** true : les 8 triangles du calice prennent la matière de la nervure. */
    val calyxAsVein: Boolean,
    /** Part de la lumière de l'hostie retirée sur les pièces de nervure
     *  (0 : aucune ; 0,7 : obsidienne plus noire). */
    val veinLightCut: Double,
    /** Finitions métalliques, par rôle de pièce ("cross", "cen1" = anneau du calice). */
    val metals: Map<String, C2Metal> = emptyMap(),
    /** Texture du verre par rôle de pièce (1,0 sinon ; hostie : HOST_TEXTURE_SCALE). */
    val textures: Map<String, Double> = emptyMap(),
    /** Verre de la bordure. */
    val border: String = "#15171A",
    /** Phase 16 : lumière commune à toute la rosace, comme dans la rosace bleue
     *  (même formule que RosacePalette.lightAmount : plus clair en haut à
     *  gauche, plus sombre en bas à droite), en clarté OKLab ajoutée au
     *  maximum (0,04 = ±0,04). Toutes les pièces sauf l'hostie et la bordure
     *  (qui descendrait sinon au niveau du plomb). 0 = aucune. */
    val directionalLight: Double = 0.0,
    /** Phase 16 (V1-BC) : le corps gris du pétale (matière de la nervure :
     *  base, nervure, triangles du calice) s'éclaircit de pièce en pièce en
     *  allant du calice vers les pointes givrées, de 0 à cette clarté OKLab. */
    val veinGradient: Double = 0.0,
    /** Phase 21 (exploration chromatique) : profondeur du fond selon le rayon,
     *  en clarté OKLab : +fieldRadial au bord intérieur du fond (près de la
     *  fleur), −fieldRadial au bord extérieur (près de la bordure), linéaire
     *  entre les deux. 0 = aucune. */
    val fieldRadial: Double = 0.0,
    /** Phase 23 : givre des pétales sans lumière de l'hostie ni grain, pour
     *  que deux pièces de même couleur aient exactement la même teinte. */
    val frostUniform: Boolean = false
) : C2NoirMaterials

/** Black Reliquary : fond d'onyx vivant, pétales d'onyx sculpté, filet
 *  d'argent, croix et calice métalliques. Lumière issue du centre en
 *  cosinus (+centralLight au centre, −0,35 × centralLight dans la bordure). */
data class ReliquaryMaterials(
    val centralLight: Double,
    val fieldTones: List<String>,
    val fieldGrain: Double,
    val crossColors: List<String>,         // cœur, bras, extrémité
    val crossGrain: Double,
    val crossLightFactor: Double,
    val calyx: String,
    val tip: String,
    val pieceGrain: Double,
    val petalBase: FacePair,
    val petalTip: FacePair,
    val vein: FacePair,
    val calyxAsVein: Boolean,
    /** Lumière concentrée sur certains rôles seulement (anneau du calice, pointes). */
    val glow: C2Glow?,
    val glowRoles: Set<String>,
    val metals: Map<String, C2Metal>,
    /** Éclaircissement de la partie courbe des pétales : base, pointe. */
    val petalLiftBase: Double,
    val petalLiftTip: Double,
    /** Contraste entre les faces : face claire, face sombre (ajouts de clarté). */
    val petalFaceLight: Double,
    val petalFaceDark: Double,
    val fieldDepth: C2FieldDepth?,
    val fieldTexture: C2FieldTexture?,
    val textures: Map<String, Double> = emptyMap()
) : C2NoirMaterials

/** Onyx et nacre : fond de nacre lumineux, croix et pétales en onyx. */
data class NacreMaterials(
    val fieldInner: String,
    val fieldOuter: String,
    val fieldVariation: Double,
    /** Assombrissement vers le bord du fond (profondeur). */
    val fieldRim: Double,
    /** Irisation froide (vert d'eau → bleu → lavande), en chroma OKLab. */
    val fieldIridescence: Double,
    val fieldTexture: Double,
    val crossColors: List<String>,         // cœur, bras, extrémité
    val crossGlow: Double,
    val crossTexture: Double,
    val calyx: String,
    val calyxVariation: Double,
    val calyxIridescence: Double,
    val calyxTexture: Double,
    val petalBase: FacePair,
    val petalTip: FacePair,
    val vein: FacePair,
    /** true : les 8 triangles du calice prennent la matière de la base du pétale. */
    val harmonizedCalyx: Boolean,
    /** Nervure = base du pétale + veinOffset (clarté) ; null : couleur vein. */
    val veinOffset: Double?,
    val petalTexture: Double,
    val borderTexture: Double
) : C2NoirMaterials

/** Une variante complète. */
data class C2NoirVariant(
    /** Identifiant unique (nom du fichier exporté). */
    val id: String,
    val label: String,
    val materials: C2NoirMaterials,
    /** Silhouette de la croix (géométrie) ; C2 = croix de référence. */
    val crossShape: CrossFlowerShape = CrossFlowerShape.C2,
    /** Couleur de tous les plombs (et du contour extérieur). */
    val leadColor: String = "#2E2A2A",
    /** Couleur des plombs situés ENTRE deux pièces du fond (null : leadColor). */
    val fieldLeadColor: String? = null,
    /** Reflet continu des pétales (null : aucun). */
    val petalShade: C2PetalShade? = null,
    /** Verre noir traversé par la lumière : transmission maximale du verre
     *  sombre (VitrailConfig.darkGlassTransmission) ; null = verre actuel. */
    val transmission: Double? = null
)

// ================================================================ les six variantes

object C2NoirVariants {

    private val FROST_BASE = FacePair("#F2F5F8", "#CED4DB")
    private val FROST_TIP = FacePair("#E1E6EC", "#BEC6CF")

    private fun obsidianFrost(vein: FacePair, baseAsVein: Boolean, veinLightCut: Double,
                              metals: Map<String, C2Metal> = emptyMap(), textures: Map<String, Double> = emptyMap()) =
        ObsidianFrostMaterials(
            fieldTones = listOf("#151A20", "#191F26", "#1D232B"), fieldGlow = C2Glow(0.04, 0.5), fieldGrain = 0.012,
            crossColors = listOf("#C9CED4", "#A8AFB7", "#7E858D"), crossGlow = C2Glow(0.06, 0.6), crossGrain = 0.03, crossBlue = 0.0,
            calyx = "#B6BCC3", tip = "#F4F6F8",
            petalGlow = C2Glow(0.10, 0.45), petalGrain = 0.01,
            petalBase = FROST_BASE, petalTip = FROST_TIP, vein = vein,
            baseAsVein = baseAsVein, calyxAsVein = true, veinLightCut = veinLightCut,
            metals = metals, textures = mapOf("mid0@flame" to 0.45) + textures)

    /** A1 — V1-bc : bases des pétales et triangles du calice en obsidienne ; givre aux pointes. */
    val V1_BC = C2NoirVariant("V1-bc", "Obsidienne et givre — V1-bc",
        obsidianFrost(FacePair("#38414D", "#2C333D"), baseAsVein = true, veinLightCut = 0.0))

    /** A2 — V1-bc noir : même composition, obsidienne plus noire (lumière de l'hostie réduite de 70 % sur l'obsidienne). */
    val V1_BC_NOIR = C2NoirVariant("V1-bc-noir", "Obsidienne et givre — V1-bc noir",
        obsidianFrost(FacePair("#2E363F", "#252B33"), baseAsVein = true, veinLightCut = 0.7))

    /** A3 — OG-K2 (phase 12) : nervure atténuée, calice argent froid patiné,
     *  croix argent ancien patiné, plombs anthracite. */
    val OG_K2 = C2NoirVariant("OG-K2", "Obsidienne et givre — OG-K2",
        obsidianFrost(FacePair("#585F68", "#494F58"), baseAsVein = false, veinLightCut = 0.7,
            metals = mapOf(
                "cen1" to C2Metal("#A3ACB6", 0.40, 0.030, "#B9C8D8", 0.30),
                "cross" to C2Metal("#979CA3", 0.45, 0.035, "#B6BEC8", 0.20)),
            textures = mapOf("cen1" to 0.6, "cross" to 1.2)),
        leadColor = "#3A3D42")

    private fun nacre(fieldInner: String, fieldOuter: String) = NacreMaterials(
        fieldInner = fieldInner, fieldOuter = fieldOuter, fieldVariation = 0.03, fieldRim = 0.03, fieldIridescence = 0.008, fieldTexture = 0.8,
        crossColors = listOf("#1C1F23", "#16181B", "#1B1E22"), crossGlow = 0.02, crossTexture = 0.3,
        calyx = "#D8DBDF", calyxVariation = 0.015, calyxIridescence = 0.0, calyxTexture = 0.4,
        petalBase = FacePair("#62676F", "#4C5158"), petalTip = FacePair("#4D5259", "#3D4147"), vein = FacePair("#50555C", "#42464C"),
        harmonizedCalyx = true, veinOffset = -0.08, petalTexture = 0.8, borderTexture = 0.2)

    /** B1 — ON-K2 : croix noire, plombs du fond gris perle, nacre gris-perle ; géométrie d'origine. */
    val ON_K2 = C2NoirVariant("ON-K2", "Onyx et nacre — ON-K2", nacre("#E3E6E9", "#D6DADF"), fieldLeadColor = "#A9AEB5")

    /** B2 — ON-K4 : silhouette ON1 (croix moins évasée), plombs gris perle, nacre blanche froide. */
    val ON_K4 = C2NoirVariant("ON-K4", "Onyx et nacre — ON-K4", nacre("#EEF1F4", "#E3E8ED"),
        crossShape = CrossFlowerShape.ON1, fieldLeadColor = "#A9AEB5")

    /** C1 — BR-C1 : onyx sculpté (A10-1c), reflet continu sculptural (BR2),
     *  croix platine froid sombre, plombs anthracite. */
    val BR_C1 = C2NoirVariant("BR-C1", "Black Reliquary — BR-C1",
        ReliquaryMaterials(
            centralLight = 0.08,
            fieldTones = listOf("#1C1E21", "#202226", "#25272B"), fieldGrain = 0.02,
            crossColors = listOf("#C9CED4", "#8E949B", "#4A4E54"), crossGrain = 0.04, crossLightFactor = 0.4,
            calyx = "#D3D6DA", tip = "#C9CCD0", pieceGrain = 0.01,
            petalBase = FacePair("#3A3F47", "#2C3036"), petalTip = FacePair("#2C3036", "#23272C"), vein = FacePair("#60636A", "#4D5258"),
            calyxAsVein = true,
            glow = C2Glow(0.20, 0.50), glowRoles = setOf("cen1", "gold@petalTip"),
            metals = mapOf(
                "cen1" to C2Metal("#858A92", 0.50, 0.045, "#A9B5C3", 0.22),
                "cross" to C2Metal("#767C85", 0.55, 0.020, "#A7B2BF", 0.25)),
            petalLiftBase = 0.1956, petalLiftTip = 0.1256,
            petalFaceLight = 0.02, petalFaceDark = -0.02,
            fieldDepth = C2FieldDepth(0.06, 0.0, 0.2, 0.03, "#252C38", 0.12),
            fieldTexture = C2FieldTexture(0.5, 1.6, 0.9),
            textures = mapOf("cen1" to 1.4, "cross" to 0.6)),
        leadColor = "#3A3D42",
        petalShade = C2PetalShade(0.38, 0.30, 0.40, 0.20, 0.40, 0.40))

    val ALL = listOf(V1_BC, V1_BC_NOIR, OG_K2, ON_K2, ON_K4, BR_C1)
}

/** La recette d'une variante, à partir de la recette de base (plombs, verre). */
fun c2NoirConfig(base: VitrailConfig, v: C2NoirVariant): VitrailConfig = base.copy(
    compositionSystem = CrossFlowerRosaceCompositionSystem(calyx = true, crossShape = v.crossShape),
    segmentationSystem = RosaceSegmentationSystem(),
    curvatureAmount = 0.0,
    leadStyle = base.leadStyle.copy(width = 1.2, color = ColorRGBa.fromHex(v.leadColor)),
    glassStyle = base.glassStyle.copy(opalescenceStrength = 0.05),
    c2Noir = v,
    darkGlassTransmission = v.transmission,
    bay = null,
    printFormat = PrintFormat(300.0, 300.0, 300.0)
)

/** Exporte les variantes, une par fichier : exports/c2noir/<id>.png
 *  (même taille, même verre, mêmes plombs pour toutes). */
fun exportC2NoirSet(drawer: Drawer, base: VitrailConfig, referenceWidth: Double,
                    variants: List<C2NoirVariant> = C2NoirVariants.ALL): List<File> {
    val dir = File("exports/c2noir").apply { mkdirs() }
    return variants.map { v ->
        val cfg = c2NoirConfig(base, v)
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "${v.id}.png"))
    }
}

// ================================================================ couleurs (identiques aux aperçus Python)

/** Couleurs 0xRRGGBB et opérations en OKLab, arrondies à 8 bits à chaque étape
 *  (arrondi « demi vers le pair », comme Python). */
internal object C2Color {
    fun parse(h: String): Int = h.removePrefix("#").toInt(16)
    /** Couleur sRGB, comme toutes les palettes du projet (ColorRGBa.fromHex).
     *  ⚠ Ne pas utiliser le constructeur ColorRGBa(r, g, b) : sa linéarité
     *  par défaut est LINEAR, et OpenRNDR éclaircit alors fortement les
     *  couleurs sombres à l'affichage (#151A20 rendu vers #515A63) — c'était
     *  la cause des noirs « gris » des premiers rendus de la phase 14. */
    fun toColor(c: Int): ColorRGBa = ColorRGBa.fromHex("#%06X".format(c))
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    private fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
    fun toOk(c: Int): DoubleArray {
        val r = lin((c shr 16 and 255) / 255.0); val g = lin((c shr 8 and 255) / 255.0); val b = lin((c and 255) / 255.0)
        val l = Math.pow(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b, 1.0 / 3)
        val m = Math.pow(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b, 1.0 / 3)
        val s = Math.pow(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b, 1.0 / 3)
        return doubleArrayOf(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }
    fun fromOk(L: Double, a: Double, b: Double): Int {
        val l0 = L + 0.3963377774 * a + 0.2158037573 * b; val m0 = L - 0.1055613458 * a - 0.0638541728 * b; val s0 = L - 0.0894841775 * a - 1.2914855480 * b
        val l = l0 * l0 * l0; val m = m0 * m0 * m0; val s = s0 * s0 * s0
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        fun to8(v: Double) = Math.rint(gam(max(0.0, v)).coerceIn(0.0, 1.0) * 255).toInt()
        return (to8(r) shl 16) or (to8(g) shl 8) or to8(bb)
    }
    /** Clarté + dL (bornée à 0..1), et b − db. */
    fun addL(c: Int, dL: Double, db: Double = 0.0): Int { val o = toOk(c); return fromOk((o[0] + dL).coerceIn(0.0, 1.0), o[1], o[2] - db) }
    fun mix(x: Int, y: Int, t: Double): Int { val a = toOk(x); val b = toOk(y); return fromOk(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t) }
}

/** Ce qu'il faut savoir d'une pièce pour la colorer (unités de la rosace). */
internal class C2Piece(val role: String, polygon: Polygon) {
    val cx: Double; val cy: Double; val px: Double; val py: Double
    init {
        val c = polygon.centroid; cx = c.x; cy = c.y
        val p = polygon.interiorPoint; px = p.x; py = p.y
    }
    val r = hypot(cx, cy)
    val seg = if (r < 0.3) 0 else if (r < 0.8) 1 else 2
    private val a = atan2(py, px)
    private val u = pyMod(a - PI / 4 + PI / 4, PI / 2) - PI / 4
    private val kd = Math.rint((a - PI / 4) / (PI / 2)) * (PI / 2) + PI / 4
    /** Partie du pétale proche du calice (jusqu'à l'arc 0,60). */
    val inner = px * cos(-kd) - py * sin(-kd) < 0.60 / 0.965
    private val plusSide = u > 0
    /** Quart : 0 NE, 1 SE, 2 SO, 3 NO (à l'écran). */
    val quad = pyMod(Math.rint((a - PI / 4) / (PI / 2)), 4.0).toInt()
    /** Face claire (faces en miroir : la face claire regarde le bras vertical). */
    val lightFace = plusSide != (quad == 0 || quad == 2)
    val isField = role == "out0" || role == "out1@fieldOuter"
    /** Les trois pièces d'accent du fond (Black Reliquary). */
    val isAccent = isField && Math.rint(Math.toDegrees(atan2(cy, cx))).let { d ->
        (d == -120.0 && r > 0.65) || (d == 155.0 && r <= 0.65) || (d == 65.0 && r <= 0.65) }

    // variations lentes selon la position (mêmes formules que les aperçus)
    fun noise(x: Double, y: Double): Double { val v = sin(x * 12.9898 + y * 78.233) * 43758.5453; return v - floor(v) }
    fun grain(amount: Double) = amount * (noise(cx * 3.1, cy * 2.7) - 0.5) * 2
    fun smooth(): Double { val a = atan2(cy, cx); return 0.5 * sin(1.7 * a + 0.9) + 0.3 * sin(3.1 * a - 1.4 + 2.0 * r) + 0.2 * cos(5.0 * r + a) }
    fun fnoise(): Double { val a = atan2(cy, cx); val n = 0.55 * sin(a + 0.8 + 1.5 * r) + 0.35 * sin(2 * a - 1.1 + 3.0 * r) + 0.25 * sin(3 * a + 2.3 - 2.0 * r); return (n / 1.12).coerceIn(-1.0, 1.0) }
    fun pnoise(seed: Double = 0.0): Double { val a = atan2(cy, cx); val n = 0.6 * sin(2.3 * a + 0.7 + seed + 4.0 * r) + 0.4 * sin(5.1 * a - 1.2 + 2 * seed - 6.0 * r); return n.coerceIn(-1.0, 1.0) }
    private val rn = ((r - 0.13) / 0.80).coerceIn(0.0, 1.0)
    fun gauss(w: Double) = exp(-(rn / w) * (rn / w))
    fun glow(g: C2Glow) = g.amount * gauss(g.width)
    /** Lumière du centre en cosinus : +A au centre, 0 à mi-rayon, −0,35 A dans la bordure. */
    fun cosLight(A: Double): Double { val f = cos(PI * ((r - 0.13) / (0.93 - 0.13)).coerceIn(0.0, 1.0)); return A * (if (f > 0) f else 0.35 * f) }
    fun tone(tones: List<String>): Int { val j = smooth() * 0.5 + 0.5; return C2Color.parse(tones[min(tones.size - 1, (j * tones.size).toInt())]) }
    fun face(p: FacePair) = C2Color.parse(if (lightFace) p.light else p.dark)
    companion object {
        fun pyMod(x: Double, m: Double): Double { val r = x % m; return if (r < 0) r + m else r }
    }
}

private fun metal(col: Int, p: C2Piece, mt: C2Metal): Int {
    var c = C2Color.mix(col, C2Color.parse(mt.patina), mt.patinaAmount)
    val n = p.pnoise()
    c = C2Color.addL(c, mt.variation * n)
    if (mt.coolReflect != null && n > 0) c = C2Color.mix(c, C2Color.parse(mt.coolReflect), mt.coolAmount * n)
    if (mt.innerLight != 0.0) c = C2Color.addL(c, mt.innerLight * p.gauss(mt.innerWidth))
    return c
}

/** Rôle effectif d'une pièce (la base des pétales ou les triangles du calice
 *  peuvent prendre la matière de la nervure). */
private fun effectiveRole(m: C2NoirMaterials, p: C2Piece): String = when {
    m is ObsidianFrostMaterials && m.baseAsVein && p.role == "mid1@petal" && p.inner -> "mid0@flame"
    m is ObsidianFrostMaterials && m.calyxAsVein && p.role == "cen1@calyx" -> "mid0@flame"
    m is ReliquaryMaterials && m.calyxAsVein && p.role == "cen1@calyx" -> "mid0@flame"
    else -> p.role
}

private fun obsidianFrostColor(m: ObsidianFrostMaterials, p: C2Piece, role: String): Int {
    val P = C2Color
    var c = when {
        role == "cen0@host" -> P.parse("#F2F3F4")
        role == "deepBig" -> P.parse(m.border)
        role == "cross" -> P.addL(P.parse(m.crossColors[p.seg]), p.glow(m.crossGlow) + p.grain(m.crossGrain), m.crossBlue)
        p.isField -> P.addL(p.tone(m.fieldTones), p.glow(m.fieldGlow) + p.grain(m.fieldGrain) +
                m.fieldRadial * (1.0 - 2.0 * ((p.r - 0.30) / (0.90 - 0.30)).coerceIn(0.0, 1.0)))
        role == "cen1" || role == "cen1@calyx" -> P.parse(m.calyx)
        role == "gold@petalTip" -> P.parse(m.tip)
        role == "mid1@petal" && m.frostUniform -> p.face(if (p.inner) m.petalBase else m.petalTip)
        role == "mid1@petal" -> P.addL(p.face(if (p.inner) m.petalBase else m.petalTip), p.glow(m.petalGlow) + p.grain(m.petalGrain))
        role == "mid0@flame" -> P.addL(p.face(m.vein), p.glow(m.petalGlow) + p.grain(m.petalGrain))
        else -> P.parse("#FF00FF")
    }
    if (m.veinLightCut != 0.0 && role == "mid0@flame") c = P.addL(c, -m.veinLightCut * p.glow(m.petalGlow))
    m.metals[role]?.let { c = metal(c, p, it) }
    if (m.veinGradient != 0.0 && role == "mid0@flame") {
        val t = ((p.r - 0.18) / (0.50 - 0.18)).coerceIn(0.0, 1.0)
        c = P.addL(c, m.veinGradient * t * t * (3 - 2 * t))
    }
    if (m.directionalLight != 0.0 && role != "cen0@host" && role != "deepBig") {
        val k = hypot(1.0, 0.35)
        c = P.addL(c, m.directionalLight * (p.cx / k - 0.35 * p.cy / k))
    }
    return c
}

private fun reliquaryColor(m: ReliquaryMaterials, p: C2Piece, role: String): Int {
    val P = C2Color
    val light = p.cosLight(m.centralLight)
    fun lit(h: Int, grain: Double): Int { val o = P.toOk(h); return P.fromOk((o[0] + light + p.grain(grain)).coerceIn(0.0, 1.0), o[1], o[2]) }
    var c = when {
        role == "cen0@host" -> P.parse("#F2F3F4")
        role == "deepBig" -> P.parse("#15171A")
        role == "cross" -> { val o = P.toOk(P.parse(m.crossColors[p.seg])); P.fromOk((o[0] + m.crossLightFactor * light + p.grain(m.crossGrain)).coerceIn(0.0, 1.0), o[1], o[2]) }
        p.isField -> lit(p.tone(m.fieldTones), m.fieldGrain)
        role == "cen1" || role == "cen1@calyx" -> lit(P.parse(m.calyx), m.pieceGrain)
        role == "gold@petalTip" -> lit(P.parse(m.tip), m.pieceGrain)
        role == "mid1@petal" -> lit(p.face(if (p.inner) m.petalBase else m.petalTip), m.pieceGrain)
        role == "mid0@flame" -> lit(p.face(m.vein), m.pieceGrain)
        else -> P.parse("#FF00FF")
    }
    if (m.glow != null && role in m.glowRoles) c = P.addL(c, p.glow(m.glow))
    m.metals[role]?.let { c = metal(c, p, it) }
    if (role == "mid1@petal") {
        c = P.addL(c, if (p.inner) m.petalLiftBase else m.petalLiftTip)
        c = P.addL(c, if (p.lightFace) m.petalFaceLight else m.petalFaceDark)
    }
    val fd = m.fieldDepth
    if (fd != null && p.isField) {
        val n = p.fnoise(); var d = 0.0
        if (n > fd.threshold) d += fd.lighten * (n - fd.threshold) / (1 - fd.threshold)
        if (n < -fd.threshold) d -= fd.deepen * (-n - fd.threshold) / (1 - fd.threshold)
        if (p.isAccent && fd.accent != 0.0) d += fd.accent
        c = P.addL(c, d)
        if (fd.reflect != null && n > 0) c = P.mix(c, P.parse(fd.reflect), fd.reflectAmount * n)
    }
    return c
}

private fun nacreColor(m: NacreMaterials, p: C2Piece): Int {
    val P = C2Color
    fun irid(col: Int, chroma: Double): Int {
        val t = 0.5 + 0.5 * sin(1.3 * atan2(p.cy, p.cx) + 2.2 * p.r + 0.6)
        val h = Math.toRadians(170 + 120 * t)
        val o = P.toOk(col); return P.fromOk(o[0], o[1] + chroma * cos(h), o[2] + chroma * sin(h))
    }
    return when {
        p.role == "cen0@host" -> P.parse("#F2F3F4")
        p.role == "deepBig" -> P.parse("#15171A")
        p.isField -> {
            val base = P.parse(if (p.role == "out0") m.fieldInner else m.fieldOuter)
            val c = P.addL(base, m.fieldVariation * p.fnoise() - m.fieldRim * max(0.0, (p.r - 0.3) / 0.6))
            if (m.fieldIridescence != 0.0) irid(c, m.fieldIridescence) else c
        }
        p.role == "cross" -> P.addL(P.parse(m.crossColors[p.seg]), m.crossGlow * p.gauss(0.35))
        p.role == "cen1@calyx" && m.harmonizedCalyx -> p.face(m.petalBase)
        p.role == "cen1" || p.role == "cen1@calyx" -> {
            val c = P.addL(P.parse(m.calyx), m.calyxVariation * p.pnoise(0.4))
            if (m.calyxIridescence != 0.0) irid(c, m.calyxIridescence) else c
        }
        p.role == "mid1@petal" -> p.face(if (p.inner) m.petalBase else m.petalTip)
        p.role == "mid0@flame" -> if (m.veinOffset != null) P.addL(p.face(m.petalBase), m.veinOffset) else p.face(m.vein)
        p.role == "gold@petalTip" -> P.parse(m.petalTip.dark)
        else -> P.parse("#FF00FF")
    }
}

/** Couleur, texture et groupe (reflet continu) de chaque pièce d'une variante. */
class C2NoirPieceData(val colors: List<ColorRGBa>, val rgb: List<Int>, val textureScales: List<Double>, val shadeGroups: List<Double>)

private val c2PieceCache = IdentityHashMap<List<RosacePiece>, MutableMap<C2NoirVariant, C2NoirPieceData>>()

fun c2NoirPieceData(unitPieces: List<RosacePiece>, v: C2NoirVariant): C2NoirPieceData = synchronized(c2PieceCache) {
    c2PieceCache.getOrPut(unitPieces) { mutableMapOf() }.getOrPut(v) {
        val m = v.materials
        val rgb = mutableListOf<Int>(); val tex = mutableListOf<Double>(); val grp = mutableListOf<Double>()
        for (u in unitPieces) {
            val p = C2Piece(u.role, u.polygon)
            val role = effectiveRole(m, p)
            rgb += when (m) {
                is ObsidianFrostMaterials -> obsidianFrostColor(m, p, role)
                is ReliquaryMaterials -> reliquaryColor(m, p, role)
                is NacreMaterials -> nacreColor(m, p)
            }
            tex += if (role == "cen0@host") HOST_TEXTURE_SCALE else when (m) {
                is ObsidianFrostMaterials -> m.textures[role] ?: 1.0
                is ReliquaryMaterials -> m.textures[role] ?: if (p.isField && m.fieldTexture != null) {
                    val ft = m.fieldTexture; val n = p.pnoise(ft.seed) * 0.5 + 0.5 * p.fnoise(); ft.low + (ft.high - ft.low) * (n * 0.5 + 0.5)
                } else 1.0
                is NacreMaterials -> when {
                    p.isField -> m.fieldTexture
                    role == "mid1@petal" || role == "mid0@flame" || role == "gold@petalTip" || (role == "cen1@calyx" && m.harmonizedCalyx) -> m.petalTexture
                    role == "cen1" || role == "cen1@calyx" -> m.calyxTexture
                    role == "cross" -> m.crossTexture
                    role == "deepBig" -> m.borderTexture
                    else -> 1.0
                }
            }
            grp += if (v.petalShade != null && (role == "mid1@petal" || role == "mid0@flame")) 1.0 else 0.0
        }
        C2NoirPieceData(rgb.map { C2Color.toColor(it) }, rgb, tex, grp)
    }
}

/** Remplace les couleurs des pièces par celles de la variante (aucun tirage). */
fun applyC2NoirColors(guide: RosaceGuide, cellColors: MutableList<ColorRGBa>, v: C2NoirVariant) {
    val d = c2NoirPieceData(guide.unitPieces, v)
    for (i in cellColors.indices) cellColors[i] = d.colors[i]
}

// ================================================================ reflet continu (profil des pétales)

/** Sens vertical de c_boundsPosition dans le shader (OpenRNDR). false :
 *  0 en haut de la pièce, 1 en bas (sens des coordonnées du dessin). Si le
 *  reflet de BR-C1 apparaît sur la face des pétales tournée vers le bras
 *  HORIZONTAL de la croix (et non vers le bras vertical), passer à true. */
const val C2_SHADE_FLIP_Y = false

/** Le reflet continu, prêt pour le shader : réglages et profil du pétale
 *  (demi-largeur en 33 points, de la base s0 à la pointe s1, le long de l'axe). */
data class C2ShadeProgram(val shade: C2PetalShade, val s0: Double, val s1: Double, val widths: List<Double>)

private val c2ProfileCache = IdentityHashMap<List<RosacePiece>, Triple<Double, Double, List<Double>>>()

/** Profil du pétale NE (nervure comprise), calculé sur la géométrie réelle. */
fun c2PetalProfile(unitPieces: List<RosacePiece>): Triple<Double, Double, List<Double>> = synchronized(c2ProfileCache) {
    c2ProfileCache.getOrPut(unitPieces) {
        val gf = GeometryFactory()
        val q0 = unitPieces.filter { (it.role == "mid1@petal" || it.role == "mid0@flame") &&
                Math.abs(Math.toDegrees(atan2(it.polygon.centroid.y, it.polygon.centroid.x)) - 45) < 30 }.map { it.polygon as Geometry }
        val u = UnaryUnionOp.union(q0)
        val ca = cos(PI / 4); val sa = sin(PI / 4)
        val xs = u.coordinates.map { it.x * ca + it.y * sa }
        val s0 = xs.min(); val s1 = xs.max()
        val widths = (0..32).map { k ->
            val s = s0 + (s1 - s0) * k / 32
            val line = gf.createLineString(arrayOf(Coordinate(s * ca + 2 * sa, s * sa - 2 * ca), Coordinate(s * ca - 2 * sa, s * sa + 2 * ca)))
            val seg = line.intersection(u); if (seg.isEmpty) 0.0 else seg.length / 2
        }
        Triple(s0, s1, widths)
    }
}

fun c2ShadeProgram(guide: RosaceGuide, v: C2NoirVariant): C2ShadeProgram? = v.petalShade?.let { sh ->
    val (s0, s1, w) = c2PetalProfile(guide.unitPieces); C2ShadeProgram(sh, s0, s1, w)
}

/** Code GLSL du reflet continu (inséré par Glass.kt après l'opalescence).
 *  Position dans la rosace : p_modelOrigin + c_boundsPosition × p_modelSize
 *  (unités de la rosace ; u vers le haut de l'image, v vers la droite). */
fun c2ShadeGlsl(sp: C2ShadeProgram): String {
    val s = sp.shade
    fun f(x: Double) = String.format(java.util.Locale.ROOT, "%.6f", x)
    return """
        // 6. REFLET CONTINU DES PÉTALES (C2 noire, Black Reliquary)
        if (p_shadeGroup > 0.5) {
            vec2 bpS = c_boundsPosition.xy;
            float muS = p_modelOrigin.x - bpS.y * p_modelSize.x;
            float mvS = p_modelOrigin.y + bpS.x * p_modelSize.y;
            float angS = atan(mvS, muS);
            float kqS = floor((angS - 0.7853981634) / 1.5707963268 + 0.5);
            float akS = 0.7853981634 + kqS * 1.5707963268;
            float ssS = muS * cos(akS) + mvS * sin(akS);
            float ttS = -muS * sin(akS) + mvS * cos(akS);
            float snS = clamp((ssS - ${f(sp.s0)}) / ${f(sp.s1 - sp.s0)}, 0.0, 1.0);
            float PW[33] = float[33](${sp.widths.joinToString(", ") { f(it) }});
            float fiS = snS * 32.0;
            int i0S = int(floor(fiS)); int i1S = min(i0S + 1, 32);
            float wS = max(mix(PW[i0S], PW[i1S], fiS - float(i0S)), 0.001);
            float tnS = ttS / wS;
            float envS = pow(sin(3.1415926536 * snS), 0.7);
            float sigS = (mod(kqS, 2.0) < 0.5) ? -1.0 : 1.0;
            float hS = (tnS - sigS * ${f(s.highlightPosition)}) / ${f(s.highlightWidth)};
            float dS = (tnS + sigS * ${f(s.shadowPosition)}) / ${f(s.shadowWidth)};
            light *= 1.0 + ${f(s.highlight)} * exp(-hS * hS) * envS - ${f(s.shadow)} * exp(-dS * dS) * envS;
        }
    """.trimIndent()
}

// ================================================================ plombs du fond (Onyx et nacre)

private val c2FieldLeadCache = IdentityHashMap<List<List<Vector2>>, Pair<List<ShapeContour>, List<Pair<ShapeContour, Double>>>>()

/** Plombs situés ENTRE deux pièces du fond, redessinés dans leur propre
 *  couleur par-dessus les plombs noirs (ceux qui bordent la croix, les
 *  pétales, le calice et la bordure restent noirs). */
fun drawC2FieldLeads(drawer: Drawer, guide: RosaceGuide, cells: List<List<Vector2>>, offset: Vector2,
                     color: ColorRGBa, fineWidth: Double, leadWidth: Double) {
    val (fine, thick) = synchronized(c2FieldLeadCache) {
        c2FieldLeadCache.getOrPut(cells) {
            val gf = GeometryFactory()
            fun poly(c: List<Vector2>): Polygon = gf.createPolygon((c + c[0]).map { Coordinate(it.x, it.y) }.toTypedArray())
            val field = guide.unitPieces.indices.filter { guide.unitPieces[it].role == "out0" || guide.unitPieces[it].role == "out1@fieldOuter" }
                .map { poly(cells[it]) }
            val inner = UnaryUnionOp.union(field.map { it as Geometry }).buffer(-0.006 * guide.radius)
            fun contours(g: Geometry): List<ShapeContour> {
                val out = mutableListOf<ShapeContour>()
                fun add(ls: LineString) {
                    val pts = ls.coordinates.map { Vector2(it.x, it.y) }
                    if (pts.size < 2) return
                    out += ShapeContour.fromSegments(pts.zipWithNext { a, b -> Segment2D(a, b) }, closed = false)
                }
                when (g) {
                    is LineString -> add(g)
                    is MultiLineString -> for (i in 0 until g.numGeometries) add(g.getGeometryN(i) as LineString)
                    else -> for (i in 0 until g.numGeometries) (g.getGeometryN(i) as? LineString)?.let { add(it) }
                }
                return out
            }
            val fineC = field.flatMap { contours(it.boundary.intersection(inner)) }
            val thickC = guide.leadLines.filter { it.points.size > 1 && it.factor > 0 }.flatMap { ln ->
                val pts = ln.points.map { Coordinate(it.x + offset.x, it.y + offset.y) }.let { if (ln.closed) it + it[0] else it }
                contours(gf.createLineString(pts.toTypedArray()).intersection(inner)).map { it to ln.factor }
            }
            fineC to thickC
        }
    }
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = color
        drawer.lineCap = LineCap.ROUND
        drawer.strokeWeight = fineWidth
        for (c in fine) drawer.contour(c)
        for ((c, f) in thick) { drawer.strokeWeight = leadWidth * f; drawer.contour(c) }
    }
}


// ================================================================ plombs fins par catégorie (phase 15)

/** Séparations intérieures : fond, fond extérieur, bordure, couronne. */
val C2_JOINT_ROLES = setOf("out0", "out1@fieldOuter", "deepBig", "cen1")
/** Nervures des pétales : toutes les lignes fines à l'intérieur d'un pétale. */
val C2_NERVURE_ROLES = setOf("mid1@petal", "mid0@flame", "gold@petalTip", "cen1@calyx")

private val c2GroupLeadCache = IdentityHashMap<List<List<Vector2>>, MutableMap<Set<String>, List<ShapeContour>>>()

/** Redessine, à l'épaisseur donnée, les plombs situés ENTRE deux pièces dont
 *  les rôles appartiennent au groupe (lignes intérieures de leur union). */
fun drawRosaceGroupFineLeads(drawer: Drawer, guide: RosaceGuide, cells: List<List<Vector2>>, roles: Set<String>,
                             color: ColorRGBa, width: Double) {
    val lines = synchronized(c2GroupLeadCache) {
        c2GroupLeadCache.getOrPut(cells) { mutableMapOf() }.getOrPut(roles) {
            val gf = GeometryFactory()
            fun poly(c: List<Vector2>): Polygon = gf.createPolygon((c + c[0]).map { Coordinate(it.x, it.y) }.toTypedArray())
            val group = guide.unitPieces.indices.filter { guide.unitPieces[it].role in roles }.map { poly(cells[it]) }
            if (group.isEmpty()) return@getOrPut emptyList()
            val inner = UnaryUnionOp.union(group.map { it as Geometry }).buffer(-0.004 * guide.radius)
            val out = mutableListOf<ShapeContour>()
            fun add(g: Geometry) {
                for (i in 0 until g.numGeometries) {
                    val ls = g.getGeometryN(i) as? LineString ?: continue
                    val pts = ls.coordinates.map { Vector2(it.x, it.y) }
                    if (pts.size >= 2) out += ShapeContour.fromSegments(pts.zipWithNext { a, b -> Segment2D(a, b) }, closed = false)
                }
            }
            for (p in group) add(p.boundary.intersection(inner))
            out
        }
    }
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = color
        drawer.lineCap = LineCap.ROUND
        drawer.strokeWeight = width
        for (cn in lines) drawer.contour(cn)
    }
}

// ================================================================ étude « verre noir traversé par la lumière » (23 nov.)

/*
 * Brief du 23 nov. 2026 : la palette noire doit être rendue comme du VERRE
 * DE VITRAIL RÉTROÉCLAIRÉ, sur le modèle de la rosace bleue (C2 + MARIAL_C2),
 * et non comme une pierre. Étape 1 : neuf propositions, mêmes conditions.
 *
 *   Piste A — compositions existantes, nouveau traitement du verre sombre :
 *             OG-K2 et V1-BC, directions A, B et C.
 *   Piste B — déclinaison noire de la rosace bleue : même géométrie (C2,
 *             version A), même architecture de couleurs que MARIAL_C2 (fond,
 *             fond extérieur, pétale, flamme, croix profonde, bordure,
 *             calice, hostie) et même traitement (palette par rôles,
 *             rosacePieceColor, lumière d'une pièce à l'autre), transposés
 *             dans le noir ; directions A, B et C.
 *
 * Directions du verre (clarté OKLab des grands fonds ; transmission maximale) :
 *   A — noir fumé lumineux    : ≈ 0,22 à 0,28, transmission 2,2, nuances discrètes
 *   B — graphite translucide  : ≈ 0,29 à 0,35, transmission 1,7, nuances plus visibles
 *   C — noir profond nuancé   : ≈ 0,18 à 0,23, transmission 2,7, nuances bleutées
 *                               et argentées d'une zone à l'autre
 *
 * Le seul nouveau levier de rendu est la transmission du verre sombre
 * (Glass.kt, GlassStyle.darkGlassTransmission) : diffusion au cœur de chaque
 * pièce et irrégularités internes amplifiées, sans reflet, sans éclairage
 * extérieur, sans aucun mélange vers le blanc. Croix et plombs : traitement de
 * référence (celui de chaque composition ; piste B : plombs de la rosace
 * bleue #262B33). Leur exploration est l'étape 2.
 */
object C2NoirGlassStudy {

    /** Une direction du verre noir. */
    data class Direction(
        val code: String, val label: String,
        val transmission: Double,
        /** Piste A : tons du fond, bordure, obsidienne (faces claire / sombre). */
        val fieldTones: List<String>, val border: String, val obsidian: FacePair,
        /** Piste B : fond, fond extérieur, pétale, flamme, croix, bordure. */
        val bField: String, val bFieldOuter: String, val bPetal: String, val bFlame: String, val bCross: String, val bBorder: String,
        val bLight: Double
    )

    val A = Direction("A", "noir fumé lumineux", 2.2,
        listOf("#181B1E", "#1B1E22", "#1F2225"), "#111417", FacePair("#323639", "#282C2F"),
        "#26292D", "#202327", "#16191C", "#3D4044", "#101214", "#121416", 6.0)
    val B = Direction("B", "graphite translucide", 1.7,
        listOf("#292C2E", "#2D2F32", "#313336"), "#1F2224", FacePair("#45484B", "#3B3D40"),
        "#383B3E", "#313336", "#242729", "#535659", "#17181A", "#1D1F22", 7.0)
    val C = Direction("C", "noir profond nuancé", 2.7,
        listOf("#101213", "#0F151D", "#141819"), "#0B0D10", FacePair("#262C32", "#1E2227"),
        "#1A2028", "#171A1C", "#0B1016", "#474B4E", "#080B0F", "#0A0E11", 5.0)
    val DIRECTIONS = listOf(A, B, C)

    // ---- piste A
    private fun pisteA(base: C2NoirVariant, d: Direction, idPrefix: String, keepVein: Boolean): C2NoirVariant {
        val m = base.materials as ObsidianFrostMaterials
        return base.copy(id = "$idPrefix-${d.code}", label = "${base.label} — verre ${d.label}",
            // pas d'effet métallique dans l'étude du verre (brief) : calice et croix
            // restent des verres argentés unis ; leur exploration est l'étape 2
            materials = m.copy(fieldTones = d.fieldTones, border = d.border, vein = if (keepVein) m.vein else d.obsidian,
                metals = emptyMap()),
            transmission = d.transmission)
    }
    /** OG-K2 : la nervure atténuée (gris clair) est gardée ; seul le verre sombre change. */
    val OG_K2 = DIRECTIONS.map { pisteA(C2NoirVariants.OG_K2, it, "PA-OG-K2", keepVein = true) }
    /** V1-BC : bases des pétales et triangles en verre sombre de la direction. */
    val V1_BC = DIRECTIONS.map { pisteA(C2NoirVariants.V1_BC, it, "PA-V1-BC", keepVein = false) }

    // ---- piste B (palette par rôles, comme la rosace bleue)
    private fun c(h: String) = ColorRGBa.fromHex(h)
    fun palette(d: Direction): RosacePalette = RosacePalettes.MARIAL_C2.copy(
        name = "Noir — déclinaison de la rosace bleue, verre ${d.label}",
        outer = c(d.bField) to c(d.bField),
        cross = c(d.bCross),
        deepBig = c(d.bBorder),
        centre = c("#F2F3F4") to c("#C9CED4"),       // anneau du calice et triangles : argent (comme l'or de la bleue, non éclairé)
        gold = c("#C9CED4"),                         // pointes des pétales : argent
        lead = c("#262B33"),                         // plombs de la rosace bleue
        lightAmount = d.bLight,
        overrides = RosacePalettes.MARIAL_C2.overrides + mapOf(
            "petal" to c(d.bPetal), "flame" to c(d.bFlame), "fieldOuter" to c(d.bFieldOuter),
            "host" to c("#F2F3F4"), "armEnd" to c(d.bCross), "diagonal" to c(d.bPetal), "panelEnd" to c(d.bField),
            "lancet" to c(d.bPetal), "panel" to c(d.bField)))

    /** Recette de la piste B, direction d. */
    fun pisteB(base: VitrailConfig, d: Direction): VitrailConfig {
        val p = palette(d)
        return base.copy(
            compositionSystem = CrossFlowerRosaceCompositionSystem(calyx = true),
            segmentationSystem = RosaceSegmentationSystem(),
            curvatureAmount = 0.0,
            leadStyle = base.leadStyle.copy(width = 1.2, color = p.lead),
            glassStyle = base.glassStyle.copy(opalescenceStrength = 0.05),
            rosacePalette = p,
            c2Noir = null,
            darkGlassTransmission = d.transmission,
            bay = null,
            printFormat = PrintFormat(300.0, 300.0, 300.0))
    }

    /** Rosace bleue de référence (C2 + MARIAL_C2), rendue dans les mêmes conditions. */
    fun blueReference(base: VitrailConfig): VitrailConfig = pisteB(base, A).copy(
        rosacePalette = RosacePalettes.MARIAL_C2,
        leadStyle = base.leadStyle.copy(width = 1.2, color = RosacePalettes.MARIAL_C2.lead),
        darkGlassTransmission = null)

    // ---- lecture « vitrail » (23 nov., après la planche de l'étape 1)
    //
    // Constat : dans toutes les versions noires, les plombs (clarté OKLab 0,29
    // à 0,36) étaient PLUS CLAIRS que le verre sombre (0,15 à 0,28) ; dans un
    // vitrail rétroéclairé, le plomb, opaque, est toujours l'élément le plus
    // sombre. Règle testée : plombs presque noirs (#050608, clarté 0,12) et
    // aucun verre sous ≈ 0,20 (écart ≥ 0,08 partout, ≥ 0,11 en général).

    /** Plomb presque noir. */
    const val LEAD_VITRAIL = "#050608"

    /** Piste B, lecture vitrail : même structure (fond > fond extérieur >
     *  pétale > croix ≈ bordure ; flamme claire), verre relevé, écart
     *  pétale / fond élargi (≈ 0,075 au lieu de 0,06), C plus nettement bleu nuit. */
    val VB_A = A.copy(code = "A", bField = "#32363A", bFieldOuter = "#2A2E33", bPetal = "#1F2328", bFlame = "#4D5054",
        bCross = "#1E2123", bBorder = "#1F2225", bLight = 5.0)
    val VB_B = B.copy(code = "B", bField = "#424549", bFieldOuter = "#393C3F", bPetal = "#2B2E31", bFlame = "#5E6164",
        bCross = "#232528", bBorder = "#27292C", bLight = 5.0)
    val VB_C = C.copy(code = "C", bField = "#273140", bFieldOuter = "#232B33", bPetal = "#19212D", bFlame = "#4E5358",
        bCross = "#171D26", bBorder = "#181F27", bLight = 4.0)

    /** Piste A, lecture vitrail : direction C, fond relevé (0,245 à 0,275). */
    val VA_C = C.copy(fieldTones = listOf("#1E2123", "#1C2530", "#24282B"), border = "#1B1E22")

    private fun withBlackLeads(v: C2NoirVariant, id: String) =
        v.copy(id = id, label = v.label + " — plombs noirs", leadColor = LEAD_VITRAIL, fieldLeadColor = null)

    fun pisteBVitrail(base: VitrailConfig, d: Direction): VitrailConfig {
        val p = palette(d).copy(lead = c(LEAD_VITRAIL))
        return pisteB(base, d).copy(rosacePalette = p, leadStyle = base.leadStyle.copy(width = 1.2, color = p.lead))
    }

    /** Les six rendus du test « lecture vitrail ». */
    fun vitrail(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "V-PB-C-plombs-seuls" to pisteBVitrail(base, C),           // couleurs de PB-C inchangées, plombs noirs seuls
        "V-PB-A" to pisteBVitrail(base, VB_A),
        "V-PB-B" to pisteBVitrail(base, VB_B),
        "V-PB-C" to pisteBVitrail(base, VB_C),
        "V-OG-K2-C" to c2NoirConfig(base, withBlackLeads(pisteA(C2NoirVariants.OG_K2, VA_C, "V-OG-K2", keepVein = true), "V-OG-K2-C")),
        "V-V1-BC-C" to c2NoirConfig(base, withBlackLeads(pisteA(C2NoirVariants.V1_BC, VA_C, "V-V1-BC", keepVein = false), "V-V1-BC-C")))

    /** « Soleil noir » (23 nov., après le test « lecture vitrail ») : couleurs de
     *  PB-C (noir profond nuancé, lumière 5), plombs noirs (clarté 0,12), et
     *  relèvement des sombres (RosacePalette.lightnessFloor) : la pièce la plus
     *  sombre passe de ≈ 0,10 à 0,18, 0,20 ou 0,22, sans perdre la hiérarchie
     *  (fond > pétales > croix) ni le dégradé de lumière. Trois dosages. */
    val SOLEIL_NOIR_FLOORS = listOf(0.18, 0.20, 0.22)

    fun soleilNoir(base: VitrailConfig, floor: Double, d: Direction = C): VitrailConfig {
        val cfg = pisteBVitrail(base, d)
        return cfg.copy(rosacePalette = cfg.rosacePalette.copy(lightnessFloor = floor,
            name = "Noir — soleil noir (PB-C, plombs noirs, sombres relevés à ${"%.2f".format(java.util.Locale.ROOT, floor)})"))
    }

    fun soleilNoirSet(base: VitrailConfig): List<Pair<String, VitrailConfig>> =
        SOLEIL_NOIR_FLOORS.map { f -> "S-PB-C-releve-%03d".format(Math.round(f * 100)) to soleilNoir(base, f) }

    // ---- amélioration des trois finalistes (23 nov.)
    //   S-PB-C-releve-018 (soleil noir), V-OG-K2-C, V-V1-BC-C.

    /** Soleil noir : pétales séparés du fond par la TEINTE (pétales bleu nuit,
     *  chroma ≈ 0,028 au lieu de 0,014 ; fond neutre au lieu de bleuté), mêmes
     *  clartés ; flammes plus claires (0,41 → 0,48). */
    val SN2 = C.copy(bField = "#1E1F21", bPetal = "#07101B", bFlame = "#5A5E62")

    /** Piste A : fond resserré en une seule famille de noirs bleutés proches
     *  (clartés 0,25 / 0,26 / 0,27, au lieu d'une alternance bleu nuit / anthracite). */
    val VA_C2 = VA_C.copy(fieldTones = listOf("#1D2228", "#1F252C", "#22272D"))

    /** Croix acier : un cran plus sombre que l'argent (cœur 0,85 → 0,72 ;
     *  bras 0,75 → 0,63 ; extrémité 0,61 → 0,50), légèrement bleutée. */
    val CROSS_STEEL = listOf("#9EA5AD", "#828A93", "#5D646C")

    /** Plombs fins minimaux pour le test (multiple de leadStyle.width). */
    const val FINE_LEAD_MIN = 1.0

    private fun finalisteA(base: VitrailConfig, v: C2NoirVariant, keepVein: Boolean, id: String, steel: Boolean): VitrailConfig {
        var variant = withBlackLeads(pisteA(v, VA_C2, id, keepVein), id)
        if (steel) variant = variant.copy(materials = (variant.materials as ObsidianFrostMaterials).copy(crossColors = CROSS_STEEL))
        return c2NoirConfig(base, variant)
    }

    /** Les six rendus du test d'amélioration (touche « y »). */
    fun finalistes(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "F-SN-teinte" to soleilNoir(base, 0.18, SN2),
        "F-SN-teinte-plombs" to soleilNoir(base, 0.18, SN2).copy(minFineLeadFactor = FINE_LEAD_MIN),
        "F-OG-K2-fond" to finalisteA(base, C2NoirVariants.OG_K2, true, "F-OG-K2-fond", steel = false),
        "F-OG-K2-fond-acier" to finalisteA(base, C2NoirVariants.OG_K2, true, "F-OG-K2-fond-acier", steel = true),
        "F-V1-BC-fond" to finalisteA(base, C2NoirVariants.V1_BC, false, "F-V1-BC-fond", steel = false),
        "F-V1-BC-fond-acier" to finalisteA(base, C2NoirVariants.V1_BC, false, "F-V1-BC-fond-acier", steel = true))

    // ---- phase 15 : compléments

    /** D / E : fond resserré, verre noir un peu plus lumineux (+0,015 de
     *  clarté : 0,265 / 0,275 / 0,285), transmission 3,0 au lieu de 2,7, et
     *  variation de ±25 % d'une pièce à l'autre (certaines pièces montrent un
     *  peu plus la différence centre / bords). */
    val VA_D = VA_C2.copy(fieldTones = listOf("#21262C", "#222830", "#252B31"), transmission = 3.0)
    const val D_TRANSMISSION_VARIATION = 0.25

    /** V1-BC : parties grises des pétales (obsidienne) un peu plus lumineuses
     *  (+0,04 de clarté), géométrie inchangée. */
    val V1_OBSIDIAN_LIFT = FacePair("#30363C", "#282C31")

    private fun finalisteD(base: VitrailConfig, id: String, steel: Boolean): VitrailConfig {
        var variant = withBlackLeads(pisteA(C2NoirVariants.OG_K2, VA_D, id, keepVein = true), id)
        if (steel) variant = variant.copy(materials = (variant.materials as ObsidianFrostMaterials).copy(crossColors = CROSS_STEEL))
        return c2NoirConfig(base, variant).copy(transmissionVariation = D_TRANSMISSION_VARIATION)
    }

    private fun finalisteV1Diffus(base: VitrailConfig, id: String): VitrailConfig {
        val variant = withBlackLeads(pisteA(C2NoirVariants.V1_BC, VA_C2.copy(obsidian = V1_OBSIDIAN_LIFT), id, keepVein = false), id)
        return c2NoirConfig(base, variant)
    }

    /** Plombs fins du soleil noir : séparations à t, nervures un cran plus
     *  fines (t − 0,1), plombs structurels inchangés. */
    val LEAD_TESTS = listOf(0.8, 0.9, 1.0)

    /** Phase 15 : les douze rendus (touche « y »). */
    fun phase15(base: VitrailConfig): List<Pair<String, VitrailConfig>> = finalistes(base) + listOf(
        "F-OG-K2-D-verre-lumineux" to finalisteD(base, "F-OG-K2-D-verre-lumineux", steel = false),
        "F-OG-K2-E-verre-lumineux-acier" to finalisteD(base, "F-OG-K2-E-verre-lumineux-acier", steel = true),
        "F-V1-BC-fond-gris-diffus" to finalisteV1Diffus(base, "F-V1-BC-fond-gris-diffus")) +
            LEAD_TESTS.map { t ->
                "F-SN-teinte-plombs-%02d".format(Math.round(t * 10)) to soleilNoir(base, 0.18, SN2)
                    .copy(fineLeadJointFactor = t, fineLeadNervureFactor = t - 0.1)
            }

    // ---- phase 16 : perfectionnement (D, A1, C3)

    /** D1 : lumière cohérente à l'échelle de la rosace : variation indépendante
     *  d'une pièce à l'autre réduite (±25 % → ±8 %), diffusion dans chaque
     *  pièce un peu renforcée (transmission 3,0 → 3,4), lumière commune
     *  discrète (±0,04 de clarté, plus claire en haut à gauche). */
    const val P16_D1_VARIATION = 0.08
    const val P16_D1_TRANSMISSION = 3.4
    const val P16_D1_LIGHT = 0.04

    /** D2 : nuances du fond : trois noirs bleutés plus étagés (0,255 / 0,275 /
     *  0,295 au lieu de 0,265 / 0,275 / 0,285, teinte 252-256°) et texture du
     *  verre du fond plus marquée (×1,4), sans changer la lumière. */
    val P16_D2_TONES = listOf("#1E232A", "#22282F", "#282D33")
    val P16_D2_FIELD_TEXTURE = 1.4

    private fun p16OG(base: VitrailConfig, id: String, diffusion: Boolean, nuances: Boolean): VitrailConfig {
        var dir = VA_D
        if (diffusion) dir = dir.copy(transmission = P16_D1_TRANSMISSION)
        if (nuances) dir = dir.copy(fieldTones = P16_D2_TONES)
        var variant = withBlackLeads(pisteA(C2NoirVariants.OG_K2, dir, id, keepVein = true), id)
        var m = variant.materials as ObsidianFrostMaterials
        if (diffusion) m = m.copy(directionalLight = P16_D1_LIGHT)
        if (nuances) m = m.copy(textures = m.textures + mapOf("out0" to P16_D2_FIELD_TEXTURE, "out1@fieldOuter" to P16_D2_FIELD_TEXTURE))
        variant = variant.copy(materials = m)
        return c2NoirConfig(base, variant).copy(transmissionVariation = if (diffusion) P16_D1_VARIATION else D_TRANSMISSION_VARIATION)
    }

    /** Soleil noir, orientation 1 : pétales un peu plus sombres et plus bleus
     *  que A1 (clarté de base 0,17 → 0,145, chroma 0,028 → 0,042), toujours
     *  sous le fond. Orientation 2 : pétales plus clairs que le fond (clarté
     *  de base 0,33, ≈ 0,06 au-dessus du fond), même teinte que A1. */
    val SN_DARK_BLUE = SN2.copy(bPetal = "#010A1B")
    val SN_LIGHT = SN2.copy(bPetal = "#2C3644")

    private fun p16SN(base: VitrailConfig, d: Direction, leads08: Boolean): VitrailConfig {
        val cfg = soleilNoir(base, 0.18, d)
        return if (leads08) cfg.copy(fineLeadJointFactor = 0.8, fineLeadNervureFactor = 0.7) else cfg
    }

    /** V1-BC : corps gris éclairci de pièce en pièce vers les pointes (jusqu'à
     *  +0,22 de clarté près du givre) ; givre enrichi en texture (×1,8 sur les
     *  parties givrées et les pointes). */
    const val P16_V1_GRADIENT = 0.22
    const val P16_V1_FROST_TEXTURE = 1.8

    private fun p16V1(base: VitrailConfig, id: String, gradient: Boolean, frost: Boolean): VitrailConfig {
        var variant = withBlackLeads(pisteA(C2NoirVariants.V1_BC, VA_C2.copy(obsidian = V1_OBSIDIAN_LIFT), id, keepVein = false), id)
        var m = variant.materials as ObsidianFrostMaterials
        if (gradient) m = m.copy(veinGradient = P16_V1_GRADIENT)
        if (frost) m = m.copy(textures = m.textures + mapOf("mid1@petal" to P16_V1_FROST_TEXTURE, "gold@petalTip" to P16_V1_FROST_TEXTURE))
        variant = variant.copy(materials = m)
        return c2NoirConfig(base, variant)
    }

    // ---- phase 16 bis : soleil noir, un seul univers de noirs (24 nov.)
    //
    // Constat de Laetitia : en A1, le fond noir neutre à côté des pétales
    // bleutés fait « deux univers ». Flammes et triangles du calice prennent
    // le verre du fond ; tous les noirs passent dans la même famille bleutée
    // (teinte 255°, chroma ≈ 0,010-0,012 ; pétales 0,028) ; la croix peut être
    // renforcée (plus sombre, fond un peu remonté, sans lumière directionnelle).

    /** Noirs unifiés : fond, fond extérieur, croix, bordure bleutés (teinte des pétales). */
    val SN_UNI = SN2.copy(bField = "#1B2025", bFieldOuter = "#161A1E", bCross = "#080B0F", bBorder = "#0A0E12")
    /** Croix renforcée : croix ≈ 0,19 après relèvement (au lieu de ≈ 0,22),
     *  fond ≈ 0,29 (au lieu de ≈ 0,27). */
    val SN_UNI_CROSS = SN_UNI.copy(bField = "#262A30", bFieldOuter = "#212429", bCross = "#040508")

    // ---- phase 16 ter : une seule famille de noirs, pétales compris (24 nov.)
    //
    // En 16 bis, les pétales gardaient une chroma de 0,027 contre 0,012 pour le
    // fond : ils ressortaient bleutés. De plus, à chroma égale, un verre plus
    // sombre paraît plus saturé. Ici, toutes les pièces sombres ont la même
    // SATURATION (chroma / clarté ≈ 0,042 pour les noirs bleutés, chroma 0,002
    // pour les noirs neutres) : les pétales ne se distinguent du fond que par
    // la clarté. Flammes et triangles du calice : verre du fond.
    //                                  fond       fond ext.  pétale     croix      bordure
    val SN_FAM_BLEU         = SN2.copy(bField = "#1C2025", bFieldOuter = "#161A1E", bPetal = "#0D1013", bCross = "#090B0F", bBorder = "#0B0D11")
    val SN_FAM_BLEU_CROIX   = SN2.copy(bField = "#262A2F", bFieldOuter = "#212429", bPetal = "#0D1013", bCross = "#040508", bBorder = "#0B0D11")
    val SN_FAM_NEUTRE       = SN2.copy(bField = "#1F1F20", bFieldOuter = "#19191A", bPetal = "#0F0F10", bCross = "#0B0B0C", bBorder = "#0D0D0E")
    val SN_FAM_NEUTRE_CROIX = SN2.copy(bField = "#292A2B", bFieldOuter = "#232425", bPetal = "#0F0F10", bCross = "#050506", bBorder = "#0D0D0E")

    /** Phase 16 ter, soleil noir (touche « b ») : quatre rendus. */
    fun phase16cSoleil(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P16c-SN-noirs-bleutes" to p16bSN(base, SN_FAM_BLEU, calyxAsField = true, unlitCross = false),
        "P16c-SN-noirs-bleutes-croix-renforcee" to p16bSN(base, SN_FAM_BLEU_CROIX, calyxAsField = true, unlitCross = true),
        "P16c-SN-noirs-neutres" to p16bSN(base, SN_FAM_NEUTRE, calyxAsField = true, unlitCross = false),
        "P16c-SN-noirs-neutres-croix-renforcee" to p16bSN(base, SN_FAM_NEUTRE_CROIX, calyxAsField = true, unlitCross = true))

    private fun p16bSN(base: VitrailConfig, d: Direction, calyxAsField: Boolean, unlitCross: Boolean): VitrailConfig {
        val cfg = soleilNoir(base, 0.18, d)
        val p = cfg.rosacePalette
        val field = ColorRGBa.fromHex(d.bField)
        var ov = p.overrides + ("flame" to field)
        if (calyxAsField) ov = ov + ("calyx" to field)
        return cfg.copy(rosacePalette = p.copy(overrides = ov, unlitCross = unlitCross,
            name = p.name + " — flammes" + (if (calyxAsField) " et calice" else "") + " du verre du fond"))
    }

    /** Phase 16 bis, soleil noir (touche « s ») : quatre rendus. */
    fun phase16bSoleil(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P16b-SN-1-flammes-calice-fond" to p16bSN(base, SN2, calyxAsField = true, unlitCross = false),
        "P16b-SN-2-noirs-unifies" to p16bSN(base, SN_UNI, calyxAsField = true, unlitCross = false),
        "P16b-SN-3-croix-renforcee" to p16bSN(base, SN_UNI_CROSS, calyxAsField = true, unlitCross = true),
        "P16b-SN-4-croix-renforcee-calice-argent" to p16bSN(base, SN_UNI_CROSS, calyxAsField = false, unlitCross = true))

    // ---- phase 17 : matérialité du verre et lumière traversante (24 nov.)

    /** OG-K2 (un essai, comparé à D1) : lumière dans le verre plus profonde
     *  (diffusion 3,4 → 3,8 : le cœur de chaque pièce sombre s'éclaire, ses
     *  bords se creusent, toujours sans blanc) et texture du fond ADOUCIE
     *  (×0,75), pour que la vie du verre vienne de la lumière et non de
     *  nuages qui évoqueraient la pierre. Lumière commune de D1 conservée. */
    const val P17_OG_TRANSMISSION = 3.8
    const val P17_OG_FIELD_TEXTURE = 0.75

    private fun p17OG(base: VitrailConfig, id: String): VitrailConfig {
        val dir = VA_D.copy(transmission = P17_OG_TRANSMISSION)
        var variant = withBlackLeads(pisteA(C2NoirVariants.OG_K2, dir, id, keepVein = true), id)
        val m = variant.materials as ObsidianFrostMaterials
        variant = variant.copy(materials = m.copy(directionalLight = P16_D1_LIGHT,
            textures = m.textures + mapOf("out0" to P17_OG_FIELD_TEXTURE, "out1@fieldOuter" to P17_OG_FIELD_TEXTURE)))
        return c2NoirConfig(base, variant).copy(transmissionVariation = P16_D1_VARIATION)
    }

    /** V1-BC (un essai, comparé à la combinaison de la phase 16) : texture
     *  interne sur le corps gris des pétales (base, nervure, calice), ×1,4,
     *  moins forte que celle du givre (×1,8). Givre et dégradé inchangés. */
    const val P17_V1_BODY_TEXTURE = 1.4

    private fun p17V1(base: VitrailConfig, id: String): VitrailConfig {
        val cfg = p16V1(base, id, gradient = true, frost = true)
        val v = cfg.c2Noir!!
        val m = v.materials as ObsidianFrostMaterials
        return c2NoirConfig(base, v.copy(materials = m.copy(textures = m.textures + ("mid0@flame" to P17_V1_BODY_TEXTURE))))
    }

    /** Soleil noir : pétales légèrement plus clairs que le fond, dans la même
     *  famille bleutée (clarté ≈ 0,34, saturation ≈ 0,04 comme le fond),
     *  à partir de P16c-SN-noirs-bleutes-croix-renforcee (fond ≈ 0,30, croix
     *  ≈ 0,20 unie, flammes et calice dans le verre du fond). */
    val SN_P17 = SN_FAM_BLEU_CROIX.copy(bPetal = "#33383F")
    /** Version 2 : même équilibre, lumière dans le verre plus poussée
     *  (diffusion 2,7 → 3,3) et légère variation d'une pièce à l'autre (±6 %),
     *  sous la lumière commune existante. */
    const val P17_SN_TRANSMISSION = 3.3
    const val P17_SN_VARIATION = 0.06

    /** Phase 17 (touche « p ») : quatre rendus ; les références (D1, V1
     *  combinée, P16c bleutés croix renforcée) existent déjà. */
    fun phase17(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P17-OG-verre-profond" to p17OG(base, "P17-OG"),
        "P17-V1-corps-texture" to p17V1(base, "P17-V1"),
        "P17-SN-1-petales-clairs" to p16bSN(base, SN_P17, calyxAsField = true, unlitCross = true),
        "P17-SN-2-petales-clairs-lumiere" to p16bSN(base, SN_P17, calyxAsField = true, unlitCross = true)
            .copy(darkGlassTransmission = P17_SN_TRANSMISSION, transmissionVariation = P17_SN_VARIATION))

    // ---- phase 18 : modèle optique du verre (24 nov.)
    //
    // Aucune couleur ne change. La lumière commune par pièce (OG-K2 :
    // directionalLight ; soleil noir : lightAmount de la palette) et la
    // variation indépendante d'une pièce à l'autre (transmissionVariation)
    // sont remplacées par le champ lumineux continu du modèle optique
    // (GlassOptics, Glass.kt) ; la variation d'épaisseur par pièce est
    // testée à part (A/B : lumière seule, puis lumière + épaisseur).

    private fun p18OG(base: VitrailConfig, id: String, o: GlassOptics): VitrailConfig {
        val cfg = p16OG(base, id, diffusion = true, nuances = false)
        val v = cfg.c2Noir!!
        val m = v.materials as ObsidianFrostMaterials
        return c2NoirConfig(base, v.copy(materials = m.copy(directionalLight = 0.0)))
            .copy(transmissionVariation = null, glassOptics = o)
    }

    private fun p18V1(base: VitrailConfig, id: String, o: GlassOptics): VitrailConfig =
        p16V1(base, id, gradient = true, frost = true).copy(glassOptics = o)

    private fun p18SN(base: VitrailConfig, o: GlassOptics): VitrailConfig {
        val cfg = p16bSN(base, SN_P17, calyxAsField = true, unlitCross = true)
        return cfg.copy(rosacePalette = cfg.rosacePalette.copy(lightAmount = 0.0), glassOptics = o)
    }

    val P18_LIGHT = GlassOptics(thickness = false)
    val P18_LIGHT_THICK = GlassOptics(thickness = true)

    /** Phase 18 bis : même modèle, dosage renforcé après les premiers rendus
     *  (l'écart de clarté dans les pièces avait baissé : halo central trop
     *  réduit, épaisseur trop faible). Halo central 80 % (au lieu de 50 %),
     *  épaisseur ×2,5 (fond 25-50 %, pétales 17-35 %, croix 12-25 %), champ
     *  lumineux ±22 % (au lieu de ±16 %). */
    val P18B = GlassOptics(fieldStrength = 0.22, thickness = true, centerGlow = 0.8, thicknessScale = 2.5)

    /** Phase 18 bis (touche « i ») : trois rendus, un par famille. */
    fun phase18b(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P18b-OG-lumiere-epaisseur-renforcees" to p18OG(base, "P18b-OG", P18B),
        "P18b-V1-lumiere-epaisseur-renforcees" to p18V1(base, "P18b-V1", P18B),
        "P18b-SN-lumiere-epaisseur-renforcees" to p18SN(base, P18B))

    /** Phase 18 ter : dosage de la phase 18 bis, mais sur le verre clair
     *  l'épaisseur ne fait qu'assombrir (plus d'écrêtage des blancs dans les
     *  pétales et la croix). Verre sombre inchangé. */
    val P18C = P18B.copy(clearGlassDarkenOnly = true)

    /** Phase 18 ter (touche « u ») : trois rendus, un par famille. */
    fun phase18c(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P18c-OG-verre-clair-sans-ecretage" to p18OG(base, "P18c-OG", P18C),
        "P18c-V1-verre-clair-sans-ecretage" to p18V1(base, "P18c-V1", P18C),
        "P18c-SN-verre-clair-sans-ecretage" to p18SN(base, P18C))

    /** Phase 19 : diffusion de la lumière dans la masse du verre, pièce par
     *  pièce, sans traverser les plombs (GlassDiffusion.kt), sur la base de la
     *  phase 18 ter. Deux dosages : légère (1 % de la largeur, 30 % de la
     *  lumière diffusée) et plus présente (2 %, 50 %). */
    val P19_LEGERE = GlassDiffusion(radius = 0.01, amount = 0.30)
    val P19_PRESENTE = GlassDiffusion(radius = 0.02, amount = 0.50)

    // ---- phase 21 : exploration chromatique, du noir strict au noir nuancé (24 nov.)
    //
    // Brief « C2 noire · phase 18 » de l'autre IA (renommé phase 21 : les
    // phases 18 à 20 ont porté sur le moteur optique). Rendus sur le moteur
    // de la phase 19 (diffusion présente) : A = palette actuelle (témoin),
    // B = noir profond nuancé, C = noir et anthracite assumés. Architecture,
    // plombs, centres, bordures, givre et croix inchangés.
    //
    // OG-K2 et V1-BC : le fond passe de trois noirs bleutés proches (écart de
    // clarté 0,02) à cinq tons, du noir profond bleuté à l'anthracite peu
    // bleuté, répartis par la même fonction lente que les trois tons actuels
    // (grandes zones continues, jamais d'alternance pièce à pièce), et une
    // profondeur selon le rayon (plus clair près de la fleur, plus profond
    // près de la bordure). V1-BC : le corps gris des pétales part d'un
    // graphite plus proche du fond et s'éclaircit plus longuement vers le
    // givre (continuité fond → corps → givre).
    //
    // Soleil noir : la fleur se lit par sa structure et non par un
    // éclaircissement général : fond extérieur plus profond que le fond
    // intérieur (profondeur vers le bord), flammes et triangles du calice
    // en anthracite intermédiaire entre le fond et les pétales, pétales en
    // graphite (B) ou anthracite (C). Croix, bordure et plombs inchangés.
    // Une seule famille de noirs légèrement bleutés (saturation 0,03 à 0,05),
    // pour ne pas recréer les « deux univers » de la phase 16.

    //                                      noir profond bleuté → anthracite
    val P21_OG_B = listOf("#1A1E24", "#1F2227", "#242629", "#282A2C", "#2B2E31")
    val P21_OG_C = listOf("#181C21", "#1E2226", "#282A2D", "#313336", "#393C40")
    const val P21_OG_B_RADIAL = 0.012
    const val P21_OG_C_RADIAL = 0.025
    val P21_V1_B = listOf("#181B20", "#1C2024", "#212326", "#252729", "#282B2E")
    val P21_V1_C = listOf("#16191D", "#1C1F23", "#252729", "#2E3033", "#36393D")
    const val P21_V1_B_RADIAL = 0.012
    const val P21_V1_C_RADIAL = 0.022
    val P21_V1_B_BODY = FacePair("#2C2F33", "#25282B")
    val P21_V1_C_BODY = FacePair("#313437", "#2A2C2F")
    const val P21_V1_B_GRADIENT = 0.25
    const val P21_V1_C_GRADIENT = 0.27

    /** Soleil noir : fond, fond extérieur, pétales, flammes, triangles du calice. */
    data class P21Sun(val field: String, val fieldOuter: String, val petal: String, val flame: String, val calyx: String)
    val P21_SN_B = P21Sun("#23272C", "#1B1F24", "#353A3F", "#2C3136", "#2C3136")
    val P21_SN_C = P21Sun("#1F2328", "#191C20", "#484E54", "#383C42", "#3C4046")

    private fun withObsidian(cfg: VitrailConfig, id: String, f: (ObsidianFrostMaterials) -> ObsidianFrostMaterials): VitrailConfig {
        val v = cfg.c2Noir!!
        return cfg.copy(c2Noir = v.copy(id = id, materials = f(v.materials as ObsidianFrostMaterials)))
    }

    private fun p21Base(cfg: VitrailConfig) = cfg.copy(glassDiffusion = P19_PRESENTE)

    private fun p21OG(base: VitrailConfig, id: String, tones: List<String>?, radial: Double): VitrailConfig {
        val cfg = p21Base(p18OG(base, id, P18C))
        return if (tones == null) cfg else withObsidian(cfg, id) { it.copy(fieldTones = tones, fieldRadial = radial) }
    }

    private fun p21V1(base: VitrailConfig, id: String, tones: List<String>?, radial: Double,
                      body: FacePair?, gradient: Double): VitrailConfig {
        val cfg = p21Base(p18V1(base, id, P18C))
        return if (tones == null) cfg else withObsidian(cfg, id) {
            it.copy(fieldTones = tones, fieldRadial = radial, vein = body ?: it.vein, veinGradient = gradient)
        }
    }

    private fun p21SN(base: VitrailConfig, sun: P21Sun?): VitrailConfig {
        if (sun == null) return p21Base(p18SN(base, P18C))
        val d = SN_P17.copy(bField = sun.field, bFieldOuter = sun.fieldOuter, bPetal = sun.petal)
        val cfg = p16bSN(base, d, calyxAsField = true, unlitCross = true)
        val p = cfg.rosacePalette
        val pal = p.copy(lightAmount = 0.0, overrides = p.overrides + mapOf(
            "flame" to ColorRGBa.fromHex(sun.flame), "calyx" to ColorRGBa.fromHex(sun.calyx)))
        return p21Base(cfg.copy(rosacePalette = pal, glassOptics = P18C))
    }

    // ---- phase 21 bis : mêmes clartés, une seule famille bleu-noir (24 nov.)
    //
    // En phase 21, B et C d'OG et de V1 mélangeaient noirs bleutés et noirs
    // neutres : les pièces du fond étant grandes et peu nombreuses, cela
    // donnait des pièces voisines de teintes différentes (coloriage pièce par
    // pièce, « deux univers »). Ici, mêmes clartés et même profondeur selon
    // le rayon, mais tous les tons dans la famille bleu-noir d'A (saturation
    // relative constante, environ 0,05, teinte 254°). Corps des pétales de
    // V1 : comme en phase 21 (B et C).
    val P21B_OG_B = listOf("#1A1E24", "#1E2328", "#21262D", "#252A31", "#292E36")
    val P21B_OG_C = listOf("#181C21", "#1D2228", "#252B31", "#2D333B", "#353D46")
    val P21B_V1_B = listOf("#171B20", "#1B2025", "#1F2429", "#22272E", "#262B32")
    val P21B_V1_C = listOf("#15191E", "#1B1F25", "#22272E", "#2A3038", "#323942")

    /** Phase 21 bis (touche « l ») : quatre rendus, B et C d'OG et de V1. */
    fun phase21b(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P21b-OG-B-noir-nuance-bleu" to p21OG(base, "P21b-OG-B", P21B_OG_B, P21_OG_B_RADIAL),
        "P21b-OG-C-noir-anthracite-bleu" to p21OG(base, "P21b-OG-C", P21B_OG_C, P21_OG_C_RADIAL),
        "P21b-V1-B-noir-nuance-bleu" to p21V1(base, "P21b-V1-B", P21B_V1_B, P21_V1_B_RADIAL, P21_V1_B_BODY, P21_V1_B_GRADIENT),
        "P21b-V1-C-noir-anthracite-bleu" to p21V1(base, "P21b-V1-C", P21B_V1_C, P21_V1_C_RADIAL, P21_V1_C_BODY, P21_V1_C_GRADIENT))

    // ---- phase 22 : huit pistes d'amélioration (24 nov.)
    //
    // Chaque piste part du témoin A de la phase 21 (moteur de la phase 19,
    // palettes actuelles) et ne change qu'une chose. Elles peuvent ensuite
    // se combiner entre elles et avec les variantes B et C de la phase 21.
    //  1. Hostie opaline : blanc laiteux à peine chaud (clarté 0,925 au lieu
    //     de 0,964), texture rendue (0,8 au lieu de 0,3), un peu de modelé
    //     par l'épaisseur. Toujours aucun mélange vers le blanc.
    //  2. Soudures aux croisements des plombs (RosaceDetails.kt).
    //  3. Soleil noir, version d'impression : croix et bordure relevées
    //     (0,24 et 0,25 au lieu de 0,19 et 0,22) pour ne pas se confondre
    //     avec les plombs sur papier. La croix reste le verre le plus sombre.
    //  4. Soleil noir, croix en verre noir : épaisseur ×2,2 et texture ×1,6,
    //     sans éclaircir sa couleur.
    //  5. Soleil noir, pointes des pétales : argent assourdi, ou anthracite
    //     des pétales (au lieu de l'argent clair).
    //  6. OG-K2, hiérarchie de température : croix plus froide (acier),
    //     givre des pétales neutre, très légèrement laiteux.
    //  7. V1-BC, corps des pétales en graphite translucide : épaisseur ×2,2,
    //     orientée vers le centre (plus sombre à la base, plus lumineux vers
    //     le givre). Couleurs inchangées.
    //  8. Grisaille : nervures peintes sur le givre des pétales (OG, V1).

    const val P22_HOST_OPAL = "#E8E6E1"
    val P22_HOST = mapOf("cen0@host" to PieceTweak(color = P22_HOST_OPAL, textureScale = 0.8, thicknessScale = 2.5))
    val P22_SN_PRINT = mapOf("cross" to PieceTweak(color = "#1C2025"), "deepBig" to PieceTweak(color = "#1F2328"))
    val P22_SN_CROSS_GLASS = mapOf("cross" to PieceTweak(thicknessScale = 2.2, textureScale = 1.6))
    val P22_SN_TIP_SILVER = mapOf("gold@petalTip" to PieceTweak(color = "#8C939A"))
    val P22_SN_TIP_PETAL = mapOf("gold@petalTip" to PieceTweak(color = SN_P17.bPetal))
    val P22_V1_BODY = mapOf("mid0@flame" to PieceTweak(thicknessScale = 2.2, thicknessInward = true, maxLightness = 0.70),
        "mid1@petal" to PieceTweak(thicknessScale = 2.2, thicknessInward = true, maxLightness = 0.70),
        "cen1@calyx" to PieceTweak(thicknessScale = 2.2, thicknessInward = true, maxLightness = 0.70))

    private fun hexOf(c: ColorRGBa) = "#%02X%02X%02X".format((c.r * 255).roundToInt(), (c.g * 255).roundToInt(), (c.b * 255).roundToInt())
    /** Même clarté OKLab, chroma = cl × clarté, à la teinte donnée (degrés). */
    private fun retint(hex: String, cl: Double, hue: Double): String {
        val L = OkLab.toLab(ColorRGBa.fromHex(hex))[0]
        val ch = cl * L
        return hexOf(OkLab.fromLab(L, ch * Math.cos(Math.toRadians(hue)), ch * Math.sin(Math.toRadians(hue))))
    }
    private fun retintPair(f: FacePair, cl: Double, hue: Double) = FacePair(retint(f.light, cl, hue), retint(f.dark, cl, hue))

    private fun p22OGTemperature(base: VitrailConfig): VitrailConfig {
        val cfg = p21OG(base, "P22-OG-6", null, 0.0)
        return withObsidian(cfg, "P22-OG-6") { m ->
            m.copy(crossColors = m.crossColors.map { retint(it, 0.024, 248.0) },
                petalBase = retintPair(m.petalBase, 0.006, 85.0), petalTip = retintPair(m.petalTip, 0.006, 85.0))
        }
    }

    /** Phase 22 (touche « m ») : quatorze rendus. */
    fun phase22(base: VitrailConfig): List<Pair<String, VitrailConfig>> {
        val og = p21OG(base, "P22-OG", null, 0.0)
        val v1 = p21V1(base, "P22-V1", null, 0.0, null, 0.0)
        val sn = p21SN(base, null)
        return listOf(
            "P22-1-OG-hostie-opaline" to og.copy(pieceTweaks = P22_HOST),
            "P22-1-V1-hostie-opaline" to v1.copy(pieceTweaks = P22_HOST),
            "P22-1-SN-hostie-opaline" to sn.copy(pieceTweaks = P22_HOST),
            "P22-2-OG-soudures" to og.copy(leadJoints = LeadJoints()),
            "P22-2-V1-soudures" to v1.copy(leadJoints = LeadJoints()),
            "P22-2-SN-soudures" to sn.copy(leadJoints = LeadJoints()),
            "P22-3-SN-version-impression" to sn.copy(pieceTweaks = P22_SN_PRINT),
            "P22-4-SN-croix-verre-noir" to sn.copy(pieceTweaks = P22_SN_CROSS_GLASS),
            "P22-5-SN-pointes-argent-assourdi" to sn.copy(pieceTweaks = P22_SN_TIP_SILVER),
            "P22-5-SN-pointes-anthracite" to sn.copy(pieceTweaks = P22_SN_TIP_PETAL),
            "P22-6-OG-croix-froide-givre-neutre" to p22OGTemperature(base),
            "P22-7-V1-corps-graphite-translucide" to v1.copy(pieceTweaks = P22_V1_BODY),
            "P22-8-OG-grisaille" to og.copy(petalGrisaille = PetalGrisaille()),
            "P22-8-V1-grisaille" to v1.copy(petalGrisaille = PetalGrisaille()))
    }

    // ---- phase 22 bis : combinaisons par famille (24 nov.)
    //
    // Retenu de la phase 22 et ajusté :
    //  - soudures : plus discrètes (×1,15) et retirées du pourtour ;
    //  - hostie opaline : texture moins nuageuse (0,55 au lieu de 0,8), en
    //    opale neutre ou en ivoire (comparaison côte à côte, OG) ;
    //  - soleil noir : croix en verre noir renforcée (épaisseur ×3, texture
    //    ×2), pointes en argent assourdi, sur la palette C de la phase 21 ;
    //  - V1 : corps en graphite translucide ;
    //  - grisaille revue (OG) : deux nervures par côté, trait qui s'affine
    //    vers la pointe et s'arrête avant elle, légère irrégularité.
    val P22B_JOINTS = LeadJoints(sizeFactor = 1.15, onRim = false)
    const val P22B_HOST_NEUTRAL = "#E5E6E8"
    val P22B_HOST_N = mapOf("cen0@host" to PieceTweak(color = P22B_HOST_NEUTRAL, textureScale = 0.55, thicknessScale = 2.5))
    val P22B_HOST_I = mapOf("cen0@host" to PieceTweak(color = P22_HOST_OPAL, textureScale = 0.55, thicknessScale = 2.5))
    val P22B_SN = mapOf("cross" to PieceTweak(thicknessScale = 3.0, textureScale = 2.0),
        "gold@petalTip" to PieceTweak(color = "#8C939A"))
    val P22B_GRISAILLE = PetalGrisaille(fractions = listOf(0.36, 0.68), width = 0.34, opacity = 0.40,
        taper = true, endT = 0.80, wobble = 0.025)

    /** Phase 22 bis (touche « q ») : cinq rendus. */
    fun phase22b(base: VitrailConfig): List<Pair<String, VitrailConfig>> {
        val og = p21OG(base, "P22b-OG", null, 0.0)
        val v1 = p21V1(base, "P22b-V1", null, 0.0, null, 0.0)
        val snC = p21SN(base, P21_SN_C)
        return listOf(
            "P22b-OG-combinaison-hostie-neutre" to og.copy(leadJoints = P22B_JOINTS, pieceTweaks = P22B_HOST_N),
            "P22b-OG-combinaison-hostie-ivoire" to og.copy(leadJoints = P22B_JOINTS, pieceTweaks = P22B_HOST_I),
            "P22b-V1-combinaison" to v1.copy(leadJoints = P22B_JOINTS, pieceTweaks = P22_V1_BODY + P22B_HOST_N),
            "P22b-SN-C-combinaison" to snC.copy(leadJoints = P22B_JOINTS, pieceTweaks = P22B_SN),
            "P22b-OG-grisaille-pinceau" to og.copy(petalGrisaille = P22B_GRISAILLE))
    }

    // ---- phase 22 ter : hostie remontée, source de lumière (24 nov.)
    //
    // À 0,925, l'hostie opaline n'était plus nettement le point le plus
    // lumineux. Ici clarté 0,95, modelé un peu réduit (épaisseur ×1,8 au lieu
    // de ×2,5), texture 0,55 ; en opale neutre et en ivoire, sur les trois
    // combinaisons de la phase 22 bis (fonds inchangés : A pour OG et V1,
    // palette C pour le soleil noir).
    const val P22C_HOST_NEUTRAL = "#EDEFF0"
    const val P22C_HOST_IVORY = "#F0EEE9"
    private fun p22cHost(hex: String) = mapOf("cen0@host" to PieceTweak(color = hex, textureScale = 0.55, thicknessScale = 1.8))

    /** Phase 22 ter (touche « t ») : six rendus, trois familles × deux hosties. */
    fun phase22c(base: VitrailConfig): List<Pair<String, VitrailConfig>> {
        val og = p21OG(base, "P22c-OG", null, 0.0).copy(leadJoints = P22B_JOINTS)
        val v1 = p21V1(base, "P22c-V1", null, 0.0, null, 0.0).copy(leadJoints = P22B_JOINTS)
        val sn = p21SN(base, P21_SN_C).copy(leadJoints = P22B_JOINTS)
        return listOf("neutre" to P22C_HOST_NEUTRAL, "ivoire" to P22C_HOST_IVORY).flatMap { (name, hex) -> listOf(
            "P22c-OG-hostie-$name" to og.copy(pieceTweaks = p22cHost(hex)),
            "P22c-V1-hostie-$name" to v1.copy(pieceTweaks = P22_V1_BODY + p22cHost(hex)),
            "P22c-SN-C-hostie-$name" to sn.copy(pieceTweaks = P22B_SN + p22cHost(hex))) }
    }

    // ---- phase 23 : OG-K2, givre des pétales et bras de la croix (24 nov.)
    //
    // Base : fond C de la phase 21 bis, soudures, hostie opaline neutre (0,95).
    // Croix : les deux premières parties de chaque bras (cœur et bras) en
    // verre identique : même couleur (celle du bras, avec la lumière de
    // l'hostie qu'il recevait en son milieu), sans dégradé de lumière ni
    // grain ; l'extrémité dans la bordure reste plus sombre, comme avant.
    // Givre des pétales, sans lumière de l'hostie ni grain (frostUniform) :
    //  0  : givre actuel (pour juger la croix seule) ;
    //  1a : base et pointe blanches (couleurs de la base) ;
    //  1b : base et pointe grises (couleurs de la pointe) ;
    //  2  : deux teintes très proches (pointe 0,02 plus sombre que la base,
    //       au lieu d'environ 0,08) ;
    //  1a bis : tout blanc, sans différence entre la face claire et la face
    //       sombre du pétale (de part et d'autre de la nervure).
    private fun p23OG(base: VitrailConfig, id: String, frost: ((ObsidianFrostMaterials) -> ObsidianFrostMaterials)?): VitrailConfig {
        val cfg = p21OG(base, id, P21B_OG_C, P21_OG_C_RADIAL)
            .copy(leadJoints = P22B_JOINTS, pieceTweaks = p22cHost(P22C_HOST_NEUTRAL))
        return withObsidian(cfg, id) { m0 ->
            val arm = hexOf(OkLab.lighten(ColorRGBa.fromHex(m0.crossColors[1]), 0.028))
            val m = m0.copy(crossColors = listOf(arm, arm, m0.crossColors[2]), crossGlow = C2Glow(0.0, 0.6), crossGrain = 0.0)
            frost?.invoke(m.copy(frostUniform = true)) ?: m
        }
    }
    private fun darker(f: FacePair, dL: Double) = FacePair(hexOf(OkLab.lighten(ColorRGBa.fromHex(f.light), -dL)),
        hexOf(OkLab.lighten(ColorRGBa.fromHex(f.dark), -dL)))

    /** Phase 23 (touche « g ») : cinq rendus d'OG-K2. */
    fun phase23(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P23-OG-0-croix-unie-givre-actuel" to p23OG(base, "P23-OG-0", null),
        "P23-OG-1a-givre-blanc" to p23OG(base, "P23-OG-1a") { it.copy(petalTip = it.petalBase) },
        "P23-OG-1b-givre-gris" to p23OG(base, "P23-OG-1b") { it.copy(petalBase = it.petalTip) },
        "P23-OG-2-givre-teintes-proches" to p23OG(base, "P23-OG-2") { it.copy(petalTip = darker(it.petalBase, 0.02)) },
        "P23-OG-1a-bis-givre-blanc-faces-egales" to p23OG(base, "P23-OG-1abis") {
            val w = FacePair(it.petalBase.light, it.petalBase.light); it.copy(petalBase = w, petalTip = w) })

    // ---- phase 23 bis : anneau autour de l'hostie (24 nov.)
    //
    // Base : 1a bis (givre blanc uniforme). Avec la croix unie (0,78), la base
    // des bras et l'anneau qui entoure l'hostie (argent 0,79) se confondaient.
    // On garde la croix et l'on change seulement l'anneau (même teinte froide
    // que l'argent actuel, clarté différente) :
    //  clair  : 0,90 (reste sous l'hostie, 0,95, qui demeure la source) ;
    //  sombre : 0,62 (comme l'extrémité de la croix dans la bordure) ;
    //  noir   : 0,42 (anneau de verre sombre autour de l'hostie).
    private fun withL(hex: String, L: Double): String {
        val lab = OkLab.toLab(ColorRGBa.fromHex(hex)); val k = L / lab[0]
        return hexOf(OkLab.fromLab(L, lab[1] * k, lab[2] * k))
    }
    private fun p23bOG(base: VitrailConfig, id: String, ringL: Double) = p23OG(base, id) {
        val w = FacePair(it.petalBase.light, it.petalBase.light)
        it.copy(petalBase = w, petalTip = w, calyx = withL(it.calyx, ringL))
    }

    /** Phase 23 bis (touche « h ») : trois rendus d'OG-K2. */
    fun phase23b(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P23b-OG-anneau-clair" to p23bOG(base, "P23b-OG-clair", 0.90),
        "P23b-OG-anneau-sombre" to p23bOG(base, "P23b-OG-sombre", 0.62),
        "P23b-OG-anneau-noir" to p23bOG(base, "P23b-OG-noir", 0.42))

    // ---- phase 23 ter : anneau noir dans la famille bleu-noir du fond (24 nov.)
    //
    // L'anneau noir (0,43) de la phase 23 bis était un gris neutre, étranger
    // au fond bleu-noir. Ici, même teinte et même saturation relative que les
    // tons du fond C (environ 0,05 de la clarté, teinte 254°) :
    //  0,43 : même clarté que l'anneau noir de la phase 23 bis ;
    //  0,33 : au niveau des tons clairs du fond (anneau de verre du fond).
    private fun blueBlack(L: Double): String {
        val ch = 0.052 * L; val h = Math.toRadians(254.0)
        return hexOf(OkLab.fromLab(L, ch * Math.cos(h), ch * Math.sin(h)))
    }
    private fun p23cOG(base: VitrailConfig, id: String, ringL: Double) = p23OG(base, id) {
        val w = FacePair(it.petalBase.light, it.petalBase.light)
        it.copy(petalBase = w, petalTip = w, calyx = blueBlack(ringL))
    }

    /** Phase 23 ter (touche « c ») : deux rendus d'OG-K2. */
    fun phase23c(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P23c-OG-anneau-bleu-noir-043" to p23cOG(base, "P23c-OG-043", 0.43),
        "P23c-OG-anneau-bleu-noir-033" to p23cOG(base, "P23c-OG-033", 0.33))

    // ---- phase 24 : versions finales des trois rosaces noires (24 nov.)
    //
    // Tous les choix validés, sans nouveau réglage :
    //  OG : fond C (21 bis), soudures, hostie opaline neutre (0,95), croix
    //       unie, givre blanc uniforme (1a bis), anneau bleu-noir 0,33 ;
    //  V1 : fond C (21 bis), corps graphite translucide, soudures, hostie
    //       opaline neutre ;
    //  SN : palette C, croix de verre, pointes argent atténué, soudures,
    //       hostie opaline neutre (identique à P22c-SN-C-hostie-neutre).
    fun phase24(base: VitrailConfig): List<Pair<String, VitrailConfig>> {
        val og = p23cOG(base, "P24-OG", 0.33)
        val v1 = p21V1(base, "P24-V1", P21B_V1_C, P21_V1_C_RADIAL, P21_V1_C_BODY, P21_V1_C_GRADIENT)
            .copy(leadJoints = P22B_JOINTS, pieceTweaks = P22_V1_BODY + p22cHost(P22C_HOST_NEUTRAL))
        val sn = p21SN(base, P21_SN_C)
            .copy(leadJoints = P22B_JOINTS, pieceTweaks = P22B_SN + p22cHost(P22C_HOST_NEUTRAL))
        return listOf("P24-OG-finale" to og, "P24-V1-finale" to v1, "P24-SN-finale" to sn)
    }

    /** Phase 21 (touche « k ») : neuf rendus, A / B / C pour chaque famille. */
    fun phase21(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P21-OG-A-noir-strict" to p21OG(base, "P21-OG-A", null, 0.0),
        "P21-OG-B-noir-nuance" to p21OG(base, "P21-OG-B", P21_OG_B, P21_OG_B_RADIAL),
        "P21-OG-C-noir-anthracite" to p21OG(base, "P21-OG-C", P21_OG_C, P21_OG_C_RADIAL),
        "P21-V1-A-noir-strict" to p21V1(base, "P21-V1-A", null, 0.0, null, 0.0),
        "P21-V1-B-noir-nuance" to p21V1(base, "P21-V1-B", P21_V1_B, P21_V1_B_RADIAL, P21_V1_B_BODY, P21_V1_B_GRADIENT),
        "P21-V1-C-noir-anthracite" to p21V1(base, "P21-V1-C", P21_V1_C, P21_V1_C_RADIAL, P21_V1_C_BODY, P21_V1_C_GRADIENT),
        "P21-SN-A-noir-strict" to p21SN(base, null),
        "P21-SN-B-noir-nuance" to p21SN(base, P21_SN_B),
        "P21-SN-C-noir-anthracite" to p21SN(base, P21_SN_C))

    /** Phase 19 (touche « j ») : six rendus, deux par famille. */
    fun phase19(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P19-OG-diffusion-legere" to p18OG(base, "P19-OG-1", P18C).copy(glassDiffusion = P19_LEGERE),
        "P19-OG-diffusion-presente" to p18OG(base, "P19-OG-2", P18C).copy(glassDiffusion = P19_PRESENTE),
        "P19-V1-diffusion-legere" to p18V1(base, "P19-V1-1", P18C).copy(glassDiffusion = P19_LEGERE),
        "P19-V1-diffusion-presente" to p18V1(base, "P19-V1-2", P18C).copy(glassDiffusion = P19_PRESENTE),
        "P19-SN-diffusion-legere" to p18SN(base, P18C).copy(glassDiffusion = P19_LEGERE),
        "P19-SN-diffusion-presente" to p18SN(base, P18C).copy(glassDiffusion = P19_PRESENTE))

    /** Phase 18 (touche « o ») : six rendus ; références : D1, V1 combinée
     *  (phase 16), P17-SN-1. */
    fun phase18(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P18-OG-lumiere-continue" to p18OG(base, "P18-OG-l", P18_LIGHT),
        "P18-OG-lumiere-epaisseur" to p18OG(base, "P18-OG-le", P18_LIGHT_THICK),
        "P18-V1-lumiere-continue" to p18V1(base, "P18-V1-l", P18_LIGHT),
        "P18-V1-lumiere-epaisseur" to p18V1(base, "P18-V1-le", P18_LIGHT_THICK),
        "P18-SN-lumiere-continue" to p18SN(base, P18_LIGHT),
        "P18-SN-lumiere-epaisseur" to p18SN(base, P18_LIGHT_THICK))

    /** Phase 16 : dix nouveaux rendus (touche « z ») ; les références D, A1,
     *  C3 et l'essai de plombs 0,8 existent déjà (phase 15). */
    fun phase16(base: VitrailConfig): List<Pair<String, VitrailConfig>> = listOf(
        "P16-OG-D1-diffusion-lumiere-commune" to p16OG(base, "P16-OG-D1", diffusion = true, nuances = false),
        "P16-OG-D2-nuances-fond" to p16OG(base, "P16-OG-D2", diffusion = false, nuances = true),
        "P16-OG-D3-combine" to p16OG(base, "P16-OG-D3", diffusion = true, nuances = true),
        "P16-SN-petales-sombres-bleus" to p16SN(base, SN_DARK_BLUE, leads08 = false),
        "P16-SN-petales-sombres-bleus-plombs08" to p16SN(base, SN_DARK_BLUE, leads08 = true),
        "P16-SN-petales-clairs" to p16SN(base, SN_LIGHT, leads08 = false),
        "P16-SN-petales-clairs-plombs08" to p16SN(base, SN_LIGHT, leads08 = true),
        "P16-V1-corps-degrade" to p16V1(base, "P16-V1-corps", gradient = true, frost = false),
        "P16-V1-givre-texture" to p16V1(base, "P16-V1-givre", gradient = false, frost = true),
        "P16-V1-combine" to p16V1(base, "P16-V1-combine", gradient = true, frost = true))

    /** Les douze rendus de l'étude : trois témoins puis les neuf propositions. */
    fun all(base: VitrailConfig): List<Pair<String, VitrailConfig>> =
        listOf("T-bleue-MARIAL_C2" to blueReference(base),
            "T-OG-K2" to c2NoirConfig(base, C2NoirVariants.OG_K2),
            "T-V1-BC" to c2NoirConfig(base, C2NoirVariants.V1_BC)) +
                OG_K2.map { it.id to c2NoirConfig(base, it) } +
                V1_BC.map { it.id to c2NoirConfig(base, it) } +
                DIRECTIONS.map { "PB-bleue-noire-${it.code}" to pisteB(base, it) }
}

/** Exporte les douze rendus de l'étude du verre noir dans
 *  exports/c2noir_verre/<identifiant>.png (mêmes dimensions et mêmes
 *  conditions pour tous). */
fun exportC2NoirGlassStudy(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_verre").apply { mkdirs() }
    return C2NoirGlassStudy.all(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Test « lecture vitrail » (touche « w ») : six rendus dans
 *  exports/c2noir_vitrail/<identifiant>.png. */
fun exportC2NoirVitrailTest(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_vitrail").apply { mkdirs() }
    return C2NoirGlassStudy.vitrail(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** « Soleil noir », trois dosages (touche « x ») : exports/c2noir_soleil/<identifiant>.png. */
fun exportC2NoirSoleilNoir(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_soleil").apply { mkdirs() }
    return C2NoirGlassStudy.soleilNoirSet(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 15, perfectionnement des trois finalistes (touche « y ») : douze rendus
 *  dans exports/c2noir_finalistes/<identifiant>.png. */
fun exportC2NoirFinalistes(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_finalistes").apply { mkdirs() }
    return C2NoirGlassStudy.phase15(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 16 (touche « z ») : dix rendus dans exports/c2noir_phase16/<identifiant>.png. */
fun exportC2NoirPhase16(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase16").apply { mkdirs() }
    return C2NoirGlassStudy.phase16(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 16 bis, soleil noir (touche « s ») : quatre rendus dans exports/c2noir_phase16/<identifiant>.png. */
fun exportC2NoirPhase16bSoleil(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase16").apply { mkdirs() }
    return C2NoirGlassStudy.phase16bSoleil(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 16 ter, soleil noir (touche « b ») : quatre rendus dans exports/c2noir_phase16/<identifiant>.png. */
fun exportC2NoirPhase16cSoleil(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase16").apply { mkdirs() }
    return C2NoirGlassStudy.phase16cSoleil(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 17 (touche « p ») : quatre rendus dans exports/c2noir_phase17/<identifiant>.png. */
fun exportC2NoirPhase17(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase17").apply { mkdirs() }
    return C2NoirGlassStudy.phase17(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 18 (touche « o ») : six rendus dans exports/c2noir_phase18/<identifiant>.png. */
fun exportC2NoirPhase18(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase18").apply { mkdirs() }
    return C2NoirGlassStudy.phase18(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 18 bis (touche « i ») : trois rendus dans exports/c2noir_phase18/<identifiant>.png. */
fun exportC2NoirPhase18b(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase18").apply { mkdirs() }
    return C2NoirGlassStudy.phase18b(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 18 ter (touche « u ») : trois rendus dans exports/c2noir_phase18/<identifiant>.png. */
fun exportC2NoirPhase18c(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase18").apply { mkdirs() }
    return C2NoirGlassStudy.phase18c(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 19 (touche « j ») : six rendus dans exports/c2noir_phase19/<identifiant>.png. */
fun exportC2NoirPhase19(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase19").apply { mkdirs() }
    return C2NoirGlassStudy.phase19(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 21 (touche « k ») : neuf rendus dans exports/c2noir_phase21/<identifiant>.png. */
fun exportC2NoirPhase21(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase21").apply { mkdirs() }
    return C2NoirGlassStudy.phase21(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 21 bis (touche « l ») : quatre rendus dans exports/c2noir_phase21/<identifiant>.png. */
fun exportC2NoirPhase21b(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase21").apply { mkdirs() }
    return C2NoirGlassStudy.phase21b(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 22 (touche « m ») : quatorze rendus dans exports/c2noir_phase22/<identifiant>.png. */
fun exportC2NoirPhase22(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase22").apply { mkdirs() }
    return C2NoirGlassStudy.phase22(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 22 bis (touche « q ») : cinq rendus dans exports/c2noir_phase22/<identifiant>.png. */
fun exportC2NoirPhase22b(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase22").apply { mkdirs() }
    return C2NoirGlassStudy.phase22b(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 22 ter (touche « t ») : six rendus dans exports/c2noir_phase22/<identifiant>.png. */
fun exportC2NoirPhase22c(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase22").apply { mkdirs() }
    return C2NoirGlassStudy.phase22c(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 23 (touche « g ») : cinq rendus dans exports/c2noir_phase23/<identifiant>.png. */
fun exportC2NoirPhase23(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase23").apply { mkdirs() }
    return C2NoirGlassStudy.phase23(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 23 bis (touche « h ») : trois rendus dans exports/c2noir_phase23/<identifiant>.png. */
fun exportC2NoirPhase23b(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase23").apply { mkdirs() }
    return C2NoirGlassStudy.phase23b(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 23 ter (touche « c ») : deux rendus dans exports/c2noir_phase23/<identifiant>.png. */
fun exportC2NoirPhase23c(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_phase23").apply { mkdirs() }
    return C2NoirGlassStudy.phase23c(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}

/** Phase 24 (touche « f ») : trois versions finales dans exports/c2noir_finales/<identifiant>.png. */
fun exportC2NoirPhase24(drawer: Drawer, base: VitrailConfig, referenceWidth: Double): List<File> {
    val dir = File("exports/c2noir_finales").apply { mkdirs() }
    return C2NoirGlassStudy.phase24(base).map { (id, cfg) ->
        val px = cfg.printFormat?.widthPx ?: 3543
        val py = cfg.printFormat?.heightPx ?: 3543
        exportHighResolution(drawer, cfg, px, py, referenceWidth, outputFile = File(dir, "$id.png"))
    }
}