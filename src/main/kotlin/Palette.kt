import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import kotlin.math.sqrt
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
 * Système de palette qui a besoin de la COMPOSITION (son centre, ses
 * rayons…) et pas seulement des cellules. computeVitrail (Renderer.kt)
 * appelle cette version-ci quand le système l'implémente ; tous les autres
 * systèmes gardent assignColors(cells, random), sans aucun changement.
 */
interface GuidedPaletteSystem : PaletteSystem {
    fun assignColors(
        guide: CompositionGuide,
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa>
}

/**
 * Répartition RADIALE d'une palette « blanc lumineux » (LuminousScheme) :
 * le poids de chaque famille dépend de la distance de la pièce au centre de
 * l'emblème. Les ocres restent près du centre (la gloire qui découpe la
 * silhouette de la croix), les blancs chauds et froids vont vers
 * l'extérieur (rayons lointains). Voir LuminousScheme pour les réglages.
 *
 * - Croix rayonnante et croix dessinées : t = distance / longueur maximale
 *   des rayons (0 au centre, 1 au bout des rayons les plus longs). Chaque
 *   croix a sa table (LuminousScheme : crossRadial pour la croix rayonnante
 *   et la tréflée, celticRadial pour la celtique, radial sinon).
 * - Ange rayonnant : les rayons de lumière derrière l'ange restent ocres
 *   (angelRayFamilies) ; la robe, quand elle prend les couleurs de la
 *   palette, suit la répartition radiale sur sa longueur.
 * - Toute autre composition : tirage pondéré ordinaire sur les familles de
 *   référence de la palette (comme WeightedRandomPaletteSystem).
 *
 * Deux tirages par cellule, comme WeightedRandomPaletteSystem (famille,
 * puis nuance). La distance relative est arrondie à 1e-6 avant le calcul
 * des poids : l'aperçu et l'export, calculés à des tailles différentes,
 * tirent ainsi exactement les mêmes couleurs.
 */
class RadialLuminousPaletteSystem(
    private val palette: LiturgicalPalette
) : GuidedPaletteSystem {

    private val scheme: LuminousScheme = requireNotNull(palette.luminous) {
        "RadialLuminousPaletteSystem : la palette ${palette.name} n'a pas de LuminousScheme"
    }

    /** Sans composition connue : tirage pondéré ordinaire. */
    override fun assignColors(cells: List<List<Vector2>>, random: Random): List<ColorRGBa> =
        WeightedRandomPaletteSystem(palette.families).assignColors(cells, random)

    override fun assignColors(
        guide: CompositionGuide,
        cells: List<List<Vector2>>,
        random: Random
    ): List<ColorRGBa> {
        return when (guide) {
            is CompositionGuide.RadiantCross ->
                radial(cells, guide.center, guide.maxRadius * guide.lightLengthMaxRatio, scheme.crossRadial, random)
            is ShapedCrossGuide ->
                if (guide.center != null && guide.rayRadius > 0.0) {
                    val table = when (guide.style) {
                        ShapedCrossStyle.TREFOIL -> scheme.crossRadial
                        ShapedCrossStyle.CELTIC -> scheme.celticRadial
                        else -> scheme.radial
                    }
                    radial(cells, guide.center, guide.rayRadius, table, random)
                } else assignColors(cells, random)
            is CompositionGuide.RadiantAngel -> {
                val body = guide.bodyWindow
                val bodyLength = guide.maxRadius * body.lengthRatio
                cells.map { cell ->
                    val g = polygonCentroid(cell)
                    val dx = g.x - guide.center.x
                    val dy = g.y - guide.center.y
                    val angle = Math.toDegrees(kotlin.math.atan2(dy, dx)).let { if (it < 0.0) it + 360.0 else it } % 360.0
                    if (angularDistanceDegrees(angle, body.centerDegrees) <= body.halfWidthDegrees) {
                        pickWeighted(scheme.radial.familiesAt(roundedRatio(sqrt(dx * dx + dy * dy), bodyLength)), random)
                    } else {
                        pickWeighted(scheme.angelRayFamilies, random)
                    }
                }
            }
            else -> assignColors(cells, random)
        }
    }

    private fun radial(cells: List<List<Vector2>>, center: Vector2, reference: Double, table: RadialTable, random: Random): List<ColorRGBa> =
        cells.map { cell ->
            val g = polygonCentroid(cell)
            val dx = g.x - center.x
            val dy = g.y - center.y
            pickWeighted(table.familiesAt(roundedRatio(sqrt(dx * dx + dy * dy), reference)), random)
        }

    private fun roundedRatio(d: Double, reference: Double): Double =
        Math.round(d / reference * 1e6) / 1e6

    private fun pickWeighted(families: List<PaletteFamily>, random: Random): ColorRGBa {
        val total = families.sumOf { it.weight }
        var pick = random.nextDouble() * total
        var chosen = families.last()
        for (family in families) {
            pick -= family.weight
            if (pick <= 0.0) {
                chosen = family
                break
            }
        }
        return chosen.shades[random.nextInt(chosen.shades.size)]
    }
}

/**
 * Le système de palette des RAYONS pour une palette liturgique :
 * - palette « blanc lumineux » (luminous non nul, Noël/Pâques) :
 *   RadialLuminousPaletteSystem — ocres au centre, blancs vers l'extérieur ;
 *   la baie n'intervient pas (les blancs des rayons et la vitrerie nacrée se
 *   distinguent par le contraste chaud/froid et par les plombs) ;
 * - toutes les autres : tirage pondéré sur les familles de la palette, ou,
 *   dans une baie, sur les seules familles lisibles sur son fond
 *   (BayStyle.raysFamiliesFor) — exactement comme avant.
 */
fun LiturgicalPalette.raysPaletteSystem(bay: BayStyle? = null): PaletteSystem =
    if (luminous != null) RadialLuminousPaletteSystem(this)
    else WeightedRandomPaletteSystem(bay?.raysFamiliesFor(families) ?: families)

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
 * clé d'arête que le Système 4 (edgeKey, EdgeCurves.kt).
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

// --------------------------------------------------
// POST-TRAITEMENT PROPRE À LA CROIX RAYONNANTE
// --------------------------------------------------

/**
 * Force les couleurs des éléments structurels de la croix rayonnante,
 * APRÈS le tirage normal du PaletteSystem : les bras vers armShades
 * (blanc cassé, constant selon les saisons), le médaillon vers
 * medallionShades (crème ou "cœur sombre réservé" de la saison, selon
 * MedallionMode). Sans ce forçage, un bras ou le médaillon pourrait
 * piocher au hasard la même famille que les rayons et se fondre dans
 * le décor.
 *
 * Ce n'est pas un PaletteSystem générique : c'est une signature
 * visuelle propre à ce style de composition. Elle modifie cellColors
 * sur place.
 *
 * ⚠ Reproductibilité : cette fonction consomme des tirages de random
 * (une nuance par cellule de bras ou de médaillon). Elle doit rester
 * appelée au même moment du pipeline — juste après
 * paletteSystem.assignColors() et avant
 * glassSystem.assignMaterialVariation() — sinon un même seed ne
 * produira plus la même œuvre.
 *
 * Classification : une cellule est dans le médaillon si son centroïde
 * est à moins de 1.05 × coreRadius du centre ; sinon, elle est dans un
 * bras si l'angle de son centroïde tombe dans une ArmWindow
 * (classifyAngle, Segmentation.kt).
 */
fun applyRadiantCrossColors(
    guide: CompositionGuide.RadiantCross,
    cells: List<List<Vector2>>,
    cellColors: MutableList<ColorRGBa>,
    armShades: List<ColorRGBa>,
    medallionShades: List<ColorRGBa>,
    random: Random
) {

    val coreRadius = guide.maxRadius * guide.coreRadiusRatio

    cells.forEachIndexed { index, cell ->

        val centroid = polygonCentroid(cell)

        val dx = centroid.x - guide.center.x
        val dy = centroid.y - guide.center.y
        val distanceFromCenter = sqrt(dx * dx + dy * dy)

        val isMedallion = distanceFromCenter < coreRadius * 1.05

        if (isMedallion) {
            cellColors[index] = medallionShades[random.nextInt(medallionShades.size)]
        } else {
            val angleDegrees = Math.toDegrees(kotlin.math.atan2(dy, dx)).let {
                if (it < 0.0) it + 360.0 else it
            } % 360.0
            val isArm = classifyAngle(angleDegrees, guide.armWindows) != null

            if (isArm) {
                cellColors[index] = armShades[random.nextInt(armShades.size)]
            }
        }
    }
}

// --------------------------------------------------
// POST-TRAITEMENT PROPRE À L'ANGE RAYONNANT
// --------------------------------------------------

/**
 * Équivalent de applyRadiantCrossColors pour l'ange rayonnant : force,
 * APRÈS le tirage normal du PaletteSystem, les couleurs des éléments
 * de la figure. Les rayons de lumière gardent la couleur tirée par le
 * PaletteSystem.
 *
 * - médaillon de poitrine → chestShades (sombre, pour qu'il ne se lise
 *   pas comme une tête) ;
 * - tête → headShades ; nimbe → haloShades ;
 * - cou et robe → bodyShades (la robe garde les couleurs de la palette
 *   liturgique si guide.robeUsesPalette) ;
 * - ailes → wingShades.
 *
 * Classification (même principe que pour la croix) : centroïde à moins
 * de 1.05 × coreRadius de la poitrine → médaillon ; sinon, selon
 * l'angle du centroïde :
 * - secteur de la tête : cou si le centroïde est avant le bout du cou,
 *   tête s'il est tout près du centre du disque, nimbe sinon (voir
 *   angelHeadGeometry, Segmentation.kt) ;
 * - secteur de la robe → robe ; secteur d'une aile → aile.
 * Les limites des rayons de l'ange coïncidant exactement avec ces
 * secteurs (voir generateAngelRays, Segmentation.kt), aucune cellule
 * n'est à cheval.
 *
 * ⚠ Reproductibilité : consomme des tirages de random (une nuance par
 * cellule forcée). À appeler au même endroit du pipeline que
 * applyRadiantCrossColors (voir Renderer.kt).
 */
fun applyRadiantAngelColors(
    guide: CompositionGuide.RadiantAngel,
    cells: List<List<Vector2>>,
    cellColors: MutableList<ColorRGBa>,
    chestShades: List<ColorRGBa>,
    headShades: List<ColorRGBa>,
    haloShades: List<ColorRGBa>,
    bodyShades: List<ColorRGBa>,
    wingShades: List<ColorRGBa>,
    random: Random
) {

    val coreRadius = guide.maxRadius * guide.coreRadiusRatio
    val head = angelHeadGeometry(guide)

    fun pick(shades: List<ColorRGBa>) = shades[random.nextInt(shades.size)]

    cells.forEachIndexed { index, cell ->

        val centroid = polygonCentroid(cell)

        val dx = centroid.x - guide.center.x
        val dy = centroid.y - guide.center.y
        val distanceFromCenter = sqrt(dx * dx + dy * dy)

        if (distanceFromCenter < coreRadius * 1.05) {
            cellColors[index] = pick(chestShades)
            return@forEachIndexed
        }

        val angleDegrees = Math.toDegrees(kotlin.math.atan2(dy, dx)).let {
            if (it < 0.0) it + 360.0 else it
        } % 360.0

        fun inWindow(centerDegrees: Double, halfWidthDegrees: Double) =
            angularDistanceDegrees(angleDegrees, centerDegrees) <= halfWidthDegrees

        val headWindow = guide.headWindow
        val body = guide.bodyWindow

        when {
            inWindow(headWindow.centerDegrees, headWindow.halfWidthDegrees) -> {
                val hx = centroid.x - head.headCenter.x
                val hy = centroid.y - head.headCenter.y
                val distanceFromHead = sqrt(hx * hx + hy * hy)
                cellColors[index] = when {
                    distanceFromCenter < head.neckRadius -> pick(bodyShades)
                    distanceFromHead < head.headRadius * 0.5 -> pick(headShades)
                    else -> pick(haloShades)
                }
            }
            inWindow(body.centerDegrees, body.halfWidthDegrees) -> {
                // Variante « robe liturgique » : la robe garde la couleur
                // tirée par le PaletteSystem (couleurs de la saison).
                if (!guide.robeUsesPalette) {
                    cellColors[index] = pick(bodyShades)
                }
            }
            guide.wingWindows.any { inWindow(it.centerDegrees, it.halfWidthDegrees) } -> {
                cellColors[index] = pick(wingShades)
            }
        }
    }
}


// --------------------------------------------------
// PALETTES LITURGIQUES (données)
// "Quelles palettes existent ?"
// --------------------------------------------------
//
// Cette seconde partie du fichier ne contient QUE des données : les
// palettes liturgiques et les teintes réservées à la croix
// rayonnante. Les algorithmes qui répartissent ces couleurs sur les
// cellules se trouvent dans la première partie, ci-dessus.
//
// Palettes reprises fidèlement des documents de référence (objets
// LiturgicalPaletteXxx, eux-mêmes référencés à la PGMR n.346) plutôt
// qu'improvisées — chaque teinte documentée devient sa propre
// PaletteFamily à une seule nuance, avec le pourcentage documenté
// comme poids exact.
//
// Chaque saison a aussi sa propre teinte de MÉDAILLON, reprise du
// "cœur sombre réservé" de son document ("hors tirage aléatoire du
// cluster system" — cœur de médaillon, jonctions de rayons). Trois
// options ont été comparées visuellement avant de choisir ce
// traitement : médaillon toujours crème (comme les bras) ; médaillon
// en cœur sombre réservé ; accent "glow" rare dans les rayons. Le cœur
// sombre a été retenu comme option saisonnière — il fait nettement
// mieux ressortir la croix, et résout au passage un souci de contraste
// sur Noël (bras crème qui se fondaient dans un champ blanc/or trop
// pâle : le médaillon sombre donne un point d'ancrage fort quelle que
// soit la palette). Voir MedallionMode ci-dessous pour le choix entre
// les deux lectures.
//
// Ordre de mise en place historique : Temps ordinaire, puis Noël,
// puis Avent — les quatre autres (rouge, noire, rose, marial) ajoutées
// ensuite, une fois le principe validé sur les trois premières.

/**
 * Une palette liturgique complète : les familles tirées au hasard
 * pour les rayons/cellules, et la teinte SOMBRE réservée au médaillon
 * lorsque MedallionMode.SEASONAL est choisi.
 *
 * Regrouper les deux dans un même objet évite l'erreur possible
 * lorsqu'on change de saison : autrefois il fallait modifier deux
 * lignes distinctes (palette de rayons + médaillon) et veiller à les
 * faire correspondre.
 */
data class LiturgicalPalette(
    val name: String,
    val families: List<PaletteFamily>,
    val seasonalMedallionShades: List<ColorRGBa>,
    /**
     * Répartition « blanc lumineux » (Noël/Pâques) : ocres au centre, blancs
     * vers l'extérieur — voir LuminousScheme. null pour toutes les autres
     * palettes, qui gardent le tirage pondéré ordinaire.
     */
    val luminous: LuminousScheme? = null
)

/**
 * Répartition radiale d'une palette : les poids de chaque famille à
 * quelques positions t (0 = centre de l'emblème, 1 = bout des rayons les
 * plus longs), interpolés linéairement entre deux positions.
 *
 * - knots : positions t, croissantes ;
 * - families : nuances de chaque famille et son poids à chaque position.
 */
data class RadialTable(
    val knots: List<Double>,
    val families: List<Pair<List<ColorRGBa>, List<Double>>>
) {
    init {
        require(knots.size >= 2 && knots.zipWithNext().all { (a, b) -> a < b }) { "RadialTable : knots croissants requis" }
        require(families.all { it.second.size == knots.size }) { "RadialTable : un poids par position de knots" }
    }

    /** Familles et poids à la position radiale t (bornée à [premier, dernier] knot). */
    fun familiesAt(t: Double): List<PaletteFamily> {
        val x = t.coerceIn(knots.first(), knots.last())
        var i = 0
        while (i < knots.size - 2 && x > knots[i + 1]) i++
        val u = (x - knots[i]) / (knots[i + 1] - knots[i])
        return families
            .map { (shades, w) -> PaletteFamily(shades, w[i] + (w[i + 1] - w[i]) * u) }
            .filter { it.weight > 0.0 }
    }
}

/**
 * Palette « blanc lumineux » : rien de brillant ni de doré à l'impression,
 * le blanc reste la couleur de référence, et l'OCRE n'est placé que là où il
 * découpe la silhouette (gloire autour de la croix ou de l'ange, nimbe,
 * bordure de la baie). Les blancs chauds et froids mêlés vers l'extérieur se
 * distinguent de l'ivoire des bras par le contraste chaud/froid ; les plombs
 * noirs font le reste. L'anneau de la croix celtique garde l'or vif commun à
 * toutes les palettes (BayStyle.BORDER_GOLD), pour la cohérence des designs.
 *
 * - radial : table radiale de référence (robe de l'ange, croix
 *   rondouillarde à rayons…) ;
 * - crossRadial : croix rayonnante et croix tréflée (zone ocre un peu plus
 *   large, pour mieux détacher la croix) ;
 * - celticRadial : croix celtique (par défaut, la table de référence) ;
 * - angelRayFamilies : rayons de lumière derrière l'ange (restent ocres) ;
 * - ochre : accents ocres (bordure de la baie, cœur des motifs de la croix
 *   rondouillarde) ;
 * - white : blancs de la bordure, alternés avec l'ocre ;
 * - quarries : losanges de la baie (nacre et champagne) ;
 * - gradient : dégradé des motifs de la croix rondouillarde, du centre
 *   (ocre) aux bords (blancs).
 */
data class LuminousScheme(
    val radial: RadialTable,
    val crossRadial: RadialTable = radial,
    val celticRadial: RadialTable = radial,
    val angelRayFamilies: List<PaletteFamily>,
    val ochre: List<ColorRGBa>,
    val white: List<ColorRGBa>,
    val quarries: List<ColorRGBa>,
    val gradient: List<ColorRGBa>
)

/**
 * Teinte du médaillon central de la croix rayonnante.
 *
 * - HOST : le médaillon reste TOUJOURS crème, comme les bras, quelle
 *   que soit la saison. Lecture liturgique proposée par Astrea : le
 *   disque blanc central évoque l'hostie — cohérent avec le
 *   Saint-Sacrement quelle que soit la période de l'année, pas
 *   seulement une question de contraste. [réglage actuel]
 *
 * - SEASONAL : le médaillon prend le "cœur sombre réservé" propre à la
 *   saison. Fait ressortir la croix un peu plus nettement dans la
 *   comparaison visuelle qui a précédé ce choix — mais moins cohérent
 *   avec la lecture "hostie", puisque le médaillon change alors de
 *   couleur avec la saison.
 *
 * - PALETTE : le médaillon prend la couleur principale de la saison
 *   (voir coloredMainShades : vert du Temps ordinaire, violet de l'Avent,
 *   framboise douce de Gaudete, ocre de Noël, gris ardoise de la palette noire…),
 *   pendant que les bras restent blancs (croix de lumière). Choisi pour
 *   les baies sur vitrerie claire (Bay.kt).
 */
enum class MedallionMode {
    HOST,
    SEASONAL,
    PALETTE
}

/** Les teintes du médaillon selon le mode choisi. */
fun LiturgicalPalette.medallionShades(mode: MedallionMode): List<ColorRGBa> {
    return when (mode) {
        MedallionMode.HOST -> LiturgicalPalettes.CROSS_ARM_SHADES
        MedallionMode.SEASONAL -> seasonalMedallionShades
        MedallionMode.PALETTE -> coloredMainShades()
    }
}

/**
 * La couleur principale « lisible » d'une palette : la famille la plus
 * pesante dont la luminance est comprise entre 0,20 et 0,75, c'est-à-dire
 * ni presque blanche (le blanc de Noël) ni presque noire (l'anthracite de
 * la palette noire). Ordinaire → #3C7A4E, Avent → #5B3168, Rose → #D47A93,
 * Noël → #D9A441, Rouge → #A6242E, Marial → #2E5C9A, Noire → #57534C.
 */
fun LiturgicalPalette.coloredMainShades(): List<ColorRGBa> {
    fun lum(c: ColorRGBa): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b
    val readable = families.filter { f -> f.shades.map { c -> lum(c) }.average() in 0.20..0.75 }
    return (readable.ifEmpty { families }).maxByOrNull { it.weight }!!.shades
}

object LiturgicalPalettes {

