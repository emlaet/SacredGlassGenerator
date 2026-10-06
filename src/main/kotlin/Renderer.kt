import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.random.Random

// --------------------------------------------------
// RENDU — assemblage des 5 systèmes
// --------------------------------------------------

/**
 * Une "recette" complète de vitrail : tout ce qu'il faut pour
 * reproduire exactement une œuvre. Même config (même seed, mêmes
 * systèmes, mêmes réglages) = même image, à n'importe quelle
 * résolution.
 *
 * Première ébauche des futures recettes du projet : pour l'instant,
 * la config est construite dans TemplateProgram.kt ; à terme, des
 * recettes nommées pourront être définies à l'avance (une par style
 * ou par saison) et simplement choisies.
 *
 * Les valeurs en PIXELS ABSOLUS (leadStyle.width,
 * leadStyle.highlightOffset, curvatureAmount) sont données pour la
 * largeur de référence de l'aperçu (768 px) : renderVitrail() les
 * multiplie par pixelScale pour les exports plus grands.
 */
data class VitrailConfig(
    val seed: Int,
    val numberOfSites: Int,

    // Système 1 et 2 — à échanger ENSEMBLE (voir TemplateProgram.kt)
    val compositionSystem: CompositionSystem,
    val segmentationSystem: SegmentationSystem,

    // Système 3 — Palette
    val paletteSystem: PaletteSystem,
    /** Teintes forcées des bras de la croix (ignorées hors RadiantCross). */
    val crossArmShades: List<ColorRGBa>,
    /** Teintes forcées du médaillon de la croix (ignorées hors RadiantCross). */
    val crossMedallionShades: List<ColorRGBa>,

    // Système 4 — Plomb
    val leadSystem: LeadSystem,
    val leadStyle: LeadStyle,

    // Système 5 — Verre
    val glassSystem: GlassSystem,
    val glassStyle: GlassStyle,

    // Géométrie des arêtes et fond de l'aperçu
    val curvatureAmount: Double,
    val backgroundColor: ColorRGBa,

    // Ange rayonnant (ignorés hors RadiantAngel). En dernier, avec des
    // valeurs par défaut, pour ne rien changer aux recettes existantes.

    /** Teintes forcées du médaillon de poitrine de l'ange : à régler sur
     *  la teinte sombre de la saison (seasonalMedallionShades de la
     *  palette liturgique active — voir TemplateProgram.kt). */
    val angelChestShades: List<ColorRGBa> = LiturgicalPalettes.ORDINAIRE.seasonalMedallionShades,
    /** Teintes forcées de la tête de l'ange (blanc cassé par défaut). */
    val angelHeadShades: List<ColorRGBa> = LiturgicalPalettes.ANGEL_HEAD_SHADES,
    /** Teintes forcées du nimbe de l'ange (or par défaut). */
    val angelHaloShades: List<ColorRGBa> = LiturgicalPalettes.ANGEL_HALO_SHADES,
    /** Teintes forcées de la robe et du cou de l'ange (ivoire doré par défaut). */
    val angelRobeShades: List<ColorRGBa> = LiturgicalPalettes.ANGEL_ROBE_SHADES,
    /** Teintes forcées des ailes de l'ange (blanc cassé par défaut). */
    val angelWingShades: List<ColorRGBa> = LiturgicalPalettes.ANGEL_WING_SHADES,

    /** Gamme de couleurs du motif raccordable (ignorée hors
     *  PeriodicShards) — voir applyPeriodicColorField, Palette.kt. */
    val tileColorRamp: List<ColorRampStop> = ColorRamps.ARC_EN_CIEL,

    /** Palette de la famille végétale (ignorée hors BotanicalGuide) —
     *  voir BotanicalPalettes, Botanical.kt. */
    val botanicalPalette: BotanicalPalette = BotanicalPalettes.PRINTEMPS
)

/**
 * Taille de la tuile pour les MOTIFS RACCORDABLES (pièces dessinées aux
 * 9 positions) ; null pour une image unique.
 */
fun tileSizeOf(guide: CompositionGuide): Vector2? = when (guide) {
    is CompositionGuide.PeriodicShards -> Vector2(guide.tileWidth, guide.tileHeight)
    is BotanicalGuide -> Vector2(guide.tileWidth, guide.tileHeight)
    else -> null
}

