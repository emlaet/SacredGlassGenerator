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
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// ROSACE RAYONNANTE (remplage gothique en pierre + vitrail au plomb)
// --------------------------------------------------
//
// Une nouvelle famille de composition, avec sa propre logique (règle 7) :
// la composition n'est PAS tirée au hasard, elle est construite comme un
// maître verrier et un tailleur de pierre l'auraient tracée au compas.
//
// Paramètre principal : rotations = nombre de secteurs identiques (3 à 48).
// Toute la géométrie en découle :
// - anneau intérieur : une grande lancette par secteur, subdivisée
//   récursivement (remplage en Y : deux sous-lancettes surmontées d'un
//   oculus à trèfle, puis de nouveau deux dans chacune tant qu'elles sont
//   assez larges) ;
// - anneau extérieur : lancettes d'environ 15° coiffées d'un trèfle, avec
//   un quadrilobe entre chaque tête ;
// - centre : oculus polylobé (à partir d'environ 16 rotations, entouré
//   d'une petite couronne de lancettes).
//
// Pierre : les barres du remplage sont des lignes épaissies (trois
// largeurs : principales, secondaires, fines) puis réunies ; les vitraux
// sont les ouvertures laissées par la pierre. Chaque ouverture est ensuite
// découpée en pièces de verre (filet de bordure + réseau de losanges pour
// les lancettes).
//
// SYMÉTRIE STRICTE, PAR CONSTRUCTION : seule une demi-secteur est découpée ;
// chaque pièce est ensuite recopiée par les 2 × rotations transformations du
// groupe diédral (rotations + miroir). Les pièces homologues ont donc
// exactement les mêmes plombs ; leurs couleurs ne dépendent que de leur
// type, de leur position et de la parité du secteur (alternance de deux
// couleurs d'un secteur à l'autre si rotations est pair).
//
// Aucun tirage aléatoire : la rosace ne dépend que de ses réglages (le seed
// ne change que la matière du verre). Prototype validé en Python
// (claude/prototype_rosace_rayonnante.py, documents du projet), porté à
// l'identique avec JTS (Java Topology Suite, dépendance Gradle jts-core).
//
// Unités : la rosace est construite dans un cercle de rayon 1 (bord
// extérieur de la pierre), puis placée dans le canevas.

// ================================================================ pièces

/** Une pièce de verre de la rosace, en unités de la rosace (rayon 1). */
class RosacePiece(
    val polygon: Polygon,
    /** Type d'ouverture (sub_lancet, lancet2, arch_spandrel, head_core1…). */
    val kind: String,
    /** Rôle de la pièce dans l'ouverture (field0/1, fillet0/1, plain, core…). */
    val role: String,
    /** Angle de l'axe de l'ouverture (pour l'alternance des secteurs). */
    val angle: Double
)

/** Un plomb d'une épaisseur particulière (hiérarchie des plombs), en pixels du cadre. */
class RosaceLeadLine(
    val points: List<Vector2>,
    val closed: Boolean,
    /** Épaisseur en multiple de LeadStyle.width (la largeur de la recette). */
    val factor: Double
)

/** La rosace découpée, en coordonnées du canevas. */
class RosaceGuide(
    /** Les pièces de verre, en pixels du cadre (une cellule par pièce). */
    val pieces: List<List<Vector2>>,
    /** La géométrie d'origine (en unités) : sert au calcul des couleurs ;
     *  null pour les dessins B et C (RosaceStyles.kt), dont chaque pièce
     *  porte directement son rôle de couleur (RosacePiece.role). */
    val geometry: RosaceGeometry?,
    /** Les mêmes pièces en unités, dans le même ordre que pieces. */
    val unitPieces: List<RosacePiece>,
    /** Centre de la rosace, en pixels du cadre. */
    val center: Vector2,
    /** Rayon extérieur de la pierre, en pixels du cadre. */
    val radius: Double,
    /** Remplage en pierre sous le verre (false : dessins C, tout en verre). */
    val hasStone: Boolean = true,
    /** Épaisseur des plombs entre les pièces, en multiple de LeadStyle.width
     *  (dessins B et C : 0,6, les plombs fins ; les plombs plus épais sont
     *  dans leadLines). */
    val cellLeadFactor: Double = 1.0,
    /** Plombs plus épais (structure, secondaires), tracés par-dessus les
     *  plombs fins ; vide pour la rosace rayonnante et le dessin A. */
    val leadLines: List<RosaceLeadLine> = emptyList()
) : CompositionGuide()

/**
 * Rosace rayonnante.
 * - rotations : nombre de secteurs identiques, de 3 à 48 (12 par défaut).
 *   Pair : les trèfles alternent de couleur d'un secteur à l'autre.
 * - sizeRatio : diamètre de la rosace en part du petit côté du cadre.
 * - orientationDegrees : direction de l'axe du premier secteur dans le
 *   canevas (−90 = vers le haut : la rosace a toujours un axe vertical).
 */
data class RosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0
) : CompositionSystem {

    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide {
        return placeRosace(RosaceGeometry.of(rotations), width, height, sizeRatio, orientationDegrees)
    }
}

/**
 * Rosace A — MÉDIÉVALE SOBRE (dessin retenu le 1er nov. 2026). Le même
 * remplage que la rosace rayonnante, réduit à son architecture :
 * - plus de réseau de losanges ni de filets de bordure : les lancettes sont
 *   coupées en grands panneaux par des traverses ;
 * - plus de quadrilobes au bord : les meneaux de la couronne vont jusqu'au
 *   cercle extérieur ;
 * - oculus central agrandi (× 1,45), découpé en un cœur rond et une pièce
 *   par lobe.
 * Environ 300 pièces pour 12 rotations (contre près de 2 600). Couleurs :
 * une RosacePalette (VitrailConfig.rosacePalette), pas un RosaceScheme.
 * Mêmes réglages que RosaceCompositionSystem ; prototype : design_A2 de
 * claude/prototype_rosace_trois_styles.py (documents du projet).
 */
data class MedievalRosaceCompositionSystem(
    val rotations: Int = 12,
    val sizeRatio: Double = 0.96,
    val orientationDegrees: Double = -90.0,
    /** Diamètre du médaillon central (polylobe et cœur), en multiple de celui
     *  du prototype validé : 1,10 = agrandi de 10 % (brief du 2 nov. 2026). */
    val medallionScale: Double = 1.10,
    /** Hauteur des panneaux des lancettes de l'étoile (traverses), en unités
     *  de la rosace : 0,09 dans le prototype (3 panneaux par lancette) ;
     *  0,15 = 2 panneaux, une étoile plus aérée (brief du 2 nov. 2026). */
    val starPanelHeight: Double = 0.15
) : CompositionSystem {

    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide {
        val geometry = RosaceGeometry.of(rotations, sober = true, medallionScale = medallionScale, starPanelHeight = starPanelHeight)
        return placeRosace(geometry, width, height, sizeRatio, orientationDegrees)
    }
}

/**
 * Passage des unités de la rosace (rayon 1, axe du premier secteur sur
 * l'angle 0) aux pixels du cadre : rosace centrée, de diamètre sizeRatio ×
 * le petit côté, premier axe orienté selon orientationDegrees.
 */
class RosacePlacement(width: Double, height: Double, sizeRatio: Double, orientationDegrees: Double) {
    val center = Vector2(width / 2.0, height / 2.0)
    val radius = 0.5 * min(width, height) * sizeRatio
    private val co = cos(orientationDegrees * PI / 180.0)
    private val so = sin(orientationDegrees * PI / 180.0)

    fun map(u: Double, v: Double) = Vector2(center.x + radius * (u * co - v * so), center.y + radius * (u * so + v * co))

    /** Contour d'un polygone (anneau fermé : le dernier point, qui répète le premier, est omis). */
    fun cell(p: Polygon): List<Vector2> {
        val coords = p.exteriorRing.coordinates
        return (0 until coords.size - 1).map { k -> map(coords[k].x, coords[k].y) }
    }
}

/** Place une géométrie de rosace (unités, rayon 1) au centre du cadre. */
private fun placeRosace(
    geometry: RosaceGeometry, width: Double, height: Double, sizeRatio: Double, orientationDegrees: Double
): RosaceGuide {
    val pl = RosacePlacement(width, height, sizeRatio, orientationDegrees)
    val pieces = geometry.pieces.map { pl.cell(it.polygon) }
    return RosaceGuide(pieces, geometry, geometry.pieces, pl.center, pl.radius)
}

/** Les pièces de la rosace, telles que construites (une par cellule). */
class RosaceSegmentationSystem : SegmentationSystem {
    override fun segment(guide: CompositionGuide, width: Double, height: Double, random: Random): List<List<Vector2>> {
        require(guide is RosaceGuide) { "RosaceSegmentationSystem nécessite un RosaceGuide (une des rosaces de Rosace.kt ou RosaceStyles.kt)" }
        return guide.pieces
    }
}

// ================================================================ couleurs

/**
 * Jeu de couleurs d'une rosace. Les paires (a, b) alternent : d'un secteur
 * à l'autre (champ des lancettes, trèfles), ou d'une pièce à l'autre (filet
 * de bordure). Les teintes sont des valeurs de départ, à ajuster à l'œil
 * après le rendu du verre.
 */
data class RosaceScheme(
    val name: String,
    /** Champ des lancettes de l'anneau extérieur (losanges alternés). */
    val outerField: Pair<ColorRGBa, ColorRGBa>,
    /** Champ des sous-lancettes de l'anneau intérieur. */
    val innerField: Pair<ColorRGBa, ColorRGBa>,
    /** Filet de bordure des lancettes (petites pièces alternées). */
    val fillet: Pair<ColorRGBa, ColorRGBa>,
    /** Cœur de l'oculus central. */
    val core: ColorRGBa,
    /** Couronne intérieure de l'oculus central (et écoinçons de sa couronne de lancettes). */
    val oculusInner: ColorRGBa,
    /** Lobes de l'oculus central (et lancettes de sa couronne). */
    val oculusOuter: ColorRGBa,
    /** Fond entre les grandes lancettes de l'anneau intérieur. */
    val innerSpandrel: ColorRGBa,
    /** Petits écoinçons près du bord. */
    val outerSpandrel: ColorRGBa,
    /** Trèfles et quadrilobes : couleurs alternées. */
    val foil: Pair<ColorRGBa, ColorRGBa>,
    /** Lunules autour des trèfles et quadrilobes. */
    val lune: ColorRGBa,
    /** Pétale autour de l'oculus de tête (une seule couleur). */
    val petal: ColorRGBa,
    /** Autres pièces (par sécurité). */
    val plain: ColorRGBa,
    /** Pierre du remplage. */
    val stone: ColorRGBa = ColorRGBa.fromHex("#C9C1B2"),
    /** Losanges des pétales et des écoinçons en deux nuances (claire et
     *  foncée) ; false pour un verre uniforme (pierre et plomb seuls). */
    val shadedFields: Boolean = true,
    /** Couleur des plombs propre à cette palette (null : celle de la
     *  recette, noir par défaut). Utilisée par rosaceRecipe, TemplateProgram.kt :
     *  color = rosaceScheme.lead ?: config.leadStyle.color. */
    val lead: ColorRGBa? = null
)

