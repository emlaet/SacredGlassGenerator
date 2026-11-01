import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// FOND « CHAMP DE RAYONS » (fonds pour d'autres designs)
// --------------------------------------------------
//
// Un fond seul, dans l'esprit d'un vitrail, à superposer sous un autre
// calque (carte de vœux Gelato, par exemple) : des rayons partant d'un
// centre, en deux teintes alternées, coupés par des anneaux de plomb
// concentriques continus, sur toute la surface du format (fond perdu
// compris). Pas de motif, pas de bandeau, pas de texte.
//
// Repris du programme indépendant « RayBackgroundProgram » d'Astrea et
// branché sur le générateur : le fond passe par le même pipeline que les
// vitraux (verre procédural, plomb noir, export par la touche « e »).
// Les tirages aléatoires (angles des rayons, puis écarts des anneaux)
// suivent le même ordre que dans le programme d'origine.
//
// Construction : rayons, anneaux, médaillon éventuel et bord du format
// sont découpés en UNE SEULE fois (polygonizeSegments, Arrangement.kt) :
// les pièces voisines partagent exactement leurs sommets et leurs arêtes
// (règle de la topologie partagée).
//
// Format libre : largeur et hauteur du FORMAT FINI et fond perdu, en
// pixels de la sortie pleine taille (300 dpi). Dans l'aperçu (768 × 576),
// le format est centré dans la fenêtre, à la même échelle relative ;
// l'export (touche « e ») produit exactement fullWidthPx × fullHeightPx.

/**
 * Le fond découpé : ses pièces, et pour chacune le rayon auquel elle
 * appartient (−1 : médaillon central).
 */
class RayFieldGuide(
    val pieces: List<List<Vector2>>,
    val sectors: List<Int>,
    /** Familles de teintes alternées d'un rayon à l'autre. */
    val sectorShades: List<List<ColorRGBa>>,
    val medallionShades: List<ColorRGBa>
) : CompositionGuide()

/**
 * Fond « champ de rayons ».
 *
 * Positions et rayons en FRACTIONS DU FORMAT FINI, mesurées depuis son
 * coin haut gauche (le fond perdu s'ajoute autour) :
 * - centerX, centerY : centre des rayons, en part de la largeur et de la
 *   hauteur du format fini (0.5, 0.5 = centre) ;
 * - ringRadii, ringJitter, medallionRadius : en part du PETIT côté du
 *   format fini (pour une carte carrée, le côté T).
 *
 * - rayCount : nombre de rayons ; doit être un multiple du nombre de
 *   familles de shades (2 par défaut : nombre PAIR), pour que
 *   l'alternance boucle sans deux rayons voisins de même couleur.
 * - sectorJitter : écart aléatoire maximal de chaque limite de rayon, en
 *   part de la largeur moyenne d'un rayon. Aucun rayon n'est plus étroit
 *   que la moitié de la moyenne (le tirage est recommencé).
 * - shades : familles de teintes alternées. Par défaut, les deux bleus
 *   nuit profonds (#16214A et #1C2A5C ; le programme d'origine prenait
 *   #1E2E64 et #263A78, jugés trop clairs) ; pour les couleurs d'une saison :
 *   shades = palette.rayFieldShades() (voir plus bas).
 * - medallionShades : médaillon central plein (si medallionRadius > 0).
 * - leadWidthMm : largeur du plomb à l'impression, en millimètres
 *   (0,762 mm = les 9 px à 300 dpi du programme d'origine).
 *
 * FORMAT ET RÉSOLUTION : le format se donne en MILLIMÈTRES (format fini et
 * fond perdu, comme dans les gabarits d'impression) et la résolution en
 * dpi (= ppp, points par pouce). La taille de l'export en pixels en
 * découle : pixels = mm ÷ 25,4 × dpi. Changer dpi ne change PAS le dessin
 * (proportions, plombs, couleurs, tirages) : seulement le nombre de
 * pixels. Gelato demande au moins 300 dpi À LA TAILLE D'IMPRESSION : le
 * format en mm doit donc être celui du produit Gelato, fond perdu compris.
 *
 * Format par défaut : carte carrée de 141 mm, avec 3 mm de fond perdu, à
 * 300 dpi (export de 1735 × 1735 px) — à vérifier dans le gabarit Gelato.
 */
