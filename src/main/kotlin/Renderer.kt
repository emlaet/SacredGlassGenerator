import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BlendMode
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
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
    val botanicalPalette: BotanicalPalette = BotanicalPalettes.PRINTEMPS,

    /** Baie en arc (bordure, fond en losanges, barlotières) autour de la
     *  composition — null = pas de baie (toutes les recettes existantes).
     *  Voir Bay.kt. */
    val bay: BayStyle? = null,

    /** Format d'impression (taille réelle en mm et résolution) : taille de
     *  l'export et cadre de la composition — null = export 3600 × 2700 px
     *  (4:3), ou 3600 × 5400 px pour une baie. Voir PrintFormat.kt. */
    val printFormat: PrintFormat? = null,

    /** Couleurs de la rosace (ignorées hors RosaceGuide) — voir
     *  RosaceSchemes, Rosace.kt. */
    val rosaceScheme: RosaceScheme = RosaceSchemes.CATHEDRALE,

    /** Palette par rôles des nouveaux dessins de rosace (rosace médiévale
     *  sobre ; ignorée par la rosace rayonnante, qui suit rosaceScheme) —
     *  voir RosacePalettes, Rosace.kt. */
    val rosacePalette: RosacePalette = RosacePalettes.GRENAT_OR,

    /** Rosace en VERRE CLAIR (ornement en verre imprimé en UV opaque) : seuls
     *  la pierre et les plombs sont dessinés ; les pièces restent vides,
     *  transparentes à l'export (le verre du support se voit à travers) et de
     *  la couleur du fond (backgroundColor) dans l'aperçu. Ignoré hors
     *  RosaceGuide. Aucun effet sur la génération ni sur les tirages. */
    val rosaceClearGlass: Boolean = false,

    /** Rosace C2 noire (phase 14) : une des variantes de C2NoirVariants
     *  (C2Noir.kt), qui fixe les couleurs, les matières, les reflets et la
     *  couleur des plombs de la rosace C2 ; null = palettes habituelles
     *  (rosacePalette). Ignorée hors RosaceGuide. Aucun tirage. Voir
     *  c2NoirConfig, qui construit la recette complète d'une variante. */
    val c2Noir: C2NoirVariant? = null,

    /** Verre noir traversé par la lumière (brief du 23 nov. 2026) : facteur
     *  de transmission maximal du verre SOMBRE (2,0 = diffusion au cœur des
     *  pièces et irrégularités internes deux fois plus marquées ; voir
     *  GlassStyle.darkGlassTransmission). Chaque pièce reçoit un facteur
     *  selon la clarté de son verre (darkGlassFactor) : maximal pour le verre
     *  très sombre, 1,0 pour le verre clair. null = verre actuel, partout. */
    val darkGlassTransmission: Double? = null,

    /** Rosaces : largeur minimale des plombs fins (joints entre pièces,
     *  nervures), en multiple de leadStyle.width (C2 noire, 23 nov. 2026 :
     *  à 0,6 ils se lisaient comme des traits de plume). null = hiérarchie
     *  du dessin inchangée. */
    val minFineLeadFactor: Double? = null,

    /** Rosaces C2 : épaisseur des plombs fins PAR CATÉGORIE, en multiple de
     *  leadStyle.width (C2 noire, phase 15) : séparations intérieures (fond,
     *  bordure, couronne) et nervures des pétales (toutes les lignes fines à
     *  l'intérieur d'un pétale : nervure, flamme, calice, pointe). Les plombs
     *  structurels ne changent pas. null = épaisseur du dessin. */
    val fineLeadJointFactor: Double? = null,
    val fineLeadNervureFactor: Double? = null,

    /** Verre noir traversé par la lumière : variation de la transmission d'une
     *  pièce à l'autre (0,25 = ±25 %), déterministe (fonction de l'indice de la
     *  pièce, sans tirage aléatoire) : certaines pièces montrent un peu plus
     *  la différence entre leur centre et leurs bords. null = aucune. */
    val transmissionVariation: Double? = null,

    /** Modèle optique du verre (phase 18, voir GlassOptics dans Glass.kt) ;
     *  null = rendu actuel. */
    val glassOptics: GlassOptics? = null,

    /** Diffusion de la lumière dans la masse du verre, sans jamais traverser
     *  les plombs (phase 19, voir GlassDiffusion.kt) ; null = rendu actuel. */
    val glassDiffusion: GlassDiffusion? = null,

    /** Phase 22 : réglages par rôle de pièce de rosace (voir PieceTweak) ;
     *  vide = rendu actuel. */
    val pieceTweaks: Map<String, PieceTweak> = emptyMap(),
    /** Phase 22 : soudures aux croisements des plombs (RosaceDetails.kt). */
    val leadJoints: LeadJoints? = null,
    /** Phase 22 : grisaille sur le verre givré des pétales (RosaceDetails.kt). */
    val petalGrisaille: PetalGrisaille? = null
)

