import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import org.openrndr.draw.*
import org.openrndr.extensions.Screenshots
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.Segment2D
import kotlin.math.sqrt
import kotlin.random.Random
import java.io.File

// --------------------------------------------------
// UTILITAIRES PARTAGÉS (arêtes courbes)
// --------------------------------------------------
//
// Ces fonctions ne "appartiennent" pas à un seul système : elles sont
// utilisées à la fois par la Segmentation (formes des cellules) et
// par le Plomb (Leading.kt, tracé des arêtes). Elles restent ici,
// au même endroit que la structure de données EdgeCurve.

data class EdgeCurve(
    val start: Vector2,
    val control1: Vector2,
    val control2: Vector2,
    val end: Vector2
)

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

    edgeCurveCache[key]?.let { cachedCurve ->

        if (cachedCurve.start == a && cachedCurve.end == b) {
            return cachedCurve
        }

        return EdgeCurve(
            start = a,
            control1 = cachedCurve.control2,
            control2 = cachedCurve.control1,
            end = b
        )
    }

    val dx = b.x - a.x
    val dy = b.y - a.y

    val length = sqrt(dx * dx + dy * dy)

    if (length == 0.0) {
        val curve = EdgeCurve(start = a, control1 = a, control2 = b, end = b)
        edgeCurveCache[key] = curve
        return curve
    }

    val tangentX = dx / length
    val tangentY = dy / length

    val normalX = -tangentY
    val normalY = tangentX

    val direction = if (key.hashCode() and 1 == 0) 1.0 else -1.0

    val controlDistance = length * 0.33

    val offset1 = curvatureAmount * direction * 0.20
    val offset2 = curvatureAmount * direction * 0.20

    val control1 = Vector2(
        a.x + tangentX * controlDistance + normalX * offset1,
        a.y + tangentY * controlDistance + normalY * offset1
    )

    val control2 = Vector2(
        b.x - tangentX * controlDistance + normalX * offset2,
        b.y - tangentY * controlDistance + normalY * offset2
    )

    val curve = EdgeCurve(start = a, control1 = control1, control2 = control2, end = b)

    edgeCurveCache[key] = curve

    return curve
}

/**
 * Décale une EdgeCurve perpendiculairement à sa direction générale
 * (corde start→end), toujours du côté qui fait face à lightDirection.
 * Utilisé par le Système 4 (Plomb) pour placer un reflet cohérent
 * avec une unique source de lumière sur tout le tableau — le même
 * lightDirection que celui utilisé par le Système 5 (Verre), pour que
 * les deux systèmes racontent la même histoire de lumière.
 *
 * Approximation : les 4 points de contrôle sont décalés du même
 * vecteur (celui de la corde), plutôt qu'un vrai offset de courbe de
 * Bézier (mathématiquement plus complexe). Suffisant visuellement
 * tant que curvatureAmount reste modéré (validé en Python jusqu'à
 * curvatureAmount=2.0, le réglage actuel).
 */
fun offsetEdgeCurve(
    curve: EdgeCurve,
    offsetDistance: Double,
    lightDirection: Vector2
): EdgeCurve {

    val dx = curve.end.x - curve.start.x
    val dy = curve.end.y - curve.start.y

    val length = sqrt(dx * dx + dy * dy)

    if (length == 0.0) {
        return curve
    }

    val tangentX = dx / length
    val tangentY = dy / length

    val normalX = -tangentY
    val normalY = tangentX

    val dot = normalX * lightDirection.x + normalY * lightDirection.y
    val sign = if (dot >= 0.0) 1.0 else -1.0

    val offsetX = normalX * offsetDistance * sign
    val offsetY = normalY * offsetDistance * sign

    return EdgeCurve(
        start = Vector2(curve.start.x + offsetX, curve.start.y + offsetY),
        control1 = Vector2(curve.control1.x + offsetX, curve.control1.y + offsetY),
        control2 = Vector2(curve.control2.x + offsetX, curve.control2.y + offsetY),
        end = Vector2(curve.end.x + offsetX, curve.end.y + offsetY)
    )
}