class RayFieldCompositionSystem(
    /** Format fini, en millimètres. */
    val trimWidthMm: Double = 141.0,
    val trimHeightMm: Double = 141.0,
    /** Fond perdu, en millimètres, de chaque côté. */
    val bleedMm: Double = 3.0,
    /** Résolution de l'export, en dpi (ppp) : 300 au minimum pour Gelato. */
    val dpi: Double = 300.0,
    private val rayCount: Int = 24,
    private val centerX: Double = 0.5,
    private val centerY: Double = 0.5,
    private val ringRadii: List<Double> = listOf(0.26, 0.42, 0.60),
    private val ringJitter: Double = 0.02,
    private val sectorJitter: Double = 0.15,
    private val shades: List<List<ColorRGBa>> = listOf(
        listOf(ColorRGBa.fromHex("#16214A")), // bleu nuit profond
        listOf(ColorRGBa.fromHex("#1C2A5C"))  // le même, un ton plus clair (écart des deux bleus d'origine)
    ),
    private val medallionRadius: Double = 0.0,
    private val medallionShades: List<ColorRGBa> = listOf(ColorRGBa.fromHex("#0E1633")), // bleu presque noir
    val leadWidthMm: Double = 0.762
) : CompositionSystem {

    init {
        require(shades.isNotEmpty() && rayCount >= 4 && rayCount % shades.size == 0) {
            "RayFieldCompositionSystem : rayCount ($rayCount) doit être au moins 4 et un multiple du nombre de familles de teintes (${shades.size})"
        }
    }

    /** Format complet (format fini + fond perdu), en millimètres. */
    val fullWidthMm: Double get() = trimWidthMm + 2 * bleedMm
    val fullHeightMm: Double get() = trimHeightMm + 2 * bleedMm

    /** Taille de l'export (format fini + fond perdu), en pixels, à la résolution dpi. */
    val fullWidthPx: Int get() = Math.round(fullWidthMm / 25.4 * dpi).toInt()
    val fullHeightPx: Int get() = Math.round(fullHeightMm / 25.4 * dpi).toInt()

    /** Largeur du plomb dans l'export, en pixels. */
    val leadWidthPx: Double get() = leadWidthMm / 25.4 * dpi

    /**
     * Rectangle du format complet (fond perdu compris) dans un canevas :
     * le plus grand possible au bon format, centré. À l'export, il
     * occupe tout le canevas.
     */
    fun frame(canvasWidth: Double, canvasHeight: Double): DoubleArray {
        val aspect = fullHeightMm / fullWidthMm
        var w = canvasWidth
        var h = w * aspect
        if (h > canvasHeight) {
            h = canvasHeight
            w = h / aspect
        }
        return doubleArrayOf((canvasWidth - w) / 2, (canvasHeight - h) / 2, w, h)
    }

    /**
     * Échelle des valeurs en pixels absolus (plomb, courbure) : rapport
     * entre la largeur du format dans ce canevas et sa largeur dans
     * l'aperçu de référence (768 × 576). Même principe que bayPixelScale
     * (Bay.kt) : l'aperçu et l'export gardent les mêmes proportions.
     */
    fun pixelScale(canvasWidth: Double, canvasHeight: Double): Double =
        frame(canvasWidth, canvasHeight)[2] / frame(768.0, 576.0)[2]

    /**
     * Largeur de plomb à donner à la recette (en pixels de l'aperçu de
     * référence) pour obtenir leadWidthPx dans la sortie pleine taille.
     */
    fun referenceLeadWidth(): Double =
        leadWidthPx / pixelScale(fullWidthPx.toDouble(), fullHeightPx.toDouble())

    override fun generate(width: Double, height: Double, targetRegions: Int, random: Random): CompositionGuide {

        val (fx, fy, fw, fh) = frame(width, height).let { listOf(it[0], it[1], it[2], it[3]) }
        // Format fini à l'intérieur du fond perdu
        val bleedX = fw * bleedMm / fullWidthMm
        val bleedY = fh * bleedMm / fullHeightMm
        val trimX = fx + bleedX
        val trimY = fy + bleedY
        val trimW = fw - 2 * bleedX
        val trimH = fh - 2 * bleedY
        val unit = min(trimW, trimH)

        val center = Vector2(trimX + centerX * trimW, trimY + centerY * trimH)
        // Assez long pour dépasser tous les coins, où que soit le centre.
        val far = 2.0 * sqrt(fw * fw + fh * fh)

        // Tirages : angles des rayons, puis écart de chaque anneau
        // (même ordre que le programme d'origine).
        val angles = rayAngles(rayCount, sectorJitter, random)
        val rings = ringRadii.map { r -> (r + signedJitter(random, ringJitter)) * unit }
        val medallion = medallionRadius * unit

        // --- Segments : bord du format, rayons, anneaux, médaillon
        val minX = fx; val minY = fy; val maxX = fx + fw; val maxY = fy + fh
        val rect = listOf(Vector2(minX, minY), Vector2(maxX, minY), Vector2(maxX, maxY), Vector2(minX, maxY))
        val segments = mutableListOf<Segment>()
        segments += polygonEdgeSegments(rect)
        // point à la distance r du centre, dans la direction a
        fun polar(a: Double, r: Double) = Vector2(center.x + r * cos(a), center.y + r * sin(a))
        fun circle(r: Double): List<Vector2> {
            // ~ un sommet tous les 0,7 % du petit côté : cercle régulier à l'œil
            val n = (2 * PI * r / (unit * 0.007)).toInt().coerceIn(48, 360)
            return (0 until n).map { k -> polar(2 * PI * k / n, r) }
        }
        // Médaillon : son contour passe EXACTEMENT par le départ de chaque
        // rayon (sommets partagés), pour que les pièces se ferment bien.
        val rayStarts = angles.map { a -> polar(a, medallion) }
        val medallionPolygon: List<Vector2> = if (medallion > 0.0) {
            val n = (2 * PI * medallion / (unit * 0.007)).toInt().coerceIn(48, 360)
            val norm = { a: Double -> ((a % (2 * PI)) + 2 * PI) % (2 * PI) }
            val rayAngles = angles.map(norm)
            val minGap = PI / n / 2
            val uniform = (0 until n).map { 2 * PI * it / n }
                .filter { u -> rayAngles.none { r -> kotlin.math.abs(r - u) < minGap || kotlin.math.abs(r - u) > 2 * PI - minGap } }
            (uniform.map { it to polar(it, medallion) } + rayAngles.zip(rayStarts))
                .sortedBy { it.first }.map { it.second }
        } else emptyList()
        for ((k, a) in angles.withIndex()) {
            val start = if (medallion > 0.0) rayStarts[k] else center
            clipSegmentToRect(Segment(start, polar(a, far)), minX, minY, maxX, maxY)?.let { segments += it }
        }
        for (r in rings) {
            if (r <= medallion) continue
            for (s in polygonEdgeSegments(circle(r))) {
                clipSegmentToRect(s, minX, minY, maxX, maxY)?.let { segments += it }
            }
        }
        if (medallion > 0.0) segments += polygonEdgeSegments(medallionPolygon)

        // --- Faces, et rayon de chacune
        val faces = polygonizeSegments(segments).filter { face ->
            val p = interiorPoint(face)
            p.x > minX && p.x < maxX && p.y > minY && p.y < maxY
        }
        fun sectorOf(p: Vector2): Int {
            if (medallion > 0.0 && polygonContains(medallionPolygon, p)) return -1
            var a = atan2(p.y - center.y, p.x - center.x)
            while (a < angles[0]) a += 2 * PI
            while (a >= angles[0] + 2 * PI) a -= 2 * PI
            var i = angles.size - 1
            for (k in 0 until angles.size - 1) {
                if (a < angles[k + 1]) { i = k; break }
            }
            return i
        }
        val labelled = faces.map { it to sectorOf(interiorPoint(it)) }

        // Ordre indépendant de l'échelle (les nuances sont tirées dans cet ordre)
        fun key(p: List<Vector2>): Pair<Long, Long> {
            val g = polygonCentroid(p)
            return Math.round((g.y - fy) / fw * 1e6) to Math.round((g.x - fx) / fw * 1e6)
        }
        val sorted = labelled.sortedWith(compareBy({ key(it.first).first }, { key(it.first).second }))

        return RayFieldGuide(
            pieces = sorted.map { it.first },
            sectors = sorted.map { it.second },
            sectorShades = shades,
            medallionShades = medallionShades
        )
    }

    /**
     * Angles des limites de rayons : secteurs égaux, puis chaque limite
     * décalée d'au plus ±jitter × largeur moyenne. On recommence le
     * tirage tant qu'un secteur est plus étroit que la moitié de la
     * moyenne (déterministe : même seed, même résultat).
     */
    private fun rayAngles(count: Int, jitter: Double, random: Random): List<Double> {
        val step = 2 * PI / count
        val start = random.nextDouble(0.0, step)
        while (true) {
            val angles = (0 until count).map { i ->
                start + i * step + signedJitter(random, jitter) * step
            }
            val narrowest = angles.indices.minOf { i ->
                val next = if (i + 1 < count) angles[i + 1] else angles[0] + 2 * PI
                next - angles[i]
            }
            if (narrowest >= 0.5 * step) return angles
        }
    }

    /** Valeur aléatoire dans [-amount, amount) ; 0 si amount vaut 0 (nextDouble refuse un intervalle vide). */
    private fun signedJitter(random: Random, amount: Double): Double =
        if (amount > 0.0) random.nextDouble(-amount, amount) else 0.0
}

