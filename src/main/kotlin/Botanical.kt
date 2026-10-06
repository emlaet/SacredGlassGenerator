import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ==================================================
// FAMILLE « VÉGÉTAL » — motif raccordable tout en courbes
// ==================================================
//
// Portage du prototype Python (documents du projet :
// claude/prototype_organique_galets_vegetal.py et
// claude/prototype_fleurs_tulipe_coquelicot.py).
//
// Principe : pas de découpe au hasard. On pose d'abord des MOTIFS
// DESSINÉS, puis on découpe le fond par des PONTS.
//
// 1. Motifs : une tige ondulante (bande de verre périodique, coupée en
//    tronçons), des feuilles en alternance le long de la tige (en
//    amande à nervure, lancéolées ou rondes), des fleurs (tulipes,
//    coquelicots, parfois une rosace) et des boutons, reliés à la tige
//    par un pédoncule. Tulipes et boutons poussent toujours vers le haut.
// 2. Ponts : courbes de Bézier qui relient deux motifs voisins en
//    partant de chacun À ANGLE DROIT (tangentes d'extrémité = normales
//    des deux bords), comme le ferait un verrier. On pose tous les ponts
//    possibles (réseau planaire), puis on en retire au hasard tant que
//    les pièces de fond restent sous une taille cible : de grandes
//    pièces, peu nombreuses.
// 3. Les pièces sont les faces de l'arrangement de toutes ces lignes
//    (polygonizeSegments, Arrangement.kt), sur le domaine étendu
//    [-W, 2W] × [-H, 2H] ; on garde les pièces « canoniques » (point
//    intérieur dans la tuile). renderVitrail les dessine aux 9 positions.
//
// Reproductibilité ET indépendance vis-à-vis de l'échelle : toute la
// géométrie est calculée dans un repère de référence de hauteur
// BOTANICAL_REF_HEIGHT (600 unités), puis mise à l'échelle à la toute
// fin. L'aperçu et l'export font donc exactement les mêmes calculs et
// les mêmes tirages aléatoires.
//
// ⚠ Toutes les arêtes sont de petits segments (courbes échantillonnées) :
// cette famille s'utilise avec curvatureAmount = 0 et des plombs à
// extrémités rondes (voir la recette dans TemplateProgram.kt).

const val BOTANICAL_REF_HEIGHT = 600.0

/** Rôle de chaque pièce de verre (pilote sa couleur). */
enum class BotanicalRole {
    BACKGROUND, STEM, LEAF_LIGHT, LEAF_DARK,
    TULIP_FRONT, TULIP_SIDE, POPPY_PETAL, POPPY_HEART,
    ROSETTE_PETAL, ROSETTE_HEART, BUD,
    GRAPE, WHEAT_LIGHT, WHEAT_DARK,
    LILY_FRONT, LILY_SIDE, ROSE_OUTER, ROSE_INNER, ROSE_HEART, IRIS_STANDARD, IRIS_FALL,
    HELLEBORE_PETAL, HELLEBORE_HEART,
    BUD_FRONT, BUD_SIDE, STUMP_CUT,
    PALM_LIGHT, PALM_DARK, PASSION_PETAL, PASSION_SEPAL, PASSION_CORONA, PASSION_HEART,
    EGLANTINE_PETAL, EGLANTINE_HEART,
    SNOWDROP_OUTER, SNOWDROP_SHADOW, SNOWDROP_CUP, SNOWDROP_OVARY, SNOWDROP_SCAPE,
    CHRYS_PETAL, CHRYS_BRONZE, CHRYS_HEART, CHRYS_PETAL_INNER, CHRYS_BRONZE_INNER, MARIGOLD_OUTER, MARIGOLD_MID, MARIGOLD_HEART,
    CAPSULE_BODY, CAPSULE_CROWN
}

/**
 * Motif de la famille végétale.
 * - FLEURS : tige, feuilles, tulipes, coquelicots, rosaces, boutons
 *   (beau, mais pas sacré : plutôt pour Atelier Astrea Designs) ;
 * - VIGNE_BLE : cep de vigne, feuilles de vigne, grappes de raisin et
 *   épis de blé — le pain et le vin de l'Eucharistie (Temps ordinaire) ;
 * - MARIAL : lys (pureté, Annonciation), rose (« Rosa mystica » des
 *   litanies de Lorette) et iris (douleurs de Marie) — palette mariale ;
 * - NOEL : roses de Noël (hellébores) et houx — la Nativité ; le houx
 *   (épines, baies rouges) est souvent lu comme annonce de la Passion ;
 * - AVENT : branche de Jessé — vieux bois taillé d'où repartent de jeunes
 *   feuilles, et roses de Noël encore en bouton : l'attente, « un rameau
 *   sortira de la souche de Jessé » (Is 11, 1) ;
 * - ROUGE : passiflores (la Passion), roses rouges (le sang des martyrs)
 *   et palmes (la palme du martyre), sur une tige épineuse ;
 * - PAQUES : lys blancs (la Résurrection), en fleur et en bouton, longues
 *   feuilles étroites ;
 * - ROSE : églantines (roses sauvages) épanouies parmi des boutons encore
 *   fermés — la joie au cœur de l'attente (Gaudete, Laetare) ;
 * - NOIRE : perce-neige sortant d'un sol enneigé (la bande ondulante
 *   devient la neige) — la lumière qui perce l'hiver, l'espérance de la
 *   résurrection (défunts, funérailles) ;
 * - SOUVENIR : fleurs du souvenir des défunts (Toussaint, 2 novembre) —
 *   pavot somnifère et sa capsule (le sommeil des défunts), chrysanthème
 *   (fleur des tombes de la Toussaint en France et en Europe) et
 *   cempasúchil (rose d'Inde du Día de Muertos mexicain).
 */
enum class BotanicalMotif { FLEURS, VIGNE_BLE, MARIAL, NOEL, AVENT, ROUGE, PAQUES, ROSE, NOIRE, SOUVENIR }

/**
 * Guide de la famille végétale : la composition calcule déjà les pièces
 * définitives (elle en a besoin pour décider quels ponts retirer) ; la
 * segmentation se contente de les renvoyer.
 */
class BotanicalGuide(
    val tileWidth: Double,
    val tileHeight: Double,
    val pieces: List<List<Vector2>>,
    val roles: List<BotanicalRole>,
    /** Famille de couleur de la fleur (tulipes, rosaces) ; -1 sinon. */
    val flowerFamilies: List<Int>,
    /** Détails peints à la grisaille (barbes du blé, vrilles de la vigne) :
     *  traits fins posés sur le verre, PAS des plombs. Voir drawBotanicalPaint. */
    val paintLines: List<List<Vector2>> = emptyList()
) : CompositionGuide()

// --------------------------------------------------
// PALETTES
// --------------------------------------------------

data class BotanicalPalette(
    val name: String,
    /** Fond, du plus sombre au plus clair (dégradé périodique lent). */
    val background: List<ColorRGBa>,
    val stem: List<ColorRGBa>,
    val leafLight: List<ColorRGBa>,
    val leafDark: List<ColorRGBa>,
    /** Familles de couleur des tulipes et des rosaces. */
    val flowerFamilies: List<List<ColorRGBa>>,
    /** Pétales latéraux des tulipes : mêmes familles, un ton plus sombre. */
    val flowerFamiliesShadow: List<List<ColorRGBa>>,
    val rosetteHeart: List<ColorRGBa>,
    val bud: List<ColorRGBa>,
    val poppy: List<ColorRGBa>,
    val poppyHeart: List<ColorRGBa>,
    /** Grains de raisin (vigne et blé). */
    val grape: List<ColorRGBa> = hexes("#4A2347", "#5B2C55", "#6B3462", "#3E1D3C"),
    /** Épis de blé : moitié éclairée et moitié dans l'ombre. */
    val wheatLight: List<ColorRGBa> = hexes("#E0B84F", "#D9A441", "#E6C463"),
    val wheatDark: List<ColorRGBa> = hexes("#B8862E", "#A9722A", "#C0913A"),
    /** Lys : pétale de devant, pétales latéraux (dans l'ombre). */
    val lily: List<ColorRGBa> = hexes("#F2E8CE", "#F7EFDD", "#EBE0C2"),
    val lilyShadow: List<ColorRGBa> = hexes("#DDD2B6", "#E3D9C3", "#D6CBAD"),
    /** Rose : pétales extérieurs, pétales intérieurs (en spirale), cœur. */
    val roseOuter: List<ColorRGBa> = hexes("#E29AAE", "#D98BA0", "#E8A9BA"),
    val roseInner: List<ColorRGBa> = hexes("#CF7C93", "#C46A84", "#D4859B"),
    val roseHeart: List<ColorRGBa> = hexes("#9E3E5C", "#8E3550"),
    /** Iris : pétales dressés (clairs), pétales retombants (plus sombres). */
    val irisStandard: List<ColorRGBa> = hexes("#BFB0E8", "#B3A3E0", "#C9BCEE"),
    val irisFall: List<ColorRGBa> = hexes("#7B64C4", "#6E57B8", "#8670CC"),
    /** Rose de Noël (hellébore) : pétales, cœur. */
    val hellebore: List<ColorRGBa> = hexes("#FAF6EC", "#F2ECD9", "#F5EFE0"),
    val helleboreHeart: List<ColorRGBa> = hexes("#C9C46A", "#BDB95C"),
    /** Rose de Noël en bouton : sépale de devant, sépales latéraux. */
    val budFront: List<ColorRGBa> = hexes("#F5E6EE", "#F1DDE8", "#F8ECF2"),
    val budSide: List<ColorRGBa> = hexes("#DDB3CB", "#D2A3BF", "#E4C0D4"),
    /** Bois coupé (face des branches taillées). */
    val stumpCut: List<ColorRGBa> = hexes("#B8AB98", "#C4B8A6"),
    /** Palme : moitié éclairée, moitié dans l'ombre. */
    val palmLight: List<ColorRGBa> = hexes("#E0B84F", "#D9A441", "#E6C463"),
    val palmDark: List<ColorRGBa> = hexes("#B8862E", "#A9722A", "#C0913A"),
    /** Passiflore : pétales, sépales, couronne de filaments, cœur. */
    val passionPetal: List<ColorRGBa> = hexes("#F5EEDC", "#F8F3E6", "#EFE6D0"),
    val passionSepal: List<ColorRGBa> = hexes("#E2E4C8", "#DADDBD", "#E8EACF"),
    val passionCorona: List<ColorRGBa> = hexes("#5B3A8C", "#6A4AA0", "#4E3080"),
    val passionHeart: List<ColorRGBa> = hexes("#9DB36A", "#A9BE76"),
    /** Églantine (rose sauvage) : pétales, cœur doré. */
    val eglantine: List<ColorRGBa> = hexes("#F6E7E4", "#F2DEDD", "#F9EEEA"),
    val eglantineHeart: List<ColorRGBa> = hexes("#DCAE79", "#E3B883", "#D6A46C"),
    /** Perce-neige : pétales extérieurs (éclairés, dans l'ombre), coupe intérieure, ovaire. */
    val snowdropOuter: List<ColorRGBa> = hexes("#F2EEE4", "#EDE7D8", "#F5F2EA"),
    val snowdropShadow: List<ColorRGBa> = hexes("#D9D3C4", "#D1CBBB", "#DED9CC"),
    val snowdropCup: List<ColorRGBa> = hexes("#DCE6CE", "#D2DEC2", "#E2EAD6"),
    val snowdropOvary: List<ColorRGBa> = hexes("#7FA06A", "#86A672", "#779862"),
    /** Hampe du perce-neige. */
    val snowdropScape: List<ColorRGBa> = hexes("#7F9586", "#879A8B", "#788E7F"),
    /** Chrysanthème : pétales ivoire, pétales bronze (autre fleur), cœur. */
    val chrysIvory: List<ColorRGBa> = hexes("#F1EBDB", "#EBE3CF", "#F5F0E4"),
    val chrysBronze: List<ColorRGBa> = hexes("#C98A4B", "#BF7F40", "#D29557"),
    val chrysHeart: List<ColorRGBa> = hexes("#C9A040", "#BF9436"),
    /** Chrysanthème : couronne intérieure (un ton plus soutenu), ivoire et bronze. */
    val chrysIvoryInner: List<ColorRGBa> = hexes("#E4D9BF", "#DDD1B5", "#E8DEC6"),
    val chrysBronzeInner: List<ColorRGBa> = hexes("#AE6A32", "#A4622D", "#B5723A"),
    /** Cempasúchil (rose d'Inde) : couronne extérieure, couronne du milieu, cœur. */
    val marigoldOuter: List<ColorRGBa> = hexes("#EE8E2C", "#F29A3A", "#E8852A"),
    val marigoldMid: List<ColorRGBa> = hexes("#D96E1E", "#D0661B", "#E07624"),
    val marigoldHeart: List<ColorRGBa> = hexes("#A9521C", "#B35A20"),
    /** Capsule de pavot : corps, couronne (disque stigmatique). */
    val capsuleBody: List<ColorRGBa> = hexes("#8E9E88", "#97A690", "#86967F"),
    val capsuleCrown: List<ColorRGBa> = hexes("#6D637A", "#766C83")
)

private fun hexes(vararg h: String) = h.map { ColorRGBa.fromHex(it) }