    /**
     * Nuances proches de blanc cassé pour les BRAS de la croix —
     * constante visuelle à travers toutes les saisons liturgiques
     * (contrairement au médaillon saisonnier, qui varie). Légère
     * variation entre les triangles/segments plutôt qu'une teinte plate
     * identique à chaque génération.
     */
    val CROSS_ARM_SHADES = listOf(
        ColorRGBa.fromHex("#F2E8CE"), // blanc cassé
        ColorRGBa.fromHex("#F7EFDD"), // ivoire plus clair
        ColorRGBa.fromHex("#EBE0C2")  // ivoire plus soutenu
    )

    /**
     * Nuances de la ROBE de l'ange rayonnant : ivoire chaud à or pâle,
     * un ton plus doré que les ailes pour que le corps se distingue des
     * ailes. Constantes à travers les saisons, comme les bras de la
     * croix. Réglage de départ, à ajuster à l'œil.
     */
    val ANGEL_ROBE_SHADES = listOf(
        ColorRGBa.fromHex("#F0E2BC"), // ivoire doré
        ColorRGBa.fromHex("#E6D29E"), // paille claire
        ColorRGBa.fromHex("#DCC282")  // or pâle
    )

    /**
     * Nuances des AILES de l'ange rayonnant : blanc cassé, les mêmes
     * que les bras de la croix.
     */
    val ANGEL_WING_SHADES = CROSS_ARM_SHADES

