import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import kotlin.random.Random

// --------------------------------------------------
// SYSTÈME 5 — VERRE
// "Comment chaque morceau réagit-il à la lumière ?"
// --------------------------------------------------
//
// Cette version remplace le halo centré ("spot") de la première
// implémentation par un rendu pensé pour l'illusion recherchée :
// "un vitrail traversé par la lumière du jour", pas un dégradé doux
// façon gradient mesh. Diagnostic qui a motivé la refonte (posé et
// validé pixel par pixel en Python avant d'écrire le moindre GLSL,
// vu la difficulté de déboguer un shader à l'aveugle) :
//
// - L'ancienne vignette (centre lumineux à 0.05, sombre dès 0.75)
//   concentrait toute la lumière au milieu de chaque pièce, créant
//   un effet "boule lumineuse" bien plus qu'un panneau de verre
//   éclairé. Un vrai carreau de verre reste lumineux sur PRESQUE
//   toute sa surface, avec un rebord plus sombre seulement tout près
//   du plomb (ombre portée du came).
// - Le terme "directionalLight" d'origine utilisait un sinus sans
//   rapport avec la direction de lumière réelle (lightDirection) :
//   décoratif, pas cohérent avec le Système 4 (Plomb) ni avec le
//   dégradé global déjà ajouté. Il est remplacé par un vrai dégradé
//   directionnel local, calculé avec la MÊME direction.
// - La texture à deux échelles restait lisse (interpolation douce),
//   loin de l'aspect strié/irrégulier du vrai verre laminé visible
//   sur les références. Une composante de stries directionnelles
//   (bruit fortement étiré selon un axe) a été ajoutée, plus un
//   contraste renforcé (courbe de puissance) sur le bruit existant.
// - Aucun mécanisme ne poussait vers un vrai blanc "brûlé" dans les
//   zones les plus lumineuses — juste une multiplication RGB, qui
//   n'éclaircit jamais suffisamment une couleur saturée pour donner
//   l'impression d'une source de lumière derrière elle.
//
// ⚠ DEUXIÈME ITÉRATION — leçon importante à ne pas défaire :
//
// La première tentative pour corriger le point ci-dessus ajoutait un
// mélange explicite vers le blanc (et un bonus "éclat" additif) dans
// les zones très lumineuses. Résultat à l'usage : ça se lisait comme
// un REFLET posé sur la surface (une tache blanche achromatique, qui
// ignore la couleur du dessous) plutôt que comme de la lumière
// TRANSMISE à travers un verre coloré — au point de donner
// l'impression d'une photo posée au sol avec du soleil qui accroche
// dessus par endroits, pas d'un vitrail éclairé par-derrière.
//
// Correction : plus AUCUN mélange vers le blanc nulle part (ni pour
// la lumière, ni pour l'opalescence). Tout reste PUREMENT
// MULTIPLICATIF sur la couleur de base — une pièce très éclairée
// devient une version plus vive/éclatante de SA PROPRE teinte, jamais
// une tache blanche superposée.
//
// ⚠ TROISIÈME ITÉRATION — l'autre versant du même compromis :
//
// Après ce correctif, retour de terrain : "comme si le verre faisait
// 2-3 cm d'épaisseur et que peu de lumière passait au travers" — en
// gardant la base BIEN sous 1.0 (pour la visibilité de la texture sur
// blanc), l'ensemble du rendu était devenu trop sombre/mat dans
// l'absolu. Point important qui a débloqué le compromis : multiplier
// une couleur SATURÉE par une valeur bien au-dessus de 1.0 ne recrée
// PAS le problème de reflet — ça donne un rouge plus vif, pas un
// blanc posé dessus. Le problème du mélange-vers-blanc était
// spécifiquement le fait de mélanger vers de l'achromatique, pas la
// luminosité en elle-même. Seul le BLANC/les couleurs déjà très
// claires posent un dilemme réel (toute valeur ≥ 1.0 sature
// immédiatement, il n'y a pas de contournement propre). Réglage
// retenu : base remontée à un point où ~50% de la surface d'une
// pièce blanche dépasse 1.0 — mesuré en testant les deux extrêmes :
// ~6% avec la base trop basse (dim, "verre épais"), ~98% avec la
// base remontée sans discernement (plat, quasi blanc uni à nouveau).