object RosaceSchemes {
    private fun c(hex: String) = ColorRGBa.fromHex(hex)

    /** Dominante bleu et rouge, inspirée des grandes rosaces gothiques
     *  (interprétation libre, pas un relevé de couleurs). */
    val CATHEDRALE = RosaceScheme(
        name = "Cathédrale",
        outerField = c("#1E3F8F") to c("#24499C"),
        innerField = c("#A3162A") to c("#8E1424"),
        fillet = c("#E9E2C8") to c("#A3162A"),
        core = c("#E3B23C"),
        oculusInner = c("#E3B23C"),
        oculusOuter = c("#A3162A"),
        innerSpandrel = c("#1A2F6B"),
        outerSpandrel = c("#A3162A"),
        foil = c("#E3B23C") to c("#E9E2C8"),
        lune = c("#1E3F8F"),
        petal = c("#2E5AAE"),
        plain = c("#1A2F6B")
    )

    /** Temps ordinaire : VERT dominant (anneau extérieur, pétales, fonds), en
     *  dialogue avec un BLEU (étoile de l'anneau intérieur, petits écoinçons),
     *  comme le bleu et le rouge de CATHEDRALE ; ors en accents. Verts de
     *  LiturgicalPalettes.ORDINAIRE ; bleus de CATHEDRALE (#2E5AAE, #1E3F8F) et
     *  #284F99 (variante plus sombre). Première version (oct. 2026), jugée trop
     *  monochrome : anneau intérieur vert clair #7FB069 / #72A25D. */
    val ORDINAIRE = RosaceScheme(
        name = "Ordinaire",
        outerField = c("#3C7A4E") to c("#356D45"),
        innerField = c("#2E5AAE") to c("#284F99"),
        fillet = c("#C9DDB0") to c("#1E3F8F"),
        core = c("#D9A441"),
        oculusInner = c("#D9A441"),
        oculusOuter = c("#1E3F8F"),
        innerSpandrel = c("#1F4D33"),
        outerSpandrel = c("#1E3F8F"),
        foil = c("#D9A441") to c("#E4EED6"),
        lune = c("#1F4D33"),
        petal = c("#4E8F5E"),
        plain = c("#1F4D33")
    )

    /** Avent, Carême : VIOLET dominant, en dialogue avec un AMBRE (étoile de
     *  l'anneau intérieur, petits écoinçons) — la couleur complémentaire du
     *  violet, la lumière attendue. Violets de LiturgicalPalettes.AVENT ;
     *  nouvelles teintes #C8902E / #B5802A (ambre) et #7A4C8C (pétales).
     *  Première version (oct. 2026), jugée terne : anneau intérieur lilas
     *  #9B7BB8 / #8A6AA8, fonds gris cendre #9B9490. */
    val AVENT = RosaceScheme(
        name = "Avent",
        outerField = c("#5B3168") to c("#4C295A"),
        innerField = c("#C8902E") to c("#B5802A"),
        fillet = c("#D9CBE0") to c("#5B3168"),
        core = c("#D9A441"),
        oculusInner = c("#EDE4F2"),
        oculusOuter = c("#3D2645"),
        innerSpandrel = c("#3D2645"),
        outerSpandrel = c("#B5802A"),
        foil = c("#D9A441") to c("#EDE4F2"),
        lune = c("#3D2645"),
        petal = c("#7A4C8C"),
        plain = c("#3D2645")
    )

    /** Rouge (Passion, Pentecôte, martyrs) : ROUGE RUBIS dominant (ni orangé
     *  ni rose), en dialogue avec un BLEU CANARD (étoile de l'anneau
     *  intérieur, petits écoinçons, comme le fond RayField de la saison) ;
     *  ors en accents. Grenat #5C1220 de LiturgicalPalettes.ROUGE ; nouvelles
     *  teintes #A61B22 / #911820 (rubis), #B8262E (pétales), #1E5D66 / #19505A
     *  (bleu canard), #EDE3C8 (crème). Première version (oct. 2026), jugée
     *  trop orangée au centre puis mauve et fuchsia : écarlate #A6242E,
     *  vermillon #D6491F. */
    val ROUGE = RosaceScheme(
        name = "Rouge",
        outerField = c("#A61B22") to c("#911820"),
        innerField = c("#1E5D66") to c("#19505A"),
        fillet = c("#EDE3C8") to c("#1E5D66"),
        core = c("#D9A441"),
        oculusInner = c("#D9A441"),
        oculusOuter = c("#1E5D66"),
        innerSpandrel = c("#5C1220"),
        outerSpandrel = c("#1E5D66"),
        foil = c("#D9A441") to c("#EDE3C8"),
        lune = c("#5C1220"),
        petal = c("#B8262E"),
        plain = c("#5C1220")
    )

    /** Blanc lumineux (Noël, Pâques) : teintes de LiturgicalPalettes.NOEL,
     *  sans aucune nouvelle teinte. Comme pour les autres familles, les ocres
     *  restent près du centre et les blancs vont vers l'extérieur ; l'ocre
     *  foncé (#906C28) ne sert qu'aux petites lunules, pour donner un peu de
     *  profondeur. */
    val NOEL = RosaceScheme(
        name = "Noël (blanc lumineux)",
        outerField = c("#FBF8F1") to c("#F4F1EA"),
        innerField = c("#EFD49A") to c("#F4E3BD"),
        fillet = c("#ECE0C6") to c("#D9A441"),
        core = c("#D9A441"),
        oculusInner = c("#EFD49A"),
        oculusOuter = c("#D9A441"),
        innerSpandrel = c("#E9EEF1"),
        outerSpandrel = c("#EFD49A"),
        foil = c("#D9A441") to c("#FBF8F1"),
        lune = c("#906C28"),
        petal = c("#ECE0C6"),
        plain = c("#F4F1EA")
    )

    /** Rose (Gaudete, Laetare), pensée pour un intérieur contemporain : ROSE
     *  POUDRÉ dominant, en dialogue avec un BLEU NUIT (étoile de l'anneau
     *  intérieur, petits écoinçons, lunules) ; ocre et blush en accents.
     *  Toutes les teintes sont nouvelles. Versions précédentes, jugées « rose bonbon » puis « vieillottes » :
     *  #A84D6B / #D47A93, puis vieux rose #8C4F6C et vert sauge #4F7F6E.
     *  À REMPLACER plus tard par une palette tirée des palettes retenues pour
     *  Gaudete et Laetare (RosacePalettes, plus bas). Les quatorze essais roses
     *  du 28 oct. 2026 (ROSE_RICHE_1 à 4, ROSE_JOIE…, ROSE_SAUGE, ROSE_PETROLE,
     *  ROSE_ARDOISE, ROSE_OLIVE, ROSE_LAITON) ont été retirés le 1er nov. 2026 :
     *  aucun n'a été retenu (historique dans « Récapitulatif rosaces
     *  2026-11-01 », documents du projet). */
    val ROSE = RosaceScheme(
        name = "Rose",
        outerField = c("#C27A92") to c("#B06E85"),
        innerField = c("#26345E") to c("#1F2B4E"),
        fillet = c("#F2D7DE") to c("#26345E"),
        core = c("#D9A441"),
        oculusInner = c("#F2D7DE"),
        oculusOuter = c("#26345E"),
        innerSpandrel = c("#7A3550"),
        outerSpandrel = c("#26345E"),
        foil = c("#D9A441") to c("#F7E6EA"),
        lune = c("#26345E"),
        petal = c("#D898AC"),
        plain = c("#7A3550")
    )

    // VERT, CRAMOISI ET AMBRE (28 oct. 2026) — palette HORS SAISON LITURGIQUE,
    // gardée comme CATHEDRALE pour d'autres motifs ; d'après une image de référence
    // envoyée par Astrea (vitrail en arc : fleur cramoisie et prune sur fond
    // vert olive sombre, traits ambre) : teintes relevées sur l'image puis
    // arrondies. La pierre ambre (#D99A3E) joue le rôle des traits dorés de
    // l'image ; les plombs restent noirs (des plombs ambre sur plus de 2 000
    // pièces couvraient tout le vitrail d'or). Fond conseillé autour : presque
    // noir (#15140E). L'ambre est une couleur imprimée comme les autres (un
    // ocre), pas une encre métallisée.

    /** Vert olive sombre dans l'anneau extérieur, étoile cramoisie au
     *  centre (comme la fleur sur le fond vert de l'image), pétales prune
     *  (#6E3B52), trèfles et bordures ambre ; pierre ambre. */
    val VERT_CRAMOISI = RosaceScheme(
        name = "Vert et cramoisi",
        outerField = c("#3C4029") to c("#33361F"),
        innerField = c("#901F3D") to c("#7E1531"),
        fillet = c("#E49931") to c("#5A1121"),
        core = c("#EAA639"),
        oculusInner = c("#901F3D"),
        oculusOuter = c("#262418"),
        innerSpandrel = c("#262418"),
        outerSpandrel = c("#5A1121"),
        foil = c("#EAA639") to c("#901F3D"),
        lune = c("#262418"),
        petal = c("#6E3B52"),
        plain = c("#262418"),
        stone = c("#D99A3E")
    )