    /** Nuances de la TÊTE de l'ange rayonnant : blanc cassé, comme les ailes. */
    val ANGEL_HEAD_SHADES = CROSS_ARM_SHADES

    /**
     * Nuances du NIMBE de l'ange rayonnant : or franc, pour détacher la
     * tête des ailes blanches. Réglage de départ, à ajuster à l'œil.
     */
    val ANGEL_HALO_SHADES = listOf(
        ColorRGBa.fromHex("#D9A441"), // or de référence (commun à plusieurs palettes)
        ColorRGBa.fromHex("#E0B84F"), // or jaune
        ColorRGBa.fromHex("#C99A2E")  // or soutenu
    )

    /** Vert — Temps ordinaire (LiturgicalPaletteGreen). */
    val ORDINAIRE = LiturgicalPalette(
        name = "Vert — Temps ordinaire",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#1F4D33")), 30.0), // greenDeep — ancrage
            PaletteFamily(listOf(ColorRGBa.fromHex("#3C7A4E")), 35.0), // greenLeaf — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#7FB069")), 13.0), // greenSoft — transmission lumineuse
            PaletteFamily(listOf(ColorRGBa.fromHex("#C9DDB0")), 7.0),  // greenPale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 7.0),  // amberGold — reflets
            PaletteFamily(listOf(ColorRGBa.fromHex("#A9722A")), 3.0),  // amberBurnt — contraste chaud
            PaletteFamily(listOf(ColorRGBa.fromHex("#8C4A32")), 4.0)   // rust — touche isolée (doc. ~3-5%)
        ),
        // nearBlackGreen — cœur de médaillon, jonctions de rayons.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#14231A"))
    )

