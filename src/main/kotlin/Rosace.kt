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
    val starPanelHeight: Double = 0.15,
    /** Épaisseur de la pierre, en multiple de celle du dessin validé (1,0).
     *  Moins de 1 : remplage plus fin (ornement en verre clair, où pierre et
     *  plombs sont d'une seule couleur). */
    val stoneScale: Double = 1.0
) : CompositionSystem {

    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide {
        val geometry = RosaceGeometry.of(rotations, sober = true, medallionScale = medallionScale, starPanelHeight = starPanelHeight, stoneScale = stoneScale)
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
     *    A et B désignent une lancette sur deux ;
     *  - dessin B1 : "almondA", "almondAShade", "almondB" (moitiés des grandes
     *    amandes), "small" (petites amandes) ; dessin B2 : "branchA",
     *    "branchB" (moitiés des branches de l'étoile), "branchAxis" (pièces
     *    entières centrées sur l'axe d'une branche), "branchAxisShade" (les
     *    mêmes, sur une branche tournée à l'opposé de la lumière), "branchRoot"
     *    (petits triangles centrés entre deux branches) ;
     *  - dessin C : "lancet" (lancettes des diagonales) et "panel" (panneaux
     *    des diagonales dans la couronne extérieure), tous deux de rôle acc ;
     *    "armEnd" (extrémités des bras de la croix, au-delà de l'anneau r3) ;
     *    "panelEnd" (bout des panneaux des diagonales, au bord) ;
     *  - dessin A : "lancet2.inner", "lancet2.middle", "lancet2.head" (trois
     *    bandes des grandes lancettes), "sub_lancet.field0" et ".field1"
     *    (panneaux intérieur et extérieur des pétales), "oc_lancet" (couronne
     *    autour de l'oculus), "spandrel1.inner", "spandrel1.outer",
     *    "arch_spandrel", "head_lune" (voir RosaceGeometry.medievalTag). */
    val overrides: Map<String, ColorRGBa> = emptyMap(),
    /** Palette du cœur (palette mariale, 16 nov. 2026) : si elle est donnée,
     *  chaque pièce glisse continûment de sa couleur dans cette palette-ci
     *  (bord) à sa couleur dans « inner » (cœur), selon la distance de son
     *  centre de gravité au centre de la rosace : couleur du bord au-delà de
     *  gradientStart, du cœur en deçà de gradientEnd, mélange en OKLab entre
     *  les deux (adouci en « smoothstep » si gradientSmooth). L'or n'est
     *  jamais mélangé. Null pour toutes les autres palettes. */
    val inner: RosacePalette? = null,
    val gradientStart: Double = 0.92,
    val gradientEnd: Double = 0.30,
    val gradientSmooth: Boolean = false,
    /** Lumière directionnelle (palette mariale) : écart de clarté OKLab, en
     *  centièmes (24 = ±0,24 aux bords), selon la position de la pièce par
     *  rapport à la lumière du rendu (haut gauche, GlassStyle.lightDirection) :
     *  plus clair en haut à gauche, plus sombre en bas à droite. Teinte et
     *  saturation inchangées ; l'or n'est jamais éclairé. 0 = aucune. */
    val lightAmount: Double = 0.0,
    /** Relèvement des sombres (palette noire de C2, 23 nov. 2026) : après la
     *  lumière directionnelle, les clartés OKLab sous 0,30 sont resserrées
     *  linéairement pour que 0,10 devienne cette valeur (0,30 et au-delà :
     *  inchangés). L'ordre des pièces et le dégradé sont conservés, en plus
     *  doux ; le plomb, opaque, reste plus sombre que tout le verre. Teinte et
     *  saturation inchangées ; l'or n'est pas concerné. null = aucun. */
    val lightnessFloor: Double? = null,
    /** La croix échappe à la lumière directionnelle (lightAmount) : elle se lit
     *  comme une structure unie sur un fond qui vibre (soleil noir, phase 16).
     *  Le relèvement des sombres s'applique toujours. false = comportement actuel. */
    val unlitCross: Boolean = false
) {
    /** Couleur d'un rôle, tel que les dessins B et C le notent sur leurs
     *  pièces (RosaceStyles.kt) : out0, out1, mid0, mid1, cen0, cen1, deep,
     *  deepBig, acc, gold, cross, veryPale. Un rôle peut porter une étiquette
     *  (« acc@ogeeB ») : la couleur de overrides[étiquette] l'emporte alors,
     *  si la palette en définit une ; sinon, celle du rôle. */
    fun colorOfRole(role: String): ColorRGBa {
        val at = role.indexOf('@')
        if (at < 0) return colorOfBaseRole(role)
        return overrideOf(role.substring(at + 1)) ?: colorOfBaseRole(role.substring(0, at))
    }

    /** Couleur imposée à une étiquette. Une étiquette peut porter des
     *  qualificatifs après « # » (« almondA#axis », « lancet2.head#lit ») :
     *  on cherche l'étiquette complète, puis sans son dernier qualificatif, et
     *  ainsi de suite. Les palettes sans clé « …#… » ne voient donc aucune
     *  différence. */
    fun overrideOf(tag: String): ColorRGBa? {
        var t = tag
        while (true) {
            overrides[t]?.let { return it }
            val h = t.lastIndexOf('#')
            if (h < 0) return null
            t = t.substring(0, h)
        }
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

    // MARIALE (bleu marial, dévotionnel : hommage à Marie, « couronnée
    // d'étoiles », Ap 12, 1) — « Majesté et lumière », VALIDÉE le 17 nov. 2026
    // pour A, B1 et B2 (briefs de l'autre IA, 14-17 nov.) : outremer au bord,
    // céruléen au cœur, glissement linéaire. Écartée : « demi-teintes
    // adoucies » (écart jugé à peine perceptible dans OpenRNDR). Lumière
    // directionnelle ±24 (OKLab, clarté seule ; peut-être à réduire), or
    // #DDB96A jamais éclairé, pierre et plombs de marialBase. Répartition :
    //  - A : couronne d'oculus et pétales intérieurs en bleu clair, pétales
    //    extérieurs et bas des grandes lancettes en bleu moyen, milieu en
    //    accent, têtes en outremer ; 12 cœurs et 24 trèfles dorés ; pour
    //    MARIAL_MAJESTE_A, couronne extérieure unie #2D4A9F (17 nov.) ;
    //  - B1 (« B1-A ») : grandes amandes bicolores (nacre / bleu moyen), unies
    //    de part et d'autre de chaque axe, contour bleu clair, 12 petites
    //    amandes dorées (les douze étoiles), médaillon nacré ;
    //  - B2 : branches de l'étoile bleu clair, rubans nacrés continus jusqu'à
    //    la pierre (petits triangles des croisements compris).
    // Ordre des 11 teintes : couronne, couronne 2, anneau, anneau clair,
    // centre, nacre, fonds, grands fonds, accent, or, très pâle.
    private val marialMajesteBord = listOf("#1D3088", "#2742A0", "#7AA4DA", "#ABC6EB", "#FBFBFC", "#E3EBF6", "#13205E", "#1A2C78", "#3757B2", "#DDB96A", "#EDF1F9")
    private val marialMajesteCoeur = listOf("#24548F", "#2E63A4", "#8DB7DF", "#BCD5EE", "#FCFBF8", "#E8EFF6", "#1A3B70", "#22498A", "#4C82C0", "#DDB96A", "#F1F4F8")

    /** Couronne extérieure de A d'une seule teinte (têtes des grandes lancettes
     *  et pointes du bord, 48 pièces ; trèfles dorés inchangés) : outremer
     *  #2D4A9F, retenu le 17 nov. 2026 pour MARIAL_MAJESTE_A. La même teinte au
     *  bord et au cœur, pour que le glissement ne la nuance pas : seule la
     *  lumière directionnelle la module. Supprime l'alternance des pointes. */
    private val marialCouronneA = "#2D4A9F"

    /** Base commune des palettes mariales (reprise d'Arctic Night, palette
     *  d'essai retirée le 17 nov. 2026) : seules la pierre #D9DAD6 et les plombs
     *  #262B33 servent telles quelles dans les palettes Majesté ;
     *  MARIAL_C en garde en outre les fonds #0D1B2A, l'accent #3F6699, l'or
     *  #D9BE7A et le très pâle #E8F1F8. */
    private val marialBase = RosacePalette(
        name = "Bleu marial (base)",
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

    private fun marial(name: String, k: List<String>, design: String, crown: String? = null): RosacePalette {
        val base = marialBase.copy(name = name,
            outer = c(k[0]) to c(k[1]), middle = c(k[2]) to c(k[3]), centre = c(k[4]) to c(k[5]),
            deep = c(k[6]), deepBig = c(k[7]), accent = c(k[8]), gold = c(k[9]), cross = c(k[4]), veryPale = c(k[10]))
        return when (design) {
            "A" -> base.copy(overrides = mapOf(
                "oc_lancet" to c(k[3]), "sub_lancet.field0" to c(k[3]), "sub_lancet.field1" to c(k[2]),
                "lancet2.inner" to c(k[2]), "lancet2.middle" to c(crown ?: k[8]), "lancet2.head" to c(crown ?: k[0]))
                    + (if (crown != null) mapOf("spandrel2" to c(crown)) else emptyMap()))
            "B1" -> base.copy(middle = c(k[3]) to c(k[3]), centre = c(k[5]) to c(k[5]), overrides = mapOf(
                "almondA" to c(k[5]), "almondAShade" to c(k[5]), "almondB" to c(k[2]),
                "almondA#axis" to c(k[2]), "almondAShade#axis" to c(k[2]), "almondB#axis" to c(k[5]),
                "small" to c(k[9]), "footA" to c(k[2]), "middleB" to c(k[2])))
            else -> base.copy(middle = c(k[2]) to c(k[5]), overrides = mapOf(
                "branchA" to c(k[3]), "branchB" to c(k[3]), "branchAxis" to c(k[3]), "branchAxisShade" to c(k[3]),
                "branchRoot" to c(k[5]), "footB" to c(k[3])))
        }
    }
    private fun marialPair(name: String, bord: List<String>, coeur: List<String>, design: String, start: Double, end: Double, smooth: Boolean,
                           crown: String? = null) =
        marial(name, bord, design, crown).copy(inner = marial("$name (cœur)", coeur, design, crown),
            gradientStart = start, gradientEnd = end, gradientSmooth = smooth, lightAmount = 24.0)

    val MARIAL_MAJESTE_A = marialPair("Bleu marial, majesté et lumière — A", marialMajesteBord, marialMajesteCoeur, "A", 0.92, 0.30, false, marialCouronneA)
    val MARIAL_MAJESTE_B1 = marialPair("Bleu marial, majesté et lumière — B1", marialMajesteBord, marialMajesteCoeur, "B1", 0.92, 0.30, false)
    val MARIAL_MAJESTE_B2 = marialPair("Bleu marial, majesté et lumière — B2", marialMajesteBord, marialMajesteCoeur, "B2", 0.92, 0.30, false)

    /** Bleu marial, C (À VALIDER) : croix bleu profond lumineux #24449E sur une rosace de
     *  bleus clairs, or qui rayonne de la croix (disque doré, « P4 »), cœur
     *  blanc, bord bleu moyen #4A78B8 ; sans lumière directionnelle. */
    val MARIAL_C = marialBase.copy(name = "Bleu marial — C",
        outer = c("#A8CDE5") to c("#A8CDE5"), middle = c("#DCEBF5") to c("#A8CDE5"),
        centre = c("#F8FAFC") to c("#D9BE7A"), deepBig = c("#4A78B8"), cross = c("#24449E"),
        overrides = mapOf("armEnd" to c("#24449E"), "diagonal" to c("#7DB0D3"), "panelEnd" to c("#DCEBF5"),
            "lancet" to c("#7DB0D3"), "panel" to c("#A8CDE5")))

    /** Rosace C2 « Croix fleurie » (dessin CrossFlowerRosaceCompositionSystem,
     *  briefs du 17 nov. 2026) : PROVISOIRE, pour juger le dessin ; aucun
     *  arbitrage de couleur. Couleurs de MARIAL_C, plus trois étiquettes du
     *  dessin : bande des pétales #7AA4DA et flamme #BCD5EE (teintes de la
     *  palette mariale) ; fond extérieur #8EBCDA (mélange OKLab à 60 % de
     *  #A8CDE5 vers #7DB0D3, « profondeur discrète vers le bord »).
     *  Lumière directionnelle 20 ; l'or n'est pas éclairé. Disque central
     *  (« host ») : ivoire chaud #F2E8CE, celui de la lecture « hostie » de
     *  la croix rayonnante (LiturgicalPalettes.CROSS_ARM_SHADES) ; au centre
     *  de la rosace, la lumière directionnelle ne le modifie pas. */
    val MARIAL_C2 = MARIAL_C.copy(name = "Bleu marial — C2 Croix fleurie (provisoire)", lightAmount = 20.0,
        overrides = MARIAL_C.overrides + mapOf("petal" to c("#7AA4DA"), "flame" to c("#BCD5EE"), "fieldOuter" to c("#8EBCDA"),
            "host" to c("#F2E8CE")))

    // PALETTES « ESTHÉTIQUES » (1er nov. 2026 ; Arctic Night
    // retirée le 17 nov.), ajustées à partir de bases
    // proposées par Laetitia : centre lumineux, pas de teinte grisée,
    // saturation modérée ; pierre et plombs choisis pour chaque palette.

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

    /** Vert, ivoire et or — validée le 2 nov. 2026, REMPLACÉE le 9 nov. 2026
     *  par VERT_A, VERT_B1, VERT_B2 et VERT_C (une palette par dessin) ;
     *  conservée pour mémoire, n'est plus proposée dans TemplateProgram.kt.
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

    /** Cathédrale — VALIDÉE le 3 nov. 2026 pour A, B1 et B2 : les teintes
     *  de RosaceSchemes.CATHEDRALE (rosace rayonnante), réparties sur les rôles.
     *  Couronne extérieure bleue, anneau intermédiaire rouge, trèfles et perles
     *  or, médaillons ivoire et or ; fonds bleu sombre, fond central des B
     *  d'un bleu plus clair. Pierre #C9C1B2 (celle de la rayonnante), plombs
     *  charbon. Pour C : CATHEDRALE_C. */
    val CATHEDRALE = RosacePalette(
        name = "Cathédrale",
        outer = c("#1E3F8F") to c("#24499C"),
        middle = c("#A3162A") to c("#8E1424"),
        centre = c("#E3B23C") to c("#A3162A"),
        deep = c("#1E3F8F"),
        deepBig = c("#1A2F6B"),
        accent = c("#2E5AAE"),
        gold = c("#E3B23C"),
        cross = c("#E9E2C8"),
        veryPale = c("#E9E2C8"),
        stone = c("#C9C1B2"),
        lead = c("#343436"),
        leadFine = c("#343436")
    )

    /** Cathédrale pour C : croix en or. En ivoire, une croix blanche sur du
     *  bleu et du rouge évoquait un drapeau (écartée le 3 nov. 2026). */
    val CATHEDRALE_C = CATHEDRALE.copy(
        name = "Cathédrale — C",
        cross = c("#E3B23C")
    )

    // ROUGE (Passion, Pentecôte, apôtres, martyrs) — « feu au centre », VALIDÉE
    // le 4 nov. 2026 (versions A2-a, B1-a, B2-a, C-a ; cinq itérations d'après
    // les briefs de l'autre IA). Une seule famille, une répartition par
    // dessin. Nuancier (11 teintes) : grenat #5C1220, carmin #7A1528, rubis
    // foncé #911820, rubis #A61B22, écarlate #C62A2A, écarlate clair #D0312D,
    // ambre #E39A35, ambre cuivré #C47A2C, or chaud #D9A441, or clair #E8BE5A,
    // ivoire chaud #F7E3B0 ; plus l'ivoire #F3EBDC du cœur de C (moins jaune).
    // Pierre #D2C6B2, plombs #2E2A2A. Aucune couleur d'écrin froide.
    private val rougeBase = RosacePalette(
        name = "Rouge, feu au centre",
        outer = c("#911820") to c("#A61B22"),
        middle = c("#C62A2A") to c("#7A1528"),
        centre = c("#F7E3B0") to c("#D9A441"),
        deep = c("#5C1220"),
        deepBig = c("#5C1220"),
        accent = c("#A61B22"),
        gold = c("#E8BE5A"),
        cross = c("#E39A35"),
        veryPale = c("#F7E3B0"),
        stone = c("#D2C6B2"),
        lead = c("#2E2A2A"),
        leadFine = c("#2E2A2A")
    )
    private val rougeRingB = mapOf("ogeeA" to c("#911820"), "ogeeB" to c("#911820"), "middleA" to c("#A61B22"),
        "middleB" to c("#A61B22"), "footA" to c("#D0312D"), "footB" to c("#D0312D"))

    /** Rouge, A (A2-a) : lancettes en dégradé (rubis vers le centre, carmin
     *  aux têtes), pétales écarlate puis rubis, couronne autour de l'oculus en
     *  ambre cuivré, oculus ivoire, ambre et or clair ; trèfles or clair. */
    val ROUGE_A = rougeBase.copy(
        name = "Rouge, feu au centre — A",
        centre = c("#F7E3B0") to c("#E39A35"),
        deep = c("#7A1528"),
        veryPale = c("#E8BE5A"),
        overrides = mapOf(
            "lancet2.inner" to c("#A61B22"), "lancet2.middle" to c("#911820"), "lancet2.head" to c("#7A1528"),
            "sub_lancet.field0" to c("#C62A2A"), "sub_lancet.field1" to c("#A61B22"), "oc_lancet" to c("#C47A2C"),
            "spandrel1.inner" to c("#7A1528"), "spandrel1.outer" to c("#5C1220"), "arch_spandrel" to c("#5C1220"),
            "head_lune" to c("#7A1528"), "spandrel2" to c("#5C1220"))
    )

    /** Rouge, B1 (B1-a) : moitiés d'amandes ambre et carmin, les amandes
     *  tournées vers l'ombre en ambre cuivré ; contour or chaud, fond rubis,
     *  médaillon ivoire et or clair. */
    val ROUGE_B1 = rougeBase.copy(
        name = "Rouge, feu au centre — B1",
        middle = c("#D9A441") to c("#7A1528"),
        centre = c("#E8BE5A") to c("#D9A441"),
        overrides = rougeRingB + mapOf(
            "almondA" to c("#E39A35"), "almondAShade" to c("#C47A2C"), "almondB" to c("#7A1528"), "small" to c("#7A1528"))
    )

    /** Rouge, B2 (B2-a) : moitiés des branches de l'étoile en ambre et ambre
     *  cuivré, rubans grenat, fond rubis, anneau du médaillon carmin (l'or ne
     *  touche pas l'ambre), médaillon ivoire et or clair. */
    val ROUGE_B2 = rougeBase.copy(
        name = "Rouge, feu au centre — B2",
        middle = c("#E39A35") to c("#5C1220"),
        centre = c("#F7E3B0") to c("#7A1528"),
        overrides = rougeRingB + mapOf("branchA" to c("#E39A35"), "branchB" to c("#C47A2C"), "branchAxis" to c("#C47A2C"), "branchAxisShade" to c("#C47A2C"), "branchRoot" to c("#E39A35"))
    )

    /** Rouge, C (C-a) : croix continue en ambre (extrémités comprises), cœur
     *  ivoire #F3EBDC (évocation discrète de l'hostie : les deux plombs fins
     *  y dessinent une croix), disque et abords de la croix en rouge profond,
     *  lancettes des diagonales écarlate, panneaux du bord en ambre cuivré. */
    val ROUGE_C = rougeBase.copy(
        name = "Rouge, feu au centre — C",
        outer = c("#A61B22") to c("#911820"),
        middle = c("#7A1528") to c("#7A1528"),
        centre = c("#F3EBDC") to c("#5C1220"),
        overrides = mapOf("armEnd" to c("#E39A35"), "panelEnd" to c("#C47A2C"), "lancet" to c("#C62A2A"), "panel" to c("#C47A2C"))
    )

    /** Rouge, C2 « Croix fleurie » (R3, validée provisoirement le 18 nov.
     *  2026) : croix grenat (structure, comme le bleu marial), calice et
     *  pointes or clair, pétales ambre cuivré, flammes ambre, fonds rubis puis
     *  rubis foncé, bordure grenat, hostie ivoire. Rôles du dessin
     *  (CrossFlowerRosaceCompositionSystem) : out0 fond intérieur, out1 fond
     *  extérieur, mid0 flamme, mid1 pétale, cen0 hostie, cen1 couronne et
     *  calice, gold pointes, cross, deepBig bordure. Toutes les teintes sont
     *  dans les rôles de base (aucune étiquette), pour que depuisRouge les
     *  transpose aux familles dérivées. */
    val ROUGE_C2 = rougeBase.copy(
        name = "Rouge, feu au centre — C2 Croix fleurie",
        outer = c("#A61B22") to c("#911820"),
        middle = c("#E39A35") to c("#C47A2C"),
        centre = c("#F3EBDC") to c("#E8BE5A"),
        cross = c("#5C1220"),
        deepBig = c("#5C1220")
    )

    // VIOLET (Avent, Carême) — VALIDÉE le 6 nov. 2026 (direction « V4 indigo »,
    // sept itérations d'après les briefs de l'autre IA ; finition « argent
    // contrasté »). Même répartition que la famille rouge, dessin par dessin :
    // chaque teinte du rouge est remplacée terme à terme par son équivalent
    // violet-indigo (famille froide, teintes 248–258°, aucune dérive rose,
    // rouge ou grenat). Le métal est de l'argent contrasté, commun à A, B1 et
    // B2 ; C n'a pas de métal. Pierre et plombs : ceux du rouge.
    /** Teintes de la famille rouge, dans l'ordre où les familles dérivées
     *  (violet, noir) les remplacent terme à terme : grenat, carmin, rubis
     *  foncé, rubis, écarlate, écarlate clair, ambre, ambre cuivré, or chaud,
     *  or clair, ivoire chaud, ivoire du cœur de C. */
    private val teintesRouges = listOf("#5C1220", "#7A1528", "#911820", "#A61B22", "#C62A2A", "#D0312D",
        "#E39A35", "#C47A2C", "#D9A441", "#E8BE5A", "#F7E3B0", "#F3EBDC").map { c(it) }

    /** Famille dérivée de la répartition rouge : chaque teinte rouge est
     *  remplacée par la teinte de même rang dans cibles (12 teintes) ; la
     *  pierre peut changer, les plombs restent ceux du rouge. */
    private fun depuisRouge(p: RosacePalette, name: String, cibles: List<String>, stone: ColorRGBa? = null): RosacePalette {
        require(cibles.size == teintesRouges.size)
        val vers = cibles.map { c(it) }
        fun m(x: ColorRGBa): ColorRGBa {
            val k = teintesRouges.indexOfFirst { abs(it.r - x.r) < 1e-4 && abs(it.g - x.g) < 1e-4 && abs(it.b - x.b) < 1e-4 }
            return if (k >= 0) vers[k] else x
        }
        return p.copy(
            name = name,
            outer = m(p.outer.first) to m(p.outer.second),
            middle = m(p.middle.first) to m(p.middle.second),
            centre = m(p.centre.first) to m(p.centre.second),
            deep = m(p.deep), deepBig = m(p.deepBig), accent = m(p.accent), gold = m(p.gold),
            cross = m(p.cross), veryPale = m(p.veryPale),
            stone = stone ?: p.stone,
            overrides = p.overrides.mapValues { (_, v) -> m(v) }
        )
    }

    /** Violet : nuit indigo, indigo profond, indigo sombre, indigo violacé,
     *  indigo moyen, indigo clair, lilas lumineux, lilas moyen, argent
     *  contrasté (base, reflet), ivoire froid, ivoire froid du cœur de C. */
    private val violets = listOf("#1B1733", "#2A2350", "#33295E", "#3F3270", "#574A8E", "#7D6FB0",
        "#C9BDE6", "#9C8FC8", "#83868E", "#CDCED2", "#EEEAF6", "#F2F0F7")
    private fun violetOf(p: RosacePalette, name: String) = depuisRouge(p, name, violets)

    /** Violet, A : lancettes en dégradé d'indigo, couronne de l'oculus lilas,
     *  trèfles et oculus en argent contrasté. */
    val VIOLET_A = violetOf(ROUGE_A, "Violet indigo et argent — A")
    /** Violet, B1 : moitiés d'amandes lilas et indigo profond, contour et
     *  médaillon en argent contrasté. */
    val VIOLET_B1 = violetOf(ROUGE_B1, "Violet indigo et argent — B1")
    /** Violet, B2 (B2-a) : branches de l'étoile en deux lilas proches,
     *  rubans nuit indigo, perles en argent. */
    val VIOLET_B2 = violetOf(ROUGE_B2, "Violet indigo et argent — B2")
    /** Violet, C : croix lilas lumineux, cœur ivoire froid, sans métal. */
    val VIOLET_C = violetOf(ROUGE_C, "Violet indigo — C")

    // NOIR (esthétique : monochrome, gothique, contemporain ; convient aussi aux
    // défunts et à la Toussaint) — VALIDÉE le 8 nov. 2026 : gris bleuté N3-b,
    // sans accent. Même répartition que la famille rouge, argent contrasté comme
    // métal (comme le violet), C sans métal. Écartée le même jour : N1-b, noir
    // et argent (noirs chauds, pierre #E6E1D8).
    /** N3-b, gris bleuté : gris graphite et ardoise froids (bleu discret),
     *  lumière gris perle froid, pierre #E0DBD2. */
    private val noirsBleutes = listOf("#15171A", "#202328", "#2A2D33", "#34383E", "#4F535A", "#696D75",
        "#DCDFE3", "#A9AEB5", "#83868E", "#CDCED2", "#F0F1F2", "#F2F3F4")
    val NOIR_BLEUTE_A = depuisRouge(ROUGE_A, "Gris bleuté (N3-b) — A", noirsBleutes, c("#E0DBD2"))
    val NOIR_BLEUTE_B1 = depuisRouge(ROUGE_B1, "Gris bleuté (N3-b) — B1", noirsBleutes, c("#E0DBD2"))
    val NOIR_BLEUTE_B2 = depuisRouge(ROUGE_B2, "Gris bleuté (N3-b) — B2", noirsBleutes, c("#E0DBD2"))
    val NOIR_BLEUTE_C = depuisRouge(ROUGE_C, "Gris bleuté (N3-b) — C", noirsBleutes, c("#E0DBD2"))

    // VERT (Temps ordinaire) — VALIDÉE le 9 nov. 2026, d'après les briefs de
    // l'autre IA : famille G1 « émeraude et forêt », même répartition que la
    // famille rouge (pierre et plombs du rouge), or pâle et ivoire en contraste.
    // Remplace VERT_IVOIRE_OR. Choix par dessin : A tel quel ; B1 « A2 »
    // (amandes émeraude et jade) ; B2 « B2-A » (branches jade clair du côté de
    // la lumière, émeraude du côté opposé) ; C « C1 » (croix vert pâle).
    /** G1 : forêt profonde, forêt, vert sombre, émeraude, émeraude moyen,
     *  vert moyen, vert pâle, vert clair, or (base, pâle), ivoire, ivoire du
     *  cœur de C. */
    private val vertsG1 = listOf("#0F2E22", "#154A35", "#1A5A40", "#1F6B4B", "#2E8A5E", "#47A274",
        "#A9D3A8", "#6FB08A", "#BE9C52", "#D9BC7A", "#F1EBDD", "#F3EFE3")
    private fun avec(p: RosacePalette, vararg o: Pair<String, String>) =
        p.copy(overrides = p.overrides + o.associate { (k, v) -> k to c(v) })
    val VERT_A = depuisRouge(ROUGE_A, "Vert émeraude et forêt (G1) — A", vertsG1)
    /** B1 : moitiés des grandes amandes en jade #459675 (ombre #3A7F63) et
     *  émeraude #1F6B4B ; contour or. */
    val VERT_B1 = avec(depuisRouge(ROUGE_B1, "Vert émeraude et forêt (G1) — B1", vertsG1),
        "almondA" to "#459675", "almondAShade" to "#3A7F63", "almondB" to "#1F6B4B")
    /** B2 : branches jade clair #8CC6A2 du côté de la lumière (et petits
     *  triangles de base), émeraude #4E9A70 du côté opposé. */
    val VERT_B2 = avec(depuisRouge(ROUGE_B2, "Vert émeraude et forêt (G1) — B2", vertsG1),
        "branchAxis" to "#8CC6A2", "branchAxisShade" to "#4E9A70", "branchRoot" to "#8CC6A2")
    /** C : croix vert pâle #98CC9E, extrémités comprises. */
    val VERT_C = avec(depuisRouge(ROUGE_C, "Vert émeraude et forêt (G1) — C", vertsG1), "armEnd" to "#98CC9E")
        .copy(cross = c("#98CC9E"))
    /** C2 « Croix fleurie » (V2, validée provisoirement le 18 nov. 2026) :
     *  transposition de ROUGE_C2, mais fonds plus clairs (émeraude moyen puis
     *  émeraude) et bordure forêt : croix forêt profonde #0F2E22, pétales vert
     *  clair #6FB08A, flammes vert pâle #A9D3A8, calice or pâle #D9BC7A,
     *  hostie ivoire #F3EFE3. */
    val VERT_C2 = depuisRouge(ROUGE_C2, "Vert émeraude et forêt (G1) — C2 Croix fleurie", vertsG1)
        .copy(outer = c("#2E8A5E") to c("#1F6B4B"), deepBig = c("#154A35"))

    // NOEL (blanc, ivoire et or) : placé après les familles dérivées du rouge,
    // car NOEL_B1 et NOEL_B2 en dépendent (ordre d'initialisation de l'objet).
    /** Blanc, ivoire et or : palettes de Noël des rosaces, VALIDÉES le 11 nov.
     *  2026 (briefs de l'autre IA, 10 et 11 nov.). Remplacent celles du 3 nov.
     *  (base blanc #FFFDF8, nacre froide #E9EEF1, ivoire #F2EBDD, champagne
     *  #E8D6AE, or #D0A64D, or ancien #A98543), dont la nacre froide donnait
     *  des zones « bleu glacier » dans A. Une seule palette blanche par dessin.
     *  Teintes : blanc #FFFFFF / #FFFDF8, ivoire #F5F1E7, ivoire soutenu
     *  #E7D9B9, or pâle doux #E6CF91, or ivoire lumineux #E8D6A6, champagne
     *  doux #DCCB9F, or champagne #E8BE5A, or soutenu #D9A441, or ombré
     *  #C99D4B, or intermédiaire #B88935 (trèfles de A). Pierre #D0C7B5,
     *  plombs #4A453D, contours fins #655F53 (inchangés depuis le 3 nov.).
     *  Principe : grandes surfaces blanches et ivoire, or réservé aux petites
     *  pièces et aux points focaux (croix de C, rubans de B2). */
    private val noelPierre = c("#D0C7B5")
    private val noelPlomb = c("#4A453D")
    private val noelPlombFin = c("#655F53")
    /** Répartition de la famille rouge (dessins B), teintes blanches : ivoire
     *  soutenu, or ombré, ivoire, blanc, ivoire soutenu ×2, or champagne ×4,
     *  blanc ×2 (même ordre que teintesRouges). */
    private fun noelB(contour: String = "#E8BE5A") = listOf("#E7D9B9", "#C99D4B", "#F5F1E7", "#FFFFFF", "#E7D9B9", "#E7D9B9",
        "#E8BE5A", "#E8BE5A", contour, "#E8BE5A", "#FFFFFF", "#FFFFFF")

    /** Noël, A (médiévale), « D » : centre A3 (cœur or champagne, six ronds
     *  or ivoire lumineux, anneau ivoire soutenu, pétales ivoire, douze cœurs
     *  des têtes en or soutenu, petites pointes or champagne) ; grandes
     *  surfaces blanches et ivoire, un peu plus claires du côté de la lumière
     *  (#lit) et un cran plus soutenues ailleurs ; trèfles de la bordure en or
     *  intermédiaire #B88935. */
    val NOEL_A = RosacePalette(
        name = "Blanc, ivoire et or (Noël) — A",
        outer = c("#F5F1E7") to c("#F5F1E7"),
        middle = c("#F5F1E7") to c("#F5F1E7"),
        centre = c("#E8BE5A") to c("#E7D9B9"),
        deep = c("#E8D6A6"),
        deepBig = c("#E6CF91"),
        accent = c("#F5F1E7"),
        gold = c("#B88935"),
        cross = c("#FFFFFF"),
        veryPale = c("#E8D6A6"),
        stone = noelPierre, lead = noelPlomb, leadFine = noelPlombFin,
        overrides = mapOf(
            "lancet2.inner" to c("#FFFFFF"), "lancet2.inner#shade" to c("#F5F1E7"),
            "lancet2.middle" to c("#F5F1E7"), "lancet2.middle#lit" to c("#FFFFFF"),
            "lancet2.head" to c("#E7D9B9"), "lancet2.head#lit" to c("#F5F1E7"),
            "spandrel2" to c("#DCCB9F"), "spandrel2#lit" to c("#E8D6A6"),
            "sub_lancet.field0" to c("#F5F1E7"), "sub_lancet.field0#lit" to c("#FFFFFF"),
            "sub_lancet.field1" to c("#E7D9B9"), "sub_lancet.field1#lit" to c("#F5F1E7"),
            "head_lune" to c("#E8D6A6"), "head_lune#lit" to c("#E6CF91"),
            "arch_spandrel" to c("#F5F1E7"), "arch_spandrel#lit" to c("#FFFFFF"),
            "spandrel1.outer" to c("#E6CF91"), "spandrel1.outer#lit" to c("#E8D6A6"),
            "spandrel1.inner" to c("#E8BE5A"),
            "oc_lancet" to c("#F5F1E7"),
            "head_core" to c("#D9A441"))
    )

    /** Noël, B1 (amandes), « C4 » et contour ivoire (B1-2) : moitiés des
     *  grandes amandes en or champagne (côté A) et ivoire soutenu (côté B) ;
     *  de part et d'autre de chaque axe, une amande entièrement or et une
     *  entièrement ivoire soutenu (qualificatif #axis) ; contour des amandes
     *  ivoire ; petites amandes or champagne ; centre or ivoire lumineux. */
    val NOEL_B1 = avec(depuisRouge(ROUGE_B1, "Blanc, ivoire et or (Noël) — B1", noelB("#F5F1E7"), noelPierre),
        "almondA" to "#E8BE5A", "almondAShade" to "#E8BE5A", "almondB" to "#E7D9B9",
        "almondA#axis" to "#E7D9B9", "almondAShade#axis" to "#E7D9B9", "almondB#axis" to "#E8BE5A",
        "small" to "#E8BE5A")
        .let { it.copy(centre = c("#E8D6A6") to it.centre.second, lead = noelPlomb, leadFine = noelPlombFin) }

    /** Noël, B2 (étoile), « B2-K » : branches ivoire #F5F1E7, fond de
     *  l'étoile blanc #FFFDF8, rubans or champagne continus (jusqu'à l'anneau,
     *  petits triangles des croisements compris), anneau or pâle doux,
     *  médaillon ivoire et étoile du médaillon or pâle doux. */
    val NOEL_B2 = avec(depuisRouge(ROUGE_B2, "Blanc, ivoire et or (Noël) — B2", noelB(), noelPierre),
        "branchA" to "#F5F1E7", "branchB" to "#F5F1E7", "branchAxis" to "#F5F1E7", "branchAxisShade" to "#F5F1E7",
        "branchRoot" to "#E8BE5A", "medallionStar" to "#E6CF91")
        .let { it.copy(middle = it.middle.first to c("#E8BE5A"), accent = c("#FFFDF8"), centre = it.centre.first to c("#E6CF91"),
            veryPale = c("#F5F1E7"), lead = noelPlomb, leadFine = noelPlombFin) }

    /** Noël, C (contemporaine), « C1 » : croix en or soutenu (extrémités
     *  comprises) sur un cœur blanc ; abords en or pâle doux, rond central
     *  ivoire ; diagonales ivoire soutenu de la base à la pointe, prolongées
     *  d'ivoire ; panneaux ivoire, bord blanc. */
    val NOEL_C = RosacePalette(
        name = "Blanc, ivoire et or (Noël) — C",
        outer = c("#E6CF91") to c("#E6CF91"),
        middle = c("#E6CF91") to c("#E6CF91"),
        centre = c("#FFFFFF") to c("#F5F1E7"),
        deep = c("#E7D9B9"),
        deepBig = c("#FFFFFF"),
        accent = c("#E7D9B9"),
        gold = c("#E8BE5A"),
        cross = c("#D9A441"),
        veryPale = c("#F5F1E7"),
        stone = noelPierre, lead = noelPlomb, leadFine = noelPlombFin,
        overrides = mapOf("armEnd" to c("#D9A441"), "panelEnd" to c("#FFFFFF"), "lancet" to c("#E7D9B9"),
            "panel" to c("#F5F1E7"), "diagonal" to c("#F5F1E7"))
    )

    /** Ornement en verre clair (76 mm, impression UV opaque ; VALIDÉ le 3 nov.
     *  2026) : seuls la pierre et les plombs comptent, les pièces restent vides
     *  (VitrailConfig.rosaceClearGlass). Pierre ET plombs du même ivoire, à la
     *  manière d'un verre sablé : un seul trait, pour un rendu plus moderne.
     *  Écartés : plombs gris-brun #6E675B (écart trop marqué avec la pierre),
     *  pierre claire #D0C7B5 (moins nette sur le sapin), anthracite (s'efface
     *  devant un fond sombre). */
    val VERRE_SABLE = NOEL_A.copy(
        name = "Verre sablé (ornement en verre clair)",
        stone = c("#F4EFE6"),
        lead = c("#F4EFE6"),
        leadFine = c("#F4EFE6")
    )

    /** Ornement en verre clair, trait rouge de Noël (4 nov. 2026) : se voit
     *  sur un sapin comme devant un mur clair, et en vignette. Écartés : #FF2400
     *  (trop criard), #C8102E et #B01E2D (trop sombres sur le vert du sapin). */
    val VERRE_ROUGE = VERRE_SABLE.copy(
        name = "Verre clair, trait rouge",
        stone = c("#D7261E"),
        lead = c("#D7261E"),
        leadFine = c("#D7261E")
    )

    /** Ornement en verre clair, trait « ton doré » (4 nov. 2026), pour un sapin
     *  décoré d'or. Ocre doré MAT : l'encre UV standard n'a pas de reflet
     *  métallique (fiches produits : « ton doré », pas « or »). */
    val VERRE_OR = VERRE_SABLE.copy(
        name = "Verre clair, trait ton doré",
        stone = c("#C9A23A"),
        lead = c("#C9A23A"),
        leadFine = c("#C9A23A")
    )

    /** Ornement en verre clair, trait « ton argenté » (4 nov. 2026), pour un
     *  sapin décoré d'argent. Gris froid MAT (pas de reflet métallique) ; très
     *  lisible sur un sapin ou du bleu nuit, il s'efface devant un mur clair. */
    val VERRE_ARGENT = VERRE_SABLE.copy(
        name = "Verre clair, trait ton argenté",
        stone = c("#C3C8CE"),
        lead = c("#C3C8CE"),
        leadFine = c("#C3C8CE")
    )

    val ALL = listOf(GRENAT_OR, CATHEDRALE, CATHEDRALE_C, EMERALD_DEPTHS, OCEAN_REVERIE, SUNSET_REVERIE, ORCHID_SHADOW, VERT_IVOIRE_OR, NOEL_A, NOEL_B1, NOEL_B2, NOEL_C, ROUGE_A, ROUGE_B1, ROUGE_B2, ROUGE_C, ROUGE_C2, VIOLET_A, VIOLET_B1, VIOLET_B2, VIOLET_C,
        NOIR_BLEUTE_A, NOIR_BLEUTE_B1, NOIR_BLEUTE_B2, NOIR_BLEUTE_C, VERT_A, VERT_B1, VERT_B2, VERT_C, VERT_C2,
        MARIAL_MAJESTE_A, MARIAL_MAJESTE_B1, MARIAL_MAJESTE_B2, MARIAL_C, MARIAL_C2)

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
            geometry == null -> rosacePieceColor(palette, p.polygon) { it.colorOfRole(p.role) }
            geometry.sober -> rosacePieceColor(palette, p.polygon) { geometry.medievalColorOf(p, it) }
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
fun drawRosaceLeadLines(drawer: Drawer, guide: RosaceGuide, offset: Vector2, leadColor: ColorRGBa, leadWidth: Double,
                        minFactor: Double = 0.0) {
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
            drawer.strokeWeight = leadWidth * maxOf(ln.factor, minFactor)
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
    val starPanelHeight: Double,
    /** Épaisseur de la pierre, en multiple de celle du prototype (1,0). */
    val stoneScale: Double
) {

    companion object {
        private data class Key(val rotations: Int, val sober: Boolean, val medallionScale: Double, val starPanelHeight: Double, val stoneScale: Double)
        private val cache = mutableMapOf<Key, RosaceGeometry>()

        /** Géométrie (mise en cache) pour ce nombre de rotations et ce style.
         *  medallionScale et starPanelHeight ne servent qu'à la rosace sobre.
         *  stoneScale amincit (< 1) ou épaissit la pierre ; 1,0 = toutes les
         *  rosaces existantes (géométrie inchangée). */
        fun of(rotations: Int, sober: Boolean = false, medallionScale: Double = 1.0, starPanelHeight: Double = 0.09, stoneScale: Double = 1.0): RosaceGeometry = synchronized(cache) {
            val key = if (sober) Key(rotations, true, medallionScale, starPanelHeight, stoneScale) else Key(rotations, false, 1.0, 0.09, stoneScale)
            cache.getOrPut(key) { RosaceGeometry(key.rotations, key.sober, key.medallionScale, key.starPanelHeight, key.stoneScale) }
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
        W1 = 0.021 * ws * stoneScale; W2 = 0.013 * ws * stoneScale; W3 = 0.008 * ws * stoneScale
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
    /** Étiquette d'une pièce du dessin A pour RosacePalette.overrides ; null
     *  pour les pièces sans étiquette (oculus, trèfles). */
    fun medievalTag(p: RosacePiece): String? {
        val c = p.polygon.centroid.coordinate
        val rr = hypot(c.x, c.y)
        return when (p.kind) {
            "lancet2" -> if (rr < 0.70) "lancet2.inner" else if (rr < 0.82) "lancet2.middle" else "lancet2.head"
            "sub_lancet" -> "sub_lancet." + p.role
            "oc_lancet", "arch_spandrel", "head_lune", "spandrel2" -> p.kind
            "spandrel1" -> if (rr < 0.4) "spandrel1.inner" else "spandrel1.outer"
            "h2_core" -> "h2_core"
            else -> if (p.kind.startsWith("head_core")) "head_core" else null
        }
    }

    /** Qualificatif de lumière d'une pièce du dessin A : « #lit » si la pièce
     *  est tournée vers la lumière du rendu (haut gauche, GlassStyle.lightDirection ;
     *  orientation par défaut de la rosace, −90°), « #shade » à l'opposé, rien
     *  entre les deux (seuil ±0,6). Utilisé par NOEL_A (11 nov. 2026). */
    private fun lightQualifier(p: RosacePiece): String {
        val c = p.polygon.centroid.coordinate
        val a = atan2(c.y, c.x)
        val t = (cos(a) - 0.35 * sin(a)) / hypot(1.0, 0.35)
        return if (t > 0.6) "#lit" else if (t < -0.6) "#shade" else ""
    }

    fun medievalColorOf(p: RosacePiece, pal: RosacePalette): ColorRGBa {
        val kind = p.kind
        val role = p.role
        val par = parity(p.angle)
        // couleurs imposées par étiquette (palettes qui en définissent ;
        // "spandrel2" est traité plus bas, comme avant)
        if (pal.overrides.isNotEmpty() && kind != "spandrel2") medievalTag(p)?.let { t -> pal.overrideOf(t + lightQualifier(p))?.let { return it } }
        if (kind == "lancet2") {
            val c = p.polygon.centroid.coordinate
            val rr = hypot(c.x, c.y)
            return when {
                rr < 0.70 -> pal.middle.first
                rr < 0.80 -> if (par == 0) pal.outer.second else pal.accent
                else -> pal.outer.first
            }
        }
        if (kind == "spandrel2") return pal.overrideOf("spandrel2" + lightQualifier(p)) ?: (if (par == 0) pal.accent else pal.deepBig)
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

// ---------------------------------------------------------------------------
// Dégradé bord → cœur et lumière directionnelle (palette mariale, 16 nov. 2026)
// ---------------------------------------------------------------------------

/** Couleur d'une pièce : couleur de base (colorIn appliquée à la palette),
 *  puis, si la palette le demande, glissement vers la palette du cœur et
 *  lumière directionnelle (voir RosacePalette.inner et .lightAmount). Pour
 *  toutes les autres palettes, renvoie exactement colorIn(palette). */
fun rosacePieceColor(palette: RosacePalette, polygon: Polygon, colorIn: (RosacePalette) -> ColorRGBa): ColorRGBa {
    var col = colorIn(palette)
    val inner = palette.inner
    if (inner == null && palette.lightAmount == 0.0 && palette.lightnessFloor == null) return col
    if (col == palette.gold) return col
    val c = polygon.centroid.coordinate
    if (inner != null) {
        val r = hypot(c.x, c.y)
        var w = ((palette.gradientStart - r) / (palette.gradientStart - palette.gradientEnd)).coerceIn(0.0, 1.0)
        if (palette.gradientSmooth) w = w * w * (3 - 2 * w)
        col = OkLab.mix(col, colorIn(inner), w)
    }
    if (palette.lightAmount != 0.0 && !(palette.unlitCross && col == palette.cross)) {
        val k = hypot(1.0, 0.35)
        val s = c.x / k - 0.35 * c.y / k            // −1 (bas droite) … +1 (haut gauche)
        col = OkLab.lighten(col, palette.lightAmount * s / 100.0)
    }
    palette.lightnessFloor?.let { f ->
        val lab = OkLab.toLab(col)
        val knee = 0.30
        if (lab[0] < knee) {
            val l = knee - (knee - lab[0]) * (knee - f) / (knee - 0.10)
            col = OkLab.fromLab(l, lab[1], lab[2])
        }
    }
    return col
}

/** Texture du verre d'une pièce de rosace, en multiple de celle de la
 *  recette (GlassStyle.textureStrength et streakStrength) : 1,0 partout, sauf
 *  le disque « hostie » (étiquette « host », rosace C2), dont le verre doit
 *  rester presque uni (brief du 17 nov. 2026 au soir). Voir Renderer.kt. */
const val HOST_TEXTURE_SCALE = 0.3

fun rosaceTextureScale(role: String): Double =
    if (role.substringAfter('@', "").substringBefore('#') == "host") HOST_TEXTURE_SCALE else 1.0

/** Conversions sRGB ↔ OKLab (Björn Ottosson, 2020). Les couleurs produites
 *  sont arrondies à 8 bits, comme les aperçus qui ont servi au choix. */
object OkLab {
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    private fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
    private fun hex8(c: ColorRGBa) = ColorRGBa.fromHex("#%02X%02X%02X".format(
        (c.r * 255).roundToInt(), (c.g * 255).roundToInt(), (c.b * 255).roundToInt()))
    fun toLab(col: ColorRGBa): DoubleArray {
        val q = hex8(col)
        val r = lin(q.r); val g = lin(q.g); val b = lin(q.b)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }
    fun fromLab(L: Double, a: Double, bb: Double): ColorRGBa {
        val l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * bb, 3.0)
        val m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * bb, 3.0)
        val s = Math.pow(L - 0.0894841775 * a - 1.2914855480 * bb, 3.0)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        fun to8(v: Double) = (gam(max(0.0, v)).coerceIn(0.0, 1.0) * 255).roundToInt()
        return ColorRGBa.fromHex("#%02X%02X%02X".format(to8(r), to8(g), to8(b)))
    }
    fun mix(a: ColorRGBa, b: ColorRGBa, w: Double): ColorRGBa {
        val x = toLab(a); val y = toLab(b)
        return fromLab(x[0] * (1 - w) + y[0] * w, x[1] * (1 - w) + y[1] * w, x[2] * (1 - w) + y[2] * w)
    }
    fun lighten(col: ColorRGBa, dL: Double): ColorRGBa {
        val x = toLab(col)
        return fromLab((x[0] + dL).coerceIn(0.0, 1.0), x[1], x[2])
    }
}