    /** L'inverse : anneau extérieur cramoisi, étoile vert olive ;
     *  pierre ambre. */
    val CRAMOISI_VERT = RosaceScheme(
        name = "Cramoisi et vert",
        outerField = c("#901F3D") to c("#7E1531"),
        innerField = c("#3C4029") to c("#33361F"),
        fillet = c("#E49931") to c("#262418"),
        core = c("#EAA639"),
        oculusInner = c("#3C4029"),
        oculusOuter = c("#5A1121"),
        innerSpandrel = c("#5A1121"),
        outerSpandrel = c("#262418"),
        foil = c("#EAA639") to c("#6E3B52"),
        lune = c("#5A1121"),
        petal = c("#6E3B52"),
        plain = c("#5A1121"),
        stone = c("#D99A3E")
    )

    /** Noir (défunts) : ANTHRACITE dominant, en dialogue avec un GRIS PERLE
     *  clair (étoile de l'anneau intérieur) — le contraste du clair et du
     *  sombre, comme la grisaille ; un écho violet dans les pétales (#3E3248)
     *  et l'oculus (#251A2C). Teintes de LiturgicalPalettes.NOIRE ; nouvelles
     *  teintes #262320, #B4B2AE, #E8E6E1, #3E3248. Première version (oct.
     *  2026), jugée « de la même couleur partout » : anneau intérieur ardoise
     *  #57534C. */
    val NOIRE = RosaceScheme(
        name = "Noire",
        outerField = c("#2E2B28") to c("#262320"),
        innerField = c("#B4B2AE") to c("#A6A6A8"),
        fillet = c("#DCDAD5") to c("#14100E"),
        core = c("#E8E6E1"),
        oculusInner = c("#A6A6A8"),
        oculusOuter = c("#251A2C"),
        innerSpandrel = c("#14100E"),
        outerSpandrel = c("#57534C"),
        foil = c("#E8E6E1") to c("#A6A6A8"),
        lune = c("#14100E"),
        petal = c("#3E3248"),
        plain = c("#14100E")
    )

    /** Bleu marial (dévotionnel) : BLEU dominant, en dialogue avec un IVOIRE
     *  (étoile de l'anneau intérieur) et l'argent des petits écoinçons ; ors
     *  en accents. Teintes de LiturgicalPalettes.MARIAL ; nouvelles teintes
     *  #285188, #5682BA, #EDE6D3 / #E2D9C2 (ivoire) ; #F3F4F2 vient des fonds
     *  RayField. Première version (oct. 2026), jugée monochrome : anneau
     *  intérieur bleu ciel #7FA8D9. */
    val MARIAL = RosaceScheme(
        name = "Marial",
        outerField = c("#2E5C9A") to c("#285188"),
        innerField = c("#EDE6D3") to c("#E2D9C2"),
        fillet = c("#F3F4F2") to c("#D9A441"),
        core = c("#D9A441"),
        oculusInner = c("#D9A441"),
        oculusOuter = c("#1D3461"),
        innerSpandrel = c("#1D3461"),
        outerSpandrel = c("#A9B4BD"),
        foil = c("#D9A441") to c("#F3F4F2"),
        lune = c("#1D3461"),
        petal = c("#5682BA"),
        plain = c("#1D3461")
    )

    /** Pierre et plombs seuls : tout le verre en blanc cassé. */
    val PIERRE_ET_PLOMB = run {
        val w = c("#F7F4EC")
        RosaceScheme(
            name = "Pierre et plomb",
            outerField = w to w, innerField = w to w, fillet = w to w,
            core = w, oculusInner = w, oculusOuter = w, innerSpandrel = w, outerSpandrel = w,
            foil = w to w, lune = w, petal = w, plain = w,
            shadedFields = false
        )
    }

    /** Pierre claire (blanc chaud grisé), plus douce avec les palettes claires ;
     *  la pierre par défaut est #C9C1B2. S'emploie avec copy(stone = …). */
    val PIERRE_CLAIRE: ColorRGBa = c("#E4DED4")

    /** Pierre par défaut (greige), pour revenir à la pierre habituelle. */
    val PIERRE_GREIGE: ColorRGBa = c("#C9C1B2")

    /** Pierre ambre (celle de VERT_CRAMOISI et CRAMOISI_VERT). */
    val PIERRE_AMBRE: ColorRGBa = c("#D99A3E")

    /** Pierre crème, plus chaude que la pierre par défaut (#C9C1B2, un greige). */
    val PIERRE_CREME: ColorRGBa = c("#E6DCCB")

    /** Pierre blanc chaud, la plus claire. */
    val PIERRE_BLANC_CHAUD: ColorRGBa = c("#F0E9DF")

    /** Les sept palettes liturgiques, dans l'ordre de LiturgicalPalettes.ALL. */
    val LITURGICAL = listOf(ORDINAIRE, NOEL, AVENT, ROUGE, NOIRE, ROSE, MARIAL)

    /** Le jeu de couleurs de rosace correspondant à une palette liturgique
     *  (par exemple RosaceSchemes.forLiturgical(liturgicalPalette), pour que
     *  la rosace suive la saison choisie en haut de TemplateProgram.kt). */
    fun forLiturgical(palette: LiturgicalPalette): RosaceScheme = when (palette) {
        LiturgicalPalettes.ORDINAIRE -> ORDINAIRE
        LiturgicalPalettes.NOEL -> NOEL
        LiturgicalPalettes.AVENT -> AVENT
        LiturgicalPalettes.ROUGE -> ROUGE
        LiturgicalPalettes.NOIRE -> NOIRE
        LiturgicalPalettes.ROSE -> ROSE
        LiturgicalPalettes.MARIAL -> MARIAL
        else -> CATHEDRALE
    }
}

// ================================================================ palettes par rôles

/**
 * Palette PAR RÔLES, commune aux nouveaux dessins de rosace (A médiéval
 * sobre ; à venir : B curviligne, C « dalle », C « arcades »). Chaque
 * dessin répartit lui-même ces rôles sur ses pièces : une même palette
 * s'applique ainsi aux quatre dessins. (La rosace rayonnante d'origine
 * garde ses RosaceScheme.)
 *
 * Retenues le 1er nov. 2026 ; leçon des essais : le rose fonctionne quand
 * il PERCE une couleur profonde, avec un centre lumineux (pas de sauge, pas
 * de grisé, pas de fuchsia).
 */
data class RosacePalette(
    val name: String,
    /** Couronne extérieure (deux teintes). */
    val outer: Pair<ColorRGBa, ColorRGBa>,
    /** Étoile, anneau intermédiaire (deux teintes). */
    val middle: Pair<ColorRGBa, ColorRGBa>,
    /** Centre (deux teintes ; la première est l'ivoire du cœur). */
    val centre: Pair<ColorRGBa, ColorRGBa>,
    /** Fonds sombres. */
    val deep: ColorRGBa,
    /** Grandes surfaces sombres, éclaircies d'un cran par rapport à deep. */
    val deepBig: ColorRGBa,
    /** Accent. */
    val accent: ColorRGBa,
    /** Or (trèfles ; points de la couronne du dessin B). */
    val gold: ColorRGBa,
    /** Croix des axes (dessins C seulement : pas de croix dans A et B). */
    val cross: ColorRGBa,
    /** Rose très pâle : anneau autour des centres ivoire (A, B). */
    val veryPale: ColorRGBa = ColorRGBa.fromHex("#F3DFE4"),
    /** Pierre du remplage (A, B). */
    val stone: ColorRGBa = ColorRGBa.fromHex("#D8CDB8"),
    /** Plombs (charbon plutôt que noir) : plombs de structure, contour extérieur. */
    val lead: ColorRGBa = ColorRGBa.fromHex("#343436"),
    /** Plombs fins (« contour décoratif ») des dessins B et C, qui ont une
     *  hiérarchie de plombs ; null = même couleur que lead. Sans effet sur le
     *  dessin A (une seule épaisseur de plomb) ni sur la rosace rayonnante. */
    val leadFine: ColorRGBa? = null,
    /** Couleurs imposées à certaines pièces, repérées par une étiquette.
     *  Vide = couleurs des rôles seuls (toutes les palettes antérieures).
     *  Étiquettes disponibles :
     *  - dessin A : "spandrel2" (écoinçons du bord, alternés sinon accent / deepBig) ;
     *  - couronne des dessins B : "ogeeA", "ogeeB" (pièces en accolade),
     *    "middleA", "middleB" (pièces du milieu), "footA", "footB" (pieds) ;
     *    A et B désignent une lancette sur deux. */
    val overrides: Map<String, ColorRGBa> = emptyMap()
) {
    /** Couleur d'un rôle, tel que les dessins B et C le notent sur leurs
     *  pièces (RosaceStyles.kt) : out0, out1, mid0, mid1, cen0, cen1, deep,
     *  deepBig, acc, gold, cross, veryPale. Un rôle peut porter une étiquette
     *  (« acc@ogeeB ») : la couleur de overrides[étiquette] l'emporte alors,
     *  si la palette en définit une ; sinon, celle du rôle. */
    fun colorOfRole(role: String): ColorRGBa {
        val at = role.indexOf('@')
        if (at < 0) return colorOfBaseRole(role)
        return overrides[role.substring(at + 1)] ?: colorOfBaseRole(role.substring(0, at))
    }

    private fun colorOfBaseRole(role: String): ColorRGBa = when (role) {
        "out0" -> outer.first
        "out1" -> outer.second
        "mid0" -> middle.first
        "mid1" -> middle.second
        "cen0" -> centre.first
        "cen1" -> centre.second
        "deep" -> deep
        "deepBig" -> deepBig
        "acc" -> accent
        "gold" -> gold
        "cross" -> cross
        "veryPale" -> veryPale
        else -> deep
    }
}

object RosacePalettes {
    private fun c(hex: String) = ColorRGBa.fromHex(hex)

    /** Rose, grenat et or — VALIDÉE pour Gaudete et Laetare (2 nov. 2026),
     *  palette de référence des rosaces A, B, C. */
    val GRENAT_OR = RosacePalette(
        name = "Rose, grenat et or",
        outer = c("#6E2238") to c("#7E2C44"),
        middle = c("#C9879C") to c("#DDA7B6"),
        centre = c("#F5E6DA") to c("#E9C3CB"),
        deep = c("#3E1222"),
        deepBig = c("#561A2D"),
        accent = c("#9A4A5E"),
        gold = c("#C9A55A"),
        cross = c("#F5E6DA"),
        lead = c("#343436")
    )