/** Les pièces sont construites par la composition : la segmentation les renvoie telles quelles. */
class RayFieldSegmentationSystem : SegmentationSystem {
    override fun segment(guide: CompositionGuide, width: Double, height: Double, random: Random): List<List<Vector2>> {
        require(guide is RayFieldGuide) { "RayFieldSegmentationSystem nécessite un RayFieldGuide" }
        return guide.pieces
    }
}

/**
 * Couleurs du fond : chaque rayon prend la famille de teintes de son rang
 * (alternance), le médaillon ses propres teintes. Remplace les couleurs
 * tirées par le PaletteSystem.
 * ⚠ Consomme des tirages de random (une nuance par pièce) : appelée depuis
 * computeVitrail (Renderer.kt), au même endroit que les autres
 * post-traitements.
 */
fun applyRayFieldColors(
    guide: RayFieldGuide,
    cellColors: MutableList<ColorRGBa>,
    random: Random
) {
    fun pick(list: List<ColorRGBa>) = list[random.nextInt(list.size)]
    guide.sectors.forEachIndexed { i, sector ->
        cellColors[i] = if (sector < 0) pick(guide.medallionShades)
        else pick(guide.sectorShades[sector % guide.sectorShades.size])
    }
}

/**
 * Deux teintes proches d'une palette liturgique, à alterner dans le champ
 * de rayons (comme les deux bleus nuit du réglage par défaut) : la
 * couleur principale de la saison (coloredMainShades, Palette.kt) et la
 * même assombrie de 18 % (rapport des deux bleus d'origine). Palette
 * « blanc lumineux » de Noël/Pâques : ses deux blancs de bordure (lys et
 * nacre).
 */
