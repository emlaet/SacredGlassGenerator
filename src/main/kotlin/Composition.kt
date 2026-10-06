import org.openrndr.math.Vector2
import kotlin.random.Random

// --------------------------------------------------
// SYSTÈME 1 — COMPOSITION
// "Qu'est-ce que l'artiste dessine ?"
// --------------------------------------------------
//
// Une composition ne dessine rien elle-même : elle produit une
// "guide" que le Système 2 (Segmentation) viendra découper en
// morceaux de verre. Séparer les deux permet de brancher des
// styles très différents (grille, nuage de sites, radial,
// botanique, sacré...) sans toucher au reste du pipeline.

sealed class CompositionGuide {

    /** Grille régulière avec un jitter organique (style actuel). */
    data class Grid(
        val columns: Int,
        val rows: Int,
        val jitterRatio: Double
    ) : CompositionGuide()

    /**
     * Nuage de sites, pour un vrai diagramme de Voronoï.
     * guideCurves est optionnel : quand la composition est pilotée par
     * des courbes directrices (voir CurveGuidedCompositionSystem), les
     * courbes sont conservées ici pour un usage futur — par exemple un
     * Système 4 (Plomb) qui voudrait un trait plus épais le long des
     * lignes maîtresses. La Segmentation actuelle (Voronoï) ignore ce
     * champ : la contrainte de forme est déjà "cuite" dans les points
     * via la technique de clôture (voir Voronoi.kt).
     */
    data class Sites(
        val points: List<Vector2>,
        val guideCurves: List<List<Vector2>> = emptyList()
    ) : CompositionGuide()

    /**
     * Structure radiale : un centre, des rayons et des anneaux
     * concentriques (esprit rosace de cathédrale). Les jitters sont
     * exprimés en PROPORTION de l'écartement naturel (largeur de
     * secteur, espacement d'anneau) et non en pixels absolus — voir
     * la note dans RadialSegmentationSystem (Segmentation.kt) sur
     * pourquoi c'est important pour éviter des cellules qui se
     * croisent.
     */
    data class Radial(
        val center: Vector2,
        val numberOfRays: Int,
        val numberOfRings: Int,
        val ringJitterRatio: Double,
        val angleJitterRatio: Double
    ) : CompositionGuide()

    /**
     * Sunburst géométrique : un centre, des rayons de largeur angulaire
     * irrégulière (angleIrregularity), chacun subdivisé indépendamment
     * (minDivisionsPerRay à maxDivisionsPerRay coupures radiales par
     * rayon — voir SunburstSegmentationSystem, Segmentation.kt, pour
     * pourquoi cette indépendance est ce qui évite l'effet "toile
     * d'araignée" du style Radial précédent).
     */
    data class Sunburst(
        val center: Vector2,
        val numberOfRays: Int,
        val coreRadiusRatio: Double,
        val angleIrregularity: Double,
        val minDivisionsPerRay: Int,
        val maxDivisionsPerRay: Int
    ) : CompositionGuide()

    /**
     * Un bras de la croix rayonnante : une fenêtre angulaire (centrée
     * sur centerDegrees, de largeur ±halfWidthDegrees) qui s'étend plus
     * loin que les rayons de lumière environnants (lengthRatio, en
     * proportion de maxRadius). Convention d'angle : 0=droite, 90=bas,
     * 180=gauche, 270=haut (coordonnées écran, y vers le bas).
     */
    data class ArmWindow(
        val name: String,
        val centerDegrees: Double,
        val halfWidthDegrees: Double,
        val lengthRatio: Double
    )

    /**
     * Croix rayonnante : même moteur que Sunburst (rayons indépendants
     * depuis un centre), mais certains rayons sont désignés comme les
     * bras d'une croix (fenêtres angulaires fixes, plus longs, colorés
     * distinctement) tandis que les autres jouent le rôle de rayons de
     * lumière plus courts et colorés autour d'elle — voir
     * RadiantCrossSegmentationSystem (Segmentation.kt) pour le détail.
     *
     * Volontairement PAS de découpe géométrique de la croix dans le
     * fond (ce qui demanderait une soustraction de polygones qu'on n'a
     * pas dans la boîte à outils) : la croix EST simplement un groupe
     * de rayons plus longs et d'une couleur distincte, ce qui donne le
     * même résultat visuel sans la complexité géométrique.
     */
    data class RadiantCross(
        val center: Vector2,
        val maxRadius: Double,
        val coreRadiusRatio: Double,
        val numberOfRays: Int,
        val angleIrregularity: Double,
        val minDivisionsPerRay: Int,
        val maxDivisionsPerRay: Int,
        val armWindows: List<ArmWindow>,
        val lightLengthMinRatio: Double,
        val lightLengthMaxRatio: Double
    ) : CompositionGuide()

