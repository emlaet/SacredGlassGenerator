import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.LineCap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Segment2D
import org.openrndr.shape.ShapeContour
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------
// BAIE — panneau en arc, non raccordable (tirages muraux)
// --------------------------------------------------
//
// Une baie entoure une composition existante (croix rayonnante, ange…)
// comme le ferait une vraie fenêtre d'église :
//
// - un ARC en plein cintre (roman) ou brisé (gothique), au format 2:3 ;
// - une BORDURE de verre qui suit tout le pourtour (pièces alternées) ;
// - un FOND en losanges (« vitrerie en losanges ») autour de l'emblème,
//   pour que toute la baie soit vitrée ;
// - des BARLOTIÈRES : barres de fer horizontales qui traversent la baie
//   (dans un vrai vitrail, elles divisent la baie en panneaux et portent
//   chacun d'eux).
//
// Tout est découpé en UNE SEULE fois (polygonizeSegments, Arrangement.kt)
// : emblème, losanges et bordure partagent exactement leurs sommets,
// comme le reste du projet (règle de la topologie partagée).
//
// Reproductibilité : la composition est générée exactement comme sans
// baie (mêmes tirages, dans le même ordre), mais aux dimensions de la
// fenêtre. Les tirages propres à la baie (couleur des losanges, matière
// des nouvelles pièces) utilisent leur propre Random, dérivé du seed :
// ajouter une baie ne change pas les tirages de la composition.

/** Ton du fond en losanges. */
enum class QuarryTone {
    /** Vitrerie claire (la convention) : verre pâle, à peine teinté de la couleur principale de la saison. */
    CLEAR,
    /** Fond sombre : la famille la plus sombre de la palette (sans descendre jusqu'au presque noir). */
    DARK,
    /**
     * Fond sombre de la couleur de la saison (réglage conseillé) : la
     * couleur principale assombrie, en gardant sa teinte et sa saturation
     * (bleu profond pour la palette mariale, vert profond pour le temps
     * ordinaire…). La croix blanche s'y lit comme une croix de lumière ;
     * les rayons trop proches de ce fond sont retirés (raysFamiliesFor).
     * Le nom MUTED est historique : la première version mêlait la couleur
     * à un gris sombre, ce qui la rendait terne (bleu gris, vert gris…).
     * Exceptions : Noël (or pâle), Rose (rose pâle) et la palette noire,
     * déjà grise (mélange au gris conservé).
     */
    MUTED
}

/** Bordure de la baie. */
enum class BorderMode {
    /** Pièces alternées : couleur principale de la saison et accent (or, argent…). */
    ALTERNATE,
    /** Bordure très sobre : verre noir, qui se lit comme un épais cadre noir. */
    BLACK,
    /**
     * Pas de bordure : les losanges du fond vont jusqu'au grand plomb du
     * pourtour. L'emblème et les losanges gardent la même taille et la
     * même place qu'avec bordure.
     */
    NONE
}

enum class ArchShape {
    /** Arc brisé (gothique) : deux arcs de cercle qui se rejoignent en pointe. */
    POINTED,
    /** Plein cintre (roman) : un demi-cercle. */
    ROUND
}

