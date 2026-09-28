import org.openrndr.math.Vector2
import kotlin.random.Random

// --------------------------------------------------
// GÉNÉRATION D'UNE TRAME ORGANIQUE
// --------------------------------------------------

fun generateOrganicFreeform(
    width: Double,
    height: Double,
    targetRegions: Int,
    random: Random
): List<List<Vector2>> {

    // --------------------------------------------------
    // DIMENSIONS DE LA TRAME
    // --------------------------------------------------

    val columns =
        when {
            targetRegions <= 20 -> 4
            targetRegions <= 30 -> 5
            else -> 6
        }

    val rows =
        when {
            targetRegions <= 20 -> 4
            targetRegions <= 30 -> 5
            else -> 6
        }

    // --------------------------------------------------
    // POINTS DE LA TRAME
    // --------------------------------------------------

    val points =
        Array(rows + 1) {
            Array(columns + 1) {
                Vector2(0.0, 0.0)
            }
        }

    for (row in 0..rows) {

        for (column in 0..columns) {

            val normalizedX =
                column.toDouble() / columns

            val normalizedY =
                row.toDouble() / rows

            // Les bords restent parfaitement sur le cadre.
            val isBorder =
                column == 0 ||
                        column == columns ||
                        row == 0 ||
                        row == rows

            if (isBorder) {

                points[row][column] =
                    Vector2(
                        normalizedX * width,
                        normalizedY * height
                    )

            } else {

                // --------------------------------------------------
                // JITTER ORGANIQUE
                // --------------------------------------------------

                val cellWidth =
                    width / columns

                val cellHeight =
                    height / rows

                val jitterX =
                    cellWidth *
                            random.nextDouble(
                                -0.22,
                                0.22
                            )

                val jitterY =
                    cellHeight *
                            random.nextDouble(
                                -0.22,
                                0.22
                            )

                points[row][column] =
                    Vector2(
                        normalizedX * width +
                                jitterX,

                        normalizedY * height +
                                jitterY
                    )
            }
        }
    }

    // --------------------------------------------------
    // CELLULES
    // --------------------------------------------------

    val cells =
        mutableListOf<List<Vector2>>()

    for (row in 0 until rows) {

        for (column in 0 until columns) {

            val topLeft =
                points[row][column]

            val topRight =
                points[row][column + 1]

            val bottomRight =
                points[row + 1][column + 1]

            val bottomLeft =
                points[row + 1][column]

            cells.add(
                listOf(
                    topLeft,
                    topRight,
                    bottomRight,
                    bottomLeft
                )
            )
        }
    }

    return cells
}