// Toute la génération (Composition → Segmentation → Palette →
// Plomb → Verre) et le dessin sont regroupés dans cette fonction,
// plutôt que calculés une seule fois au démarrage. Nécessaire pour
// l'export haute résolution : on ne peut pas se contenter d'agrandir
// l'image déjà calculée à 768×576
// (le plomb, la courbure des arêtes, la taille des cellules sont
// tous définis en PIXELS ABSOLUS — les ré-agrandir sans
// régénérer la géométrie donnerait un plomb relativement plus
// fin, une courbure relativement plus plate, etc. à haute
// résolution). À la place, on régénère tout le motif à la
// résolution cible avec le MÊME seed (donc le même dessin), et
// on multiplie les paramètres en pixels absolus par pixelScale
// pour garder les mêmes proportions relatives.
//
// canvasWidth/canvasHeight : dimensions cibles (aperçu ou export).
// pixelScale : rapport entre canvasWidth et la largeur de
// référence (768) — 1.0 pour l'aperçu, >1.0 pour un export plus
// grand. S'applique à tout ce qui est défini en pixels absolus
// (épaisseur du plomb, curvatureAmount) ; tout ce qui est déjà relatif
// (ratios, poids, fréquences de bruit du shader en uv 0–1) n'a
// besoin d'aucun ajustement et s'adapte de lui-même.
//
// Les tirages aléatoires (composition, segmentation, palette, etc.)
// sont faits par generateVitrail, plus bas dans ce fichier ;
// renderVitrail ne fait que dessiner.
fun renderVitrail(
    targetDrawer: Drawer,
    config: VitrailConfig,
    canvasWidth: Double,
    canvasHeight: Double,
    pixelScale: Double
) {

    val scaledCurvatureAmount = config.curvatureAmount * pixelScale

    // Génération (tirages aléatoires) : calculée une fois par recette et
    // par taille, puis réutilisée à chaque image de l'aperçu (voir
    // generateVitrail).
    val (guide, cells, cellColors, materialVariation) =
        generateVitrail(config, canvasWidth, canvasHeight)

    // Système 4 — Plomb (épaisseur et décalage du reflet mis à l'échelle)
    val leadStyle = config.leadStyle.copy(
        width = config.leadStyle.width * pixelScale,
        highlightOffset = config.leadStyle.highlightOffset * pixelScale
    )

    // Système 5 — Verre
    val glassShadeStyle = config.glassSystem.createShadeStyle(config.glassStyle)

    val edgeCurveCache = mutableMapOf<String, EdgeCurve>()

    // Motif raccordable : chaque pièce canonique est dessinée à 9
    // positions (la tuile et ses 8 voisines), avec la MÊME couleur et la
    // même matière — les pièces coupées par le bord de la tuile se
    // raccordent ainsi exactement avec leur autre moitié. Pour toutes les
    // autres compositions, une seule position (aucun changement).
    val tile = tileSizeOf(guide)
    val tileOffsets = if (tile != null) {
        (-1..1).flatMap { dx -> (-1..1).map { dy -> Vector2(dx * tile.x, dy * tile.y) } }
    } else {
        listOf(Vector2(0.0, 0.0))
    }
    val drawnCells = tileOffsets.flatMap { offset ->
        cells.map { cell -> cell.map { Vector2(it.x + offset.x, it.y + offset.y) } }
    }

    // Remplissage (Palette + Verre)
    drawnCells.forEachIndexed { drawnIndex, cell ->

        // Pièce canonique d'origine (même couleur, même matière).
        val index = drawnIndex % cells.size

        if (cell.size < 3) {
            return@forEachIndexed
        }

        val contour = createCurvedContour(cell, edgeCurveCache, scaledCurvatureAmount)

        val cellCenter = polygonCentroid(cell)
        val bounds = contour.bounds

        // Position normalisée DANS la cellule (déjà existant) :
        // pilote la vignette locale (plus clair au centre de
        // la pièce, plus sombre vers ses bords).
        val normalizedCellCenter = Vector2(
            (cellCenter.x - bounds.corner.x) / bounds.width,
            (cellCenter.y - bounds.corner.y) / bounds.height
        )

        // Position normalisée DANS TOUT LE CANEVAS (nouveau) :
        // pilote le dégradé de lumière globale cohérent sur
        // l'ensemble du tableau (voir Glass.kt, section 4).
        // (Motif raccordable : position de la pièce CANONIQUE ramenée
        // dans la tuile, identique pour toutes ses copies.)
        val canvasPosition = if (tile != null) {
            val c = polygonCentroid(cells[index])
            Vector2(
                ((c.x % canvasWidth) + canvasWidth) % canvasWidth / canvasWidth,
                ((c.y % canvasHeight) + canvasHeight) % canvasHeight / canvasHeight
            )
        } else {
            Vector2(
                cellCenter.x / canvasWidth,
                cellCenter.y / canvasHeight
            )
        }

        targetDrawer.fill = cellColors[index]
        targetDrawer.stroke = null

        glassShadeStyle.parameter("cellCenter", normalizedCellCenter)
        glassShadeStyle.parameter("canvasPosition", canvasPosition)
        glassShadeStyle.parameter("opalescence", materialVariation[index])
        targetDrawer.shadeStyle = glassShadeStyle

        targetDrawer.contour(contour)
    }

    // Détails peints à la grisaille (famille végétale : barbes du blé,
    // vrilles), entre le verre et le plomb.
    if (guide is BotanicalGuide) {
        drawBotanicalPaint(targetDrawer, guide, tileOffsets, config.leadStyle.width * 0.36 * pixelScale)
    }

    // Bordures (Plomb)
    config.leadSystem.draw(targetDrawer, drawnCells, edgeCurveCache, scaledCurvatureAmount, leadStyle)
}

