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
// Bay.kt). Motifs raccordables : fleurs du souvenir (Toussaint,
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
        //   LiturgicalPalettes.NOEL      — blanc et or, Noël/Pâques
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
            compositionSystem = angelRobeVariant, // <-- swap ici

            // Système 2 — Segmentation (à échanger avec la composition)
            // Alternatives disponibles : GridSegmentationSystem(),
            // VoronoiSegmentationSystem(relaxationIterations),
            // RadialSegmentationSystem(), SunburstSegmentationSystem(),
            // RadiantCrossSegmentationSystem()
            segmentationSystem = RadiantAngelSegmentationSystem(), // <-- swap ici

            // Système 3 — Palette
            // Les rayons de lumière n'ont pas besoin de regroupement
            // (CappedClusterPaletteSystem) — ce sont déjà de grandes formes
            // distinctes, un tirage indépendant pondéré suffit. Voir
            // Palette.kt pour le détail des essais précédents pertinents
            // pour les styles à cellules petites et nombreuses (Voronoï).
            paletteSystem = WeightedRandomPaletteSystem(paletteFamilies), // <-- swap ici
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
            angelChestShades = liturgicalPalette.seasonalMedallionShades
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
        //   BorderMode.ALTERNATE — pièces alternées, couleur de la saison et or [réglage actuel]
        //   BorderMode.BLACK     — très sobre, verre noir
        //   BorderMode.NONE      — pas de bordure : les losanges vont jusqu'au bord
        val bayBorder = BorderMode.ALTERNATE
        // Noël : fond or pâle (voir Bay.kt), centre crème — sur cet or, un
        // centre doré disparaîtrait.
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
            paletteSystem = WeightedRandomPaletteSystem(crossBay.raysFamiliesFor(paletteFamilies)),
            crossMedallionShades = liturgicalPalette.medallionShades(crossMedallionMode),
            bay = crossBay
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

        // <-- swap ici : recette affichée et exportée.
        //   crossBayRecipe       — croix rayonnante dans une baie en arc brisé (2:3) [réglage actuel]
        //   angelBayRecipe       — ange dans une baie en plein cintre (2:3)
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
        //   config               — ange (ou croix) défini ci-dessus
        val activeConfig = angelBayRecipe

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
        val exportWidth = 3600
        val exportHeight = if (activeConfig.bay != null) 5400 else 2700

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