    /**
     * Blanc lumineux — Noël/Pâques (oct. 2026). Remplace la palette
     * « blanc et or » (LiturgicalPaletteWhiteGold) : rien ne s'imprime en
     * doré (ni brillant ni pailleté), et l'ocre, trop présent, faisait lire
     * les œuvres comme « jaunes » alors que le BLANC est la couleur
     * liturgique de référence de ces temps. Le problème n'était pas l'ocre
     * lui-même mais sa place : il reste près du centre (gloire autour de la
     * croix ou de l'ange, nimbe), là où il découpe la
     * silhouette ; les blancs chauds et froids vont vers l'extérieur (rayons
     * lointains, baie en nacre et champagne). Teintes et parts indicatives
     * proposées par Astrea. Le bleu-gris très pâle (#D7E0E6) d'origine a
     * été remplacé partout par un ocre très pâle (#F4E3BD), à sa demande.
     *
     * Les familles ci-dessous (avec leur part indicative) servent de
     * référence et de repli ; la répartition réelle des rayons est radiale
     * (luminous, RadialLuminousPaletteSystem).
     *
     * Ancienne palette, pour mémoire : ivory #F2ECD9 (22), pearl #FAF6EC
     * (15), goldLight #F0C674 (15), gold #D9A441 (20), champagne #E5D8B8
     * (10), goldAntique #9C6F24 (10).
     */
    private val LYS = ColorRGBa.fromHex("#FBF8F1")          // blanc lys
    private val NACRE = ColorRGBa.fromHex("#F4F1EA")        // nacre
    private val NACRE_FROIDE = ColorRGBa.fromHex("#E9EEF1") // nacre froide
    private val CHAMPAGNE = ColorRGBa.fromHex("#ECE0C6")    // champagne
    private val OCRE_PALE = ColorRGBa.fromHex("#EFD49A")    // ocre pâle
    private val OCRE = ColorRGBa.fromHex("#D9A441")         // ocre, près du centre
    // Ocre très pâle, à la place du bleu-gris très pâle #D7E0E6 : même
    // teinte que l'ocre pâle, plus clair (luminosité HSL 0,85 au lieu de
    // 0,77), et plus jaune que le champagne.
    private val OCRE_TRES_PALE = ColorRGBa.fromHex("#F4E3BD")