/** Assombrit une couleur hexadécimale (facteur < 1). */
private fun darkerHex(hex: String, factor: Double): String {
    val h = hex.removePrefix("#")
    val r = (h.substring(0, 2).toInt(16) * factor).toInt().coerceIn(0, 255)
    val g = (h.substring(2, 4).toInt(16) * factor).toInt().coerceIn(0, 255)
    val b = (h.substring(4, 6).toInt(16) * factor).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

private fun botanicalPalette(
    name: String,
    background: List<String>, stem: List<String>,
    leafLight: List<String>, leafDark: List<String>,
    flowers: List<List<String>>, rosetteHeart: List<String>, bud: List<String>,
    poppy: List<String>, poppyHeart: List<String>
) = BotanicalPalette(
    name = name,
    background = background.map { ColorRGBa.fromHex(it) },
    stem = stem.map { ColorRGBa.fromHex(it) },
    leafLight = leafLight.map { ColorRGBa.fromHex(it) },
    leafDark = leafDark.map { ColorRGBa.fromHex(it) },
    flowerFamilies = flowers.map { f -> f.map { ColorRGBa.fromHex(it) } },
    flowerFamiliesShadow = flowers.map { f -> f.map { ColorRGBa.fromHex(darkerHex(it, 0.86)) } },
    rosetteHeart = rosetteHeart.map { ColorRGBa.fromHex(it) },
    bud = bud.map { ColorRGBa.fromHex(it) },
    poppy = poppy.map { ColorRGBa.fromHex(it) },
    poppyHeart = poppyHeart.map { ColorRGBa.fromHex(it) }
)

object BotanicalPalettes {

    /** Printemps : ciel pâle, vert tendre, rose poudré et jaune pâle. */
    val PRINTEMPS = botanicalPalette(
        name = "Printemps",
        background = listOf("#9FCBE4", "#AED6EC", "#BFE0F0", "#CDE7F3"),
        stem = listOf("#6E9F4E", "#76A855"),
        leafLight = listOf("#B5DC8E", "#A8D47F", "#BFE29A"),
        leafDark = listOf("#86BC5F", "#7AB055", "#8FC468"),
        flowers = listOf(listOf("#F2B8C6", "#EDA6B8", "#F6C9D4"), listOf("#F7E39A", "#F3D97E", "#F9EAB0")),
        rosetteHeart = listOf("#E8B04A", "#E0A23E"),
        bud = listOf("#EDA6B8", "#E897AC"),
        poppy = listOf("#E2412F", "#D93A2A", "#E8553F"),
        poppyHeart = listOf("#2A1A20", "#33212A")
    )

    /** Art nouveau : fond ambre et ocre doré, feuilles olive, fleurs bordeaux et violettes. */
    val ART_NOUVEAU = botanicalPalette(
        name = "Art nouveau",
        background = listOf("#C99232", "#D9A441", "#E0B055", "#E6BC6A"),
        stem = listOf("#5E6B2E", "#66743A"),
        leafLight = listOf("#A8B35E", "#9AA552", "#B2BC6B"),
        leafDark = listOf("#6F7A36", "#7C873F", "#66702F"),
        flowers = listOf(listOf("#7A1F35", "#8E2A42", "#6B1A2E"), listOf("#5B3A7A", "#6A4889", "#4E3069")),
        rosetteHeart = listOf("#E8C46A", "#F0D27E"),
        bud = listOf("#8E2A42", "#7A1F35"),
        poppy = listOf("#B02A30", "#BE3337", "#A12229"),
        poppyHeart = listOf("#2A1A20", "#33212A")
    )

    /** Nuit : fond bleu nuit, feuilles vert sauge, fleurs ambre et or. */
    val NUIT = botanicalPalette(
        name = "Nuit",
        background = listOf("#1A3F66", "#1E4B78", "#255C8C", "#2D6CA0"),
        stem = listOf("#4A6B4A", "#557A55"),
        leafLight = listOf("#A9C4A6", "#9BB89A", "#B4CDB1"),
        leafDark = listOf("#7C9C7B", "#6E8E6D", "#86A584"),
        flowers = listOf(listOf("#E9A23B", "#F0B451", "#E39632"), listOf("#E8C35A", "#F2D16E", "#E2B84A")),
        rosetteHeart = listOf("#B9681F", "#A85C1C"),
        bud = listOf("#E9A23B", "#F0B451"),
        poppy = listOf("#D0452F", "#C23B28", "#DA5536"),
        poppyHeart = listOf("#1E1418", "#281B21")
    )

    /**
     * Vigne et blé — Temps ordinaire : le champ de couleur reprend les
     * verts profonds de la palette liturgique ORDINAIRE (comme la croix
     * et l'ange), les feuilles de vigne ses verts clairs, les épis son
     * ambre doré ; le cep est brun, les raisins pourpres.
     */
    val VIGNE_ORDINAIRE = botanicalPalette(
        name = "Vigne et blé — Temps ordinaire",
        background = listOf("#17402A", "#1F4D33", "#2B6240", "#3C7A4E"),
        stem = listOf("#6B4A2B", "#7A5531"),
        leafLight = listOf("#AFD196", "#9CC783", "#C2DCAA"),
        leafDark = listOf("#7FB069", "#6FA35C", "#86B571"),
        flowers = listOf(listOf("#F2E8CE"), listOf("#D9A441")),
        rosetteHeart = listOf("#D9A441"),
        bud = listOf("#D9A441"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    )

    /**
     * Lys, rose et iris — palette mariale (dévotionnelle) : le champ de
     * couleur reprend les bleus de LiturgicalPalettes.MARIAL ; lys blanc
     * cassé (comme les bras de la croix), roses rose ancien, iris mauves,
     * feuillage vert tendre. Les boutons sont des boutons de lys.
     */
    val MARIAL_FLEURS = botanicalPalette(
        name = "Lys, rose et iris — palette mariale",
        background = listOf("#1D3461", "#24477D", "#2E5C9A", "#3A6BAA"),
        stem = listOf("#4E7A4A", "#5A8755"),
        leafLight = listOf("#A9CF9B", "#9CC48E", "#B6D8A8"),
        leafDark = listOf("#6F9E66", "#7AAA70", "#669460"),
        flowers = listOf(listOf("#F2E8CE"), listOf("#E29AAE")),
        rosetteHeart = listOf("#D9A441"),
        bud = listOf("#F2E8CE", "#EBE0C2"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    )

    /**
     * Roses de Noël et houx — blanc et or (Noël) : le champ de couleur
     * reprend les ors de LiturgicalPalettes.NOEL ; hellébores ivoire et
     * perle (le blanc de la palette), cœur vert-jaune ; houx vert sombre
     * (côté éclairé plus clair), baies rouges ; branche brune.
     */
    val NOEL_FLEURS = botanicalPalette(
        name = "Roses de Noël et houx — blanc et or",
        background = listOf("#C9952F", "#D9A441", "#E3B65A", "#EBC06B"),
        stem = listOf("#5E4526", "#6B4F2C"),
        leafLight = listOf("#3F7A45", "#4A8650", "#3A7040"),
        leafDark = listOf("#24502C", "#2B5C33", "#1F4727"),
        flowers = listOf(listOf("#FAF6EC"), listOf("#F2ECD9")),
        rosetteHeart = listOf("#C9C46A"),
        bud = listOf("#F2ECD9", "#EDE4CC"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    ).copy(grape = hexes("#B3262E", "#C8323A", "#A11E27"))

    /**
     * Branche de Jessé — Avent (violet) : le champ de couleur reprend les
     * violets de LiturgicalPalettes.AVENT ; le vieux bois prend son bronze
     * cendré (sobriété, jeûne), les jeunes feuilles un vert tendre, les
     * boutons de rose de Noël un blanc à peine rosé.
     */
    val AVENT_FLEURS = botanicalPalette(
        name = "Branche de Jessé — Avent",
        background = listOf("#3D2645", "#4A2B56", "#5B3168", "#6A3C78"),
        stem = listOf("#6B5D52", "#5E5048"),
        leafLight = listOf("#AFC98E", "#9DBB7A", "#BBD29C"),
        leafDark = listOf("#7A9C5C", "#86A766", "#6E9052"),
        flowers = listOf(listOf("#F3E9EF"), listOf("#D9CBE0")),
        rosetteHeart = listOf("#C9C46A"),
        bud = listOf("#F3E9EF"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    )

    /**
     * Passiflores, roses rouges et palmes — Rouge (Passion, martyrs) : le
     * champ de couleur reprend le grenat profond de LiturgicalPalettes.ROUGE
     * (le sang de la Passion), assez sombre pour que les roses écarlates
     * s'en détachent ; palmes en or (la couronne des martyrs) ; passiflores
     * blanc crème à couronne violette ; tige et feuilles vert sombre.
     */
    val ROUGE_FLEURS = botanicalPalette(
        name = "Passiflores, roses et palmes — Rouge",
        background = listOf("#3A0A13", "#4A0E19", "#5C1220", "#6B1622"),
        stem = listOf("#2F4A2A", "#3A5733"),
        leafLight = listOf("#6E9A5A", "#7AA666", "#649050"),
        leafDark = listOf("#466E3A", "#507A43", "#3E6334"),
        flowers = listOf(listOf("#F5EEDC"), listOf("#D9A441")),
        rosetteHeart = listOf("#D9A441"),
        bud = listOf("#C8202F", "#B81C2A"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    ).copy(
        roseOuter = hexes("#D6303A", "#C8202F", "#DE4444"),
        roseInner = hexes("#A81C2C", "#9E1626", "#B22432"),
        roseHeart = hexes("#5C1220", "#4A0E19")
    )

    /**
     * Lys blancs — Pâques (blanc et or) : le champ de couleur reprend les ors
     * de LiturgicalPalettes.NOEL (« gloire »), plus clairs que pour Noël ;
     * lys ivoire et perle, ombres champagne ; boutons blanc verdâtre ;
     * feuillage vert frais.
     */
    val PAQUES_FLEURS = botanicalPalette(
        name = "Lys blancs — Pâques",
        background = listOf("#D9A441", "#E0B055", "#E8BD62", "#F0C674"),
        stem = listOf("#5E8A4E", "#6A9658"),
        leafLight = listOf("#9DC48A", "#AACD97", "#8FB97C"),
        leafDark = listOf("#6F9E5E", "#7AA868", "#64925A"),
        flowers = listOf(listOf("#FAF6EC"), listOf("#F2ECD9")),
        rosetteHeart = listOf("#D9A441"),
        bud = listOf("#F4F1DE"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    ).copy(
        lily = hexes("#FAF6EC", "#F2ECD9", "#F7F1E3"),
        lilyShadow = hexes("#E5D8B8", "#DDD0B0", "#E9DEC2"),
        budFront = hexes("#F4F1DE", "#EFEBD3", "#F7F4E6"),
        budSide = hexes("#DCE3C2", "#D2DAB6", "#E2E8CB")
    )

    /**
     * Églantines — Rose (Gaudete, Laetare) : le champ de couleur reprend le
     * rose liturgique de LiturgicalPalettes.ROSE (roseMain #B08093 et ses
     * voisins), assez soutenu pour que les églantines, d'un blanc rosé
     * (dawnBlush, rosePale), s'en détachent ; cœur en or adouci (goldSoft) ;
     * boutons d'un rose plus vif entre des sépales verts ; feuillage vert
     * tendre, un peu grisé pour s'accorder au rose.
     */
    val ROSE_FLEURS = botanicalPalette(
        name = "Églantines — Rose (Gaudete, Laetare)",
        background = listOf("#A06A7E", "#A97689", "#B08093", "#BA8B9D"),
        stem = listOf("#6E7A55", "#77835D"),
        leafLight = listOf("#9DB487", "#A7BD91", "#93AA7E"),
        leafDark = listOf("#6E8A5E", "#779366", "#668156"),
        flowers = listOf(listOf("#F6E7E4"), listOf("#EDD9DC")),
        rosetteHeart = listOf("#DCAE79"),
        bud = listOf("#C4607F"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    ).copy(
        eglantine = hexes("#F8EBE4", "#F3E0DE", "#F6E5E2"),
        eglantineHeart = hexes("#DCAE79", "#E3B883", "#D6A46C"),
        budFront = hexes("#C4607F", "#BA5575", "#CC6C89"),
        budSide = hexes("#7F9868", "#88A070", "#77905F")
    )

    /**
     * Perce-neige — Noire (défunts, funérailles) : le champ de couleur
     * reprend l'anthracite et l'ardoise de LiturgicalPalettes.NOIRE, avec
     * une pointe de violet (l'autre couleur du deuil), sans aller jusqu'au
     * noir (le plomb y disparaîtrait) ; jamais d'or. La « tige » est ici un
     * sol enneigé, gris perle et argent ; feuillage vert-gris glauque ;
     * fleurs ivoire (hopeGlow : la lumière qui perce les ténèbres).
     */
    val NOIRE_FLEURS = botanicalPalette(
        name = "Perce-neige — Noire (défunts)",
        background = listOf("#2B2830", "#34313A", "#3D3A43", "#47444D"),
        stem = listOf("#C9CDD3", "#BFC4CB", "#D3D6DB", "#B5BAC2"),
        leafLight = listOf("#A3B8AA", "#ABBFB2", "#9AAFA1"),
        leafDark = listOf("#7B9084", "#83988B", "#73887B"),
        flowers = listOf(listOf("#EDE3C8"), listOf("#D9D3C4")),
        rosetteHeart = listOf("#A6A6A8"),
        bud = listOf("#EDE3C8"),
        poppy = listOf("#C0392B"),
        poppyHeart = listOf("#2A1A20")
    ).copy(
        snowdropOuter = hexes("#F1EBDB", "#EDE3C8", "#F4F0E6"),
        snowdropShadow = hexes("#D6CFBE", "#CDC6B4", "#DBD5C6"),
        snowdropCup = hexes("#DDE6CF", "#D3DEC4", "#E3EAD8"),
        snowdropOvary = hexes("#7C9A6A", "#849F70", "#728F61")
    )

    /**
     * Fleurs du souvenir — Toussaint et commémoration des défunts : fond
     * anthracite un peu chaud (pas la palette noire stricte : les fleurs
     * gardent leurs couleurs) ; pavots somnifères mauve très pâle à cœur
     * gris-vert ; chrysanthèmes ivoire ou bronze à cœur ocre ; cempasúchil
     * orange en trois tons ; capsules gris-vert à couronne violacée ;
     * feuillage vert d'automne.
     */
    val TOUSSAINT_FLEURS = botanicalPalette(
        name = "Fleurs du souvenir — Toussaint",
        background = listOf("#2E2A30", "#373239", "#403B42", "#4A444C"),
        stem = listOf("#4F5F45", "#58684D"),
        leafLight = listOf("#8A9C78", "#93A581", "#82946F"),
        leafDark = listOf("#62744F", "#6A7C57", "#5B6C48"),
        flowers = listOf(listOf("#F1EBDB"), listOf("#C98A4B")),
        rosetteHeart = listOf("#C9A040"),
        bud = listOf("#8E9E88"),
        poppy = listOf("#E6D6E8", "#DDCBE2", "#EDE2EF"),
        poppyHeart = listOf("#8E9C84", "#97A48C")
    )

    /**
     * Version LITURGIQUE (première proposition, à juger à l'œil) : le fond
     * prend les familles principales de la palette liturgique (comme le
     * champ de couleur de la croix et de l'ange), les tulipes et rosaces
     * sont blanc cassé et or (comme les bras de la croix et le nimbe de
     * l'ange), les feuilles vert sauge ; les coquelicots restent rouges.
     */
    fun fromLiturgical(palette: LiturgicalPalette): BotanicalPalette {
        val main = palette.families.sortedByDescending { it.weight }.take(3).flatMap { it.shades }
        val white = LiturgicalPalettes.CROSS_ARM_SHADES
        val gold = LiturgicalPalettes.ANGEL_HALO_SHADES
        return NUIT.copy(
            name = "Liturgique — ${palette.name}",
            background = main,
            flowerFamilies = listOf(white, gold),
            flowerFamiliesShadow = listOf(
                hexes("#DDD2B6", "#E3D9C3", "#D6CBAD"),
                hexes("#BA8C37", "#C09E44", "#AD8427")
            ),
            rosetteHeart = gold,
            bud = gold
        )
    }
}

/**
 * Couleurs de la famille végétale, d'après le rôle de chaque pièce.
 * Le fond suit un dégradé lent et périodique (cosinus de la hauteur dans
 * la tuile : aucun saut de couleur au raccord).
 * ⚠ Consomme des tirages de random : à appeler au même endroit du
 * pipeline que les autres post-traitements (voir Renderer.kt).
 */
fun applyBotanicalColors(
    guide: BotanicalGuide,
    cellColors: MutableList<ColorRGBa>,
    palette: BotanicalPalette,
    random: Random
) {
    val phase = random.nextDouble(0.0, 2.0 * PI)
    fun pick(list: List<ColorRGBa>) = list[random.nextInt(list.size)]

    guide.pieces.forEachIndexed { index, piece ->
        val family = guide.flowerFamilies[index].coerceAtLeast(0) % palette.flowerFamilies.size
        cellColors[index] = when (guide.roles[index]) {
            BotanicalRole.BACKGROUND -> {
                val c = polygonCentroid(piece)
                val y = ((c.y % guide.tileHeight) + guide.tileHeight) % guide.tileHeight
                var t = 0.5 + 0.45 * cos(2.0 * PI * y / guide.tileHeight + phase)
                t += random.nextDouble(-0.2, 0.2)
                val i = Math.round(t.coerceIn(0.0, 1.0) * (palette.background.size - 1)).toInt()
                palette.background[i]
            }
            BotanicalRole.STEM -> pick(palette.stem)
            BotanicalRole.LEAF_LIGHT -> pick(palette.leafLight)
            BotanicalRole.LEAF_DARK -> pick(palette.leafDark)
            BotanicalRole.TULIP_FRONT -> pick(palette.flowerFamilies[family])
            BotanicalRole.TULIP_SIDE -> pick(palette.flowerFamiliesShadow[family])
            BotanicalRole.ROSETTE_PETAL -> pick(palette.flowerFamilies[family])
            BotanicalRole.ROSETTE_HEART -> pick(palette.rosetteHeart)
            BotanicalRole.POPPY_PETAL -> pick(palette.poppy)
            BotanicalRole.POPPY_HEART -> pick(palette.poppyHeart)
            BotanicalRole.BUD -> pick(palette.bud)
            BotanicalRole.GRAPE -> pick(palette.grape)
            BotanicalRole.WHEAT_LIGHT -> pick(palette.wheatLight)
            BotanicalRole.WHEAT_DARK -> pick(palette.wheatDark)
            BotanicalRole.LILY_FRONT -> pick(palette.lily)
            BotanicalRole.LILY_SIDE -> pick(palette.lilyShadow)
            BotanicalRole.ROSE_OUTER -> pick(palette.roseOuter)
            BotanicalRole.ROSE_INNER -> pick(palette.roseInner)
            BotanicalRole.ROSE_HEART -> pick(palette.roseHeart)
            BotanicalRole.IRIS_STANDARD -> pick(palette.irisStandard)
            BotanicalRole.IRIS_FALL -> pick(palette.irisFall)
            BotanicalRole.HELLEBORE_PETAL -> pick(palette.hellebore)
            BotanicalRole.HELLEBORE_HEART -> pick(palette.helleboreHeart)
            BotanicalRole.BUD_FRONT -> pick(palette.budFront)
            BotanicalRole.BUD_SIDE -> pick(palette.budSide)
            BotanicalRole.STUMP_CUT -> pick(palette.stumpCut)
            BotanicalRole.PALM_LIGHT -> pick(palette.palmLight)
            BotanicalRole.PALM_DARK -> pick(palette.palmDark)
            BotanicalRole.PASSION_PETAL -> pick(palette.passionPetal)
            BotanicalRole.PASSION_SEPAL -> pick(palette.passionSepal)
            BotanicalRole.PASSION_CORONA -> pick(palette.passionCorona)
            BotanicalRole.PASSION_HEART -> pick(palette.passionHeart)
            BotanicalRole.EGLANTINE_PETAL -> pick(palette.eglantine)
            BotanicalRole.EGLANTINE_HEART -> pick(palette.eglantineHeart)
            BotanicalRole.SNOWDROP_OUTER -> pick(palette.snowdropOuter)
            BotanicalRole.SNOWDROP_SHADOW -> pick(palette.snowdropShadow)
            BotanicalRole.SNOWDROP_CUP -> pick(palette.snowdropCup)
            BotanicalRole.SNOWDROP_OVARY -> pick(palette.snowdropOvary)
            BotanicalRole.SNOWDROP_SCAPE -> pick(palette.snowdropScape)
            BotanicalRole.CHRYS_PETAL -> pick(palette.chrysIvory)
            BotanicalRole.CHRYS_BRONZE -> pick(palette.chrysBronze)
            BotanicalRole.CHRYS_HEART -> pick(palette.chrysHeart)
            BotanicalRole.CHRYS_PETAL_INNER -> pick(palette.chrysIvoryInner)
            BotanicalRole.CHRYS_BRONZE_INNER -> pick(palette.chrysBronzeInner)
            BotanicalRole.MARIGOLD_OUTER -> pick(palette.marigoldOuter)
            BotanicalRole.MARIGOLD_MID -> pick(palette.marigoldMid)
            BotanicalRole.MARIGOLD_HEART -> pick(palette.marigoldHeart)
            BotanicalRole.CAPSULE_BODY -> pick(palette.capsuleBody)
            BotanicalRole.CAPSULE_CROWN -> pick(palette.capsuleCrown)
        }
    }
}

/**
 * Peinture à la grisaille : traits fins et sombres posés SUR le verre
 * (après le remplissage, avant le plomb), comme le font les verriers pour
 * les détails trop fins pour être découpés (barbes du blé, vrilles).
 * strokeWeight est en pixels de l'image dessinée.
 */
fun drawBotanicalPaint(drawer: Drawer, guide: BotanicalGuide, offsets: List<Vector2>, strokeWeight: Double) {
    if (guide.paintLines.isEmpty()) return
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = ColorRGBa.fromHex("#2A1F18").opacify(0.85)
        drawer.strokeWeight = strokeWeight
        drawer.lineCap = LineCap.ROUND
        for (o in offsets) for (line in guide.paintLines) {
            if (line.size < 2) continue
            val segments = (0 until line.size - 1).map { k ->
                val a = Vector2(line[k].x + o.x, line[k].y + o.y)
                val b = Vector2(line[k + 1].x + o.x, line[k + 1].y + o.y)
                Segment2D(a, Vector2(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3),
                    Vector2(a.x + 2 * (b.x - a.x) / 3, a.y + 2 * (b.y - a.y) / 3), b)
            }
            drawer.contour(ShapeContour.fromSegments(segments, closed = false))
        }
    }
}

// --------------------------------------------------
// COMPOSITION ET SEGMENTATION
// --------------------------------------------------

/**
 * Composition végétale raccordable. Les tailles sont en part de la
 * hauteur de la tuile (H) ; les valeurs par défaut sont celles du
 * prototype validé à l'œil.
 *
 * targetRegions (numberOfSites de la recette) n'est PAS utilisé : le
 * nombre de pièces dépend des motifs et de backgroundPieceRatio.
 */
class BotanicalCompositionSystem(
    /** Fleurs, ou vigne et blé (voir BotanicalMotif). */
    private val motif: BotanicalMotif = BotanicalMotif.FLEURS,
    /** Nombre de feuilles le long de la tige (par largeur de tuile). */
    private val leafCountMin: Int = 4,
    private val leafCountMax: Int = 5,
    /** Probabilités relatives des trois sortes de fleurs. */
    private val tulipWeight: Double = 0.42,
    private val poppyWeight: Double = 0.42,
    private val rosetteWeight: Double = 0.16,
    /** Probabilité d'une seconde fleur, plus petite. */
    private val secondFlowerProbability: Double = 0.75,
    private val budCountMin: Int = 1,
    private val budCountMax: Int = 2,
    /** Aire maximale d'une pièce de fond (part de la tuile). */
    private val backgroundPieceRatio: Double = 0.055,
    /** Écart minimal entre deux motifs (part de H). */
    private val gapRatio: Double = 0.07,
    /** Largeur de la tige (part de H). */
    private val stemWidthRatio: Double = 0.055
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val refW = BOTANICAL_REF_HEIGHT * width / height
        val built = BotanicalBuilder(
            W = refW, H = BOTANICAL_REF_HEIGHT, rnd = random, motif = motif,
            leafCountMin = leafCountMin, leafCountMax = leafCountMax,
            tulipWeight = tulipWeight, poppyWeight = poppyWeight, rosetteWeight = rosetteWeight,
            secondFlowerProbability = secondFlowerProbability,
            budCountMin = budCountMin, budCountMax = budCountMax,
            backgroundPieceRatio = backgroundPieceRatio,
            gap = gapRatio * BOTANICAL_REF_HEIGHT,
            // le cep de vigne est un peu plus épais qu'une tige de fleur
            // (Noire : la bande est un sol enneigé, nettement plus épais)
            stemWidth = stemWidthRatio * BOTANICAL_REF_HEIGHT * when (motif) {
                BotanicalMotif.VIGNE_BLE -> 1.25
                BotanicalMotif.NOIRE -> 1.8
                else -> 1.0
            }
        ).build()

        val s = height / BOTANICAL_REF_HEIGHT
        return BotanicalGuide(
            tileWidth = width,
            tileHeight = height,
            pieces = built.pieces.map { piece -> piece.map { Vector2(it.x * s, it.y * s) } },
            roles = built.roles,
            flowerFamilies = built.families,
            paintLines = built.paint.map { line -> line.map { Vector2(it.x * s, it.y * s) } }
        )
    }
}

/** Les pièces sont calculées par la composition (voir BotanicalGuide). */
class BotanicalSegmentationSystem : SegmentationSystem {
    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {
        require(guide is BotanicalGuide) { "BotanicalSegmentationSystem nécessite un BotanicalGuide" }
        return guide.pieces
    }
}

// --------------------------------------------------
// CONSTRUCTION (repère de référence)
// --------------------------------------------------

private enum class BotKind { STEM, LEAF, TULIP, POPPY, ROSETTE, BUD, VINE_LEAF, GRAPES, WHEAT, COMPOSITE, PALM }

/**
 * Un motif posé dans la tuile. outline : contour fermé (premier point
 * non répété), utilisé pour les distances et pour reconnaître les pièces ;
 * lines : lignes de plomb canoniques propres au motif.
 */
private class BotFeature(
    val kind: BotKind,
    val outline: List<Vector2>,
    val lines: List<List<Vector2>>,
    /** Nervure (feuille) : sépare la moitié claire de la moitié sombre. */
    val axis: List<Vector2>? = null,
    /** Cœur (coquelicot, rosace) ou pétale central (tulipe). */
    val inner: List<Vector2>? = null,
    val family: Int = -1,
    /** Grains de raisin, du premier plan vers l'arrière. */
    val grains: List<List<Vector2>>? = null,
    /** Détails peints à la grisaille (barbes du blé…), pas des plombs. */
    val paint: List<List<Vector2>> = emptyList(),
    /** Parties d'une fleur composée (lys, rose, iris), de l'avant vers
     *  l'arrière, avec leur rôle ; une pièce prend le rôle de la première
     *  partie qui la contient, sinon defaultRole. */
    val parts: List<Pair<List<Vector2>, BotanicalRole>> = emptyList(),
    val defaultRole: BotanicalRole = BotanicalRole.BACKGROUND
) {
    val bounds: DoubleArray = botBounds(outline)
}

private class BotResult(
    val pieces: List<List<Vector2>>,
    val roles: List<BotanicalRole>,
    val families: List<Int>,
    val paint: List<List<Vector2>> = emptyList()
)

private class BotanicalBuilder(
    val W: Double,
    val H: Double,
    val rnd: Random,
    val motif: BotanicalMotif,
    val leafCountMin: Int,
    val leafCountMax: Int,
    val tulipWeight: Double,
    val poppyWeight: Double,
    val rosetteWeight: Double,
    val secondFlowerProbability: Double,
    val budCountMin: Int,
    val budCountMax: Int,
    val backgroundPieceRatio: Double,
    val gap: Double,
    val stemWidth: Double
) {
    val features = mutableListOf<BotFeature>()
    /** Taille des feuilles posées par placeLeaves (Avent : jeunes feuilles ; Rose : folioles ; plus petites). */
    val leafScale = when (motif) {
        BotanicalMotif.AVENT -> 0.72
        BotanicalMotif.ROSE -> 0.80   // folioles d'églantier, plus petites
        else -> 1.0
    }
    /** Lignes canoniques recopiées aux 9 positions (pédoncules, coupes de la tige…). */
    val fixedLines = mutableListOf<List<Vector2>>()
    /** Détails peints à la grisaille (vrilles…), en plus de ceux des motifs. */
    val paintLines = mutableListOf<List<Vector2>>()
    /** Lignes de la tige : déjà étendues sur [-W, 2W], recopiées verticalement seulement. */
    val stemLines = mutableListOf<List<Vector2>>()
    lateinit var stemOutline: List<Vector2>
    lateinit var stemY: (Double) -> Double
    lateinit var stemDy: (Double) -> Double
    /** Dessus de la bande (Noire : bord bosselé du sol enneigé). */
    var groundTop: (Double) -> Double = { x -> stemY(x) - stemWidth / 2 }

    val allOffsets = (-1..1).flatMap { dx -> (-1..1).map { dy -> Vector2(dx * W, dy * H) } }
    val verticalOffsets = (-1..1).map { Vector2(0.0, it * H) }

    fun offsetsOf(f: BotFeature) = if (f.kind == BotKind.STEM) verticalOffsets else allOffsets

    fun stemCopies() = verticalOffsets.map { o -> botShift(stemOutline, o) }

    // ---------------- espacement

    /** Distance entre un contour fermé et tous les motifs posés (copies comprises). */
    fun clearance(outline: List<Vector2>, closed: Boolean, exclude: Set<BotFeature> = emptySet(), cutoff: Double): Double {
        var best = Double.MAX_VALUE
        val b = botBounds(outline)
        for (f in features) {
            if (f in exclude) continue
            for (o in offsetsOf(f)) {
                if (botBoundsGap(b, f.bounds, o) > cutoff) continue
                val other = botShift(f.outline, o)
                val d = botShapeDistance(outline, closed, other)
                if (d < best) best = d
                if (best <= 0.0) return 0.0
            }
        }
        return best
    }

    fun isFree(outline: List<Vector2>, closed: Boolean, minGap: Double, exclude: Set<BotFeature> = emptySet()) =
        clearance(outline, closed, exclude, minGap) >= minGap

    // ---------------- motifs

    fun build(): BotResult {
        placeStem()
        if (motif == BotanicalMotif.VIGNE_BLE) {
            placeVineLeaves()
            placeGrapesAndWheat()
        } else if (motif == BotanicalMotif.PAQUES) {
            placeEasterLilies()
            placeLeaves()
        } else if (motif == BotanicalMotif.ROSE) {
            placeEglantines()
            placeLeaves()
        } else if (motif == BotanicalMotif.NOIRE) {
            placeSnowdrops()
        } else if (motif == BotanicalMotif.SOUVENIR) {
            placeSouvenirBlooms()
            placeLeaves()
        } else if (motif == BotanicalMotif.ROUGE) {
            placeRedBlooms()
            placePalms()
            placeLeaves()
            placeThorns()
        } else if (motif == BotanicalMotif.AVENT) {
            placeStumps()
            placeLeaves()
            placeAdventBuds()
        } else if (motif == BotanicalMotif.NOEL) {
            // fleurs, puis feuilles de houx, puis baies (à l'aisselle des feuilles)
            placeNoelBlooms(listOf("hellebore" to true, "hellebore" to false, "bouton" to false))
            placeHollyLeaves()
            placeNoelBlooms(List(rnd.nextInt(2, 4)) { "baies" to false })
        } else if (motif == BotanicalMotif.MARIAL) {
            // les fleurs d'abord (ce sont elles le sujet), les feuilles ensuite
            placeMarianBlooms()
            placeLeaves()
        } else {
            placeLeaves()
            placeBlooms()
        }
        return network()
    }

    // ---------------- lys blancs (Pâques)

    /** Deux grands lys et un plus petit, tous dressés, et deux boutons de lys allongés. */
    fun placeEasterLilies() {
        val stems = stemCopies()
        val stem = features.first()
        val items = listOf("lys" to true, "lys" to true, "lys" to false, "bouton" to false, "bouton" to false)
        for ((kind, big) in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                if (ps.y < c.y) continue
                val ang = -PI / 2 + rnd.nextDouble(-0.45, 0.45)
                val h: Double
                val feature: BotFeature
                var inset = 6.0
                if (kind == "lys") {
                    h = (if (big) rnd.nextDouble(0.32, 0.36) else rnd.nextDouble(0.26, 0.29)) * H
                    val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                    val l = botLily(b, ang, h, rnd.nextDouble())
                    feature = BotFeature(BotKind.COMPOSITE, l.outline, listOf(l.outline + l.outline.first()) + l.dividers,
                        paint = l.stamens, parts = listOf(l.centre to BotanicalRole.LILY_FRONT), defaultRole = BotanicalRole.LILY_SIDE)
                } else {
                    h = rnd.nextDouble(0.19, 0.23) * H
                    val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                    val bud = botClosedBud(b, ang, h, 0.42)
                    inset = bud.inset
                    feature = BotFeature(BotKind.COMPOSITE, bud.outline, listOf(bud.outline + bud.outline.first()) + bud.dividers,
                        parts = listOf(bud.front to BotanicalRole.BUD_FRONT), defaultRole = BotanicalRole.BUD_SIDE)
                }
                val base = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                if (!isFree(feature.outline, true, gap * 1.1)) continue
                val d = botMinDistance(feature.outline, true, stems)
                if (d > 0.30 * H || d < 0.07 * H) continue
                // la tige qui porte la fleur est forcément SOUS sa base
                // (pas celle du dessus, même si la corolle s'en approche)
                val target = stems.filter { botNearestOnRing(base, it).y > base.y }
                    .minByOrNull { botNearestOnRing(base, it).distanceTo(base) } ?: continue
                if (botNearestOnRing(base, target).distanceTo(base) > 0.30 * H) continue
                val stalk = botBaseStalk(base, ang, feature.outline, target, inset) ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
    }

    // ---------------- perce-neige (Noire)

    /**
     * Trois touffes de perce-neige qui sortent du sol enneigé (le dessus de
     * la bande) : dans chaque touffe, deux ou trois hampes (étroites bandes
     * de verre vert) qui se recourbent au sommet, chacune portant une fleur
     * pendante (ouverte ou encore fermée), puis deux ou trois feuilles
     * étroites dressées entre elles. Le sol est ensuite coupé en mottes,
     * entre les touffes.
     */
    fun placeSnowdrops() {
        val stems = stemCopies()
        val stem = features.first()
        fun groundY(x: Double) = groundTop(x)
        val anchors = mutableListOf<Double>()
        // lignes déjà posées (hampes), hors du sol
        fun lineParts() = fixedLines.flatMap { botClipOutside(it, stems) }.filter { it.size >= 2 }
        fun crossesLines(outline: List<Vector2>, margin: Double): Boolean {
            val b = botBounds(outline)
            return lineParts().any { sp ->
                val sb = botBounds(sp)
                allOffsets.any { o ->
                    if (botBoundsGap(b, sb, o) > margin) return@any false
                    val so = botShift(sp, o)
                    botShapeDistance(so, false, outline) < margin
                }
            }
        }
        val nClumps = 3
        val x0 = rnd.nextDouble(0.0, W)
        for (i in 0 until nClumps) {
            val cx = botWrap(x0 + i * W / nClumps + rnd.nextDouble(-0.04, 0.04) * W, W)
            var side = if (rnd.nextBoolean()) 1 else -1
            val nFlowers = rnd.nextInt(2, 4)
            for (k in 0 until nFlowers) {
                val open = k == 0 || rnd.nextDouble() < 0.6
                for (attempt in 0 until 150) {
                    val gx = cx + rnd.nextDouble(-0.13, 0.13) * W
                    val g = Vector2(gx, groundY(gx) + 4)
                    val rise = rnd.nextDouble(0.32, 0.56) * H
                    val reach = side * rnd.nextDouble(0.05, 0.18) * H
                    val p = Vector2(gx + reach, groundY(gx) - rise)
                    val hang = PI / 2 - side * rnd.nextDouble(0.15, 0.45)
                    val h = if (open) rnd.nextDouble(0.21, 0.25) * H else rnd.nextDouble(0.17, 0.20) * H
                    val sd = botSnowdrop(p, hang, h, open)
                    if (!isFree(sd.outline, true, gap * 0.6)) continue
                    if (crossesLines(sd.outline, gap * 0.5)) continue
                    // la hampe : sort du sol, monte, se recourbe et entre dans l'ovaire
                    val dir = Vector2(cos(hang), sin(hang))
                    val e = Vector2(p.x + dir.x * 0.10 * h, p.y + dir.y * 0.10 * h)
                    val scape = botCubic(g, Vector2(g.x, g.y - 0.80 * (g.y - p.y)),
                        Vector2(p.x - dir.x * 0.13 * H, p.y - dir.y * 0.13 * H), e, 36)
                    // dans le sol au début seulement, dans la fleur à la fin seulement
                    val inGround = scape.map { q -> stems.any { polygonContains(it, q) } }
                    val leave = inGround.indexOfFirst { !it }
                    if (leave <= 0 || inGround.drop(leave).any { it }) continue
                    val inFlower = scape.map { polygonContains(sd.outline, it) }
                    val enter = inFlower.indexOfFirst { it }
                    if (enter < 0 || inFlower.drop(enter).any { !it } || enter <= leave) continue
                    // la hampe est une étroite bande de verre vert (un simple plomb se
                    // confondrait avec les lignes du fond), un peu plus fine en haut
                    val ring = botStrip(scape, 0.024 * H, 0.017 * H)
                    if (!isFree(ring, true, gap * 0.4, setOf(stem))) continue
                    if (anchors.any { abs(botWrap(it - gx + W / 2, W) - W / 2) < gap * 0.5 }) continue
                    // la fleur d'abord : sa pièce d'ovaire l'emporte sur le bout de la hampe
                    features.add(BotFeature(BotKind.COMPOSITE, sd.outline, sd.lines, paint = sd.paint,
                        parts = sd.parts, defaultRole = BotanicalRole.SNOWDROP_OUTER))
                    features.add(BotFeature(BotKind.COMPOSITE, ring, botClipOutside(ring + ring.first(), stems + listOf(sd.outline)),
                        defaultRole = BotanicalRole.SNOWDROP_SCAPE))
                    anchors.add(gx)
                    break
                }
                side = -side
            }
            // puis les feuilles étroites, dressées, entre les hampes
            val nLeaves = rnd.nextInt(2, 4)
            var dark = rnd.nextBoolean()
            for (k in 0 until nLeaves) {
                for (attempt in 0 until 60) {
                    val lx = cx + rnd.nextDouble(-0.09, 0.09) * W
                    if (anchors.any { abs(botWrap(it - lx + W / 2, W) - W / 2) < gap * 0.45 }) continue
                    val base = Vector2(lx, groundY(lx) + 4)
                    val length = rnd.nextDouble(0.22, 0.34) * H
                    val ang = -PI / 2 + rnd.nextDouble(-0.40, 0.40)
                    val (outline, _) = botLeaf(base, ang, length, length * rnd.nextDouble(0.17, 0.20), rnd.nextDouble(-0.18, 0.18))
                    val crossesGround = outline.any { q -> q.distanceTo(base) > 0.3 * length && stems.any { polygonContains(it, q) } }
                    if (crossesGround) continue
                    if (!isFree(outline, true, gap * 0.55, setOf(stem))) continue
                    if (crossesLines(outline, gap * 0.4)) continue
                    features.add(BotFeature(BotKind.COMPOSITE, outline, botClipOutside(outline + outline.first(), stems),
                        defaultRole = if (dark) BotanicalRole.LEAF_DARK else BotanicalRole.LEAF_LIGHT))
                    anchors.add(lx)
                    dark = !dark
                    break
                }
            }
        }
        // le sol est coupé en mottes, loin des pieds des touffes
        val nCuts = rnd.nextInt(4, 6)
        val xc = rnd.nextDouble(0.0, W)
        for (i in 0 until nCuts) {
            for (attempt in 0 until 20) {
                val xa = botWrap(xc + (i + 0.5) * W / nCuts + rnd.nextDouble(-0.06, 0.06) * W, W)
                if (anchors.any { abs(botWrap(it - xa + W / 2, W) - W / 2) < gap * 0.9 }) continue
                fixedLines.add(listOf(Vector2(xa, groundTop(xa) - 3), Vector2(xa, stemY(xa) + stemWidth / 2 + 3)))
                break
            }
        }
    }

    // ---------------- fleurs du souvenir (Toussaint)

    /**
     * Un grand chrysanthème, un grand cempasúchil, un pavot somnifère, une
     * quatrième fleur plus petite (chrysanthème bronze ou cempasúchil), et
     * une capsule de pavot dressée (le sommeil des défunts).
     */
    fun placeSouvenirBlooms() {
        val stems = stemCopies()
        val stem = features.first()
        val items = listOf("chrysantheme" to true, "cempasuchil" to true, "pavot" to true,
            (if (rnd.nextBoolean()) "chrysantheme" else "cempasuchil") to false, "capsule" to false)
        var chrysCount = 0
        for ((kind, big) in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                val feature: BotFeature
                var stalk: List<Vector2>?
                if (kind != "capsule") {
                    when (kind) {
                        "chrysantheme" -> {
                            val r = (if (big) rnd.nextDouble(0.15, 0.17) else rnd.nextDouble(0.11, 0.13)) * H
                            val ch = botChrysanthemum(c, r, rnd.nextDouble(0.0, 2 * PI))
                            val ivory = chrysCount == 0
                            feature = BotFeature(BotKind.COMPOSITE, ch.outline, ch.lines, paint = ch.paint,
                                parts = listOf(ch.heart to BotanicalRole.CHRYS_HEART,
                                    ch.inner to (if (ivory) BotanicalRole.CHRYS_PETAL_INNER else BotanicalRole.CHRYS_BRONZE_INNER)),
                                defaultRole = if (ivory) BotanicalRole.CHRYS_PETAL else BotanicalRole.CHRYS_BRONZE)
                        }
                        "cempasuchil" -> {
                            val r = (if (big) rnd.nextDouble(0.13, 0.15) else rnd.nextDouble(0.10, 0.12)) * H
                            val mg = botMarigold(c, r, rnd.nextDouble(0.0, 2 * PI))
                            feature = BotFeature(BotKind.COMPOSITE, mg.outline, mg.lines, paint = mg.paint,
                                parts = listOf(mg.heart to BotanicalRole.MARIGOLD_HEART, mg.mid to BotanicalRole.MARIGOLD_MID),
                                defaultRole = BotanicalRole.MARIGOLD_OUTER)
                        }
                        else -> {
                            // pavot somnifère : quatre larges pétales, cœur (disque stigmatique) rayé
                            val r = rnd.nextDouble(0.14, 0.16) * H
                            val pp = botPoppy(c, r, rnd.nextDouble(0.0, PI), rnd.nextDouble(0.0, 6.3))
                            val rays = (0 until 9).map { k ->
                                val a = 2 * PI * k / 9 + 0.2
                                listOf(Vector2(c.x + 0.05 * r * cos(a), c.y + 0.05 * r * sin(a)),
                                    Vector2(c.x + 0.20 * r * cos(a), c.y + 0.20 * r * sin(a)))
                            }
                            feature = BotFeature(BotKind.COMPOSITE, pp.outline,
                                listOf(pp.outline + pp.outline.first(), pp.heart + pp.heart.first()) + pp.dividers,
                                paint = rays, parts = listOf(pp.heart to BotanicalRole.POPPY_HEART), defaultRole = BotanicalRole.POPPY_PETAL)
                        }
                    }
                    if (!isFree(feature.outline, true, gap * 1.2)) continue
                    val d = botMinDistance(feature.outline, true, stems)
                    if (d > 0.30 * H || d < 0.07 * H) continue
                    val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                    stalk = botBridge(feature.outline, target, rnd)
                } else {
                    // capsule dressée, au-dessus de la tige qui la porte
                    if (ps.y < c.y) continue
                    val ang = -PI / 2 + rnd.nextDouble(-0.40, 0.40)
                    val h = rnd.nextDouble(0.19, 0.22) * H
                    val base = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                    val cap = botCapsule(base, ang, h)
                    feature = BotFeature(BotKind.COMPOSITE, cap.outline, cap.lines, paint = cap.paint,
                        parts = listOf(cap.crown to BotanicalRole.CAPSULE_CROWN), defaultRole = BotanicalRole.CAPSULE_BODY)
                    if (!isFree(feature.outline, true, gap * 1.1)) continue
                    val d = botMinDistance(feature.outline, true, stems)
                    if (d > 0.30 * H || d < 0.07 * H) continue
                    val target = stems.filter { botNearestOnRing(base, it).y > base.y }
                        .minByOrNull { botNearestOnRing(base, it).distanceTo(base) } ?: continue
                    if (botNearestOnRing(base, target).distanceTo(base) > 0.30 * H) continue
                    stalk = botBaseStalk(base, ang, feature.outline, target, 6.0)
                }
                if (stalk == null) continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                if (kind == "chrysantheme") chrysCount++
                break
            }
        }
    }

    // ---------------- églantines (Rose : Gaudete, Laetare)

    /**
     * Deux grandes églantines et une plus petite, épanouies, vues de face ;
     * deux boutons dressés, encore fermés (la joie au cœur de l'attente).
     */
    fun placeEglantines() {
        val stems = stemCopies()
        val stem = features.first()
        val items = listOf("eglantine" to true, "eglantine" to true, "eglantine" to false, "bouton" to false, "bouton" to false)
        for ((kind, big) in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                val feature: BotFeature
                var stalk: List<Vector2>?
                if (kind == "eglantine") {
                    val r = (if (big) rnd.nextDouble(0.15, 0.17) else rnd.nextDouble(0.11, 0.13)) * H
                    val eg = botEglantine(c, r, rnd.nextDouble(0.0, 2 * PI))
                    feature = BotFeature(BotKind.COMPOSITE, eg.outline, eg.lines, paint = eg.paint,
                        parts = listOf(eg.heart to BotanicalRole.EGLANTINE_HEART), defaultRole = BotanicalRole.EGLANTINE_PETAL)
                    if (!isFree(feature.outline, true, gap * 1.2)) continue
                    val d = botMinDistance(feature.outline, true, stems)
                    if (d > 0.30 * H || d < 0.07 * H) continue
                    val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                    stalk = botBridge(feature.outline, target, rnd)
                } else {
                    // bouton dressé, au-dessus de la tige qui le porte
                    if (ps.y < c.y) continue
                    val ang = -PI / 2 + rnd.nextDouble(-0.45, 0.45)
                    val h = rnd.nextDouble(0.15, 0.18) * H
                    val base = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                    val bud = botClosedBud(base, ang, h, 0.56)
                    feature = BotFeature(BotKind.COMPOSITE, bud.outline, listOf(bud.outline + bud.outline.first()) + bud.dividers,
                        parts = listOf(bud.front to BotanicalRole.BUD_FRONT), defaultRole = BotanicalRole.BUD_SIDE)
                    if (!isFree(feature.outline, true, gap * 1.1)) continue
                    val d = botMinDistance(feature.outline, true, stems)
                    if (d > 0.30 * H || d < 0.07 * H) continue
                    val target = stems.filter { botNearestOnRing(base, it).y > base.y }
                        .minByOrNull { botNearestOnRing(base, it).distanceTo(base) } ?: continue
                    if (botNearestOnRing(base, target).distanceTo(base) > 0.30 * H) continue
                    stalk = botBaseStalk(base, ang, feature.outline, target, bud.inset)
                }
                if (stalk == null) continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
    }

    // ---------------- passiflores, roses rouges et palmes (Rouge)

    /** Une grande passiflore, une rose rouge, et une seconde fleur (rose ou passiflore) plus petite. */
    fun placeRedBlooms() {
        val stems = stemCopies()
        val stem = features.first()
        val items = mutableListOf("passiflore" to true, "rose" to true,
            (if (rnd.nextBoolean()) "rose" else "passiflore") to false, "bouton" to false)
        for ((kind, big) in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                var ang = atan2(c.y - ps.y, c.x - ps.x) + rnd.nextDouble(-0.35, 0.35)
                var basePoint: Vector2? = null
                val feature: BotFeature
                when (kind) {
                    "passiflore" -> {
                        val r = (if (big) rnd.nextDouble(0.16, 0.19) else rnd.nextDouble(0.12, 0.14)) * H
                        val pf = botPassion(c, r, rnd.nextDouble(0.0, 2 * PI))
                        feature = BotFeature(BotKind.COMPOSITE, pf.outline, pf.lines, paint = pf.paint,
                            parts = pf.parts, defaultRole = BotanicalRole.PASSION_PETAL)
                    }
                    "rose" -> {
                        val r = (if (big) rnd.nextDouble(0.14, 0.16) else rnd.nextDouble(0.11, 0.13)) * H
                        val ro = botRose(c, r, rnd.nextDouble(0.0, 2 * PI))
                        feature = BotFeature(BotKind.COMPOSITE, ro.outline, ro.lines,
                            parts = listOf(ro.heart to BotanicalRole.ROSE_HEART, ro.ring to BotanicalRole.ROSE_INNER),
                            defaultRole = BotanicalRole.ROSE_OUTER)
                    }
                    else -> {
                        ang = -PI / 2 + rnd.nextDouble(-0.5, 0.5)
                        if (ps.y < c.y) continue
                        val size = rnd.nextDouble(0.12, 0.15) * H
                        val b = Vector2(c.x - cos(ang) * size * 0.5, c.y - sin(ang) * size * 0.5)
                        basePoint = b
                        val o = botBud(b, ang, size)
                        feature = BotFeature(BotKind.BUD, o, listOf(o + o.first()))
                    }
                }
                if (!isFree(feature.outline, true, gap * 1.2)) continue
                val d = botMinDistance(feature.outline, true, stems)
                if (d > 0.30 * H || d < 0.07 * H) continue
                val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                val stalk = if (basePoint != null) botBaseStalk(basePoint, ang, feature.outline, target)
                else botBridge(feature.outline, target, rnd)
                if (stalk == null) continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
    }

    /** Deux palmes du martyre, qui partent de la tige en s'incurvant. */
    fun placePalms() {
        val stems = stemCopies()
        val stem = features.first()
        repeat(2) {
            for (attempt in 0 until 200) {
                val x = rnd.nextDouble(0.0, W)
                val up = rnd.nextBoolean()
                val ang = (if (up) -PI / 2 else PI / 2) + rnd.nextDouble(-0.7, 0.7)
                val base = Vector2(x, stemY(x) + (if (up) -1 else 1) * (stemWidth / 2 + rnd.nextDouble(0.04, 0.07) * H))
                val length = rnd.nextDouble(0.40, 0.48) * H
                val bend = rnd.nextDouble(0.10, 0.20) * (if (rnd.nextBoolean()) 1 else -1)
                val pm = botPalm(base, ang, length, 0.12 * H, bend, rnd.nextInt(11, 15))
                if (!isFree(pm.outline, true, gap, setOf(stem))) continue
                if (botMinDistance(pm.outline, true, stems) < 0.025 * H) continue
                val target = stems.minByOrNull { botShapeDistance(pm.outline, true, it) }!!
                val stalk = botBaseStalk(base, ang, pm.outline, target, pm.inset) ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(BotFeature(BotKind.PALM, pm.outline, pm.lines, axis = pm.axis))
                fixedLines.add(stalk)
                break
            }
        }
    }

    /** Épines de la tige (la couronne d'épines), peintes à la grisaille là où il reste de la place. */
    fun placeThorns() {
        val stem = features.first()
        val n = rnd.nextInt(7, 11)
        for (i in 0 until n) {
            for (attempt in 0 until 10) {
                val x = rnd.nextDouble(0.0, W)
                val up = rnd.nextBoolean()
                val sgn = if (up) -1.0 else 1.0
                val tang = atan2(stemDy(x), 1.0)
                val e = Vector2(x, stemY(x) + sgn * stemWidth / 2)
                val l = 0.035 * H
                // épine courbée vers l'avant de la tige
                val dir = tang + sgn * PI / 2 - sgn * 0.6
                val tip = Vector2(e.x + cos(dir) * l, e.y + sin(dir) * l)
                val a = Vector2(e.x - cos(tang) * 0.018 * H, e.y - sin(tang) * 0.018 * H)
                val b = Vector2(e.x + cos(tang) * 0.018 * H, e.y + sin(tang) * 0.018 * H)
                val thorn = listOf(a, tip, b)
                if (clearance(listOf(tip, Vector2((a.x + b.x) / 2, (a.y + b.y) / 2)), false, setOf(stem), gap * 0.3) < gap * 0.3) continue
                paintLines.add(thorn)
                break
            }
        }
    }

    // ---------------- branche de Jessé (Avent)

    /** Chicots : branches latérales taillées, face de coupe en bois clair (la « souche »). */
    fun placeStumps() {
        val stems = stemCopies()
        val stem = features.first()
        repeat(2) {
            for (attempt in 0 until 60) {
                val x = rnd.nextDouble(0.0, W)
                val up = rnd.nextBoolean()
                val tang = atan2(stemDy(x), 1.0)
                val ang = (if (up) tang - PI / 2 else tang + PI / 2) + rnd.nextDouble(-0.45, 0.45)
                val base = Vector2(x, stemY(x))
                val st = botStump(base, ang, stemWidth / 2 + rnd.nextDouble(0.06, 0.09) * H, rnd.nextDouble(0.050, 0.060) * H)
                if (!isFree(st.outline, true, gap, setOf(stem))) continue
                val lines = botClipOutside(st.outline + st.outline.first(), stems) + listOf(st.cut + st.cut.first())
                features.add(BotFeature(BotKind.COMPOSITE, st.outline, lines,
                    parts = listOf(st.cut to BotanicalRole.STUMP_CUT), defaultRole = BotanicalRole.STEM))
                break
            }
        }
    }

    /** Roses de Noël en bouton : deux penchées sous la branche, une ou deux dressées au-dessus. */
    fun placeAdventBuds() {
        val stems = stemCopies()
        val stem = features.first()
        val items = mutableListOf(false, false) + List(rnd.nextInt(1, 3)) { true }   // true : dressé
        for (upright in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                if (upright && ps.y < c.y) continue
                if (!upright && ps.y > c.y) continue
                val ang = (if (upright) -PI / 2 else PI / 2) + rnd.nextDouble(-0.5, 0.5)
                val h = rnd.nextDouble(0.15, 0.19) * H
                val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                val bud = botClosedBud(b, ang, h)
                if (!isFree(bud.outline, true, gap * 1.1)) continue
                val d = botMinDistance(bud.outline, true, stems)
                if (d > 0.28 * H || d < 0.06 * H) continue
                val target = stems.minByOrNull { botShapeDistance(bud.outline, true, it) }!!
                val stalk = botBaseStalk(b, ang, bud.outline, target, bud.inset) ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(BotFeature(BotKind.COMPOSITE, bud.outline, listOf(bud.outline + bud.outline.first()) + bud.dividers,
                    parts = listOf(bud.front to BotanicalRole.BUD_FRONT), defaultRole = BotanicalRole.BUD_SIDE))
                fixedLines.add(stalk)
                break
            }
        }
    }

    // ---------------- roses de Noël et houx

    /**
     * Roses de Noël (une grande, une plus petite) et bouton, ou grappes de
     * baies de houx, toutes reliées à la branche.
     */
    fun placeNoelBlooms(items: List<Pair<String, Boolean>>) {
        val stems = stemCopies()
        val stem = features.first()
        for ((kind, big) in items) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                var ang = atan2(c.y - ps.y, c.x - ps.x) + rnd.nextDouble(-0.35, 0.35)
                var basePoint: Vector2? = null
                var inset = 6.0
                val feature: BotFeature
                var minD = 0.07 * H
                var maxD = 0.30 * H
                when (kind) {
                    "hellebore" -> {
                        val r = (if (big) rnd.nextDouble(0.15, 0.18) else rnd.nextDouble(0.11, 0.13)) * H
                        val he = botHellebore(c, r, rnd.nextDouble(0.0, 2 * PI))
                        feature = BotFeature(BotKind.COMPOSITE, he.outline, he.lines, paint = he.paint,
                            parts = listOf(he.heart to BotanicalRole.HELLEBORE_HEART), defaultRole = BotanicalRole.HELLEBORE_PETAL)
                    }
                    "bouton" -> {
                        ang = -PI / 2 + rnd.nextDouble(-0.5, 0.5)
                        if (ps.y < c.y) continue
                        val size = rnd.nextDouble(0.12, 0.15) * H
                        val b = Vector2(c.x - cos(ang) * size * 0.5, c.y - sin(ang) * size * 0.5)
                        basePoint = b
                        val o = botBud(b, ang, size)
                        feature = BotFeature(BotKind.BUD, o, listOf(o + o.first()))
                    }
                    else -> {
                        // baies : petite grappe serrée près de la branche
                        val b = Vector2(c.x - cos(ang) * 0.02 * H, c.y - sin(ang) * 0.02 * H)
                        basePoint = b
                        val g = botBerries(b, ang, rnd.nextDouble(0.030, 0.034) * H, rnd.nextInt(3, 6), rnd)
                        feature = BotFeature(BotKind.GRAPES, g.outline, g.lines, grains = g.grains)
                        minD = 0.035 * H
                        maxD = 0.12 * H
                    }
                }
                val exclude = if (kind == "baies") setOf(stem) else emptySet()
                if (!isFree(feature.outline, true, if (kind == "baies") gap * 0.8 else gap * 1.2, exclude)) continue
                val d = botMinDistance(feature.outline, true, stems)
                if (d > maxD || d < minD) continue
                val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                val stalk = if (basePoint != null) {
                    botBaseStalk(basePoint, ang, feature.outline, target, inset)
                } else {
                    botBridge(feature.outline, target, rnd)
                } ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
    }

    /** Feuilles de houx en alternance le long de la branche (épineuses, à nervure). */
    fun placeHollyLeaves() {
        val stem = features.first()
        val stems = stemCopies()
        val otherStems = stems.filterIndexed { i, _ -> i != 1 }
        val n = rnd.nextInt(5, 7)
        val x0 = rnd.nextDouble(0.0, W)
        var side = if (rnd.nextBoolean()) 1 else -1
        for (i in 0 until n) {
            val x = botWrap(x0 + i * W / n + rnd.nextDouble(-0.04, 0.04) * W, W)
            for (attempt in 0 until 25) {
                val length = rnd.nextDouble(0.25, 0.31) * H
                val width = length * rnd.nextDouble(0.46, 0.54)
                val tilt = rnd.nextDouble(40.0, 70.0) * PI / 180.0
                val bend = rnd.nextDouble(-0.12, 0.12)
                val tang = atan2(stemDy(x), 1.0)
                val base = Vector2(x, stemY(x) + if (side > 0) -stemWidth / 2 + 4 else stemWidth / 2 - 4)
                val ang = if (side > 0) tang - tilt else tang + tilt
                val (outline, axis) = botHolly(base, ang, length, width, bend, rnd.nextInt(3, 5))
                val crossesStem = outline.any { p -> p.distanceTo(base) > 0.30 * length && stems.any { s -> polygonContains(s, p) } }
                if (crossesStem) continue
                if (botMinDistance(outline, true, otherStems) <= gap) continue
                if (!isFree(outline, true, gap, setOf(stem))) continue
                val lines = botClipOutside(outline + outline.first(), stems) + botClipOutside(axis, stems)
                features.add(BotFeature(BotKind.LEAF, outline, lines, axis = axis))
                break
            }
            side = -side
        }
        for (i in 0 until n) {
            if (i % 2 == 1) continue      // un tronçon pour deux feuilles
            val xa = botWrap(x0 + (i + 0.5) * W / n + rnd.nextDouble(-0.03, 0.03) * W, W)
            fixedLines.add(listOf(Vector2(xa, stemY(xa) - stemWidth / 2 - 3), Vector2(xa, stemY(xa) + stemWidth / 2 + 3)))
        }
    }

    // ---------------- lys, rose et iris

    /**
     * Fleurs mariales : un grand lys, un iris, une rose, souvent une
     * seconde rose ou un second lys, et un bouton de lys. Lys, iris et
     * bouton se dressent au-dessus de leur tige ; la rose, vue de face,
     * peut se trouver de part et d'autre.
     */
    fun placeMarianBlooms() {
        val stems = stemCopies()
        val stem = features.first()
        val blooms = mutableListOf("iris" to true, "lys" to true, "rose" to true)
        if (rnd.nextDouble() < 0.7) blooms.add((if (rnd.nextBoolean()) "rose" else "lys") to false)
        blooms.add("bouton" to false)
        for ((kind, big) in blooms) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                var ang = atan2(c.y - ps.y, c.x - ps.x) + rnd.nextDouble(-0.35, 0.35)
                if (kind != "rose") {
                    ang = -PI / 2 + rnd.nextDouble(-0.45, 0.45)
                    if (ps.y < c.y) continue
                }
                var basePoint: Vector2? = null
                var inset = 6.0
                val feature: BotFeature
                when (kind) {
                    "lys" -> {
                        val h = (if (big) rnd.nextDouble(0.32, 0.36) else rnd.nextDouble(0.26, 0.30)) * H
                        val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                        basePoint = b
                        val l = botLily(b, ang, h, rnd.nextDouble())
                        feature = BotFeature(BotKind.COMPOSITE, l.outline, listOf(l.outline + l.outline.first()) + l.dividers,
                            paint = l.stamens, parts = listOf(l.centre to BotanicalRole.LILY_FRONT), defaultRole = BotanicalRole.LILY_SIDE)
                    }
                    "iris" -> {
                        val h = rnd.nextDouble(0.31, 0.35) * H
                        val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                        basePoint = b
                        val ir = botIris(b, ang, h)
                        inset = ir.inset
                        feature = BotFeature(BotKind.COMPOSITE, ir.outline, ir.lines, paint = ir.veins,
                            parts = ir.parts, defaultRole = BotanicalRole.IRIS_STANDARD)
                    }
                    "rose" -> {
                        val r = (if (big) rnd.nextDouble(0.14, 0.16) else rnd.nextDouble(0.11, 0.13)) * H
                        val ro = botRose(c, r, rnd.nextDouble(0.0, 2 * PI))
                        feature = BotFeature(BotKind.COMPOSITE, ro.outline, ro.lines,
                            parts = listOf(ro.heart to BotanicalRole.ROSE_HEART, ro.ring to BotanicalRole.ROSE_INNER),
                            defaultRole = BotanicalRole.ROSE_OUTER)
                    }
                    else -> {
                        val size = rnd.nextDouble(0.13, 0.16) * H
                        val b = Vector2(c.x - cos(ang) * size * 0.5, c.y - sin(ang) * size * 0.5)
                        basePoint = b
                        val o = botBud(b, ang, size)
                        feature = BotFeature(BotKind.BUD, o, listOf(o + o.first()))
                    }
                }
                if (!isFree(feature.outline, true, gap * 1.2)) continue
                val d = botMinDistance(feature.outline, true, stems)
                if (d > 0.30 * H || d < 0.07 * H) continue
                val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                val stalk = if (basePoint != null) {
                    botBaseStalk(basePoint, ang, feature.outline, target, inset)
                } else {
                    botBridge(feature.outline, target, rnd)
                } ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
    }

    // ---------------- vigne et blé

    /** Feuilles de vigne en alternance, reliées au cep par un pétiole. */
    fun placeVineLeaves() {
        val stems = stemCopies()
        val stem = features.first()
        val n = rnd.nextInt(3, 5)
        val x0 = rnd.nextDouble(0.0, W)
        var side = if (rnd.nextBoolean()) 1 else -1
        for (i in 0 until n) {
            val x = botWrap(x0 + i * W / n + rnd.nextDouble(-0.05, 0.05) * W, W)
            for (attempt in 0 until 30) {
                val size = rnd.nextDouble(0.26, 0.33) * H
                val tilt = rnd.nextDouble(-0.55, 0.55)
                val petiole = rnd.nextDouble(0.09, 0.14) * H
                val ang = if (side > 0) -PI / 2 + tilt else PI / 2 + tilt
                val edge = stemY(x) + if (side > 0) -stemWidth / 2 else stemWidth / 2
                val base = Vector2(x, edge + if (side > 0) -petiole else petiole)
                val leaf = botVineLeaf(base, ang, size)
                if (!isFree(leaf.outline, true, gap, setOf(stem))) continue
                if (botMinDistance(leaf.outline, true, stems) < 0.03 * H) continue
                val target = stems.minByOrNull { botShapeDistance(leaf.outline, true, it) }!!
                val stalk = botBaseStalk(base, ang, leaf.outline, target) ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(BotFeature(BotKind.VINE_LEAF, leaf.outline,
                    listOf(leaf.outline + leaf.outline.first()) + leaf.veins, axis = leaf.mainVein))
                fixedLines.add(stalk)
                break
            }
            side = -side
        }
        // le cep est coupé en tronçons, entre les feuilles
        for (i in 0 until n) {
            val xa = botWrap(x0 + (i + 0.5) * W / n + rnd.nextDouble(-0.03, 0.03) * W, W)
            fixedLines.add(listOf(Vector2(xa, stemY(xa) - stemWidth / 2 - 3), Vector2(xa, stemY(xa) + stemWidth / 2 + 3)))
        }
    }

    /** Grappes qui pendent SOUS le cep, épis de blé qui se dressent AU-DESSUS. */
    fun placeGrapesAndWheat() {
        val stems = stemCopies()
        val stem = features.first()
        val items = mutableListOf<BotKind>()
        repeat(rnd.nextInt(1, 3)) { items.add(BotKind.GRAPES) }
        repeat(rnd.nextInt(2, 4)) { items.add(BotKind.WHEAT) }
        for (kind in items) {
            for (attempt in 0 until 300) {
                val x = rnd.nextDouble(0.0, W)
                val ang: Double
                val base: Vector2
                val feature: BotFeature
                val inset: Double
                if (kind == BotKind.GRAPES) {
                    ang = PI / 2 + rnd.nextDouble(-0.25, 0.25)
                    base = Vector2(x, stemY(x) + stemWidth / 2 + rnd.nextDouble(0.05, 0.10) * H)
                    val g = botGrapes(base, ang, rnd.nextDouble(0.040, 0.046) * H, rnd)
                    feature = BotFeature(kind, g.outline, g.lines, grains = g.grains)
                    inset = 6.0
                } else {
                    ang = -PI / 2 + rnd.nextDouble(-0.35, 0.35)
                    base = Vector2(x, stemY(x) - stemWidth / 2 - rnd.nextDouble(0.06, 0.13) * H)
                    val length = rnd.nextDouble(0.32, 0.38) * H
                    val w = botWheat(base, ang, length, 0.12 * H * rnd.nextDouble(0.92, 1.08), rnd.nextInt(5, 7))
                    feature = BotFeature(kind, w.outline, w.lines, axis = w.axis, paint = w.awns)
                    inset = w.inset
                }
                if (!isFree(feature.outline, true, gap, setOf(stem))) continue
                if (botMinDistance(feature.outline, true, stems) < 0.035 * H) continue
                val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                val stalk = botBaseStalk(base, ang, feature.outline, target, inset) ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                break
            }
        }
        // vrilles, peintes à la grisaille, là où il reste de la place
        val tendrilCount = rnd.nextInt(2, 4)
        var placed = 0
        for (attempt in 0 until 80) {
            if (placed >= tendrilCount) break
            val x = rnd.nextDouble(0.0, W)
            val up = rnd.nextBoolean()
            val start = Vector2(x, stemY(x) + if (up) -stemWidth / 2 else stemWidth / 2)
            val dir = (if (up) -PI / 2 else PI / 2) + rnd.nextDouble(-0.6, 0.6)
            val t = botTendril(start, dir, rnd.nextDouble(0.10, 0.15) * H, if (rnd.nextBoolean()) 1.0 else -1.0)
            if (clearance(t.drop(3), false, setOf(stem), gap * 0.35) < gap * 0.35) continue
            if (paintLines.any { p -> allOffsets.any { o -> botPolylineDistance(botShift(p, o), false, t, false) < gap * 0.5 } }) continue
            paintLines.add(t)
            placed++
        }
    }

    fun placeStem() {
        val base = rnd.nextDouble(0.35, 0.65) * H
        val a1 = rnd.nextDouble(0.07, 0.12) * H
        val p1 = rnd.nextDouble(0.0, 2 * PI)
        val a2 = rnd.nextDouble(0.02, 0.04) * H
        val p2 = rnd.nextDouble(0.0, 2 * PI)
        stemY = { x -> base + a1 * sin(2 * PI * x / W + p1) + a2 * sin(4 * PI * x / W + p2) }
        stemDy = { x -> a1 * 2 * PI / W * cos(2 * PI * x / W + p1) + a2 * 4 * PI / W * cos(4 * PI * x / W + p2) }
        if (motif == BotanicalMotif.NOIRE) {
            // sol enneigé : le dessus de la bande se soulève en petites congères
            // (périodiques en W, pour le raccord)
            val k1 = rnd.nextInt(3, 5)
            val q1 = rnd.nextDouble(0.0, 2 * PI)
            val q2 = rnd.nextDouble(0.0, 2 * PI)
            groundTop = { x ->
                val u = 0.5 + 0.5 * sin(2 * PI * k1 * x / W + q1)
                stemY(x) - stemWidth / 2 - 0.035 * H * u * u - 0.010 * H * sin(2 * PI * (k1 + 2) * x / W + q2)
            }
        }
        val n = 270
        val xs = (0..n).map { -W + 3 * W * it / n }
        val upper = xs.map { Vector2(it, groundTop(it)) }
        val lower = xs.map { Vector2(it, stemY(it) + stemWidth / 2) }
        stemOutline = upper + lower.reversed()
        features.add(BotFeature(BotKind.STEM, stemOutline, emptyList()))
        stemLines.add(upper)
        stemLines.add(lower)
    }

    fun placeLeaves() {
        val stem = features.first()
        val stems = stemCopies()
        val otherStems = stems.filterIndexed { i, _ -> i != 1 }
        val nLeaves = rnd.nextInt(leafCountMin, leafCountMax + 1) + (if (motif == BotanicalMotif.ROSE) 2 else 0)
        val x0 = rnd.nextDouble(0.0, W)
        var side = if (rnd.nextBoolean()) 1 else -1
        // Pâques, Rose, Souvenir : pédoncules déjà posés (fleurs placées avant les
        // feuilles), hors de la tige : une feuille ne doit pas les croiser.
        // (Limité à ces motifs pour ne pas modifier les œuvres déjà générées
        // des autres motifs.)
        val stalkParts = if (motif != BotanicalMotif.PAQUES && motif != BotanicalMotif.ROSE && motif != BotanicalMotif.SOUVENIR) emptyList() else fixedLines.flatMap { botClipOutside(it, stems) }.filter { it.size >= 2 }
        fun crossesStalk(outline: List<Vector2>): Boolean {
            val b = botBounds(outline)
            val ring = outline + outline.first()
            return stalkParts.any { sp ->
                val sb = botBounds(sp)
                allOffsets.any { o ->
                    if (botBoundsGap(b, sb, o) > 0.0) return@any false
                    val so = botShift(sp, o)
                    botPolylinesIntersect(so, ring) || so.any { polygonContains(outline, it) }
                }
            }
        }
        for (i in 0 until nLeaves) {
            val x = botWrap(x0 + i * W / nLeaves + rnd.nextDouble(-0.04, 0.04) * W, W)
            val kindRoll = rnd.nextDouble()
            val kind = if (motif == BotanicalMotif.PAQUES) 1
            else if (motif == BotanicalMotif.ROSE) (if (kindRoll < 0.6) 0 else 2)   // folioles ovales, à nervure
            else when {
                kindRoll < 0.45 -> 0   // en amande, à nervure
                kindRoll < 0.75 -> 1   // lancéolée, d'une seule pièce
                else -> 2              // ronde, à nervure
            }
            for (attempt in 0 until 25) {
                val length: Double
                val width: Double
                when (kind) {
                    0 -> { length = rnd.nextDouble(0.22, 0.30) * H * leafScale; width = length * rnd.nextDouble(0.40, 0.48) }
                    1 -> { length = rnd.nextDouble(0.28, 0.36) * H * leafScale; width = length * rnd.nextDouble(0.26, 0.30) }
                    else -> { length = rnd.nextDouble(0.17, 0.22) * H * leafScale; width = length * rnd.nextDouble(0.75, 0.88) }
                }
                val tilt = rnd.nextDouble(40.0, 65.0) * PI / 180.0
                val bend = rnd.nextDouble(-0.15, 0.15)
                val tang = atan2(stemDy(x), 1.0)
                val base = Vector2(x, stemY(x) + if (side > 0) -stemWidth / 2 + 4 else stemWidth / 2 - 4)
                val ang = if (side > 0) tang - tilt else tang + tilt
                val (outline, axis) = botLeaf(base, ang, length, width, bend)
                // la feuille ne doit pas recroiser sa tige (hors de sa base)
                // (jeunes feuilles plus petites : le voisinage de la base, qui
                // touche forcément la tige, est compté en largeurs de tige)
                val baseZone = if (leafScale < 1.0) max(0.30 * length, 1.3 * stemWidth) else 0.30 * length
                val crossesStem = outline.any { p ->
                    p.distanceTo(base) > baseZone && stems.any { s -> polygonContains(s, p) }
                }
                if (crossesStem) continue
                if (botMinDistance(outline, true, otherStems) <= gap) continue
                if (!isFree(outline, true, gap, setOf(stem))) continue
                if (crossesStalk(outline)) continue
                val lines = mutableListOf<List<Vector2>>()
                lines += botClipOutside(outline + outline.first(), stems)
                if (kind != 1) lines += botClipOutside(axis, stems)
                features.add(BotFeature(BotKind.LEAF, outline, lines, axis = if (kind != 1) axis else null))
                break
            }
            side = -side
        }
        // la tige est coupée en tronçons, entre les feuilles
        for (i in 0 until nLeaves) {
            val xa = botWrap(x0 + (i + 0.5) * W / nLeaves + rnd.nextDouble(-0.03, 0.03) * W, W)
            fixedLines.add(listOf(Vector2(xa, stemY(xa) - stemWidth / 2 - 3), Vector2(xa, stemY(xa) + stemWidth / 2 + 3)))
        }
    }

    fun placeBlooms() {
        val stems = stemCopies()
        val stem = features.first()
        fun flowerKind(): BotKind {
            val total = tulipWeight + poppyWeight + rosetteWeight
            val r = rnd.nextDouble() * total
            return when {
                r < tulipWeight -> BotKind.TULIP
                r < tulipWeight + poppyWeight -> BotKind.POPPY
                else -> BotKind.ROSETTE
            }
        }
        val blooms = mutableListOf(flowerKind() to true)
        if (rnd.nextDouble() < secondFlowerProbability) blooms.add(flowerKind() to false)
        repeat(rnd.nextInt(budCountMin, budCountMax + 1)) { blooms.add(BotKind.BUD to false) }
        val familyOrder = mutableListOf(0, 1).also { if (rnd.nextBoolean()) it.reverse() }
        var familyIndex = 0

        for ((kind, big) in blooms) {
            for (attempt in 0 until 300) {
                val c = Vector2(rnd.nextDouble(0.0, W), rnd.nextDouble(0.0, H))
                val ps = botNearestOnPolygons(c, stems)
                var ang = atan2(c.y - ps.y, c.x - ps.x) + rnd.nextDouble(-0.35, 0.35)
                if (kind == BotKind.TULIP || kind == BotKind.BUD) {
                    // tulipes et boutons poussent vers le HAUT, au-dessus de leur tige
                    ang = -PI / 2 + rnd.nextDouble(-0.55, 0.55)
                    if (ps.y < c.y) continue
                }
                var basePoint: Vector2? = null
                val feature: BotFeature
                when (kind) {
                    BotKind.TULIP -> {
                        val h = (if (big) rnd.nextDouble(0.33, 0.38) else rnd.nextDouble(0.25, 0.29)) * H
                        val b = Vector2(c.x - cos(ang) * h * 0.5, c.y - sin(ang) * h * 0.5)
                        basePoint = b
                        val t = botTulip(b, ang, h, rnd.nextDouble(), rnd.nextDouble())
                        feature = BotFeature(kind, t.outline, listOf(t.outline + t.outline.first()) + t.dividers,
                            inner = t.centre, family = familyOrder[familyIndex % 2])
                    }
                    BotKind.POPPY -> {
                        val r = (if (big) rnd.nextDouble(0.16, 0.19) else rnd.nextDouble(0.12, 0.14)) * H
                        val p = botPoppy(c, r, rnd.nextDouble(0.0, PI), rnd.nextDouble(0.0, 6.3))
                        feature = BotFeature(kind, p.outline, listOf(p.outline + p.outline.first(), p.heart + p.heart.first()) + p.dividers,
                            inner = p.heart)
                    }
                    BotKind.ROSETTE -> {
                        val r = (if (big) rnd.nextDouble(0.15, 0.18) else rnd.nextDouble(0.11, 0.13)) * H
                        val petals = 5 + rnd.nextInt(3)
                        val p = botRosette(c, r, petals, rnd.nextDouble(0.0, 2 * PI))
                        feature = BotFeature(kind, p.outline, listOf(p.outline + p.outline.first(), p.heart + p.heart.first()) + p.dividers,
                            inner = p.heart, family = familyOrder[familyIndex % 2])
                    }
                    else -> {
                        val size = rnd.nextDouble(0.12, 0.15) * H
                        val b = Vector2(c.x - cos(ang) * size * 0.5, c.y - sin(ang) * size * 0.5)
                        basePoint = b
                        val o = botBud(b, ang, size)
                        feature = BotFeature(kind, o, listOf(o + o.first()))
                    }
                }
                if (!isFree(feature.outline, true, gap * 1.2)) continue
                val d = botMinDistance(feature.outline, true, stems)
                if (d > 0.30 * H || d < 0.07 * H) continue
                val target = stems.minByOrNull { botShapeDistance(feature.outline, true, it) }!!
                val stalk = if (basePoint != null) {
                    botBaseStalk(basePoint, ang, feature.outline, target)
                } else {
                    botBridge(feature.outline, target, rnd)
                } ?: continue
                if (clearance(stalk, false, setOf(stem), gap * 0.45) < gap * 0.45) continue
                features.add(feature)
                fixedLines.add(stalk)
                if (kind == BotKind.TULIP || kind == BotKind.ROSETTE) familyIndex++
                break
            }
        }
    }

    // ---------------- ponts et pièces

    fun network(): BotResult {
        // Le fond doit être entièrement découpé en pièces fermées : si une
        // région de fond fait le tour de la tuile (aucune chaîne de motifs
        // et de ponts ne la traverse), on recommence avec des ponts plus
        // longs.
        var bridges = emptyList<List<Vector2>>()
        var faces = emptyList<List<Vector2>>()
        val baseLines = features.flatMap { it.lines } + fixedLines
        // (une région de fond « sans fin » touche le bord du domaine étendu ;
        // son point intérieur peut tomber hors de la tuile : on la cherche
        // donc parmi TOUTES les faces, voir faces())
        fun unbounded(@Suppress("UNUSED_PARAMETER") fs: List<List<Vector2>>) = lastFacesUnbounded
        for (attempt in 0 until 3) {
            bridges = bridgesUpTo(0.34 * H * (1.0 + 0.4 * attempt))
            faces = faces(baseLines + bridges)
            if (!unbounded(faces)) break
        }
        // Dernier recours (motifs clairsemés) : de longues lignes de fond
        // qui relient la branche à sa copie du dessus, pour que le fond ne
        // fasse pas le tour de la tuile.
        if (unbounded(faces)) {
            val connectors = stemConnectors(bridges)
            bridges = bridges + connectors
            faces = faces(baseLines + bridges)
        }
        return mergeAndFinish(baseLines, bridges, faces)
    }

    /** Lignes de fond qui montent de la branche jusqu'à la branche de la tuile du dessus. */
    fun stemConnectors(existing: List<List<Vector2>>): List<List<Vector2>> {
        val result = mutableListOf<List<Vector2>>()
        for (attempt in 0 until 200) {
            if (result.size >= 2) break
            val x0 = rnd.nextDouble(0.0, W)
            val x1 = x0 + rnd.nextDouble(-0.25, 0.25) * W
            val p0 = Vector2(x0, stemY(x0) - stemWidth / 2 + 4)
            val p3 = Vector2(x1, stemY(x1) + stemWidth / 2 - 4 - H)
            val k = 0.35 * (p0.y - p3.y)
            val curve = botCubic(p0, Vector2(p0.x, p0.y - k), Vector2(p3.x, p3.y + k), p3, 40)
            val inner = botSubPolyline(curve, 0.05, 0.95)
            var ok = true
            for (f in features) {
                if (f.kind == BotKind.STEM) continue
                for (o in allOffsets) {
                    if (botBoundsGap(botBounds(inner), f.bounds, o) > gap * 0.6) continue
                    if (botShapeDistance(inner, false, botShift(f.outline, o)) < gap * 0.6) { ok = false; break }
                }
                if (!ok) break
            }
            if (ok) {
                loop@ for (other in existing + fixedLines + result) {
                    for (o in allOffsets) {
                        val oc = botShift(other, o)
                        if (botPolylineDistance(oc, false, inner, false) < gap * 0.9 || botPolylinesIntersect(oc, curve)) { ok = false; break@loop }
                    }
                }
            }
            if (ok && result.none { r -> abs(r.first().x - p0.x) < 0.3 * W }) result.add(curve)
        }
        return result
    }

    fun bridgesUpTo(maxBridge: Double): List<List<Vector2>> {
        // 1. Tous les ponts possibles, du plus court au plus long.
        class Cand(val d: Double, val i: Int, val j: Int, val o: Vector2)
        val cands = mutableListOf<Cand>()
        for (i in features.indices) for (j in i until features.size) {
            val fa = features[i]
            val fb = features[j]
            if (fa.kind == BotKind.STEM && fb.kind == BotKind.STEM) continue
            for (o in allOffsets) {
                if (i == j && o.x == 0.0 && o.y == 0.0) continue
                if ((fa.kind == BotKind.STEM || fb.kind == BotKind.STEM) && o.x != 0.0) continue
                if (botBoundsGap(fa.bounds, fb.bounds, o) > maxBridge) continue
                val d = botShapeDistance(fa.outline, true, botShift(fb.outline, o))
                if (d < gap * 0.5 || d > maxBridge) continue
                cands.add(Cand(d, i, j, o))
            }
        }
        cands.sortBy { it.d }
        val bridges = mutableListOf<List<Vector2>>()
        for (c in cands) {
            val a = features[c.i].outline
            val b = botShift(features[c.j].outline, c.o)
            for (t in 0 until 3) {
                val curve = botBridge(a, b, rnd) ?: continue
                val inner = botSubPolyline(curve, 0.12, 0.88)
                // loin des autres motifs…
                var ok = true
                for (f in features) {
                    for (o in offsetsOf(f)) {
                        val isA = f === features[c.i] && o.x == 0.0 && o.y == 0.0
                        val isB = f === features[c.j] && o.x == c.o.x && o.y == c.o.y
                        if (isA || isB) continue
                        if (botBoundsGap(botBounds(inner), f.bounds, o) > gap * 0.6) continue
                        if (botShapeDistance(inner, false, botShift(f.outline, o)) < gap * 0.6) { ok = false; break }
                    }
                    if (!ok) break
                }
                // …et des autres ponts et pédoncules
                if (ok) {
                    loop@ for (other in bridges + fixedLines) {
                        for (o in allOffsets) {
                            val oc = botShift(other, o)
                            if (botBoundsGap(botBounds(oc), botBounds(curve), Vector2.ZERO) > gap) continue
                            if (botPolylineDistance(oc, false, inner, false) < gap * 0.9 || botPolylinesIntersect(oc, curve)) {
                                ok = false; break@loop
                            }
                        }
                    }
                }
                if (ok) { bridges.add(curve); break }
            }
        }
        return bridges
    }

    fun mergeAndFinish(baseLines: List<List<Vector2>>, bridges: List<List<Vector2>>, faces: List<List<Vector2>>): BotResult {
        // 2. Rôle des pièces avec tous les ponts.
        val roles = faces.map { classify(it) }

        // 3. Retrait de ponts : fusionne deux pièces de fond voisines tant
        //    que la pièce obtenue reste sous la taille cible. Les pièces
        //    sont repérées par leur indice canonique (union-find) ; un
        //    pont dont les deux côtés appartiennent déjà à la même pièce
        //    n'est jamais retiré (il créerait un trou ou une pièce qui
        //    ferait le tour de la tuile).
        val parent = IntArray(faces.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        val area = DoubleArray(faces.size) { polygonAreaAbs(faces[it]) }
        val target = backgroundPieceRatio * W * H
        val order = bridges.indices.toMutableList()
        for (k in order.indices.reversed()) {
            val j = rnd.nextInt(k + 1)
            val tmp = order[k]; order[k] = order[j]; order[j] = tmp
        }
        val removed = BooleanArray(bridges.size)
        for (bi in order) {
            val (fa, fb) = facesBeside(bridges[bi], faces) ?: continue
            if (roles[fa].role != BotanicalRole.BACKGROUND || roles[fb].role != BotanicalRole.BACKGROUND) continue
            val ra = find(fa)
            val rb = find(fb)
            if (ra == rb) continue
            if (area[ra] + area[rb] > target) continue
            parent[rb] = ra
            area[ra] += area[rb]
            removed[bi] = true
        }

        // 4. Les pièces de fond restées trop grandes (motifs trop
        //    éloignés pour être reliés par des ponts) sont recoupées par un
        //    ARC doux, perpendiculaire à leur plus grande longueur.
        val kept = bridges.filterIndexed { i, _ -> !removed[i] }
        var current = faces(baseLines + kept)
        val cuts = arcCuts(current, current.map { classify(it).role }, target)

        // 5. Pièces définitives.
        if (cuts.isNotEmpty()) current = faces(baseLines + kept + cuts)
        val finalRoles = current.map { classify(it) }
        return BotResult(current, finalRoles.map { it.role }, finalRoles.map { it.family },
            paintLines + features.flatMap { it.paint })
    }

    /**
     * Arcs de recoupe des grandes pièces de fond. Une coupe n'est gardée
     * que si elle donne exactement deux parts, pas trop petites, et
     * qu'un verrier pourrait couper (aucune pointe ni lanière trop
     * étroite : voir newNarrowResidue, Arrangement.kt).
     */
    fun arcCuts(faces: List<List<Vector2>>, roles: List<BotanicalRole>, target: Double): List<List<Vector2>> {
        val cuts = mutableListOf<List<Vector2>>()
        val narrowRadius = 0.035 * H
        val work = faces.indices.filter { roles[it] == BotanicalRole.BACKGROUND }.map { faces[it] }.toMutableList()
        var guard = 0
        while (guard++ < 60) {
            work.sortByDescending { polygonAreaAbs(it) }
            val big = work.firstOrNull() ?: break
            val bigArea = polygonAreaAbs(big)
            if (bigArea <= 2.5 * target) break
            var done = false
            val residue = narrowResidueCells(big, narrowRadius)
            for (attempt in 0 until 12) {
                val (center, longAngle) = botPrincipalAxis(big)
                val b = botBounds(big)
                val diag = hypot(b[2] - b[0], b[3] - b[1])
                val p = Vector2(
                    center.x + rnd.nextDouble(-0.15, 0.15) * (b[2] - b[0]),
                    center.y + rnd.nextDouble(-0.15, 0.15) * (b[3] - b[1])
                )
                val angle = longAngle + PI / 2 + rnd.nextDouble(-0.35, 0.35)
                val radius = rnd.nextDouble(0.8, 2.5) * H
                val side = if (rnd.nextBoolean()) 1.0 else -1.0
                if (!polygonContains(big, p)) continue
                val arc = botArc(p, angle, radius, side, diag)
                val inside = botClipInside(arc, big)
                val piece = inside.minByOrNull { botPolylineDistance(it, false, listOf(p, p), false) } ?: continue
                val parts = splitPolygonByPolyline(big, piece)
                if (parts.size != 2) continue
                if (parts.any { polygonAreaAbs(it) < 0.3 * target }) continue
                if (parts.any { newNarrowResidue(it, residue, narrowRadius) > 2.0 }) continue
                cuts.add(piece)
                work.remove(big)
                work.addAll(parts)
                done = true
                break
            }
            if (!done) work.remove(big)
        }
        return cuts
    }

    /** Vrai si le dernier calcul de faces() a trouvé une région de fond sans fin. */
    var lastFacesUnbounded = false

    /** Faces canoniques de l'arrangement (domaine étendu [-W, 2W] × [-H, 2H]). */
    fun faces(lines: List<List<Vector2>>): List<List<Vector2>> {
        val minX = -W; val minY = -H; val maxX = 2 * W; val maxY = 2 * H
        val segments = mutableListOf<Segment>()
        fun addLine(line: List<Vector2>, o: Vector2) {
            for (k in 0 until line.size - 1) {
                val s = Segment(Vector2(line[k].x + o.x, line[k].y + o.y), Vector2(line[k + 1].x + o.x, line[k + 1].y + o.y))
                clipSegmentToRect(s, minX, minY, maxX, maxY)?.let { segments.add(it) }
            }
        }
        for (line in lines) for (o in allOffsets) addLine(line, o)
        for (line in stemLines) for (o in verticalOffsets) addLine(line, o)
        segments += polygonEdgeSegments(listOf(Vector2(minX, minY), Vector2(maxX, minY), Vector2(maxX, maxY), Vector2(minX, maxY)))
        val all = polygonizeSegments(segments, 1.0)
        lastFacesUnbounded = all.any { f ->
            val b = botBounds(f)
            val touchesBorder = b[0] <= minX + 1e-6 || b[1] <= minY + 1e-6 || b[2] >= maxX - 1e-6 || b[3] >= maxY - 1e-6
            touchesBorder && b[0] < W && b[2] > 0.0 && b[1] < H && b[3] > 0.0
        }
        return all.filter { face ->
            val p = interiorPoint(face)
            p.x >= 0.0 && p.x < W && p.y >= 0.0 && p.y < H
        }
    }

    /** Les deux pièces (indices canoniques) de part et d'autre du milieu d'un pont. */
    fun facesBeside(bridge: List<Vector2>, faces: List<List<Vector2>>): Pair<Int, Int>? {
        val mid = bridge.size / 2
        val a = bridge[mid - 1]
        val b = bridge[mid]
        val m = Vector2((a.x + b.x) / 2, (a.y + b.y) / 2)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l = hypot(dx, dy)
        if (l == 0.0) return null
        val n = Vector2(-dy / l * 1.5, dx / l * 1.5)
        val left = faceContaining(Vector2(m.x + n.x, m.y + n.y), faces) ?: return null
        val right = faceContaining(Vector2(m.x - n.x, m.y - n.y), faces) ?: return null
        return left to right
    }

    fun faceContaining(p: Vector2, faces: List<List<Vector2>>): Int? {
        for (o in allOffsets) {
            val q = Vector2(p.x - o.x, p.y - o.y)
            for ((i, f) in faces.withIndex()) {
                val b = botBounds(f)
                if (q.x < b[0] || q.x > b[2] || q.y < b[1] || q.y > b[3]) continue
                if (polygonContains(f, q)) return i
            }
        }
        return null
    }

    class Classified(val role: BotanicalRole, val family: Int)

    /** Rôle d'une pièce : on teste un point bien intérieur contre chaque motif (copies comprises). */
    fun classify(face: List<Vector2>): Classified {
        val p = botDeepPoint(face)
        val order = listOf(BotKind.STEM, BotKind.COMPOSITE, BotKind.GRAPES, BotKind.WHEAT, BotKind.PALM, BotKind.VINE_LEAF,
            BotKind.TULIP, BotKind.POPPY, BotKind.ROSETTE, BotKind.BUD, BotKind.LEAF)
        for (kind in order) for (f in features) {
            if (f.kind != kind) continue
            for (o in offsetsOf(f)) {
                val q = Vector2(p.x - o.x, p.y - o.y)
                val b = f.bounds
                if (q.x < b[0] || q.x > b[2] || q.y < b[1] || q.y > b[3]) continue
                if (!polygonContains(f.outline, q)) continue
                return when (kind) {
                    BotKind.STEM -> Classified(BotanicalRole.STEM, -1)
                    BotKind.TULIP -> Classified(
                        if (f.inner != null && polygonContains(f.inner, q)) BotanicalRole.TULIP_FRONT else BotanicalRole.TULIP_SIDE,
                        f.family)
                    BotKind.POPPY -> Classified(
                        if (f.inner != null && polygonContains(f.inner, q)) BotanicalRole.POPPY_HEART else BotanicalRole.POPPY_PETAL, -1)
                    BotKind.ROSETTE -> Classified(
                        if (f.inner != null && polygonContains(f.inner, q)) BotanicalRole.ROSETTE_HEART else BotanicalRole.ROSETTE_PETAL,
                        f.family)
                    BotKind.BUD -> Classified(BotanicalRole.BUD, -1)
                    BotKind.COMPOSITE -> Classified(
                        f.parts.firstOrNull { polygonContains(it.first, q) }?.second ?: f.defaultRole, -1)
                    BotKind.GRAPES -> Classified(BotanicalRole.GRAPE, -1)
                    BotKind.WHEAT -> {
                        val near = botNearestOnPolyline(q, f.axis!!)
                        val light = (q.x - near.x) * -0.35 + (q.y - near.y) * -1.0 > 0
                        Classified(if (light) BotanicalRole.WHEAT_LIGHT else BotanicalRole.WHEAT_DARK, -1)
                    }
                    BotKind.PALM -> {
                        val near = botNearestOnPolyline(q, f.axis!!)
                        val light = (q.x - near.x) * -0.35 + (q.y - near.y) * -1.0 > 0
                        Classified(if (light) BotanicalRole.PALM_LIGHT else BotanicalRole.PALM_DARK, -1)
                    }
                    BotKind.VINE_LEAF, BotKind.LEAF -> {
                        val axis = f.axis ?: return Classified(BotanicalRole.LEAF_LIGHT, -1)
                        // côté de la nervure tourné vers la lumière (en haut à gauche) : clair
                        val near = botNearestOnPolyline(q, axis)
                        val light = (q.x - near.x) * -0.35 + (q.y - near.y) * -1.0 > 0
                        Classified(if (light) BotanicalRole.LEAF_LIGHT else BotanicalRole.LEAF_DARK, -1)
                    }
                }
            }
        }
        return Classified(BotanicalRole.BACKGROUND, -1)
    }
}

// --------------------------------------------------
// FORMES
// --------------------------------------------------

private fun botCubic(p0: Vector2, p1: Vector2, p2: Vector2, p3: Vector2, n: Int, skipFirst: Boolean = false): List<Vector2> =
    (0..n).filter { !(skipFirst && it == 0) }.map { i ->
        val t = i.toDouble() / n
        val u = 1 - t
        Vector2(
            u * u * u * p0.x + 3 * t * u * u * p1.x + 3 * t * t * u * p2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3 * t * u * u * p1.y + 3 * t * t * u * p2.y + t * t * t * p3.y
        )
    }

/** Repère local (x vers la droite, y vers le sommet) → tuile ; angle = direction base → sommet. */
private fun botPlace(points: List<Vector2>, base: Vector2, angle: Double): List<Vector2> {
    val ux = cos(angle)
    val uy = sin(angle)
    return points.map { Vector2(base.x + ux * it.y + uy * it.x, base.y + uy * it.y - ux * it.x) }
}

/** Feuille : axe légèrement courbé, plus large vers 40 % de la longueur, base étroite, pointe fine. */
private fun botLeaf(base: Vector2, angle: Double, length: Double, width: Double, bend: Double, n: Int = 24): Pair<List<Vector2>, List<Vector2>> {
    val tip = Vector2(base.x + cos(angle) * length, base.y + sin(angle) * length)
    val nx = -sin(angle)
    val ny = cos(angle)
    val ctrl = Vector2((base.x + tip.x) / 2 + nx * bend * length, (base.y + tip.y) / 2 + ny * bend * length)
    val axis = mutableListOf<Vector2>()
    val normals = mutableListOf<Vector2>()
    for (i in 0..n) {
        val t = i.toDouble() / n
        val u = 1 - t
        axis.add(Vector2(u * u * base.x + 2 * t * u * ctrl.x + t * t * tip.x, u * u * base.y + 2 * t * u * ctrl.y + t * t * tip.y))
        val dx = 2 * u * (ctrl.x - base.x) + 2 * t * (tip.x - ctrl.x)
        val dy = 2 * u * (ctrl.y - base.y) + 2 * t * (tip.y - ctrl.y)
        val l = hypot(dx, dy)
        normals.add(Vector2(-dy / l, dx / l))
    }
    fun w(t: Double) = width * 0.5 * sin(PI * t.pow(0.8)).pow(0.85) + 0.10 * width * (1 - t).pow(3)
    val left = (0..n).map { Vector2(axis[it].x + normals[it].x * w(it.toDouble() / n), axis[it].y + normals[it].y * w(it.toDouble() / n)) }
    val right = (0..n).map { Vector2(axis[it].x - normals[it].x * w(it.toDouble() / n), axis[it].y - normals[it].y * w(it.toDouble() / n)) }
    // gauche (base → pointe) puis droite (pointe → base), sans répéter la pointe
    return (left + right.reversed().drop(1)) to axis
}

private class BotTulip(val outline: List<Vector2>, val dividers: List<List<Vector2>>, val centre: List<Vector2>)

/**
 * Tulipe vue de face : coupe en forme d'œuf, deux pétales latéraux à
 * pointe arrondie, pétale central devant (plus clair). open (0..1) :
 * pointes latérales plus écartées ; tip (0..1) : pétale central plus haut
 * et plus pointu.
 */
private fun botTulip(base: Vector2, angle: Double, h: Double, open: Double, tip: Double): BotTulip {
    val w = 0.66 * h
    val lt = Vector2(-(0.40 + 0.06 * open) * w, 0.88 * h)
    val vl = Vector2(-0.20 * w, 0.74 * h)
    val ct = Vector2(0.0, (0.95 + 0.05 * tip) * h)
    val left = botCubic(Vector2(0.0, 0.0), Vector2(-0.32 * w, 0.0), Vector2(-0.66 * w, 0.30 * h), Vector2(-0.50 * w, 0.62 * h), 16) +
            botCubic(Vector2(-0.50 * w, 0.62 * h), Vector2(-0.40 * w, 0.82 * h), Vector2(lt.x - 0.05 * w, lt.y - 0.10 * h), lt, 10, true)
    val topL = botCubic(lt, Vector2(lt.x + 0.10 * w, lt.y + 0.05 * h), Vector2(-0.27 * w, 0.78 * h), vl, 10, true)
    val topC = botCubic(vl, Vector2(-0.21 * w, 0.86 * h), Vector2(-0.11 * w * (1 - 0.6 * tip), ct.y), ct, 12, true)
    val half = left + topL + topC
    val outlineLocal = half + half.dropLast(1).reversed().map { Vector2(-it.x, it.y) }
    val divs = listOf(-1.0, 1.0).map { s ->
        botCubic(Vector2(s * 0.03 * w, -0.03 * h), Vector2(s * 0.30 * w, 0.24 * h), Vector2(s * 0.27 * w, 0.56 * h), Vector2(s * 0.20 * w, 0.74 * h), 20) +
                Vector2(s * 0.195 * w, 0.78 * h)
    }
    val divL = botCubic(Vector2(-0.03 * w, -0.03 * h), Vector2(-0.30 * w, 0.24 * h), Vector2(-0.27 * w, 0.56 * h), vl, 20)
    val centreLocal = divL + topC + topC.dropLast(1).reversed().map { Vector2(-it.x, it.y) } +
            Vector2(-vl.x, vl.y) + divL.dropLast(1).reversed().map { Vector2(-it.x, it.y) }
    val outline = botPlace(outlineLocal.dropLast(1), base, angle)
    return BotTulip(outline, divs.map { botPlace(it, base, angle) }, botPlace(centreLocal, base, angle))
}

private class BotRound(val outline: List<Vector2>, val heart: List<Vector2>, val dividers: List<List<Vector2>>)

/** Coquelicot vu de face : 4 grands pétales au bord froissé, 2 un peu plus grands, cœur sombre. */
private fun botPoppy(c: Vector2, r: Double, rot: Double, phase: Double): BotRound {
    val outline = (0 until 240).map { i ->
        val th = rot + 2 * PI * i / 240
        val big = 1 + 0.06 * cos(2 * (th - rot))
        val lobe = (0.80 + 0.20 * abs(cos(2 * (th - rot))).pow(0.5)) * big
        val crinkle = 1 + 0.025 * sin(14 * (th - rot) + phase)
        val rr = r * lobe * crinkle
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val rc = 0.24 * r
    val heart = botCircle(c, rc, 48)
    val divs = (0 until 4).map { k ->
        val th = rot + PI / 4 + k * PI / 2
        (0..20).map { i ->
            val t = i / 20.0
            val rr = rc * 0.8 + (0.86 * r - rc * 0.8) * t
            val a = th + 0.16 * sin(PI * t) * (if (k % 2 == 0) 1 else -1)
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        }
    }
    return BotRound(outline, heart, divs)
}

/** Rosace : cœur + couronne festonnée découpée en n pétales. */
private fun botRosette(c: Vector2, r: Double, n: Int, rot: Double): BotRound {
    val outline = (0 until 180).map { i ->
        val th = rot + 2 * PI * i / 180
        val rr = r * (0.80 + 0.20 * abs(cos(n * (th - rot) / 2)).pow(0.7))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val rc = 0.32 * r
    val heart = botCircle(c, rc, 48)
    val divs = (0 until n).map { k ->
        val th = rot + (2 * k + 1) * PI / n
        (0..16).map { i ->
            val t = i / 16.0
            val rr = rc * 0.9 + (0.82 * r - rc * 0.9) * t
            val a = th + 0.12 * sin(PI * t)
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        }
    }
    return BotRound(outline, heart, divs)
}

/** Bouton : goutte, rond en bas, pointu en haut. */
private fun botBud(base: Vector2, angle: Double, size: Double): List<Vector2> {
    val local = (0 until 72).map { i ->
        val th = 2 * PI * i / 72
        val r = 0.5 * size
        val x = r * sin(th) * (0.75 + 0.25 * cos(th)).pow(1.2) * 0.9
        val y = -r * cos(th) + 0.25 * size * (1 - cos(th)) * 0.5 + 0.55 * size
        Vector2(x, y)
    }
    return botPlace(local, base, angle)
}

private class BotVineLeaf(val outline: List<Vector2>, val veins: List<List<Vector2>>, val mainVein: List<Vector2>)

/**
 * Feuille de vigne : cinq lobes (le lobe central plus grand), bord
 * légèrement denté, échancrure à la base où s'attache le pétiole. Trois
 * nervures partent du point d'attache (6 unités au-dessus de la base,
 * là où arrive le pétiole) vers les trois grands lobes : la feuille est
 * faite de 4 pièces.
 */
private fun botVineLeaf(base: Vector2, angle: Double, size: Double): BotVineLeaf {
    val r0 = 0.5 * size
    fun lobes(phi: Double): Double {
        val a = abs(phi)
        var l = 0.66
        l += 0.36 * kotlin.math.exp(-(phi / 0.40).pow(2))
        l += 0.30 * kotlin.math.exp(-((a - 1.25) / 0.40).pow(2))
        l += 0.15 * kotlin.math.exp(-((a - 2.30) / 0.38).pow(2))
        l *= 1 + 0.035 * cos(22 * phi)
        l *= 1 - 0.8 * kotlin.math.exp(-((a - PI) / 0.30).pow(2))
        return r0 * l
    }
    val sinus = lobes(PI)
    val c = Vector2(0.0, sinus)                       // centre : la base (0, 0) est le fond de l'échancrure
    fun at(phi: Double, f: Double = 1.0) = Vector2(c.x + lobes(phi) * f * sin(phi), c.y + lobes(phi) * f * cos(phi))
    val local = (0 until 200).map { at(-PI + 2 * PI * it / 200) }
    val joint = Vector2(0.0, 6.0)
    val veins = listOf(0.0, -1.2, 1.2).map { phi ->
        val tip = at(phi, 1.05)
        val ctrl = Vector2(tip.x * 0.30, joint.y + (tip.y - joint.y) * 0.55)
        (0..16).map { i ->
            val t = i / 16.0
            val u = 1 - t
            Vector2(u * u * joint.x + 2 * t * u * ctrl.x + t * t * tip.x, u * u * joint.y + 2 * t * u * ctrl.y + t * t * tip.y)
        }
    }
    return BotVineLeaf(botPlace(local, base, angle), veins.map { botPlace(it, base, angle) }, botPlace(veins[0], base, angle))
}

private class BotGrapes(val outline: List<Vector2>, val grains: List<List<Vector2>>, val lines: List<List<Vector2>>)

/**
 * Grappe de raisin qui pend depuis `top` (angle = direction de la
 * grappe, vers le bas) : grains ronds en rangs décalés (3, 4, 3, 2, 1),
 * qui se chevauchent un peu ; les grains du milieu passent devant. Le
 * contour d'un grain est coupé là où un grain de devant le recouvre.
 */
private fun botGrapes(top: Vector2, angle: Double, rg: Double, rnd: Random): BotGrapes {
    val rows = listOf(3, 4, 3, 2, 1)
    val centers = mutableListOf<Pair<Vector2, Double>>()
    rows.forEachIndexed { k, count ->
        for (i in 0 until count) {
            val x = (i - (count - 1) / 2.0) * 1.62 * rg + (if (k == 0 && i == 1) 0.0 else rnd.nextDouble(-0.08, 0.08) * rg)
            val y = rg + k * 1.40 * rg + (if (k == 0) 0.0 else rnd.nextDouble(-0.08, 0.08) * rg)
            val r = rg * (if (k == 0 && i == 1) 1.0 else rnd.nextDouble(0.94, 1.06))
            centers.add(Vector2(x, y) to r)
        }
    }
    // profondeur : les grains du milieu devant, les bords derrière
    val order = centers.indices.sortedBy { abs(centers[it].first.x) / rg + 0.4 * rnd.nextDouble() }
    val circles = order.map { i ->
        val (c, r) = centers[i]
        botPlace((0 until 48).map { Vector2(c.x + r * cos(2 * PI * it / 48), c.y + r * sin(2 * PI * it / 48)) }, top, angle)
    }
    val lines = mutableListOf<List<Vector2>>()
    circles.forEachIndexed { i, circle ->
        val front = circles.subList(0, i)
        lines += if (front.isEmpty()) listOf(circle + circle.first()) else botClipOutside(circle + circle.first(), front)
    }
    // contour de la grappe : points des cercles qui ne sont dans aucun autre, triés autour du centre
    val all = circles.flatMapIndexed { i, circle ->
        circle.filter { p -> circles.withIndex().none { (j, o) -> j != i && polygonContains(o, p) } }
    }
    val cx = all.sumOf { it.x } / all.size
    val cy = all.sumOf { it.y } / all.size
    val outline = all.sortedBy { atan2(it.y - cy, it.x - cx) }
    return BotGrapes(outline, circles, lines)
}

private class BotLily(val outline: List<Vector2>, val dividers: List<List<Vector2>>, val centre: List<Vector2>, val stamens: List<List<Vector2>>)

/**
 * Lys (Lilium candidum) vu de côté : trompe étroite qui s'évase, deux
 * pétales latéraux dont la pointe se recourbe vers l'extérieur, pétale
 * central devant ; étamines peintes à la grisaille, qui dépassent du
 * calice. open (0..1) : pointes plus ou moins recourbées.
 */
private fun botLily(base: Vector2, angle: Double, h: Double, open: Double): BotLily {
    val w = 0.85 * h
    val lt = Vector2(-(0.62 + 0.08 * open) * w, (0.84 - 0.06 * open) * h)
    val vl = Vector2(-0.18 * w, 0.82 * h)
    val ct = Vector2(0.0, 1.00 * h)
    val left = botCubic(Vector2(0.0, 0.0), Vector2(-0.12 * w, 0.05 * h), Vector2(-0.16 * w, 0.40 * h), Vector2(-0.24 * w, 0.62 * h), 14) +
            botCubic(Vector2(-0.24 * w, 0.62 * h), Vector2(-0.36 * w, 0.80 * h), Vector2(-0.58 * w, 0.88 * h), lt, 12, true)
    val topL = botCubic(lt, Vector2(lt.x + 0.10 * w, lt.y + 0.18 * h), Vector2(-0.30 * w, 0.94 * h), vl, 12, true)
    val topC = botCubic(vl, Vector2(-0.16 * w, 0.92 * h), Vector2(-0.07 * w, 0.98 * h), ct, 12, true)
    val half = left + topL + topC
    val outlineLocal = half + half.dropLast(1).reversed().map { Vector2(-it.x, it.y) }
    val divs = listOf(-1.0, 1.0).map { s ->
        botCubic(Vector2(s * 0.02 * w, -0.03 * h), Vector2(s * 0.10 * w, 0.35 * h), Vector2(s * 0.18 * w, 0.62 * h), Vector2(s * 0.18 * w, 0.82 * h), 18) +
                Vector2(s * 0.175 * w, 0.86 * h)
    }
    val divL = botCubic(Vector2(-0.02 * w, -0.03 * h), Vector2(-0.10 * w, 0.35 * h), Vector2(-0.18 * w, 0.62 * h), vl, 18)
    val centreLocal = divL + topC + topC.dropLast(1).reversed().map { Vector2(-it.x, it.y) } +
            Vector2(-vl.x, vl.y) + divL.dropLast(1).reversed().map { Vector2(-it.x, it.y) }
    // étamines : partent du fond de la fleur, dépassent du pétale central
    val stamens = mutableListOf<List<Vector2>>()
    for (k in -1..1) {
        val start = Vector2(k * 0.03 * w, 0.78 * h)
        val end = Vector2(k * 0.15 * w, (1.03 + 0.02 * (1 - abs(k))) * h)
        stamens.add(listOf(start, Vector2((start.x + end.x) / 2 + k * 0.03 * w, (start.y + end.y) / 2), end))
        stamens.add(listOf(Vector2(end.x - 0.03 * w, end.y - 0.004 * h), Vector2(end.x + 0.03 * w, end.y + 0.004 * h)))
    }
    return BotLily(
        botPlace(outlineLocal.dropLast(1), base, angle),
        divs.map { botPlace(it, base, angle) },
        botPlace(centreLocal, base, angle),
        stamens.map { botPlace(it, base, angle) }
    )
}

private class BotPassion(
    val outline: List<Vector2>, val lines: List<List<Vector2>>,
    val parts: List<Pair<List<Vector2>, BotanicalRole>>, val paint: List<List<Vector2>>
)

/**
 * Passiflore vue de face : dix tépales pointus (cinq pétales et cinq
 * sépales alternés), couronne de filaments violette, cœur vert. Les
 * filaments (qui débordent sur les pétales), les cinq étamines et les
 * trois styles sont peints à la grisaille.
 */
private fun botPassion(c: Vector2, r: Double, rot: Double): BotPassion {
    val outline = (0 until 240).map { i ->
        val th = rot + 2 * PI * i / 240
        val rr = r * (0.70 + 0.30 * abs(cos(5 * (th - rot))).pow(1.6))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val corona = botCircle(c, 0.50 * r, 72)
    val heart = botCircle(c, 0.20 * r, 40)
    val lines = mutableListOf(outline + outline.first(), corona + corona.first(), heart + heart.first())
    val wedges = mutableListOf<Pair<List<Vector2>, BotanicalRole>>()
    for (k in 0 until 10) {
        val a0 = rot + (2 * k + 1) * PI / 10
        lines.add((0..8).map { i ->
            val rr = 0.47 * r + (0.74 * r - 0.47 * r) * i / 8.0
            Vector2(c.x + rr * cos(a0), c.y + rr * sin(a0))
        })
        // la couronne est coupée en cinq : ses lignes relient le cœur au
        // reste du plomb (sinon le cœur « flotterait » dans la couronne)
        if (k % 2 == 1) lines.add(listOf(
            Vector2(c.x + 0.17 * r * cos(a0), c.y + 0.17 * r * sin(a0)),
            Vector2(c.x + 0.53 * r * cos(a0), c.y + 0.53 * r * sin(a0))
        ))
        // secteur du tépale k (entre deux lignes), pour reconnaître pétale / sépale
        val aa = rot + (2 * k - 1) * PI / 10
        val sector = listOf(c) + (0..10).map { i -> val a = aa + (a0 - aa) * i / 10; Vector2(c.x + 1.3 * r * cos(a), c.y + 1.3 * r * sin(a)) }
        wedges.add(sector to (if (k % 2 == 0) BotanicalRole.PASSION_PETAL else BotanicalRole.PASSION_SEPAL))
    }
    val paint = mutableListOf<List<Vector2>>()
    for (k in 0 until 40) {
        val a = rot + 2 * PI * k / 40 + 0.04
        val outer = if (k % 2 == 0) 0.68 else 0.60
        paint.add(listOf(Vector2(c.x + 0.24 * r * cos(a), c.y + 0.24 * r * sin(a)), Vector2(c.x + outer * r * cos(a), c.y + outer * r * sin(a))))
    }
    for (k in 0 until 5) {
        val a = rot + 2 * PI * k / 5 + 0.3
        val e = Vector2(c.x + 0.30 * r * cos(a), c.y + 0.30 * r * sin(a))
        paint.add(listOf(c, e))
        paint.add(listOf(Vector2(e.x - sin(a) * 0.06 * r, e.y + cos(a) * 0.06 * r), Vector2(e.x + sin(a) * 0.06 * r, e.y - cos(a) * 0.06 * r)))
    }
    for (k in 0 until 3) {
        val a = rot + 2 * PI * k / 3 + 0.9
        paint.add(listOf(c, Vector2(c.x + 0.17 * r * cos(a), c.y + 0.17 * r * sin(a))))
    }
    val parts = listOf(heart to BotanicalRole.PASSION_HEART, corona to BotanicalRole.PASSION_CORONA) + wedges
    return BotPassion(outline, lines, parts, paint)
}

private class BotPalm(val outline: List<Vector2>, val axis: List<Vector2>, val lines: List<List<Vector2>>, val inset: Double)

/**
 * Palme du martyre : longue fronde incurvée, bord en dents de scie (chaque
 * dent est la pointe d'une foliole, tournée vers le bout de la palme),
 * nervure centrale (moitié éclairée / moitié dans l'ombre), folioles
 * séparées par des lignes obliques qui montent vers le bout.
 */
private fun botPalm(base: Vector2, angle: Double, length: Double, width: Double, bend: Double, leaflets: Int): BotPalm {
    val n = 120
    val tip = Vector2(base.x + cos(angle) * length, base.y + sin(angle) * length)
    val nx = -sin(angle)
    val ny = cos(angle)
    val ctrl = Vector2((base.x + tip.x) / 2 + nx * bend * length, (base.y + tip.y) / 2 + ny * bend * length)
    val axis = mutableListOf<Vector2>()
    val normals = mutableListOf<Vector2>()
    for (i in 0..n) {
        val t = i.toDouble() / n
        val u = 1 - t
        axis.add(Vector2(u * u * base.x + 2 * t * u * ctrl.x + t * t * tip.x, u * u * base.y + 2 * t * u * ctrl.y + t * t * tip.y))
        val dx = 2 * u * (ctrl.x - base.x) + 2 * t * (tip.x - ctrl.x)
        val dy = 2 * u * (ctrl.y - base.y) + 2 * t * (tip.y - ctrl.y)
        val l = hypot(dx, dy)
        normals.add(Vector2(-dy / l, dx / l))
    }
    val start = 0.18
    fun envelope(t: Double) = if (t < start) 0.10 * width * (t / start) + 0.02 * width
    else 0.5 * width * sin(PI * ((t - start) / (1 - start)).pow(0.7)).pow(0.55)
    fun saw(t: Double, offset: Double): Double {
        if (t < start) return 1.0
        val u = (t - start) / (1 - start) * leaflets + offset
        return 0.40 + 0.60 * (u - floor(u)).pow(0.8)
    }
    fun edge(t: Double, offset: Double) = max(envelope(t) * saw(t, offset), 0.0)
    val left = (0..n).map { val t = it.toDouble() / n; Vector2(axis[it].x + normals[it].x * edge(t, 0.0), axis[it].y + normals[it].y * edge(t, 0.0)) }
    val right = (0..n).map { val t = it.toDouble() / n; Vector2(axis[it].x - normals[it].x * edge(t, 0.5), axis[it].y - normals[it].y * edge(t, 0.5)) }
    val outline = left + right.reversed().drop(1)
    // lignes de folioles : de la nervure vers le creux qui suit chaque pointe
    val lines = mutableListOf(outline + outline.first())
    val inset = 0.04 * length
    val axisFrom = (inset / length * n).toInt().coerceAtLeast(1)
    // la nervure part exactement du point où arrive le pédoncule (botBaseStalk)
    val joint = Vector2(base.x + cos(angle) * inset, base.y + sin(angle) * inset)
    val rib = listOf(joint) + axis.subList(axisFrom + 1, axis.size)
    lines.add(rib)
    for ((side, offset) in listOf(1.0 to 0.0, -1.0 to 0.5)) {
        for (k in 1 until leaflets) {
            val tn = start + (1 - start) * (k - offset) / leaflets
            if (tn <= start + 0.02 || tn >= 0.95) continue
            val iEdge = (tn * n).toInt().coerceIn(1, n - 1)
            val iAxis = ((tn - 0.05) * n).toInt().coerceIn(axisFrom + 1, n - 1)
            val e = axis[iEdge]
            val w = edge(tn + 0.004, offset) + 3.0
            val edgePoint = Vector2(e.x + side * normals[iEdge].x * w, e.y + side * normals[iEdge].y * w)
            lines.add(listOf(axis[iAxis], edgePoint))
        }
    }
    return BotPalm(outline, rib, lines, inset)
}

private class BotClosedBud(val outline: List<Vector2>, val dividers: List<List<Vector2>>, val front: List<Vector2>, val inset: Double)

/**
 * Rose de Noël en bouton : bouton fermé en forme d'œuf pointu, trois
 * sépales visibles (celui de devant plus clair). Les deux lignes de
 * sépales partent du point où arrive le pédoncule (inset au-dessus de la
 * base) : le pédoncule et les sépales se rejoignent en un seul nœud.
 */
private fun botClosedBud(base: Vector2, angle: Double, h: Double, widthRatio: Double = 0.72): BotClosedBud {
    val w = widthRatio * h
    val inset = 0.10 * h
    val tipL = Vector2(-0.22 * w, 0.93 * h)      // pointe du sépale gauche
    val notch = Vector2(-0.09 * w, 0.88 * h)     // creux entre deux sépales
    val tipC = Vector2(0.0, 1.0 * h)             // pointe du sépale de devant
    val left = botCubic(Vector2(0.0, 0.0), Vector2(-0.30 * w, 0.02 * h), Vector2(-0.60 * w, 0.36 * h), Vector2(-0.48 * w, 0.64 * h), 16) +
            botCubic(Vector2(-0.48 * w, 0.64 * h), Vector2(-0.40 * w, 0.82 * h), Vector2(-0.30 * w, 0.90 * h), tipL, 10, true) +
            botCubic(tipL, Vector2(-0.18 * w, 0.92 * h), Vector2(-0.12 * w, 0.89 * h), notch, 6, true) +
            botCubic(notch, Vector2(-0.06 * w, 0.92 * h), Vector2(-0.03 * w, 0.98 * h), tipC, 8, true)
    val outlineLocal = left + left.dropLast(1).reversed().map { Vector2(-it.x, it.y) }
    val joint = Vector2(0.0, inset)
    val divs = listOf(-1.0, 1.0).map { s ->
        botCubic(joint, Vector2(s * 0.24 * w, 0.30 * h), Vector2(s * 0.20 * w, 0.70 * h), Vector2(s * 0.09 * w, notch.y), 18) +
                Vector2(s * 0.085 * w, 0.92 * h)
    }
    val frontLocal = divs[0].dropLast(1) + Vector2(-0.06 * w, 0.92 * h) + tipC + Vector2(0.06 * w, 0.92 * h) + divs[1].dropLast(1).reversed()
    return BotClosedBud(
        botPlace(outlineLocal.dropLast(1), base, angle),
        divs.map { botPlace(it, base, angle) },
        botPlace(frontLocal, base, angle),
        inset
    )
}

private class BotStump(val outline: List<Vector2>, val cut: List<Vector2>)

/**
 * Chicot : branche latérale taillée qui part de `base` (sur l'axe de la
 * branche principale) dans la direction `angle` ; légèrement effilée, face
 * de coupe en ellipse au bout.
 */
private fun botStump(base: Vector2, angle: Double, length: Double, width: Double): BotStump {
    val ry = 0.28 * width
    val endY = length
    val bodyLocal = listOf(
        Vector2(-0.62 * width, 0.0), Vector2(-0.50 * width, length * 0.6), Vector2(-0.50 * width, endY)
    ) + (1 until 24).map { i ->
        val a = PI - PI * i / 24
        Vector2(0.50 * width * cos(a), endY + ry * sin(a))
    } + listOf(Vector2(0.50 * width, endY), Vector2(0.50 * width, length * 0.6), Vector2(0.62 * width, 0.0))
    val cutLocal = (0 until 36).map { i -> val a = 2 * PI * i / 36; Vector2(0.50 * width * cos(a), endY + ry * sin(a)) }
    return BotStump(botPlace(bodyLocal, base, angle), botPlace(cutLocal, base, angle))
}

/** Bande fermée de largeur w0 (au début) à w1 (à la fin) autour d'une ligne. */
private fun botStrip(line: List<Vector2>, w0: Double, w1: Double): List<Vector2> {
    val n = line.size
    val left = ArrayList<Vector2>()
    val right = ArrayList<Vector2>()
    for (i in 0 until n) {
        val a = line[max(0, i - 1)]
        val b = line[min(n - 1, i + 1)]
        val l = hypot(b.x - a.x, b.y - a.y)
        val nx = -(b.y - a.y) / l
        val ny = (b.x - a.x) / l
        val w = (w0 + (w1 - w0) * i / (n - 1)) / 2
        left.add(Vector2(line[i].x + nx * w, line[i].y + ny * w))
        right.add(Vector2(line[i].x - nx * w, line[i].y - ny * w))
    }
    return left + right.reversed()
}

private class BotSnowdrop(
    val outline: List<Vector2>, val lines: List<List<Vector2>>,
    val parts: List<Pair<List<Vector2>, BotanicalRole>>, val paint: List<List<Vector2>>
)

/**
 * Perce-neige (Galanthus nivalis), fleur pendante vue de côté : petit
 * ovaire vert en haut (là où arrive la hampe), puis
 * - ouverte : deux pétales extérieurs écartés (le droit dans l'ombre) et,
 *   entre eux, la coupe des pétales intérieurs, blanc verdâtre, avec sa
 *   marque verte peinte à la grisaille ;
 * - fermée : une goutte (deux moitiés, la droite dans l'ombre).
 * `top` est le point d'attache, `angle` la direction dans laquelle pend la
 * fleur (vers le bas), `h` sa longueur.
 */
private fun botSnowdrop(top: Vector2, angle: Double, h: Double, open: Boolean): BotSnowdrop {
    fun v(x: Double, y: Double) = Vector2(x * h, y * h)
    fun m(l: List<Vector2>) = l.map { Vector2(-it.x, it.y) }
    /** Concatène des tronçons en retirant les points répétés aux raccords. */
    fun chain(vararg ls: List<Vector2>): List<Vector2> {
        val out = mutableListOf<Vector2>()
        for (l in ls) for (q in l) if (out.isEmpty() || out.last().distanceTo(q) > 1e-9) out.add(q)
        return out
    }
    // ovaire : ellipse ; elle rejoint la corolle en J (±0.065, 0.177)
    fun ell(th: Double) = v(0.08 * cos(th), 0.11 + 0.115 * sin(th))
    val thR = 0.62
    val thL = PI - thR
    val ovaryFull = (0 until 40).map { ell(2 * PI * it / 40) }
    val topArc = (0..24).map { ell(thR - (thR + 2 * PI - thL) * it / 24) }          // J_R → (dessus) → J_L
    val bottomArc = (0..12).map { ell(thL - (thL - PI / 2) * it / 12) } +
            (1..12).map { ell(PI / 2 - (PI / 2 - thR) * it / 12) }                       // J_L → (0, 0.225) → J_R
    val bottomToRight = bottomArc.drop(12)                                           // (0, 0.225) → J_R
    val jl = ell(thL)
    val bottom = v(0.0, 0.225)
    val outlineL: List<Vector2>
    val localLines = mutableListOf<List<Vector2>>()
    val localParts = mutableListOf(ovaryFull to BotanicalRole.SNOWDROP_OVARY)
    val localPaint = mutableListOf<List<Vector2>>()
    if (open) {
        val tl = v(-0.39, 0.68); val rl = v(-0.26, 0.75); val cl = v(-0.105, 0.36); val gl = v(-0.085, 0.66)
        val a = botCubic(jl, v(-0.20, 0.19), v(-0.40, 0.36), tl, 16)
        val b = botCubic(tl, v(-0.385, 0.76), v(-0.32, 0.80), rl, 8)
        val c = botCubic(rl, v(-0.20, 0.66), v(-0.15, 0.48), cl, 12)
        val d = botCubic(cl, v(-0.115, 0.46), v(-0.115, 0.58), gl, 10)
        val e = botCubic(gl, v(-0.06, 0.70), v(-0.03, 0.71), v(0.0, 0.71), 6)
        val leftPath = chain(a, b, c, d, e)
        outlineL = chain(leftPath, m(leftPath).reversed())
        // séparation coupe / pétale : de la coupe jusqu'au bas de l'ovaire
        val div = botCubic(cl, v(-0.09, 0.30), v(-0.03, 0.25), bottom, 10)
        // prolongée un peu hors du contour et un peu dans l'ovaire (bouts pendants, élagués)
        val divLine = listOf(v(-0.117, 0.366)) + div + v(0.0, 0.20)
        localLines.add(divLine)
        localLines.add(m(divLine))
        val cupHalf = chain(d, e)
        val cup = chain(div.reversed(), cupHalf, m(cupHalf).reversed(), m(div))
        val tepalR = chain(m(chain(a, b, c)), m(div), bottomToRight)
        localParts.add(cup to BotanicalRole.SNOWDROP_CUP)
        localParts.add(tepalR to BotanicalRole.SNOWDROP_SHADOW)
        // marque verte de la coupe (un petit arc renversé)
        localPaint.add(botCubic(v(-0.055, 0.60), v(-0.03, 0.54), v(0.03, 0.54), v(0.055, 0.60), 10))
    } else {
        val ml = v(-0.13, 0.74); val tip = v(0.0, 0.88)
        val a = botCubic(jl, v(-0.20, 0.20), v(-0.23, 0.52), ml, 16)
        val b = botCubic(ml, v(-0.08, 0.81), v(-0.03, 0.86), tip, 10)
        val leftPath = chain(a, b)
        outlineL = chain(leftPath, m(leftPath).reversed())
        val div = botCubic(bottom, v(0.03, 0.40), v(0.035, 0.65), tip, 16)
        localLines.add(listOf(v(0.0, 0.20)) + div + v(0.0, 0.90))
        val halfR = chain(div, m(leftPath).reversed(), bottomToRight.reversed())
        localParts.add(halfR to BotanicalRole.SNOWDROP_SHADOW)
    }
    // contour : corolle (de J_L à J_R) puis dessus de l'ovaire
    val outline = botPlace(chain(outlineL, topArc).dropLast(1), top, angle)
    val lines = localLines.map { botPlace(it, top, angle) } +
            listOf(outline + outline.first(), botPlace(bottomArc, top, angle))
    return BotSnowdrop(outline, lines,
        localParts.map { (poly, role) -> botPlace(poly, top, angle) to role },
        localPaint.map { botPlace(it, top, angle) })
}

private class BotRoundPaint(val outline: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

private class BotChrys(val outline: List<Vector2>, val inner: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

/**
 * Chrysanthème double vu de face (comme les chrysanthèmes « décoratifs » de
 * la Toussaint) : couronne extérieure de quatorze pétales en cuillère,
 * couronne intérieure de dix pétales plus courts, décalés, qui couvrent
 * presque le cœur ; petit cœur ocre ; gouttière de chaque pétale peinte à
 * la grisaille. (Une seule couronne le ferait lire comme une marguerite.)
 */
private fun botChrysanthemum(c: Vector2, r: Double, rot: Double): BotChrys {
    fun petals(n: Int, rot0: Double, rIn: Double, rOut: Double, samples: Int) = (0 until samples).map { i ->
        val span = 2 * PI / n
        val th = rot0 + 2 * PI * i / samples
        val phi = ((th - rot0) % span + span) % span
        val s = (((phi + span / 2) % span) - span / 2) / (span / 2)
        val rr = r * (rIn + (rOut - rIn) * (1 - s * s).coerceAtLeast(0.0).pow(0.6))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val nOut = 14
    val nIn = 10
    val rotIn = rot + PI / nIn
    val outline = petals(nOut, rot, 0.66, 1.0, 336)
    val inner = petals(nIn, rotIn, 0.44, 0.64, 240)
    val heart = botCircle(c, 0.19 * r, 40)
    val lines = mutableListOf(outline + outline.first(), inner + inner.first(), heart + heart.first())
    fun ray(th: Double, vararg fs: Double) = fs.map { f -> Vector2(c.x + f * r * cos(th), c.y + f * r * sin(th)) }
    // séparations : jusqu'au-delà des creux (échantillonnés, ils sont un peu plus loin)
    for (k in 0 until nOut) lines.add(ray(rot + (k + 0.5) * 2 * PI / nOut, 0.50, 0.70, 0.84))
    for (k in 0 until nIn) lines.add(ray(rotIn + (k + 0.5) * 2 * PI / nIn, 0.16, 0.34, 0.54))
    val paint = mutableListOf<List<Vector2>>()
    for (k in 0 until nOut) paint.add(ray(rot + k * 2 * PI / nOut, 0.72, 0.84, 0.93))
    for (k in 0 until nIn) paint.add(ray(rotIn + k * 2 * PI / nIn, 0.28, 0.40, 0.55))
    return BotChrys(outline, inner, heart, lines, paint)
}

private class BotMarigold(val outline: List<Vector2>, val mid: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

/**
 * Cempasúchil (rose d'Inde, Tagetes erecta) vu de face : pompon rond au bord
 * festonné, en trois couronnes concentriques (de plus en plus foncées vers
 * le cœur), découpées par six rayons ; pétales frisés suggérés à la
 * grisaille.
 */
private fun botMarigold(c: Vector2, r: Double, rot: Double): BotMarigold {
    fun ring(n: Int, scallops: Int, base: Double, amp: Double, shift: Double) = (0 until n).map { i ->
        val th = rot + 2 * PI * i / n
        val rr = r * (base + amp * abs(cos(scallops / 2.0 * (th - rot) + shift)).pow(0.6))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val outline = ring(300, 20, 0.90, 0.10, 0.0)
    val mid = ring(200, 14, 0.58, 0.07, 0.4)
    val heart = ring(120, 10, 0.27, 0.05, 0.9)
    val lines = mutableListOf(outline + outline.first(), mid + mid.first(), heart + heart.first())
    for (k in 0 until 6) {
        val th = rot + k * PI / 3 + 0.1
        lines.add((0..12).map { i ->
            val t = i / 12.0
            val rr = (0.24 + (1.06 - 0.24) * t) * r
            val a = th + 0.18 * t
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        })
    }
    val paint = mutableListOf<List<Vector2>>()
    for (k in 0 until 20) {
        val a = rot + 2 * PI * k / 20
        paint.add(listOf(0.72, 0.80, 0.86).mapIndexed { j, f -> val aa = a + 0.04 * (j - 1); Vector2(c.x + f * r * cos(aa), c.y + f * r * sin(aa)) })
    }
    for (k in 0 until 14) {
        val a = rot + 2 * PI * k / 14 + 0.4 / 7
        paint.add(listOf(0.38, 0.45, 0.52).mapIndexed { j, f -> val aa = a + 0.05 * (j - 1); Vector2(c.x + f * r * cos(aa), c.y + f * r * sin(aa)) })
    }
    return BotMarigold(outline, mid, heart, lines, paint)
}

private class BotCapsule(val outline: List<Vector2>, val crown: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

/**
 * Capsule de pavot dressée : corps en urne (gris-vert) qui part de `base`
 * dans la direction `angle`, col étroit, couronne plate (le disque
 * stigmatique, violacé) dont les rayons sont peints à la grisaille.
 */
private fun botCapsule(base: Vector2, angle: Double, h: Double): BotCapsule {
    fun v(x: Double, y: Double) = Vector2(x * h, y * h)
    // couronne : ellipse de centre (0, 0.88), demi-axes 0.24 × 0.09 ; le col
    // la rejoint en (±0.15, ≈0.81)
    val cy = 0.88; val rx = 0.24; val ry = 0.09
    fun ce(t: Double) = v(rx * cos(t), cy + ry * sin(t))
    val tNeck = asin(-sqrt(1 - (0.15 / rx).pow(2)))            // côté droit, en bas de l'ellipse
    val neckR = ce(tNeck)
    val neckL = ce(PI - tNeck)
    val left = botCubic(Vector2(0.0, 0.0), v(-0.32, 0.06), v(-0.34, 0.60), neckL, 20)
    // dessus de la couronne : de neckL (en bas à gauche) par le haut jusqu'à neckR
    val tL = PI - tNeck
    val crownTop = (1 until 30).map { i -> ce(tL - (tL - tNeck) * i / 30.0) }
    val right = botCubic(neckR, v(0.34, 0.60), v(0.32, 0.06), Vector2(0.0, 0.0), 20)
    val local = left + crownTop + right.dropLast(1)
    val crownLocal = (0 until 40).map { ce(2 * PI * it / 40) }
    // séparation corps / couronne : le bas de l'ellipse, de neckL à neckR
    val tR = 2 * PI + tNeck
    val lowerArc = (0..14).map { i -> ce(tL + (tR - tL) * i / 14.0) }
    val outline = botPlace(local, base, angle)
    val lines = listOf(outline + outline.first(), botPlace(lowerArc, base, angle))
    val paint = mutableListOf<List<Vector2>>()
    for (k in 0 until 7) {
        val a = PI + PI * (k + 0.5) / 7          // moitié visible (devant) de la couronne
        paint.add(botPlace(listOf(v(0.0, cy), v(0.92 * rx * cos(a), cy + 0.92 * ry * sin(a))), base, angle))
    }
    for (x in listOf(-0.12, 0.0, 0.12)) {
        paint.add(botPlace(botCubic(v(x * 0.6, 0.12), v(x * 1.3, 0.35), v(x * 1.3, 0.58), v(x * 0.9, 0.74), 10), base, angle))
    }
    return BotCapsule(outline, botPlace(crownLocal, base, angle), lines, paint)
}

private class BotEglantine(val outline: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

/**
 * Églantine (rose sauvage, Rosa canina) vue de face : cinq larges pétales
 * arrondis, légèrement échancrés au sommet ; cœur doré ; couronne
 * d'étamines et nervure de chaque pétale peintes à la grisaille.
 */
private fun botEglantine(c: Vector2, r: Double, rot: Double): BotEglantine {
    val span = 2 * PI / 5
    val outline = (0 until 250).map { i ->
        val th = rot + 2 * PI * i / 250
        // s = 0 au milieu d'un pétale, ±1 au creux entre deux pétales
        val phi = ((th - rot) % span + span) % span
        val s = (((phi + span / 2) % span) - span / 2) / (span / 2)
        val rr = r * (0.70 + 0.30 * (1 - s * s).coerceAtLeast(0.0).pow(0.45) - 0.10 * exp(-(s / 0.14).pow(2)))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val heart = botCircle(c, 0.27 * r, 48)
    val lines = mutableListOf(outline + outline.first(), heart + heart.first())
    for (k in 0 until 5) {
        val th = rot + (2 * k + 1) * PI / 5
        lines.add((0..12).map { i ->
            val t = i / 12.0
            val rr = 0.25 * r + (0.76 * r - 0.25 * r) * t
            val a = th + 0.05 * sin(PI * t)
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        })
    }
    val paint = mutableListOf<List<Vector2>>()
    // couronne d'étamines : filets rayonnants, chacun terminé par une anthère
    for (k in 0 until 26) {
        val a = rot + 2 * PI * k / 26 + 0.07
        val len = if (k % 2 == 0) 0.44 else 0.38
        val e = Vector2(c.x + len * r * cos(a), c.y + len * r * sin(a))
        paint.add(listOf(Vector2(c.x + 0.16 * r * cos(a), c.y + 0.16 * r * sin(a)), e))
        val px = -sin(a) * 0.025 * r
        val py = cos(a) * 0.025 * r
        paint.add(listOf(Vector2(e.x - px, e.y - py), Vector2(e.x + px, e.y + py)))
    }
    // nervure de chaque pétale (s'arrête avant l'échancrure)
    for (k in 0 until 5) {
        val a = rot + 2 * PI * k / 5
        paint.add((0..8).map { i ->
            val rr = (0.50 + 0.22 * i / 8.0) * r
            val aa = a + 0.03 * sin(PI * i / 8.0)
            Vector2(c.x + rr * cos(aa), c.y + rr * sin(aa))
        })
    }
    return BotEglantine(outline, heart, lines, paint)
}

private class BotHellebore(val outline: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>, val paint: List<List<Vector2>>)

/**
 * Rose de Noël (Helleborus niger) vue de face : cinq larges pétales
 * arrondis, cœur vert-jaune ; couronne d'étamines dorées et fine
 * nervure de chaque pétale peintes à la grisaille.
 */
private fun botHellebore(c: Vector2, r: Double, rot: Double): BotHellebore {
    val outline = (0 until 200).map { i ->
        val th = rot + 2 * PI * i / 200
        val lobe = abs(cos(2.5 * (th - rot)))
        // pétales larges, à pointe à peine marquée
        val rr = r * (0.78 + 0.18 * lobe.pow(0.3) + 0.05 * lobe.pow(12))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val heart = botCircle(c, 0.28 * r, 48)
    val lines = mutableListOf(outline + outline.first(), heart + heart.first())
    for (k in 0 until 5) {
        val th = rot + (2 * k + 1) * PI / 5
        lines.add((0..12).map { i ->
            val t = i / 12.0
            val rr = 0.25 * r + (0.84 * r - 0.25 * r) * t
            val a = th + 0.06 * sin(PI * t)
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        })
    }
    val paint = mutableListOf<List<Vector2>>()
    // couronne d'étamines : filets rayonnants, chacun terminé par une anthère
    for (k in 0 until 22) {
        val a = rot + 2 * PI * k / 22 + 0.1
        val len = if (k % 2 == 0) 0.50 else 0.44
        val e = Vector2(c.x + len * r * cos(a), c.y + len * r * sin(a))
        paint.add(listOf(Vector2(c.x + 0.30 * r * cos(a), c.y + 0.30 * r * sin(a)), e))
        val px = -sin(a) * 0.025 * r
        val py = cos(a) * 0.025 * r
        paint.add(listOf(Vector2(e.x - px, e.y - py), Vector2(e.x + px, e.y + py)))
    }
    for (k in 0 until 5) {
        val a = rot + 2 * PI * k / 5
        paint.add(listOf(Vector2(c.x + 0.48 * r * cos(a), c.y + 0.48 * r * sin(a)), Vector2(c.x + 0.80 * r * cos(a), c.y + 0.80 * r * sin(a))))
    }
    return BotHellebore(outline, heart, lines, paint)
}

/**
 * Feuille de houx : silhouette ovale et pointue dont le bord se creuse
 * entre des épines (spines par côté, décalées d'un côté à l'autre) ;
 * nervure centrale.
 */
private fun botHolly(base: Vector2, angle: Double, length: Double, width: Double, bend: Double, spines: Int, n: Int = 60): Pair<List<Vector2>, List<Vector2>> {
    val tip = Vector2(base.x + cos(angle) * length, base.y + sin(angle) * length)
    val nx = -sin(angle)
    val ny = cos(angle)
    val ctrl = Vector2((base.x + tip.x) / 2 + nx * bend * length, (base.y + tip.y) / 2 + ny * bend * length)
    val axis = mutableListOf<Vector2>()
    val normals = mutableListOf<Vector2>()
    for (i in 0..n) {
        val t = i.toDouble() / n
        val u = 1 - t
        axis.add(Vector2(u * u * base.x + 2 * t * u * ctrl.x + t * t * tip.x, u * u * base.y + 2 * t * u * ctrl.y + t * t * tip.y))
        val dx = 2 * u * (ctrl.x - base.x) + 2 * t * (tip.x - ctrl.x)
        val dy = 2 * u * (ctrl.y - base.y) + 2 * t * (tip.y - ctrl.y)
        val l = hypot(dx, dy)
        normals.add(Vector2(-dy / l, dx / l))
    }
    fun profile(t: Double) = width * 0.5 * sin(PI * t.pow(0.9)).pow(0.7) + 0.08 * width * (1 - t).pow(3)
    fun edge(t: Double, offset: Double): Double {
        if (t < 0.12) return profile(t)
        val u = (t - 0.12) / 0.88 * spines + offset
        return profile(t) * (1 - 0.24 * abs(sin(PI * u)).pow(0.8))
    }
    val left = (0..n).map { val t = it.toDouble() / n; Vector2(axis[it].x + normals[it].x * edge(t, 0.0), axis[it].y + normals[it].y * edge(t, 0.0)) }
    val right = (0..n).map { val t = it.toDouble() / n; Vector2(axis[it].x - normals[it].x * edge(t, 0.5), axis[it].y - normals[it].y * edge(t, 0.5)) }
    return (left + right.reversed().drop(1)) to axis
}

/** Petite grappe serrée de baies rondes (houx), qui part de `top` dans la direction `angle`. */
private fun botBerries(top: Vector2, angle: Double, rb: Double, count: Int, rnd: Random): BotGrapes {
    val rows = when (count) { 3 -> listOf(1, 2); 4 -> listOf(1, 2, 1); else -> listOf(1, 2, 2) }
    val centers = mutableListOf<Pair<Vector2, Double>>()
    rows.forEachIndexed { k, cnt ->
        for (i in 0 until cnt) {
            val x = (i - (cnt - 1) / 2.0) * 1.62 * rb + if (k == 0 && cnt == 1) 0.0 else rnd.nextDouble(-0.08, 0.08) * rb
            val y = rb + k * 1.40 * rb
            centers.add(Vector2(x, y) to rb * (if (k == 0 && cnt == 1) 1.0 else rnd.nextDouble(0.94, 1.06)))
        }
    }
    val order = centers.indices.sortedBy { abs(centers[it].first.x) / rb + 0.4 * rnd.nextDouble() }
    val circles = order.map { i ->
        val (c, r) = centers[i]
        botPlace((0 until 40).map { Vector2(c.x + r * cos(2 * PI * it / 40), c.y + r * sin(2 * PI * it / 40)) }, top, angle)
    }
    val lines = mutableListOf<List<Vector2>>()
    circles.forEachIndexed { i, circle ->
        val front = circles.subList(0, i)
        lines += if (front.isEmpty()) listOf(circle + circle.first()) else botClipOutside(circle + circle.first(), front)
    }
    val all = circles.flatMapIndexed { i, circle -> circle.filter { p -> circles.withIndex().none { (j, o) -> j != i && polygonContains(o, p) } } }
    val cx = all.sumOf { it.x } / all.size
    val cy = all.sumOf { it.y } / all.size
    return BotGrapes(all.sortedBy { atan2(it.y - cy, it.x - cx) }, circles, lines)
}

private class BotRose(val outline: List<Vector2>, val ring: List<Vector2>, val heart: List<Vector2>, val lines: List<List<Vector2>>)

/**
 * Rose vue de face : cinq grands pétales extérieurs arrondis, une coupe
 * intérieure de trois pétales enroulés en spirale, un petit cœur.
 */
private fun botRose(c: Vector2, r: Double, rot: Double): BotRose {
    val outline = (0 until 200).map { i ->
        val th = rot + 2 * PI * i / 200
        val rr = r * (0.84 + 0.16 * abs(cos(2.5 * (th - rot))).pow(0.4))
        Vector2(c.x + rr * cos(th), c.y + rr * sin(th))
    }
    val ring = botCircle(c, 0.56 * r, 72)
    val heart = botCircle(c, 0.18 * r, 32)
    val lines = mutableListOf<List<Vector2>>()
    lines.add(outline + outline.first())
    lines.add(ring + ring.first())
    lines.add(heart + heart.first())
    for (k in 0 until 5) {
        val th = rot + (2 * k + 1) * PI / 5
        lines.add((0..12).map { i ->
            val t = i / 12.0
            val rr = 0.53 * r + (0.90 * r - 0.53 * r) * t
            val a = th + 0.10 * sin(PI * t)
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        })
    }
    for (k in 0 until 3) {
        val a0 = rot + 0.5 + k * 2 * PI / 3
        lines.add((0..18).map { i ->
            val t = i / 18.0
            val rr = 0.15 * r + (0.60 * r - 0.15 * r) * t
            val a = a0 + 1.7 * t
            Vector2(c.x + rr * cos(a), c.y + rr * sin(a))
        })
    }
    return BotRose(outline, ring, heart, lines)
}

/** Pétale arrondi : base étroite, plus large vers les deux tiers, bout arrondi ; axe légèrement courbé. */
private fun botPetal(root: Vector2, angle: Double, length: Double, width: Double, bend: Double, n: Int = 24): Pair<List<Vector2>, List<Vector2>> {
    val tip = Vector2(root.x + cos(angle) * length, root.y + sin(angle) * length)
    val nx = -sin(angle)
    val ny = cos(angle)
    val ctrl = Vector2((root.x + tip.x) / 2 + nx * bend * length, (root.y + tip.y) / 2 + ny * bend * length)
    val axis = mutableListOf<Vector2>()
    val normals = mutableListOf<Vector2>()
    for (i in 0..n) {
        val t = i.toDouble() / n
        val u = 1 - t
        axis.add(Vector2(u * u * root.x + 2 * t * u * ctrl.x + t * t * tip.x, u * u * root.y + 2 * t * u * ctrl.y + t * t * tip.y))
        val dx = 2 * u * (ctrl.x - root.x) + 2 * t * (tip.x - ctrl.x)
        val dy = 2 * u * (ctrl.y - root.y) + 2 * t * (tip.y - ctrl.y)
        val l = hypot(dx, dy)
        normals.add(Vector2(-dy / l, dx / l))
    }
    fun w(t: Double) = width / 2 * (2 * sqrt(max(0.0, t * (1 - t))) * (0.30 + 0.70 * t)) / 0.70 + 0.06 * width * (1 - t)
    val left = (0..n).map { Vector2(axis[it].x + normals[it].x * w(it.toDouble() / n), axis[it].y + normals[it].y * w(it.toDouble() / n)) }
    val right = (0..n).map { Vector2(axis[it].x - normals[it].x * w(it.toDouble() / n), axis[it].y - normals[it].y * w(it.toDouble() / n)) }
    return (left + right.reversed().drop(1)) to axis
}

private class BotIris(
    val outline: List<Vector2>, val lines: List<List<Vector2>>,
    val parts: List<Pair<List<Vector2>, BotanicalRole>>, val veins: List<List<Vector2>>, val inset: Double
)

/**
 * Iris vu de face : trois pétales dressés (le central devant, deux en
 * retrait de part et d'autre) et deux grands pétales retombants au premier
 * plan. Le contour d'un pétale est coupé là où un pétale de devant le
 * recouvre. Fines veines des pétales retombants peintes à la grisaille.
 */
private fun botIris(base: Vector2, angle: Double, h: Double): BotIris {
    val j = Vector2(0.0, 0.40 * h)
    // repère local : angles mesurés depuis la verticale du repère (vers +y)
    fun dir(deg: Double) = PI / 2 + deg * PI / 180
    // (signe de la courbure : les pétales retombants s'incurvent vers le bas)
    val (fallL, fallLAxis) = botPetal(j, dir(112.0), 0.55 * h, 0.30 * h, 0.18)
    val (fallR, fallRAxis) = botPetal(j, dir(-112.0), 0.55 * h, 0.30 * h, -0.18)
    val (standard, _) = botPetal(Vector2(0.0, 0.36 * h), dir(0.0), 0.62 * h, 0.30 * h, 0.0)
    val (sideL, _) = botPetal(j, dir(34.0), 0.50 * h, 0.24 * h, 0.10)
    val (sideR, _) = botPetal(j, dir(-34.0), 0.50 * h, 0.24 * h, -0.10)
    // de l'avant vers l'arrière
    val petalsLocal = listOf(fallL, fallR, standard, sideL, sideR)
    val petals = petalsLocal.map { botPlace(it, base, angle) }
    val lines = mutableListOf<List<Vector2>>()
    petals.forEachIndexed { i, p ->
        val front = petals.subList(0, i)
        lines += if (front.isEmpty()) listOf(p + p.first()) else botClipOutside(p + p.first(), front)
    }
    val centre = botPlace(listOf(j), base, angle)[0]
    val all = petals.flatMapIndexed { i, p -> p.filter { q -> petals.withIndex().none { (k, o) -> k != i && polygonContains(o, q) } } }
    val outline = all.sortedBy { atan2(it.y - centre.y, it.x - centre.x) }
    val veins = mutableListOf<List<Vector2>>()
    for (axisLocal in listOf(fallLAxis, fallRAxis)) {
        val a = axisLocal
        for (off in listOf(-0.05, 0.0, 0.05)) {
            val pts = (6..16).map { k ->
                val i = k.coerceAtMost(a.size - 2)
                val p = a[i]
                val q = a[i + 1]
                val dx = q.x - p.x
                val dy = q.y - p.y
                val l = hypot(dx, dy)
                Vector2(p.x - dy / l * off * h, p.y + dx / l * off * h)
            }
            veins.add(botPlace(pts, base, angle))
        }
    }
    val parts = listOf(
        petals[0] to BotanicalRole.IRIS_FALL, petals[1] to BotanicalRole.IRIS_FALL,
        petals[2] to BotanicalRole.IRIS_STANDARD, petals[3] to BotanicalRole.IRIS_STANDARD, petals[4] to BotanicalRole.IRIS_STANDARD
    )
    return BotIris(outline, lines, parts, veins, 0.40 * h)
}

private class BotWheat(
    val outline: List<Vector2>, val axis: List<Vector2>, val lines: List<List<Vector2>>,
    val inset: Double, val awns: List<List<Vector2>>
)

/**
 * Épi de blé dressé depuis `base` : silhouette effilée dont le bord se
 * bombe à chaque grain, axe central (moitié éclairée / moitié dans
 * l'ombre), grains séparés par des chevrons qui montent de l'axe vers le
 * bord, décalés d'un côté à l'autre. Les BARBES, trop fines pour du
 * verre, sont peintes à la grisaille (awns : voir paintLines).
 */
private fun botWheat(base: Vector2, angle: Double, length: Double, width: Double, grainsPerSide: Int): BotWheat {
    val n = 80
    val step = 0.80 / grainsPerSide
    fun envelope(t: Double) = 0.5 * width * sin(PI * t.pow(0.85)).pow(0.35)
    // bord bombé entre deux chevrons (grains), pincé au droit de chaque chevron
    fun hw(t: Double, side: Double): Double {
        val start = 0.22 + (if (side > 0) step / 2 else 0.0)
        val u = (t - start) / step
        val bulge = if (t > start - step && t < 0.95) 0.24 * abs(sin(PI * u)) else 0.0
        return envelope(t) * (0.84 + bulge)
    }
    val left = (0..n).map { Vector2(-hw(it.toDouble() / n, -1.0), length * it / n) }
    val right = (0..n).map { Vector2(hw(it.toDouble() / n, 1.0), length * it / n) }
    val outlineLocal = left + right.reversed().drop(1).dropLast(1)
    val inset = 0.06 * length
    val axis = listOf(Vector2(0.0, inset), Vector2(0.0, length))
    val chevrons = mutableListOf<List<Vector2>>()
    val awns = mutableListOf<List<Vector2>>()
    for (side in listOf(-1.0, 1.0)) {
        for (k in 0 until grainsPerSide) {
            val t1 = 0.22 + k * step + (if (side > 0) step / 2 else 0.0)
            val t0 = t1 - 0.10
            if (t1 > 0.93) continue
            val edgePoint = Vector2(side * hw(t1, side), length * t1)
            chevrons.add(listOf(Vector2(0.0, length * t0), Vector2(side * (hw(t1, side) + 3.0), length * t1 + 1.0)))
            // barbe : part du haut du grain, s'écarte un peu de l'axe
            val a = 0.30 + 0.10 * t1
            val l = length * (0.30 + 0.15 * t1)
            awns.add(listOf(edgePoint, Vector2(edgePoint.x + side * sin(a) * l, edgePoint.y + cos(a) * l)))
        }
    }
    for (a in listOf(-0.12, 0.0, 0.12)) {
        awns.add(listOf(Vector2(0.0, length), Vector2(sin(a) * 0.32 * length, length + cos(a) * 0.32 * length)))
    }
    val outline = botPlace(outlineLocal, base, angle)
    val axisW = botPlace(axis, base, angle)
    return BotWheat(outline, axisW, listOf(outline + outline.first(), axisW) + chevrons.map { botPlace(it, base, angle) },
        inset, awns.map { botPlace(it, base, angle) })
}

/**
 * Vrille de vigne, peinte à la grisaille : part du bord du cep, s'en
 * écarte, puis s'enroule en spirale (side : sens d'enroulement).
 */
private fun botTendril(start: Vector2, direction: Double, length: Double, side: Double): List<Vector2> {
    val ux = cos(direction)
    val uy = sin(direction)
    val end = Vector2(start.x + ux * length, start.y + uy * length)
    val stemPart = (0..12).map { i ->
        val t = i / 12.0
        val bend = 0.12 * length * sin(PI * t) * side
        Vector2(start.x + ux * length * t - uy * bend, start.y + uy * length * t + ux * bend)
    }
    val r0 = 0.30 * length
    // centre de la spirale, sur le côté de la fin du brin
    val c = Vector2(end.x - uy * r0 * side, end.y + ux * r0 * side)
    val phi0 = atan2(end.y - c.y, end.x - c.x)
    val spiral = (1..40).map { i ->
        val phi = 2.6 * PI * i / 40
        val r = r0 * kotlin.math.exp(-0.22 * phi)
        Vector2(c.x + r * cos(phi0 + side * phi), c.y + r * sin(phi0 + side * phi))
    }
    return stemPart + spiral
}

private fun botCircle(c: Vector2, r: Double, n: Int) = (0 until n).map { Vector2(c.x + r * cos(2 * PI * it / n), c.y + r * sin(2 * PI * it / n)) }

// --------------------------------------------------
// PONTS ET PÉDONCULES
// --------------------------------------------------

/** Contour fermé paramétré par l'abscisse curviligne. */
private class BotRing(val points: List<Vector2>) {
    val cumulative = DoubleArray(points.size + 1).also { c ->
        for (i in points.indices) c[i + 1] = c[i] + points[i].distanceTo(points[(i + 1) % points.size])
    }
    val length get() = cumulative[points.size]

    fun pointAt(s: Double): Vector2 {
        val L = length
        val t = ((s % L) + L) % L
        var i = 0
        while (i < points.size - 1 && cumulative[i + 1] < t) i++
        val a = points[i]
        val b = points[(i + 1) % points.size]
        val seg = cumulative[i + 1] - cumulative[i]
        val u = if (seg > 0) (t - cumulative[i]) / seg else 0.0
        return Vector2(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
    }

    fun project(p: Vector2): Double {
        var best = Double.MAX_VALUE
        var bestS = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val l2 = dx * dx + dy * dy
            val u = if (l2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val q = Vector2(a.x + dx * u, a.y + dy * u)
            val d = q.distanceTo(p)
            if (d < best) { best = d; bestS = cumulative[i] + u * sqrt(l2) }
        }
        return bestS
    }

    /** Normale sortante en s. */
    fun outwardNormal(s: Double): Vector2 {
        val p1 = pointAt(s - 2)
        val p2 = pointAt(s + 2)
        val tx = p2.x - p1.x
        val ty = p2.y - p1.y
        val l = hypot(tx, ty).takeIf { it > 0 } ?: 1.0
        var n = Vector2(-ty / l, tx / l)
        val p = pointAt(s)
        if (polygonContains(points, Vector2(p.x + n.x * 3, p.y + n.y * 3))) n = Vector2(-n.x, -n.y)
        return n
    }
}

/** Points les plus proches de deux contours fermés. */
private fun botNearestPair(a: List<Vector2>, b: List<Vector2>): Pair<Vector2, Vector2> {
    var best = Double.MAX_VALUE
    var pa = a[0]
    var pb = b[0]
    for (p in a) {
        val q = botNearestOnRing(p, b)
        val d = p.distanceTo(q)
        if (d < best) { best = d; pa = p; pb = q }
    }
    for (q in b) {
        val p = botNearestOnRing(q, a)
        val d = p.distanceTo(q)
        if (d < best) { best = d; pa = p; pb = q }
    }
    return pa to pb
}

/**
 * Pont entre deux contours : courbe de Bézier qui quitte chacun À ANGLE
 * DROIT. Ses extrémités sont légèrement décalées au hasard le long des
 * bords (courbes en S plutôt que droites). La courbe est prolongée de
 * 4 unités dans les deux formes : elle croise ainsi franchement leurs
 * bords ; les bouts intérieurs, sans issue, sont ignorés au calcul des
 * pièces. Null si la courbe ressort et rentre dans l'une des formes.
 */
private fun botBridge(a: List<Vector2>, b: List<Vector2>, rnd: Random): List<Vector2>? {
    val (pa0, pb0) = botNearestPair(a, b)
    val d0 = pa0.distanceTo(pb0)
    if (d0 < 1) return null
    val ra = BotRing(a)
    val rb = BotRing(b)
    val shift = d0 * 0.25
    val sa = ra.project(pa0) + rnd.nextDouble(-shift, shift)
    val sb = rb.project(pb0) + rnd.nextDouble(-shift, shift)
    val pa = ra.pointAt(sa)
    val pb = rb.pointAt(sb)
    val na = ra.outwardNormal(sa)
    val nb = rb.outwardNormal(sb)
    val d = pa.distanceTo(pb)
    val k = 0.38 * d
    val main = botCubic(pa, Vector2(pa.x + na.x * k, pa.y + na.y * k), Vector2(pb.x + nb.x * k, pb.y + nb.y * k), pb, 30)
    for (i in 1 until main.size - 1) {
        if (polygonContains(a, main[i]) || polygonContains(b, main[i])) return null
    }
    return listOf(Vector2(pa.x - na.x * 4, pa.y - na.y * 4)) + main + Vector2(pb.x - nb.x * 4, pb.y - nb.y * 4)
}

/** Pédoncule qui part de la BASE de la fleur, dans son axe, et rejoint la tige à angle droit. */
private fun botBaseStalk(base: Vector2, angle: Double, flower: List<Vector2>, stem: List<Vector2>, inset: Double = 6.0): List<Vector2>? {
    val ux = cos(angle)
    val uy = sin(angle)
    val probe = Vector2(base.x - ux * 40, base.y - uy * 40)
    val ring = BotRing(stem)
    val s = ring.project(probe)
    val q = ring.pointAt(s)
    val n = ring.outwardNormal(s)
    val d = q.distanceTo(base)
    val k = 0.40 * d
    val pts = botCubic(
        Vector2(base.x + ux * inset, base.y + uy * inset),
        Vector2(base.x - ux * k, base.y - uy * k),
        Vector2(q.x + n.x * k, q.y + n.y * k),
        Vector2(q.x - n.x * 4, q.y - n.y * 4), 30
    )
    // il doit sortir de la fleur une seule fois, puis entrer dans la tige une seule fois
    val inFlower = pts.map { polygonContains(flower, it) }
    val inStem = pts.map { polygonContains(stem, it) }
    val leaveFlower = inFlower.indexOfFirst { !it }
    if (leaveFlower < 0 || inFlower.drop(leaveFlower).any { it }) return null
    val enterStem = inStem.indexOfFirst { it }
    if (enterStem < 0 || inStem.drop(enterStem).any { !it } || enterStem <= leaveFlower) return null
    if (pts[leaveFlower].distanceTo(pts[enterStem]) < 0.3 * d) return null
    return pts
}

// --------------------------------------------------
// OUTILS GÉOMÉTRIQUES
// --------------------------------------------------

private fun botWrap(x: Double, period: Double) = ((x % period) + period) % period

private fun botShift(points: List<Vector2>, o: Vector2) =
    if (o.x == 0.0 && o.y == 0.0) points else points.map { Vector2(it.x + o.x, it.y + o.y) }

private fun botBounds(points: List<Vector2>): DoubleArray {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (p in points) {
        if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
    }
    return doubleArrayOf(minX, minY, maxX, maxY)
}

/** Écart entre deux boîtes englobantes (la seconde décalée de o) ; 0 si elles se touchent. */
private fun botBoundsGap(a: DoubleArray, b: DoubleArray, o: Vector2): Double {
    val dx = max(0.0, max(b[0] + o.x - a[2], a[0] - (b[2] + o.x)))
    val dy = max(0.0, max(b[1] + o.y - a[3], a[1] - (b[3] + o.y)))
    return hypot(dx, dy)
}

private fun botPointSegment(p: Vector2, a: Vector2, b: Vector2): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    val u = if (l2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
    return hypot(a.x + dx * u - p.x, a.y + dy * u - p.y)
}

private fun botSegmentsIntersect(a: Vector2, b: Vector2, c: Vector2, d: Vector2): Boolean {
    fun cross(o: Vector2, p: Vector2, q: Vector2) = (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x)
    val d1 = cross(c, d, a)
    val d2 = cross(c, d, b)
    val d3 = cross(a, b, c)
    val d4 = cross(a, b, d)
    return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
}

private fun botSegmentDistance(a: Vector2, b: Vector2, c: Vector2, d: Vector2): Double {
    if (botSegmentsIntersect(a, b, c, d)) return 0.0
    return min(min(botPointSegment(a, c, d), botPointSegment(b, c, d)), min(botPointSegment(c, a, b), botPointSegment(d, a, b)))
}

/** Distance entre deux lignes (fermées ou non). */
private fun botPolylineDistance(a: List<Vector2>, aClosed: Boolean, b: List<Vector2>, bClosed: Boolean): Double {
    var best = Double.MAX_VALUE
    val na = if (aClosed) a.size else a.size - 1
    val nb = if (bClosed) b.size else b.size - 1
    for (i in 0 until na) {
        val p = a[i]
        val q = a[(i + 1) % a.size]
        for (j in 0 until nb) {
            val d = botSegmentDistance(p, q, b[j], b[(j + 1) % b.size])
            if (d < best) { best = d; if (best == 0.0) return 0.0 }
        }
    }
    return best
}

/** Distance d'une forme (ou d'une ligne) à une forme fermée : 0 si elles se chevauchent. */
private fun botShapeDistance(a: List<Vector2>, aClosed: Boolean, b: List<Vector2>): Double {
    if (polygonContains(b, a[0])) return 0.0
    if (aClosed && polygonContains(a, b[0])) return 0.0
    return botPolylineDistance(a, aClosed, b, true)
}

private fun botMinDistance(a: List<Vector2>, aClosed: Boolean, others: List<List<Vector2>>) =
    others.minOf { botShapeDistance(a, aClosed, it) }

private fun botPolylinesIntersect(a: List<Vector2>, b: List<Vector2>): Boolean {
    for (i in 0 until a.size - 1) for (j in 0 until b.size - 1) {
        if (botSegmentsIntersect(a[i], a[i + 1], b[j], b[j + 1])) return true
    }
    return false
}

private fun botNearestOnRing(p: Vector2, ring: List<Vector2>): Vector2 {
    var best = Double.MAX_VALUE
    var bestQ = ring[0]
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val u = if (l2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        val q = Vector2(a.x + dx * u, a.y + dy * u)
        val d = q.distanceTo(p)
        if (d < best) { best = d; bestQ = q }
    }
    return bestQ
}

private fun botNearestOnPolyline(p: Vector2, line: List<Vector2>): Vector2 {
    var best = Double.MAX_VALUE
    var bestQ = line[0]
    for (i in 0 until line.size - 1) {
        val a = line[i]
        val b = line[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val u = if (l2 > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        val q = Vector2(a.x + dx * u, a.y + dy * u)
        val d = q.distanceTo(p)
        if (d < best) { best = d; bestQ = q }
    }
    return bestQ
}

private fun botNearestOnPolygons(p: Vector2, polygons: List<List<Vector2>>): Vector2 =
    polygons.map { botNearestOnRing(p, it) }.minByOrNull { it.distanceTo(p) }!!

/** Portion d'une ligne entre deux fractions de sa longueur. */
private fun botSubPolyline(line: List<Vector2>, from: Double, to: Double): List<Vector2> {
    val n = 20
    val ring = BotRingOpen(line)
    return (0..n).map { ring.pointAt(ring.length * (from + (to - from) * it / n)) }
}

private class BotRingOpen(val points: List<Vector2>) {
    val cumulative = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + points[i].distanceTo(points[i - 1])
    }
    val length get() = cumulative[points.size - 1]
    fun pointAt(s: Double): Vector2 {
        var i = 0
        while (i < points.size - 2 && cumulative[i + 1] < s) i++
        val seg = cumulative[i + 1] - cumulative[i]
        val u = if (seg > 0) ((s - cumulative[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        val a = points[i]
        val b = points[i + 1]
        return Vector2(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
    }
}

/**
 * Parties d'une ligne situées HORS de tous les polygones donnés (par
 * exemple le contour d'une feuille, sans le bout qui entre dans la tige).
 */
private fun botClipOutside(line: List<Vector2>, polygons: List<List<Vector2>>): List<List<Vector2>> {
    val result = mutableListOf<List<Vector2>>()
    var current = mutableListOf<Vector2>()
    fun outside(p: Vector2) = polygons.none { polygonContains(it, p) }
    for (k in 0 until line.size - 1) {
        val a = line[k]
        val b = line[k + 1]
        val ts = mutableListOf(0.0, 1.0)
        for (poly in polygons) {
            for (i in poly.indices) {
                val c = poly[i]
                val d = poly[(i + 1) % poly.size]
                val rx = b.x - a.x; val ry = b.y - a.y
                val sx = d.x - c.x; val sy = d.y - c.y
                val den = rx * sy - ry * sx
                if (abs(den) < 1e-12) continue
                val qx = c.x - a.x; val qy = c.y - a.y
                val t = (qx * sy - qy * sx) / den
                val u = (qx * ry - qy * rx) / den
                if (t in 0.0..1.0 && u in 0.0..1.0) ts.add(t)
            }
        }
        ts.sort()
        for (m in 0 until ts.size - 1) {
            val t0 = ts[m]
            val t1 = ts[m + 1]
            if (t1 - t0 < 1e-12) continue
            val mid = Vector2(a.x + (b.x - a.x) * (t0 + t1) / 2, a.y + (b.y - a.y) * (t0 + t1) / 2)
            val p0 = Vector2(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0)
            val p1 = Vector2(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1)
            if (outside(mid)) {
                if (current.isEmpty()) current.add(p0)
                current.add(p1)
            } else if (current.isNotEmpty()) {
                result.add(current); current = mutableListOf()
            }
        }
    }
    if (current.size >= 2) result.add(current)
    // Prolonge chaque morceau d'une unité à ses extrémités coupées : il
    // croise ainsi franchement le bord de la tige au calcul des pièces.
    return result.filter { it.size >= 2 }.map { piece ->
        fun ext(p: Vector2, q: Vector2): Vector2 {
            val l = p.distanceTo(q).takeIf { it > 0 } ?: 1.0
            return Vector2(p.x + (p.x - q.x) / l, p.y + (p.y - q.y) / l)
        }
        val first = piece.first()
        val last = piece.last()
        val head = if (first != line.first()) listOf(ext(first, piece[1])) else emptyList()
        val tail = if (last != line.last()) listOf(ext(last, piece[piece.size - 2])) else emptyList()
        head + piece + tail
    }
}

/** Centre et direction de la plus grande longueur d'une pièce (axe principal de ses sommets). */
private fun botPrincipalAxis(face: List<Vector2>): Pair<Vector2, Double> {
    val c = polygonCentroid(face)
    var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    for (p in face) {
        val dx = p.x - c.x
        val dy = p.y - c.y
        sxx += dx * dx; syy += dy * dy; sxy += dx * dy
    }
    return botDeepPoint(face) to 0.5 * atan2(2 * sxy, sxx - syy)
}

/** Arc de cercle de rayon r passant par p, tangent à `angle`, de longueur 2 × halfLength. */
private fun botArc(p: Vector2, angle: Double, r: Double, side: Double, halfLength: Double): List<Vector2> {
    val nx = -sin(angle) * side
    val ny = cos(angle) * side
    val cx = p.x + nx * r
    val cy = p.y + ny * r
    val a0 = atan2(p.y - cy, p.x - cx)
    val span = halfLength / r
    return (0..60).map { i ->
        val a = a0 - span + 2 * span * i / 60
        Vector2(cx + r * cos(a), cy + r * sin(a))
    }
}

/** Parties d'une ligne situées DANS un polygone (prolongées d'une unité au-delà de son bord). */
private fun botClipInside(line: List<Vector2>, polygon: List<Vector2>): List<List<Vector2>> {
    val result = mutableListOf<List<Vector2>>()
    var current = mutableListOf<Vector2>()
    for (k in 0 until line.size - 1) {
        val a = line[k]
        val b = line[k + 1]
        val ts = mutableListOf(0.0, 1.0)
        for (i in polygon.indices) {
            val c = polygon[i]
            val d = polygon[(i + 1) % polygon.size]
            val rx = b.x - a.x; val ry = b.y - a.y
            val sx = d.x - c.x; val sy = d.y - c.y
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-12) continue
            val qx = c.x - a.x; val qy = c.y - a.y
            val t = (qx * sy - qy * sx) / den
            val u = (qx * ry - qy * rx) / den
            if (t in 0.0..1.0 && u in 0.0..1.0) ts.add(t)
        }
        ts.sort()
        for (m in 0 until ts.size - 1) {
            val t0 = ts[m]
            val t1 = ts[m + 1]
            if (t1 - t0 < 1e-12) continue
            val mid = Vector2(a.x + (b.x - a.x) * (t0 + t1) / 2, a.y + (b.y - a.y) * (t0 + t1) / 2)
            if (polygonContains(polygon, mid)) {
                if (current.isEmpty()) current.add(Vector2(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0))
                current.add(Vector2(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1))
            } else if (current.isNotEmpty()) {
                result.add(current); current = mutableListOf()
            }
        }
    }
    if (current.size >= 2) result.add(current)
    return result.filter { it.size >= 2 }.map { piece ->
        fun ext(p: Vector2, q: Vector2): Vector2 {
            val l = p.distanceTo(q).takeIf { it > 0 } ?: 1.0
            return Vector2(p.x + (p.x - q.x) / l, p.y + (p.y - q.y) / l)
        }
        listOf(ext(piece.first(), piece[1])) + piece + ext(piece.last(), piece[piece.size - 2])
    }
}

/** Point bien intérieur d'une pièce (milieu du plus large passage horizontal). */
private fun botDeepPoint(face: List<Vector2>): Vector2 {
    val b = botBounds(face)
    var best: Vector2? = null
    var bestWidth = -1.0
    for (k in 1..7) {
        val y = b[1] + (b[3] - b[1]) * k / 8.0
        val xs = mutableListOf<Double>()
        for (i in face.indices) {
            val p = face[i]
            val q = face[(i + 1) % face.size]
            if ((p.y > y) != (q.y > y)) xs.add(p.x + (y - p.y) * (q.x - p.x) / (q.y - p.y))
        }
        xs.sort()
        var m = 0
        while (m + 1 < xs.size) {
            val w = xs[m + 1] - xs[m]
            if (w > bestWidth) { bestWidth = w; best = Vector2((xs[m] + xs[m + 1]) / 2, y) }
            m += 2
        }
    }
    return best ?: interiorPoint(face)
}