data class BayStyle(
    val arch: ArchShape = ArchShape.POINTED,
    /** Hauteur / largeur de la baie : 1.5 = format 2:3. */
    val aspect: Double = 1.5,
    /** Marge autour de la baie, en part du canevas. */
    val marginRatio: Double = 0.03,
    /** Largeur de la bordure, en part de la largeur de la baie. */
    val borderRatio: Double = 0.065,
    /** Longueur visée d'une pièce de bordure, en largeurs de bordure. */
    val borderPieceRatio: Double = 1.5,
    /** Deux familles de teintes, alternées le long de la bordure. */
    val borderShades: List<List<ColorRGBa>>,
    /** false = pas de bordure (voir BorderMode.NONE). */
    val showBorder: Boolean = true,
    /** Teintes des losanges du fond. */
    val quarryShades: List<ColorRGBa>,
    /** Nombre de losanges sur la largeur de la fenêtre. */
    val quarryColumns: Int = 4,
    /** Hauteur / largeur d'un losange. */
    val quarrySlope: Double = 1.6,
    /**
     * Barlotières (barres de fer horizontales) : leur hauteur, en part de
     * la partie droite de la fenêtre (0 = naissance de l'arc, 1 = bas).
     * Par défaut une seule, sous l'emblème ; liste vide = aucune.
     */
    val saddleBarPositions: List<Double> = listOf(0.74),
    val saddleBarColor: ColorRGBa = ColorRGBa.fromHex("#181412"),
    /** Arc brisé : écart des deux centres, en part de la largeur (0 = plein cintre). */
    val pointedOffsetRatio: Double = 0.2,
    /**
     * Agrandissement de l'emblème dans la fenêtre : la composition est
     * générée dans un rectangle emblemScale fois plus grand que la
     * fenêtre, centré en largeur et calé à 42 % de la hauteur (le centre
     * de la croix et la poitrine de l'ange restent au même endroit) ; ce
     * qui dépasse de la fenêtre est coupé.
     */
    val emblemScale: Double = 1.2,
    /**
     * Déplacement vertical de l'emblème, en part de la hauteur de la
     * fenêtre (positif = vers le bas). 0 = position d'origine. Le fond en
     * losanges couvre toute la fenêtre quelle que soit cette valeur ; il
     * faut seulement que l'emblème ne sorte pas par le bas.
     */
    val emblemOffsetRatio: Double = 0.0
) {
    companion object {
        /**
         * Or de la bordure alternée (accent par défaut de forLiturgical) :
         * un jaune d'or vif. L'or des auréoles (ANGEL_HALO_SHADES, #D9A441),
         * utilisé d'abord, tirait vers l'ocre et paraissait terne en
         * bordure.
         */
        val BORDER_GOLD: List<ColorRGBa> = listOf(
            ColorRGBa.fromHex("#F2C230"), ColorRGBa.fromHex("#F6CF4A"), ColorRGBa.fromHex("#E9B526")
        )

        /** Fond rose pâle de la baie pour la palette rose (voir forLiturgical). */
        val ROSE_PALE_QUARRIES: List<ColorRGBa> = listOf(
            ColorRGBa.fromHex("#F2D3DE"), ColorRGBa.fromHex("#F6DCE5"),
            ColorRGBa.fromHex("#EFCAD7"), ColorRGBa.fromHex("#F8E3EA")
        )

        /**
         * Baie aux couleurs d'une palette liturgique. Bordure : la couleur
         * principale lisible de la palette (coloredMainShades, Palette.kt)
         * alternée avec un accent (BORDER_GOLD par défaut ; argent conseillé
         * pour la palette noire). Fond en losanges :
         * - CLEAR : vitrerie claire, la couleur principale
         *   mêlée à 80 % d'un blanc légèrement gris ;
         * - DARK : la famille la plus sombre de la palette, sans descendre
         *   jusqu'au presque noir (le plomb doit rester visible) ;
         * - MUTED (par défaut) : la couleur principale assombrie (luminosité
         *   0,28), teinte et saturation conservées — voir QuarryTone.MUTED.
         *   Palette noire, déjà grise : mêlée à 70 % d'un gris sombre.
         *   Exceptions, pour les fêtes joyeuses (joie plutôt que contraste) :
         *   quand la couleur principale est claire (l'or de Noël), le gris
         *   la rendrait kaki et terne ; le fond est alors l'or pâle du motif
         *   de Pâques. Pour la palette rose (Gaudete, Laetare), le gris
         *   donnerait un mauve grisâtre ; le fond est un rose pâle
         *   (ROSE_PALE_QUARRIES).
         * Bordure : ALTERNATE (par défaut), BLACK (verre noir, très sobre) ou
         * NONE (pas de bordure).
         * Dans tous les cas, quatre teintes assez écartées pour que les
         * losanges ne paraissent pas tous identiques. Pour la croix, voir
         * aussi raysFamiliesFor : les rayons trop proches du fond en sont
         * retirés.
         */
        fun forLiturgical(
            palette: LiturgicalPalette,
            arch: ArchShape = ArchShape.POINTED,
            accent: List<ColorRGBa> = BORDER_GOLD,
            tone: QuarryTone = QuarryTone.MUTED,
            border: BorderMode = BorderMode.ALTERNATE
        ): BayStyle {
            val main = palette.coloredMainShades()
            // bordure : si l'accent est presque de la même valeur que la
            // couleur principale (l'or de Noël avec l'or par défaut), on
            // alterne plutôt avec la famille la plus claire de la palette
            val accentShades = if (abs(luminance(accent.first()) - luminance(main.first())) < 0.15)
                palette.families.maxByOrNull { f -> f.shades.map { c -> luminance(c) }.average() }!!.shades
            else accent
            val quarries = when (tone) {
                QuarryTone.CLEAR -> {
                    val base = main.first().mix(ColorRGBa.fromHex("#E6E8E1"), 0.80)
                    listOf(base, base.shade(0.95), base.shade(0.90), base.mix(ColorRGBa.WHITE, 0.20))
                }
                QuarryTone.DARK -> {
                    fun lum(f: PaletteFamily): Double = f.shades.map { c -> luminance(c) }.average()
                    val candidates = palette.families.filter { lum(it) >= 0.15 }.ifEmpty { palette.families }
                    val base = candidates.minByOrNull { lum(it) }!!.shades.first()
                    listOf(base, base.shade(0.90), base.mix(main.first(), 0.25), base.mix(ColorRGBa.fromHex("#808080"), 0.20))
                }
                QuarryTone.MUTED -> {
                    val m = main.first()
                    if (palette == LiturgicalPalettes.ROSE) {
                        ROSE_PALE_QUARRIES
                    } else if (luminance(m) > 0.60) {
                        // couleur principale claire (or de Noël) : on garde un
                        // fond lumineux, l'or pâle du motif de Pâques
                        // (BotanicalPalettes.PAQUES_FLEURS, Botanical.kt) — la
                        // joie de la fête prime sur le contraste de la croix
                        BotanicalPalettes.PAQUES_FLEURS.background
                    } else if (hsl(m)[1] >= 0.15) {
                        // couleur de la saison assombrie (luminosité 0,28),
                        // teinte et saturation conservées ; quatre variantes
                        // assez écartées pour que l'œil distingue les losanges
                        // (plus sombre, plus claire et moins saturée, teinte
                        // décalée)
                        val (h, s, _) = hsl(m)
                        listOf(fromHsl(h, s, 0.28), fromHsl(h, s, 0.22), fromHsl(h, s * 0.85, 0.35), fromHsl((h + 0.02) % 1.0, s, 0.30))
                    } else {
                        // palette noire : déjà grise, mélange au gris sombre
                        val base = m.mix(ColorRGBa.fromHex("#383C3A"), 0.70)
                        listOf(base, base.shade(0.90), base.mix(ColorRGBa.fromHex("#5A5E5C"), 0.30), base.mix(m, 0.15))
                    }
                }
            }
            val borderShades = when (border) {
                BorderMode.ALTERNATE -> listOf(main, accentShades)
                BorderMode.BLACK -> listOf(listOf(ColorRGBa.fromHex("#16120F"), ColorRGBa.fromHex("#1B1714"), ColorRGBa.fromHex("#120F0D")))
                BorderMode.NONE -> listOf(main) // sans objet : pas de bordure
            }
            return BayStyle(arch = arch, borderShades = borderShades, quarryShades = quarries,
                showBorder = border != BorderMode.NONE)
        }

        private fun luminance(c: ColorRGBa): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

        /** Teinte (0–1), saturation et luminosité HSL d'une couleur. */
        private fun hsl(c: ColorRGBa): DoubleArray {
            val mx = max(c.r, max(c.g, c.b)); val mn = min(c.r, min(c.g, c.b))
            val l = (mx + mn) / 2
            if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
            val d = mx - mn
            val s = if (l <= 0.5) d / (mx + mn) else d / (2.0 - mx - mn)
            val h = when (mx) {
                c.r -> ((c.g - c.b) / d).let { if (it < 0) it + 6 else it }
                c.g -> (c.b - c.r) / d + 2
                else -> (c.r - c.g) / d + 4
            } / 6.0
            return doubleArrayOf(h, s, l)
        }

        /** Couleur à partir de sa teinte (0–1), saturation et luminosité HSL. */
        private fun fromHsl(h: Double, s: Double, l: Double): ColorRGBa {
            if (s == 0.0) return fromHsl(0.0, 1e-9, l)
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun channel(t0: Double): Double {
                val t = ((t0 % 1.0) + 1.0) % 1.0
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            // Construite par fromHex, comme toutes les couleurs des palettes :
            // le constructeur ColorRGBa(r, g, b, alpha) déclare la couleur
            // LINÉAIRE par défaut, et OpenRNDR la convertit alors en sRGB à
            // l'affichage, ce qui l'éclaircit fortement (un bleu profond
            // devenait bleu ciel).
            fun hex2(v: Double) = "%02X".format(Math.round(v.coerceIn(0.0, 1.0) * 255).toInt())
            return ColorRGBa.fromHex("#" + hex2(channel(h + 1.0 / 3)) + hex2(channel(h)) + hex2(channel(h - 1.0 / 3)))
        }
    }

    /**
     * Familles de la palette qui restent lisibles sur ce fond : celles dont
     * le contraste de luminance (au sens WCAG) avec la teinte moyenne des
     * losanges atteint minContrast. À donner à la palette des RAYONS de la
     * croix (voir crossBayRecipe, TemplateProgram.kt) : un rayon de la
     * couleur du fond s'y fondrait. Si aucune famille ne passe, on garde
     * toute la palette.
     */
    fun raysFamiliesFor(families: List<PaletteFamily>, minContrast: Double = 2.0): List<PaletteFamily> {
        fun lin(x: Double) = if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        fun rel(c: ColorRGBa) = 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
        val bg = quarryShades.map { rel(it) }.average()
        fun contrast(c: ColorRGBa): Double { val l = rel(c); return (max(l, bg) + 0.05) / (min(l, bg) + 0.05) }
        return families.filter { f -> f.shades.map { contrast(it) }.average() >= minContrast }.ifEmpty { families }
    }
}

/** Assombrit une couleur (facteur < 1). */
private fun ColorRGBa.shade(f: Double) = ColorRGBa(r * f, g * f, b * f, alpha)

/** Mélange deux couleurs (t = part de `other`). */
private fun ColorRGBa.mix(other: ColorRGBa, t: Double) =
    ColorRGBa(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t, alpha)

/** Géométrie d'une baie dans un canevas donné. */
class BayGeometry(
    val x0: Double,
    val y0: Double,
    val width: Double,
    val height: Double,
    /** Contour extérieur de la baie (le grand plomb du pourtour). */
    val outer: List<Vector2>,
    /** Contour de la fenêtre (intérieur de la bordure). */
    val window: List<Vector2>,
    /** Indices des points de `window` où la bordure est coupée. */
    val borderCutIndices: List<Int>,
    /** Rectangle englobant de la fenêtre : la composition y est générée. */
    val contentX: Double,
    val contentY: Double,
    val contentWidth: Double,
    val contentHeight: Double,
    /** Rectangle où la composition est réellement générée (voir emblemScale). */
    val generationX: Double,
    val generationY: Double,
    val generationWidth: Double,
    val generationHeight: Double,
    /** Ordonnée des naissances de l'arc (fin des côtés droits) de la fenêtre. */
    val springY: Double,
    val bottomY: Double
)

/** Rectangle de la baie (format style.aspect) centré dans le canevas. */
private fun bayRect(style: BayStyle, canvasWidth: Double, canvasHeight: Double): DoubleArray {
    var h = canvasHeight * (1 - 2 * style.marginRatio)
    var w = h / style.aspect
    val maxW = canvasWidth * (1 - 2 * style.marginRatio)
    if (w > maxW) {
        w = maxW
        h = w * style.aspect
    }
    return doubleArrayOf((canvasWidth - w) / 2, (canvasHeight - h) / 2, w, h)
}

/**
 * Échelle des valeurs en pixels absolus (plomb, courbure) pour une baie :
 * rapport entre la largeur de la baie dans ce canevas et sa largeur dans
 * l'aperçu de référence (768 × 576). L'aperçu (baie haute, au milieu de
 * la fenêtre en paysage) et l'export (au format 2:3) gardent ainsi les
 * mêmes proportions de plomb.
 */
fun bayPixelScale(style: BayStyle, canvasWidth: Double, canvasHeight: Double): Double =
    bayRect(style, canvasWidth, canvasHeight)[2] / bayRect(style, 768.0, 576.0)[2]

private class ArchHalf(val points: List<Vector2>, val corner: Int, val springY: Double)

/**
 * Moitié gauche d'un contour en arc, rentré de `inset` : du milieu du bas,
 * par le coin bas gauche et le côté gauche, jusqu'au sommet de l'arc.
 * Les côtés droits sont échantillonnés (pas `step`) pour que la bordure
 * puisse y être coupée.
 */
private fun archLeftHalf(style: BayStyle, x0: Double, y0: Double, w: Double, h: Double, inset: Double, step: Double): ArchHalf {
    val cx = x0 + w / 2
    val bottom = y0 + h - inset
    val left = x0 + inset
    val pts = mutableListOf<Vector2>()
    // bas, du milieu vers le coin gauche
    val nb = max(2, ceil((cx - left) / step).toInt())
    for (i in 0..nb) pts.add(Vector2(cx - (cx - left) * i / nb, bottom))
    val corner = pts.lastIndex
    // naissance de l'arc et arc
    val arcPts = mutableListOf<Vector2>()
    val springY: Double
    when (style.arch) {
        ArchShape.ROUND -> {
            val r = w / 2 - inset
            springY = y0 + w / 2
            val n = 48
            for (i in 0..n) {
                val th = PI - PI / 2 * i / n
                arcPts.add(Vector2(cx + r * cos(th), springY - r * sin(th)))
            }
        }
        ArchShape.POINTED -> {
            val c = style.pointedOffsetRatio * w
            val bigR = w / 2 + c
            springY = y0 + sqrt(bigR * bigR - c * c)
            val r = bigR - inset
            val center = Vector2(cx + c, springY)
            // de l'angle π (côté gauche) jusqu'au sommet (x = cx)
            val thApex = acos(-c / r)
            val n = 48
            for (i in 0..n) {
                val th = PI - (PI - thApex) * i / n
                arcPts.add(Vector2(center.x + r * cos(th), center.y - r * sin(th)))
            }
        }
    }
    // côté gauche, du coin bas jusqu'à la naissance de l'arc
    val ns = max(2, ceil((bottom - springY) / step).toInt())
    for (i in 1 until ns) pts.add(Vector2(left, bottom - (bottom - springY) * i / ns))
    pts.addAll(arcPts)
    return ArchHalf(pts, corner, springY)
}

/** Contour fermé symétrique à partir de sa moitié gauche (sans doublon au milieu du bas ni au sommet). */
private fun mirrorClose(leftHalf: List<Vector2>, cx: Double): List<Vector2> {
    val m = leftHalf.size
    return leftHalf + (m - 2 downTo 1).map { Vector2(2 * cx - leftHalf[it].x, leftHalf[it].y) }
}

fun bayGeometry(style: BayStyle, canvasWidth: Double, canvasHeight: Double): BayGeometry {
    val rect = bayRect(style, canvasWidth, canvasHeight)
    val x0 = rect[0]; val y0 = rect[1]; val w = rect[2]; val h = rect[3]
    val b = style.borderRatio * w
    val cx = x0 + w / 2
    val outer = mirrorClose(archLeftHalf(style, x0, y0, w, h, 0.0, b / 2).points, cx)
    val half = archLeftHalf(style, x0, y0, w, h, b, b / 3)
    val leftHalf = half.points
    val window = mirrorClose(leftHalf, cx)

    // Coupes de la bordure : réparties régulièrement sur la moitié gauche
    // (du milieu du bas jusqu'au sommet), puis recopiées en miroir. La
    // pièce du milieu du bas est à cheval sur l'axe (coupes à ± une
    // demi-pièce). Le coin bas et le sommet de l'arc brisé sont toujours
    // coupés ; une coupe régulière trop proche d'eux est abandonnée.
    val m = leftHalf.size
    val cum = DoubleArray(m)
    for (i in 1 until m) cum[i] = cum[i - 1] + leftHalf[i].distanceTo(leftHalf[i - 1])
    val halfLen = cum[m - 1]
    val target = style.borderPieceRatio * b
    val pieces = max(2, (halfLen / target).roundToInt())
    val fixed = mutableListOf(half.corner)
    if (style.arch == ArchShape.POINTED) fixed.add(m - 1)
    val cutsLeft = sortedSetOf<Int>()
    cutsLeft.addAll(fixed)
    for (k in 0 until pieces) {
        val s = (k + 0.5) * halfLen / pieces
        var best = 1
        for (i in 1 until m - 1) if (abs(cum[i] - s) < abs(cum[best] - s)) best = i
        if (fixed.any { abs(cum[it] - cum[best]) < 0.4 * target }) continue
        cutsLeft.add(best)
    }
    val n = window.size
    val cutIndices = (cutsLeft.toList() + cutsLeft.filter { it in 1..(m - 2) }.map { 2 * m - 2 - it })
        .distinct().filter { it in 0 until n }

    val minX = window.minOf { it.x }; val maxX = window.maxOf { it.x }
    val minY = window.minOf { it.y }; val maxY = window.maxOf { it.y }
    val cw = maxX - minX
    val ch = maxY - minY
    val sc = style.emblemScale
    return BayGeometry(
        x0, y0, w, h, outer, window, cutIndices,
        minX, minY, cw, ch,
        generationX = minX - (sc - 1) * cw / 2,
        generationY = minY - (sc - 1) * ch * 0.42 + style.emblemOffsetRatio * ch,
        generationWidth = cw * sc,
        generationHeight = ch * sc,
        springY = half.springY, bottomY = y0 + h - b
    )
}

// ---------------- découpe

/** Coupe le segment [a, b] par un polygone CONVEXE ; null s'il est dehors. */
private fun clipSegmentConvex(a: Vector2, b: Vector2, poly: List<Vector2>): Pair<Vector2, Vector2>? {
    val orientation = if (signedArea(poly) >= 0) 1.0 else -1.0
    var t0 = 0.0
    var t1 = 1.0
    val d = Vector2(b.x - a.x, b.y - a.y)
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        // normale intérieure de l'arête p → q
        val nx = -(q.y - p.y) * orientation
        val ny = (q.x - p.x) * orientation
        val num = nx * (a.x - p.x) + ny * (a.y - p.y)
        val den = nx * d.x + ny * d.y
        if (abs(den) < 1e-12) {
            if (num < 0) return null
            continue
        }
        val t = -num / den
        if (den > 0) t0 = max(t0, t) else t1 = min(t1, t)
        if (t0 > t1) return null
    }
    if (t1 - t0 < 1e-9) return null
    return Vector2(a.x + d.x * t0, a.y + d.y * t0) to Vector2(a.x + d.x * t1, a.y + d.y * t1)
}

