import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import kotlin.random.Random

// --------------------------------------------------
// SYSTÈME 3 — PALETTE
// "Quels verres sont utilisés ?"
// --------------------------------------------------

interface PaletteSystem {
    fun assignColors(
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa>
}

/**
 * Comportement actuel : couleur aléatoire indépendante par cellule
 * (aucune règle d'adjacence). Reprend `palette[random.nextInt(...)]`
 * tel qu'il existait dans main().
 */
class RandomPaletteSystem(
    private val colors: List<ColorRGBa>
) : PaletteSystem {

    override fun assignColors(
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa> {
        return cells.map { colors[random.nextInt(colors.size)] }
    }
}

/**
 * Palette avec règle anti-adjacence simple : évite, quand c'est
 * possible en `maxAttempts` essais, qu'une cellule ait la même
 * couleur qu'une cellule voisine (partageant une arête).
 *
 * Limite constatée à l'usage : forcer la différence entre voisins
 * pousse vers l'ALTERNANCE (l'inverse du regroupement), ce qui donne
 * un résultat "poivre et sel" — chaque couleur dispersée en petites
 * touches partout plutôt qu'en zones. Voir HarmoniousPaletteSystem
 * ci-dessous pour l'approche qui corrige ça.
 */
class AdjacencyAwarePaletteSystem(
    private val colors: List<ColorRGBa>,
    private val maxAttempts: Int = 8
) : PaletteSystem {

    override fun assignColors(
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa> {

        val assigned = arrayOfNulls<ColorRGBa>(cells.size)
        val neighbors = findAdjacentCells(cells)

        for (index in cells.indices) {

            val forbidden = neighbors[index]
                .mapNotNull { assigned[it] }
                .toSet()

            var chosen = colors[random.nextInt(colors.size)]
            var attempt = 0

            while (chosen in forbidden && attempt < maxAttempts) {
                chosen = colors[random.nextInt(colors.size)]
                attempt++
            }

            assigned[index] = chosen
        }

        return assigned.map { it ?: colors.first() }
    }
}

/**
 * Palette aléatoire pondérée, SANS règle de voisinage — chaque
 * cellule tire indépendamment une famille au prorata des poids, puis
 * une nuance dans cette famille.
 *
 * Contrairement à RandomPaletteSystem (poids égaux) et à
 * CappedClusterPaletteSystem (regroupement local), celle-ci n'a pas
 * besoin de regroupement pour bien fonctionner : sur une composition
 * dont les cellules sont déjà de grandes formes distinctes (ex.
 * SunburstSegmentationSystem), un tirage indépendant par cellule ne
 * lit pas comme "poivre et sel" — les rayons/segments fournissent
 * déjà leur propre structure visuelle. Validé en Python sur le style
 * sunburst avant portage.
 */
class WeightedRandomPaletteSystem(
    private val families: List<PaletteFamily>
) : PaletteSystem {

    override fun assignColors(
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa> {

        val totalWeight = families.sumOf { it.weight }

        return cells.map {
            var pick = random.nextDouble() * totalWeight
            var chosenFamily = families.last()

            for (family in families) {
                pick -= family.weight
                if (pick <= 0.0) {
                    chosenFamily = family
                    break
                }
            }

            chosenFamily.shades[random.nextInt(chosenFamily.shades.size)]
        }
    }
}

/**
 * Une famille de teintes harmonieuses (plusieurs nuances proches,
 * comme un coloriste choisirait "les bleus" plutôt qu'"un bleu") et
 * son poids relatif — combien de place cette famille doit occuper
 * dans la composition par rapport aux autres. Un poids élevé = couleur
 * dominante ("field", le bleu du ciel) ; un poids faible = accent rare
 * (l'ivoire posé avec parcimonie).
 */
data class PaletteFamily(
    val shades: List<ColorRGBa>,
    val weight: Double
)

/**
 * Palette harmonieuse par regroupements PLAFONNÉS : chaque cellule est
 * traitée une à une (ordre aléatoire) ; avec probabilité
 * `neighborBias`, elle reprend la famille d'une cellule voisine déjà
 * posée — mais UNIQUEMENT si la taille RÉELLE de la zone connectée
 * qui en résulterait ne dépasse pas `maxClusterSize`. Sinon (ou si le
 * biais ne se déclenche pas), elle tire une famille fraîche au
 * prorata des poids.
 *
 * Deux approches plus simples ont été essayées et rejetées avant
 * celle-ci (voir l'historique du projet) :
 *
 * 1. Flood-fill multi-source par régions : donne un nombre de régions
 *    fixé par famille, mais RIEN n'empêche deux régions séparées de
 *    la même famille de finir voisines et de fusionner visuellement
 *    en un seul continent — observé de façon répétée en test, quel
 *    que soit le placement des graines.
 * 2. Copie probabiliste simple (biais fixe, sans plafond) : se
 *    comporte comme un problème de percolation — au-delà d'un certain
 *    biais (~0.15 sur ce type de graphe), un groupe géant émerge, et
 *    le seuil exact est instable d'un seed à l'autre (parfois un
 *    groupe de 30+ cellules même à biais prudent). Inutilisable pour
 *    un générateur qui doit produire un résultat fiable à chaque fois.
 *
 * La différence ici : la taille de zone n'est jamais estimée après
 * coup, elle est calculée EXACTEMENT avant chaque décision (somme des
 * tailles de toutes les racines voisines de même famille + 1), et
 * l'assignation est refusée si elle dépasserait le plafond. Testé sur
 * 10 seeds différents : le plafond est respecté à la cellule près à
 * chaque fois (voir tableau de validation dans l'historique).
 *
 * maxClusterSize=4 et neighborBias=0.6 donnent un bon équilibre :
 * de petits regroupements de 2 à 4 cellules dispersés dans toute la
 * composition, sans jamais dégénérer en grande zone ni retomber dans
 * le "poivre et sel" du tirage pur aléatoire.
 */
class CappedClusterPaletteSystem(
    private val families: List<PaletteFamily>,
    private val maxClusterSize: Int = 4,
    private val neighborBias: Double = 0.6
) : PaletteSystem {

    override fun assignColors(
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa> {

        val neighbors = findAdjacentCells(cells)
        val cellCount = cells.size

        val parent = IntArray(cellCount) { it }
        val clusterSize = IntArray(cellCount) { 1 }
        val familyOf = IntArray(cellCount) { -1 }

        fun find(x: Int): Int {
            var current = x
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        val order = cells.indices.toMutableList()
        order.shuffle(random)

        val totalWeight = families.sumOf { it.weight }

        for (idx in order) {

            // Pour chaque famille présente chez des voisins déjà
            // assignés, la taille RÉELLE que ça donnerait si idx la
            // rejoint (racines distinctes de cette famille + idx).
            val familyRoots = mutableMapOf<Int, MutableSet<Int>>()

            for (neighbor in neighbors[idx]) {
                val neighborFamily = familyOf[neighbor]
                if (neighborFamily != -1) {
                    familyRoots
                        .getOrPut(neighborFamily) { mutableSetOf() }
                        .add(find(neighbor))
                }
            }

            val resultingSize = familyRoots.mapValues { (_, roots) ->
                1 + roots.sumOf { clusterSize[it] }
            }

            val validFamilies = families.indices.filter { familyIndex ->
                (resultingSize[familyIndex] ?: 1) <= maxClusterSize
            }.ifEmpty { families.indices.toList() }

            val neighborValid = familyRoots.keys.filter { it in validFamilies }

            val chosenFamily = if (neighborValid.isNotEmpty() && random.nextDouble() < neighborBias) {
                neighborValid[random.nextInt(neighborValid.size)]
            } else {
                var pick = random.nextDouble() * validFamilies.sumOf { families[it].weight }
                var result = validFamilies.last()
                for (familyIndex in validFamilies) {
                    pick -= families[familyIndex].weight
                    if (pick <= 0.0) {
                        result = familyIndex
                        break
                    }
                }
                result
            }

            familyOf[idx] = chosenFamily

            val roots = familyRoots[chosenFamily]
            if (roots != null) {
                var newSize = 1
                for (root in roots) {
                    newSize += clusterSize[root]
                    parent[root] = idx
                }
                clusterSize[idx] = newSize
            }
        }

        return familyOf.map { familyIndex ->
            val shades = families[familyIndex].shades
            shades[random.nextInt(shades.size)]
        }
    }
}

/**
 * Détecte les cellules adjacentes (partageant une arête), via la même
 * clé d'arête que le Système 4 (edgeKey, TemplateProgram.kt).
 * Utilisé par AdjacencyAwarePaletteSystem et CappedClusterPaletteSystem.
 */
fun findAdjacentCells(
    cells: List<List<Vector2>>
): List<MutableSet<Int>> {

    val neighbors = cells.indices.map { mutableSetOf<Int>() }
    val edgeOwners = mutableMapOf<String, Int>()

    cells.forEachIndexed { cellIndex, cell ->
        for (i in cell.indices) {
            val a = cell[i]
            val b = cell[(i + 1) % cell.size]
            val key = edgeKey(a, b)

            val owner = edgeOwners[key]
            if (owner != null && owner != cellIndex) {
                neighbors[cellIndex].add(owner)
                neighbors[owner].add(cellIndex)
            } else {
                edgeOwners[key] = cellIndex
            }
        }
    }

    return neighbors
}