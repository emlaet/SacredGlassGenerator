import org.openrndr.math.Vector2
import kotlin.math.sqrt
import kotlin.random.Random

fun findIntersection(
    start: Vector2,
    end: Vector2,
    midpoint: Vector2,
    dx: Double,
    dy: Double
): Vector2 {

    val startSide =
        (start.x - midpoint.x) * dx +
                (start.y - midpoint.y) * dy

    val endSide =
        (end.x - midpoint.x) * dx +
                (end.y - midpoint.y) * dy

    val t = startSide / (startSide - endSide)

    return Vector2(
        start.x + t * (end.x - start.x),
        start.y + t * (end.y - start.y)
    )
}


fun clipPolygon(
    polygon: List<Vector2>,
    midpoint: Vector2,
    dx: Double,
    dy: Double
): List<Vector2> {

    val result = mutableListOf<Vector2>()

    for (i in polygon.indices) {

        val current = polygon[i]

        val next = polygon[
            (i + 1) % polygon.size
        ]

        val currentSide =
            (current.x - midpoint.x) * dx +
                    (current.y - midpoint.y) * dy

        val nextSide =
            (next.x - midpoint.x) * dx +
                    (next.y - midpoint.y) * dy

        val currentInside = currentSide <= 0.0
        val nextInside = nextSide <= 0.0

        if (currentInside && nextInside) {

            result.add(next)

        } else if (currentInside && !nextInside) {

            val intersection = findIntersection(
                current,
                next,
                midpoint,
                dx,
                dy
            )

            result.add(intersection)

        } else if (!currentInside && nextInside) {

            val intersection = findIntersection(
                current,
                next,
                midpoint,
                dx,
                dy
            )

            result.add(intersection)
            result.add(next)
        }
    }

    return result
}


fun generateVoronoiCell(
    siteIndex: Int,
    sites: List<Vector2>,
    width: Double,
    height: Double
): List<Vector2> {

    var polygon = listOf(
        Vector2(0.0, 0.0),
        Vector2(width, 0.0),
        Vector2(width, height),
        Vector2(0.0, height)
    )

    val site = sites[siteIndex]

    for (i in sites.indices) {

        if (i == siteIndex) {
            continue
        }

        val other = sites[i]

        // Milieu entre les deux sites
        val midpoint = Vector2(
            (site.x + other.x) / 2.0,
            (site.y + other.y) / 2.0
        )

        // Vecteur allant du site vers l'autre site
        val dx = other.x - site.x
        val dy = other.y - site.y

        polygon = clipPolygon(
            polygon,
            midpoint,
            dx,
            dy
        )

        if (polygon.isEmpty()) {
            break
        }
    }

    return polygon
}


fun polygonCentroid(
    polygon: List<Vector2>
): Vector2 {

    var area = 0.0
    var centroidX = 0.0
    var centroidY = 0.0

    for (i in polygon.indices) {

        val current = polygon[i]

        val next = polygon[
            (i + 1) % polygon.size
        ]

        val cross =
            current.x * next.y -
                    next.x * current.y

        area += cross

        centroidX +=
            (current.x + next.x) * cross

        centroidY +=
            (current.y + next.y) * cross
    }

    area *= 0.5

    if (area == 0.0) {
        return polygon[0]
    }

    centroidX /= (6.0 * area)
    centroidY /= (6.0 * area)

    return Vector2(
        centroidX,
        centroidY
    )
}


fun relaxPoints(
    points: List<Vector2>,
    width: Double,
    height: Double,
    iterations: Int,
    random: Random
): List<Vector2> {

    var currentPoints = points

    repeat(iterations) {

        val cells = currentPoints.mapIndexed { index, _ ->
            generateVoronoiCell(
                index,
                currentPoints,
                width,
                height
            )
        }

        currentPoints = currentPoints.mapIndexed { index, point ->

            val cell = cells[index]

            if (cell.size < 3) {
                point
            } else {
                polygonCentroid(cell)
            }
        }
    }

    return currentPoints
}


