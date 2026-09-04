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


// --------------------------------------------------
// DÉFORMATION DES SOMMETS
// --------------------------------------------------



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
        curvatureAmount * direction * 0.20

    val offset2 =
        curvatureAmount * direction * 0.20

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