fun LiturgicalPalette.rayFieldShades(): List<List<ColorRGBa>> {
    val lum = luminous
    if (lum != null) return lum.white.map { listOf(it) }
    val main = coloredMainShades().first()
    fun hex2(v: Double) = "%02X".format(Math.round(v.coerceIn(0.0, 1.0) * 255).toInt())
    // construite par fromHex (voir fromHsl, Bay.kt : le constructeur
    // ColorRGBa(r, g, b) déclare la couleur linéaire et l'éclaircit)
    val darker = ColorRGBa.fromHex("#" + hex2(main.r * 0.82) + hex2(main.g * 0.82) + hex2(main.b * 0.82))
    return listOf(listOf(darker), listOf(main))
}

/** Médaillon du champ de rayons aux couleurs d'une saison : son cœur sombre (l'ocre pour la palette « blanc lumineux »). */
fun LiturgicalPalette.rayFieldMedallionShades(): List<ColorRGBa> =
    luminous?.ochre ?: seasonalMedallionShades

// --------------------------------------------------
// FONDS ASSOCIÉS AUX PALETTES LITURGIQUES
// --------------------------------------------------

/**
 * Un fond « champ de rayons » prêt à l'emploi : deux teintes alternées
 * d'un rayon à l'autre, et la teinte du médaillon central (utilisée
 * seulement si medallionRadius > 0).
 */