/** Phase 22 : réglage d'un rôle de pièce de rosace, appliqué au moment du
 *  dessin du verre (après la palette). Chaque champ null ou neutre laisse
 *  le rendu inchangé. */
data class PieceTweak(
    /** Couleur imposée (remplace celle de la palette). */
    val color: String? = null,
    /** Échelle de texture imposée (1,0 = texture normale ; hostie : 0,3). */
    val textureScale: Double? = null,
    /** Multiplicateur de l'amplitude d'épaisseur (modèle optique). */
    val thicknessScale: Double = 1.0,
    /** Épaisseur orientée vers le centre de la rosace : la pièce est plus
     *  épaisse, donc plus sombre, du côté du centre. */
    val thicknessInward: Boolean = false,
    /** Ne s'applique qu'aux pièces dont la couleur a une clarté OKLab au
     *  plus égale à ce seuil (null = toutes). */
    val maxLightness: Double? = null
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
    requestedPixelScale: Double,
    /** true à l'export (fond transparent) : voir rosaceClearGlass. */
    transparentBackground: Boolean = false
) {

    // Baie : l'échelle du plomb suit la largeur de la BAIE (voir
    // bayPixelScale, Bay.kt), pas celle du canevas.
    // Fond « champ de rayons » : l'échelle suit la largeur du FORMAT (voir
    // RayFieldCompositionSystem.pixelScale, RayField.kt).
    // Format d'impression : l'échelle suit la largeur du cadre (voir
    // PrintFormat.pixelScale, PrintFormat.kt).
    val pixelScale = config.bay?.let { bayPixelScale(it, canvasWidth, canvasHeight) }
        ?: (config.compositionSystem as? RayFieldCompositionSystem)?.pixelScale(canvasWidth, canvasHeight)
        ?: config.framedPrintFormat()?.pixelScale(canvasWidth, canvasHeight)
        ?: requestedPixelScale

    val scaledCurvatureAmount = config.curvatureAmount * pixelScale

    // Génération (tirages aléatoires) : calculée une fois par recette et
    // par taille, puis réutilisée à chaque image de l'aperçu (voir
    // generateVitrail).
    val (guide, cells, cellColors, materialVariation, bayGeometry) =
        generateVitrail(config, canvasWidth, canvasHeight)

    // Système 4 — Plomb (épaisseur et décalage du reflet mis à l'échelle)
    val leadStyle = config.leadStyle.copy(
        width = config.leadStyle.width * pixelScale,
        highlightOffset = config.leadStyle.highlightOffset * pixelScale
    )

    // Système 5 — Verre
    // (Rosace C2 noire : couleurs, textures et reflet continu des pétales
    // propres à la variante, voir C2Noir.kt.)
    val c2Variant = if (guide is RosaceGuide) config.c2Noir else null
    val c2Data = if (guide is RosaceGuide && c2Variant != null) c2NoirPieceData(guide.unitPieces, c2Variant) else null
    val c2Shade = if (guide is RosaceGuide && c2Variant != null) c2ShadeProgram(guide, c2Variant) else null
    var glassStyleUsed = if (c2Shade != null) config.glassStyle.copy(c2Shade = c2Shade) else config.glassStyle
    if (config.darkGlassTransmission != null) glassStyleUsed = glassStyleUsed.copy(darkGlassTransmission = true)
    if (config.glassOptics != null) glassStyleUsed = glassStyleUsed.copy(optics = config.glassOptics)
    val glassShadeStyle = config.glassSystem.createShadeStyle(glassStyleUsed)

    val edgeCurveCache = mutableMapOf<String, EdgeCurve>()

    // Motif raccordable : chaque pièce canonique est dessinée à 9
    // positions (la tuile et ses 8 voisines), avec la MÊME couleur et la
    // même matière — les pièces coupées par le bord de la tuile se
    // raccordent ainsi exactement avec leur autre moitié. Pour toutes les
    // autres compositions, une seule position (aucun changement).
    val tile = if (bayGeometry != null) null else tileSizeOf(guide)
    val tileOffsets = if (tile != null) {
        (-1..1).flatMap { dx -> (-1..1).map { dy -> Vector2(dx * tile.x, dy * tile.y) } }
    } else {
        listOf(Vector2(0.0, 0.0))
    }
    val drawnCells = tileOffsets.flatMap { offset ->
        cells.map { cell -> cell.map { Vector2(it.x + offset.x, it.y + offset.y) } }
    }

    // Rosace : la pierre (disque plein) sous le verre. Les ouvertures du
    // remplage sont ensuite recouvertes par les pièces de verre.
    val frameOffset = config.framedPrintFormat()?.frame(canvasWidth, canvasHeight)
        ?.let { Vector2(it[0], it[1]) } ?: Vector2(0.0, 0.0)
    if (guide is RosaceGuide && bayGeometry == null) {
        drawRosaceStone(targetDrawer, guide, frameOffset, rosaceStoneColor(guide, config.rosaceScheme, config.rosacePalette))
    }

    // Remplissage (Palette + Verre)
    val drawGlassPieces: () -> Unit = { drawnCells.forEachIndexed { drawnIndex, cell ->

        // Pièce canonique d'origine (même couleur, même matière).
        val index = drawnIndex % cells.size

        if (cell.size < 3) {
            return@forEachIndexed
        }

        // Rosace : arêtes droites (ses courbes sont faites de nombreux petits
        // segments), contour construit directement, sans le cache d'arêtes.
        val contour = if (guide is RosaceGuide) straightContour(cell)
        else createCurvedContour(cell, edgeCurveCache, scaledCurvatureAmount)

        // Rosace en verre clair : la pièce est « vidée » (la pierre, peinte
        // en disque plein, est effacée à cet endroit). À l'export, REPLACE
        // écrit une transparence totale ; la teinte (celle des plombs, alpha
        // 0) évite un liseré coloré au bord des plombs, qui recouvrent tous
        // les contours des pièces. Dans l'aperçu : la couleur du fond.
        if (config.rosaceClearGlass && guide is RosaceGuide) {
            targetDrawer.isolated {
                shadeStyle = null
                stroke = null
                if (transparentBackground) {
                    drawStyle.blendMode = BlendMode.REPLACE
                    val c = config.leadStyle.color
                    fill = ColorRGBa(c.r, c.g, c.b, 0.0)
                } else {
                    fill = config.backgroundColor
                }
                contour(contour)
            }
            return@forEachIndexed
        }

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

        // Phase 22 : réglage du rôle de la pièce (couleur, texture, épaisseur).
        val tweak = if (guide is RosaceGuide && config.pieceTweaks.isNotEmpty())
            guide.unitPieces.getOrNull(index)?.role?.let { config.pieceTweaks[it] }
                ?.takeIf { t -> t.maxLightness == null || OkLab.toLab(cellColors[index])[0] <= t.maxLightness }
        else null
        val pieceColor = tweak?.color?.let { ColorRGBa.fromHex(it) } ?: cellColors[index]

        targetDrawer.fill = pieceColor
        targetDrawer.stroke = null

        glassShadeStyle.parameter("cellCenter", normalizedCellCenter)
        glassShadeStyle.parameter("canvasPosition", canvasPosition)
        glassShadeStyle.parameter("opalescence", materialVariation[index])
        // Verre noir traversé par la lumière : transmission de la pièce,
        // selon la clarté de son verre (darkGlassFactor).
        config.darkGlassTransmission?.let { tMax ->
            val v = config.transmissionVariation?.let { 1.0 + it * pieceHash(index) } ?: 1.0
            glassShadeStyle.parameter("transmission", maxOf(1.0, darkGlassFactor(pieceColor, tMax) * v))
        }
        // Texture adoucie pour certaines pièces de rosace (disque « hostie »
        // de C2) ; 1.0 partout ailleurs : rendu inchangé.
        glassShadeStyle.parameter("textureScale", tweak?.textureScale ?:
        if (c2Data != null) c2Data.textureScales[index]
        else if (guide is RosaceGuide) guide.unitPieces.getOrNull(index)?.let { rosaceTextureScale(it.role) } ?: 1.0 else 1.0)
        // C2 noire, reflet continu des pétales : position de la pièce dans la
        // rosace (unités ; u vers le haut, v vers la droite), voir C2Noir.kt.
        if (c2Shade != null && c2Data != null && guide is RosaceGuide) {
            val cx = guide.center.x + frameOffset.x
            val cy = guide.center.y + frameOffset.y
            val rr = guide.radius
            glassShadeStyle.parameter("shadeGroup", c2Data.shadeGroups[index])
            if (!C2_SHADE_FLIP_Y) {
                glassShadeStyle.parameter("modelOrigin", Vector2((cy - bounds.corner.y) / rr, (bounds.corner.x - cx) / rr))
                glassShadeStyle.parameter("modelSize", Vector2(bounds.height / rr, bounds.width / rr))
            } else {
                glassShadeStyle.parameter("modelOrigin", Vector2((cy - bounds.corner.y - bounds.height) / rr, (bounds.corner.x - cx) / rr))
                glassShadeStyle.parameter("modelSize", Vector2(-bounds.height / rr, bounds.width / rr))
            }
        }
        // Modèle optique (phase 18) : position continue sur le canevas et
        // variation d'épaisseur propre à la pièce.
        config.glassOptics?.let { o ->
            glassShadeStyle.parameter("canvasOrigin", Vector2(bounds.corner.x / canvasWidth, bounds.corner.y / canvasHeight))
            glassShadeStyle.parameter("canvasSize", Vector2(bounds.width / canvasWidth, bounds.height / canvasHeight))
            val role = (guide as? RosaceGuide)?.unitPieces?.getOrNull(index)?.role ?: ""
            val center = if (guide is RosaceGuide) Vector2(guide.center.x + frameOffset.x, guide.center.y + frameOffset.y)
            else Vector2(canvasWidth / 2.0, canvasHeight / 2.0)
            val radial = polygonCentroid(cells[index]) - center
            val (dir0, amp) = glassThickness(role, radial, index)
            val dir = if (tweak?.thicknessInward == true && radial.length > 1e-6) radial.normalized * -1.0 else dir0
            glassShadeStyle.parameter("thickDir", dir)
            glassShadeStyle.parameter("thickAmp", if (o.thickness) (amp * o.thicknessScale * (tweak?.thicknessScale ?: 1.0)).coerceAtMost(0.6) else 0.0)
            if (o.clearGlassDarkenOnly) glassShadeStyle.parameter("thickLift", thicknessLift(pieceColor))
        }
        targetDrawer.shadeStyle = glassShadeStyle

        targetDrawer.contour(contour)
    } }

    // Diffusion de la lumière dans le verre (phase 19, GlassDiffusion.kt) :
    // le verre est dessiné dans une image à part, diffusé pièce par pièce,
    // puis posé sur la pierre. Sans diffusion : dessin direct, comme avant.
    val diffusion = config.glassDiffusion?.takeIf { !config.rosaceClearGlass }
    if (diffusion == null) {
        drawGlassPieces()
    } else {
        val layers = GlassDiffusionLayers(Math.round(canvasWidth).toInt(), Math.round(canvasHeight).toInt())
        var leftFill: ColorRGBa? = null
        var leftStroke: ColorRGBa? = null
        targetDrawer.isolatedWithTarget(layers.glass) {
            ortho(layers.glass)
            clear(ColorRGBa.TRANSPARENT)
            drawGlassPieces()
            leftFill = fill
            leftStroke = stroke
        }
        targetDrawer.isolatedWithTarget(layers.ids) {
            ortho(layers.ids)
            clear(ColorRGBa.TRANSPARENT)
            shadeStyle = null
            stroke = null
            drawnCells.forEachIndexed { drawnIndex, cell ->
                if (cell.size >= 3) {
                    fill = glassPieceIdColor(drawnIndex)
                    contour(if (guide is RosaceGuide) straightContour(cell)
                    else createCurvedContour(cell, edgeCurveCache, scaledCurvatureAmount))
                }
            }
        }
        layers.diffuseAndDraw(targetDrawer, diffusion, diffusion.radius * canvasWidth)
        // Même état du dessin qu'après le dessin direct (les plombs fins sont
        // tracés ensuite avec cet état).
        targetDrawer.fill = leftFill
        targetDrawer.stroke = leftStroke
        targetDrawer.shadeStyle = glassShadeStyle
    }

    // Phase 22 : grisaille sur le verre givré des pétales de rosace, entre le
    // verre et le plomb (RosaceDetails.kt).
    if (config.petalGrisaille != null && guide is RosaceGuide && bayGeometry == null && !config.rosaceClearGlass) {
        drawRosacePetalGrisaille(targetDrawer, guide, cells, cellColors, frameOffset, leadStyle.width, config.petalGrisaille)
        targetDrawer.shadeStyle = glassShadeStyle
    }

    // Détails peints à la grisaille (famille végétale : barbes du blé,
    // vrilles), entre le verre et le plomb.
    if (guide is BotanicalGuide && bayGeometry == null) {
        drawBotanicalPaint(targetDrawer, guide, tileOffsets, config.leadStyle.width * 0.36 * pixelScale)
    }

    // Bordures (Plomb)
    // (Rosaces B et C : les plombs entre les pièces sont les plus fins de la
    // hiérarchie, RosaceGuide.cellLeadFactor ; les plus épais suivent.)
    // Leur couleur peut aussi différer (RosacePalette.leadFine : « contour
    // décoratif » plus clair que les plombs de structure).
    val baseFine = if (guide is RosaceGuide) maxOf(guide.cellLeadFactor, config.minFineLeadFactor ?: 0.0) else 1.0
    val jointFactor = config.fineLeadJointFactor ?: baseFine
    val nervureFactor = config.fineLeadNervureFactor ?: baseFine
    val fineLeadFactor = if (config.fineLeadJointFactor != null || config.fineLeadNervureFactor != null)
        minOf(jointFactor, nervureFactor) else baseFine
    val cellLeadStyle = if (guide is RosaceGuide && fineLeadFactor != 1.0)
        leadStyle.copy(
            width = leadStyle.width * fineLeadFactor,
            color = if (c2Variant != null) leadStyle.color else config.rosacePalette.leadFine ?: leadStyle.color
        ) else leadStyle
    config.leadSystem.draw(targetDrawer, drawnCells, edgeCurveCache, scaledCurvatureAmount, cellLeadStyle)

    // Rosace C2 : plombs fins plus épais pour une catégorie (séparations ou
    // nervures), redessinés par-dessus, sous les plombs structurels.
    if (guide is RosaceGuide && bayGeometry == null) {
        if (jointFactor > fineLeadFactor)
            drawRosaceGroupFineLeads(targetDrawer, guide, cells, C2_JOINT_ROLES, cellLeadStyle.color, leadStyle.width * jointFactor)
        if (nervureFactor > fineLeadFactor)
            drawRosaceGroupFineLeads(targetDrawer, guide, cells, C2_NERVURE_ROLES, cellLeadStyle.color, leadStyle.width * nervureFactor)
    }

    // Rosace : plombs épais (dessins B et C), puis contour extérieur.
    if (guide is RosaceGuide && bayGeometry == null) {
        drawRosaceLeadLines(targetDrawer, guide, frameOffset, leadStyle.color, leadStyle.width, config.minFineLeadFactor ?: 0.0)
        // C2 noire (Onyx et nacre) : plombs ENTRE deux pièces du fond dans leur propre couleur.
        c2Variant?.fieldLeadColor?.let { fc ->
            drawC2FieldLeads(targetDrawer, guide, cells, frameOffset, ColorRGBa.fromHex(fc),
                leadStyle.width * fineLeadFactor, leadStyle.width)
        }
        drawRosaceRim(targetDrawer, guide, frameOffset, leadStyle.color, leadStyle.width)
        // Phase 22 : soudures aux croisements des plombs (RosaceDetails.kt).
        config.leadJoints?.let { j ->
            drawRosaceLeadJoints(targetDrawer, guide, cells, frameOffset, leadStyle.color,
                leadStyle.width, leadStyle.width * fineLeadFactor, j)
        }
    }

    // Baie : grand plomb du pourtour et barlotières, par-dessus tout.
    if (config.bay != null && bayGeometry != null) {
        drawBayIron(targetDrawer, config.bay, bayGeometry, leadStyle.width)
    }
}