    /**
     * Une aile de l'ange rayonnant : une fenêtre angulaire (même
     * convention d'angle que ArmWindow : 0=droite, 90=bas, 180=gauche,
     * 270=haut) découpée en featherCount plumes régulières.
     */
    data class WingWindow(
        val name: String,
        val centerDegrees: Double,
        val halfWidthDegrees: Double,
        val featherCount: Int
    )

    /**
     * Ange rayonnant : même moteur que RadiantCross (rayons depuis un
     * centre), mais le centre devient la POITRINE de l'ange (un petit
     * médaillon), et les rayons se répartissent ainsi :
     * - vers le bas, la ROBE (bodyWindow, découpée en bodyRayCount
     *   rayons) ;
     * - vers le haut, le COU (headWindow, jusqu'à
     *   headWindow.lengthRatio) puis la TÊTE : un disque posé sur le
     *   cou, entouré d'un NIMBE en deux pièces (gauche et droite) ;
     * - de part et d'autre, deux AILES levées (wingWindows), découpées
     *   en plumes de longueurs graduées ;
     * - dans les intervalles restants, des rayons de lumière ordinaires.
     * Les ailes partent ainsi des épaules, et non de la tête. Historique :
     * une première version faisait partir les ailes de la tête (elles se
     * lisaient comme des oreilles) ; une deuxième terminait le rayon du
     * haut par un simple arrondi (il se lisait comme une mitre, et le
     * médaillon clair de poitrine comme la tête). D'où le disque, le
     * nimbe, et un médaillon de poitrine sombre.
     *
     * Voir RadiantAngelSegmentationSystem (Segmentation.kt) pour le
     * détail de la forme des ailes et de la tête. Les longueurs
     * (…LengthRatio, …Ratio) sont exprimées en proportion de l'espace
     * entre le médaillon de poitrine et maxRadius, comme pour
     * RadiantCross.
     */
    data class RadiantAngel(
        val center: Vector2,
        val maxRadius: Double,
        val coreRadiusRatio: Double,

        // Rayons de lumière (tout ce qui n'est ni robe ni aile)
        val lightRayWidthDegrees: Double,
        val angleIrregularity: Double,
        val lightLengthMinRatio: Double,
        val lightLengthMaxRatio: Double,
        val lightMinDivisionsPerRay: Int,
        val lightMaxDivisionsPerRay: Int,

        // Robe
        val bodyWindow: ArmWindow,
        val bodyRayCount: Int,
        val bodyMinDivisionsPerRay: Int,
        val bodyMaxDivisionsPerRay: Int,

        // Cou et tête (headWindow.lengthRatio = longueur du cou)
        val headWindow: ArmWindow,
        val headFillRatio: Double,
        val haloRatio: Double,
        val headArcPoints: Int,

        // Ailes
        val wingWindows: List<WingWindow>,
        val wingLowerLengthRatio: Double,
        val wingPeakLengthRatio: Double,
        val wingPeakPosition: Double,
        val wingUpperEdgeRatio: Double,
        val wingCovertRatio: Double,
        val featherScallop: Double,
        val featherJitter: Double,
        val featherTipRatio: Double,
        val featherMinDivisions: Int,
        val featherMaxDivisions: Int,

        // Variante « robe liturgique » (voir RadiantAngelCompositionSystem)
        val showLightRays: Boolean,
        val robeUsesPalette: Boolean
    ) : CompositionGuide()

    /**
     * Vitrail « lignes maîtresses + éclats » en MOTIF RACCORDABLE : la
     * tuile tileWidth × tileHeight se répète sans raccord visible dans
     * les deux directions (pour l'impression intégrale sur textile).
     *
     * primaryLines : lignes maîtresses (plomb épais), déjà recopiées sur
     * le domaine étendu [−W, 2W] × [−H, 2H] pour que la géométrie soit
     * périodique. Les autres champs règlent le découpage en éclats (voir
     * PeriodicShardsSegmentationSystem, Segmentation.kt).
     *
     * ⚠ Les cellules produites par la segmentation sont les pièces
     * « canoniques » de la tuile : renderVitrail (Renderer.kt) les
     * dessine aux 9 positions (tuile et ses 8 voisines) avec la MÊME
     * couleur et la même matière, pour que les pièces coupées par le bord
     * de la tuile se raccordent exactement.
     */
    data class PeriodicShards(
        val tileWidth: Double,
        val tileHeight: Double,
        val primaryLines: List<List<Vector2>>,
        val pieceAreaRatio: Double,
        val minPieceRatio: Double,
        val minCompactness: Double,
        val minWidthRatio: Double,
        val maxNarrowResidue: Double,
        val arcGestureProb: Double,
        val fanGestureProb: Double
    ) : CompositionGuide()

    // À venir : Botanical(troncs, branches, ...), Symmetric(axes, ...), etc.
}