    /** La joie dans l'attente — la plus juste liturgiquement : le violet de
     *  l'Avent et du Carême, le rose au cœur. */
    val JOIE_ATTENTE = RosacePalette(
        name = "La joie dans l'attente",
        outer = c("#493449") to c("#68465F"),
        middle = c("#C87997") to c("#E3B0C0"),
        centre = c("#F0E5D5") to c("#E3B0C0"),
        deep = c("#493449"),
        deepBig = c("#583D54"),
        accent = c("#68465F"),
        gold = c("#B49A62"),
        cross = c("#F0E5D5"),
        lead = c("#302B30")
    )

    /** Grenat et prune — palette de travail proposée par l'autre IA, retenue
     *  le 1er nov. 2026 ; #48222F (fonds les plus sombres) est dérivé du grenat. */
    val GRENAT_PRUNE = RosacePalette(
        name = "Grenat et prune",
        outer = c("#592B3D") to c("#60415F"),
        middle = c("#C87997") to c("#E3B0C0"),
        centre = c("#F0E5D5") to c("#E3B0C0"),
        deep = c("#48222F"),
        deepBig = c("#592B3D"),
        accent = c("#60415F"),
        gold = c("#B49A62"),
        cross = c("#F0E5D5"),
        lead = c("#302B30")
    )

    // PALETTES « ESTHÉTIQUES » (1er nov. 2026), ajustées à partir de bases
    // proposées par Laetitia : centre lumineux, pas de teinte grisée,
    // saturation modérée ; pierre et plombs choisis pour chaque palette.

    /** Arctic Night — bleu nuit et glace, or pâle comme une étoile ; pierre
     *  froide. Retenue (complément pour les ornements de Noël) ; c'est la
     *  palette « bleu, ivoire et or » des briefs de l'autre IA. Base : nuit
     *  #0D1B2A, dégradé #FFFFFF → #C9E4F5 → #87CEEB → #191970, adouci. */
    val ARCTIC_NIGHT = RosacePalette(
        name = "Arctic Night",
        outer = c("#1B2B5E") to c("#24396F"),
        middle = c("#7DB0D3") to c("#A8CDE5"),
        centre = c("#F8FAFC") to c("#DCEBF5"),
        deep = c("#0D1B2A"),
        deepBig = c("#16294A"),
        accent = c("#3F6699"),
        gold = c("#D9BE7A"),
        cross = c("#F8FAFC"),
        veryPale = c("#E8F1F8"),
        stone = c("#D9DAD6"),
        lead = c("#262B33")
    )

    /** Emerald Depths — verts de vitrail et or ; candidate pour revisiter la
     *  palette verte. Centre et croix en jade pâle, moins blancs que la
     *  première version (#F2F6EE ; croix #F2F6EE), jugés trop blancs dans B et
     *  les C. Base : forêt #0C3B2E, dégradé #D4E8DC → #8FC9A8 → #50997A → #2D5F4D. */
    val EMERALD_DEPTHS = RosacePalette(
        name = "Emerald Depths",
        outer = c("#1D5641") to c("#25654D"),
        middle = c("#5BA27F") to c("#8BC2A3"),
        centre = c("#D9EADD") to c("#B5D6C0"),
        deep = c("#0B3326"),
        deepBig = c("#134434"),
        accent = c("#3A8063"),
        gold = c("#D4AE55"),
        cross = c("#CDE5D4"),
        veryPale = c("#CBE3D3"),
        stone = c("#D8CFBD"),
        lead = c("#28302C")
    )

    /** Ocean Reverie — sarcelle et vert d'eau, sans seconde couleur (la
     *  version avec un accent cuivre a été écartée : plus belle sans). Teintes
     *  de la base telles que données (#14363C ; #CFE6E4 → #7FA8A6 → #335C5A),
     *  la quatrième obtenue par mélange. */
    val OCEAN_REVERIE = RosacePalette(
        name = "Ocean Reverie",
        outer = c("#335C5A") to c("#3E6765"),
        middle = c("#7FA8A6") to c("#9FC1BF"),
        centre = c("#CFE6E4") to c("#A7C7C5"),
        deep = c("#14363C"),
        deepBig = c("#1F4346"),
        accent = c("#598280"),
        gold = c("#BBD6D4"),
        cross = c("#CFE6E4"),
        veryPale = c("#B7D3D1")
    )

    /** Sunset Reverie — bordeaux, corail et or, centre crème pêche ; pierre
     *  grès chaud. Base (fond crème #FBE4C9, dégradé #F4C430 → #FFDAB9 →
     *  #CD5C5C → #800020) réinterprétée : la crème au centre, le bordeaux dans
     *  les fonds ; teintes adoucies. */
    val SUNSET_REVERIE = RosacePalette(
        name = "Sunset Reverie",
        outer = c("#7A1E2E") to c("#8C2A38"),
        middle = c("#D27866") to c("#E7A68E"),
        centre = c("#FCEEDD") to c("#F6D5B8"),
        deep = c("#4A1420"),
        deepBig = c("#64192A"),
        accent = c("#B04A45"),
        gold = c("#E2B04A"),
        cross = c("#FCEEDD"),
        veryPale = c("#F9E3CF"),
        stone = c("#E2D3BB"),
        lead = c("#33292A")
    )

    /** Orchid Shadow — prune presque noire et orchidée, or ancien. À L'ESSAI :
     *  à comparer avec la palette violette actuelle avant de décider. Base :
     *  #1A0F1C, dégradé #C8A5C4 → #9B7498 → #6B4A6B → #3D2540, désgrisé. */
    val ORCHID_SHADOW = RosacePalette(
        name = "Orchid Shadow",
        outer = c("#46264C") to c("#55305C"),
        middle = c("#A86FA6") to c("#C899C4"),
        centre = c("#F6EDF3") to c("#E6CDE1"),
        deep = c("#1C1020"),
        deepBig = c("#2D1A33"),
        accent = c("#7A4680"),
        gold = c("#C9A55A"),
        cross = c("#F6EDF3"),
        veryPale = c("#EFE0EB"),
        stone = c("#D6CCC4"),
        lead = c("#2A2229")
    )

    // PALETTES LITURGIQUES DES ROSACES A, B, C (2 nov. 2026), d'après les
    // teintes indicatives proposées par l'autre IA, adaptées. Vert : validée.
    // Blanc : à l'essai (v2a, v2b) — et C demandera une palette blanche propre,
    // sa croix ne ressortant que sur un fond soutenu.

    /** Vert, ivoire et or — VALIDÉE pour le Temps ordinaire (2 nov. 2026).
     *  Version 2 : plus de profondeur et de
     *  nuances, sans dominante sauge ni émeraude. Teintes indicatives #174C3B,
     *  #287653, #559875, #A5C8AD, ivoire #F1EBDD, or ancien #B69550 ; le vert
     *  clair #A5C8AD (proche de la sauge) n'est pas employé tel quel : il est
     *  remplacé par #8DBB9C (rôle middle) et #CFE0CC (anneaux clairs) ; l'or
     *  est éclairci (#BE9C52), un or trop sombre virant au bronze sous le verre.
     *  Version 1 (#2F6B45, #3C7A4E, #6FA77E…), jugée trop uniforme. */
    val VERT_IVOIRE_OR = RosacePalette(
        name = "Vert, ivoire et or",
        outer = c("#174C3B") to c("#1F5E46"),
        middle = c("#559875") to c("#8DBB9C"),
        centre = c("#F1EBDD") to c("#CFE0CC"),
        deep = c("#123D30"),
        deepBig = c("#1B5240"),
        accent = c("#287653"),
        gold = c("#BE9C52"),
        cross = c("#F1EBDD"),
        veryPale = c("#E6ECDD"),
        lead = c("#2A2E2B")
    )

    /** Blanc, ivoire et or : palettes de Noël des rosaces, VALIDÉES le 3 nov.
     *  2026 (essais v4 à v7, d'après les briefs de l'autre IA). Une palette par
     *  dessin, sur une base commune :
     *  - blanc lumineux #FFFDF8 (blanc dominant) ; nacre froide #E9EEF1 (celle
     *    de la palette « blanc lumineux » de Noël, Palette.kt), qui empêche
     *    l'ensemble de glisser vers le beige ; ivoire chaud #F2EBDD ;
     *  - champagne clair #E8D6AE ; or lumineux #D0A64D ; or ancien #A98543,
     *    réservé aux petits accents (trèfles de A, perles de B1) ;
     *  - pierre claire #D0C7B5 ; plombs #4A453D, contours fins #655F53
     *    (traitement « b » ; ni contours noirs épais, ni plomb #777064, qui
     *    dissout la structure de A).
     *  L'or va aux médaillons, perles, trèfles et détails ; pas de carreaux
     *  dorés sur la bordure des B, pas d'anneau extérieur doré sur C.
     *  Écartés : v3 (ivoires et pierres, rendu patiné et terne), panneaux
     *  champagne sur C (trop jaune), semis en damier sur la couronne des B. */
    private val noelBase = RosacePalette(
        name = "Blanc, ivoire et or (Noël)",
        outer = c("#F2EBDD") to c("#FFFDF8"),
        middle = c("#FFFDF8") to c("#E9EEF1"),
        centre = c("#FFFDF8") to c("#E8D6AE"),
        deep = c("#D0A64D"),
        deepBig = c("#E8D6AE"),
        accent = c("#F2EBDD"),
        gold = c("#D0A64D"),
        cross = c("#FFFDF8"),
        veryPale = c("#D0A64D"),
        stone = c("#D0C7B5"),
        lead = c("#4A453D"),
        leadFine = c("#655F53")
    )

    /** Noël, dessin A (médiévale) : cœur de l'oculus cerclé d'or, trèfles en
     *  or ancien, écoinçons du bord tous en champagne clair (anneau continu). */
    val NOEL_A = noelBase.copy(
        name = "Blanc, ivoire et or (Noël) — A",
        centre = c("#FFFDF8") to c("#D0A64D"),
        gold = c("#A98543"),
        overrides = mapOf("spandrel2" to c("#E8D6AE"))
    )

    /** Couronne des B : toutes les pièces en accolade en champagne clair, plus
     *  la pièce du milieu une lancette sur deux (dégradé du doré au blanc). */
    private val noelRingB = mapOf("ogeeA" to c("#E8D6AE"), "ogeeB" to c("#E8D6AE"), "middleA" to c("#E8D6AE"))