    /** Croix rayonnante et croix tréflée : zone ocre élargie. */
    private val CROSS_RADIAL = RadialTable(
        knots = listOf(0.0, 0.35, 0.55, 0.75, 1.0),
        families = listOf(
            listOf(OCRE) to listOf(60.0, 38.0, 12.0, 2.0, 0.0),
            listOf(OCRE_PALE) to listOf(32.0, 34.0, 22.0, 6.0, 0.0),
            listOf(CHAMPAGNE) to listOf(8.0, 14.0, 16.0, 9.0, 4.0),
            listOf(LYS) to listOf(0.0, 7.0, 20.0, 28.0, 30.0),
            listOf(NACRE) to listOf(0.0, 4.0, 15.0, 23.0, 25.0),
            listOf(NACRE_FROIDE) to listOf(0.0, 3.0, 10.0, 19.0, 24.0),
            listOf(OCRE_TRES_PALE) to listOf(0.0, 0.0, 5.0, 13.0, 16.0)
        )
    )

    val NOEL = LiturgicalPalette(
        name = "Blanc lumineux — Noël/Pâques",
        families = listOf(
            PaletteFamily(listOf(LYS), 25.0),
            PaletteFamily(listOf(NACRE), 20.0),
            PaletteFamily(listOf(NACRE_FROIDE), 15.0),
            PaletteFamily(listOf(OCRE_TRES_PALE), 10.0),
            PaletteFamily(listOf(CHAMPAGNE), 10.0),
            PaletteFamily(listOf(OCRE_PALE), 12.0),
            PaletteFamily(listOf(OCRE), 8.0)
        ),
        // Ocre foncé — cœur réservé (poitrine de l'ange ; médaillon de la
        // croix en MedallionMode.SEASONAL) : la teinte de l'ocre (#D9A441),
        // assombrie (luminosité HSL 0,36). Remplace le brun darkBronze
        // #4A3312 (oct. 2026) : élément chaud le plus sombre de l'image, au
        // cœur de l'ange, il risquait de se lire comme un « trou » en grand
        // format. Plus sombre que la tête et le nimbe, il ne se confond pas
        // avec eux. Autres teintes comparées : brun plus clair #7B551E, ocre
        // foncé plus clair #A87D2E.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#906C28")),
        luminous = LuminousScheme(
            // t : 0 = centre, 1 = bout des rayons les plus longs.
            // Table de référence : poids réglés pour retrouver, en surface,
            // les parts indicatives ci-dessus sur la croix rayonnante, avec les
            // ocres au centre. Sert à la croix celtique, à la robe de l'ange
            // et aux autres croix à rayons.
            radial = RadialTable(
                knots = listOf(0.0, 0.3, 0.5, 0.7, 1.0),
                families = listOf(
                    listOf(OCRE) to listOf(60.0, 30.0, 4.0, 0.0, 0.0),
                    listOf(OCRE_PALE) to listOf(32.0, 34.0, 15.0, 3.0, 0.0),
                    listOf(CHAMPAGNE) to listOf(8.0, 15.0, 14.0, 8.0, 4.0),
                    listOf(LYS) to listOf(0.0, 10.0, 26.0, 30.0, 30.0),
                    listOf(NACRE) to listOf(0.0, 6.0, 20.0, 24.0, 25.0),
                    listOf(NACRE_FROIDE) to listOf(0.0, 4.0, 12.0, 20.0, 24.0),
                    listOf(OCRE_TRES_PALE) to listOf(0.0, 0.0, 8.0, 14.0, 16.0)
                )
            ),
            // Croix rayonnante et croix tréflée : zone ocre élargie, pour
            // mieux faire ressortir la croix (CROSS_RADIAL, plus haut).
            crossRadial = CROSS_RADIAL,
            angelRayFamilies = listOf(
                PaletteFamily(listOf(OCRE), 55.0),
                PaletteFamily(listOf(OCRE_PALE), 35.0),
                PaletteFamily(listOf(CHAMPAGNE), 10.0)
            ),
            ochre = listOf(OCRE),
            white = listOf(LYS, NACRE),
            quarries = listOf(NACRE, CHAMPAGNE, NACRE_FROIDE),
            gradient = listOf(OCRE, OCRE_PALE, CHAMPAGNE, NACRE, NACRE_FROIDE)
        )
    )