interface CompositionSystem {
    fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide
}

/**
 * Composition actuelle : grille jitterée.
 * Reprend exactement la logique de dimensionnement qui existait
 * dans TemplateProgram.kt / main().
 */
class GridCompositionSystem(
    private val jitterRatio: Double = 0.22
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val columns = when {
            targetRegions <= 20 -> 4
            targetRegions <= 30 -> 5
            else -> 6
        }

        val rows = columns

        return CompositionGuide.Grid(columns, rows, jitterRatio)
    }
}

/**
 * Composition alternative : sites dispersés avec variation de taille
 * purement radiale (grands au centre, petits en bord d'image).
 * Consomme generatePoints() (Voronoi.kt), qui gère sizeVariation via
 * minimumDistanceAt(). Destinée à être utilisée avec
 * VoronoiSegmentationSystem (voir Segmentation.kt).
 *
 * Gardée pour référence / comparaison : voir OrganicSitesCompositionSystem
 * ci-dessous pour une variation de taille non-radiale, plus proche du
 * rendu recherché (cf. image de référence du vitrail).
 */
class ScatteredSitesCompositionSystem(
    private val sizeVariation: Double = 1.0
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val points = generatePoints(
            numberOfSites = targetRegions,
            width = width.toInt(),
            height = height.toInt(),
            random = random,
            sizeVariation = sizeVariation
        )

        return CompositionGuide.Sites(points)
    }
}

/**
 * Composition organique : sites dispersés dont la taille désirée est
 * pilotée par un champ de bruit spatial (generateOrganicPoints,
 * Voronoi.kt) plutôt que par la seule distance au centre. Produit un
 * mélange de grandes et petites cellules réparties sans motif
 * radial ni alignement de grille — le style retenu pour se
 * rapprocher de l'image de référence du vitrail.
 *
 * - baseDistance : taille "moyenne" de référence avant application
 *   du bruit (plus petit = plus de cellules pour un targetRegions donné).
 * - sizeVariation : amplitude du contraste entre petites et grandes
 *   cellules (0 = quasi uniforme, >1.5 = contrastes marqués).
 */
class OrganicSitesCompositionSystem(
    private val baseDistance: Double = 35.0,
    private val sizeVariation: Double = 1.3
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val points = generateOrganicPoints(
            numberOfSites = targetRegions,
            width = width,
            height = height,
            random = random,
            baseDistance = baseDistance,
            sizeVariation = sizeVariation
        )

        return CompositionGuide.Sites(points)
    }
}

/**
 * Composition guidée par des courbes directrices : quelques lignes
 * organiques traversent toute l'image d'un bord à l'autre, et une
 * "clôture" de sites (voir Voronoi.kt : generateGuideCurves,
 * curveFencePoints) force le futur diagramme de Voronoï à faire
 * passer une frontière de cellule exactement le long de chacune.
 * L'espace restant est rempli par le même échantillonnage organique
 * que OrganicSitesCompositionSystem (generateOrganicPointsWithSeeds).
 *
 * C'est la composition qui produit les grandes lignes de plomb
 * traversant toute l'œuvre, comme sur l'image de référence — tout en
 * réutilisant le même VoronoiSegmentationSystem que les autres styles
 * de composition à base de sites.
 *
 * - numberOfCurves : nombre de lignes directrices.
 * - fenceSpacingRatio / fenceOffsetRatio : exprimés en multiples de
 *   baseDistance. Réglages validés empiriquement (voir commentaire
 *   dans Voronoi.kt) : spacing ≈ 1.4–1.6× et offset ≈ 0.12–0.15×
 *   donnent une frontière nette sans créer sa propre bande de
 *   fines échardes. Ne pas s'en écarter sans retester visuellement.
 */
class CurveGuidedCompositionSystem(
    private val numberOfCurves: Int = 3,
    private val baseDistance: Double = 35.0,
    private val sizeVariation: Double = 1.3,
    private val fenceSpacingRatio: Double = 1.6,
    private val fenceOffsetRatio: Double = 0.12
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val curves = generateGuideCurves(
            width = width,
            height = height,
            numberOfCurves = numberOfCurves,
            random = random
        )

        val fenceSpacing = baseDistance * fenceSpacingRatio
        val fenceOffset = baseDistance * fenceOffsetRatio

        val fencePoints = curves.flatMap { curve ->
            curveFencePoints(curve, fenceSpacing, fenceOffset)
        }

        val points = generateOrganicPointsWithSeeds(
            numberOfSites = targetRegions,
            width = width,
            height = height,
            random = random,
            baseDistance = baseDistance,
            sizeVariation = sizeVariation,
            seedPoints = fencePoints,
            seedMinDistance = fenceOffset
        )

        return CompositionGuide.Sites(points, curves)
    }
}