    /** Noël, dessin B1 (amandes) : médaillon doré, perles en or ancien. */
    val NOEL_B1 = noelBase.copy(
        name = "Blanc, ivoire et or (Noël) — B1",
        outer = c("#F2EBDD") to c("#E8D6AE"),
        centre = c("#FFFDF8") to c("#D0A64D"),
        deepBig = c("#F2EBDD"),
        gold = c("#A98543"),
        overrides = noelRingB
    )

    /** Noël, dessin B2 (étoile) : or lumineux pour les perles, médaillon clair. */
    val NOEL_B2 = noelBase.copy(
        name = "Blanc, ivoire et or (Noël) — B2",
        outer = c("#F2EBDD") to c("#E8D6AE"),
        deepBig = c("#F2EBDD"),
        overrides = noelRingB
    )

    /** Noël, dessin C (contemporaine) : croix blanche sur une gloire dorée
     *  (disque central), champagne le long des bras, panneaux blancs. */
    val NOEL_C = noelBase.copy(
        name = "Blanc, ivoire et or (Noël) — C",
        outer = c("#D0A64D") to c("#FFFDF8"),
        middle = c("#E8D6AE") to c("#F2EBDD"),
        centre = c("#FFFDF8") to c("#D0A64D")
    )

    val ALL = listOf(GRENAT_OR, JOIE_ATTENTE, GRENAT_PRUNE, ARCTIC_NIGHT, EMERALD_DEPTHS, OCEAN_REVERIE, SUNSET_REVERIE, ORCHID_SHADOW, VERT_IVOIRE_OR, NOEL_A, NOEL_B1, NOEL_B2, NOEL_C)

    /** Pierre sombre : un anthracite, comme les pierres volcaniques (pierre de
     *  Volvic) ou un remplage vu à contre-jour. S'emploie avec
     *  palette.copy(stone = RosacePalettes.PIERRE_ANTHRACITE). Pour une
     *  lecture « plombs seuls », prendre plutôt palette.copy(stone = palette.lead). */
    val PIERRE_ANTHRACITE: ColorRGBa = c("#4A4743")
}

/**
 * Couleurs de la rosace : chaque pièce prend la couleur de son type et de
 * sa position (voir RosaceGeometry.colorOf). Remplace les couleurs tirées
 * par le PaletteSystem ; ne consomme AUCUN tirage aléatoire.
 * - rosace rayonnante (RosaceCompositionSystem) : scheme (RosaceScheme) ;
 * - rosace médiévale sobre (MedievalRosaceCompositionSystem) et dessins B
 *   et C (RosaceStyles.kt) : palette (RosacePalette).
 */
fun applyRosaceColors(guide: RosaceGuide, cellColors: MutableList<ColorRGBa>, scheme: RosaceScheme, palette: RosacePalette) {
    val geometry = guide.geometry
    for (i in cellColors.indices) {
        val p = guide.unitPieces[i]
        cellColors[i] = when {
            geometry == null -> palette.colorOfRole(p.role)
            geometry.sober -> geometry.medievalColorOf(p, palette)
            else -> geometry.colorOf(p.kind, p.role, p.angle, scheme)
        }
    }
}

/** Couleur de la pierre : celle du RosaceScheme (rosace rayonnante) ou de la
 *  RosacePalette (tous les autres dessins). */
fun rosaceStoneColor(guide: RosaceGuide, scheme: RosaceScheme, palette: RosacePalette): ColorRGBa {
    val geometry = guide.geometry
    return if (geometry == null || geometry.sober) palette.stone else scheme.stone
}

/** Plombs épais de la hiérarchie (dessins B et C), par-dessus les plombs fins.
 *  leadWidth : LeadStyle.width, déjà mis à l'échelle de l'export. */
fun drawRosaceLeadLines(drawer: Drawer, guide: RosaceGuide, offset: Vector2, leadColor: ColorRGBa, leadWidth: Double) {
    if (guide.leadLines.isEmpty()) return
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = leadColor
        drawer.lineCap = LineCap.ROUND
        for (ln in guide.leadLines) {
            if (ln.points.size < 2) continue
            val pts = ln.points.map { Vector2(it.x + offset.x, it.y + offset.y) }
            val n = if (ln.closed) pts.size else pts.size - 1
            val segments = (0 until n).map { k ->
                val a = pts[k]
                val b = pts[(k + 1) % pts.size]
                Segment2D(a, Vector2(a.x + (b.x - a.x) / 3.0, a.y + (b.y - a.y) / 3.0), Vector2(a.x + 2.0 * (b.x - a.x) / 3.0, a.y + 2.0 * (b.y - a.y) / 3.0), b)
            }
            drawer.strokeWeight = leadWidth * ln.factor
            drawer.contour(ShapeContour.fromSegments(segments, closed = ln.closed))
        }
    }
}

/** Contour à segments droits d'une pièce (la rosace n'a pas d'arêtes courbées). */
fun straightContour(cell: List<Vector2>): ShapeContour {
    val segments = cell.indices.map { k ->
        val a = cell[k]
        val b = cell[(k + 1) % cell.size]
        Segment2D(
            a,
            Vector2(a.x + (b.x - a.x) / 3.0, a.y + (b.y - a.y) / 3.0),
            Vector2(a.x + 2.0 * (b.x - a.x) / 3.0, a.y + 2.0 * (b.y - a.y) / 3.0),
            b
        )
    }
    return ShapeContour.fromSegments(segments, closed = true)
}

/** Cercle en quatre arcs de Bézier. */
private fun circleContour(c: Vector2, r: Double): ShapeContour {
    val k = 0.5522847498 * r
    val pts = listOf(Vector2(c.x + r, c.y), Vector2(c.x, c.y + r), Vector2(c.x - r, c.y), Vector2(c.x, c.y - r))
    val tangents = listOf(Vector2(0.0, k), Vector2(-k, 0.0), Vector2(0.0, -k), Vector2(k, 0.0))
    val segments = (0 until 4).map { i ->
        val a = pts[i]; val b = pts[(i + 1) % 4]
        val ta = tangents[i]; val tb = tangents[(i + 1) % 4]
        Segment2D(a, Vector2(a.x + ta.x, a.y + ta.y), Vector2(b.x - tb.x, b.y - tb.y), b)
    }
    return ShapeContour.fromSegments(segments, closed = true)
}

/** Pierre : disque plein sous le verre (les vitraux le recouvrent ensuite). */
fun drawRosaceStone(drawer: Drawer, guide: RosaceGuide, offset: Vector2, stone: ColorRGBa) {
    if (!guide.hasStone) return
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.stroke = null
        drawer.fill = stone
        drawer.contour(circleContour(Vector2(guide.center.x + offset.x, guide.center.y + offset.y), guide.radius))
    }
}

/** Contour extérieur de la pierre, par-dessus tout. */
fun drawRosaceRim(drawer: Drawer, guide: RosaceGuide, offset: Vector2, leadColor: ColorRGBa, leadWidth: Double) {
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = leadColor
        drawer.strokeWeight = leadWidth * 2.3
        drawer.contour(circleContour(Vector2(guide.center.x + offset.x, guide.center.y + offset.y), guide.radius))
    }
}

// ================================================================ géométrie (JTS)

private val GF = GeometryFactory()

private fun polar(r: Double, a: Double) = Coordinate(r * cos(a), r * sin(a))
private fun clamp(x: Double, a: Double, b: Double) = max(a, min(b, x))
private fun dist(a: Coordinate, b: Coordinate) = hypot(a.x - b.x, a.y - b.y)
private fun line(cs: List<Coordinate>): LineString = GF.createLineString(cs.toTypedArray())
private fun pointAt(c: Coordinate) = GF.createPoint(c)

/** Arrondi « à la Python » (demi vers le pair), pour reproduire le prototype. */
private fun pyRound(x: Double): Long = Math.rint(x).toLong()

private fun union(gs: List<Geometry>): Geometry = OverlayNGRobust.union(gs)
private fun inter(a: Geometry, b: Geometry): Geometry = OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION)
private fun diff(a: Geometry, b: Geometry): Geometry = OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE)

private fun bufferMitre(g: Geometry, d: Double): Geometry =
    BufferOp.bufferOp(g, d, BufferParameters(16, BufferParameters.CAP_ROUND, BufferParameters.JOIN_MITRE, 5.0))

private fun rotate(g: Geometry, a: Double): Geometry = AffineTransformation.rotationInstance(a).transform(g)
private fun mirrorX(g: Geometry): Geometry = AffineTransformation.scaleInstance(1.0, -1.0).transform(g)

private fun polygonsOf(g: Geometry): List<Polygon> = when (g) {
    is Polygon -> if (g.isEmpty) emptyList() else listOf(g)
    is GeometryCollection -> (0 until g.numGeometries).flatMap { polygonsOf(g.getGeometryN(it)) }
    else -> emptyList()
}

private fun lineStringsOf(g: Geometry): List<LineString> = when (g) {
    is LineString -> if (g.isEmpty) emptyList() else listOf(g)
    is GeometryCollection -> (0 until g.numGeometries).flatMap { lineStringsOf(g.getGeometryN(it)) }
    else -> emptyList()
}

/** Cercle (polyligne fermée) ; nombre de sommets multiple de 48. */
private fun circ(c: Coordinate, r: Double, n: Int? = null): LineString {
    val n0 = n ?: max(48, (r * 900).toInt())
    val nn = 48 * max(1L, pyRound(n0 / 48.0)).toInt()
    return line((0..nn).map { k -> Coordinate(c.x + r * cos(2 * PI * k / nn), c.y + r * sin(2 * PI * k / nn)) })
}

private fun archHeight(ratio: Double) = sqrt(ratio * ratio - (ratio - 0.5) * (ratio - 0.5))

/**
 * Arc brisé (deux arcs de cercle de rayon ratio × ouverture) entre les
 * naissances pl et pr ; la pointe est du côté extérieur de la rosace.
 */