    /** Violet — Avent/Carême (LiturgicalPaletteViolet). */
    val AVENT = LiturgicalPalette(
        name = "Violet — Avent/Carême",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#3D2645")), 20.0), // violetDeep — ancrage, pénitence
            PaletteFamily(listOf(ColorRGBa.fromHex("#5B3168")), 32.0), // violetBishop — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#9B7BB8")), 18.0), // violetLight — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9CBE0")), 10.0), // violetPale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#9B9490")), 8.0),  // ashSilver — cendre
            PaletteFamily(listOf(ColorRGBa.fromHex("#6B5D52")), 6.0)   // duskBronze — sobriété, jeûne
        ),
        // nearBlackViolet — cœur de médaillon, pénitence profonde.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#1F1420"))
    )

    /**
     * Rouge — Passion (Rameaux, Vendredi saint), Pentecôte/Esprit-Saint,
     * apôtres et martyrs (LiturgicalPaletteRed).
     */
    val ROUGE = LiturgicalPalette(
        name = "Rouge — Passion/Pentecôte/martyrs",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#5C1220")), 18.0), // garnetDeep — sang de la Passion
            PaletteFamily(listOf(ColorRGBa.fromHex("#A6242E")), 32.0), // scarlet — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#D6491F")), 20.0), // flameVermilion — feu de Pentecôte
            PaletteFamily(listOf(ColorRGBa.fromHex("#F2A65A")), 10.0), // paleFlame — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 10.0), // gold — couronne des martyrs
            PaletteFamily(listOf(ColorRGBa.fromHex("#8B5A1F")), 7.0)   // goldAntique — contraste chaud
        ),
        // nearBlackGarnet — ténèbres du Golgotha, cœur de médaillon.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#210608"))
    )

    /**
     * Noir — messes des défunts, funérailles (usage facultatif depuis
     * Vatican II, le violet étant l'option ordinaire)
     * (LiturgicalPaletteBlack).
     */
    val NOIRE = LiturgicalPalette(
        name = "Noir — défunts (facultatif)",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#14100E")), 22.0), // blackDeep — ténèbres du deuil
            PaletteFamily(listOf(ColorRGBa.fromHex("#2E2B28")), 30.0), // anthracite — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#57534C")), 18.0), // slateGrey — transmission lumineuse
            PaletteFamily(listOf(ColorRGBa.fromHex("#8B8781")), 10.0), // pearlGreyDark — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#A6A6A8")), 10.0), // silver — dignité du deuil
            PaletteFamily(listOf(ColorRGBa.fromHex("#251A2C")), 7.0)   // violetUndertone — écho du violet
        ),
        // blackVioletCore — noir le plus profond, cœur de médaillon.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#0D0A0F"))
    )

    /**
     * Rose — 3e dimanche de l'Avent (Gaudete) et 4e dimanche de Carême
     * (Laetare) uniquement, variante ponctuelle du violet
     * (LiturgicalPaletteRose). « Gaudete » et « Laetare » : « réjouissez-
     * vous », la joie au milieu d'un temps de pénitence. Le rose liturgique
     * est un violet éclairci, plus soutenu que le rose pâle habituel
     * (E. McNamara, ZENIT, déc. 2004) : rose violacé, pas rose bonbon.
     * Version ravivée (oct. 2026) : la première version (roseMain #B08093,
     * saturation 0,23) était la plus grise des palettes colorées et
     * paraissait terne.
     * Révision du 19 oct. 2026 (teintes d'Astrea) : palette ENTIÈREMENT
     * rose, en cinq tons du blush au très foncé ; l'or doux (#E8B868) et le
     * bronze rosé (#A55F4C) de la version précédente sont retirés. L'anneau
     * des croix celtiques et la bordure des baies gardent l'or vif commun à
     * toutes les palettes (BayStyle.BORDER_GOLD). Version précédente, pour
     * mémoire : roseDeep #8C3D63 (18), roseMain #C4628F (32), roseLight
     * #E59AB8 (20), rosePale #F5D5E1 (10), goldSoft #E8B868 (8), roseBronze
     * #A55F4C (5).
     */
    val ROSE = LiturgicalPalette(
        name = "Rose — Gaudete/Laetare",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#7A2E4A")), 18.0), // très foncé — ancrage
            PaletteFamily(listOf(ColorRGBa.fromHex("#A84D6B")), 20.0), // foncé
            PaletteFamily(listOf(ColorRGBa.fromHex("#D47A93")), 32.0), // moyen (framboise douce) — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#E9A9B8")), 18.0), // clair — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#F4D3D8")), 12.0)  // très clair (blush) — halo
        ),
        // nearBlackMauve — écho du violet parent, cœur de médaillon.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#3D2530"))
    )

    /**
     * Bleu marial — PAS une des six couleurs liturgiques universelles
     * de la PGMR : privilège régional (Espagne, Amérique latine),
     * réservé à l'Immaculée Conception (8 décembre). Palette
     * dévotionnelle, pas liturgique au sens strict — à documenter comme
     * telle si commercialisée, pas présentée comme une "couleur du
     * calendrier" au même titre que les six autres.
     */
    val MARIAL = LiturgicalPalette(
        name = "Bleu marial (dévotionnel)",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#1D3461")), 20.0), // blueDeep — outremer profond
            PaletteFamily(listOf(ColorRGBa.fromHex("#2E5C9A")), 32.0), // blueMain — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#7FA8D9")), 18.0), // skyBlue — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#C9DCEF")), 10.0), // bluePale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#A9B4BD")), 10.0), // silverStar — étoiles
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 7.0)   // gold — couronne mariale
        ),
        // nightBlueCore — nuit mariale, cœur de médaillon.
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#0C1830"))
    )

    /** Toutes les palettes, par exemple pour générer une série complète. */
    val ALL = listOf(ORDINAIRE, NOEL, AVENT, ROUGE, NOIRE, ROSE, MARIAL)
}