/**
 * Composition radiale : place un centre (par défaut au milieu de
 * l'image) et délègue à RadialSegmentationSystem le soin de bâtir le
 * maillage rayons/anneaux — cette composition ne fait que fixer les
 * paramètres structurels (combien de rayons, combien d'anneaux, quel
 * niveau d'irrégularité).
 *
 * targetRegions (paramètre de l'interface CompositionSystem) n'est
 * PAS utilisé ici : le nombre de cellules est entièrement déterminé
 * par numberOfRays × numberOfRings (+ les triangles du disque
 * central). Les valeurs par défaut donnent ~75 pièces, du même ordre
 * que les autres styles de composition.
 */
class RadialCompositionSystem(
    private val numberOfRays: Int = 16,
    private val numberOfRings: Int = 5,
    private val ringJitterRatio: Double = 0.30,
    private val angleJitterRatio: Double = 0.30
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val center = Vector2(width / 2.0, height / 2.0)

        return CompositionGuide.Radial(
            center = center,
            numberOfRays = numberOfRays,
            numberOfRings = numberOfRings,
            ringJitterRatio = ringJitterRatio,
            angleJitterRatio = angleJitterRatio
        )
    }
}

/**
 * Composition sunburst : fixe les paramètres structurels (nombre de
 * rayons, taille du médaillon central, irrégularité) et délègue la
 * construction du maillage à SunburstSegmentationSystem. targetRegions
 * n'est pas utilisé — comme pour RadialCompositionSystem, le nombre
 * de cellules découle de numberOfRays × (divisions par rayon), pas
 * d'un compte cible.
 *
 * Valeurs par défaut validées visuellement sur 3 seeds en Python :
 * ~90-100 cellules, médaillon central lisible, aucune cellule
 * dégénérée.
 */
class SunburstCompositionSystem(
    private val numberOfRays: Int = 22,
    private val coreRadiusRatio: Double = 0.12,
    private val angleIrregularity: Double = 0.35,
    private val minDivisionsPerRay: Int = 2,
    private val maxDivisionsPerRay: Int = 4
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val center = Vector2(width / 2.0, height / 2.0)

        return CompositionGuide.Sunburst(
            center = center,
            numberOfRays = numberOfRays,
            coreRadiusRatio = coreRadiusRatio,
            angleIrregularity = angleIrregularity,
            minDivisionsPerRay = minDivisionsPerRay,
            maxDivisionsPerRay = maxDivisionsPerRay
        )
    }
}

/**
 * Composition croix rayonnante : place le centre (le point de
 * croisement) légèrement au-dessus du centre du canevas — comme une
 * vraie croix latine, dont le bras du bas est plus long que celui du
 * haut — et fixe les quatre fenêtres angulaires des bras par défaut.
 *
 * maxRadius est volontairement plus petit que la distance aux coins
 * du canevas (contrairement à Sunburst, qui vise le plein cadre) :
 * l'emblème doit tenir avec une marge de fond visible autour, pas
 * déborder jusqu'aux bords.
 */
class RadiantCrossCompositionSystem(
    private val coreRadiusRatio: Double = 0.09,
    private val numberOfRays: Int = 30,
    private val angleIrregularity: Double = 0.30,
    private val minDivisionsPerRay: Int = 2,
    private val maxDivisionsPerRay: Int = 4,
    private val lightLengthMinRatio: Double = 0.25,
    private val lightLengthMaxRatio: Double = 0.50,
    private val armWindows: List<CompositionGuide.ArmWindow> = listOf(
        CompositionGuide.ArmWindow("haut", 270.0, 17.0, 0.62),
        CompositionGuide.ArmWindow("bas", 90.0, 17.0, 1.00),
        CompositionGuide.ArmWindow("gauche", 180.0, 17.0, 0.78),
        CompositionGuide.ArmWindow("droite", 0.0, 17.0, 0.78)
    )
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val center = Vector2(width / 2.0, height * 0.42)
        val maxRadius = minOf(width, height) * 0.46

        return CompositionGuide.RadiantCross(
            center = center,
            maxRadius = maxRadius,
            coreRadiusRatio = coreRadiusRatio,
            numberOfRays = numberOfRays,
            angleIrregularity = angleIrregularity,
            minDivisionsPerRay = minDivisionsPerRay,
            maxDivisionsPerRay = maxDivisionsPerRay,
            armWindows = armWindows,
            lightLengthMinRatio = lightLengthMinRatio,
            lightLengthMaxRatio = lightLengthMaxRatio
        )
    }
}

