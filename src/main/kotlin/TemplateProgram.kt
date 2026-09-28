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
// Réglage actuel : Composition RadiantCross + Segmentation
// RadiantCross + Palette aléatoire pondérée (vert, temps ordinaire)
// + Basic (plomb noir simple) + Procedural (avec lumière globale +
// opalescence). Même moteur que Sunburst (rayons indépendants depuis
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
//   - RadiantCrossCompositionSystem(...) + RadiantCrossSegmentationSystem()  [réglage actuel]
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
        val liturgicalPalette = LiturgicalPalettes.ORDINAIRE

        // <-- swap ici : teinte du MÉDAILLON, indépendante de la saison
        // (voir MedallionMode dans Palette.kt) :
        //   MedallionMode.HOST     — toujours crème, lecture "hostie" [réglage actuel]
        //   MedallionMode.SEASONAL — cœur sombre propre à la saison active
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
        val config = VitrailConfig(
            seed = seed,
            numberOfSites = numberOfSites,

            // Système 1 — Composition
            // Alternatives disponibles : GridCompositionSystem(),
            // OrganicSitesCompositionSystem(baseSiteDistance, sizeVariation),
            // CurveGuidedCompositionSystem(numberOfGuideCurves, baseSiteDistance, sizeVariation),
            // RadialCompositionSystem(numberOfRays, numberOfRings, radialJitterRatio, radialJitterRatio),
            // SunburstCompositionSystem(sunburstNumberOfRays, sunburstCoreRadiusRatio, sunburstAngleIrregularity, sunburstMinDivisionsPerRay, sunburstMaxDivisionsPerRay)
            compositionSystem = RadiantCrossCompositionSystem(
                coreRadiusRatio = crossCoreRadiusRatio,
                numberOfRays = crossNumberOfRays,
                angleIrregularity = crossAngleIrregularity,
                minDivisionsPerRay = crossMinDivisionsPerRay,
                maxDivisionsPerRay = crossMaxDivisionsPerRay,
                lightLengthMinRatio = crossLightLengthMinRatio,
                lightLengthMaxRatio = crossLightLengthMaxRatio,
                armWindows = crossArmWindows
            ), // <-- swap ici

            // Système 2 — Segmentation (à échanger avec la composition)
            // Alternatives disponibles : GridSegmentationSystem(),
            // VoronoiSegmentationSystem(relaxationIterations),
            // RadialSegmentationSystem(), SunburstSegmentationSystem()
            segmentationSystem = RadiantCrossSegmentationSystem(), // <-- swap ici

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
            leadSystem = BasicLeadSystem(),
            leadStyle = LeadStyle(
                width = strokeWeight,
                color = strokeColor
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
            backgroundColor = backgroundColor
        )

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

        keyboard.keyDown.listen {
            if (it.name == "e") {
                // Voir Export.kt : rendu hors-écran à fond transparent,
                // enregistré dans exports/vitrail-seed<seed>-<horodatage>.png
                exportHighResolution(
                    drawer = drawer,
                    config = config,
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
            drawer.clear(config.backgroundColor)
            renderVitrail(drawer, config, width.toDouble(), height.toDouble(), 1.0)
        }
    }
}