/** Tout ce que les tirages aléatoires décident pour une œuvre. */
data class GeneratedVitrail(
    val guide: CompositionGuide,
    val cells: List<List<Vector2>>,
    val cellColors: List<ColorRGBa>,
    val materialVariation: List<Double>
)

// Dernières générations calculées (aperçu, aperçu 2 × 2, export) : la
// génération peut prendre plusieurs dixièmes de seconde (motif
// raccordable), trop pour être refaite à chaque image de l'aperçu. La
// clé est la recette ET la taille : changer l'une ou l'autre relance la
// génération. Aucun effet sur l'image produite.
private val generationCache = object : LinkedHashMap<Triple<VitrailConfig, Double, Double>, GeneratedVitrail>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<VitrailConfig, Double, Double>, GeneratedVitrail>?) = size > 4
}

/**
 * Génération d'une œuvre : composition, segmentation, palette,
 * post-traitements de couleur et variation de matière du verre.
 *
 * Random(seed) est recréé à CHAQUE génération, jamais partagé : l'aperçu
 * et l'export haute résolution consomment ainsi chacun leur propre
 * séquence de tirages à partir du même seed, et produisent exactement
 * le même motif (juste à une résolution différente).
 *
 * ⚠ Reproductibilité : l'ordre des appels ci-dessous détermine l'ordre
 * des tirages de random. Le modifier changerait l'œuvre produite par un
 * même seed.
 */
fun generateVitrail(
    config: VitrailConfig,
    canvasWidth: Double,
    canvasHeight: Double
): GeneratedVitrail = generationCache.getOrPut(Triple(config, canvasWidth, canvasHeight)) {
    computeVitrail(config, canvasWidth, canvasHeight)
}

private fun computeVitrail(
    config: VitrailConfig,
    canvasWidth: Double,
    canvasHeight: Double
): GeneratedVitrail {

    val random = Random(config.seed)

    // Système 1 — Composition
    val guide = config.compositionSystem.generate(
        canvasWidth,
        canvasHeight,
        config.numberOfSites,
        random
    )

    // Système 2 — Segmentation
    val cells = config.segmentationSystem.segment(
        guide,
        canvasWidth,
        canvasHeight,
        random
    )

    // Système 3 — Palette
    val cellColors = config.paletteSystem.assignColors(cells, random).toMutableList()

    // Post-traitements SPÉCIFIQUES aux compositions emblématiques (voir
    // Palette.kt) :
    // - croix rayonnante : bras forcés en blanc cassé, médaillon selon
    //   MedallionMode (applyRadiantCrossColors) ;
    // - ange rayonnant : poitrine sombre (teinte de la saison), tête et
    //   ailes en blanc cassé, nimbe doré, cou et robe en ivoire doré
    //   (applyRadiantAngelColors) ;
    // - motif raccordable : champ de couleur périodique
    //   (applyPeriodicColorField) ;
    // - famille végétale : couleur selon le rôle de chaque pièce
    //   (applyBotanicalColors, Botanical.kt).
    // ⚠ Consomment des tirages de random : garder cet appel à cet endroit.
    when (guide) {
        is CompositionGuide.RadiantCross -> applyRadiantCrossColors(
            guide = guide,
            cells = cells,
            cellColors = cellColors,
            armShades = config.crossArmShades,
            medallionShades = config.crossMedallionShades,
            random = random
        )
        is CompositionGuide.RadiantAngel -> applyRadiantAngelColors(
            guide = guide,
            cells = cells,
            cellColors = cellColors,
            chestShades = config.angelChestShades,
            headShades = config.angelHeadShades,
            haloShades = config.angelHaloShades,
            bodyShades = config.angelRobeShades,
            wingShades = config.angelWingShades,
            random = random
        )
        is CompositionGuide.PeriodicShards -> applyPeriodicColorField(
            guide = guide,
            cells = cells,
            cellColors = cellColors,
            ramp = config.tileColorRamp,
            random = random
        )
        is BotanicalGuide -> applyBotanicalColors(
            guide = guide,
            cellColors = cellColors,
            palette = config.botanicalPalette,
            random = random
        )
        else -> {}
    }

    // Système 5 — Verre (variation de matière de chaque pièce)
    val materialVariation = config.glassSystem.assignMaterialVariation(cells.size, random)

    return GeneratedVitrail(guide, cells, cellColors, materialVariation)
}