/**
 * Composition ange rayonnant (voir CompositionGuide.RadiantAngel).
 *
 * Toutes les valeurs par défaut ont été réglées sur un prototype
 * Python (même géométrie, couleurs à plat) avant portage. Elles donnent
 * un ange aux ailes levées partant des épaules, tête ronde auréolée
 * d'un nimbe posée sur un cou, robe évasée vers le bas.
 *
 * - chestYRatio : hauteur de la poitrine (centre des rayons) ; la tête
 *   est au-dessus, la robe en dessous.
 * - coreRadiusRatio : taille du médaillon de poitrine. Trop petit, tous
 *   les rayons convergent en un amas de plomb noir au centre.
 * - headHalfWidthDegrees : ouverture du secteur réservé au cou, à la
 *   tête et au nimbe.
 * - neckLengthRatio : longueur du cou ; la tête grandit avec lui (elle
 *   est posée au bout du cou et remplit le secteur à cette hauteur).
 * - headFillRatio : part de la largeur du secteur occupée par la tête
 *   (le reste, sur les côtés, revient au nimbe).
 * - haloRatio : rayon du nimbe en multiple du rayon de la tête. Doit
 *   dépasser 1 / headFillRatio, sinon le nimbe n'atteint pas les bords
 *   du secteur (une erreur explicite est levée).
 * - wingCenterDegrees : direction de l'aile GAUCHE (198 = vers la
 *   gauche, levée de 18° au-dessus de l'horizontale) ; l'aile droite
 *   est son miroir exact (180 − angle), pour une figure symétrique.
 * - wingHalfWidthDegrees / featherCount : ouverture de chaque aile et
 *   nombre de plumes. La robe, la tête et les ailes ne doivent pas se
 *   chevaucher (une erreur explicite est levée sinon).
 * - wingLowerLengthRatio → wingPeakLengthRatio : longueur des plumes,
 *   des plus courtes (bord bas de l'aile, près de la robe) aux plus
 *   longues (vers wingPeakPosition, 0 = bord bas, 1 = bord haut) ;
 *   au-delà, elles raccourcissent jusqu'à wingUpperEdgeRatio × pic.
 *   wingPeakLengthRatio peut dépasser 1.0 : les ailes s'étendent alors
 *   plus loin que la robe (le canevas est plus large que haut).
 * - lightLengthMinRatio / lightLengthMaxRatio : longueur des rayons de
 *   lumière colorés. Réglage retenu : des rayons presque aussi longs
 *   que les ailes, qui se distinguent d'elles par leur couleur plutôt
 *   que par leur taille — sinon la couleur de la saison disparaît
 *   presque entièrement derrière les ailes blanches.
 * - wingCovertRatio : rangée de « couvertures » (petites plumes) à la
 *   base de l'aile — une première coupe commune à toutes les plumes.
 * - featherScallop : une plume sur deux raccourcie de ce pourcentage,
 *   pour un bord d'aile festonné plutôt qu'une courbe lisse.
 * - featherTipRatio : longueur de la pointe ajoutée au bout de chaque
 *   plume (0.0 = bout plat, comme les rayons).
 * - showLightRays : false = aucun rayon de lumière, l'ange se détache
 *   seul sur le fond (ailes, tête, robe).
 * - robeUsesPalette : true = la robe prend les couleurs de la palette
 *   liturgique active (tirées par le PaletteSystem, comme l'étaient les
 *   rayons) au lieu de l'ivoire doré. Le cou reste ivoire doré.
 *   Les deux options vont ensemble pour la variante « robe
 *   liturgique » (voir TemplateProgram.kt), mais restent indépendantes.
 */