/** Paramètre t (0..1) de l'intersection de [a, b] avec [c, d], ou null. */
private fun segmentParam(a: Vector2, b: Vector2, c: Vector2, d: Vector2): Double? {
    val rx = b.x - a.x; val ry = b.y - a.y
    val sx = d.x - c.x; val sy = d.y - c.y
    val den = rx * sy - ry * sx
    if (abs(den) < 1e-12) return null
    val qx = c.x - a.x; val qy = c.y - a.y
    val t = (qx * sy - qy * sx) / den
    val u = (qx * ry - qy * rx) / den
    if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null
    return t.coerceIn(0.0, 1.0)
}

/** Distance d'un point au contour d'un polygone. */
private fun distanceToPolygon(p: Vector2, poly: List<Vector2>): Double {
    var best = Double.MAX_VALUE
    for (i in poly.indices) {
        val a = poly[i]
        val c = poly[(i + 1) % poly.size]
        val dx = c.x - a.x; val dy = c.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
        best = min(best, hypot(a.x + dx * t - p.x, a.y + dy * t - p.y))
    }
    return best
}

private fun bounds(poly: List<Vector2>) = doubleArrayOf(poly.minOf { it.x }, poly.minOf { it.y }, poly.maxOf { it.x }, poly.maxOf { it.y })

