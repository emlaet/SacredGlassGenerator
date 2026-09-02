import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import org.openrndr.draw.Drawer
import org.openrndr.draw.shadeStyle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.Segment2D
import kotlin.math.sqrt
import kotlin.random.Random

data class EdgeCurve(
    val start: Vector2,
    val control1: Vector2,
    val control2: Vector2,
    val end: Vector2
)

// --------------------------------------------------
// FONCTIONS DE GÉNÉRATION
// --------------------------------------------------

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


// --------------------------------------------------
// DÉFORMATION DES SOMMETS
// --------------------------------------------------

fun vertexRandomValue(
    x: Double,
    y: Double,
    offset: Int
): Double {

    /*
     * On transforme les coordonnées du sommet en une valeur
     * pseudo-aléatoire stable.
     *
     * Le résultat est toujours identique pour les mêmes
     * coordonnées et le même offset.
     */

    val ix = (x * 1000.0).toLong()
    val iy = (y * 1000.0).toLong()

    var value =
        ix * 73856093L +
                iy * 19349663L +
                offset * 83492791L

    value = value xor (value shr 13)
    value *= 1274126177L
    value = value xor (value shr 16)

    return (
            (value and 0x7FFFFFFF).toDouble()
                    / 0x7FFFFFFF.toDouble()
            )
}


fun deformCellVertices(
    cell: List<Vector2>,
    width: Double,
    height: Double,
    vertexCache: MutableMap<String, Vector2>,
    deformationAmount: Double
): List<Vector2> {

    return cell.map { point ->

        /*
         * Les coordonnées arrondies servent d'identifiant
         * commun pour les sommets partagés entre plusieurs
         * cellules.
         */
        val key =
            "${"%.3f".format(point.x)}_" +
                    "${"%.3f".format(point.y)}"

        vertexCache[key] ?: run {

            // Les sommets situés sur le bord de l'image
            // restent parfaitement immobiles.
            val onBorder =
                point.x <= 0.1 ||
                        point.x >= width - 0.1 ||
                        point.y <= 0.1 ||
                        point.y >= height - 0.1

            val deformed = if (onBorder) {

                point

            } else {

                /*
                 * Déformation spatiale lisse.
                 *
                 * Contrairement à un hash totalement aléatoire,
                 * les sommets proches reçoivent ici des déplacements
                 * proches. Cela évite les changements brutaux de
                 * direction autour des jonctions.
                 */

                val x = point.x
                val y = point.y

                val randomX =
                    0.5 +
                            0.25 * kotlin.math.sin(
                        x * 0.018 +
                                y * 0.011 +
                                1.7
                    ) +
                            0.25 * kotlin.math.sin(
                        x * 0.007 -
                                y * 0.021 +
                                4.3
                    )

                val randomY =
                    0.5 +
                            0.25 * kotlin.math.sin(
                        x * 0.013 -
                                y * 0.017 +
                                2.8
                    ) +
                            0.25 * kotlin.math.sin(
                        x * 0.022 +
                                y * 0.006 +
                                5.1
                    )

                val dx =
                    (randomX - 0.5) *
                            2.0 *
                            deformationAmount

                val dy =
                    (randomY - 0.5) *
                            2.0 *
                            deformationAmount

                Vector2(
                    (point.x + dx).coerceIn(
                        0.0,
                        width
                    ),
                    (point.y + dy).coerceIn(
                        0.0,
                        height
                    )
                )
            }

            vertexCache[key] = deformed

            deformed
        }
    }
}


fun drawVoronoiCell(
    drawer: Drawer,
    cell: List<Vector2>,
    fillColor: ColorRGBa,
    strokeColor: ColorRGBa,
    strokeWeight: Double,
    width: Double,
    height: Double,
    vertexCache: MutableMap<String, Vector2>,
    deformationAmount: Double,
    random: Random
) {

    if (cell.size < 3) {
        return
    }

    val deformedCell =
        deformCellVertices(
            cell,
            width,
            height,
            vertexCache,
            deformationAmount
        )

    val contour =
        ShapeContour.fromPoints(
            deformedCell,
            closed = true
        )

    drawer.fill = fillColor
    drawer.stroke = null

    drawer.contour(contour)
}

fun edgeKey(a: Vector2, b: Vector2): String {
    val keyA = "%.3f_%.3f".format(a.x, a.y)
    val keyB = "%.3f_%.3f".format(b.x, b.y)

    return if (keyA < keyB) {
        "$keyA|$keyB"
    } else {
        "$keyB|$keyA"
    }
}

