import org.openrndr.math.Vector2
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// SYSTÈME 2 — SEGMENTATION
// "Comment l'œuvre est-elle découpée en morceaux de verre ?"
// --------------------------------------------------
//
// Prend une CompositionGuide (Système 1) et produit la liste des
// cellules (polygones) qui seront ensuite colorées (Système 3),
// bordées de plomb (Système 4) et éclairées (Système 5).

interface SegmentationSystem {
    fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>>
}

/**
 * Découpage actuel : grille jitterée (OrganicFreeform.kt).
 * Attend une CompositionGuide.Grid.
 */
class GridSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.Grid) {
            "GridSegmentationSystem nécessite une CompositionGuide.Grid"
        }

        return generateOrganicFreeform(
            width = width,
            height = height,
            targetRegions = guide.columns * guide.rows,
            random = random
        )
    }
}

/**
 * Découpage en vrai diagramme de Voronoï, avec relaxation de Lloyd
 * optionnelle. Attend une CompositionGuide.Sites (voir
 * ScatteredSitesCompositionSystem dans Composition.kt).
 *
 * Le code de génération (generateVoronoiCell, relaxPoints,
 * polygonCentroid...) vit déjà dans Voronoi.kt et n'est pas dupliqué
 * ici — ce système ne fait que l'orchestrer.
 */
class VoronoiSegmentationSystem(
    private val relaxationIterations: Int = 1
) : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.Sites) {
            "VoronoiSegmentationSystem nécessite une CompositionGuide.Sites"
        }

        val relaxedPoints = relaxPoints(
            points = guide.points,
            width = width,
            height = height,
            iterations = relaxationIterations,
            random = random
        )

        return relaxedPoints.indices.map { index ->
            generateVoronoiCell(
                siteIndex = index,
                sites = relaxedPoints,
                width = width,
                height = height
            )
        }
    }
}

/**
 * Découpage en rayons/anneaux depuis un centre (rosace). Attend une
 * CompositionGuide.Radial (voir RadialCompositionSystem dans
 * Composition.kt).
 *
 * Construit un maillage lattice[r][i] de sommets (r = indice d'anneau,
 * i = indice de rayon), légèrement irrégularisé par un bruit
 * déterministe (vertexRandomValue, réutilisé de Deformation.kt) pour
 * éviter l'aspect parfaitement mécanique. Deux précautions
 * importantes, trouvées empiriquement :
 *
 * 1. Les jitters DOIVENT être exprimés en proportion de l'écartement
 *    naturel (largeur de secteur angulaire, espacement radial d'un
 *    anneau) et non en valeur absolue. Un jitter d'angle fixe en
 *    radians déplace un sommet éloigné du centre bien plus qu'un
 *    sommet proche (l'arc parcouru croît avec le rayon), ce qui peut
 *    faire chevaucher deux secteurs voisins et produire des cellules
 *    qui se croisent sur elles-mêmes. Testé sans croisement jusqu'à
 *    des ratios de 0.40 avec 16 rayons × 5 anneaux.
 *
 * 2. Le sommet à l'angle 0 et celui à l'angle 2π sont mathématiquement
 *    le même point, mais NE DOIVENT PAS être recalculés indépendamment :
 *    une infime erreur de virgule flottante entre cos(0) et cos(2π),
 *    négligeable en coordonnées, peut faire basculer la quantification
 *    au millipixel de vertexRandomValue vers une valeur de bruit
 *    complètement différente — ouvrant une couture visible dans le
 *    cercle. Le dernier point de chaque anneau est donc une copie
 *    directe du premier, jamais un recalcul.
 *
 * L'anneau le plus extérieur (r = numberOfRings) n'est volontairement
 * pas jitterré : son rayon (= distance jusqu'au coin le plus éloigné
 * du centre) garantit que le maillage couvre tout le canevas une fois
 * clippé par clipToCanvas(). Le disque central (r = 0) est réduit à
 * un point unique : les cellules du premier anneau sont donc des
 * triangles, pas des quadrilatères.
 */
class RadialSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.Radial) {
            "RadialSegmentationSystem nécessite une CompositionGuide.Radial"
        }

        val cx = guide.center.x
        val cy = guide.center.y

        val maxRadius = computeMaxRadiusToCorner(guide.center, width, height)

        val numberOfRings = guide.numberOfRings
        val numberOfRays = guide.numberOfRays

        val ringSpacing = maxRadius / numberOfRings
        val sectorWidth = (2.0 * Math.PI) / numberOfRays

        val ringJitterAmount = ringSpacing * guide.ringJitterRatio
        val angleJitterAmount = sectorWidth * guide.angleJitterRatio

        val lattice: Array<Array<Vector2?>> =
            Array(numberOfRings + 1) { arrayOfNulls(numberOfRays + 1) }

        for (r in 0..numberOfRings) {

            val baseRadius = (r.toDouble() / numberOfRings) * maxRadius

            for (i in 0..numberOfRays) {

                if (i == numberOfRays) {
                    // Fermeture exacte du cercle — voir le point 2 ci-dessus.
                    lattice[r][i] = lattice[r][0]
                    continue
                }

                if (r == 0) {
                    lattice[r][i] = guide.center
                    continue
                }

                val baseAngle = (i.toDouble() / numberOfRays) * 2.0 * Math.PI

                val baseX = cx + kotlin.math.cos(baseAngle) * baseRadius
                val baseY = cy + kotlin.math.sin(baseAngle) * baseRadius

                if (r == numberOfRings) {
                    lattice[r][i] = Vector2(baseX, baseY)
                    continue
                }

                val radiusNoise = (vertexRandomValue(baseX, baseY, 0) - 0.5) * 2.0
                val angleNoise = (vertexRandomValue(baseX, baseY, 1) - 0.5) * 2.0

                val jitteredRadius = baseRadius + radiusNoise * ringJitterAmount
                val jitteredAngle = baseAngle + angleNoise * angleJitterAmount

                lattice[r][i] = Vector2(
                    cx + kotlin.math.cos(jitteredAngle) * jitteredRadius,
                    cy + kotlin.math.sin(jitteredAngle) * jitteredRadius
                )
            }
        }

        val cells = mutableListOf<List<Vector2>>()

        for (r in 0 until numberOfRings) {
            for (i in 0 until numberOfRays) {

                val cell = if (r == 0) {
                    listOf(
                        lattice[0][i]!!,
                        lattice[1][i]!!,
                        lattice[1][i + 1]!!
                    )
                } else {
                    listOf(
                        lattice[r][i]!!,
                        lattice[r + 1][i]!!,
                        lattice[r + 1][i + 1]!!,
                        lattice[r][i + 1]!!
                    )
                }

                val clipped = clipToCanvas(cell, width, height)

                if (clipped.size >= 3) {
                    cells.add(clipped)
                }
            }
        }

        return cells
    }
}

/**
 * Découpe un polygone quelconque aux limites rectangulaires du
 * canevas, en appliquant quatre fois clipPolygon() (déjà défini dans
 * Voronoi.kt comme outil générique de découpe par demi-plan — pas
 * spécifique au Voronoï, réutilisable tel quel ici).
 */
fun clipToCanvas(
    polygon: List<Vector2>,
    width: Double,
    height: Double
): List<Vector2> {

    var result = clipPolygon(polygon, Vector2(0.0, 0.0), -1.0, 0.0)   // x >= 0
    if (result.isEmpty()) return result

    result = clipPolygon(result, Vector2(width, 0.0), 1.0, 0.0)       // x <= width
    if (result.isEmpty()) return result

    result = clipPolygon(result, Vector2(0.0, 0.0), 0.0, -1.0)        // y >= 0
    if (result.isEmpty()) return result

    result = clipPolygon(result, Vector2(0.0, height), 0.0, 1.0)      // y <= height

    return result
}

/**
 * Distance du centre au coin le plus éloigné du canevas — le rayon
 * minimal garantissant qu'un motif radial centré couvre tout le
 * canevas une fois clippé. Partagé par RadialSegmentationSystem et
 * SunburstSegmentationSystem.
 */
fun computeMaxRadiusToCorner(
    center: Vector2,
    width: Double,
    height: Double
): Double {

    val dx = kotlin.math.max(center.x, width - center.x)
    val dy = kotlin.math.max(center.y, height - center.y)

    return sqrt(dx * dx + dy * dy)
}