fun perturbPoints(
    points: List<Vector2>,
    amount: Double,
    width: Double,
    height: Double,
    random: Random
): List<Vector2> {

    return points.map { point ->

        val dx = random.nextDouble(-amount, amount)
        val dy = random.nextDouble(-amount, amount)

        Vector2(
            (point.x + dx).coerceIn(0.0, width),
            (point.y + dy).coerceIn(0.0, height)
        )
    }
}


fun minimumDistanceAt(
    point: Vector2,
    width: Double,
    height: Double,
    sizeVariation: Double
): Double {

    val centerX = width / 2.0
    val centerY = height / 2.0

    val dx = point.x - centerX
    val dy = point.y - centerY

    val distance =
        sqrt(dx * dx + dy * dy)

    val maximumDistance =
        sqrt(
            centerX * centerX +
                    centerY * centerY
        )

    val normalized =
        distance / maximumDistance

    val baseDistance = 50.0
    val variation = 30.0

    return baseDistance +
            variation * sizeVariation * (1.0 - normalized)
}


fun generatePoints(
    numberOfSites: Int,
    width: Int,
    height: Int,
    random: Random,
    sizeVariation: Double
): List<Vector2> {

    val points = mutableListOf<Vector2>()
    val pointDistances = mutableListOf<Double>()

    val maxAttempts = 100_000
    var attempts = 0

    while (
        points.size < numberOfSites &&
        attempts < maxAttempts
    ) {

        attempts++

        val candidate = Vector2(
            random.nextDouble(
                0.0,
                width.toDouble()
            ),
            random.nextDouble(
                0.0,
                height.toDouble()
            )
        )

        // La distance minimale dépend maintenant
        // de la position du point dans l'image.
        val candidateDistance =
            minimumDistanceAt(
                candidate,
                width.toDouble(),
                height.toDouble(),
                sizeVariation
            )

        var valid = true

        for (i in points.indices) {

            val point = points[i]

            val dx = candidate.x - point.x
            val dy = candidate.y - point.y

            val distanceSquared =
                dx * dx + dy * dy

            val existingDistance =
                pointDistances[i]

            val requiredDistance =
                (candidateDistance + existingDistance) / 2.0

            if (
                distanceSquared <
                requiredDistance * requiredDistance
            ) {

                valid = false
                break
            }
        }

        if (valid) {

            points.add(candidate)
            pointDistances.add(candidateDistance)
        }
    }

    return points
}


// --------------------------------------------------
// GÉNÉRATION DE SITES "ORGANIQUE" (champ de bruit)
// --------------------------------------------------
//
// minimumDistanceAt() ci-dessus ne fait varier la taille désirée
// des cellules qu'en fonction de la distance au centre (un dégradé
// radial). Résultat : les cellules restent assez uniformes en
// taille à distance égale du centre, ce qui donne un pavage en
// "nid d'abeille" plutôt régulier.
//
// noiseField() introduit à la place un champ de bruit spatial
// (somme de sinus décorrélés, dans le même esprit que
// Deformation.kt) : la taille désirée varie alors de façon
// irrégulière sur toute l'image, sans lien avec la position
// relative au centre. C'est ce qui permet d'obtenir un vrai
// mélange de grandes et petites pièces de verre, comme sur un
// vitrail réel.

fun noiseField(
    x: Double,
    y: Double
): Double {

    return 0.5 +
            0.25 * kotlin.math.sin(
        x * 0.010 +
                y * 0.013
    ) +
            0.25 * kotlin.math.sin(
        x * 0.004 -
                y * 0.007
    )
}

fun organicMinimumDistanceAt(
    point: Vector2,
    baseDistance: Double,
    sizeVariation: Double
): Double {

    val noise = noiseField(point.x, point.y)

    // noise ∈ [0, 1] → taille locale entre 0.4x et (0.4 + 1.8·sizeVariation)x
    val factor = 0.4 + sizeVariation * 1.8 * noise

    return baseDistance * factor
}