private fun arch(pl: Coordinate, pr: Coordinate, ratio: Double, n: Int = 28): Pair<List<Coordinate>, Coordinate> {
    val span = dist(pl, pr)
    val rad = span * ratio
    val ux = (pr.x - pl.x) / span
    val uy = (pr.y - pl.y) / span
    var nx = -uy
    var ny = ux
    val mx = (pl.x + pr.x) / 2
    val my = (pl.y + pr.y) / 2
    if (nx * mx + ny * my < 0) { nx = -nx; ny = -ny }
    val cl = Coordinate(pl.x + ux * rad, pl.y + uy * rad)
    val cr = Coordinate(pr.x - ux * rad, pr.y - uy * rad)
    val h = sqrt(max(rad * rad - (rad - span / 2) * (rad - span / 2), 0.0))
    val apex = Coordinate(mx + nx * h, my + ny * h)
    fun arc(c: Coordinate, a: Coordinate, b: Coordinate): List<Coordinate> {
        val t0 = atan2(a.y - c.y, a.x - c.x)
        val t1 = atan2(b.y - c.y, b.x - c.x)
        val d = (t1 - t0 + PI).mod(2 * PI) - PI
        return (0..n).map { k -> Coordinate(c.x + rad * cos(t0 + d * k / n), c.y + rad * sin(t0 + d * k / n)) }
    }
    return Pair(arc(cl, pl, apex) + arc(cr, apex, pr).drop(1), apex)
}

/** Contour d'un polylobe (trilobe, quadrilobe…) inscrit dans un cercle de rayon R. */
private fun foilOutline(c: Coordinate, r: Double, lobes: Int, a0: Double, cusp: Double = 0.62): LineString {
    val rho = r * (1 - cusp) * 1.15
    val d = r - rho
    val parts = mutableListOf<Geometry>(pointAt(c).buffer(r * cusp * 0.95, 48))
    for (k in 0 until lobes) {
        val a = a0 + 2 * PI * k / lobes
        parts.add(pointAt(Coordinate(c.x + d * cos(a), c.y + d * sin(a))).buffer(rho, 48))
    }
    val shape = polygonsOf(union(parts)).maxByOrNull { it.area }!!
    return line(shape.exteriorRing.coordinates.toList())
}

private fun wedge(a0: Double, a1: Double, r: Double = 2.0): Polygon {
    val cs = listOf(Coordinate(0.0, 0.0)) + (0..8).map { k -> polar(r, a0 + (a1 - a0) * k / 8) } + listOf(Coordinate(0.0, 0.0))
    return GF.createPolygon(cs.toTypedArray())
}

/** Découpe un polygone par des lignes (les pièces voisines partagent exactement leurs arêtes). */
private fun splitBy(poly: Polygon, lines: List<LineString>): List<Polygon> {
    val pb = poly.buffer(1e-9)
    val ls = mutableListOf<Geometry>()
    for (ln in lines) {
        val x = inter(ln, pb)
        if (!x.isEmpty) ls.addAll(lineStringsOf(x))
    }
    if (ls.isEmpty()) return listOf(poly)
    val rings = mutableListOf<Geometry>(line(poly.exteriorRing.coordinates.toList()))
    for (i in 0 until poly.numInteriorRing) rings.add(line(poly.getInteriorRingN(i).coordinates.toList()))
    val noded = union(rings + ls)
    val pz = Polygonizer()
    pz.add(noded)
    val big = poly.buffer(1e-7)
    val parts = pz.polygons.map { it as Polygon }.filter { it.area > 1e-6 && big.contains(it.interiorPoint) }
    return parts.ifEmpty { listOf(poly) }
}

/** Retire les trous éventuels d'une pièce en la recoupant (les cellules du pipeline n'ont pas de trous). */
private fun withoutHoles(p: Polygon, depth: Int = 0): List<Polygon> {
    if (p.numInteriorRing == 0 || depth > 6) return listOf(p)
    val hc = p.getInteriorRingN(0).centroid.coordinate
    val env = p.envelopeInternal
    val cut = line(listOf(Coordinate(env.minX - 1, hc.y), Coordinate(env.maxX + 1, hc.y)))
    return splitBy(p, listOf(cut)).flatMap { withoutHoles(it, depth + 1) }
}

private fun symmetrize(op: Geometry, axis: Double): Polygon {
    val l = rotate(op, -axis)
    val halfPlane = GF.createPolygon(arrayOf(Coordinate(-2.0, 0.0), Coordinate(2.0, 0.0), Coordinate(2.0, 2.0), Coordinate(-2.0, 2.0), Coordinate(-2.0, 0.0)))
    val half = inter(l, halfPlane)
    var full = union(listOf(half, mirrorX(half)))
    full = bufferMitre(bufferMitre(full, 1e-9), -1e-9)
    val p = polygonsOf(full).maxByOrNull { it.area }!!
    return rotate(p, axis) as Polygon
}

/** Espacement du réseau de losanges, par type d'ouverture découpée en champ. */
private val LANCET_SPACING = mapOf(
    "sub_lancet" to 0.050, "lancet2" to 0.062, "arch_spandrel" to 0.048, "spandrel1" to 0.070, "oc_lancet" to 0.040
)

/**
 * Filet de bordure coupé à intervalles réguliers + réseau de losanges, dans
 * le repère de l'axe de l'ouverture (symétrique par construction).
 */
private fun lancetPieces(op: Polygon, ang: Double, s: Double, fillet: Double): List<Pair<Polygon, String>> {
    val l = rotate(op, -ang) as Polygon
    val env = l.envelopeInternal
    val minx = env.minX
    val maxx = env.maxX
    val inner: Geometry = if (fillet > 0) bufferMitre(l, -fillet) else l
    val out = mutableListOf<Pair<Polygon, String>>()
    if (fillet > 0) {
        val band = diff(l, inner)
        val cuts = mutableListOf<LineString>()
        var k = 1
        while (k < 60 && minx + fillet + k * s < maxx) {
            val x = minx + fillet + k * s
            cuts.add(line(listOf(Coordinate(x, -1.0), Coordinate(x, 1.0))))
            k++
        }
        cuts.add(line(listOf(Coordinate(maxx - 0.25 * (maxx - minx), 0.0), Coordinate(maxx + 1, 0.0))))
        for (g in polygonsOf(band)) {
            if (g.area < 1e-6) continue
            for (q in splitBy(g, cuts)) {
                val kk = floor((q.interiorPoint.x - minx - fillet) / s).toLong()
                out.add(q to "fillet${kk.mod(2)}")
            }
        }
    }
    val diag = mutableListOf<LineString>()
    for (k in -60 until 100) {
        val o = minx + k * s
        diag.add(line(listOf(Coordinate(o - 3, -3.0), Coordinate(o + 3, 3.0))))
        diag.add(line(listOf(Coordinate(o - 3, 3.0), Coordinate(o + 3, -3.0))))
    }
    for (g in polygonsOf(inner)) {
        for (q in splitBy(g, diag)) {
            val p = q.interiorPoint
            val u = p.x + p.y
            val v = p.x - p.y
            val t = (floor((u - minx) / s).toLong() + floor((v - minx) / s).toLong()).mod(2)
            out.add(q to "field$t")
        }
    }
    return out.map { (q, role) -> (rotate(q, ang) as Polygon) to role }
}

/**
 * Lancette coupée en grands PANNEAUX par des traverses perpendiculaires à
 * son axe, tous les s (rosace médiévale sobre) ; rôles field0 / field1
 * alternés d'un panneau à l'autre. Dans le repère de l'axe : symétrique
 * par construction.
 */
private fun barPieces(op: Polygon, ang: Double, s: Double): List<Pair<Polygon, String>> {
    val l = rotate(op, -ang) as Polygon
    val env = l.envelopeInternal
    val minx = env.minX
    val maxx = env.maxX
    val bars = mutableListOf<LineString>()
    var k = 1
    while (k < 80 && minx + k * s < maxx) {
        val x = minx + k * s
        bars.add(line(listOf(Coordinate(x, -3.0), Coordinate(x, 3.0))))
        k++
    }
    val out = mutableListOf<Pair<Polygon, String>>()
    for (q in splitBy(l, bars)) {
        val t = floor((q.interiorPoint.x - minx) / s).toLong().mod(2)
        out.add((rotate(q, ang) as Polygon) to "field$t")
    }
    return out
}

/**
 * Géométrie d'une rosace à `rotations` secteurs, en unités (rayon 1).
 * Calculée une seule fois par réglage (voir of()).
 *
 * sober = true : rosace MÉDIÉVALE SOBRE (MedievalRosaceCompositionSystem) :
 * - couronne : plus de quadrilobes au bord, meneaux prolongés jusqu'au
 *   cercle extérieur ;
 * - lancettes coupées en panneaux par des traverses (ni losanges ni
 *   filets) ; pétales, fonds et lancettes de l'oculus en une seule pièce ;
 * - oculus central agrandi (× 1,45, au plus 0,36), découpé en un cœur rond
 *   et une pièce par lobe.
 * sober = false : la rosace rayonnante d'origine, inchangée.
 */