/**
 * Découpage en sunburst géométrique : des rayons émanant d'un centre,
 * chacun subdivisé INDÉPENDAMMENT des autres (nombre de divisions et
 * position des rayons de coupure propres à chaque rayon). Attend une
 * CompositionGuide.Sunburst.
 *
 * C'est la correction directe à l'échec du style radial précédent
 * (RadialSegmentationSystem) : là où un maillage rayons×anneaux
 * partageait les mêmes rayons de coupure sur tout le pourtour (d'où
 * l'effet "toile d'araignée" mécanique), ici chaque secteur angulaire
 * choisit ses propres coupures — les frontières ne s'alignent jamais
 * en cercles concentriques.
 *
 * Le médaillon central est UNE SEULE cellule (le polygone du cercle
 * intérieur, pas un triangle par rayon) : coloré uniformément en
 * post-traitement par TemplateProgram.kt, il se lit comme un vrai
 * disque, sans traits de plomb internes qui trahiraient la structure
 * en rayons sous-jacente.
 *
 * Les largeurs angulaires des rayons sont elles-mêmes irrégulières
 * (angleIrregularity), pour éviter que même l'écartement des rayons
 * ne trahisse une grille mécanique.
 *
 * Maillage CONFORME aux frontières entre secteurs voisins : deux
 * secteurs adjacents ont des rayons de coupure indépendants (c'est
 * voulu), mais leur frontière angulaire commune est reconstruite avec
 * l'union triée des points de coupure des deux côtés, pour que les
 * segments produits aient exactement les mêmes extrémités de part et
 * d'autre. Sans ça, la frontière partagée est dessinée deux fois avec
 * des points d'arrêt différents, chaque version avec sa propre
 * courbure indépendante — visible à l'usage comme un trait sombre
 * anormalement épais ou dédoublé à certains endroits. Vérifié sur un
 * maillage de test : chaque arête interne partagée apparaît
 * exactement 2 fois après ce correctif (donc correctement dédupliquée
 * par edgeKey), jamais avec des extrémités décalées.
 */
class SunburstSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.Sunburst) {
            "SunburstSegmentationSystem nécessite une CompositionGuide.Sunburst"
        }

        val maxRadius = computeMaxRadiusToCorner(guide.center, width, height)
        val coreRadius = maxRadius * guide.coreRadiusRatio

        val angleBoundaries = generateRayAngleBoundaries(
            guide.numberOfRays,
            guide.angleIrregularity,
            random
        )

        val cells = mutableListOf<List<Vector2>>()

        // Médaillon central : UNE SEULE cellule (le polygone fermé
        // formé par tous les points du cercle intérieur), pas un
        // triangle par rayon. C'est la correction du défaut relevé à
        // l'usage : même coloriés à l'identique en post-traitement
        // (TemplateProgram.kt), des triangles séparés restent des
        // cellules séparées, donc le Système 4 (Plomb) dessine quand
        // même les traits entre elles — visible comme des "rayons"
        // internes qui trahissent la structure sous-jacente. En
        // fusionnant en une seule cellule, il n'y a plus d'arête
        // interne à dessiner à cet endroit : juste le contour extérieur
        // du disque, qui se lit comme un vrai médaillon uni.
        val corePolygon = (0 until guide.numberOfRays).map { i ->
            val angle = angleBoundaries[i]
            Vector2(
                guide.center.x + kotlin.math.cos(angle) * coreRadius,
                guide.center.y + kotlin.math.sin(angle) * coreRadius
            )
        }

        val clippedCore = clipToCanvas(corePolygon, width, height)
        if (clippedCore.size >= 3) {
            cells.add(clippedCore)
        }

        // Rayons de coupure de CHAQUE secteur, précalculés une seule
        // fois. Nécessaire pour pouvoir les réconcilier avec les
        // secteurs voisins ensuite (voir plus bas) — on ne peut pas
        // les générer à la volée dans la boucle principale comme
        // avant, puisqu'il faut connaître ceux du voisin AVANT de
        // construire les cellules d'un secteur donné.
        val rayRadii = (0 until guide.numberOfRays).map {
            generateRayRadii(
                coreRadius,
                maxRadius,
                guide.minDivisionsPerRay,
                guide.maxDivisionsPerRay,
                random
            )
        }

        for (i in 0 until guide.numberOfRays) {

            val a0 = angleBoundaries[i]
            val a1 = angleBoundaries[i + 1]

            val radii = rayRadii[i]

            // Défaut constaté à l'usage : deux secteurs voisins
            // choisissent leurs propres rayons de coupure de façon
            // INDÉPENDANTE (c'est voulu, voir plus haut — c'est ce qui
            // évite l'effet "toile d'araignée"). Mais leur frontière
            // angulaire commune est alors découpée à des points
            // DIFFÉRENTS de chaque côté : chaque secteur dessine sa
            // propre version de cette ligne, avec ses propres points
            // d'arrêt. Comme les segments résultants n'ont pas
            // exactement les mêmes extrémités, la déduplication par
            // clé d'arête (edgeKey) ne les reconnaît pas comme la
            // même arête — les deux versions sont dessinées
            // séparément, avec chacune sa propre courbure indépendante
            // (getEdgeCurve), et là où elles ne coïncident pas
            // exactement, ça se voit comme un trait anormalement épais
            // ou dédoublé.
            //
            // Correctif : le long de CHAQUE frontière partagée, les
            // deux secteurs voisins utilisent l'union triée de leurs
            // points de coupure respectifs. Chaque secteur ajoute donc
            // des sommets supplémentaires (colinéaires, donc sans
            // effet sur la forme réelle de la cellule) exactement là
            // où le voisin a lui aussi un point de coupure. Les deux
            // côtés produisent alors des segments aux extrémités
            // identiques, correctement dédupliqués à l'affichage.
            // Vérifié : sur un maillage de test, chaque arête interne
            // partagée apparaît alors exactement 2 fois (une fois par
            // secteur), jamais dédoublée avec des extrémités décalées.
            val leftNeighborRadii = rayRadii[(i - 1 + guide.numberOfRays) % guide.numberOfRays]
            val rightNeighborRadii = rayRadii[(i + 1) % guide.numberOfRays]

            val leftBoundary = (leftNeighborRadii + radii).distinct().sorted()
            val rightBoundary = (radii + rightNeighborRadii).distinct().sorted()

            for (j in 0 until radii.size - 1) {

                val rLow = radii[j]
                val rHigh = radii[j + 1]

                val leftPoints = leftBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> Vector2(guide.center.x + kotlin.math.cos(a0) * r, guide.center.y + kotlin.math.sin(a0) * r) }

                val rightPoints = rightBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> Vector2(guide.center.x + kotlin.math.cos(a1) * r, guide.center.y + kotlin.math.sin(a1) * r) }

                val polygon = leftPoints + rightPoints.reversed()

                val clipped = clipToCanvas(polygon, width, height)
                if (clipped.size >= 3) {
                    cells.add(clipped)
                }
            }
        }

        return cells
    }
}

/**
 * Limites angulaires (0 à 2π) de largeur irrégulière entre rayons.
 *
 * Défaut corrigé : la version initiale tirait des poids aléatoires
 * indépendants et les normalisait — rien n'empêchait deux limites de
 * finir très proches l'une de l'autre, créant un rayon quasi nul.
 * Cette version part d'un partage égal (2π / numberOfRays) et
 * n'applique qu'un jitter LOCAL et BORNÉ autour de chaque position de
 * base, garantissant mathématiquement qu'aucun rayon ne descend sous
 * (1 - irregularity) fois la largeur moyenne — même principe que
 * generateRayRadii ci-dessous (voir sa note pour l'historique complet
 * du défaut et sa mesure).
 */
fun generateRayAngleBoundaries(
    numberOfRays: Int,
    irregularity: Double,
    random: Random
): List<Double> {

    val equalShare = (2.0 * Math.PI) / numberOfRays

    // Bornée à 0.9 par sécurité : à 1.0, le jitter pourrait en théorie
    // ramener deux limites au même point (largeur nulle).
    val boundedIrregularity = irregularity.coerceIn(0.0, 0.9)
    val jitterRange = equalShare * boundedIrregularity / 2.0

    val boundaries = mutableListOf(0.0)

    for (i in 1 until numberOfRays) {
        val baseline = i * equalShare
        val jitter = random.nextDouble(-jitterRange, jitterRange)
        boundaries.add(baseline + jitter)
    }

    // Fermeture exacte du cercle (voir la note sur ce même sujet dans
    // RadialSegmentationSystem : ne jamais laisser une accumulation en
    // virgule flottante remplacer 2π directement).
    boundaries.add(2.0 * Math.PI)

    return boundaries
}