fun generateOrganicPoints(
    numberOfSites: Int,
    width: Double,
    height: Double,
    random: Random,
    baseDistance: Double,
    sizeVariation: Double
): List<Vector2> {

    val points = mutableListOf<Vector2>()
    val pointDistances = mutableListOf<Double>()

    val maxAttempts = 200_000
    var attempts = 0

    while (
        points.size < numberOfSites &&
        attempts < maxAttempts
    ) {

        attempts++

        val candidate = Vector2(
            random.nextDouble(0.0, width),
            random.nextDouble(0.0, height)
        )

        val candidateDistance =
            organicMinimumDistanceAt(
                candidate,
                baseDistance,
                sizeVariation
            )

        var valid = true

        for (i in points.indices) {

            val point = points[i]

            val dx = candidate.x - point.x
            val dy = candidate.y - point.y

            val distanceSquared = dx * dx + dy * dy

            val existingDistance = pointDistances[i]

            val requiredDistance =
                (candidateDistance + existingDistance) / 2.0

            if (distanceSquared < requiredDistance * requiredDistance) {
                valid = false
                break
            }
        }

        if (valid) {
            points.add(candidate)
            pointDistances.add(candidateDistance)
        }
    }

    return points
}


// --------------------------------------------------
// COURBES DIRECTRICES + "CLÔTURE" DE SITES
// --------------------------------------------------
//
// Technique classique pour forcer un diagramme de Voronoï à faire
// passer une frontière de cellule le long d'une courbe donnée :
// on sème deux rangées de points, une de chaque côté de la courbe,
// à faible décalage perpendiculaire. La bissectrice entre deux
// points d'une même paire est alors localement tangente à la
// courbe, et l'enchaînement des paires successives approxime la
// courbe sur toute sa longueur.
//
// Deux réglages critiques (validés empiriquement en Python avant
// ce portage) :
// - l'espacement entre paires doit être PROCHE de la taille de
//   cellule ambiante (baseDistance), sinon la clôture crée sa
//   propre chaîne de fines échardes au lieu d'une simple frontière ;
// - le décalage perpendiculaire doit rester PETIT (une fraction de
//   baseDistance), sinon la clôture engendre un ruban de cellules
//   fines qui lui est propre, très visible et peu naturel.

fun generateGuideCurves(
    width: Double,
    height: Double,
    numberOfCurves: Int,
    random: Random
): List<List<Vector2>> {

    val curves = mutableListOf<List<Vector2>>()

    repeat(numberOfCurves) {

        val vertical = random.nextBoolean()

        val baseOffset = random.nextDouble(0.2, 0.8)
        val amplitude = (if (vertical) width else height) * random.nextDouble(0.10, 0.25)
        val frequency = random.nextDouble(1.2, 2.6)
        val phase = random.nextDouble(0.0, 2.0 * Math.PI)

        val samples = 60
        val points = mutableListOf<Vector2>()

        for (s in 0..samples) {

            val t = s.toDouble() / samples

            val wobble =
                amplitude * kotlin.math.sin(t * frequency * Math.PI + phase) +
                        amplitude * 0.35 * kotlin.math.sin(t * frequency * 2.3 * Math.PI + phase * 1.7)

            val point = if (vertical) {
                Vector2(
                    (baseOffset * width + wobble).coerceIn(0.0, width),
                    t * height
                )
            } else {
                Vector2(
                    t * width,
                    (baseOffset * height + wobble).coerceIn(0.0, height)
                )
            }

            points.add(point)
        }

        curves.add(points)
    }

    return curves
}

fun curveFencePoints(
    curve: List<Vector2>,
    fenceSpacing: Double,
    offsetDistance: Double
): List<Vector2> {

    if (curve.size < 2) {
        return emptyList()
    }

    val fencePoints = mutableListOf<Vector2>()

    var accumulated = 0.0
    var nextSampleAt = 0.0

    for (i in 0 until curve.size - 1) {

        val a = curve[i]
        val b = curve[i + 1]

        val dx = b.x - a.x
        val dy = b.y - a.y

        val segmentLength = sqrt(dx * dx + dy * dy)

        if (segmentLength == 0.0) {
            continue
        }

        val normalX = -(dy / segmentLength)
        val normalY = dx / segmentLength

        while (nextSampleAt <= accumulated + segmentLength) {

            val localT = (nextSampleAt - accumulated) / segmentLength

            val sampleX = a.x + dx * localT
            val sampleY = a.y + dy * localT

            fencePoints.add(
                Vector2(
                    sampleX + normalX * offsetDistance,
                    sampleY + normalY * offsetDistance
                )
            )

            fencePoints.add(
                Vector2(
                    sampleX - normalX * offsetDistance,
                    sampleY - normalY * offsetDistance
                )
            )

            nextSampleAt += fenceSpacing
        }

        accumulated += segmentLength
    }

    return fencePoints
}