/** Tout ce que les tirages aléatoires décident pour une œuvre. */
data class GeneratedVitrail(
    val guide: CompositionGuide,
    val cells: List<List<Vector2>>,
    val cellColors: List<ColorRGBa>,
    val materialVariation: List<Double>,
    /** Géométrie de la baie (null sans baie). */
    val bay: BayGeometry? = null
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

    // Baie : la composition est générée dans le rectangle de la fenêtre
    // (mêmes tirages que sans baie), puis la baie est assemblée autour.
    val bayGeometry = config.bay?.let { bayGeometry(it, canvasWidth, canvasHeight) }
    // Format d'impression : la composition est générée dans un cadre aux
    // proportions du format (mêmes tirages), puis placée au centre du
    // canevas (voir PrintFormat.kt).
    val printFrame = config.framedPrintFormat()?.frame(canvasWidth, canvasHeight)
    val contentWidth = bayGeometry?.generationWidth ?: printFrame?.get(2) ?: canvasWidth
    val contentHeight = bayGeometry?.generationHeight ?: printFrame?.get(3) ?: canvasHeight

    // Système 1 — Composition
    val guide = config.compositionSystem.generate(
        contentWidth,
        contentHeight,
        config.numberOfSites,
        random
    )

    // Système 2 — Segmentation
    val cells = config.segmentationSystem.segment(
        guide,
        contentWidth,
        contentHeight,
        random
    )

    // Système 3 — Palette
    // (Un GuidedPaletteSystem reçoit aussi la composition : répartition
    // radiale de la palette « blanc lumineux », voir Palette.kt. Mêmes
    // tirages que les autres systèmes : une famille et une nuance par
    // cellule.)
    val paletteSystem = config.paletteSystem
    val cellColors = (
            if (paletteSystem is GuidedPaletteSystem) paletteSystem.assignColors(guide, cells, random)
            else paletteSystem.assignColors(cells, random)
            ).toMutableList()

    // Post-traitements SPÉCIFIQUES aux compositions emblématiques (voir
    // Palette.kt) :
    // - croix rayonnante : bras forcés en blanc cassé, médaillon selon
    //   MedallionMode (applyRadiantCrossColors) ;
    // - croix dessinées, tréflée ou celtique : mêmes couleurs de bras et
    //   de médaillon (applyShapedCrossColors, ShapedCross.kt) ;
    // - ange rayonnant : poitrine sombre (teinte de la saison), tête et
    //   ailes en blanc cassé, nimbe doré, cou et robe en ivoire doré
    //   (applyRadiantAngelColors) ;
    // - motif raccordable : champ de couleur périodique
    //   (applyPeriodicColorField) ;
    // - famille végétale : couleur selon le rôle de chaque pièce
    //   (applyBotanicalColors, Botanical.kt) ;
    // - fond « champ de rayons » : teintes alternées d'un rayon à l'autre
    //   (applyRayFieldColors, RayField.kt) ;
    // - rosace : couleur selon le type et la position de chaque pièce
    //   (applyRosaceColors, Rosace.kt ; aucun tirage).
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
        is ShapedCrossGuide -> applyShapedCrossColors(
            guide = guide,
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
        is RayFieldGuide -> applyRayFieldColors(
            guide = guide,
            cellColors = cellColors,
            random = random
        )
        // Rosace : aucun tirage (couleurs fixées par le type et la
        // position de chaque pièce, pour une symétrie stricte).
        is RosaceGuide -> if (config.c2Noir != null) applyC2NoirColors(guide, cellColors, config.c2Noir)
        else applyRosaceColors(
            guide = guide,
            cellColors = cellColors,
            scheme = config.rosaceScheme,
            palette = config.rosacePalette
        )
        else -> {}
    }

    // Système 5 — Verre (variation de matière de chaque pièce)
    val materialVariation = config.glassSystem.assignMaterialVariation(cells.size, random)

    if (config.bay != null && bayGeometry != null) {
        val (bayCells, bayColors, bayMaterial) =
            assembleBay(config, config.bay, bayGeometry, cells, cellColors, materialVariation)
        return GeneratedVitrail(guide, bayCells, bayColors, bayMaterial, bayGeometry)
    }

    // Format d'impression : pièces décalées dans le cadre centré (après tous
    // les tirages et post-traitements, qui travaillent dans le cadre).
    if (printFrame != null && (printFrame[0] != 0.0 || printFrame[1] != 0.0)) {
        val dx = printFrame[0]
        val dy = printFrame[1]
        val placed = cells.map { cell -> cell.map { Vector2(it.x + dx, it.y + dy) } }
        return GeneratedVitrail(guide, placed, cellColors, materialVariation)
    }

    return GeneratedVitrail(guide, cells, cellColors, materialVariation)
}