class RosaceGeometry private constructor(
    val rotations: Int,
    val sober: Boolean,
    /** Rosace sobre : diamètre du médaillon central (1,0 = prototype). */
    val medallionScale: Double,
    /** Rosace sobre : hauteur des panneaux des lancettes de l'étoile. */
    val starPanelHeight: Double
) {

    companion object {
        private data class Key(val rotations: Int, val sober: Boolean, val medallionScale: Double, val starPanelHeight: Double)
        private val cache = mutableMapOf<Key, RosaceGeometry>()

        /** Géométrie (mise en cache) pour ce nombre de rotations et ce style.
         *  medallionScale et starPanelHeight ne servent qu'à la rosace sobre. */
        fun of(rotations: Int, sober: Boolean = false, medallionScale: Double = 1.0, starPanelHeight: Double = 0.09): RosaceGeometry = synchronized(cache) {
            val key = if (sober) Key(rotations, true, medallionScale, starPanelHeight) else Key(rotations, false, 1.0, 0.09)
            cache.getOrPut(key) { RosaceGeometry(key.rotations, key.sober, key.medallionScale, key.starPanelHeight) }
        }

        /** Ouverture minimale d'une sous-lancette (rayon de la rosace = 1). */
        const val SPLIT_MIN_CHORD = 0.05
    }

    private class Node(
        val c: Double, val w: Double, val s: Double, val level: Int,
        val head: List<Coordinate>, val lw: Double
    ) {
        var split = false
        var s2 = 0.0
        var oculus: Pair<Coordinate, Double>? = null
        var childLw = 0.0
    }

    private class Head(val center: Coordinate, val rho: Double, val axis: Double, val level: Int)

    val N: Int
    private val STEP: Double
    private val H: Double
    /** Alternance de couleurs d'un secteur à l'autre (seulement si rotations est pair). */
    val alternate: Boolean
    private val W1: Double
    private val W2: Double
    private val W3: Double
    private val R_OUT = 0.965
    private val R_MID = 0.60
    private val R_OC: Double
    private val spr1: Double
    private val m2: Int
    private val h2: Double
    private val spr3: Double
    private val chord2: Double
    private val rosR: Double
    private val rosRadius: Double
    private val ocLobes: Int
    private val ocCrown: Boolean
    private val ocS: Double
    private val R_CORE: Double
    private val lancet2Axes: List<Double>
    private val nodes = mutableListOf<Node>()
    private val heads = mutableListOf<Head>()
    private val leaves = mutableListOf<Node>()
    private val root: Node

    init {
        require(rotations in 3..48) { "rotations doit être entre 3 et 48 (reçu : $rotations)" }
        N = rotations
        STEP = 2 * PI / N
        H = STEP / 2
        alternate = N % 2 == 0
        val ws = clamp(sqrt(sin(H) / sin(PI / 12)), 0.75, 1.25)
        W1 = 0.021 * ws; W2 = 0.013 * ws; W3 = 0.008 * ws
        // anneau 1 : une lancette par secteur
        val rOc = clamp(0.040 / sin(H), 0.15, 0.36)
        R_OC = if (sober) min(rOc * 1.45, 0.36) else rOc   // sobre : centre agrandi
        spr1 = R_MID / (cos(H) + 2 * sin(H) * archHeight(0.95))      // pointe de l'arc sur l'anneau médian
        // anneau 2 : lancettes d'environ 15°
        m2 = max(1L, pyRound(2 * H / (15.0 * PI / 180.0))).toInt()
        h2 = H / m2
        val a2 = R_OUT - 0.028
        spr3 = a2 / (cos(h2) + 2 * sin(h2) * archHeight(0.9))
        chord2 = 2 * spr3 * sin(h2)
        rosRadius = 0.22 * chord2
        rosR = R_OUT - 0.6 * W1 - rosRadius - 0.004
        // oculus central
        ocLobes = if (N % 2 == 0 && N >= 8) N / 2 else N
        ocCrown = R_OC >= 0.19
        if (ocCrown) {
            ocS = R_OC / (cos(H) + 2 * sin(H) * archHeight(1.0))
            R_CORE = min(0.45 * R_OC * (if (sober) medallionScale else 1.0), ocS - 0.03)
        } else {
            ocS = 0.0
            R_CORE = R_OC
        }
        lancet2Axes = (0 until m2).map { i -> -H + h2 * (2 * i + 1) }
        root = tracery(0.0, H, spr1, 0)
    }

    // ------------------------------------------------------------ remplage récursif

    private fun width(level: Int) = listOf(W1, W2, W3 * 1.3, W3)[min(level, 3)]

    private fun tracery(c: Double, w: Double, s: Double, level: Int): Node {
        val ratio = if (level == 0) 0.95 else 1.0
        val (head, apex) = arch(polar(s, c - w), polar(s, c + w), ratio)
        val node = Node(c, w, s, level, head, width(level))
        nodes.add(node)
        val chord = 2 * s * sin(w)
        val s2 = s - 0.25 * chord
        val childChord = 2 * s2 * sin(w / 2)
        if (childChord >= SPLIT_MIN_CHORD && s2 >= R_OC + 0.03 && level < 3) {
            node.split = true
            node.s2 = s2
            node.childLw = width(level + 1)
            val kids = listOf(tracery(c - w / 2, w / 2, s2, level + 1), tracery(c + w / 2, w / 2, s2, level + 1))
            // oculus de tête : plus grand cercle sur l'axe entre l'arc et les deux sous-arcs
            val lines = listOf(line(head) to node.lw) + kids.map { line(it.head) to it.lw }
            val ra = hypot(apex.x, apex.y)
            var bestAv = 0.0
            var bestT = s2
            for (k in 1 until 80) {
                val t = s2 + (ra - s2) * k / 80
                val p = pointAt(polar(t, c))
                val av = lines.minOf { (l, lw) -> p.distance(l) - lw / 2 }
                if (av > bestAv) { bestAv = av; bestT = t }
            }
            val rho = (bestAv - width(level + 1) / 2) * 0.72
            node.oculus = polar(bestT, c) to rho
            heads.add(Head(polar(bestT, c), rho, c, level))
        } else {
            leaves.add(node)
        }
        return node
    }

    private fun tref2(ci: Double) = polar(spr3 + 0.206 * chord2, ci)

    /** Squelette d'UN secteur (axe à l'angle 0) ; le meneau à −H vient du secteur voisin. */
    private fun sectorSkeleton(): List<Pair<LineString, Double>> {
        val l = mutableListOf<Pair<LineString, Double>>()
        l.add(line(listOf(polar(R_OC, H), polar(spr1, H))) to W1)
        for (nd in nodes) {
            l.add(line(nd.head) to nd.lw)
            if (nd.split) {
                l.add(line(listOf(polar(R_OC, nd.c), polar(nd.s2, nd.c))) to nd.childLw)
                val (c, rho) = nd.oculus!!
                l.add(circ(c, rho) to nd.childLw)
                l.add(foilOutline(c, rho * 0.86, 3, nd.c) to W3 * (if (nd.level == 0) 1.0 else 0.8))
            }
        }
        // anneau 2
        for ((i, ci) in lancet2Axes.withIndex()) {
            val right = ci + h2
            // sobre : meneau prolongé jusqu'au cercle extérieur
            l.add(line(listOf(polar(R_MID, right), polar(if (sober) R_OUT else spr3, right))) to (if (i == m2 - 1) W1 else W2))
            val (hd, _) = arch(polar(spr3, ci - h2), polar(spr3, right), 0.9)
            l.add(line(hd) to W2)
            l.add(foilOutline(tref2(ci), 0.177 * chord2, 3, ci) to W3)
            if (sober) continue   // sobre : pas de quadrilobe au bord
            val c = polar(rosR, right)
            l.add(circ(c, rosRadius) to W2)
            l.add(foilOutline(c, rosRadius * 0.85, 4, right + PI / 4) to W3)
        }
        return l
    }

    private fun fullSkeleton(): List<Pair<Geometry, Double>> {
        val o = Coordinate(0.0, 0.0)
        val l = mutableListOf<Pair<Geometry, Double>>(
            circ(o, R_OUT, 48 * 15) to W1 * 1.2, circ(o, R_OC, 48 * 5) to W1, circ(o, R_MID, 48 * 12) to W1
        )
        val sec = sectorSkeleton()
        for (i in 0 until N) for ((ln, w) in sec) l.add(rotate(ln, i * STEP) to w)
        l.add(foilOutline(o, R_CORE * 0.80, ocLobes, 0.0) to W2)
        if (ocCrown) {
            l.add(circ(o, R_CORE, 48 * 4) to W2)
            for (i in 0 until N) {
                val a = i * STEP
                l.add(line(listOf(polar(R_CORE, a + H), polar(ocS, a + H))) to W3 * 1.3)
                val (hd, _) = arch(polar(ocS, a - H), polar(ocS, a + H), 1.0)
                l.add(line(hd) to W3 * 1.3)
            }
        }
        return l
    }

    // ------------------------------------------------------------ classification

    private class Label(val p: Coordinate, val kind: String, val axis: Double)

    private val labels: List<Label> by lazy {
        val out = mutableListOf<Label>()
        for (h in heads) out.add(Label(h.center, "head_core${h.level}", h.axis))
        for (nd in leaves) out.add(Label(polar((R_OC + nd.s) / 2, nd.c), "sub_lancet", nd.c))
        for (ci in lancet2Axes) {
            out.add(Label(polar((R_MID + spr3) / 2, ci), "lancet2", ci))
            out.add(Label(tref2(ci), "h2_core", ci))
            if (!sober) out.add(Label(polar(rosR, ci + h2), "ros_core", ci + h2))
        }
        if (!sober) out.add(Label(polar(rosR, -H), "ros_core", -H))
        if (ocCrown) {
            out.add(Label(polar((R_CORE + ocS) / 2, 0.0), "oc_lancet", 0.0))
            out.add(Label(polar(R_OC - 0.01, H), "oc_spandrel", H))
        }
        out
    }

    /** Type et axe d'une ouverture ramenée dans le secteur 0 (angle local entre 0 et H). */
    private fun classify(g: Polygon): Pair<String, Double> {
        val c = g.centroid.coordinate
        if (hypot(c.x, c.y) < 0.01) return "oculus" to 0.0
        for (lb in labels) if (g.contains(pointAt(lb.p))) return lb.kind to lb.axis
        val rp = g.interiorPoint.coordinate
        val r = hypot(rp.x, rp.y)
        val a = atan2(rp.y, rp.x)
        for (h in heads) if (dist(rp, h.center) < h.rho) return "head_lune" to h.axis
        if (!sober) for (ax in lancet2Axes.map { it + h2 } + listOf(-H)) {
            if (dist(rp, polar(rosR, ax)) < rosRadius) return "ros_lune" to ax
        }
        if (r < R_MID) {
            val nd = root
            val region = GF.createPolygon((nd.head + listOf(polar(R_OC, nd.c + nd.w), polar(R_OC, nd.c - nd.w), nd.head[0])).toTypedArray())
            if (region.contains(pointAt(rp))) return "arch_spandrel" to a
            return "spandrel1" to a
        }
        return "spandrel2" to a
    }

    // ------------------------------------------------------------ construction

    /**
     * Oculus de la rosace sobre : un cœur rond (rôle core), puis une pièce
     * par lobe (rôle oc_in) — et non des rayons fins, qui faisaient une
     * « marguerite ». Ouverture centrale sans le centre : une pièce par lobe
     * (rôle oc_out).
     */
    private fun soberOculusPieces(op: Polygon): List<Pair<Polygon, String>> {
        val out = mutableListOf<Pair<Polygon, String>>()
        val o = pointAt(Coordinate(0.0, 0.0))
        val rest: Geometry
        val role: String
        if (op.contains(o)) {
            val core = o.buffer(0.30 * R_CORE, 48)
            for (p in polygonsOf(inter(core, op))) out.add(p to "core")
            rest = diff(op, core)
            role = "oc_in"
        } else {
            rest = op
            role = "oc_out"
        }
        for (k in 0 until ocLobes) {
            val a = 2 * PI * k / ocLobes
            for (g in polygonsOf(inter(rest, wedge(a - PI / ocLobes, a + PI / ocLobes)))) if (g.area > 1e-7) out.add(g to role)
        }
        return out
    }

    private fun oculusPieces(op: Polygon): List<Pair<Polygon, String>> {
        if (sober) return soberOculusPieces(op)
        val out = mutableListOf<Pair<Polygon, String>>()
        val o = pointAt(Coordinate(0.0, 0.0))
        val core = o.buffer(0.267 * R_CORE, 48)
        val ring = o.buffer(0.50 * R_CORE, 48)
        for (p in polygonsOf(inter(core, op))) out.add(p to "core")
        val ringZone = diff(ring, core)
        val outerZone = diff(op, ring)
        for (k in 0 until N) {
            val w = wedge(k * STEP - H, k * STEP + H)
            for ((zone, role) in listOf(ringZone to "oc_in", outerZone to "oc_out")) {
                val q = inter(inter(zone, op), w)
                for (g in polygonsOf(q)) if (g.area > 1e-7) out.add(g to role)
            }
        }
        return out
    }

    private fun images(g: Polygon, ang: Double): List<Pair<Polygon, Double>> {
        val m = mirrorX(g) as Polygon
        val out = mutableListOf<Pair<Polygon, Double>>()
        for (i in 0 until N) {
            val d = i * STEP
            out.add((rotate(g, d) as Polygon) to ang + d)
            out.add((rotate(m, d) as Polygon) to -ang + d)
        }
        return out
    }

    private fun build(): List<RosacePiece> {
        val sk = fullSkeleton()
        val stone = union(sk.map { (ln, w) -> ln.buffer(w / 2, 6) })
        val disc = pointAt(Coordinate(0.0, 0.0)).buffer(R_OUT, 48 * 15)
        val openings = diff(disc, stone)
        val ops = polygonsOf(openings).filter { it.area > 2e-5 }
        val result = mutableListOf<RosacePiece>()
        data class Rep(val g: Polygon, val kind: String, val axis: Double)
        val reps = LinkedHashMap<Pair<Long, Long>, Rep>()
        for (op in ops) {
            var c = op.centroid.coordinate
            if (hypot(c.x, c.y) < 0.01) {
                for ((q, role) in oculusPieces(op)) for (p in withoutHoles(q)) result.add(RosacePiece(p, "oculus", role, 0.0))
                continue
            }
            val a = atan2(c.y, c.x)
            val k = pyRound(a / STEP)
            var g = rotate(op, -k * STEP) as Polygon
            c = g.centroid.coordinate
            var la = atan2(c.y, c.x)
            if (la < 0) { g = mirrorX(g) as Polygon; la = -la }
            val key = pyRound(g.centroid.x * 1000) to pyRound(g.centroid.y * 1000)
            if (key in reps) continue
            var onAxis: Double? = null
            if (la < 0.2 * PI / 180) onAxis = 0.0
            else if (la > H - 0.2 * PI / 180) onAxis = H
            if (onAxis != null) g = symmetrize(g, onAxis)
            var (kind, ax) = classify(g)
            if (onAxis != null) ax = onAxis   // ouverture symétrique : son repère est l'axe lui-même
            reps[key] = Rep(g, kind, ax)
        }
        val seen = HashSet<Triple<Long, Long, String>>()
        for (rep in reps.values) {
            val spacing = LANCET_SPACING[rep.kind]
            val base: List<Pair<Polygon, String>> = when {
                // sobre : lancettes en panneaux (traverses), le reste d'une pièce
                sober && rep.kind == "lancet2" -> barPieces(rep.g, rep.axis, 0.11)
                sober && rep.kind == "sub_lancet" -> barPieces(rep.g, rep.axis, starPanelHeight)
                sober && spacing != null -> listOf(rep.g to "field0")
                spacing != null -> {
                    val fillet = if (rep.kind == "sub_lancet" || rep.kind == "lancet2") 0.012 else 0.0
                    lancetPieces(rep.g, rep.axis, spacing, fillet)
                }
                else -> listOf(rep.g to "plain")
            }
            for ((q0, role) in base) for (q in withoutHoles(q0)) {
                for ((g, ga) in images(q, rep.axis)) {
                    val cc = g.centroid.coordinate
                    val key = Triple(pyRound(cc.x * 1e4), pyRound(cc.y * 1e4), role)
                    if (!seen.add(key)) continue
                    result.add(RosacePiece(g, rep.kind, role, ga))
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------ couleurs

    private fun parity(ang: Double): Int =
        if (!alternate) 0 else (floor(ang / STEP + 0.5).toLong().mod(N.toLong()) % 2).toInt()

    /**
     * Nuance plus claire ou plus foncée d'une couleur (f > 1 : plus clair).
     * Passe par un code hexadécimal, comme toutes les autres couleurs : un
     * ColorRGBa construit directement à partir de r, g, b était interprété
     * par OpenRNDR comme une couleur LINÉAIRE, donc affiché bien plus clair
     * (le grenat #6A1525 devenait un rose #AD516A, le gris violet #362B3F un
     * lilas #7F7288). Corrigé le 26 oct. 2026.
     */
    private fun shade(c: ColorRGBa, f: Double): ColorRGBa {
        fun ch(v: Double) = (min(1.0, v * f) * 255.0).roundToInt().coerceIn(0, 255)
        return ColorRGBa.fromHex(String.format("#%02X%02X%02X", ch(c.r), ch(c.g), ch(c.b)))
    }

    /** Couleur d'une pièce selon son type, son rôle et la parité de son secteur. */
    fun colorOf(kind: String, role: String, ang: Double, s: RosaceScheme): ColorRGBa {
        val par = parity(ang)
        if (role.startsWith("field")) {
            val t = role.last().digitToInt()
            return when (kind) {
                "arch_spandrel" -> if (t == 0 || !s.shadedFields) s.petal else shade(s.petal, 0.88)
                "spandrel1" -> if (t == 0 || !s.shadedFields) s.innerSpandrel else shade(s.innerSpandrel, 1.15)
                "lancet2" -> if ((t + par) % 2 == 0) s.outerField.first else s.outerField.second
                else -> if ((t + par) % 2 == 0) s.innerField.first else s.innerField.second
            }
        }
        if (role.startsWith("fillet")) return if (role.last() == '0') s.fillet.first else s.fillet.second
        if (role == "core") return s.core
        if (role == "oc_in") return s.oculusInner
        if (role == "oc_out") return s.oculusOuter
        if (kind == "oc_lancet") return s.oculusOuter
        if (kind == "oc_spandrel") return s.oculusInner
        if (kind.startsWith("head_core") || kind == "ros_core" || kind == "h2_core") {
            // trèfles et quadrilobes : couleurs alternées d'un secteur à l'autre
            // (et d'un niveau du remplage à l'autre) ; ceux posés sur un meneau
            // entre deux secteurs prennent la première couleur
            val a = ang - floor(ang / STEP + 0.5) * STEP
            if (abs(abs(a) - H) < 1e-3) return s.foil.first
            val lv = if (kind.startsWith("head_core")) kind.last().digitToInt() else 0
            val idx = if (alternate) (par + lv) % 2 else lv % 2
            return if (idx == 0) s.foil.first else s.foil.second
        }
        if (kind == "head_lune" || kind == "ros_lune") return s.lune
        if (kind == "arch_spandrel") return s.petal
        if (kind == "spandrel1") return s.innerSpandrel
        if (kind == "spandrel2") return s.outerSpandrel
        return s.plain
    }

    /**
     * Couleur d'une pièce de la rosace médiévale sobre (dessin A), d'après
     * une palette par rôles. Réglages validés sur le prototype (design_A2,
     * couronne enrichie, 1er nov. 2026) :
     * - centre : cœur de l'oculus IVOIRE (centre.first), lobes autour en
     *   rose très pâle (veryPale), pour éviter l'effet « marguerite » ;
     * - étoile (sous-lancettes) : middle, alterné d'un panneau et d'un
     *   secteur à l'autre ; pétales : accent ; fonds entre les grandes
     *   lancettes : deepBig (éclairci d'un cran) ;
     * - couronne enrichie : chaque lancette en trois bandes, middle.first au
     *   pied, puis outer.second ou accent (selon la parité du secteur), et
     *   outer.first en tête ; petits écoinçons du bord accent / deepBig ;
     * - trèfles : tous de la couleur gold de la palette ; lunules : deep ;
     * - pas de croix blanche dans les axes (jugée artificielle).
     */
    fun medievalColorOf(p: RosacePiece, pal: RosacePalette): ColorRGBa {
        val kind = p.kind
        val role = p.role
        val par = parity(p.angle)
        if (kind == "lancet2") {
            val c = p.polygon.centroid.coordinate
            val rr = hypot(c.x, c.y)
            return when {
                rr < 0.70 -> pal.middle.first
                rr < 0.80 -> if (par == 0) pal.outer.second else pal.accent
                else -> pal.outer.first
            }
        }
        if (kind == "spandrel2") return pal.overrides["spandrel2"] ?: (if (par == 0) pal.accent else pal.deepBig)
        if (role.startsWith("field")) {
            val t = role.last().digitToInt()
            return when (kind) {
                "arch_spandrel" -> if (t == 0) pal.accent else shade(pal.accent, 0.88)
                "spandrel1" -> if (t == 0) pal.deepBig else shade(pal.deepBig, 1.15)
                else -> if ((t + par) % 2 == 0) pal.middle.first else pal.middle.second
            }
        }
        if (role == "core") return pal.centre.first
        if (role == "oc_in") return pal.veryPale
        if (role == "oc_out") return pal.centre.second
        if (kind == "oc_spandrel") return pal.veryPale
        // trèfles : tous de la couleur gold de la palette (l'alternance or et
        // ivoire du prototype a été écartée le 2 nov. 2026, moins harmonieuse)
        if (kind.startsWith("head_core") || kind == "h2_core") return pal.gold
        if (kind == "arch_spandrel") return pal.accent
        if (kind == "spandrel1") return pal.deepBig
        return pal.deep   // lunules et autres pièces
    }

    /** Les pièces de verre (rayon 1, axe du secteur 0 sur l'angle 0).
     *  Déclarée en dernier : construite une fois tout le reste initialisé. */
    val pieces: List<RosacePiece> = build()
}