// --------------------------------------------------
// CHAMP DE COULEUR PÉRIODIQUE (motif raccordable)
// --------------------------------------------------

/**
 * Un palier d'une gamme de couleurs ordonnée du froid (temperature 0)
 * au chaud (temperature 1), et les nuances proches tirées au hasard à
 * ce palier.
 */
data class ColorRampStop(
    val temperature: Double,
    val shades: List<ColorRGBa>
)

object ColorRamps {

    /**
     * Arc-en-ciel du vitrail contemporain de référence (bleus profonds →
     * turquoises → verts → jaunes → orangés → rouges → rouges profonds).
     * Pas de blanc : Printful ne peut pas imprimer le blanc sur les
     * produits en impression intégrale.
     */
    val ARC_EN_CIEL = listOf(
        ColorRampStop(0.00, listOf(ColorRGBa.fromHex("#1B3F8F"), ColorRGBa.fromHex("#2254B5"), ColorRGBa.fromHex("#2E62C4"))), // bleus profonds
        ColorRampStop(0.18, listOf(ColorRGBa.fromHex("#1F78B4"), ColorRGBa.fromHex("#2A86C8"), ColorRGBa.fromHex("#1C6FAE"))), // bleus moyens
        ColorRampStop(0.33, listOf(ColorRGBa.fromHex("#1AA0B0"), ColorRGBa.fromHex("#20B2BE"), ColorRGBa.fromHex("#149AA6"))), // turquoises
        ColorRampStop(0.47, listOf(ColorRGBa.fromHex("#1E9A6B"), ColorRGBa.fromHex("#2EAD74"), ColorRGBa.fromHex("#8DC04A"))), // verts
        ColorRampStop(0.58, listOf(ColorRGBa.fromHex("#F2D13A"), ColorRGBa.fromHex("#E8C62A"), ColorRGBa.fromHex("#F5DD6A"))), // jaunes
        ColorRampStop(0.70, listOf(ColorRGBa.fromHex("#F2A023"), ColorRGBa.fromHex("#EE8C1E"), ColorRGBa.fromHex("#F7B545"))), // orangés
        ColorRampStop(0.84, listOf(ColorRGBa.fromHex("#E0421F"), ColorRGBa.fromHex("#D8361C"), ColorRGBa.fromHex("#E8552C"))), // rouges
        ColorRampStop(1.00, listOf(ColorRGBa.fromHex("#8E1B22"), ColorRGBa.fromHex("#7A1620"), ColorRGBa.fromHex("#A02428")))  // rouges profonds
    )
}