fun getEdgeCurve(
    a: Vector2,
    b: Vector2,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double
): EdgeCurve {

    val key = edgeKey(a, b)

    // --------------------------------------------------
    // L'arête existe déjà
    // --------------------------------------------------

    edgeCurveCache[key]?.let { cachedCurve ->

        if (cachedCurve.start == a && cachedCurve.end == b) {
            return cachedCurve
        }

        // Arête parcourue dans le sens inverse.
        //
        // Pour une courbe cubique :
        //
        // A → C1 → C2 → B
        //
        // devient :
        //
        // B → C2 → C1 → A

        return EdgeCurve(
            start = a,
            control1 = cachedCurve.control2,
            control2 = cachedCurve.control1,
            end = b
        )
    }

    // --------------------------------------------------
    // Géométrie de base
    // --------------------------------------------------

    val dx = b.x - a.x
    val dy = b.y - a.y

    val length = sqrt(
        dx * dx +
                dy * dy
    )

    if (length == 0.0) {

        val curve = EdgeCurve(
            start = a,
            control1 = a,
            control2 = b,
            end = b
        )

        edgeCurveCache[key] = curve

        return curve
    }

    // --------------------------------------------------
    // Vecteur tangent
    // --------------------------------------------------

    val tangentX = dx / length
    val tangentY = dy / length

    // Vecteur normal
    val normalX = -tangentY
    val normalY = tangentX

    // --------------------------------------------------
    // Direction déterministe
    // --------------------------------------------------

    val direction =
        if (key.hashCode() and 1 == 0) {
            1.0
        } else {
            -1.0
        }

    // --------------------------------------------------
    // Deux contrôles légèrement différents
    // --------------------------------------------------

    val controlDistance =
        length * 0.33

    val offset1 =
        curvatureAmount * direction

    val offset2 =
        curvatureAmount * direction * 0.65

    val control1 = Vector2(
        a.x +
                tangentX * controlDistance +
                normalX * offset1,

        a.y +
                tangentY * controlDistance +
                normalY * offset1
    )

    val control2 = Vector2(
        b.x -
                tangentX * controlDistance +
                normalX * offset2,

        b.y -
                tangentY * controlDistance +
                normalY * offset2
    )

    val curve = EdgeCurve(
        start = a,
        control1 = control1,
        control2 = control2,
        end = b
    )

    edgeCurveCache[key] = curve

    return curve
}

fun drawCurvedVoronoiEdges(
    drawer: Drawer,
    cells: List<List<Vector2>>,
    strokeColor: ColorRGBa,
    strokeWeight: Double,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double
) {

    val drawnEdges = mutableSetOf<String>()

    drawer.fill = null
    drawer.stroke = strokeColor
    drawer.strokeWeight = strokeWeight

    for (cell in cells) {

        if (cell.size < 3) {
            continue
        }

        for (i in cell.indices) {

            val a = cell[i]
            val b = cell[(i + 1) % cell.size]

            val key = edgeKey(a, b)

            if (drawnEdges.add(key)) {

                val curve = getEdgeCurve(
                    a,
                    b,
                    edgeCurveCache,
                    curvatureAmount
                )

                val contour = ShapeContour.fromSegments(
                    listOf(
                        Segment2D(
                            curve.start,
                            curve.control1,
                            curve.control2,
                            curve.end
                        )
                    ),
                    closed = false
                )

                drawer.contour(contour)
            }
        }
    }
}

fun createCurvedContour(
    cell: List<Vector2>,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double
): ShapeContour {

    if (cell.size < 3) {
        return ShapeContour.EMPTY
    }

    val segments = mutableListOf<Segment2D>()

    for (i in cell.indices) {

        val a = cell[i]
        val b = cell[(i + 1) % cell.size]

        val curve = getEdgeCurve(
            a,
            b,
            edgeCurveCache,
            curvatureAmount
        )

        segments.add(
            Segment2D(
                curve.start,
                curve.control1,
                curve.control2,
                curve.end
            )
        )
    }

    return ShapeContour.fromSegments(
        segments,
        closed = true
    )
}



// --------------------------------------------------
// PROGRAMME PRINCIPAL
// --------------------------------------------------

