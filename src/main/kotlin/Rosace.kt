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

/** La rosace découpée, en coordonnées du canevas. */
class RosaceGuide(
    /** Les pièces de verre, en pixels du cadre (une cellule par pièce). */
    val pieces: List<List<Vector2>>,
    /** La géométrie d'origine (en unités) : sert au calcul des couleurs. */
    val geometry: RosaceGeometry,
    /** Les mêmes pièces en unités, dans le même ordre que pieces. */
    val unitPieces: List<RosacePiece>,
    /** Centre de la rosace, en pixels du cadre. */
    val center: Vector2,
    /** Rayon extérieur de la pierre, en pixels du cadre. */
    val radius: Double
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
        val geometry = RosaceGeometry.of(rotations)
        val center = Vector2(width / 2.0, height / 2.0)
        val radius = 0.5 * min(width, height) * sizeRatio
        val o = orientationDegrees * PI / 180.0
        val co = cos(o)
        val so = sin(o)
        val pieces = geometry.pieces.map { piece ->
            val coords = piece.polygon.exteriorRing.coordinates
            // Anneau fermé : le dernier point répète le premier.
            (0 until coords.size - 1).map { k ->
                val u = coords[k].x
                val v = coords[k].y
                Vector2(center.x + radius * (u * co - v * so), center.y + radius * (u * so + v * co))
            }
        }
        return RosaceGuide(pieces, geometry, geometry.pieces, center, radius)
    }
}