fun drawCurvedVoronoiEdges(
    drawer: Drawer,
    cells: List<List<Vector2>>,
    strokeColor: ColorRGBa,
    strokeWeight: Double,
    edgeCurveCache: MutableMap<String, EdgeCurve>,
    curvatureAmount: Double,
    offsetDistance: Double = 0.0,
    lightDirection: Vector2 = Vector2.ZERO
) {

    val drawnEdges = mutableSetOf<String>()

    drawer.fill = null
    drawer.stroke = strokeColor
    drawer.strokeWeight = strokeWeight

    for (cell in cells) {

        if (cell.size < 3) continue

        for (i in cell.indices) {

            val a = cell[i]
            val b = cell[(i + 1) % cell.size]
            val key = edgeKey(a, b)

            if (drawnEdges.add(key)) {

                var curve = getEdgeCurve(a, b, edgeCurveCache, curvatureAmount)

                if (offsetDistance != 0.0) {
                    curve = offsetEdgeCurve(curve, offsetDistance, lightDirection)
                }

                val contour = ShapeContour.fromSegments(
                    listOf(Segment2D(curve.start, curve.control1, curve.control2, curve.end)),
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

    if (cell.size < 3) return ShapeContour.EMPTY

    val segments = mutableListOf<Segment2D>()

    for (i in cell.indices) {

        val a = cell[i]
        val b = cell[(i + 1) % cell.size]

        val curve = getEdgeCurve(a, b, edgeCurveCache, curvatureAmount)

        segments.add(Segment2D(curve.start, curve.control1, curve.control2, curve.end))
    }

    return ShapeContour.fromSegments(segments, closed = true)
}


// --------------------------------------------------
// PROGRAMME PRINCIPAL
// --------------------------------------------------
//
// Ce fichier n'implémente plus aucune logique de composition,
// segmentation, palette, plomb ou verre : il se contente
// d'assembler les 5 systèmes (Composition.kt, Segmentation.kt,
// Palette.kt, Leading.kt, Glass.kt) et de piloter la boucle de
// rendu OpenRNDR.
//
// Réglage actuel : RadiantCross + RadiantCross + Palette aléatoire
// pondérée (vert, temps ordinaire) + Basic (plomb noir simple) + Procedural
// (avec lumière globale + opalescence). Même moteur que Sunburst
// (rayons indépendants depuis un centre), mais certains rayons sont
// désignés comme les bras d'une croix latine (plus longs, forcés en
// blanc cassé) tandis que les autres jouent le rôle de rayons de
// lumière plus courts et colorés autour d'elle — esprit "croix de
// lumière/gloire", sans avoir besoin de découpe géométrique (pas de
// soustraction de polygones dans la boîte à outils) : la croix EST
// simplement un groupe de rayons plus longs et distinctement colorés.
//
// Alternatives déjà implémentées et prêtes à l'emploi (voir les
// lignes marquées "// <-- swap ici" plus bas) :
//
//   Composition/Segmentation couplées (échanger les deux ensemble) :
//   - GridCompositionSystem()          + GridSegmentationSystem()
//   - OrganicSitesCompositionSystem(baseSiteDistance, sizeVariation)
//                                       + VoronoiSegmentationSystem(relaxationIterations)
//   - CurveGuidedCompositionSystem(numberOfGuideCurves, baseSiteDistance, sizeVariation)
//                                       + VoronoiSegmentationSystem(relaxationIterations)
//   - RadialCompositionSystem(...)     + RadialSegmentationSystem()
//     (⚠ a produit un résultat jugé peu harmonieux — effet "toile
//     d'araignée" — à retravailler en profondeur avant réemploi, pas
//     juste en rejouant avec d'autres paramètres)
//   - SunburstCompositionSystem(...)   + SunburstSegmentationSystem()
//     (plein cadre, sans croix — voir les paramètres sunburst* encore
//     présents plus bas, non utilisés tant que RadiantCross est actif)
//   - RadiantCrossCompositionSystem(...) + RadiantCrossSegmentationSystem()  [réglage actuel]
//
//   Palette :
//   - RandomPaletteSystem(flatColors)                 [résultat "poivre et sel" sur cellules petites/nombreuses, évité]
//   - AdjacencyAwarePaletteSystem(flatColors)          [pousse à l'alternance, pas au regroupement]
//   - CappedClusterPaletteSystem(paletteFamilies, ...) [pour cellules Voronoï petites et nombreuses — voir Palette.kt]
//   - WeightedRandomPaletteSystem(paletteFamilies)     [réglage actuel — voir paletteFamiliesOrdinaire/Noel/Avent plus bas pour changer de saison liturgique]
//
//   Plomb :
//   - LeadStyle(width = strokeWeight, color = strokeColor)                      [réglage actuel]
//   - LeadStyle(..., highlightColor = ColorRGBa.WHITE.opacify(0.55), lightDirection = lightDirection)
//     (⚠ testé puis écarté — jugé moins bon que le trait noir simple)

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
        // Random(seed) est recréé à chaque appel de renderVitrail() plus
        // bas, pas gardé ici — indispensable pour que l'aperçu et l'export
        // haute résolution consomment chacun leur PROPRE séquence de
        // tirages aléatoires à partir du même seed, et produisent donc
        // exactement le même motif (juste à une résolution différente).

        val numberOfSites = 45

        // Système 1/2 — variation de taille organique (voir Composition.kt)
        val baseSiteDistance = 35.0
        val sizeVariation = 1.3

        // Système 1 — courbes directrices (voir CurveGuidedCompositionSystem)
        val numberOfGuideCurves = 3

        // Système 1 — structure radiale (voir RadialCompositionSystem)
        // ⚠ résultat jugé peu harmonieux ("toile d'araignée") — non
        // réemployé sans repenser la structure, voir la note plus bas.
        val numberOfRays = 16
        val numberOfRings = 5
        val radialJitterRatio = 0.30

        // Système 1/2 — sunburst géométrique (voir SunburstCompositionSystem
        // / SunburstSegmentationSystem). Correction directe de l'échec du
        // style radial ci-dessus : chaque rayon se subdivise indépendamment.
        val sunburstNumberOfRays = 22
        val sunburstCoreRadiusRatio = 0.12
        val sunburstAngleIrregularity = 0.35
        val sunburstMinDivisionsPerRay = 2
        val sunburstMaxDivisionsPerRay = 4
        // Nuances proches de blanc cassé pour les BRAS de la croix —
        // constante visuelle à travers toutes les saisons liturgiques
        // (contrairement au médaillon, voir crossMedallionShades plus
        // bas, qui varie par saison). Légère variation entre les
        // triangles/segments plutôt qu'une teinte plate identique à
        // chaque génération.
        val crossArmShades = listOf(
            ColorRGBa.fromHex("#F2E8CE"), // blanc cassé
            ColorRGBa.fromHex("#F7EFDD"), // ivoire plus clair
            ColorRGBa.fromHex("#EBE0C2")  // ivoire plus soutenu
        )

        // Sunburst plein cadre (alternative documentée plus haut dans le
        // fichier) : réutilise crossArmShades pour son médaillon.
        val sunburstMedallionShades = crossArmShades

        // Système 1/2 — croix rayonnante (voir RadiantCrossCompositionSystem
        // / RadiantCrossSegmentationSystem). Même moteur que le sunburst
        // ci-dessus, mais certains rayons sont désignés comme les bras
        // d'une croix latine (plus longs, fenêtres angulaires fixes) et
        // le reste comme des rayons de lumière plus courts autour d'elle.
        val crossNumberOfRays = 30
        val crossCoreRadiusRatio = 0.09
        val crossAngleIrregularity = 0.30
        val crossMinDivisionsPerRay = 2
        val crossMaxDivisionsPerRay = 4
        val crossLightLengthMinRatio = 0.25
        val crossLightLengthMaxRatio = 0.50
        // Fenêtres angulaires des 4 bras (voir la convention d'angle dans
        // CompositionGuide.ArmWindow). Longueurs choisies pour dépasser
        // NETTEMENT le maximum des rayons de lumière (0.50 ci-dessus) —
        // avec les tout premiers réglages (haut=0.35, gauche/droite=0.55),
        // les rayons de lumière (qui allaient jusqu'à 0.65) pouvaient
        // dépasser la croix elle-même à ces endroits, la noyant dans le
        // décor. Le bras du bas nettement plus long que celui du haut
        // donne la proportion d'une croix LATINE (contrairement à une
        // croix grecque à bras égaux).
        val crossArmWindows = listOf(
            CompositionGuide.ArmWindow("haut", 270.0, 17.0, 0.62),
            CompositionGuide.ArmWindow("bas", 90.0, 17.0, 1.00),
            CompositionGuide.ArmWindow("gauche", 180.0, 17.0, 0.78),
            CompositionGuide.ArmWindow("droite", 0.0, 17.0, 0.78)
        )

        // Sans objet pour le sunburst (pas de relaxation dans son
        // pipeline) — pertinent seulement pour VoronoiSegmentationSystem.
        val relaxationIterations = 1

        val curvatureAmount = 2.0

        // Système 5 — voir la construction de GlassStyle plus bas
        // pour les paramètres de rendu du verre (texture, stries,
        // lumière directionnelle).

        // Direction de lumière PARTAGÉE entre le Plomb (Système 4) et
        // le Verre (Système 5) — c'est ce qui unifie tout le tableau
        // sous UNE SEULE source de lumière plutôt que des pièces
        // éclairées indépendamment. Vecteur non-normalisé : seul son
        // sens compte pour le Plomb, la normalisation se fait dans le
        // shader pour le Verre.
        val lightDirection = Vector2(-0.35, -1.0)

        // Toujours présent mais non branché pour l'instant : voir la
        // note dans Segmentation.kt sur withDeformation().
        val deformationAmount = 7.0

        val strokeWeight = 5.0
        val strokeColor = ColorRGBa.BLACK

        // Bleu nuit profond — fond de la croix rayonnante. Remplace le
        // blanc utilisé pour le sunburst plein cadre : ici l'emblème ne
        // couvre pas tout le canevas (voir RadiantCrossCompositionSystem),
        // le fond doit donc être une vraie couleur de "ciel" plutôt qu'un
        // blanc neutre.
        val backgroundColor = ColorRGBa.fromHex("#0E2A47")

        // Système 3 — palettes liturgiques, reprises fidèlement des
        // project documents fournis (objets LiturgicalPaletteXxx,
        // eux-mêmes référencés à la PGMR n.346) plutôt qu'improvisées —
        // chaque teinte documentée devient sa propre PaletteFamily à une
        // seule nuance, avec le pourcentage documenté comme poids exact.
        //
        // Chaque saison a aussi sa propre teinte de MÉDAILLON, reprise
        // du "cœur sombre réservé" de son document ("hors tirage
        // aléatoire du cluster system" — cœur de médaillon, jonctions de
        // rayons). Trois options ont été comparées visuellement avant de
        // choisir ce traitement : médaillon toujours crème (comme les
        // bras) ; médaillon en cœur sombre réservé ; accent "glow" rare
        // dans les rayons. Le cœur sombre a été retenu — il fait
        // nettement mieux ressortir la croix, et résout au passage un
        // souci de contraste sur Noël (bras crème qui se fondaient dans
        // un champ blanc/or trop pâle : le médaillon sombre donne un
        // point d'ancrage fort quelle que soit la palette).
        //
        // Priorité de mise en place demandée : Temps ordinaire (actif
        // par défaut — c'est la période liturgique en cours), puis
        // Noël, puis Avent — les quatre autres (rouge, noire, rose,
        // marial) ajoutées ensuite, sur demande, une fois le principe
        // validé sur les trois premières.

        // Vert — Temps ordinaire (LiturgicalPaletteGreen). [réglage actuel]
        val paletteFamiliesOrdinaire = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#1F4D33")), 30.0), // greenDeep — ancrage
            PaletteFamily(listOf(ColorRGBa.fromHex("#3C7A4E")), 35.0), // greenLeaf — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#7FB069")), 13.0), // greenSoft — transmission lumineuse
            PaletteFamily(listOf(ColorRGBa.fromHex("#C9DDB0")), 7.0),  // greenPale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 7.0),  // amberGold — reflets
            PaletteFamily(listOf(ColorRGBa.fromHex("#A9722A")), 3.0),  // amberBurnt — contraste chaud
            PaletteFamily(listOf(ColorRGBa.fromHex("#8C4A32")), 4.0)   // rust — touche isolée (doc. ~3-5%)
        )
        // nearBlackGreen — cœur de médaillon, jonctions de rayons.
        val crossMedallionOrdinaire = listOf(ColorRGBa.fromHex("#14231A"))

        // Blanc et or — Noël/Pâques (LiturgicalPaletteWhiteGold). Le
        // drap d'or remplace canoniquement le blanc pour les grandes
        // occasions (PGMR) — d'où la parité blanc/or dans ce document,
        // contrairement à ma première tentative improvisée (un or trop
        // proche du moutarde, et une touche de bleu ajoutée par
        // association hivernale personnelle plutôt que par recherche
        // liturgique — retirée après vérification : le bleu ne fait pas
        // partie des couleurs du rite romain).
        val paletteFamiliesNoel = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#F2ECD9")), 22.0), // ivory — corps principal blanc
            PaletteFamily(listOf(ColorRGBa.fromHex("#FAF6EC")), 15.0), // pearl — halo lumineux
            PaletteFamily(listOf(ColorRGBa.fromHex("#F0C674")), 15.0), // goldLight — gloire, auréole
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 20.0), // gold — or de référence
            PaletteFamily(listOf(ColorRGBa.fromHex("#E5D8B8")), 10.0), // champagne — transition
            PaletteFamily(listOf(ColorRGBa.fromHex("#9C6F24")), 10.0)  // goldAntique — ancrage doré
        )
        // darkBronze — cœur de médaillon ("reste chaud, jamais noir pur").
        val crossMedallionNoel = listOf(ColorRGBa.fromHex("#4A3312"))

        // Violet — Avent/Carême (LiturgicalPaletteViolet).
        val paletteFamiliesAvent = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#3D2645")), 20.0), // violetDeep — ancrage, pénitence
            PaletteFamily(listOf(ColorRGBa.fromHex("#5B3168")), 32.0), // violetBishop — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#9B7BB8")), 18.0), // violetLight — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9CBE0")), 10.0), // violetPale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#9B9490")), 8.0),  // ashSilver — cendre
            PaletteFamily(listOf(ColorRGBa.fromHex("#6B5D52")), 6.0)   // duskBronze — sobriété, jeûne
        )
        // nearBlackViolet — cœur de médaillon, pénitence profonde.
        val crossMedallionAvent = listOf(ColorRGBa.fromHex("#1F1420"))

        // Rouge — Passion (Rameaux, Vendredi saint), Pentecôte/Esprit-Saint,
        // apôtres et martyrs (LiturgicalPaletteRed).
        val paletteFamiliesRouge = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#5C1220")), 18.0), // garnetDeep — sang de la Passion
            PaletteFamily(listOf(ColorRGBa.fromHex("#A6242E")), 32.0), // scarlet — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#D6491F")), 20.0), // flameVermilion — feu de Pentecôte
            PaletteFamily(listOf(ColorRGBa.fromHex("#F2A65A")), 10.0), // paleFlame — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 10.0), // gold — couronne des martyrs
            PaletteFamily(listOf(ColorRGBa.fromHex("#8B5A1F")), 7.0)   // goldAntique — contraste chaud
        )
        // nearBlackGarnet — ténèbres du Golgotha, cœur de médaillon.
        val crossMedallionRouge = listOf(ColorRGBa.fromHex("#210608"))

        // Noir — messes des défunts, funérailles (usage facultatif depuis
        // Vatican II, le violet étant l'option ordinaire) (LiturgicalPaletteBlack).
        val paletteFamiliesNoire = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#14100E")), 22.0), // blackDeep — ténèbres du deuil
            PaletteFamily(listOf(ColorRGBa.fromHex("#2E2B28")), 30.0), // anthracite — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#57534C")), 18.0), // slateGrey — transmission lumineuse
            PaletteFamily(listOf(ColorRGBa.fromHex("#8B8781")), 10.0), // pearlGreyDark — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#A6A6A8")), 10.0), // silver — dignité du deuil
            PaletteFamily(listOf(ColorRGBa.fromHex("#251A2C")), 7.0)   // violetUndertone — écho du violet
        )
        // blackVioletCore — noir le plus profond, cœur de médaillon.
        val crossMedallionNoire = listOf(ColorRGBa.fromHex("#0D0A0F"))

        // Rose — 3e dimanche de l'Avent (Gaudete) et 4e dimanche de Carême
        // (Laetare) uniquement, variante ponctuelle du violet
        // (LiturgicalPaletteRose).
        val paletteFamiliesRose = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#7A4A5C")), 18.0), // roseDeep — ancrage
            PaletteFamily(listOf(ColorRGBa.fromHex("#B08093")), 32.0), // roseMain — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#DCAEB8")), 20.0), // roseLight — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#EDD9DC")), 10.0), // rosePale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#DCAE79")), 8.0),  // goldSoft — reflets
            PaletteFamily(listOf(ColorRGBa.fromHex("#96684F")), 5.0)   // roseBronze — contraste chaud
        )
        // nearBlackMauve — écho du violet parent, cœur de médaillon.
        val crossMedallionRose = listOf(ColorRGBa.fromHex("#3D2530"))

        // Bleu marial — PAS une des six couleurs liturgiques universelles
        // de la PGMR (voir la note dans MarianBluePalette) : privilège
        // régional (Espagne, Amérique latine), réservé à l'Immaculée
        // Conception (8 décembre). Palette dévotionnelle, pas liturgique
        // au sens strict — à documenter comme telle si commercialisée,
        // pas présentée comme une "couleur du calendrier" au même titre
        // que les six autres.
        val paletteFamiliesMarial = listOf(
            PaletteFamily(listOf(ColorRGBa.fromHex("#1D3461")), 20.0), // blueDeep — outremer profond
            PaletteFamily(listOf(ColorRGBa.fromHex("#2E5C9A")), 32.0), // blueMain — corps principal
            PaletteFamily(listOf(ColorRGBa.fromHex("#7FA8D9")), 18.0), // skyBlue — transmission
            PaletteFamily(listOf(ColorRGBa.fromHex("#C9DCEF")), 10.0), // bluePale — halo
            PaletteFamily(listOf(ColorRGBa.fromHex("#A9B4BD")), 10.0), // silverStar — étoiles
            PaletteFamily(listOf(ColorRGBa.fromHex("#D9A441")), 7.0)   // gold — couronne mariale
        )
        // nightBlueCore — nuit mariale, cœur de médaillon.
        val crossMedallionMarial = listOf(ColorRGBa.fromHex("#0C1830"))

        // <-- swap ici (ligne du haut) : palette de rayons active.
        // Sept disponibles :
        //   paletteFamiliesOrdinaire — vert, temps ordinaire [réglage actuel]
        //   paletteFamiliesNoel      — blanc et or, Noël/Pâques
        //   paletteFamiliesAvent     — violet, Avent/Carême
        //   paletteFamiliesRouge     — rouge, Pentecôte/martyrs
        //   paletteFamiliesNoire     — noir, funérailles (facultatif)
        //   paletteFamiliesRose      — rose, Gaudete/Laetare
        //   paletteFamiliesMarial    — bleu marial (privilège régional, PAS une des six couleurs universelles — voir la note plus haut)
        val paletteFamilies = paletteFamiliesOrdinaire

        // <-- swap ici (ligne du bas) : teinte du MÉDAILLON — indépendant
        // de la palette de rayons ci-dessus, donc les deux se combinent
        // librement (n'importe quelle saison avec Option 1 ou Option 2).
        //
        // Option 1 — crossArmShades : le médaillon reste TOUJOURS crème,
        // comme les bras, quelle que soit la saison. Lecture liturgique
        // proposée par Astrea : le disque blanc central évoque l'hostie
        // — cohérent avec le Saint-Sacrement quelle que soit la période
        // de l'année, pas seulement une question de contraste. [réglage
        // actuel]
        //
        // Option 2 — crossMedallionOrdinaire / Noel / Avent / Rouge /
        // Noire / Rose / Marial (à faire correspondre à la palette de
        // rayons choisie juste au-dessus) : le médaillon prend le "cœur
        // sombre réservé" propre à la saison. Fait ressortir la croix un
        // peu plus nettement dans la comparaison visuelle qui a précédé
        // ce choix — mais moins cohérent avec la lecture "hostie" d'Option
        // 1, puisque le médaillon change alors de couleur avec la saison.
        val crossMedallionShades = crossArmShades

        // ------------------------
        // PIPELINE — 5 SYSTÈMES + RENDU
        // ------------------------
        //
        // Toute la génération (Composition → Segmentation → Palette →
        // Plomb → Verre) et le dessin sont regroupés dans cette fonction,
        // plutôt que calculés une fois au niveau de program{} comme
        // avant. Nécessaire pour l'export haute résolution : on ne peut
        // pas se contenter d'agrandir l'image déjà calculée à 768×576
        // (le plomb, la courbure des arêtes, la taille des cellules sont
        // tous définis en PIXELS ABSOLUS — les ré-agrandir sans
        // régénérer la géométie donnerait un plomb relativement plus
        // fin, une courbure relativement plus plate, etc. à haute
        // résolution). À la place, on régénère tout le motif à la
        // résolution cible avec le MÊME seed (donc le même dessin), et
        // on multiplie les paramètres en pixels absolus par pixelScale
        // pour garder les mêmes proportions relatives.
        //
        // canvasWidth/canvasHeight : dimensions cibles (aperçu ou export).
        // pixelScale : rapport entre canvasWidth et la largeur de
        // référence (768) — 1.0 pour l'aperçu, >1.0 pour un export plus
        // grand. S'applique à tout ce qui est défini en pixels absolus
        // (strokeWeight, curvatureAmount) ; tout ce qui est déjà relatif
        // (ratios, poids, fréquences de bruit du shader en uv 0–1) n'a
        // besoin d'aucun ajustement et s'adapte de lui-même.
        fun renderVitrail(
            targetDrawer: Drawer,
            canvasWidth: Double,
            canvasHeight: Double,
            pixelScale: Double
        ) {

            val random = Random(seed)

            val scaledStrokeWeight = strokeWeight * pixelScale
            val scaledCurvatureAmount = curvatureAmount * pixelScale

            // Système 1 — Composition
            // Alternatives disponibles : GridCompositionSystem(),
            // OrganicSitesCompositionSystem(baseSiteDistance, sizeVariation),
            // CurveGuidedCompositionSystem(numberOfGuideCurves, baseSiteDistance, sizeVariation),
            // RadialCompositionSystem(numberOfRays, numberOfRings, radialJitterRatio, radialJitterRatio),
            // SunburstCompositionSystem(sunburstNumberOfRays, sunburstCoreRadiusRatio, sunburstAngleIrregularity, sunburstMinDivisionsPerRay, sunburstMaxDivisionsPerRay)
            val compositionSystem: CompositionSystem =
                RadiantCrossCompositionSystem(
                    coreRadiusRatio = crossCoreRadiusRatio,
                    numberOfRays = crossNumberOfRays,
                    angleIrregularity = crossAngleIrregularity,
                    minDivisionsPerRay = crossMinDivisionsPerRay,
                    maxDivisionsPerRay = crossMaxDivisionsPerRay,
                    lightLengthMinRatio = crossLightLengthMinRatio,
                    lightLengthMaxRatio = crossLightLengthMaxRatio,
                    armWindows = crossArmWindows
                ) // <-- swap ici
            val guide = compositionSystem.generate(
                canvasWidth,
                canvasHeight,
                numberOfSites,
                random
            )

            // Système 2 — Segmentation
            // Alternatives disponibles : GridSegmentationSystem(),
            // VoronoiSegmentationSystem(relaxationIterations),
            // RadialSegmentationSystem(), SunburstSegmentationSystem()
            val segmentationSystem: SegmentationSystem =
                RadiantCrossSegmentationSystem() // <-- swap ici
            val cells = segmentationSystem.segment(
                guide,
                canvasWidth,
                canvasHeight,
                random
            )

            // Système 3 — Palette
            // Les rayons de lumière n'ont pas besoin de regroupement
            // (CappedClusterPaletteSystem) — ce sont déjà de grandes formes
            // distinctes, un tirage indépendant pondéré suffit. Voir
            // Palette.kt pour le détail des essais précédents pertinents
            // pour les styles à cellules petites et nombreuses (Voronoï).
            val paletteSystem: PaletteSystem =
                WeightedRandomPaletteSystem(paletteFamilies) // <-- swap ici
            val cellColors = paletteSystem.assignColors(cells, random).toMutableList()

            // Post-traitement SPÉCIFIQUE à la croix rayonnante : les
            // bras sont forcés vers crossArmShades (blanc cassé, constant
            // selon les saisons), le médaillon vers crossMedallionShades
            // (le "cœur sombre réservé" propre à la saison active) — les
            // deux distincts, contrairement au premier jet qui les
            // forçait tous les deux vers la même teinte crème. Sans ce
            // forçage, un bras ou le médaillon aurait pu piocher au
            // hasard la même famille que les rayons et se fondre dans le
            // décor. Ce n'est pas un Système générique — c'est une
            // signature visuelle propre à ce style de composition, donc
            // traité ici plutôt que dans Palette.kt.
            if (guide is CompositionGuide.RadiantCross) {

                val coreRadius = guide.maxRadius * guide.coreRadiusRatio

                cells.forEachIndexed { index, cell ->

                    val centroid = polygonCentroid(cell)

                    val dx = centroid.x - guide.center.x
                    val dy = centroid.y - guide.center.y
                    val distanceFromCenter = sqrt(dx * dx + dy * dy)

                    val isMedallion = distanceFromCenter < coreRadius * 1.05

                    if (isMedallion) {
                        cellColors[index] = crossMedallionShades[random.nextInt(crossMedallionShades.size)]
                    } else {
                        val angleDegrees = Math.toDegrees(kotlin.math.atan2(dy, dx)).let {
                            if (it < 0.0) it + 360.0 else it
                        } % 360.0
                        val isArm = classifyAngle(angleDegrees, guide.armWindows) != null

                        if (isArm) {
                            cellColors[index] = crossArmShades[random.nextInt(crossArmShades.size)]
                        }
                    }
                }
            }

            // Système 4 — Plomb
            // Retour au trait noir simple, sans reflet — préférence
            // confirmée après test du reflet décalé (jugé moins bon que
            // le simple trait noir).
            val leadSystem: LeadSystem = BasicLeadSystem()
            val leadStyle = LeadStyle(
                width = scaledStrokeWeight,
                color = strokeColor
            )

            // Système 5 — Verre
            // Cinquième réglage (voir l'en-tête de Glass.kt pour
            // l'historique complet) — celui-ci corrige un défaut différent
            // des précédents : le médaillon central (une forme compacte,
            // quasi circulaire) apparaissait plat/blanc uni alors que les
            // quartiers de rayon (allongés) montraient une bonne texture
            // avec les mêmes réglages. La vignette de lumière sature plus
            // vite sur une forme compacte que sur une forme allongée (pas
            // de "coins" pour tirer la distance au centre vers le haut).
            // Mesuré sur le rendu réel : 92% des pixels du médaillon
            // au-delà de 250/255, écart-type de 1.8 seulement. Corrigé en
            // resserrant la vignette (elle affecte alors toute forme, pas
            // seulement les formes allongées) : 62% de saturation avec
            // une vraie texture visible, sans dégrader le rendu des
            // quartiers de rayon.
            val glassSystem: GlassSystem = ProceduralGlassSystem() // <-- swap ici
            val glassStyle = GlassStyle(
                textureStrength = 0.26,
                streakStrength = 0.28,
                streakAngle = 0.6,
                localLightStrength = 0.22,
                lightDirection = lightDirection,
                globalLightStrength = 0.18,
                opalescenceStrength = 0.10
            )
            val glassShadeStyle = glassSystem.createShadeStyle(glassStyle)
            val materialVariation = glassSystem.assignMaterialVariation(cells.size, random)

            val edgeCurveCache = mutableMapOf<String, EdgeCurve>()

            // Remplissage (Palette + Verre)
            cells.forEachIndexed { index, cell ->

                if (cell.size < 3) {
                    return@forEachIndexed
                }

                val contour = createCurvedContour(cell, edgeCurveCache, scaledCurvatureAmount)

                val cellCenter = polygonCentroid(cell)
                val bounds = contour.bounds

                // Position normalisée DANS la cellule (déjà existant) :
                // pilote la vignette locale (plus clair au centre de
                // la pièce, plus sombre vers ses bords).
                val normalizedCellCenter = Vector2(
                    (cellCenter.x - bounds.corner.x) / bounds.width,
                    (cellCenter.y - bounds.corner.y) / bounds.height
                )

                // Position normalisée DANS TOUT LE CANEVAS (nouveau) :
                // pilote le dégradé de lumière globale cohérent sur
                // l'ensemble du tableau (voir Glass.kt, section 4).
                val canvasPosition = Vector2(
                    cellCenter.x / canvasWidth,
                    cellCenter.y / canvasHeight
                )

                targetDrawer.fill = cellColors[index]
                targetDrawer.stroke = null

                glassShadeStyle.parameter("cellCenter", normalizedCellCenter)
                glassShadeStyle.parameter("canvasPosition", canvasPosition)
                glassShadeStyle.parameter("opalescence", materialVariation[index])
                targetDrawer.shadeStyle = glassShadeStyle

                targetDrawer.contour(contour)
            }

            // Bordures (Plomb)
            leadSystem.draw(targetDrawer, cells, edgeCurveCache, scaledCurvatureAmount, leadStyle)
        }

        // ------------------------
        // EXPORT
        // ------------------------
        //
        // Deux façons d'exporter :
        //
        // 1. Barre d'espace : capture rapide de la fenêtre telle
        //    qu'affichée (768×576) dans un dossier "screenshots/" —
        //    pratique pour juger un rendu vite fait, pas pour l'impression.
        extend(Screenshots())

        // 2. Touche "e" : rendu hors-écran à la résolution d'impression
        //    choisie ci-dessous, sauvegardé dans un dossier "exports/".
        //    C'est celui-ci qu'il faut utiliser pour du POD.
        //
        // Réglage actuel : 3600×2700px, soit 12×9 pouces à 300 DPI —
        // à ajuster selon le format réel demandé par Printful pour le
        // produit visé (aluminium ou tissu). Le ratio largeur/hauteur
        // reste 4:3, identique à l'aperçu (768×576) ; changer ce ratio
        // demanderait de revoir la composition, pas juste ces deux
        // nombres.
        val exportWidth = 3600
        val exportHeight = 2700
        val exportPixelScale = exportWidth / width.toDouble()

        keyboard.keyDown.listen {
            if (it.name == "e") {

                val exportTarget = renderTarget(exportWidth, exportHeight) {
                    colorBuffer()
                    depthBuffer()
                }

                drawer.isolatedWithTarget(exportTarget) {
                    drawer.ortho(exportTarget)
                    // Fond TRANSPARENT pour l'export (contrairement à
                    // l'aperçu à l'écran, qui garde backgroundColor pour
                    // rester lisible pendant qu'on travaille). ColorBuffer
                    // inclut un canal alpha par défaut (RGBa 8 bits) —
                    // confirmé par la doc officielle OpenRNDR — donc rien
                    // d'autre à configurer : ColorRGBa.TRANSPARENT suffit,
                    // et le PNG final aura un vrai fond transparent partout
                    // où rien n'a été dessiné (en dehors de l'emblème).
                    drawer.clear(ColorRGBa.TRANSPARENT)
                    renderVitrail(
                        drawer,
                        exportWidth.toDouble(),
                        exportHeight.toDouble(),
                        exportPixelScale
                    )
                }

                val exportsFolder = File("exports").absoluteFile
                exportsFolder.mkdirs()

                val fileName = "vitrail-seed$seed-${System.currentTimeMillis()}.png"
                val outputFile = exportsFolder.resolve(fileName)
                // async = false : on attend la fin de l'écriture avant de
                // continuer, pour pouvoir libérer exportTarget juste après
                // sans risquer une sauvegarde encore en cours.
                exportTarget.colorBuffer(0).saveToFile(outputFile, async = false)

                exportTarget.destroy()

                println("Export haute résolution enregistré : ${outputFile.absolutePath}")
            }
        }

        // ------------------------
        // RENDU (aperçu)
        // ------------------------

        extend {
            drawer.clear(backgroundColor)
            renderVitrail(drawer, width.toDouble(), height.toDouble(), 1.0)
        }
    }
}