data class GlassStyle(
    /** Intensité de la texture "matière" à deux échelles (mottled,
     *  contraste renforcé). */
    val textureStrength: Double = 0.26,
    /** Intensité des stries directionnelles (verre laminé/roulé). */
    val streakStrength: Double = 0.28,
    /** Angle des stries, en radians. Choix esthétique indépendant de
     *  lightDirection — l'orientation du laminage du verre n'a pas de
     *  raison physique d'être alignée avec la source de lumière. */
    val streakAngle: Double = 0.6,
    /** Intensité du dégradé directionnel LOCAL (au sein de chaque
     *  pièce), aligné sur lightDirection. */
    val localLightStrength: Double = 0.22,
    /** Direction de lumière partagée avec le Système 4 (Plomb) — voir
     *  LeadStyle.lightDirection. Les deux systèmes doivent utiliser la
     *  même valeur pour que l'ensemble se lise comme UNE SEULE source
     *  de lumière traversant tout le tableau, pas des pièces éclairées
     *  indépendamment. Sert ICI à la fois pour le dégradé local (par
     *  pièce) et le dégradé global (sur tout le canevas). */
    val lightDirection: Vector2 = Vector2(-0.35, -1.0),
    /** Intensité du dégradé de lumière global (0 = désactivé). Modéré
     *  par construction : il module la lumière locale déjà existante
     *  par cellule, il ne la remplace pas. Validé numériquement avant
     *  portage (plage typique du terme brut : environ [-0.4, 0.45]). */
    val globalLightStrength: Double = 0.18,
    /** Amplitude de la variation d'opalescence (matière plus laiteuse/
     *  translucide) d'une pièce à l'autre. Purement multiplicative
     *  (éclaircit la teinte, ne la lave pas vers le blanc — voir la
     *  note dans buildGlassFragmentShader). */
    val opalescenceStrength: Double = 0.10
)

interface GlassSystem {
    fun createShadeStyle(style: GlassStyle): ShadeStyle

    /**
     * Valeur d'opalescence par cellule (une par cellule, dans l'ordre
     * de la liste de cellules), consommée par le shader via le
     * paramètre "opalescence". Ce n'est pas juste une teinte : c'est
     * une propriété de MATIÈRE (verre plus laiteux vs plus clair),
     * donc logiquement du ressort du Système 5, pas de la Palette
     * (Système 3, qui décide seulement de la couleur).
     */
    fun assignMaterialVariation(cellCount: Int, random: Random): List<Double>
}

class ProceduralGlassSystem : GlassSystem {

    override fun createShadeStyle(style: GlassStyle): ShadeStyle {
        return shadeStyle {
            fragmentTransform = buildGlassFragmentShader(
                textureStrength = style.textureStrength,
                streakStrength = style.streakStrength,
                streakAngle = style.streakAngle,
                localLightStrength = style.localLightStrength,
                lightDirection = style.lightDirection,
                globalLightStrength = style.globalLightStrength,
                opalescenceStrength = style.opalescenceStrength
            )
        }
    }

    override fun assignMaterialVariation(cellCount: Int, random: Random): List<Double> {
        return List(cellCount) { random.nextDouble(0.0, 1.0) }
    }
}

