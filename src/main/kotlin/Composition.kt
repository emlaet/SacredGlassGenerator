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