import kotlin.math.roundToInt

// --------------------------------------------------
// FORMAT D'IMPRESSION (taille réelle et résolution de l'export)
// --------------------------------------------------
//
// Par défaut, l'export (touche « e ») fait 3600 × 2700 px (4:3), ou
// 3600 × 5400 px pour une baie (2:3). Ces tailles suffisent pour un tirage
// de 30 × 22,5 cm (ou 30 × 45 cm) à 300 ppp, mais pas pour un grand format :
// un tirage aluminium de 60 × 60 cm à 300 ppp demande 7087 × 7087 px.
//
// PrintFormat donne le format RÉEL du produit (en millimètres, fond perdu
// compris si le gabarit en demande un) et la résolution voulue (dpi = ppp) :
// - l'export fait alors largeur ÷ 25,4 × dpi sur hauteur ÷ 25,4 × dpi pixels ;
// - la composition est générée dans un cadre AUX PROPORTIONS DU FORMAT (un
//   carré pour 60 × 60 cm), centré dans la fenêtre d'aperçu ; à l'export, ce
//   cadre occupe toute l'image. L'aperçu et l'export restent identiques.
// - changer dpi ne change PAS le dessin, seulement le nombre de pixels ;
//   changer les proportions du format (carré, 4:3, 2:3…) change la mise en
//   page (même seed, autre cadre).
//
// Cas particuliers :
// - baie : la baie garde ses proportions (2:3) ; seule la taille de l'export
//   suit le format (pour un format qui n'est pas 2:3, la baie est centrée,
//   avec des marges transparentes) ;
// - motifs raccordables (éclats, végétal) : le format est ignoré (la tuile
//   doit garder sa taille exacte) ;
// - fond « champ de rayons » : il a son propre format (RayField.kt).

data class PrintFormat(
    /** Largeur du produit, en millimètres (fond perdu compris s'il y en a un). */
    val widthMm: Double,
    /** Hauteur du produit, en millimètres (fond perdu compris s'il y en a un). */
    val heightMm: Double,
    /** Résolution de l'export, en dpi (ppp) : 300 au minimum pour Gelato. */
    val dpi: Double = 300.0
) {
    /** Taille de l'export, en pixels. */
    val widthPx: Int get() = (widthMm / 25.4 * dpi).roundToInt()
    val heightPx: Int get() = (heightMm / 25.4 * dpi).roundToInt()

    /**
     * Cadre de la composition dans un canevas : le plus grand rectangle aux
     * proportions du format, centré. À l'export, il occupe tout le canevas.
     */
    fun frame(canvasWidth: Double, canvasHeight: Double): DoubleArray {
        val aspect = heightMm / widthMm
        var w = canvasWidth
        var h = w * aspect
        if (h > canvasHeight) {
            h = canvasHeight
            w = h / aspect
        }
        return doubleArrayOf((canvasWidth - w) / 2, (canvasHeight - h) / 2, w, h)
    }

    /**
     * Échelle des valeurs en pixels absolus (plomb, courbure) : largeur du
     * cadre dans ce canevas, rapportée à sa largeur dans l'aperçu de
     * référence (768 × 576). Même principe que la baie (bayPixelScale).
     */
    fun pixelScale(canvasWidth: Double, canvasHeight: Double): Double =
        frame(canvasWidth, canvasHeight)[2] / frame(768.0, 576.0)[2]
}

/**
 * Le format d'impression s'applique-t-il au CADRE de cette recette ? Non
 * pour une baie (proportions propres), un motif raccordable (tuile exacte)
 * ou le champ de rayons (format propre).
 */
fun VitrailConfig.framedPrintFormat(): PrintFormat? {
    val format = printFormat ?: return null
    if (bay != null) return null
    return when (compositionSystem) {
        is PeriodicShardsCompositionSystem, is BotanicalCompositionSystem, is RayFieldCompositionSystem -> null
        else -> format
    }
}