fun buildGlassFragmentShader(
    textureStrength: Double,
    streakStrength: Double,
    streakAngle: Double,
    localLightStrength: Double,
    lightDirection: Vector2,
    globalLightStrength: Double,
    opalescenceStrength: Double
): String {
    return """

        vec2 uv = c_boundsPosition.xy;

        // Direction de lumière partagée (Système 4 + dégradé local + global)
        vec2 lightDir = normalize(vec2(${lightDirection.x}, ${lightDirection.y}));


        // --------------------------------------------------
        // 1. CHAMP LUMINEUX — plage haute large, rebord sombre
        //    seulement tout près du bord (pas un halo centré)
        // --------------------------------------------------
        //
        // Cinquième réglage (historique complet en en-tête de fichier)
        // — celui-ci corrige un défaut différent des précédents. Le
        // médaillon central (le disque du sunburst) apparaissait
        // complètement plat/blanc uni, alors que les quartiers de
        // rayon (allongés) montraient une bonne texture avec les
        // MÊMES réglages. Cause : "distanceFromCenter" est mesuré
        // dans les coordonnées normalisées de la BOUNDING BOX de la
        // cellule. Un cercle (le médaillon) n'a pas de "coins" dans
        // sa bounding box — donc, contrairement à une forme allongée,
        // la quasi-totalité de sa surface reste à faible distance du
        // centre, où fieldLight sature à 1.0. Mesuré sur le rendu réel
        // : 92% des pixels à l'intérieur du médaillon étaient à plus
        // de 250/255 de luminance, avec un écart-type de seulement
        // 1.8 (contre 21.4 sur une zone colorée) — un aplat, pas du
        // verre. Reproduit et confirmé sur un test en forme de cercle
        // isolé avant ce correctif (100% de saturation).
        //
        // Correctif : resserrer la plage du smoothstep (0.55–0.90 →
        // 0.25–0.55) et réduire la part de fieldLight dans le total
        // (0.55 → 0.30, compensé par une base constante plus haute)
        // — la vignette "descend" alors plus tôt et affecte une plus
        // grande partie de N'IMPORTE QUELLE forme, cercle compris,
        // au lieu de saturer sur les formes compactes. Validé : le
        // médaillon passe de 100% à 62% de saturation avec une texture
        // et un dégradé directionnel nettement visibles, sans dégrader
        // le rendu déjà bon sur les quartiers de rayon.

        float distanceFromCenter = distance(uv, p_cellCenter);

        float fieldLight = 1.0 - smoothstep(0.25, 0.55, distanceFromCenter);

        // Vrai dégradé directionnel LOCAL, aligné sur lightDir (au
        // lieu d'une ondulation décorative sans rapport)
        float localDirectional = dot(uv - vec2(0.5), lightDir);

        float light =
            0.78 +
            0.30 * fieldLight +
            localDirectional * $localLightStrength;


        // --------------------------------------------------
        // 2. TEXTURE DU VERRE — deux échelles, contraste renforcé
        // --------------------------------------------------

        vec2 noisePosition1 = uv * 3.5;
        vec2 noiseCell1 = floor(noisePosition1);
        vec2 noiseFraction1 = fract(noisePosition1);
        noiseFraction1 = noiseFraction1 * noiseFraction1 * (3.0 - 2.0 * noiseFraction1);

        float noiseA1 = fract(sin(dot(noiseCell1, vec2(127.1, 311.7))) * 43758.5453);
        float noiseB1 = fract(sin(dot(noiseCell1 + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453);
        float noiseC1 = fract(sin(dot(noiseCell1 + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
        float noiseD1 = fract(sin(dot(noiseCell1 + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);

        float noiseLarge = mix(
            mix(noiseA1, noiseB1, noiseFraction1.x),
            mix(noiseC1, noiseD1, noiseFraction1.x),
            noiseFraction1.y
        );

        vec2 noisePosition2 = uv * 13.0;
        vec2 noiseCell2 = floor(noisePosition2);
        vec2 noiseFraction2 = fract(noisePosition2);
        noiseFraction2 = noiseFraction2 * noiseFraction2 * (3.0 - 2.0 * noiseFraction2);

        float noiseA2 = fract(sin(dot(noiseCell2, vec2(269.5, 183.3))) * 43758.5453);
        float noiseB2 = fract(sin(dot(noiseCell2 + vec2(1.0, 0.0), vec2(269.5, 183.3))) * 43758.5453);
        float noiseC2 = fract(sin(dot(noiseCell2 + vec2(0.0, 1.0), vec2(269.5, 183.3))) * 43758.5453);
        float noiseD2 = fract(sin(dot(noiseCell2 + vec2(1.0, 1.0), vec2(269.5, 183.3))) * 43758.5453);

        float noiseFine = mix(
            mix(noiseA2, noiseB2, noiseFraction2.x),
            mix(noiseC2, noiseD2, noiseFraction2.x),
            noiseFraction2.y
        );

        float material = noiseLarge * 0.6 + noiseFine * 0.4 - 0.5;
        // Courbe de puissance pour accentuer les écarts (patches plus
        // nets) plutôt qu'un flou uniformément doux.
        material = sign(material) * pow(abs(material), 0.60);

        light *= 1.0 + material * $textureStrength;


        // --------------------------------------------------
        // 3. STRIES DIRECTIONNELLES (verre laminé/roulé)
        // --------------------------------------------------
        //
        // Bruit échantillonné sur des axes tournés puis fortement
        // compressé sur un axe : le motif varie vite dans un sens et
        // presque pas dans l'autre, ce qui donne de longues bandes
        // plutôt que des taches — l'aspect strié du verre laminé
        // visible sur les vitraux de référence (portes Art Nouveau,
        // panneau sunburst).

        float streakCos = cos($streakAngle);
        float streakSin = sin($streakAngle);
        float streakU = uv.x * streakCos - uv.y * streakSin;
        float streakV = (uv.x * streakSin + uv.y * streakCos) * 0.15;

        vec2 streakPosition = vec2(streakU, streakV) * 9.0;
        vec2 streakCell = floor(streakPosition);
        vec2 streakFraction = fract(streakPosition);
        streakFraction = streakFraction * streakFraction * (3.0 - 2.0 * streakFraction);

        float streakA = fract(sin(dot(streakCell, vec2(91.7, 143.3))) * 43758.5453);
        float streakB = fract(sin(dot(streakCell + vec2(1.0, 0.0), vec2(91.7, 143.3))) * 43758.5453);
        float streakC = fract(sin(dot(streakCell + vec2(0.0, 1.0), vec2(91.7, 143.3))) * 43758.5453);
        float streakD = fract(sin(dot(streakCell + vec2(1.0, 1.0), vec2(91.7, 143.3))) * 43758.5453);

        float streakNoise = mix(
            mix(streakA, streakB, streakFraction.x),
            mix(streakC, streakD, streakFraction.x),
            streakFraction.y
        );

        light *= 1.0 + (streakNoise - 0.5) * $streakStrength;


        // --------------------------------------------------
        // 4. LUMIÈRE GLOBALE COHÉRENTE (partagée avec le Système 4)
        // --------------------------------------------------
        //
        // p_canvasPosition est la position du CENTRE de la cellule
        // normalisée sur TOUT LE CANEVAS (0 à 1 sur toute l'image) —
        // un seul vecteur par cellule, pas un champ continu par pixel,
        // mais suffisant pour qu'un dégradé simule une unique source
        // de lumière traversant tout le vitrail. Même lightDir que le
        // dégradé local ci-dessus et que le reflet du Système 4.

        float globalGradient = dot(p_canvasPosition - vec2(0.5), lightDir);

        vec2 globalNoisePosition = p_canvasPosition * 2.5;
        vec2 globalNoiseCell = floor(globalNoisePosition);
        vec2 globalNoiseFraction = fract(globalNoisePosition);
        globalNoiseFraction = globalNoiseFraction * globalNoiseFraction * (3.0 - 2.0 * globalNoiseFraction);

        float globalA = fract(sin(dot(globalNoiseCell, vec2(127.1, 311.7))) * 43758.5453);
        float globalB = fract(sin(dot(globalNoiseCell + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453);
        float globalC = fract(sin(dot(globalNoiseCell + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);
        float globalD = fract(sin(dot(globalNoiseCell + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453);

        float globalNoise = mix(
            mix(globalA, globalB, globalNoiseFraction.x),
            mix(globalC, globalD, globalNoiseFraction.x),
            globalNoiseFraction.y
        );

        float globalLight = globalGradient * 0.7 + (globalNoise - 0.5) * 0.6;

        light *= 1.0 + globalLight * $globalLightStrength;


        // --------------------------------------------------
        // 5. OPALESCENCE (variation de matière par pièce)
        // --------------------------------------------------

        light *= 1.0 + p_opalescence * $opalescenceStrength * 0.6;


        // --------------------------------------------------
        // APPLICATION FINALE
        // --------------------------------------------------
        //
        // Volontairement PUREMENT MULTIPLICATIF, du début à la fin —
        // aucun mélange vers le blanc nulle part, ni pour la lumière
        // ni pour l'opalescence. Une première version ajoutait un
        // bonus "éclat ponctuel" (additif, poussant vers le blanc pur
        // indépendamment de la teinte) pour révéler la texture sur
        // les couleurs claires — mais à l'usage, ça se lisait comme
        // un REFLET posé sur la surface (une tache blanche achromatique
        // qui ignore la couleur du dessous), pas comme de la lumière
        // TRANSMISE à travers un verre coloré. Une vraie pièce de
        // verre, même très éclairée, reste teintée — sa couleur
        // devient plus vive/éclatante, elle ne blanchit pas par
        // plaques. En gardant tout multiplicatif (et la base sous 1.0,
        // voir plus haut), chaque pixel reste une nuance de sa teinte
        // de départ, jamais lavé vers l'achromatique.

        x_fill.rgb *= light;

    """.trimIndent()
}