/** Facteur de transmission d'une pièce (VitrailConfig.darkGlassTransmission) :
 *  tMax pour un verre de clarté OKLab ≤ 0,20, 1,0 pour un verre de clarté
 *  ≥ 0,55, linéaire entre les deux. */
fun darkGlassFactor(color: ColorRGBa, tMax: Double): Double {
    val l = OkLab.toLab(color)[0]
    val k = ((0.55 - l) / (0.55 - 0.20)).coerceIn(0.0, 1.0)
    return 1.0 + (tMax - 1.0) * k
}


/** Phase 18 ter : part de l'éclaircissement du côté mince conservée selon la
 *  clarté OKLab du verre : 1,0 (inchangé) jusqu'à 0,60, 0,0 (l'épaisseur ne
 *  fait qu'assombrir) à partir de 0,85, linéaire entre les deux. */
fun thicknessLift(color: ColorRGBa): Double {
    val l = OkLab.toLab(color)[0]
    return ((0.85 - l) / (0.85 - 0.60)).coerceIn(0.0, 1.0)
}


/** Valeur déterministe dans [-1, 1] pour la pièce d'indice i (aucun tirage
 *  aléatoire : l'ordre des tirages de renderVitrail() reste inchangé). */
fun pieceHash(i: Int): Double {
    var h = (i.toLong() * 2654435761L + 0x9E3779B9L) and 0xFFFFFFFFL
    h = (h xor (h ushr 15)) * 0x2C1B3C6DL and 0xFFFFFFFFL
    h = h xor (h ushr 12)
    return (h % 2001L) / 1000.0 - 1.0
}