/** Les pièces de la rosace, telles que construites (une par cellule). */
class RosaceSegmentationSystem : SegmentationSystem {
    override fun segment(guide: CompositionGuide, width: Double, height: Double, random: Random): List<List<Vector2>> {
        require(guide is RosaceGuide) { "RosaceSegmentationSystem nécessite un RosaceGuide (RosaceCompositionSystem)" }
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
     *  Toutes les teintes sont nouvelles. Essais de roses plus riches : ROSE_RICHE_1 à
     *  ROSE_RICHE_4 (plus bas). Versions précédentes, jugées « rose bonbon » puis « vieillottes » :
     *  #A84D6B / #D47A93, puis vieux rose #8C4F6C et vert sauge #4F7F6E. */
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

    // ROSES RICHES (28 oct. 2026). Les essais rose poudré et aubergine (retirés)
    // paraissaient froids et réduits à deux couleurs. Ici : des roses CHAUDS
    // (tirant vers la pêche ou l'abricot, ni froids ni grisés), deux teintes
    // différentes qui alternent dans l'anneau extérieur, l'aubergine pour
    // l'étoile et les fonds, et une TROISIÈME couleur d'accompagnement (miel,
    // bleu canard, abricot ou vert olive) dans les petits écoinçons et les
    // bordures ; trèfles miel (#E7B04F) et ivoire rosé. Pierre crème
    // (#E6DCCB) par défaut. Toutes les teintes sont nouvelles.

    /** Rose riche 1 — rose pêche, aubergine et miel : deux roses pêche alternés (#E9B3A9, #E2A3A0), étoile aubergine (#7A2A5E, #5E1F4A), petits écoinçons et bordures miel (#D9A45A), trèfles miel et ivoire rosé. */
    val ROSE_RICHE_1 = RosaceScheme(
        name = "Rose, aubergine et miel",
        outerField = c("#E9B3A9") to c("#E2A3A0"),
        innerField = c("#7A2A5E") to c("#5E1F4A"),
        fillet = c("#F8E6DC") to c("#D9A45A"),
        core = c("#E7B04F"),
        oculusInner = c("#E9B3A9"),
        oculusOuter = c("#5E1F4A"),
        innerSpandrel = c("#3E1236"),
        outerSpandrel = c("#D9A45A"),
        foil = c("#E7B04F") to c("#FBEDE6"),
        lune = c("#3E1236"),
        petal = c("#F1CFC2"),
        plain = c("#3E1236"),
        stone = c("#E6DCCB")
    )

    /** Rose riche 2 — rose, aubergine et bleu canard : roses chauds alternés (#E6A9A6, #ECBDB3), étoile aubergine, une touche de bleu canard (#2F6F73) dans les petits écoinçons et les bordures. */
    val ROSE_RICHE_2 = RosaceScheme(
        name = "Rose, aubergine et canard",
        outerField = c("#E6A9A6") to c("#ECBDB3"),
        innerField = c("#6E2358") to c("#561C46"),
        fillet = c("#FBEDE6") to c("#2F6F73"),
        core = c("#E7B04F"),
        oculusInner = c("#E6A9A6"),
        oculusOuter = c("#561C46"),
        innerSpandrel = c("#3A1232"),
        outerSpandrel = c("#2F6F73"),
        foil = c("#E7B04F") to c("#FBEDE6"),
        lune = c("#3A1232"),
        petal = c("#F3D2C8"),
        plain = c("#3A1232"),
        stone = c("#E6DCCB")
    )

    /** Rose riche 3 — rose, abricot et aubergine : un rose (#EBAFB1) et un abricot (#F0BC98) alternés dans l'anneau extérieur, étoile aubergine, écoinçons abricot soutenu (#D98E6B). */
    val ROSE_RICHE_3 = RosaceScheme(
        name = "Rose, abricot et aubergine",
        outerField = c("#EBAFB1") to c("#F0BC98"),
        innerField = c("#7D2C5F") to c("#62224B"),
        fillet = c("#FBEDE6") to c("#7D2C5F"),
        core = c("#E9B05A"),
        oculusInner = c("#EBAFB1"),
        oculusOuter = c("#62224B"),
        innerSpandrel = c("#3E1236"),
        outerSpandrel = c("#D98E6B"),
        foil = c("#E9B05A") to c("#F7DED6"),
        lune = c("#3E1236"),
        petal = c("#F7DED6"),
        plain = c("#3E1236"),
        stone = c("#E6DCCB")
    )

    /** Rose riche 4 — rose, aubergine et vert olive : roses pêche alternés, étoile aubergine, une touche de vert olive (#5E6B3A) dans les petits écoinçons et les bordures. */
    val ROSE_RICHE_4 = RosaceScheme(
        name = "Rose, aubergine et olive",
        outerField = c("#E9B3A9") to c("#ECBDB3"),
        innerField = c("#7A2A5E") to c("#5E1F4A"),
        fillet = c("#FBEDE6") to c("#5E6B3A"),
        core = c("#E7B04F"),
        oculusInner = c("#E9B3A9"),
        oculusOuter = c("#5E1F4A"),
        innerSpandrel = c("#3E1236"),
        outerSpandrel = c("#5E6B3A"),
        foil = c("#E7B04F") to c("#FBEDE6"),
        lune = c("#3E1236"),
        petal = c("#F1CFC2"),
        plain = c("#3E1236"),
        stone = c("#E6DCCB")
    )

    // ROSES DE LA JOIE (28 oct. 2026). Gaudete et Laetare : le rose dit la joie
    // au cœur d'un temps de pénitence et d'attente ; un centre sombre (prune,
    // aubergine) la contredit. Ici, le CENTRE EST CLAIR : l'étoile centrale
    // alterne rose pâle et ivoire (ou ivoire et champagne), les pétales sont
    // roses, et la couleur profonde est réduite aux fonds entre les grandes
    // lancettes (4 % du verre). Anneau extérieur rose et rose pâle, accent
    // sauge dans les petits écoinçons et les lunules, trèfles et cœur laiton
    // doux. Pierre crème, plombs charbon doux (#292B2B).

    /** rose #D69AA7, rose pâle #EBD2D5, prune clair #8F6A86 (fonds seulement), sauge #758E87, ivoire #E8DFD0, laiton doux #C2A36B : la « version 5 ajustée ». */
    val ROSE_JOIE = RosaceScheme(
        name = "Joie — rose, sauge et laiton",
        outerField = c("#D69AA7") to c("#EBD2D5"),
        innerField = c("#EBD2D5") to c("#E8DFD0"),
        fillet = c("#E8DFD0") to c("#D69AA7"),
        core = c("#C2A36B"),
        oculusInner = c("#EBD2D5"),
        oculusOuter = c("#D69AA7"),
        innerSpandrel = c("#8F6A86"),
        outerSpandrel = c("#758E87"),
        foil = c("#C2A36B") to c("#E8DFD0"),
        lune = c("#758E87"),
        petal = c("#D69AA7"),
        plain = c("#8F6A86"),
        stone = c("#E6DCCB"),
        lead = c("#292B2B")
    )

    /** les teintes de ROSE_SAUGE (rose poudré #D9A3AE, porcelaine #EAD0D1, sauge #78918A, ivoire #E6DCC8), la prune sourde #75465F réduite aux fonds, trèfles laiton #C2A36B. */
    val ROSE_JOIE_POUDRE = RosaceScheme(
        name = "Joie — rose poudré et sauge",
        outerField = c("#D9A3AE") to c("#EAD0D1"),
        innerField = c("#EAD0D1") to c("#E6DCC8"),
        fillet = c("#E6DCC8") to c("#D9A3AE"),
        core = c("#C2A36B"),
        oculusInner = c("#EAD0D1"),
        oculusOuter = c("#D9A3AE"),
        innerSpandrel = c("#75465F"),
        outerSpandrel = c("#78918A"),
        foil = c("#C2A36B") to c("#E6DCC8"),
        lune = c("#78918A"),
        petal = c("#D9A3AE"),
        plain = c("#75465F"),
        stone = c("#E6DCCB"),
        lead = c("#292B2B")
    )

    /** comme ROSE_JOIE, mais l'étoile centrale en ivoire et champagne (#F3E9D6, #EAD9B4), une « gloire » lumineuse. */
    val ROSE_JOIE_GLOIRE = RosaceScheme(
        name = "Joie — gloire",
        outerField = c("#D69AA7") to c("#EBD2D5"),
        innerField = c("#F3E9D6") to c("#EAD9B4"),
        fillet = c("#E8DFD0") to c("#D69AA7"),
        core = c("#C2A36B"),
        oculusInner = c("#EBD2D5"),
        oculusOuter = c("#D69AA7"),
        innerSpandrel = c("#8F6A86"),
        outerSpandrel = c("#758E87"),
        foil = c("#C2A36B") to c("#EBD2D5"),
        lune = c("#758E87"),
        petal = c("#D69AA7"),
        plain = c("#8F6A86"),
        stone = c("#E6DCCB"),
        lead = c("#292B2B")
    )

    // PALETTES PROPOSÉES PAR ASTREA (28 oct. 2026), avec leur répartition
    // conseillée : environ 50 % de clairs (rose pâle, ivoire), 25 % du rose
    // principal, 15 % de la couleur profonde, 10 % de l'accent. Réalisée ainsi
    // (surfaces de verre mesurées, 12 rotations) : rose principal et rose pâle
    // en alternance dans l'anneau extérieur, rose pâle et ivoire en alternance
    // dans les bordures, rose principal dans les pétales ; couleur profonde
    // dans l'étoile centrale et les fonds ; accent dans les petits écoinçons
    // et un trèfle sur deux. Résultat : clairs 46 à 48 %, rose 23 à 28 %,
    // profond 15 à 20 %, accent 9 %. Teintes dérivées (alternance des
    // losanges) : variante 12 % plus sombre de la couleur profonde. Pierre
    // crème ; plombs de la couleur indiquée (voir RosaceScheme.lead).

    /** rose poudré #D9A3AE, rose porcelaine #EAD0D1, prune sourde #75465F, vert sauge #78918A, ivoire chaud #E6DCC8, plombs charbon doux #292B2B. */
    val ROSE_SAUGE = RosaceScheme(
        name = "Rose, prune et sauge",
        outerField = c("#D9A3AE") to c("#EAD0D1"),
        innerField = c("#75465F") to c("#673E54"),
        fillet = c("#E6DCC8") to c("#EAD0D1"),
        core = c("#E6DCC8"),
        oculusInner = c("#D9A3AE"),
        oculusOuter = c("#75465F"),
        innerSpandrel = c("#75465F"),
        outerSpandrel = c("#78918A"),
        foil = c("#E6DCC8") to c("#78918A"),
        lune = c("#75465F"),
        petal = c("#D9A3AE"),
        plain = c("#75465F"),
        stone = c("#E6DCCB"),
        lead = c("#292B2B")
    )

    /** rose argile #C78389, rose nude #E7C2BF, bordeaux prune #762F49, bleu pétrole #285B60, sable clair #D6C5A8, plombs anthracite #303033. */
    val ROSE_PETROLE = RosaceScheme(
        name = "Rose, bordeaux et pétrole",
        outerField = c("#C78389") to c("#E7C2BF"),
        innerField = c("#762F49") to c("#682940"),
        fillet = c("#D6C5A8") to c("#E7C2BF"),
        core = c("#D6C5A8"),
        oculusInner = c("#C78389"),
        oculusOuter = c("#762F49"),
        innerSpandrel = c("#762F49"),
        outerSpandrel = c("#285B60"),
        foil = c("#D6C5A8") to c("#285B60"),
        lune = c("#762F49"),
        petal = c("#C78389"),
        plain = c("#762F49"),
        stone = c("#E6DCCB"),
        lead = c("#303033")
    )

    /** rose brume #D5AAB8, rose très pâle #F0DDE0, lilas grisé #9B87A6 (en alternance avec le rose brume dans l'étoile), bleu ardoise #708596, gris coquille #E9E4DF, prune charbon #37343C (fonds et plombs). */
    val ROSE_ARDOISE = RosaceScheme(
        name = "Rose, lilas et ardoise",
        outerField = c("#D5AAB8") to c("#F0DDE0"),
        innerField = c("#9B87A6") to c("#D5AAB8"),
        fillet = c("#E9E4DF") to c("#F0DDE0"),
        core = c("#E9E4DF"),
        oculusInner = c("#D5AAB8"),
        oculusOuter = c("#9B87A6"),
        innerSpandrel = c("#37343C"),
        outerSpandrel = c("#708596"),
        foil = c("#E9E4DF") to c("#708596"),
        lune = c("#37343C"),
        petal = c("#D5AAB8"),
        plain = c("#37343C"),
        stone = c("#E6DCCB"),
        lead = c("#37343C")
    )

    /** rose minéral #C9878C, rose coquillage #E8C9BE, terracotta sourde #A65D55, olive grisé #777C57, lin clair #E6D8BF, brun charbon #34302E (fonds et plombs). */
    val ROSE_OLIVE = RosaceScheme(
        name = "Rose, terracotta et olive",
        outerField = c("#C9878C") to c("#E8C9BE"),
        innerField = c("#A65D55") to c("#92524B"),
        fillet = c("#E6D8BF") to c("#E8C9BE"),
        core = c("#E6D8BF"),
        oculusInner = c("#C9878C"),
        oculusOuter = c("#A65D55"),
        innerSpandrel = c("#34302E"),
        outerSpandrel = c("#777C57"),
        foil = c("#E6D8BF") to c("#777C57"),
        lune = c("#34302E"),
        petal = c("#C9878C"),
        plain = c("#34302E"),
        stone = c("#E6DCCB"),
        lead = c("#34302E")
    )

    /** rose #D69AA7, rose pâle #EBD2D5, prune #70445C, sauge #758E87, ivoire #E8DFD0, laiton doux #C2A36B (trèfles et cœur) ; plombs noirs. */
    val ROSE_LAITON = RosaceScheme(
        name = "Rose, prune, sauge et laiton",
        outerField = c("#D69AA7") to c("#EBD2D5"),
        innerField = c("#70445C") to c("#633C51"),
        fillet = c("#E8DFD0") to c("#EBD2D5"),
        core = c("#C2A36B"),
        oculusInner = c("#D69AA7"),
        oculusOuter = c("#70445C"),
        innerSpandrel = c("#70445C"),
        outerSpandrel = c("#758E87"),
        foil = c("#C2A36B") to c("#758E87"),
        lune = c("#70445C"),
        petal = c("#D69AA7"),
        plain = c("#70445C"),
        stone = c("#E6DCCB")
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

/**
 * Couleurs de la rosace : chaque pièce prend la couleur de son type et de
 * sa position (voir RosaceGeometry.colorOf). Remplace les couleurs tirées
 * par le PaletteSystem ; ne consomme AUCUN tirage aléatoire.
 */
fun applyRosaceColors(guide: RosaceGuide, cellColors: MutableList<ColorRGBa>, scheme: RosaceScheme) {
    for (i in cellColors.indices) {
        val p = guide.unitPieces[i]
        cellColors[i] = guide.geometry.colorOf(p.kind, p.role, p.angle, scheme)
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
fun drawRosaceStone(drawer: Drawer, guide: RosaceGuide, offset: Vector2, scheme: RosaceScheme) {
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.stroke = null
        drawer.fill = scheme.stone
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
 * Géométrie d'une rosace à `rotations` secteurs, en unités (rayon 1).
 * Calculée une seule fois par nombre de rotations (voir of()).
 */
class RosaceGeometry private constructor(val rotations: Int) {

    companion object {
        private val cache = mutableMapOf<Int, RosaceGeometry>()

        /** Géométrie (mise en cache) pour ce nombre de rotations. */
        fun of(rotations: Int): RosaceGeometry = synchronized(cache) {
            cache.getOrPut(rotations) { RosaceGeometry(rotations) }
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
        R_OC = clamp(0.040 / sin(H), 0.15, 0.36)
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
            R_CORE = min(0.45 * R_OC, ocS - 0.03)
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
            l.add(line(listOf(polar(R_MID, right), polar(spr3, right))) to (if (i == m2 - 1) W1 else W2))
            val (hd, _) = arch(polar(spr3, ci - h2), polar(spr3, right), 0.9)
            l.add(line(hd) to W2)
            l.add(foilOutline(tref2(ci), 0.177 * chord2, 3, ci) to W3)
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
            out.add(Label(polar(rosR, ci + h2), "ros_core", ci + h2))
        }
        out.add(Label(polar(rosR, -H), "ros_core", -H))
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
        for (ax in lancet2Axes.map { it + h2 } + listOf(-H)) {
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

    private fun oculusPieces(op: Polygon): List<Pair<Polygon, String>> {
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
            val base: List<Pair<Polygon, String>> = if (spacing != null) {
                val fillet = if (rep.kind == "sub_lancet" || rep.kind == "lancet2") 0.012 else 0.0
                lancetPieces(rep.g, rep.axis, spacing, fillet)
            } else {
                listOf(rep.g to "plain")
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

    /** Les pièces de verre (rayon 1, axe du secteur 0 sur l'angle 0).
     *  Déclarée en dernier : construite une fois tout le reste initialisé. */
    val pieces: List<RosacePiece> = build()
}