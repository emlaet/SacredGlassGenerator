import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector2
import org.openrndr.draw.*
import org.openrndr.extensions.Screenshots

// --------------------------------------------------
// PROGRAMME PRINCIPAL
// --------------------------------------------------
//
// Ce fichier n'implémente plus aucune logique de composition,
// segmentation, palette, plomb ou verre : il se contente
// d'assembler les 5 systèmes (Composition.kt, Segmentation.kt,
// Palette.kt, Leading.kt, Glass.kt) et de piloter la boucle de
// rendu OpenRNDR. Les arêtes courbes partagées sont dans
// EdgeCurves.kt. Les données de palettes (saisons liturgiques)
// sont dans Palette.kt, après les algorithmes. La génération et le
// dessin (renderVitrail) sont dans Renderer.kt : ce fichier construit
// seulement la "recette" (VitrailConfig) et gère la fenêtre et les
// touches. L'export haute résolution est dans Export.kt.
//
// Réglage actuel : BAIE EN ARC BRISÉ avec croix rayonnante (format
// 2:3, pour les tirages muraux — voir crossBayRecipe, angelBayRecipe et
// Bay.kt). Autres croix (ShapedCross.kt), avec ou sans baie : tréflée,
// celtique et rondouillarde (trefoilRecipe, celticRecipe, roundedRecipe ;
// trefoilBayRecipe, celticBayRecipe, roundedBayRecipe). Motifs raccordables : fleurs du souvenir (Toussaint,
// souvenirRecipe, Botanical.kt ; touche « r » : aperçu répété 2 × 2) ;
// perce-neige (Noire, snowdropRecipe) ; églantines (Rose, roseRecipe) ; lys blancs (Pâques, easterRecipe) ; passiflores, roses rouges et palmes (Rouge, redRecipe) ;
// branche de Jessé
// (Avent, adventRecipe) ; roses de Noël et houx
// (blanc et or, noelRecipe) ; lys, rose et iris
// (palette mariale, marianRecipe) ; vigne et blé (Temps ordinaire,
// vineRecipe) ; motif floral : tulipes, coquelicots (botanicalRecipe).
// Autre motif raccordable disponible : « lignes maîtresses + éclats »
// (periodicShardsRecipe). Recette de base (config), toujours disponible :
// Composition RadiantAngel + Segmentation
// RadiantAngel + Palette aléatoire pondérée (vert, temps ordinaire)
// + Basic (plomb noir simple) + Procedural (avec lumière globale +
// opalescence), variante « robe liturgique » (sans rayons de lumière,
// robe aux couleurs de la palette — voir angelRobeVariant plus bas).
// L'ange rayonnant est dérivé de la croix rayonnante
// décrite ci-dessous, mais ses rayons partent de la POITRINE (petit
// médaillon sombre) : vers le bas la robe, vers le haut le cou et la
// tête ronde entourée d'un nimbe doré, de part et d'autre deux ailes
// levées découpées en plumes, et quelques rayons de lumière entre les
// ailes et la robe (voir RadiantAngelCompositionSystem dans
// Composition.kt pour tous les réglages de forme).
//
// Croix rayonnante (réglage précédent) : Composition RadiantCross +
// Segmentation RadiantCross. Même moteur que Sunburst (rayons indépendants depuis
// un centre), mais certains rayons sont désignés comme les bras
// d'une croix latine (plus longs, forcés en blanc cassé) tandis que
// les autres jouent le rôle de rayons de lumière plus courts et
// colorés autour d'elle — esprit "croix de lumière/gloire", sans
// avoir besoin de découpe géométrique (pas de soustraction de
// polygones dans la boîte à outils) : la croix EST simplement un
// groupe de rayons plus longs et distinctement colorés.
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
//   - RadiantCrossCompositionSystem(...) + RadiantCrossSegmentationSystem()
//     (croix rayonnante — voir radiantCrossComposition plus bas)
//   - RadiantAngelCompositionSystem()  + RadiantAngelSegmentationSystem()
//     (ange entouré de rayons colorés)
//   - angelRobeVariant (voir plus bas) + RadiantAngelSegmentationSystem()  [réglage actuel]
//     (ange sans rayons, robe aux couleurs de la saison)
//
//   Palette (algorithme de répartition) :
//   - RandomPaletteSystem(flatColors)                 [résultat "poivre et sel" sur cellules petites/nombreuses, évité]
//   - AdjacencyAwarePaletteSystem(flatColors)          [pousse à l'alternance, pas au regroupement]
//   - CappedClusterPaletteSystem(paletteFamilies, ...) [pour cellules Voronoï petites et nombreuses — voir Palette.kt]
//   - WeightedRandomPaletteSystem(paletteFamilies)     [réglage actuel]
//
//   Palette (saison liturgique) : voir la ligne "val liturgicalPalette"
//   plus bas, et Palette.kt pour les sept palettes disponibles.
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
        // Random(seed) est recréé à chaque appel de renderVitrail() (voir
        // Renderer.kt), jamais gardé ici — l'aperçu et l'export produisent
        // ainsi exactement le même motif.

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

        // Nuances blanc cassé des BRAS de la croix — constantes à travers
        // toutes les saisons (voir LiturgicalPalettes.CROSS_ARM_SHADES
        // dans Palette.kt).
        val crossArmShades = LiturgicalPalettes.CROSS_ARM_SHADES

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

        // Système 3 — saison liturgique (données dans Palette.kt).
        //
        // <-- swap ici : palette active. Sept disponibles :
        //   LiturgicalPalettes.ORDINAIRE — vert, temps ordinaire [réglage actuel]
        //   LiturgicalPalettes.NOEL      — blanc lumineux, Noël/Pâques (ocres au centre, blancs vers l'extérieur)
        //   LiturgicalPalettes.AVENT     — violet, Avent/Carême
        //   LiturgicalPalettes.ROUGE     — rouge, Pentecôte/martyrs
        //   LiturgicalPalettes.NOIRE     — noir, funérailles (facultatif)
        //   LiturgicalPalettes.ROSE      — rose, Gaudete/Laetare
        //   LiturgicalPalettes.MARIAL    — bleu marial (privilège régional, PAS une des six couleurs universelles)
        val liturgicalPalette = LiturgicalPalettes.MARIAL

        // <-- swap ici : teinte du MÉDAILLON, indépendante de la saison
        // (voir MedallionMode dans Palette.kt) :
        //   MedallionMode.HOST     — toujours crème, lecture "hostie" [réglage actuel]
        //   MedallionMode.SEASONAL — cœur sombre propre à la saison active
        // (Croix rayonnante uniquement : l'ange a toujours une poitrine
        // sombre de la saison et une tête claire — voir angelChestShades
        // dans la recette ci-dessous.)
        // ATTENTION : ce réglage ne vaut que pour les recettes SANS baie.
        // La croix en baie (crossBayRecipe, plus bas) a son propre réglage,
        // crossMedallionMode, qui remplace celui-ci.
        val medallionMode = MedallionMode.HOST

        val paletteFamilies = liturgicalPalette.families
        val crossMedallionShades = liturgicalPalette.medallionShades(medallionMode)

        // ------------------------
        // RECETTE — assemblage des 5 systèmes
        // ------------------------
        //
        // Tout ce qui définit l'œuvre est réuni dans une seule
        // VitrailConfig (voir Renderer.kt). renderVitrail() se charge
        // ensuite de la génération et du dessin, pour l'aperçu comme
        // pour l'export.
        // Croix rayonnante, prête à l'emploi : pour y revenir, remplacer
        // dans la recette ci-dessous RadiantAngelCompositionSystem() par
        // radiantCrossComposition, et RadiantAngelSegmentationSystem()
        // par RadiantCrossSegmentationSystem().
        val radiantCrossComposition = RadiantCrossCompositionSystem(
            coreRadiusRatio = crossCoreRadiusRatio,
            numberOfRays = crossNumberOfRays,
            angleIrregularity = crossAngleIrregularity,
            minDivisionsPerRay = crossMinDivisionsPerRay,
            maxDivisionsPerRay = crossMaxDivisionsPerRay,
            lightLengthMinRatio = crossLightLengthMinRatio,
            lightLengthMaxRatio = crossLightLengthMaxRatio,
            armWindows = crossArmWindows
        )

        // Ange rayonnant, variante « robe liturgique » : pas de rayons de
        // lumière, la robe (plus large et plus finement découpée) porte
        // les couleurs de la palette liturgique active, et les ailes
        // sont un peu plus ouvertes. Pour revenir à l'ange entouré de
        // rayons colorés, remplacer dans la recette ci-dessous
        // angelRobeVariant par RadiantAngelCompositionSystem().
        val angelRobeVariant = RadiantAngelCompositionSystem(
            showLightRays = false,
            robeUsesPalette = true,
            bodyHalfWidthDegrees = 26.0,
            bodyRayCount = 6,
            bodyMinDivisionsPerRay = 3,
            bodyMaxDivisionsPerRay = 4,
            wingCenterDegrees = 196.0,
            wingHalfWidthDegrees = 38.0,
            // Ailes raccourcies par rapport au premier réglage de la
            // variante (1.05 / 0.50 : envergure ~550 px à l'aperçu),
            // pour équilibrer la robe : 0.80 / 0.38 donne ~440 px
            // (−20 %). Autres essais comparés : 0.95 / 0.46 (−8 %,
            // différence à peine visible), 0.72 / 0.34 (−27 %).
            wingLowerLengthRatio = 0.38,
            wingPeakLengthRatio = 0.80
        )

        // <-- swap ici : FORMAT D'IMPRESSION (voir PrintFormat.kt) — taille
        // réelle du produit en millimètres (fond perdu compris si le gabarit
        // en demande un) et résolution en dpi (ppp, 300 au minimum pour
        // Gelato). L'export (touche « e ») fait alors largeur ÷ 25,4 × dpi
        // pixels de large, et la composition est cadrée aux proportions du
        // format (un carré pour 60 × 60 cm), dans l'aperçu comme à l'export.
        //   null                                — export par défaut : 3600 × 2700 px (4:3), baie 3600 × 5400 px [réglage actuel]
        //   PrintFormat(600.0, 600.0, 300.0)    — aluminium 60 × 60 cm, 300 ppp (7087 × 7087 px)
        //   PrintFormat(400.0, 600.0, 300.0)    — 40 × 60 cm (baie 2:3), 300 ppp (4724 × 7087 px)
        //   PrintFormat(300.0, 225.0, 300.0)    — 30 × 22,5 cm (4:3), 300 ppp : l'export actuel
        // Ne s'applique ni aux motifs raccordables ni au champ de rayons (qui
        // a son propre format, plus bas).
        val printFormat: PrintFormat? = null

        val config = VitrailConfig(
            seed = seed,
            numberOfSites = numberOfSites,

            // Système 1 — Composition
            // Alternatives disponibles : GridCompositionSystem(),
            // OrganicSitesCompositionSystem(baseSiteDistance, sizeVariation),
            // CurveGuidedCompositionSystem(numberOfGuideCurves, baseSiteDistance, sizeVariation),
            // RadialCompositionSystem(numberOfRays, numberOfRings, radialJitterRatio, radialJitterRatio),
            // SunburstCompositionSystem(sunburstNumberOfRays, sunburstCoreRadiusRatio, sunburstAngleIrregularity, sunburstMinDivisionsPerRay, sunburstMaxDivisionsPerRay)
            // radiantCrossComposition (croix rayonnante), RadiantAngelCompositionSystem()
            // (ange entouré de rayons colorés) — voir juste au-dessus de la recette
            compositionSystem = radiantCrossComposition, // <-- swap ici

            // Système 2 — Segmentation (à échanger avec la composition)
            // Alternatives disponibles : GridSegmentationSystem(),
            // VoronoiSegmentationSystem(relaxationIterations),
            // RadialSegmentationSystem(), SunburstSegmentationSystem(),
            // RadiantCrossSegmentationSystem()
            segmentationSystem = RadiantCrossSegmentationSystem(), // <-- swap ici

            // Système 3 — Palette
            // Les rayons de lumière n'ont pas besoin de regroupement
            // (CappedClusterPaletteSystem) — ce sont déjà de grandes formes
            // distinctes, un tirage indépendant pondéré suffit. Voir
            // Palette.kt pour le détail des essais précédents pertinents
            // pour les styles à cellules petites et nombreuses (Voronoï).
            // raysPaletteSystem (Palette.kt) : WeightedRandomPaletteSystem(paletteFamilies)
            // pour toutes les palettes, sauf la palette « blanc lumineux » de
            // Noël/Pâques, répartie en rayons (RadialLuminousPaletteSystem :
            // ocres près du centre, blancs chauds et froids vers l'extérieur).
            paletteSystem = liturgicalPalette.raysPaletteSystem(), // <-- swap ici
            crossArmShades = crossArmShades,
            crossMedallionShades = crossMedallionShades,

            // Système 4 — Plomb
            // Trait noir simple, sans reflet — préférence confirmée
            // après test du reflet décalé (jugé moins bon que le simple
            // trait noir).
            // Extrémités RONDES (LineCap.ROUND) : les cercles et les arcs
            // (nimbe et tête de l'ange, médaillon de la croix, arc de la
            // baie) sont faits de nombreuses petites arêtes, tracées une à
            // une ; avec des extrémités droites (BUTT), leurs jonctions
            // laissaient des encoches claires et le plomb paraissait
            // pointillé. Une extrémité ronde recouvre chaque jonction,
            // comme une soudure d'étain (voir LeadStyle.lineCap, Leading.kt).
            leadSystem = BasicLeadSystem(),
            leadStyle = LeadStyle(
                width = strokeWeight,
                color = strokeColor,
                lineCap = LineCap.ROUND
            ),

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
            glassSystem = ProceduralGlassSystem(), // <-- swap ici
            glassStyle = GlassStyle(
                textureStrength = 0.26,
                streakStrength = 0.28,
                streakAngle = 0.6,
                localLightStrength = 0.22,
                lightDirection = lightDirection,
                globalLightStrength = 0.18,
                opalescenceStrength = 0.10
            ),

            curvatureAmount = curvatureAmount,
            backgroundColor = backgroundColor,

            // Ange rayonnant : poitrine dans la teinte sombre de la saison
            // active. Tête, nimbe, robe et ailes gardent leurs teintes par
            // défaut (voir ANGEL_…_SHADES dans Palette.kt).
            angelChestShades = liturgicalPalette.seasonalMedallionShades,

            // Format d'impression (voir printFormat plus haut)
            printFormat = printFormat
        )

        // Motif RACCORDABLE « lignes maîtresses + éclats » (impression
        // intégrale sur textile) : même recette de base, mais avec sa
        // composition et sa segmentation, des plombs droits (curvature 0 :
        // indispensable avec les jonctions en T — voir
        // PeriodicShardsSegmentationSystem) et sans dégradé de lumière
        // global (il créerait une rupture de luminosité au raccord entre
        // deux tuiles), plombs à extrémités rondes. Couleurs : champ
        // périodique « arc-en-ciel » (ColorRamps.ARC_EN_CIEL, Palette.kt).
        // La tuile a la taille du canevas : 768 × 576 à l'aperçu,
        // exportWidth × exportHeight à l'export (touche « e »).
        val periodicShardsRecipe = config.copy(
            compositionSystem = PeriodicShardsCompositionSystem(),
            segmentationSystem = PeriodicShardsSegmentationSystem(),
            curvatureAmount = 0.0,
            glassStyle = config.glassStyle.copy(globalLightStrength = 0.0),
            // Extrémités rondes : les arcs sont faits de nombreuses petites
            // arêtes, et avec des extrémités droites leurs jonctions
            // laissaient des stries claires dans le plomb (voir
            // LeadStyle.lineCap, Leading.kt).
            leadStyle = config.leadStyle.copy(lineCap = LineCap.ROUND)
        )

        // Motif raccordable VÉGÉTAL, tout en courbes (Botanical.kt) : tige
        // ondulante, feuilles, tulipes, coquelicots, parfois une rosace,
        // boutons ; fond découpé par des ponts courbes. Comme le motif en
        // éclats : arêtes droites (courbes échantillonnées, curvature 0),
        // plombs à extrémités rondes, pas de dégradé de lumière global.
        // numberOfSites n'est pas utilisé par cette famille.
        val botanicalRecipe = config.copy(
            compositionSystem = BotanicalCompositionSystem(),
            segmentationSystem = BotanicalSegmentationSystem(),
            curvatureAmount = 0.0,
            glassStyle = config.glassStyle.copy(globalLightStrength = 0.0),
            leadStyle = config.leadStyle.copy(lineCap = LineCap.ROUND),
            // <-- swap ici : palette du végétal
            //   BotanicalPalettes.PRINTEMPS   — ciel pâle, rose et jaune [réglage actuel]
            //   BotanicalPalettes.ART_NOUVEAU — ambre, olive, bordeaux et violet
            //   BotanicalPalettes.NUIT        — bleu nuit, sauge, ambre et or
            //   BotanicalPalettes.fromLiturgical(LiturgicalPalettes.AVENT) — etc.
            botanicalPalette = BotanicalPalettes.PRINTEMPS
        )

        // Motif raccordable VIGNE ET BLÉ — Temps ordinaire (Atelier Arcana) :
        // cep de vigne, feuilles de vigne, grappes de raisin, épis de blé
        // (le pain et le vin de l'Eucharistie). Barbes du blé et vrilles de
        // la vigne peintes à la grisaille (traits fins sur le verre).
        val vineRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.VIGNE_BLE),
            botanicalPalette = BotanicalPalettes.VIGNE_ORDINAIRE
        )

        // Motif raccordable LYS, ROSE ET IRIS — palette mariale (Atelier
        // Arcana) : lys (pureté, Annonciation), rose (« Rosa mystica »),
        // iris (douleurs de Marie). Étamines du lys et veines de l'iris
        // peintes à la grisaille.
        val marianRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.MARIAL),
            botanicalPalette = BotanicalPalettes.MARIAL_FLEURS
        )

        // Motif raccordable ROSES DE NOËL ET HOUX — blanc et or (Atelier
        // Arcana) : hellébores (Nativité), houx et baies rouges (souvent
        // lus comme annonce de la Passion). Étamines et nervures des
        // hellébores peintes à la grisaille.
        val noelRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.NOEL),
            botanicalPalette = BotanicalPalettes.NOEL_FLEURS
        )

        // Motif raccordable BRANCHE DE JESSÉ — Avent (violet, Atelier
        // Arcana) : vieux bois taillé (la souche) d'où repartent de jeunes
        // feuilles, roses de Noël encore en bouton — l'attente, « un rameau
        // sortira de la souche de Jessé » (Is 11, 1).
        val adventRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.AVENT),
            botanicalPalette = BotanicalPalettes.AVENT_FLEURS
        )

        // Motif raccordable PASSIFLORES, ROSES ROUGES ET PALMES — Rouge
        // (Passion, martyrs ; Atelier Arcana) : la Passion, le sang des
        // martyrs, la palme du martyre ; tige épineuse (épines, filaments
        // de la passiflore peints à la grisaille).
        val redRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.ROUGE),
            botanicalPalette = BotanicalPalettes.ROUGE_FLEURS
        )

        // Motif raccordable LYS BLANCS — Pâques (blanc et or, Résurrection ;
        // Atelier Arcana) : grands lys de la Madone dressés, boutons encore
        // fermés, feuilles lancéolées, sur un fond d'or lumineux.
        val easterRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.PAQUES),
            botanicalPalette = BotanicalPalettes.PAQUES_FLEURS
        )

        // Motif raccordable ÉGLANTINES — Rose (Gaudete, Laetare ; Atelier
        // Arcana) : roses sauvages épanouies parmi des boutons encore
        // fermés, la joie au cœur de l'attente ; fond rose liturgique.
        val roseRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.ROSE),
            botanicalPalette = BotanicalPalettes.ROSE_FLEURS
        )

        // Motif raccordable PERCE-NEIGE — Noire (défunts, funérailles ;
        // Atelier Arcana) : touffes de perce-neige sortant d'un sol enneigé,
        // fleurs pendantes au bout de hampes recourbées ; la lumière qui
        // perce l'hiver. Fond anthracite et ardoise, sans or.
        val snowdropRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.NOIRE),
            botanicalPalette = BotanicalPalettes.NOIRE_FLEURS
        )

        // Motif raccordable FLEURS DU SOUVENIR — Toussaint et commémoration
        // des défunts (Atelier Arcana) : pavot somnifère et sa capsule (le
        // sommeil des défunts), chrysanthèmes ivoire et bronze, cempasúchil
        // (Día de Muertos). Fond anthracite ; les fleurs gardent leurs couleurs.
        val souvenirRecipe = botanicalRecipe.copy(
            compositionSystem = BotanicalCompositionSystem(motif = BotanicalMotif.SOUVENIR),
            botanicalPalette = BotanicalPalettes.TOUSSAINT_FLEURS
        )

        // BAIES EN ARC (Bay.kt) — panneaux non raccordables au format 2:3,
        // pour les tirages muraux : bordure de verre qui suit l'arc (pièces
        // alternées : couleur principale de la saison et or), fond en
        // losanges (sombre et désaturé), une barlotière
        // (barres de fer horizontales). La composition est générée dans la
        // fenêtre avec les mêmes tirages que sans baie.
        // Arc : ArchShape.POINTED (brisé, gothique) ou ArchShape.ROUND
        // (plein cintre, roman). Pour la palette noire, préférer un accent
        // argent : BayStyle.forLiturgical(LiturgicalPalettes.NOIRE,
        // ArchShape.POINTED, accent = listOf(ColorRGBa.fromHex("#A6A6A8"))).
        // Le fond en losanges est sombre et désaturé par défaut ;
        // QuarryTone.CLEAR donne une vitrerie claire (verre pâle, la
        // convention des vitreries), QuarryTone.DARK un fond sombre.
        // Fond : sombre et désaturé par défaut (QuarryTone.MUTED : la
        // couleur de la saison, assombrie et grisée), pour que la croix
        // blanche se lise comme une croix de lumière ; vitrerie claire avec
        // QuarryTone.CLEAR, fond sombre de la palette avec QuarryTone.DARK.
        // Rayons : seulement les familles assez contrastées avec ce fond
        // (raysFamiliesFor, Bay.kt).
        // Médaillon : couleur de la saison (MedallionMode.PALETTE) ou crème,
        // lecture « hostie » (MedallionMode.HOST) — voir Palette.kt.
        // <-- swap ici : bordure de la baie (croix et ange) :
        //   BorderMode.ALTERNATE — pièces alternées, couleur de la saison et or (Noël/Pâques : ocre et blanc) [réglage actuel]
        //   BorderMode.BLACK     — très sobre, verre noir
        //   BorderMode.NONE      — pas de bordure : les losanges vont jusqu'au bord
        val bayBorder = BorderMode.ALTERNATE
        // Noël/Pâques (palette « blanc lumineux ») : vitrerie en nacre et
        // champagne, bordure ocre et blanc (voir Bay.kt), centre crème ; les
        // rayons y suivent la répartition radiale (ocres au centre).
        // Barlotière retirée (saddleBarPositions = emptyList()).
        // Croix descendue de 6 % de la hauteur de la fenêtre
        // (emblemOffsetRatio, Bay.kt) : la traverse passe sous la naissance
        // de l'arc, dans la partie droite de la fenêtre, au lieu d'être
        // serrée dans l'arc. 0.0 = position d'origine.
        val crossBay = BayStyle.forLiturgical(liturgicalPalette, ArchShape.POINTED, border = bayBorder)
            .copy(saddleBarPositions = emptyList(), emblemOffsetRatio = 0.06)
        // <-- swap ici : médaillon de la croix EN BAIE (remplace medallionMode,
        // plus haut). Couleur de la palette (MedallionMode.PALETTE), sauf à
        // Noël : crème (MedallionMode.HOST). Pour un médaillon toujours
        // crème : val crossMedallionMode = MedallionMode.HOST
        val crossMedallionMode = if (liturgicalPalette == LiturgicalPalettes.NOEL) MedallionMode.HOST else MedallionMode.PALETTE
        val crossBayRecipe = config.copy(
            compositionSystem = radiantCrossComposition,
            segmentationSystem = RadiantCrossSegmentationSystem(),
            paletteSystem = liturgicalPalette.raysPaletteSystem(crossBay),
            crossMedallionShades = liturgicalPalette.medallionShades(crossMedallionMode),
            bay = crossBay
        )

        // Croix DESSINÉES (ShapedCross.kt) dans la même baie, avec les mêmes
        // rayons colorés passant derrière elles :
        // - croix tréflée : bras terminés par trois lobes arrondis ;
        // - croix celtique : anneau doré autour du croisement, pièces de
        //   couleur à l'intérieur de l'anneau. Anneau en blanc cassé, comme
        //   les bras : ShapedCrossCompositionSystem(ShapedCrossStyle.CELTIC, ringGold = false)
        val trefoilBayRecipe = crossBayRecipe.copy(
            compositionSystem = ShapedCrossCompositionSystem(ShapedCrossStyle.TREFOIL),
            segmentationSystem = ShapedCrossSegmentationSystem()
        )
        val celticBayRecipe = crossBayRecipe.copy(
            compositionSystem = ShapedCrossCompositionSystem(ShapedCrossStyle.CELTIC),
            segmentationSystem = ShapedCrossSegmentationSystem()
        )
        // (croix rondouillarde en baie : voir roundedBayRecipe plus bas, après
        // le choix de son motif)

        // Les mêmes croix SANS BAIE (format 4:3, fond transparent à l'export,
        // médaillon selon medallionMode plus haut) :
        // - croix tréflée grecque (quatre bras égaux, comme la croix de
        //   saint Maurice) ; croix latine : equalArms = false ;
        // - croix celtique (latine) ;
        // - croix « rondouillarde » : sans rayons, les couleurs de la saison
        //   dans les pièces de la croix (voir roundedScheme ci-dessous).
        val trefoilRecipe = config.copy(
            compositionSystem = ShapedCrossCompositionSystem(ShapedCrossStyle.TREFOIL),
            segmentationSystem = ShapedCrossSegmentationSystem()
        )
        val celticRecipe = config.copy(
            compositionSystem = ShapedCrossCompositionSystem(ShapedCrossStyle.CELTIC),
            segmentationSystem = ShapedCrossSegmentationSystem()
        )
        // <-- swap ici : dessin intérieur de la croix rondouillarde (RoundedPatterns.kt) :
        //   RoundedPattern.GEMS    — croix gemmée : corps ivoire, filet coloré, cinq pierres serties [réglage actuel]
        //   RoundedPattern.LANCETS — lancettes rayonnantes, en dégradé (comme une rose)
        //   RoundedPattern.RHOMBI  — losanges en spirale, en dégradé
        //   RoundedPattern.PLAIN   — découpe simple, couleurs selon roundedScheme ci-dessous
        // Les motifs ont leur propre cœur doré (medallionMode ne s'y applique pas).
        val roundedPattern = RoundedPattern.GEMS
        // <-- swap ici : couleurs de la découpe simple (RoundedPattern.PLAIN, ShapedCross.kt) :
        //   RoundedCrossScheme.PINWHEEL       — cœur clair, bouts des bras en deux tons alternés [réglage actuel]
        //   RoundedCrossScheme.CORE           — cœur dans la couleur de la saison, bouts plus clairs
        //   RoundedCrossScheme.TIPS           — cœur blanc cassé, bouts dans la couleur de la saison
        //   RoundedCrossScheme.PINWHEEL_LIGHT — cœur de la saison, bouts alternés clair / blanc cassé
        // Pour la croix blanche entourée de rayons : retirer tierShades et
        // showRays = false.
        val roundedScheme = RoundedCrossScheme.PINWHEEL
        val roundedRecipe = config.copy(
            compositionSystem = ShapedCrossCompositionSystem(
                ShapedCrossStyle.ROUNDED,
                showRays = false,
                tierShades = liturgicalPalette.roundedCrossShades(roundedScheme),
                pattern = roundedPattern,
                patternColors = liturgicalPalette.roundedCrossColors()
            ),
            segmentationSystem = ShapedCrossSegmentationSystem()
        )
        // Croix rondouillarde dans la baie, même motif (sans rayons)
        val roundedBayRecipe = crossBayRecipe.copy(
            compositionSystem = roundedRecipe.compositionSystem,
            segmentationSystem = ShapedCrossSegmentationSystem()
        )

        // Ange dans une baie en plein cintre : robe IVOIRE (et non aux
        // couleurs de la saison) — sur le fond en losanges sombre de la
        // saison, une robe colorée disparaîtrait.
        val angelBayComposition = RadiantAngelCompositionSystem(
            showLightRays = false,
            robeUsesPalette = false,
            bodyHalfWidthDegrees = 26.0,
            bodyRayCount = 6,
            bodyMinDivisionsPerRay = 3,
            bodyMaxDivisionsPerRay = 4,
            wingCenterDegrees = 196.0,
            wingHalfWidthDegrees = 38.0,
            wingLowerLengthRatio = 0.38,
            wingPeakLengthRatio = 0.80
        )
        // Fond sombre et désaturé (réglage par défaut) : sur une vitrerie
        // claire, les ailes et la robe ivoire de l'ange se perdraient.
        // Barlotière retirée ; pour la remettre sous la robe :
        // saddleBarPositions = listOf(0.88).
        val angelBayRecipe = config.copy(
            compositionSystem = angelBayComposition,
            bay = BayStyle.forLiturgical(liturgicalPalette, ArchShape.ROUND, border = bayBorder).copy(saddleBarPositions = emptyList())
        )
        // Le même ange, entouré de RAYONS de lumière dans la baie. Conseillé
        // pour Noël/Pâques : sur la vitrerie claire (nacre et champagne), les
        // ailes blanches et la robe ivoire ne se détachent que par le plomb ;
        // les rayons ocres derrière l'ange (gloire) découpent sa silhouette.
        val angelRaysBayRecipe = angelBayRecipe.copy(
            compositionSystem = RadiantAngelCompositionSystem(
                showLightRays = true,
                robeUsesPalette = false,
                bodyHalfWidthDegrees = 26.0,
                bodyRayCount = 6,
                bodyMinDivisionsPerRay = 3,
                bodyMaxDivisionsPerRay = 4,
                wingCenterDegrees = 196.0,
                wingHalfWidthDegrees = 38.0,
                wingLowerLengthRatio = 0.38,
                wingPeakLengthRatio = 0.80
            )
        )

        // FOND « CHAMP DE RAYONS » (RayField.kt) — un fond seul, dans l'esprit
        // d'un vitrail, à superposer sous un autre calque (carte de vœux
        // Gelato…) : rayons en deux teintes alternées, anneaux de plomb
        // concentriques continus, sur tout le format, fond perdu compris.
        // FORMAT en millimètres (format fini + fond perdu de chaque côté),
        // RÉSOLUTION en dpi (ppp) : l'export (touche « e ») fait
        // (format fini + 2 × fond perdu) ÷ 25,4 × dpi pixels de côté. Le
        // format doit être celui du produit Gelato, sinon Gelato agrandit
        // l'image et la résolution réelle baisse (un fichier prévu pour
        // 141 mm, imprimé sur environ 206 mm, n'est plus qu'à ~214 ppp).
        // Changer dpi ne change pas le dessin, seulement le nombre de pixels.
        // ⚠ dpi est une RÉSOLUTION (300 à 600), pas une taille : une valeur
        // très élevée demanderait une image de centaines de milliers
        // de pixels de côté, impossible à créer. Ce réglage ne vaut que pour
        // le champ de rayons ; pour les autres recettes, voir printFormat.
        // Dans l'aperçu, le format est centré. Plombs droits (courbure 0) ;
        // largeur du plomb : leadWidthMm à l'impression (0,762 mm).
        // COULEURS DU FOND : elles suivent la palette active (liturgicalPalette,
        // plus haut), d'après la liste RayFieldBackgrounds (RayField.kt) :
        //   Blanc (Noël/Pâques) — bleu nuit de l'ange     #16214A / #1C2A5C
        //   Rouge               — bleu canard profond     #072A30 / #0A343B
        //   Vert (ordinaire)    — bleu nuit franc         #16214A / #1C2A5C
        //   Violet              — pierre chaude claire   #D2CBC0 / #DED8CE
        //   Rose                — vert sapin franc        #0D2A22 / #11332A
        //   Bleu marial         — blanc froid très pâle   #F3F4F2 / #E4E7EA
        //   Noir                — blanc cassé / gris clair #F0EFEC / #DCDAD5
        // <-- swap ici : pour un autre fond que celui de la saison, par exemple :
        //   val rayFieldBackground = RayFieldBackgrounds.ROUGE
        val rayFieldBackground = RayFieldBackgrounds.NOEL

        // <-- swap ici : réglages du fond. Exemples :
        //   trimWidthMm = 200.0, trimHeightMm = 200.0 — carte carrée de 20 cm
        //   trimWidthMm = 148.0, trimHeightMm = 210.0 — format A5 portrait
        //   dpi = 600.0                            — export deux fois plus fin
        //   rayCount = 32                          — 32 rayons au lieu de 24
        //   medallionRadius = 0.06                 — médaillon plein au centre
        //   shades = liturgicalPalette.rayFieldShades(),
        //   medallionShades = liturgicalPalette.rayFieldMedallionShades()
        //                                          — rayons dans la couleur principale de la saison, au lieu du fond de la liste
        val rayField = RayFieldCompositionSystem(
            trimWidthMm = 141.0,
            trimHeightMm = 141.0,
            bleedMm = 3.0,
            dpi = 600.0,
            rayCount = 24,
            shades = rayFieldBackground.shades,
            medallionShades = rayFieldBackground.medallionShades
        )
        val rayFieldRecipe = config.copy(
            compositionSystem = rayField,
            segmentationSystem = RayFieldSegmentationSystem(),
            curvatureAmount = 0.0,
            leadStyle = config.leadStyle.copy(width = rayField.referenceLeadWidth()),
            bay = null
        )

        // ------------------------
        // ROSACE (Rosace.kt)
        // ------------------------
        // Rosace rayonnante gothique : remplage en pierre, vitraux au plomb,
        // symétrie stricte. Aucun tirage aléatoire (le seed ne change que la
        // matière du verre).
        // <-- swap ici : nombre de rotations (secteurs identiques), de 3 à 48.
        // Pair : les trèfles alternent de couleur d'un secteur à l'autre.
        // Exemples essayés : 4, 5, 6, 8, 10, 12, 16, 24.
        val rosaceRotations = 12
        // <-- swap ici : couleurs de la rosace (RosaceSchemes, Rosace.kt) :
        //   RosaceSchemes.forLiturgical(liturgicalPalette) — suit la palette de la saison choisie plus haut
        //   RosaceSchemes.CATHEDRALE       — bleu et rouge, trèfles jaunes et blancs
        //   RosaceSchemes.ORDINAIRE        — vert (Temps ordinaire)
        //   RosaceSchemes.NOEL             — blanc lumineux (Noël, Pâques)
        //   RosaceSchemes.AVENT            — violet (Avent, Carême)
        //   RosaceSchemes.ROUGE            — rouge (Passion, Pentecôte, martyrs)
        //   RosaceSchemes.NOIRE            — noir (défunts)
        //   RosaceSchemes.ROSE             — rose (Gaudete, Laetare)
        //   RosaceSchemes.MARIAL           — bleu marial (dévotionnel)
        //   RosaceSchemes.PIERRE_ET_PLOMB  — pierre et plombs seuls, verre blanc cassé
    val rosaceScheme = RosaceSchemes.PIERRE_ET_PLOMB
        // <-- swap ici : format de la rosace (carré). 300 × 300 mm à 300 ppp
        // = export de 3543 × 3543 px ; PrintFormat(600.0, 600.0, 300.0) pour
        // un tirage de 60 × 60 cm (7087 × 7087 px). Hors du disque, l'export
        // est transparent.
        val rosaceRecipe = config.copy(
            compositionSystem = RosaceCompositionSystem(rotations = rosaceRotations),
            segmentationSystem = RosaceSegmentationSystem(),
            curvatureAmount = 0.0,
            // Plomb fin : la rosace compte plus de 2 000 pièces.
            leadStyle = config.leadStyle.copy(width = 1.2),
            // Opalescence réduite : les pièces homologues gardent le même éclat.
            glassStyle = config.glassStyle.copy(opalescenceStrength = 0.05),
            rosaceScheme = rosaceScheme,
            bay = null,
            printFormat = PrintFormat(300.0, 300.0, 300.0)
        )

        // ------------------------
        // ROSACES A, B, C (Rosace.kt, RosaceStyles.kt)
        // ------------------------
        // Dessins validés le 2 nov. 2026 ; une palette PAR RÔLES commune
        // (RosacePalettes). Aucun tirage aléatoire. Réglés pour 12 rotations
        // (rosaceRotations, plus haut) ; l'entrelacs de B3 demande un nombre pair.
        // <-- swap ici : dessin de la rosace :
        //   MedievalRosaceCompositionSystem(rotations = rosaceRotations)       — A, médiévale (pierre)
        //   OrientalRosaceCompositionSystem(StyledRosace.Style.ORIENTAL_ALMONDS, rosaceRotations)
        //                                                                      — B1, amandes, croix de mandorles (pierre)
        //   OrientalRosaceCompositionSystem(StyledRosace.Style.ORIENTAL_STAR, rosaceRotations)
        //                                                                      — B2, étoile, médaillon étoilé (pierre)
        //   OrientalRosaceCompositionSystem(StyledRosace.Style.ORIENTAL_STAR_FOIL, rosaceRotations)
        //                                                                      — B2, étoile, médaillon quadrilobe (pierre)
        //   OrientalRosaceCompositionSystem(StyledRosace.Style.ORIENTAL_INTERLACE, rosaceRotations)
        //                                                                      — B3, entrelacs (pierre autour, entrelacs en verre)
        //   ContemporaryRosaceCompositionSystem()                              — C, contemporaine, croix pattée (tout en verre)
        //   CurvilinearRosaceCompositionSystem(rotations = rosaceRotations)    — B d'origine, soufflets (pierre)
        val rosaceDesign: CompositionSystem = MedievalRosaceCompositionSystem(rotations = rosaceRotations)
        // <-- swap ici : palette (RosacePalettes, Rosace.kt) :
        //   RosacePalettes.GRENAT_OR        — rose, grenat et or (Gaudete, Laetare) [validée]
        //   RosacePalettes.VERT_IVOIRE_OR   — vert, ivoire et or (Temps ordinaire) [validée]
        //   RosacePalettes.NOEL_A, NOEL_B1, NOEL_B2, NOEL_C
        //                                   — blanc, ivoire et or (Noël) [validées] : une par dessin,
        //                                     à associer au dessin du même nom (B2 : médaillon étoilé)
        //   RosacePalettes.JOIE_ATTENTE     — la joie dans l'attente (violet, rose au cœur)
        //   RosacePalettes.GRENAT_PRUNE     — grenat et prune
        //   RosacePalettes.ARCTIC_NIGHT     — bleu nuit et glace, or pâle (Noël, hiver)
        //   RosacePalettes.EMERALD_DEPTHS   — émeraude et or
        //   RosacePalettes.OCEAN_REVERIE    — sarcelle et vert d'eau
        //   RosacePalettes.SUNSET_REVERIE   — bordeaux, corail et or
        //   RosacePalettes.ORCHID_SHADOW    — prune et orchidée (à l'essai)
        val rosacePaletteChoice = RosacePalettes.GRENAT_OR
        // <-- swap ici : pierre du remplage (A et B ; C n'en a pas) :
        //   null                               — celle de la palette (claire)
        //   RosacePalettes.PIERRE_ANTHRACITE   — pierre sombre (#4A4743)
        //   rosacePaletteChoice.lead           — couleur des plombs : lecture « plombs seuls »
        val stoneOverride: ColorRGBa? = null
        // <-- swap ici : true = verre adouci (texture 0,10 ; stries 0,08 ;
        // opalescence 0,10), false = verre actuel (opalescence 0,05).
        val softGlass = false
        val rosaceDesignRecipe = config.copy(
            compositionSystem = rosaceDesign,
            segmentationSystem = RosaceSegmentationSystem(),
            curvatureAmount = 0.0,
            // Plombs de la palette. Largeur de référence 1,2 : le dessin A
            // l'emploie partout ; B et C en tirent leur hiérarchie (fins 0,6 ×,
            // secondaires 1 ×, structure 1,6 à 2,2 ×).
            leadStyle = config.leadStyle.copy(width = 1.2, color = rosacePaletteChoice.lead),
            glassStyle = if (softGlass) config.glassStyle.copy(textureStrength = 0.10, streakStrength = 0.08, opalescenceStrength = 0.10)
            else config.glassStyle.copy(opalescenceStrength = 0.05),
            rosacePalette = if (stoneOverride != null) rosacePaletteChoice.copy(stone = stoneOverride) else rosacePaletteChoice,
            bay = null,
            printFormat = PrintFormat(300.0, 300.0, 300.0)
        )



        // <-- swap ici : recette affichée et exportée.
        //   crossBayRecipe       — croix rayonnante dans une baie en arc brisé (2:3) [réglage actuel]
        //   trefoilBayRecipe     — croix tréflée dans une baie en arc brisé (2:3)
        //   celticBayRecipe      — croix celtique dans une baie en arc brisé (2:3)
        //   roundedBayRecipe     — croix rondouillarde (même motif) dans une baie en arc brisé (2:3)
        //   trefoilRecipe        — croix tréflée (grecque), sans baie
        //   celticRecipe         — croix celtique, sans baie
        //   roundedRecipe        — croix rondouillarde (motif roundedPattern), sans baie
        //   angelBayRecipe       — ange dans une baie en plein cintre (2:3)
        //   angelRaysBayRecipe   — ange entouré de rayons dans la baie (conseillé à Noël/Pâques : rayons ocres)
        //   souvenirRecipe       — pavot, chrysanthème, cempasúchil (Toussaint)
        //   snowdropRecipe       — perce-neige, Noire (défunts)
        //   roseRecipe           — églantines, Rose (Gaudete, Laetare)
        //   easterRecipe         — lys blancs, Pâques (blanc et or)
        //   redRecipe            — passiflores, roses et palmes, Rouge
        //   adventRecipe         — branche de Jessé, Avent (violet)
        //   noelRecipe           — roses de Noël et houx, blanc et or
        //   marianRecipe         — lys, rose et iris, palette mariale
        //   vineRecipe           — vigne et blé, Temps ordinaire
        //   botanicalRecipe      — motif raccordable floral
        //   periodicShardsRecipe — motif raccordable en éclats
        //   rayFieldRecipe       — fond « champ de rayons » (carte de vœux…), format libre
        //   rosaceRecipe         — rosace rayonnante gothique (RosaceSchemes)
        //   rosaceDesignRecipe   — rosaces A, B1, B2, B3, C (RosacePalettes)
        //   config               — ange (ou croix) défini ci-dessus
        val activeConfig = rosaceDesignRecipe

        // Aperçu répété 2 × 2 (touche « r ») : pour vérifier le raccord
        // du motif. Chaque quart affiche la tuile entière, réduite.
        var previewRepeat = false

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
        // Baie en arc : format 2:3, 3600×5400px, soit 12×18 pouces à
        // 300 DPI (dans l'aperçu, la baie est centrée dans la fenêtre).
        // Fond « champ de rayons » : la taille de son format, fond perdu
        // compris (voir rayField plus haut).
        // Format d'impression (printFormat, plus haut) : sa taille en pixels.
        // Motifs raccordables : toujours 3600 × 2700 px (la tuile).
        val rayFieldFormat = activeConfig.compositionSystem as? RayFieldCompositionSystem
        val isTile = activeConfig.compositionSystem is PeriodicShardsCompositionSystem ||
                activeConfig.compositionSystem is BotanicalCompositionSystem
        val printPx = if (isTile) null else activeConfig.printFormat
        val exportWidth = rayFieldFormat?.fullWidthPx ?: printPx?.widthPx ?: 3600
        val exportHeight = rayFieldFormat?.fullHeightPx ?: printPx?.heightPx ?: if (activeConfig.bay != null) 5400 else 2700

        keyboard.keyDown.listen {
            if (it.name == "r") {
                previewRepeat = !previewRepeat
            }
            if (it.name == "e") {
                // Voir Export.kt : rendu hors-écran à fond transparent,
                // enregistré dans exports/vitrail-seed<seed>-<horodatage>.png
                exportHighResolution(
                    drawer = drawer,
                    config = activeConfig,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    referenceWidth = width.toDouble()
                )
            }
        }

        // ------------------------
        // RENDU (aperçu)
        // ------------------------

        extend {
            drawer.clear(activeConfig.backgroundColor)
            if (previewRepeat) {
                val halfW = width / 2.0
                val halfH = height / 2.0
                for (i in 0..1) for (j in 0..1) {
                    drawer.isolated {
                        drawer.translate(i * halfW, j * halfH)
                        renderVitrail(drawer, activeConfig, halfW, halfH, 0.5)
                    }
                }
            } else {
                renderVitrail(drawer, activeConfig, width.toDouble(), height.toDouble(), 1.0)
            }
        }
    }
}