/** Variation d'épaisseur d'une pièce (modèle optique, phase 18) : direction
 *  (coordonnées de la pièce, y vers le bas) et amplitude (part de lumière
 *  retirée du côté le plus épais, ajoutée du côté le plus mince), selon le
 *  rôle de la pièce, déterministe (pieceHash, aucun tirage aléatoire).
 *   - fond, bordure : direction libre, amplitude 0,10 à 0,20 ;
 *   - pétales, flammes, calice, pointes : le long de l'axe du pétale (±25°),
 *     0,07 à 0,14 ;
 *   - croix : le long du bras (±15°), 0,05 à 0,10 ;
 *   - hostie, couronne : 0,03. */
fun glassThickness(role: String, radial: Vector2, index: Int): Pair<Vector2, Double> {
    val h1 = pieceHash(index); val h2 = pieceHash(index + 7919); val h3 = pieceHash(index + 104729)
    val axis = if (radial.length > 1e-6) Math.atan2(radial.y, radial.x) else 0.0
    val base = role.substringBefore('@')
    val (angle, amp) = when {
        role == "out0" || role == "out1@fieldOuter" || role == "deepBig" -> Pair(Math.PI * h1, 0.15 + 0.05 * h2)
        base == "mid1" || base == "mid0" || role == "cen1@calyx" || role == "gold@petalTip" ->
            Pair(axis + Math.toRadians(25.0) * h1, 0.105 + 0.035 * h2)
        role == "cross" -> Pair(axis + Math.toRadians(15.0) * h1, 0.075 + 0.025 * h2)
        else -> Pair(Math.PI * h1, 0.03)
    }
    val sign = if (h3 < 0) -1.0 else 1.0
    return Pair(Vector2(Math.cos(angle) * sign, Math.sin(angle) * sign), amp)
}