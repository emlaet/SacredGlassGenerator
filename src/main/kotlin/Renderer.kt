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
    /** Teintes forcées du médaillon (ignorées hors RadiantCross). */
    val crossMedallionShades: List<ColorRGBa>,

    // Système 4 — Plomb
    val leadSystem: LeadSystem,
    val leadStyle: LeadStyle,

    // Système 5 — Verre
    val glassSystem: GlassSystem,
    val glassStyle: GlassStyle,

    // Géométrie des arêtes et fond de l'aperçu
    val curvatureAmount: Double,
    val backgroundColor: ColorRGBa
)

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
// ⚠ Reproductibilité : l'ordre des appels ci-dessous (composition,
// segmentation, palette, post-traitement de la croix, variation de
// matière du verre) détermine l'ordre des tirages de random. Le
// modifier changerait l'œuvre produite par un même seed.
fun renderVitrail(
    targetDrawer: Drawer,
    config: VitrailConfig,
    canvasWidth: Double,
    canvasHeight: Double,
    pixelScale: Double
) {

    // Random(seed) est recréé à CHAQUE appel, jamais partagé : l'aperçu
    // et l'export haute résolution consomment ainsi chacun leur propre
    // séquence de tirages à partir du même seed, et produisent
    // exactement le même motif (juste à une résolution différente).
    val random = Random(config.seed)

    val scaledCurvatureAmount = config.curvatureAmount * pixelScale

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

    // Post-traitement SPÉCIFIQUE à la croix rayonnante : bras forcés
    // en blanc cassé, médaillon selon MedallionMode (voir
    // applyRadiantCrossColors dans Palette.kt). ⚠ Consomme des tirages
    // de random : garder cet appel à cet endroit.
    if (guide is CompositionGuide.RadiantCross) {
        applyRadiantCrossColors(
            guide = guide,
            cells = cells,
            cellColors = cellColors,
            armShades = config.crossArmShades,
            medallionShades = config.crossMedallionShades,
            random = random
        )
    }

    // Système 4 — Plomb (épaisseur et décalage du reflet mis à l'échelle)
    val leadStyle = config.leadStyle.copy(
        width = config.leadStyle.width * pixelScale,
        highlightOffset = config.leadStyle.highlightOffset * pixelScale
    )

    // Système 5 — Verre
    val glassShadeStyle = config.glassSystem.createShadeStyle(config.glassStyle)
    val materialVariation = config.glassSystem.assignMaterialVariation(cells.size, random)

    val edgeCurveCache = mutableMapOf<String, EdgeCurve>()

    // Remplissage (Palette + Verre)
    cells.forEachIndexed { index, cell ->

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
        val canvasPosition = Vector2(
            cellCenter.x / canvasWidth,
            cellCenter.y / canvasHeight
        )

        targetDrawer.fill = cellColors[index]
        targetDrawer.stroke = null

        glassShadeStyle.parameter("cellCenter", normalizedCellCenter)
        glassShadeStyle.parameter("canvasPosition", canvasPosition)
        glassShadeStyle.parameter("opalescence", materialVariation[index])
        targetDrawer.shadeStyle = glassShadeStyle

        targetDrawer.contour(contour)
    }

    // Bordures (Plomb)
    config.leadSystem.draw(targetDrawer, cells, edgeCurveCache, scaledCurvatureAmount, leadStyle)
}