class RadiantAngelCompositionSystem(
    private val chestYRatio: Double = 0.50,
    private val maxRadiusRatio: Double = 0.46,
    private val coreRadiusRatio: Double = 0.10,
    private val lightRayWidthDegrees: Double = 12.0,
    private val angleIrregularity: Double = 0.30,
    private val lightLengthMinRatio: Double = 0.45,
    private val lightLengthMaxRatio: Double = 0.80,
    private val lightMinDivisionsPerRay: Int = 2,
    private val lightMaxDivisionsPerRay: Int = 4,
    private val bodyHalfWidthDegrees: Double = 20.0,
    private val bodyLengthRatio: Double = 0.95,
    private val bodyRayCount: Int = 4,
    private val bodyMinDivisionsPerRay: Int = 2,
    private val bodyMaxDivisionsPerRay: Int = 3,
    private val headHalfWidthDegrees: Double = 26.0,
    private val neckLengthRatio: Double = 0.20,
    private val headFillRatio: Double = 0.78,
    private val haloRatio: Double = 1.40,
    private val headArcPoints: Int = 16,
    private val wingCenterDegrees: Double = 198.0,
    private val wingHalfWidthDegrees: Double = 34.0,
    private val featherCount: Int = 9,
    private val wingLowerLengthRatio: Double = 0.45,
    private val wingPeakLengthRatio: Double = 0.90,
    private val wingPeakPosition: Double = 0.62,
    private val wingUpperEdgeRatio: Double = 0.70,
    private val wingCovertRatio: Double = 0.20,
    private val featherScallop: Double = 0.06,
    private val featherJitter: Double = 0.15,
    private val featherTipRatio: Double = 0.07,
    private val featherMinDivisions: Int = 1,
    private val featherMaxDivisions: Int = 2,
    private val showLightRays: Boolean = true,
    private val robeUsesPalette: Boolean = false
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val center = Vector2(width / 2.0, height * chestYRatio)
        val maxRadius = minOf(width, height) * maxRadiusRatio

        // Miroir par rapport à l'axe vertical : θ → 180 − θ (mod 360).
        val rightWingCenter = ((180.0 - wingCenterDegrees) % 360.0 + 360.0) % 360.0

        return CompositionGuide.RadiantAngel(
            center = center,
            maxRadius = maxRadius,
            coreRadiusRatio = coreRadiusRatio,
            lightRayWidthDegrees = lightRayWidthDegrees,
            angleIrregularity = angleIrregularity,
            lightLengthMinRatio = lightLengthMinRatio,
            lightLengthMaxRatio = lightLengthMaxRatio,
            lightMinDivisionsPerRay = lightMinDivisionsPerRay,
            lightMaxDivisionsPerRay = lightMaxDivisionsPerRay,
            bodyWindow = CompositionGuide.ArmWindow("robe", 90.0, bodyHalfWidthDegrees, bodyLengthRatio),
            bodyRayCount = bodyRayCount,
            bodyMinDivisionsPerRay = bodyMinDivisionsPerRay,
            bodyMaxDivisionsPerRay = bodyMaxDivisionsPerRay,
            headWindow = CompositionGuide.ArmWindow("tête", 270.0, headHalfWidthDegrees, neckLengthRatio),
            headFillRatio = headFillRatio,
            haloRatio = haloRatio,
            headArcPoints = headArcPoints,
            wingWindows = listOf(
                CompositionGuide.WingWindow("aile gauche", wingCenterDegrees, wingHalfWidthDegrees, featherCount),
                CompositionGuide.WingWindow("aile droite", rightWingCenter, wingHalfWidthDegrees, featherCount)
            ),
            wingLowerLengthRatio = wingLowerLengthRatio,
            wingPeakLengthRatio = wingPeakLengthRatio,
            wingPeakPosition = wingPeakPosition,
            wingUpperEdgeRatio = wingUpperEdgeRatio,
            wingCovertRatio = wingCovertRatio,
            featherScallop = featherScallop,
            featherJitter = featherJitter,
            featherTipRatio = featherTipRatio,
            featherMinDivisions = featherMinDivisions,
            featherMaxDivisions = featherMaxDivisions,
            showLightRays = showLightRays,
            robeUsesPalette = robeUsesPalette
        )
    }
}

/**
 * Composition « lignes maîtresses + éclats » en MOTIF RACCORDABLE
 * (voir CompositionGuide.PeriodicShards) : produit les lignes maîtresses
 * périodiques, déjà recopiées sur le domaine étendu [−W, 2W] × [−H, 2H]
 * (W × H = taille de la tuile = taille du canevas).
 *
 * Réglages par défaut = variante « A » retenue sur le prototype Python
 * (lignes quasi droites, jugées plus « vitrail » que les grandes
 * courbes) :
 * - horizontalWaves / verticalWaves : ondulations très douces qui
 *   traversent toute la tuile (une seule ondulation par largeur de
 *   tuile : waveHarmonics = [1]) ; amplitude entre waveAmplitudeMin et
 *   waveAmplitudeMax, en part de la hauteur (ou largeur) de la tuile ;
 * - diagonalLines : droites en diagonale qui montent (ou descendent)
 *   d'une hauteur de tuile quand elles avancent d'une largeur — seule
 *   pente qui se raccorde exactement d'une tuile à l'autre ;
 * - circles : grands cercles (0 par défaut ; 1 avec un rayon de 0,45 à
 *   0,5 × W donnait la variante « B », à grands arcs).
 * - minLineSpacing : écart minimal entre deux lignes maîtresses
 *   PARALLÈLES (deux ondulations horizontales, deux verticales, ou deux
 *   diagonales de même pente), en part de la tuile, mesuré de façon
 *   périodique (au raccord aussi). Sans lui, deux lignes tirées presque
 *   au même endroit enfermaient une bande de verre très étroite sur
 *   toute la longueur du motif. Une ligne trop proche est simplement
 *   retirée : si le premier tirage convient, rien ne change par rapport
 *   à la version sans écart minimal (mêmes tirages aléatoires).
 * - pièces réalisables par un verrier (règles appliquées aux zones
 *   découpées par les lignes maîtresses comme aux éclats) :
 *   minPieceRatio = aire minimale d'une pièce (part de la tuile) ;
 *   minWidthRatio = largeur minimale, en part du petit côté de la tuile
 *   (diamètre d'un disque qui doit pouvoir atteindre toute la pièce :
 *   pas de lanière ni de goulet plus étroits) ; maxNarrowResidue et
 *   maxZoneNarrowResidue = tolérance aux pointes aiguës (2 r² ≈ 35° pour
 *   les éclats, 3,5 r² ≈ 24° pour les zones). Voir narrowResidueCells
 *   (Arrangement.kt).
 * Les réglages des éclats (pieceAreaRatio, etc.) sont transmis tels
 * quels à PeriodicShardsSegmentationSystem.
 */