private fun inBounds(b: DoubleArray, p: Vector2) = p.x >= b[0] && p.x <= b[2] && p.y >= b[1] && p.y <= b[3]

/**
 * Assemble la baie autour d'une composition déjà générée (cellules,
 * couleurs et matière dans le repère de la fenêtre) : renvoie les pièces
 * définitives (emblème, losanges, bordure), leurs couleurs et leur matière.
 */
fun assembleBay(
    config: VitrailConfig,
    style: BayStyle,
    geometry: BayGeometry,
    contentCells: List<List<Vector2>>,
    contentColors: List<ColorRGBa>,
    contentMaterial: List<Double>
): Triple<List<List<Vector2>>, List<ColorRGBa>, List<Double>> {

    val window = geometry.window
    val outer = geometry.outer
    // l'emblème, ramené dans le repère du canevas
    val cells = contentCells.map { c -> c.map { Vector2(it.x + geometry.generationX, it.y + geometry.generationY) } }
    val cellBounds = cells.map { bounds(it) }

    // partie vitrée par l'emblème et les losanges : la fenêtre, ou toute
    // la baie quand il n'y a pas de bordure
    val glass = if (style.showBorder) window else outer
    val segments = mutableListOf<Segment>()
    // contours de la baie et de la fenêtre
    segments += polygonEdgeSegments(outer)
    if (style.showBorder) segments += polygonEdgeSegments(window)
    // coupes de la bordure (vers l'extérieur ; le bout qui dépasse est élagué)
    val b = style.borderRatio * geometry.width
    val orientation = if (signedArea(window) >= 0) 1.0 else -1.0
    for (i in (if (style.showBorder) geometry.borderCutIndices else emptyList())) {
        val p = window[i]
        val prev = window[(i - 1 + window.size) % window.size]
        val next = window[(i + 1) % window.size]
        // normale extérieure moyenne des deux arêtes voisines
        fun outward(a: Vector2, c: Vector2): Vector2 {
            val l = a.distanceTo(c)
            return Vector2((c.y - a.y) / l * orientation, -(c.x - a.x) / l * orientation)
        }
        val n1 = outward(prev, p)
        val n2 = outward(p, next)
        var nx = n1.x + n2.x
        var ny = n1.y + n2.y
        val l = hypot(nx, ny)
        nx /= l; ny /= l
        // un coin (bas) : la coupe va jusqu'au coin extérieur, en diagonale
        val corner = n1.x * n2.x + n1.y * n2.y < 0.5
        val reach = if (corner) b * sqrt(2.0) * 1.3 else b * 1.8
        segments += Segment(p, Vector2(p.x + nx * reach, p.y + ny * reach))
    }
    // arêtes de l'emblème, coupées par la fenêtre. Les découpages
    // rayonnants ont des jonctions en T (le sommet d'une pièce posé au
    // milieu de l'arête de sa voisine) : chaque arête est d'abord coupée
    // aux sommets qui tombent dessus, et les doublons sont retirés, sinon
    // deux arêtes superposées ne se croisent pas et le découpage final
    // dépendrait de la résolution.
    val tol = 1e-9 * geometry.width
    val vertices = cells.flatten().distinctBy { Math.round(it.x / tol) to Math.round(it.y / tol) }
    val seen = HashSet<String>()
    fun key(p: Vector2) = "${Math.round(p.x / (tol * 1000))}_${Math.round(p.y / (tol * 1000))}"
    for (cell in cells) for (k in cell.indices) {
        val a = cell[k]
        val c = cell[(k + 1) % cell.size]
        val dx = c.x - a.x; val dy = c.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) continue
        val ts = mutableListOf(0.0, 1.0)
        for (v in vertices) {
            val t = ((v.x - a.x) * dx + (v.y - a.y) * dy) / l2
            if (t <= 1e-9 || t >= 1 - 1e-9) continue
            val d = abs((v.x - a.x) * dy - (v.y - a.y) * dx) / sqrt(l2)
            if (d < tol * 1000) ts.add(t)
        }
        ts.sort()
        for (j in 0 until ts.size - 1) {
            val p0 = Vector2(a.x + dx * ts[j], a.y + dy * ts[j])
            val p1 = Vector2(a.x + dx * ts[j + 1], a.y + dy * ts[j + 1])
            val k0 = key(p0); val k1 = key(p1)
            if (k0 == k1 || !seen.add(if (k0 < k1) "$k0|$k1" else "$k1|$k0")) continue
            val clipped = clipSegmentConvex(p0, p1, glass) ?: continue
            segments += Segment(clipped.first, clipped.second)
        }
    }
    // losanges du fond : deux familles de droites, gardées hors de l'emblème
    val dw = geometry.contentWidth / style.quarryColumns
    val dh = dw * style.quarrySlope
    val cx = geometry.contentX + geometry.contentWidth / 2
    val top = geometry.contentY
    val bottom = geometry.contentY + geometry.contentHeight
    val kMax = ceil((geometry.contentWidth / dw) + (geometry.contentHeight / dh)).toInt() + 2
    val emblemEdges = cells.flatMap { c -> c.indices.map { c[it] to c[(it + 1) % c.size] } }
    for (sign in listOf(1.0, -1.0)) for (k in -kMax..kMax) {
        // x = cx + dw * (k − sign · (y − top) / dh)
        val a = Vector2(cx + dw * (k - sign * (-dh - 0.0) / dh), top - dh)
        val e = Vector2(cx + dw * (k - sign * (bottom + dh - top) / dh), bottom + dh)
        val clipped = clipSegmentConvex(a, e, glass) ?: continue
        val (p0, q0) = clipped
        val len0 = p0.distanceTo(q0)
        if (len0 < 1e-9) continue
        // Sans bordure, la ligne est prolongée d'un cheveu au-delà du bord :
        // elle coupe alors franchement le contour de la baie au lieu de
        // s'arrêter pile dessus, ce qui rendait le découpage différent à
        // l'aperçu et à l'export. Le bout qui dépasse ne ferme aucune pièce
        // et est élagué.
        val ext = if (style.showBorder) 0.0 else 1e-3 * geometry.width
        val ux = (q0.x - p0.x) / len0; val uy = (q0.y - p0.y) / len0
        val p = Vector2(p0.x - ux * ext, p0.y - uy * ext)
        val q = Vector2(q0.x + ux * ext, q0.y + uy * ext)
        val len = len0 + 2 * ext
        fun at(t: Double) = Vector2(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        // Échardes au bord : un tronçon de la ligne (entre deux croisements
        // du treillis, ou entre un croisement et le bord) qui longe le bord
        // de très près — ligne presque tangente à l'arc — découperait une
        // pièce longue et très fine. Ces tronçons sont retirés : l'écharde
        // rejoint le losange voisin. Critère : le milieu du tronçon est à
        // moins de 0,13 × sa longueur du bord (angle d'environ 15°).
        // Croisements avec l'autre famille : y = top + dh·(k − k')/(2·sign).
        val vts = (-kMax..kMax).map { k2 -> (top + dh * (k - k2) / (2 * sign) - p.y) / (q.y - p.y) }
            .filter { it > 1e-6 && it < 1 - 1e-6 }.sorted()
        val cuts = listOf(0.0) + vts + listOf(1.0)
        val allowed = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until cuts.size - 1) {
            val ta = cuts[i]; val tb = cuts[i + 1]
            val l = (tb - ta) * len
            if (l <= 0.0) continue
            if (distanceToPolygon(at((ta + tb) / 2), glass) < 0.13 * l) continue
            if (allowed.isNotEmpty() && abs(allowed.last().second - ta) < 1e-12) allowed[allowed.lastIndex] = allowed.last().first to tb
            else allowed.add(ta to tb)
        }
        for ((ta0, tb0) in allowed) {
            // coupée là où elle rencontre l'emblème ; on garde les morceaux hors de l'emblème
            val ts = mutableListOf(ta0, tb0)
            for ((c1, c2) in emblemEdges) segmentParam(p, q, c1, c2)?.let { if (it > ta0 && it < tb0) ts.add(it) }
            ts.sort()
            for (j in 0 until ts.size - 1) {
                val t0 = ts[j]; val t1 = ts[j + 1]
                if (t1 - t0 < 1e-9) continue
                val m0 = at(t0)
                val m1 = at(t1)
                // un bout trop court entre deux pièces de l'emblème ferait une écharde
                if (m0.distanceTo(m1) < 0.30 * dw) continue
                val mid = Vector2((m0.x + m1.x) / 2, (m0.y + m1.y) / 2)
                val insideEmblem = cells.indices.any { i -> inBounds(cellBounds[i], mid) && polygonContains(cells[i], mid) }
                if (!insideEmblem) segments += Segment(m0, m1)
            }
        }
    }

    // L'ordre des faces renvoyé par polygonizeSegments peut varier avec la
    // résolution : on les trie par position (relative à la baie) pour que
    // les tirages de couleur tombent sur les mêmes pièces à l'aperçu et à
    // l'export.
    fun rank(face: List<Vector2>): Vector2 {
        val c = polygonCentroid(face)
        return Vector2((c.x - geometry.x0) / geometry.width, (c.y - geometry.y0) / geometry.width)
    }
    val faces = polygonizeSegments(segments, 1.0).map { it to rank(it) }.sortedWith { f1, f2 ->
        val a = f1.second
        val c = f2.second
        if (abs(a.y - c.y) > 1e-9) a.y.compareTo(c.y) else a.x.compareTo(c.x)
    }.map { it.first }

    // Rôle de chaque pièce : emblème (cellule d'origine), losange, bordure.
    val rnd = Random(config.seed * 7919 + 17)
    val windowLength = DoubleArray(window.size + 1)
    for (i in 1..window.size) windowLength[i] = windowLength[i - 1] + window[i - 1].distanceTo(window[i % window.size])
    fun alongWindow(p: Vector2): Double {
        var best = Double.MAX_VALUE
        var bestS = 0.0
        for (i in window.indices) {
            val a = window[i]
            val c = window[(i + 1) % window.size]
            val dx = c.x - a.x; val dy = c.y - a.y
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(a.x + dx * t - p.x, a.y + dy * t - p.y)
            if (d < best) { best = d; bestS = windowLength[i] + t * sqrt(l2) }
        }
        return bestS
    }
    val outCells = mutableListOf<List<Vector2>>()
    val outColors = mutableListOf<ColorRGBa>()
    val outMaterial = mutableListOf<Double>()
    val borderFaces = mutableListOf<Pair<Double, List<Vector2>>>()
    for (face in faces) {
        val p = interiorPoint(face)
        if (!polygonContains(outer, p)) continue
        if (style.showBorder && !polygonContains(window, p)) {
            borderFaces.add(alongWindow(polygonCentroid(face)) to face)
            continue
        }
        val i = cells.indices.firstOrNull { inBounds(cellBounds[it], p) && polygonContains(cells[it], p) }
        outCells.add(face)
        if (i != null) {
            outColors.add(contentColors[i])
            outMaterial.add(contentMaterial[i])
        } else {
            outColors.add(style.quarryShades[rnd.nextInt(style.quarryShades.size)])
            outMaterial.add(rnd.nextDouble())
        }
    }
    // bordure : familles alternées le long du pourtour
    borderFaces.sortBy { it.first }
    borderFaces.forEachIndexed { k, (_, face) ->
        val family = style.borderShades[k % style.borderShades.size]
        outCells.add(face)
        outColors.add(family[rnd.nextInt(family.size)])
        outMaterial.add(rnd.nextDouble())
    }
    return Triple(outCells, outColors, outMaterial)
}