/**
 * Couleurs du motif raccordable : un champ de « température » qui varie
 * en cosinus sur la tuile (surtout verticalement, un peu
 * horizontalement), donc PÉRIODIQUE — un bord bleu ne se heurte jamais
 * à un bord rouge au raccord entre deux tuiles. Chaque pièce prend une
 * nuance du palier le plus proche de la température à son centre, avec
 * un peu de bruit (temperatureNoise) et, rarement (accentProb), la
 * température opposée (accent contrasté, comme l'éclat rouge au milieu
 * des bleus sur le vitrail de référence).
 *
 * Remplace les couleurs tirées par le PaletteSystem. ⚠ Consomme des
 * tirages de random : à appeler au même endroit du pipeline que les
 * autres post-traitements (voir Renderer.kt).
 */
fun applyPeriodicColorField(
    guide: CompositionGuide.PeriodicShards,
    cells: List<List<Vector2>>,
    cellColors: MutableList<ColorRGBa>,
    ramp: List<ColorRampStop>,
    random: Random,
    temperatureNoise: Double = 0.18,
    accentProb: Double = 0.10
) {

    val phaseY = random.nextDouble(0.0, 2.0 * Math.PI)
    val phaseX = random.nextDouble(0.0, 2.0 * Math.PI)

    cells.forEachIndexed { index, cell ->

        val c = polygonCentroid(cell)
        val x = ((c.x % guide.tileWidth) + guide.tileWidth) % guide.tileWidth
        val y = ((c.y % guide.tileHeight) + guide.tileHeight) % guide.tileHeight

        var t = 0.5 +
                0.40 * kotlin.math.cos(2.0 * Math.PI * y / guide.tileHeight + phaseY) +
                0.10 * kotlin.math.cos(2.0 * Math.PI * x / guide.tileWidth + phaseX)
        t += random.nextDouble(-temperatureNoise, temperatureNoise)
        if (random.nextDouble() < accentProb) t = 1.0 - t
        t = t.coerceIn(0.0, 1.0)

        val stop = ramp.minByOrNull { kotlin.math.abs(it.temperature - t) }!!
        cellColors[index] = stop.shades[random.nextInt(stop.shades.size)]
    }
}