class PeriodicShardsCompositionSystem(
    private val horizontalWaves: Int = 2,
    private val verticalWaves: Int = 2,
    private val diagonalLines: Int = 2,
    private val circles: Int = 0,
    private val waveAmplitudeMin: Double = 0.015,
    private val waveAmplitudeMax: Double = 0.04,
    private val waveHarmonics: List<Int> = listOf(1),
    private val circleRadiusMin: Double = 0.45,
    private val circleRadiusMax: Double = 0.50,
    private val waveSamplesPerTile: Int = 60,
    private val pieceAreaRatio: Double = 1.0 / 45.0,
    private val minPieceRatio: Double = 0.006,
    private val minCompactness: Double = 0.18,
    private val minWidthRatio: Double = 0.07,
    private val maxNarrowResidue: Double = 2.0,
    private val maxZoneNarrowResidue: Double = 3.5,
    private val arcGestureProb: Double = 0.05,
    private val fanGestureProb: Double = 0.10,
    private val minLineSpacing: Double = 0.18
) : CompositionSystem {

    override fun generate(
        width: Double,
        height: Double,
        targetRegions: Int,
        random: Random
    ): CompositionGuide {

        val lines = mutableListOf<List<Vector2>>()

        // Écart périodique entre deux positions, en part de la période
        // (0 = confondues, 0.5 = le plus loin possible).
        fun periodicGap(a: Double, b: Double): Double {
            val d = ((a - b) % 1.0 + 1.0) % 1.0
            return kotlin.math.min(d, 1.0 - d)
        }
        val maxRedraws = 100
        val maxZoneRedraws = 30

        // Les zones découpées par les lignes maîtresses doivent elles
        // aussi pouvoir être coupées par un verrier : pas de zone plus
        // petite qu'une pièce (minPieceRatio), pas de pointe plus effilée
        // qu'environ 24° (maxZoneNarrowResidue = 3,5 r² ; voir
        // narrowResidueCells, Arrangement.kt). Le seuil est plus
        // tolérant que pour les éclats (2 r², environ 35°), car une
        // diagonale croise une ondulation horizontale sous un angle de
        // 25 à 45° environ : c'est le dessin voulu. Ce contrôle écarte
        // surtout les lignes qui passent presque par un croisement
        // existant (minuscule triangle de verre).
        // Vérifié à chaque nouvelle ligne ; une ligne qui crée une zone
        // impossible est retirée au hasard (au plus maxZoneRedraws fois,
        // puis abandonnée).
        val narrowRadius = 0.5 * minWidthRatio * kotlin.math.min(width, height)
        fun zonesAreCraftable(candidateLines: List<List<Vector2>>): Boolean =
            periodicCanonicalZones(candidateLines, width, height).all { zone ->
                polygonAreaAbs(zone) >= minPieceRatio * width * height &&
                        newNarrowResidue(zone, emptySet(), narrowRadius) <= maxZoneNarrowResidue
            }

        // Positions (en part de la tuile) des lignes déjà placées, par
        // famille de lignes parallèles.
        val horizontalProfiles = mutableListOf<DoubleArray>()
        val verticalProfiles = mutableListOf<DoubleArray>()
        val risingIntercepts = mutableListOf<Double>()
        val fallingIntercepts = mutableListOf<Double>()

        fun wave(horizontal: Boolean): List<Vector2> {
            val along = if (horizontal) width else height
            val across = if (horizontal) height else width
            val placed = if (horizontal) horizontalProfiles else verticalProfiles

            fun offsetAt(components: List<Triple<Int, Double, Double>>, s: Double) =
                components.sumOf { (k, a, phase) -> a * kotlin.math.sin(2.0 * Math.PI * k * s / along + phase) }

            var base: Double
            var components: List<Triple<Int, Double, Double>>
            var profile: DoubleArray
            var redraws = 0
            while (true) {
                base = random.nextDouble(0.0, across)
                components = (0 until 2).map {
                    val k = waveHarmonics[random.nextInt(waveHarmonics.size)]
                    val amplitude = random.nextDouble(waveAmplitudeMin, waveAmplitudeMax) * across / (if (k == 1) 1.0 else 2.0)
                    Triple(k, amplitude, random.nextDouble(0.0, 2.0 * Math.PI))
                }
                // Position de l'ondulation (en part de la tuile) le long
                // d'une période : deux ondulations parallèles doivent rester
                // à minLineSpacing l'une de l'autre sur TOUTE leur longueur,
                // pas seulement en moyenne (elles peuvent se rapprocher là où
                // l'une monte et l'autre descend).
                val c = components
                val b = base
                profile = DoubleArray(waveSamplesPerTile) { i ->
                    (b + offsetAt(c, along * i / waveSamplesPerTile)) / across
                }
                val p = profile
                val farEnough = placed.all { other ->
                    p.indices.all { i -> periodicGap(other[i], p[i]) >= minLineSpacing }
                }
                if (farEnough || redraws >= maxRedraws) break
                redraws++
            }
            placed.add(profile)
            val samples = 3 * waveSamplesPerTile
            return (0..samples).map { i ->
                val s = -along + 3.0 * along * i / samples
                val offset = offsetAt(components, s)
                if (horizontal) Vector2(s, base + offset) else Vector2(base + offset, s)
            }
        }

        for (horizontal in List(horizontalWaves) { true } + List(verticalWaves) { false }) {
            var zoneRedraws = 0
            while (true) {
                val w = wave(horizontal)
                val copies = (-1..1).map { k ->
                    if (horizontal) w.map { Vector2(it.x, it.y + k * height) }
                    else w.map { Vector2(it.x + k * width, it.y) }
                }
                if (zonesAreCraftable(lines + copies)) {
                    lines.addAll(copies)
                    break
                }
                // ligne retirée : on oublie aussi sa position
                val placed = if (horizontal) horizontalProfiles else verticalProfiles
                placed.removeAt(placed.lastIndex)
                zoneRedraws++
                // Aucune position ne convient : la ligne est abandonnée
                // (mieux vaut une ligne de moins qu'une pièce impossible).
                if (zoneRedraws >= maxZoneRedraws) break
            }
        }
        repeat(diagonalLines) {
            var zoneRedraws = 0
            while (true) {
                var slopeSign: Double
                var x0: Double
                var y0: Double
                var intercept: Double
                var redraws = 0
                while (true) {
                    slopeSign = if (random.nextBoolean()) 1.0 else -1.0
                    x0 = random.nextDouble(0.0, width)
                    y0 = random.nextDouble(0.0, height)
                    // Ordonnée à l'origine, en part de la hauteur : deux
                    // diagonales de même pente sont parallèles, et leur écart
                    // est celui de leurs ordonnées à l'origine (modulo H).
                    intercept = (y0 - slopeSign * height * x0 / width) / height
                    val placed = if (slopeSign > 0.0) risingIntercepts else fallingIntercepts
                    if (redraws >= maxRedraws || placed.none { periodicGap(it, intercept) < minLineSpacing }) {
                        placed.add(intercept)
                        break
                    }
                    redraws++
                }
                val copies = (-3..3).map { k ->
                    listOf(-2.0 * width, 3.0 * width).map { x ->
                        Vector2(x, y0 + k * height + slopeSign * height * (x - x0) / width)
                    }
                }
                if (zonesAreCraftable(lines + copies)) {
                    lines.addAll(copies)
                    break
                }
                val placed = if (slopeSign > 0.0) risingIntercepts else fallingIntercepts
                placed.removeAt(placed.lastIndex)
                zoneRedraws++
                if (zoneRedraws >= maxZoneRedraws) break
            }
        }
        repeat(circles) {
            val cx = random.nextDouble(0.0, width)
            val cy = random.nextDouble(0.0, height)
            val r = random.nextDouble(circleRadiusMin, circleRadiusMax) * width
            for (dx in -1..1) for (dy in -1..1) {
                lines.add(circlePoints(Vector2(cx + dx * width, cy + dy * height), r, 180))
            }
        }

        return CompositionGuide.PeriodicShards(
            tileWidth = width,
            tileHeight = height,
            primaryLines = lines,
            pieceAreaRatio = pieceAreaRatio,
            minPieceRatio = minPieceRatio,
            minCompactness = minCompactness,
            minWidthRatio = minWidthRatio,
            maxNarrowResidue = maxNarrowResidue,
            arcGestureProb = arcGestureProb,
            fanGestureProb = fanGestureProb
        )
    }
}

/** Cercle approché par n segments (polyligne fermée : dernier point = premier). */
fun circlePoints(center: Vector2, radius: Double, n: Int): List<Vector2> =
    (0..n).map { i ->
        val a = 2.0 * Math.PI * (i % n) / n
        Vector2(center.x + kotlin.math.cos(a) * radius, center.y + kotlin.math.sin(a) * radius)
    }