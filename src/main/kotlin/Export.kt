import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import java.io.File

// --------------------------------------------------
// EXPORT HAUTE RÉSOLUTION
// --------------------------------------------------

/**
 * Rend la recette `config` hors écran, à exportWidth × exportHeight
 * pixels, et l'enregistre en PNG dans le dossier "exports/" sous le
 * nom vitrail-seed<seed>-<horodatage>.png. Renvoie le fichier créé.
 *
 * Le motif n'est pas agrandi : il est entièrement RÉGÉNÉRÉ à la
 * résolution cible avec le même seed (voir renderVitrail dans
 * Renderer.kt), et les valeurs en pixels absolus (plomb, courbure)
 * sont multipliées par pixelScale = exportWidth / referenceWidth.
 * L'export est donc identique à l'aperçu, en plus grand.
 *
 * - referenceWidth : largeur de l'aperçu à l'écran (768), à laquelle
 *   correspondent les réglages en pixels de la recette.
 * - Le ratio exportWidth / exportHeight doit rester celui de
 *   l'aperçu (4:3 actuellement) ; le changer demanderait de revoir la
 *   composition, pas seulement ces deux nombres.
 */
fun exportHighResolution(
    drawer: Drawer,
    config: VitrailConfig,
    exportWidth: Int,
    exportHeight: Int,
    referenceWidth: Double
): File {

    val exportPixelScale = exportWidth / referenceWidth

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
            config,
            exportWidth.toDouble(),
            exportHeight.toDouble(),
            exportPixelScale
        )
    }

    val exportsFolder = File("exports").absoluteFile
    exportsFolder.mkdirs()

    val fileName = "vitrail-seed${config.seed}-${System.currentTimeMillis()}.png"
    val outputFile = exportsFolder.resolve(fileName)
    // async = false : on attend la fin de l'écriture avant de
    // continuer, pour pouvoir libérer exportTarget juste après
    // sans risquer une sauvegarde encore en cours.
    exportTarget.colorBuffer(0).saveToFile(outputFile, async = false)

    exportTarget.destroy()

    println("Export haute résolution enregistré : ${outputFile.absolutePath}")

    return outputFile
}