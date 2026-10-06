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
 * post-traitement (applyRadiantCrossColors, Palette.kt), il se lit
 * comme un vrai disque, sans traits de plomb internes qui
 * trahiraient la structure en rayons sous-jacente.
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
        // (Palette.kt), des triangles séparés restent des
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

// --------------------------------------------------
// ANGE RAYONNANT
// --------------------------------------------------

/** Rôle d'un rayon de l'ange rayonnant. */
enum class AngelRayRole { LIGHT, BODY, HEAD, WING }

/**
 * Un rayon de l'ange rayonnant : son rôle, et pour une plume sa
 * position dans l'aile (0 = bord bas, près de la robe ; 1 = bord
 * haut) et son rang depuis le bord bas (pour le festonnage une
 * plume sur deux).
 */
data class AngelRay(
    val role: AngelRayRole,
    val wingPosition: Double = 0.0,
    val indexInWindow: Int = 0
)

/**
 * Découpage en ange rayonnant. Attend une CompositionGuide.RadiantAngel.
 *
 * Même principe que RadiantCrossSegmentationSystem (médaillon central
 * d'une seule cellule — ici la poitrine —, rayons indépendants depuis
 * le centre, maillage CONFORME aux frontières partagées), avec quatre
 * différences :
 *
 * 1. Les limites angulaires des rayons ne sont plus tirées
 *    uniformément sur tout le cercle : la robe, la tête et chaque aile sont
 *    d'abord posées comme des fenêtres EXACTES, découpées en un
 *    nombre fixe de rayons (bodyRayCount, un seul pour la tête,
 *    featherCount) ; les rayons
 *    de lumière remplissent ensuite les intervalles entre fenêtres.
 *    Aucun rayon n'est donc à cheval sur une aile et un rayon de
 *    lumière, et le nombre de plumes est maîtrisé.
 *
 * 2. La longueur d'une plume dépend de sa position dans l'aile
 *    (courte près de la robe, maximale vers wingPeakPosition, un peu
 *    plus courte au bord haut), avec un festonnage une plume sur deux.
 *
 * 3. Chaque plume commence par une rangée commune de « couvertures »
 *    (même rayon de coupe pour toutes les plumes d'une aile), et sa
 *    dernière pièce reçoit une pointe (featherTipRatio). La pointe
 *    n'est jamais partagée avec une autre cellule : elle ne casse pas
 *    le maillage conforme.
 *
 * 4. Le rayon de la tête produit quatre pièces (voir
 *    angelHeadGeometry) : le cou (du médaillon à
 *    headWindow.lengthRatio), la tête (un disque posé au milieu du
 *    bout du cou) et le nimbe, coupé en deux moitiés par un plomb
 *    vertical au-dessus de la tête. Le disque et le nimbe restent à
 *    l'intérieur du secteur de la tête : seuls les côtés du nimbe et
 *    du cou, le long des bords du secteur, touchent les rayons voisins
 *    (maillage conforme, comme partout ailleurs).
 */
class RadiantAngelSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.RadiantAngel) {
            "RadiantAngelSegmentationSystem nécessite une CompositionGuide.RadiantAngel"
        }

        val coreRadius = guide.maxRadius * guide.coreRadiusRatio
        val span = guide.maxRadius - coreRadius

        val (boundariesDegrees, rays) = generateAngelRays(guide, random)
        val angleBoundaries = boundariesDegrees.map { Math.toRadians(it) }
        val numberOfRays = rays.size

        fun pointAt(angle: Double, radius: Double) = Vector2(
            guide.center.x + kotlin.math.cos(angle) * radius,
            guide.center.y + kotlin.math.sin(angle) * radius
        )

        val cells = mutableListOf<List<Vector2>>()

        // Médaillon de poitrine : une seule cellule, comme le médaillon
        // de la croix.
        val corePolygon = (0 until numberOfRays).map { i ->
            pointAt(angleBoundaries[i], coreRadius)
        }
        val clippedCore = clipToCanvas(corePolygon, width, height)
        if (clippedCore.size >= 3) {
            cells.add(clippedCore)
        }

        // Rayons de coupe de chaque rayon, selon son rôle.
        val rayRadii = rays.map { ray ->
            when (ray.role) {
                // Variante sans rayons : le secteur est gardé (pour que
                // robe, tête et ailes restent exactement à leur place)
                // mais ne produit aucune pièce — le fond reste visible.
                AngelRayRole.LIGHT -> if (!guide.showLightRays) listOf(coreRadius) else {
                    val lengthRatio = random.nextDouble(guide.lightLengthMinRatio, guide.lightLengthMaxRatio)
                    generateRayRadii(
                        coreRadius,
                        coreRadius + span * lengthRatio,
                        guide.lightMinDivisionsPerRay,
                        guide.lightMaxDivisionsPerRay,
                        random
                    )
                }
                AngelRayRole.BODY -> generateRayRadii(
                    coreRadius,
                    coreRadius + span * guide.bodyWindow.lengthRatio,
                    guide.bodyMinDivisionsPerRay,
                    guide.bodyMaxDivisionsPerRay,
                    random
                )
                AngelRayRole.HEAD -> {
                    val head = angelHeadGeometry(guide)
                    listOf(coreRadius, head.neckRadius, head.haloSideRadius)
                }
                AngelRayRole.WING -> {
                    val covertRadius = coreRadius + span * guide.wingCovertRatio
                    val outerRadius = coreRadius + span * featherLengthRatio(guide, ray)
                    listOf(coreRadius) + generateRayRadii(
                        covertRadius,
                        outerRadius,
                        guide.featherMinDivisions,
                        guide.featherMaxDivisions,
                        random
                    )
                }
            }
        }

        for (i in 0 until numberOfRays) {

            val a0 = angleBoundaries[i]
            val a1 = angleBoundaries[i + 1]

            val radii = rayRadii[i]

            // Maillage conforme — voir SunburstSegmentationSystem.
            val leftNeighborRadii = rayRadii[(i - 1 + numberOfRays) % numberOfRays]
            val rightNeighborRadii = rayRadii[(i + 1) % numberOfRays]

            val leftBoundary = (leftNeighborRadii + radii).distinct().sorted()
            val rightBoundary = (radii + rightNeighborRadii).distinct().sorted()

            if (rays[i].role == AngelRayRole.HEAD) {
                val head = angelHeadGeometry(guide)
                val pieces = angelHeadCells(guide, head, a0, a1, leftBoundary, rightBoundary, coreRadius, ::pointAt)
                for (piece in pieces) {
                    val clipped = clipToCanvas(piece, width, height)
                    if (clipped.size >= 3) {
                        cells.add(clipped)
                    }
                }
                continue
            }

            for (j in 0 until radii.size - 1) {

                val rLow = radii[j]
                val rHigh = radii[j + 1]

                val leftPoints = leftBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> pointAt(a0, r) }

                val rightPoints = rightBoundary
                    .filter { it >= rLow && it <= rHigh }
                    .map { r -> pointAt(a1, r) }

                val isOuterPiece = j == radii.size - 2

                val polygon = when {
                    rays[i].role == AngelRayRole.WING && isOuterPiece && guide.featherTipRatio > 0.0 -> {
                        val tip = pointAt((a0 + a1) / 2.0, rHigh + span * guide.featherTipRatio)
                        leftPoints + tip + rightPoints.reversed()
                    }
                    else -> leftPoints + rightPoints.reversed()
                }

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
 * Géométrie du cou, de la tête et du nimbe de l'ange rayonnant, dans
 * le secteur headWindow (centre des rayons = poitrine) :
 * - neckRadius : bout du cou (distance à la poitrine) ;
 * - headCenter / headRadius : disque de la tête, posé exactement au
 *   milieu du bout du cou (point bas du disque = milieu de la corde du
 *   cou) et occupant headFillRatio de la largeur du secteur à cette
 *   hauteur ;
 * - haloRadius : cercle du nimbe, concentrique à la tête ;
 * - haloSideRadius : distance à la poitrine où ce cercle coupe les
 *   bords du secteur (fin des côtés droits du nimbe).
 * Calcul purement géométrique (aucun tirage aléatoire) : les
 * segmentations et applyRadiantAngelColors (Palette.kt) peuvent
 * l'appeler autant de fois que nécessaire.
 */
data class AngelHeadGeometry(
    val axisAngle: Double,
    val neckRadius: Double,
    val headCenter: Vector2,
    val headRadius: Double,
    val haloRadius: Double,
    val haloSideRadius: Double
)

fun angelHeadGeometry(guide: CompositionGuide.RadiantAngel): AngelHeadGeometry {

    val coreRadius = guide.maxRadius * guide.coreRadiusRatio
    val span = guide.maxRadius - coreRadius

    val axisAngle = Math.toRadians(guide.headWindow.centerDegrees)
    val halfWidth = Math.toRadians(guide.headWindow.halfWidthDegrees)
    val fill = guide.headFillRatio.coerceIn(0.1, 0.95)

    val neckRadius = coreRadius + span * guide.headWindow.lengthRatio

    // Point bas du disque sur la corde du cou (à neckRadius·cos(demi-
    // ouverture) de la poitrine), disque occupant « fill » de la
    // largeur du secteur au niveau de son centre.
    val headDistance = neckRadius * kotlin.math.cos(halfWidth) / (1.0 - fill * kotlin.math.sin(halfWidth))
    val headRadius = fill * headDistance * kotlin.math.sin(halfWidth)
    val haloRadius = headRadius * guide.haloRatio

    val headCenter = Vector2(
        guide.center.x + kotlin.math.cos(axisAngle) * headDistance,
        guide.center.y + kotlin.math.sin(axisAngle) * headDistance
    )

    // Intersection du cercle du nimbe avec un bord du secteur :
    // |poitrine + r·v − centreTête|² = haloRadius², plus grande racine.
    val edge = axisAngle - halfWidth
    val vx = kotlin.math.cos(edge)
    val vy = kotlin.math.sin(edge)
    val wx = guide.center.x - headCenter.x
    val wy = guide.center.y - headCenter.y
    val vw = vx * wx + vy * wy
    val discriminant = vw * vw - (wx * wx + wy * wy) + haloRadius * haloRadius

    require(discriminant > 0.0) {
        "Nimbe trop petit pour atteindre les bords du secteur de la tête : augmenter haloRatio (> 1 / headFillRatio)"
    }

    val haloSideRadius = -vw + kotlin.math.sqrt(discriminant)

    require(haloSideRadius > neckRadius) {
        "Nimbe incohérent avec la longueur du cou (haloSideRadius <= neckRadius)"
    }

    return AngelHeadGeometry(axisAngle, neckRadius, headCenter, headRadius, haloRadius, haloSideRadius)
}

/**
 * Points d'un arc de cercle de fromAngle à toAngle (radians), sans les
 * deux extrémités.
 */
fun arcPoints(
    center: Vector2,
    radius: Double,
    fromAngle: Double,
    toAngle: Double,
    steps: Int
): List<Vector2> {
    val n = kotlin.math.max(2, steps)
    return (1 until n).map { k ->
        val angle = fromAngle + (toAngle - fromAngle) * k / n
        Vector2(
            center.x + kotlin.math.cos(angle) * radius,
            center.y + kotlin.math.sin(angle) * radius
        )
    }
}

/**
 * Les quatre pièces du rayon de la tête : cou, tête, nimbe gauche,
 * nimbe droit (dans cet ordre). Les arcs de la tête et du nimbe sont
 * calculés une seule fois et partagés tels quels entre pièces voisines,
 * pour que les plombs communs coïncident exactement.
 *
 * leftBoundary / rightBoundary : rayons de coupe le long des deux bords
 * du secteur, voisins compris (maillage conforme).
 */
fun angelHeadCells(
    guide: CompositionGuide.RadiantAngel,
    head: AngelHeadGeometry,
    a0: Double,
    a1: Double,
    leftBoundary: List<Double>,
    rightBoundary: List<Double>,
    coreRadius: Double,
    pointAt: (Double, Double) -> Vector2
): List<List<Vector2>> {

    val alpha = head.axisAngle
    val steps = guide.headArcPoints

    fun onCircle(radius: Double, angle: Double) = Vector2(
        head.headCenter.x + kotlin.math.cos(angle) * radius,
        head.headCenter.y + kotlin.math.sin(angle) * radius
    )

    // Points clés : bas et haut de la tête, haut du nimbe, coins.
    val headBottom = onCircle(head.headRadius, alpha + Math.PI)
    val headTop = onCircle(head.headRadius, alpha)
    val haloTop = onCircle(head.haloRadius, alpha)

    val neckLeft = pointAt(a0, head.neckRadius)
    val neckRight = pointAt(a1, head.neckRadius)
    val haloLeft = pointAt(a0, head.haloSideRadius)
    val haloRight = pointAt(a1, head.haloSideRadius)

    // Contour de la tête : moitié côté a0 (bas → haut), moitié côté a1
    // (haut → bas). Côté a0 = angles alpha + π → alpha + 2π.
    val headSideA0 = arcPoints(head.headCenter, head.headRadius, alpha + Math.PI, alpha + 2.0 * Math.PI, steps)
    val headSideA1 = arcPoints(head.headCenter, head.headRadius, alpha, alpha + Math.PI, steps)

    // Nimbe : du haut vers le coin côté a0, et du coin côté a1 vers le haut.
    fun angleAround(p: Vector2) = kotlin.math.atan2(p.y - head.headCenter.y, p.x - head.headCenter.x)

    var leftAngle = angleAround(haloLeft)
    while (leftAngle >= alpha) leftAngle -= 2.0 * Math.PI
    while (leftAngle < alpha - Math.PI) leftAngle += 2.0 * Math.PI

    var rightAngle = angleAround(haloRight)
    while (rightAngle <= alpha) rightAngle += 2.0 * Math.PI
    while (rightAngle > alpha + Math.PI) rightAngle -= 2.0 * Math.PI

    val haloArcA0 = arcPoints(head.headCenter, head.haloRadius, alpha, leftAngle, steps)
    val haloArcA1 = arcPoints(head.headCenter, head.haloRadius, rightAngle, alpha, steps)

    // Points de coupe des voisins le long des côtés du nimbe.
    val sideA0 = leftBoundary.filter { it > head.neckRadius && it < head.haloSideRadius }
        .sortedDescending().map { pointAt(a0, it) }
    val sideA1 = rightBoundary.filter { it > head.neckRadius && it < head.haloSideRadius }
        .sorted().map { pointAt(a1, it) }

    val neck = leftBoundary.filter { it >= coreRadius && it <= head.neckRadius }.map { pointAt(a0, it) } +
            headBottom +
            rightBoundary.filter { it >= coreRadius && it <= head.neckRadius }.map { pointAt(a1, it) }.reversed()

    val headDisc = listOf(headBottom) + headSideA0 + headTop + headSideA1

    val haloA0 = listOf(neckLeft, headBottom) + headSideA0 + listOf(headTop, haloTop) +
            haloArcA0 + haloLeft + sideA0

    val haloA1 = listOf(neckRight) + sideA1 + haloRight + haloArcA1 + listOf(haloTop, headTop) +
            headSideA1 + headBottom

    return listOf(neck, headDisc, haloA0, haloA1)
}

/**
 * Longueur d'une plume (en proportion de l'espace tête → maxRadius)
 * selon sa position dans l'aile : montée en quart de sinus du bord bas
 * (wingLowerLengthRatio) jusqu'au pic (wingPeakLengthRatio, atteint à
 * wingPeakPosition), puis légère descente parabolique jusqu'au bord
 * haut (wingUpperEdgeRatio × pic). Une plume sur deux est raccourcie
 * de featherScallop (bord festonné).
 */
fun featherLengthRatio(
    guide: CompositionGuide.RadiantAngel,
    ray: AngelRay
): Double {

    val t = ray.wingPosition.coerceIn(0.0, 1.0)
    val peakPosition = guide.wingPeakPosition.coerceIn(0.05, 0.95)
    val lowerShare = guide.wingLowerLengthRatio / guide.wingPeakLengthRatio

    val shape = if (t <= peakPosition) {
        lowerShare + (1.0 - lowerShare) * kotlin.math.sin(Math.PI / 2.0 * t / peakPosition)
    } else {
        val u = (t - peakPosition) / (1.0 - peakPosition)
        1.0 - (1.0 - guide.wingUpperEdgeRatio) * u * u
    }

    var length = guide.wingPeakLengthRatio * shape

    if (ray.indexInWindow % 2 == 1) {
        length *= (1.0 - guide.featherScallop)
    }

    return length
}

/**
 * Limites angulaires (en DEGRÉS, croissantes, sur un tour complet à
 * partir du bord de la robe) et rôle de chaque rayon de l'ange.
 * Renvoie numberOfRays + 1 limites (la dernière = la première + 360)
 * et numberOfRays rayons.
 */
fun generateAngelRays(
    guide: CompositionGuide.RadiantAngel,
    random: Random
): Pair<List<Double>, List<AngelRay>> {

    data class Window(
        val start: Double,
        val end: Double,
        val role: AngelRayRole,
        val rayCount: Int,
        val lowerEdge: Double
    )

    val body = guide.bodyWindow
    val origin = body.centerDegrees - body.halfWidthDegrees

    // Ramène un angle dans [origin, origin + 360).
    fun unwrap(angle: Double): Double =
        ((angle - origin) % 360.0 + 360.0) % 360.0 + origin

    val head = guide.headWindow
    val headStart = unwrap(head.centerDegrees - head.halfWidthDegrees)

    val windows = mutableListOf(
        Window(origin, origin + 2.0 * body.halfWidthDegrees, AngelRayRole.BODY, guide.bodyRayCount, origin),
        Window(headStart, headStart + 2.0 * head.halfWidthDegrees, AngelRayRole.HEAD, 1, headStart)
    )

    for (wing in guide.wingWindows) {
        val start = unwrap(wing.centerDegrees - wing.halfWidthDegrees)
        val end = start + 2.0 * wing.halfWidthDegrees
        // Bord bas = celui des deux bords le plus proche de la verticale
        // descendante (90°), c'est-à-dire du côté de la robe.
        val lowerEdge = if (angularDistanceDegrees(start, 90.0) <= angularDistanceDegrees(end, 90.0)) start else end
        windows.add(Window(start, end, AngelRayRole.WING, wing.featherCount, lowerEdge))
    }

    windows.sortBy { it.start }

    val boundaries = mutableListOf(origin)
    val rays = mutableListOf<AngelRay>()
    var cursor = origin

    fun addLightRays(from: Double, to: Double) {
        val gap = to - from
        if (gap <= 1e-9) return
        val count = kotlin.math.max(1, kotlin.math.round(gap / guide.lightRayWidthDegrees).toInt())
        val share = gap / count
        val jitterRange = share * guide.angleIrregularity.coerceIn(0.0, 0.9) / 2.0
        for (k in 1 until count) {
            val jitter = if (jitterRange > 0.0) random.nextDouble(-jitterRange, jitterRange) else 0.0
            boundaries.add(from + k * share + jitter)
        }
        boundaries.add(to)
        repeat(count) { rays.add(AngelRay(AngelRayRole.LIGHT)) }
    }

    for (window in windows) {

        require(window.start >= cursor - 1e-9) {
            "Les fenêtres de la robe, de la tête et des ailes se chevauchent (${window.role} à ${window.start}°)"
        }

        addLightRays(cursor, window.start)

        val share = (window.end - window.start) / window.rayCount
        val jitterRange = share * guide.featherJitter.coerceIn(0.0, 0.9) / 2.0

        for (k in 1 until window.rayCount) {
            val jitter = if (jitterRange > 0.0) random.nextDouble(-jitterRange, jitterRange) else 0.0
            boundaries.add(window.start + k * share + jitter)
        }
        boundaries.add(window.end)

        for (k in 0 until window.rayCount) {
            val middle = window.start + (k + 0.5) * share
            val position = kotlin.math.abs(middle - window.lowerEdge) / (window.end - window.start)
            // Rang compté depuis le bord BAS de la fenêtre (et non depuis
            // son début angulaire) : les deux ailes, parcourues en sens
            // inverse, ont ainsi exactement le même festonnage en miroir.
            val rankFromLowerEdge = if (window.lowerEdge == window.start) k else window.rayCount - 1 - k
            rays.add(AngelRay(window.role, position, rankFromLowerEdge))
        }

        cursor = window.end
    }

    addLightRays(cursor, origin + 360.0)

    return boundaries to rays
}

// --------------------------------------------------
// MOTIF RACCORDABLE « LIGNES MAÎTRESSES + ÉCLATS »
// --------------------------------------------------

/**
 * Zones « canoniques » découpées par les lignes maîtresses d'un motif
 * raccordable : arrangement des lignes sur le domaine étendu
 * [−W, 2W] × [−H, 2H] (polygonizeSegments, Arrangement.kt), dont on ne
 * garde que les zones ayant un point intérieur dans la tuile
 * [0, W) × [0, H). Chaque zone du motif infini est la copie translatée
 * d'exactement une zone canonique. Utilisée par
 * PeriodicShardsSegmentationSystem et, pour vérifier chaque nouvelle
 * ligne maîtresse, par PeriodicShardsCompositionSystem.
 */
fun periodicCanonicalZones(
    lines: List<List<Vector2>>,
    tileW: Double,
    tileH: Double
): List<List<Vector2>> {

    val extMinX = -tileW; val extMinY = -tileH
    val extMaxX = 2.0 * tileW; val extMaxY = 2.0 * tileH

    val segments = mutableListOf<Segment>()
    for (line in lines) {
        for (s in polylineSegments(line)) {
            clipSegmentToRect(s, extMinX, extMinY, extMaxX, extMaxY)?.let { segments.add(it) }
        }
    }
    segments += polygonEdgeSegments(listOf(
        Vector2(extMinX, extMinY), Vector2(extMaxX, extMinY),
        Vector2(extMaxX, extMaxY), Vector2(extMinX, extMaxY)
    ))

    return polygonizeSegments(segments, 1.0).filter { zone ->
        val p = interiorPoint(zone)
        p.x >= 0.0 && p.x < tileW && p.y >= 0.0 && p.y < tileH
    }
}

/**
 * Découpage du motif raccordable. Attend une
 * CompositionGuide.PeriodicShards. Portage du prototype Python
 * (claude/prototype_motif_raccordable.py dans les documents du projet).
 *
 * 1. ZONES : arrangement des lignes maîtresses sur le domaine étendu
 *    [−W, 2W] × [−H, 2H] (polygonizeSegments, Arrangement.kt). On ne
 *    garde que les zones « canoniques », dont un point intérieur est
 *    dans la tuile [0, W) × [0, H) : chaque zone du motif infini est la
 *    copie translatée d'exactement une zone canonique.
 * 2. GESTES (rares) : dans une zone, des arcs concentriques autour d'un
 *    de ses sommets (arcGestureProb) ou un éventail de droites partant
 *    d'un sommet (fanGestureProb).
 * 3. ÉCLATS : on coupe toujours la PLUS GRANDE pièce restante de la
 *    zone, par une droite passant par un point tiré à l'intérieur,
 *    jusqu'à un nombre de pièces proportionnel à l'aire de la zone
 *    (pieceAreaRatio de la tuile par pièce). Couper une seule pièce à la
 *    fois crée des jonctions en T (une coupe s'arrête sur une autre),
 *    comme sur un vrai vitrail. Une coupe est refusée si elle crée une
 *    pièce plus petite que minPieceRatio de la tuile, trop allongée
 *    (compacité 4πA/P² < minCompactness), ou qu'un verrier ne pourrait
 *    pas couper : une partie où ne passe pas un disque de diamètre
 *    minWidthRatio × (petit côté de la tuile) — pointe très aiguë,
 *    lanière, goulet (voir narrowResidueCells, Arrangement.kt).
 *    Le même contrôle s'applique aux gestes (arcs, éventails) : un arc
 *    presque tangent à un plomb est interrompu plutôt que de laisser
 *    une lanière de verre.
 *
 * Les cellules renvoyées sont les pièces canoniques ; elles peuvent
 * dépasser du bord de la tuile. Voir renderVitrail pour leur
 * répétition aux 9 positions.
 *
 * ⚠ Les jonctions en T laissent, sur l'arête de la pièce voisine, un
 * point qui n'est pas un de ses sommets : avec des plombs COURBES, les
 * deux côtés seraient dessinés avec des courbures différentes. Cette
 * famille s'utilise donc avec curvatureAmount = 0 (plombs droits,
 * superposés exactement).
 */
class PeriodicShardsSegmentationSystem : SegmentationSystem {

    override fun segment(
        guide: CompositionGuide,
        width: Double,
        height: Double,
        random: Random
    ): List<List<Vector2>> {

        require(guide is CompositionGuide.PeriodicShards) {
            "PeriodicShardsSegmentationSystem nécessite une CompositionGuide.PeriodicShards"
        }

        val tileW = guide.tileWidth
        val tileH = guide.tileHeight
        val tileArea = tileW * tileH

        // --- 1. Zones canoniques
        val zones = periodicCanonicalZones(guide.primaryLines, tileW, tileH)
        // (déjà triées par polygonizeSegments, indépendamment de l'échelle :
        // l'ordre des zones fixe l'ordre des tirages aléatoires qui suivent,
        // il doit être le même à l'aperçu et à l'export)

        val minPieceArea = guide.minPieceRatio * tileArea
        val narrowRadius = 0.5 * guide.minWidthRatio * kotlin.math.min(tileW, tileH)
        val pieces = mutableListOf<List<Vector2>>()

        // Résidu étroit de chaque pièce (calculé une fois par pièce).
        val residueCache = java.util.IdentityHashMap<List<Vector2>, Set<Long>>()
        fun residueOf(piece: List<Vector2>) =
            residueCache.getOrPut(piece) { narrowResidueCells(piece, narrowRadius) }

        // Une coupe de `parent` en `parts` est acceptée si aucune partie
        // n'est trop petite, et si la coupe ne crée aucune partie trop
        // étroite (pointe effilée, lanière, goulet : voir
        // narrowResidueCells, Arrangement.kt).
        fun cutIsCraftable(parent: List<Vector2>, parts: List<List<Vector2>>): Boolean {
            if (parts.size < 2) return false
            if (parts.any { polygonAreaAbs(it) < minPieceArea }) return false
            val parentResidue = residueOf(parent)
            return parts.all { newNarrowResidue(it, parentResidue, narrowRadius) <= guide.maxNarrowResidue }
        }

        fun acceptable(parent: List<Vector2>, parts: List<List<Vector2>>) =
            cutIsCraftable(parent, parts) &&
                    parts.all { polygonCompactnessRatio(it) >= guide.minCompactness }

        for (zone in zones) {

            var parts = mutableListOf(zone)
            val zb = polygonBounds(zone)
            val diagonal = kotlin.math.hypot(zb[2] - zb[0], zb[3] - zb[1])

            // --- 2. Gestes
            val gesture = random.nextDouble()
            val gestureCuts = mutableListOf<List<Vector2>>()
            if (gesture < guide.arcGestureProb) {
                val v = zone[random.nextInt(zone.size)]
                val count = random.nextInt(2, 5)
                for (k in 1..count) gestureCuts.add(circlePoints(v, diagonal * 0.18 * k, 90))
            } else if (gesture < guide.arcGestureProb + guide.fanGestureProb) {
                val v = zone[random.nextInt(zone.size)]
                val a0 = random.nextDouble(0.0, 2.0 * Math.PI)
                var a = a0
                repeat(random.nextInt(3, 6)) {
                    gestureCuts.add(listOf(v, Vector2(v.x + kotlin.math.cos(a) * diagonal * 2.0, v.y + kotlin.math.sin(a) * diagonal * 2.0)))
                    a += random.nextDouble(0.25, 0.45)
                }
            }
            for (cut in gestureCuts) {
                val next = mutableListOf<List<Vector2>>()
                for (part in parts) {
                    val split = splitPolygonByPolyline(part, cut)
                    if (cutIsCraftable(part, split)) next.addAll(split) else next.add(part)
                }
                parts = next
            }

            // --- 3. Éclats
            val target = kotlin.math.max(1, Math.round(polygonAreaAbs(zone) / (guide.pieceAreaRatio * tileArea)).toInt())
            var attempts = 0
            while (parts.size < target && attempts < 40) {
                attempts++
                parts.sortBy { polygonAreaAbs(it) }
                val big = parts.last()
                val bb = polygonBounds(big)
                val bd = kotlin.math.hypot(bb[2] - bb[0], bb[3] - bb[1])
                var point: Vector2? = null
                for (tries in 0 until 20) {
                    val q = Vector2(random.nextDouble(bb[0], bb[2]), random.nextDouble(bb[1], bb[3]))
                    if (polygonContains(big, q)) { point = q; break }
                }
                if (point == null) continue
                val angle = random.nextDouble(0.0, Math.PI)
                val dx = kotlin.math.cos(angle) * bd * 2.0
                val dy = kotlin.math.sin(angle) * bd * 2.0
                val split = splitPolygonByPolyline(big, listOf(Vector2(point.x - dx, point.y - dy), Vector2(point.x + dx, point.y + dy)))
                if (!acceptable(big, split)) continue
                parts = (parts.dropLast(1) + split).toMutableList()
            }

            pieces.addAll(parts.sortedWith(compareBy({ polygonCentroid(it).y }, { polygonCentroid(it).x })))
        }

        return pieces
    }
}