fun main() = application {

    configure {
        width = 768
        height = 576
    }

    program {

        // ------------------------
        // PARAMÈTRES
        // ------------------------

        val seed = 123
        val random = Random(seed)

        val numberOfSites = 50

        val sizeVariation = 1.0

        val relaxationIterations = 1

        val perturbationAmount = 8.0

        val curvatureAmount = 2.0

        val glassTextureStrength = 0.045

        /*
         * NOUVEAU PARAMÈTRE
         *
         * 0.0  = Voronoï parfaitement géométrique
         * 2.0  = déformation très subtile
         * 5.0  = déformation visible mais élégante
         * 8.0  = déformation plus organique
         * 12.0 = effet beaucoup plus irrégulier
         */
        val deformationAmount = 7.0

        val strokeWeight = 5.0
        val strokeColor = ColorRGBa.BLACK

        val backgroundColor = ColorRGBa.WHITE

        val palette = listOf(

            ColorRGBa.fromHex("#174A78"), // bleu profond
            ColorRGBa.fromHex("#245C8A"), // bleu moyen
            ColorRGBa.fromHex("#6D8FA8"), // bleu gris
            ColorRGBa.fromHex("#C99A2E"), // or ancien
            ColorRGBa.fromHex("#E0B84F"), // or lumineux
            ColorRGBa.fromHex("#7A2025"), // rouge vitrail
            ColorRGBa.fromHex("#A83A35"), // rouge plus clair
            ColorRGBa.fromHex("#F2E8CE")  // ivoire
        )


        // ------------------------
        // CRÉATION DES PIÈCES
        // ------------------------

        val initialPoints =
            generatePoints(
                numberOfSites,
                width,
                height,
                random,
                sizeVariation
            )


        val relaxedPoints =
            relaxPoints(
                initialPoints,
                width.toDouble(),
                height.toDouble(),
                relaxationIterations,
                random
            )


        val points =
            perturbPoints(
                relaxedPoints,
                perturbationAmount,
                width.toDouble(),
                height.toDouble(),
                random
            )


        val voronoiCells =
            points.mapIndexed { index, _ ->

                generateVoronoiCell(
                    index,
                    points,
                    width.toDouble(),
                    height.toDouble()
                )
            }


        val cellColors =
            voronoiCells.map {

                palette[
                    random.nextInt(
                        palette.size
                    )
                ]
            }


        // ------------------------
// RENDU
// ------------------------

        val vertexCache =
            mutableMapOf<String, Vector2>()

        val edgeCurveCache =
            mutableMapOf<String, EdgeCurve>()

        val glassShadeStyle = shadeStyle {

            fragmentTransform = """

        vec2 uv = c_boundsPosition.xy;


// --------------------------------------------------
// 1. LUMIÈRE CENTRALE
// --------------------------------------------------

float distanceFromCenter =
    distance(
        uv,
        p_cellCenter
    );

// --------------------------------------------------
// LUMIÈRE DU VERRE
// --------------------------------------------------

// Lumière douce au centre de la pièce
float centerLight =
    1.0 -
    smoothstep(
        0.05,
        0.75,
        distanceFromCenter
    );

// Légère variation directionnelle
float directionalLight =
    0.08 *
    (
        0.5 +
        0.5 * sin(
            uv.x * 5.0 +
            uv.y * 3.0
        )
    );

// Micro-variation organique du verre
float glassVariation =
    0.035 *
    sin(
        uv.x * 31.0 +
        sin(uv.y * 17.0)
    ) *
    sin(
        uv.y * 23.0 +
        sin(uv.x * 13.0)
    );

// Lumière finale
float light =
    0.72 +
    0.65 * centerLight +
    directionalLight +
    glassVariation;
    
    


        // --------------------------------------------------
        // 2. VARIATION ORGANIQUE
        // --------------------------------------------------

        float organicVariation =
            sin(uv.x * 8.7 + uv.y * 5.3) * 0.5 +
            sin(uv.x * 13.1 - uv.y * 7.4) * 0.3 +
            sin(uv.x * 21.7 + uv.y * 11.2) * 0.2;

        organicVariation =
            organicVariation * 0.5 + 0.5;

        light *=
            1.0 +
            (organicVariation - 0.5) * 0.10;


        // --------------------------------------------------
        // 3. TEXTURE DU VERRE
        // --------------------------------------------------

        // Première échelle : variation large et douce

        vec2 noisePosition1 =
            uv * 4.0;

        vec2 noiseCell1 =
            floor(noisePosition1);

        vec2 noiseFraction1 =
            fract(noisePosition1);

        noiseFraction1 =
            noiseFraction1 *
            noiseFraction1 *
            (3.0 - 2.0 * noiseFraction1);


        float noiseA1 =
            fract(
                sin(
                    dot(
                        noiseCell1,
                        vec2(127.1, 311.7)
                    )
                ) * 43758.5453
            );

        float noiseB1 =
            fract(
                sin(
                    dot(
                        noiseCell1 + vec2(1.0, 0.0),
                        vec2(127.1, 311.7)
                    )
                ) * 43758.5453
            );

        float noiseC1 =
            fract(
                sin(
                    dot(
                        noiseCell1 + vec2(0.0, 1.0),
                        vec2(127.1, 311.7)
                    )
                ) * 43758.5453
            );

        float noiseD1 =
            fract(
                sin(
                    dot(
                        noiseCell1 + vec2(1.0, 1.0),
                        vec2(127.1, 311.7)
                    )
                ) * 43758.5453
            );


        float noiseLarge =
            mix(
                mix(
                    noiseA1,
                    noiseB1,
                    noiseFraction1.x
                ),
                mix(
                    noiseC1,
                    noiseD1,
                    noiseFraction1.x
                ),
                noiseFraction1.y
            );


        // --------------------------------------------------
        // Deuxième échelle : détail plus fin
        // --------------------------------------------------

        vec2 noisePosition2 =
            uv * 9.0;

        vec2 noiseCell2 =
            floor(noisePosition2);

        vec2 noiseFraction2 =
            fract(noisePosition2);

        noiseFraction2 =
            noiseFraction2 *
            noiseFraction2 *
            (3.0 - 2.0 * noiseFraction2);


        float noiseA2 =
            fract(
                sin(
                    dot(
                        noiseCell2,
                        vec2(269.5, 183.3)
                    )
                ) * 43758.5453
            );

        float noiseB2 =
            fract(
                sin(
                    dot(
                        noiseCell2 + vec2(1.0, 0.0),
                        vec2(269.5, 183.3)
                    )
                ) * 43758.5453
            );

        float noiseC2 =
            fract(
                sin(
                    dot(
                        noiseCell2 + vec2(0.0, 1.0),
                        vec2(269.5, 183.3)
                    )
                ) * 43758.5453
            );

        float noiseD2 =
            fract(
                sin(
                    dot(
                        noiseCell2 + vec2(1.0, 1.0),
                        vec2(269.5, 183.3)
                    )
                ) * 43758.5453
            );


        float noiseFine =
            mix(
                mix(
                    noiseA2,
                    noiseB2,
                    noiseFraction2.x
                ),
                mix(
                    noiseC2,
                    noiseD2,
                    noiseFraction2.x
                ),
                noiseFraction2.y
            );


        // --------------------------------------------------
        // MÉLANGE DES DEUX ÉCHELLES
        // --------------------------------------------------

        float materialVariation =
            noiseLarge * 0.75 +
            noiseFine * 0.25;

        materialVariation =
            materialVariation - 0.5;


        // --------------------------------------------------
        // APPLICATION DE LA TEXTURE
        // --------------------------------------------------

        light *=
            1.0 +
            materialVariation *
            ${glassTextureStrength.toString()};


        // --------------------------------------------------
        // APPLICATION FINALE
        // --------------------------------------------------
        
        x_fill.rgb *= light;

        

    """.trimIndent()
        }



        extend {

            drawer.clear(backgroundColor)

            val deformedCells =
                voronoiCells.map { cell ->

                    deformCellVertices(
                        cell,
                        width.toDouble(),
                        height.toDouble(),
                        vertexCache,
                        deformationAmount
                    )
                }

            // ------------------------
            // REMPLISSAGE
            // ------------------------

            deformedCells.forEachIndexed { index, cell ->

                if (cell.size < 3) {
                    return@forEachIndexed
                }

                val contour =
                    createCurvedContour(
                        cell,
                        edgeCurveCache,
                        curvatureAmount
                    )

                // --------------------------------------------------
                // CENTRE RÉEL DE LA CELLULE
                // --------------------------------------------------

                val cellCenter =
                    polygonCentroid(cell)

                // Bounding box réel du contour
                val bounds =
                    contour.bounds

                // Conversion du centre réel de la cellule
                // vers les coordonnées normalisées du shader.
                val normalizedCellCenter = Vector2(
                    (
                            cellCenter.x - bounds.corner.x
                            ) / bounds.width,

                    (
                            cellCenter.y - bounds.corner.y
                            ) / bounds.height
                )

                // --------------------------------------------------
                // RENDU
                // --------------------------------------------------

                drawer.fill = cellColors[index]
                drawer.stroke = null

                glassShadeStyle.parameter(
                    "cellCenter",
                    normalizedCellCenter
                )

                drawer.shadeStyle = glassShadeStyle

                drawer.contour(contour)
            }

            // ------------------------
            // BORDURES
            // ------------------------

            drawCurvedVoronoiEdges(
                drawer,
                deformedCells,
                strokeColor,
                strokeWeight,
                edgeCurveCache,
                curvatureAmount
            )
        }
    }
}