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
    val seasonalMedallionShades: List<ColorRGBa>
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
 */
enum class MedallionMode {
    HOST,
    SEASONAL
}

/** Les teintes du médaillon selon le mode choisi. */
fun LiturgicalPalette.medallionShades(mode: MedallionMode): List<ColorRGBa> {
    return when (mode) {
        MedallionMode.HOST -> LiturgicalPalettes.CROSS_ARM_SHADES
        MedallionMode.SEASONAL -> seasonalMedallionShades
    }
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
     * Blanc et or — Noël/Pâques (LiturgicalPaletteWhiteGold). Le drap
     * d'or remplace canoniquement le blanc pour les grandes occasions
     * (PGMR) — d'où la parité blanc/or dans ce document, contrairement
     * à une première tentative improvisée (un or trop proche du
     * moutarde, et une touche de bleu ajoutée par association
     * hivernale plutôt que par recherche liturgique — retirée après
     * vérification : le bleu ne fait pas partie des couleurs du rite
     * romain).
     */
    val NOEL = LiturgicalPalette(
        name = "Blanc et or — Noël/Pâques",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#F2ECD9")), 22.0), // ivory — corps principal blanc
            PaletteFamily(listOf(ColorRGBa.fromHex("#FAF6EC")), 15.0), // pearl — halo lumineux
            PaletteFamily(listOf(ColorRGBa.fromHex("#F0C674")), 15.0), // goldLight — gloire, auréole
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 20.0), // gold — or de référence
            PaletteFamily(listOf(ColorRGBa.fromHex("#E5D8B8")), 10.0), // champagne — transition
            PaletteFamily(listOf(ColorRGBa.fromHex("#9C6F24")), 10.0)  // goldAntique — ancrage doré
        ),
        // darkBronze — cœur de médaillon ("reste chaud, jamais noir pur").
        seasonalMedallionShades = listOf(ColorRGBa.fromHex("#4A3312"))
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
     * (LiturgicalPaletteRose).
     */
    val ROSE = LiturgicalPalette(
        name = "Rose — Gaudete/Laetare",
        families = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#7A4A5C")), 18.0), // roseDeep — ancrage
            PaletteFamily(listOf(ColorRGBa.fromHex("#B08093")), 32.0), // roseMain — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#DCAEB8")), 20.0), // roseLight — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#EDD9DC")), 10.0), // rosePale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#DCAE79")), 8.0),  // goldSoft — reflets
            PaletteFamily(listOf(ColorRGBa.fromHex("#96684F")), 5.0)   // roseBronze — contraste chaud
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