/**
 * Après le plomb : grand plomb du pourtour (plus épais) et barlotières,
 * barres de fer horizontales réparties sur la partie droite de la baie.
 */
fun drawBayIron(drawer: Drawer, style: BayStyle, geometry: BayGeometry, leadWidth: Double) {
    fun polyline(points: List<Vector2>, closed: Boolean): ShapeContour {
        val pts = if (closed) points + points.first() else points
        val segments = (0 until pts.size - 1).map { k ->
            val a = pts[k]
            val b = pts[k + 1]
            Segment2D(a, Vector2(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3),
                Vector2(a.x + 2 * (b.x - a.x) / 3, a.y + 2 * (b.y - a.y) / 3), b)
        }
        return ShapeContour.fromSegments(segments, closed = false)
    }
    drawer.isolated {
        drawer.shadeStyle = null
        drawer.fill = null
        drawer.stroke = ColorRGBa.BLACK
        drawer.strokeWeight = leadWidth * 2.2
        drawer.lineCap = LineCap.ROUND
        drawer.contour(polyline(geometry.outer, closed = true))
        drawer.stroke = style.saddleBarColor
        drawer.strokeWeight = leadWidth * 1.9
        drawer.lineCap = LineCap.BUTT
        for (t in style.saddleBarPositions) {
            val y = geometry.springY + (geometry.bottomY - geometry.springY) * t
            drawer.contour(polyline(listOf(Vector2(geometry.x0, y), Vector2(geometry.x0 + geometry.width, y)), closed = false))
        }
    }
}