/**
 * Variante de generateOrganicPoints() qui part d'un ensemble de
 * points déjà imposés (seedPoints — typiquement une clôture de
 * courbes directrices) et complète l'espace restant avec le même
 * échantillonnage par rejet piloté par bruit spatial.
 */
fun generateOrganicPointsWithSeeds(
    numberOfSites: Int,
    width: Double,
    height: Double,
    random: Random,
    baseDistance: Double,
    sizeVariation: Double,
    seedPoints: List<Vector2>,
    seedMinDistance: Double
): List<Vector2> {

    val points = seedPoints.toMutableList()
    val pointDistances = MutableList(seedPoints.size) { seedMinDistance }

    val maxAttempts = 200_000
    var attempts = 0

    val targetTotal = seedPoints.size + numberOfSites

    while (
        points.size < targetTotal &&
        attempts < maxAttempts
    ) {

        attempts++

        val candidate = Vector2(
            random.nextDouble(0.0, width),
            random.nextDouble(0.0, height)
        )

        val candidateDistance =
            organicMinimumDistanceAt(
                candidate,
                baseDistance,
                sizeVariation
            )

        var valid = true

        for (i in points.indices) {

            val point = points[i]

            val dx = candidate.x - point.x
            val dy = candidate.y - point.y

            val distanceSquared = dx * dx + dy * dy

            val requiredDistance =
                (candidateDistance + pointDistances[i]) / 2.0

            if (distanceSquared < requiredDistance * requiredDistance) {
                valid = false
                break
            }
        }

        if (valid) {
            points.add(candidate)
            pointDistances.add(candidateDistance)
        }
    }

    return points
}


fun densityAt(
    point: Vector2,
    width: Double,
    height: Double
): Double {

    val centerX = width / 2.0
    val centerY = height / 2.0

    val dx = point.x - centerX
    val dy = point.y - centerY

    val distanceFromCenter =
        sqrt(
            dx * dx +
                    dy * dy
        )

    val maximumDistance =
        sqrt(
            centerX * centerX +
                    centerY * centerY
        )

    val normalizedDistance =
        distanceFromCenter / maximumDistance

    return 0.7 + 0.6 * normalizedDistance
}


fun weightedPolygonCentroid(
    polygon: List<Vector2>,
    width: Double,
    height: Double,
    samples: Int,
    random: Random
): Vector2 {

    var totalWeight = 0.0
    var weightedX = 0.0
    var weightedY = 0.0

    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE

    for (point in polygon) {

        minX = minOf(minX, point.x)
        maxX = maxOf(maxX, point.x)

        minY = minOf(minY, point.y)
        maxY = maxOf(maxY, point.y)
    }

    var acceptedSamples = 0

    while (acceptedSamples < samples) {

        val x = random.nextDouble(minX, maxX)
        val y = random.nextDouble(minY, maxY)

        val sample = Vector2(x, y)

        // On vérifie que le point est dans la cellule.
        var inside = false

        var j = polygon.lastIndex

        for (i in polygon.indices) {

            val xi = polygon[i].x
            val yi = polygon[i].y

            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersects =
                ((yi > y) != (yj > y)) &&
                        (
                                x <
                                        (xj - xi) * (y - yi) /
                                        (yj - yi) + xi
                                )

            if (intersects) {
                inside = !inside
            }

            j = i
        }

        if (inside) {

            val weight =
                densityAt(
                    sample,
                    width,
                    height
                )

            weightedX += sample.x * weight
            weightedY += sample.y * weight

            totalWeight += weight

            acceptedSamples++
        }
    }

    if (totalWeight == 0.0) {
        return polygonCentroid(polygon)
    }

    return Vector2(
        weightedX / totalWeight,
        weightedY / totalWeight
    )
}