import org.openrndr.math.Vector2

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