/**
 * Rayons de coupure croissants et irréguliers, propres à UN rayon.
 *
 * Défaut constaté à l'usage (signalé par Astrea, mesuré avant
 * correctif) : la version initiale tirait chaque coupure de façon
 * complètement libre (random.nextDouble(0.15, 1.0), triée) — rien
 * n'empêchait deux coupures consécutives d'être tirées très proches
 * l'une de l'autre, créant un segment radial minuscule dont le
 * remplissage coloré devient invisible sous l'épaisseur du plomb (vu
 * à l'écran comme un trait anormalement épais entre deux autres
 * cellules, alors que c'est en fait une cellule à part entière, juste
 * trop fine pour qu'on voie autre chose que ses deux bords). Mesuré
 * sur 2000 tirages avec l'ancienne méthode : segments jusqu'à 0.11px,
 * 5.8% des segments sous 15px (moins de 3× la largeur du plomb).
 *
 * Corrigé avec le même principe que generateRayAngleBoundaries
 * ci-dessus : partage égal de l'espace total en `divisions` parts,
 * puis jitter local borné autour de chaque coupure interne — garantit
 * un segment minimal de (minSegmentRatio × part égale). Revalidé sur
 * 2000 tirages après correctif : plus aucun segment sous 30px.
 */
fun generateRayRadii(
    coreRadius: Double,
    maxRadius: Double,
    minDivisions: Int,
    maxDivisions: Int,
    random: Random,
    minSegmentRatio: Double = 0.45
): List<Double> {

    val divisions = random.nextInt(minDivisions, maxDivisions + 1)

    val totalSpan = maxRadius - coreRadius
    val equalShare = totalSpan / divisions

    val jitterRange = equalShare * (1.0 - minSegmentRatio.coerceIn(0.0, 0.9)) / 2.0

    val radii = mutableListOf(coreRadius)

    for (i in 1 until divisions) {
        val baseline = coreRadius + i * equalShare
        val jitter = random.nextDouble(-jitterRange, jitterRange)
        radii.add(baseline + jitter)
    }

    radii.add(maxRadius)

    return radii
}

// --------------------------------------------------
// NOTE — Déformation des sommets (Deformation.kt)
// --------------------------------------------------
//
// deformCellVertices() existe déjà mais n'est branché nulle part
// dans le pipeline actuel (ni avant, ni après ce refactor : c'est
// un comportement préexistant, pas un oubli de ce squelette).
//
// Sa place naturelle serait ici, comme une étape de post-traitement
// optionnelle appliquée à la sortie d'un SegmentationSystem, par ex. :
//
//   fun SegmentationSystem.withDeformation(
//       vertexCache: MutableMap<String, Vector2>,
//       amount: Double
//   ): SegmentationSystem = object : SegmentationSystem {
//       override fun segment(guide: CompositionGuide, width: Double, height: Double, random: Random) =
//           this@withDeformation.segment(guide, width, height, random).map {
//               deformCellVertices(it, width, height, vertexCache, amount)
//           }
//   }
//
// À activer explicitement quand on voudra ce style plus irrégulier.

/** Distance angulaire entre deux angles en degrés, gérant le passage 0°/360°. */
fun angularDistanceDegrees(a: Double, b: Double): Double {
    var diff = (a - b) % 360.0
    if (diff > 180.0) diff -= 360.0
    if (diff < -180.0) diff += 360.0
    return kotlin.math.abs(diff)
}

/** Le bras dont la fenêtre angulaire contient angleDegrees, ou null (rayon de lumière). */
fun classifyAngle(
    angleDegrees: Double,
    armWindows: List<CompositionGuide.ArmWindow>
): CompositionGuide.ArmWindow? {

    for (arm in armWindows) {
        if (angularDistanceDegrees(angleDegrees, arm.centerDegrees) <= arm.halfWidthDegrees) {
            return arm
        }
    }

    return null
}

/**
 * Découpage en croix rayonnante : même moteur que SunburstSegmentationSystem
 * (rayons indépendants, maillage conforme aux frontières partagées — voir
 * sa documentation pour le détail de ce correctif), mais la longueur de
 * chaque rayon dépend de sa classification : un rayon dans une fenêtre
 * d'ArmWindow s'étend jusqu'à arm.lengthRatio × maxRadius (les "bras" de
 * la croix, plus longs et réguliers) ; les autres s'étendent jusqu'à une
 * longueur aléatoire entre lightLengthMinRatio et lightLengthMaxRatio ×
 * maxRadius (les "rayons de lumière", plus courts et irréguliers).
 *
 * Attend une CompositionGuide.RadiantCross.
 */
class RadiantCrossSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.RadiantCross) {
            "RadiantCrossSegmentationSystem nécessite une CompositionGuide.RadiantCross"
        }

        val coreRadius = guide.maxRadius * guide.coreRadiusRatio

        val angleBoundaries = generateRayAngleBoundaries(
            guide.numberOfRays,
            guide.angleIrregularity,
            random
        )

        val cells = mutableListOf<List<Vector2>>()

        // Médaillon central : une seule cellule, même principe que
        // SunburstSegmentationSystem (voir sa documentation).
        val corePolygon = (0 until guide.numberOfRays).map { i ->
            val angle = angleBoundaries[i]
            Vector2(
                guide.center.x + kotlin.math.cos(angle) * coreRadius,
                guide.center.y + kotlin.math.sin(angle) * coreRadius
            )
        }

        val clippedCore = clipToCanvas(corePolygon, width, height)
        if (clippedCore.size >= 3) {
            cells.add(clippedCore)
        }

        // Longueur de CHAQUE rayon, déterminée AVANT la génération des
        // subdivisions (nécessaire pour le maillage conforme ci-dessous,
        // qui doit connaître les points de coupure des deux voisins
        // avant de construire les cellules d'un rayon donné).
        val rayOuterRadii = (0 until guide.numberOfRays).map { i ->
            val a0 = angleBoundaries[i]
            val a1 = angleBoundaries[i + 1]
            val centerAngleDeg = Math.toDegrees((a0 + a1) / 2.0).let {
                if (it < 0.0) it + 360.0 else it
            } % 360.0

            val arm = classifyAngle(centerAngleDeg, guide.armWindows)

            if (arm != null) {
                coreRadius + (guide.maxRadius - coreRadius) * arm.lengthRatio
            } else {
                val lengthRatio = random.nextDouble(guide.lightLengthMinRatio, guide.lightLengthMaxRatio)
                coreRadius + (guide.maxRadius - coreRadius) * lengthRatio
            }
        }

        val rayRadii = (0 until guide.numberOfRays).map { i ->
            generateRayRadii(
                coreRadius,
                rayOuterRadii[i],
                guide.minDivisionsPerRay,
                guide.maxDivisionsPerRay,
                random
            )
        }

        for (i in 0 until guide.numberOfRays) {

            val a0 = angleBoundaries[i]
            val a1 = angleBoundaries[i + 1]

            val radii = rayRadii[i]

            // Maillage conforme aux frontières partagées — voir la note
            // détaillée dans SunburstSegmentationSystem pour le
            // mécanisme complet du défaut évité ici (traits doublés aux
            // frontières entre secteurs voisins). Fonctionne aussi bien
            // quand les deux voisins ont des longueurs de rayon très
            // différentes (un bras long à côté d'un rayon de lumière
            // court) : au-delà de la longueur du plus court des deux,
            // il n'y a plus de cellule voisine à faire correspondre, le
            // filtre par intervalle [rLow, rHigh] exclut naturellement
            // les points hors de portée.
            val leftNeighborRadii = rayRadii[(i - 1 + guide.numberOfRays) % guide.numberOfRays]
            val rightNeighborRadii = rayRadii[(i + 1) % guide.numberOfRays]

            val leftBoundary = (leftNeighborRadii + radii).distinct().sorted()
            val rightBoundary = (radii + rightNeighborRadii).distinct().sorted()

            for (j in 0 until radii.size - 1) {

                val rLow = radii[j]
                val rHigh = radii[j + 1]

                val leftPoints = leftBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> Vector2(guide.center.x + kotlin.math.cos(a0) * r, guide.center.y + kotlin.math.sin(a0) * r) }

                val rightPoints = rightBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> Vector2(guide.center.x + kotlin.math.cos(a1) * r, guide.center.y + kotlin.math.sin(a1) * r) }

                val polygon = leftPoints + rightPoints.reversed()

                val clipped = clipToCanvas(polygon, width, height)
                if (clipped.size >= 3) {
                    cells.add(clipped)
                }
            }
        }

        return cells
    }
}