data class RayFieldBackground(
    val name: String,
    val shades: List<List<ColorRGBa>>,
    val medallionShades: List<ColorRGBa>
)

/**
 * Les fonds choisis par Astrea pour accompagner chaque palette liturgique
 * (oct. 2026) : une teinte qui met en valeur les designs de la saison, en
 * deux tons proches alternés.
 *
 * Médaillon : calculé à partir des deux tons (voir medallionFor) — sur un
 * fond sombre, le ton le plus sombre encore assombri (× 0,65) ; sur un fond
 * clair, le ton le plus soutenu un peu assombri (× 0,90). Discret, sans
 * nouvelle couleur.
 */
object RayFieldBackgrounds {

    private fun of(name: String, a: String, b: String): RayFieldBackground {
        val ca = ColorRGBa.fromHex(a)
        val cb = ColorRGBa.fromHex(b)
        return RayFieldBackground(name, listOf(listOf(ca), listOf(cb)), medallionFor(ca, cb))
    }

    /** Médaillon : ton le plus sombre des deux, assombri (× 0,65 si le fond est sombre, × 0,90 s'il est clair). */
    private fun medallionFor(a: ColorRGBa, b: ColorRGBa): List<ColorRGBa> {
        fun lum(c: ColorRGBa) = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b
        val darker = if (lum(a) <= lum(b)) a else b
        val factor = if (lum(darker) < 0.5) 0.65 else 0.90
        fun hex2(v: Double) = "%02X".format(Math.round(v.coerceIn(0.0, 1.0) * 255).toInt())
        // construite par fromHex (voir fromHsl, Bay.kt : linéarité)
        return listOf(ColorRGBa.fromHex("#" + hex2(darker.r * factor) + hex2(darker.g * factor) + hex2(darker.b * factor)))
    }

    /** Blanc (Noël/Pâques) : bleu nuit de l'ange. */
    val NOEL = of("Bleu nuit de l'ange", "#16214A", "#1C2A5C")
    /** Rouge : bleu canard profond. */
    val ROUGE = of("Bleu canard profond", "#072A30", "#0A343B")
    /** Vert (Temps ordinaire) : bleu nuit franc. */
    val ORDINAIRE = of("Bleu porcelaine (clair)", "#B4C6DA", "#C6D5E6")
    /** Violet (Avent/Carême) : vert émeraude profond. */
    val AVENT = of("Anthracite bleuté", "#1E2433", "#262D3F")
    /** Rose (Gaudete/Laetare) : vert sapin franc. */
    val ROSE = of("Bleu nuit", "#1A1C48", "#22255A")
    /** Bleu marial : blanc froid très pâle. */
    val MARIAL = of("sable rosé", "#E2CDBF", "#ECDFD3")
    /** Noir (défunts) : blanc cassé / gris clair. */
    val NOIRE = of("Anthracite chaud", "#353433", "#403E3D")

    /** Le fond associé à une palette liturgique (bleu nuit de l'ange par défaut). */
    fun forPalette(palette: LiturgicalPalette): RayFieldBackground = when (palette) {
        LiturgicalPalettes.NOEL -> NOEL
        LiturgicalPalettes.ROUGE -> ROUGE
        LiturgicalPalettes.ORDINAIRE -> ORDINAIRE
        LiturgicalPalettes.AVENT -> AVENT
        LiturgicalPalettes.ROSE -> ROSE
        LiturgicalPalettes.MARIAL -> MARIAL
        LiturgicalPalettes.NOIRE -> NOIRE
        else -> NOEL
    }
}

/** Le fond « champ de rayons » associé à cette palette (voir RayFieldBackgrounds). */
fun LiturgicalPalette.rayFieldBackground(): RayFieldBackground = RayFieldBackgrounds.forPalette(this)