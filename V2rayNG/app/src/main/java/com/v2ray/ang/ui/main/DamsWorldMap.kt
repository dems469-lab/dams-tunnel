package com.v2ray.ang.ui.main

/**
 * Silhouette des continents (Natural Earth 1:110m, domaine public) échantillonnée
 * en [WORLD_MAP_COLUMNS] x [WORLD_MAP_ROWS] cellules, encodée en base64.
 * Utilisée uniquement pour dessiner la carte numérique de l'arrière-plan.
 */
internal object DamsWorldMap {
    const val COLUMNS = 96
    const val ROWS = 48

    private const val ENCODED =
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD+//wAAwAQAAAAACC5//gCAAAGAAAAAHX0H/AAAIH" +
        "/0AAD+99TD+AB4Cv////3///jDwwHZ//////D//+GBgAe//////uAg/+HgAAEf////wgAAf/34AB" +
        "L/////ggAAP//wAB//////gAAAH//gAB//////gAAAH/+AADlh////YAAAH/8AAHLf///8QAAAD/" +
        "8AAC4D///ygAAAB/wAAH9n///wAAAAA8IAAH//v//wAAAAAcAAAP/74//wAAAAAMkAAP/98ecAAA" +
        "AAAHAAAP/9wMeIAAAAAAQAAP/+AIOAAAAAAADwAP//gMAUAAAAAAH8AHf/gAAQAAAAAAH+AAH/AA" +
        "MgAAAAAAP/AAP+AAFhAAAAAAP/4AH8AAAAYAAAAAH/4AH+AAAAEAAAAAD/wAH+AAABAAAAAAB/wA" +
        "H8gAAHoAAAAAB/gAD4gAAf8AAAAAB+AAD4gAA/+AAAAAB+AAD4AAA/+AAAAAB8AABwAAAd+AAAAA" +
        "B4AAAAAAAAcCAAAADgAAAAAAAAACAAAABgAAAAAAAAAEAAAADAAAAAAAAAAAAAAADAAAAAAAAAAA" +
        "AAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAA"

    /** Bit par cellule, ligne par ligne, de gauche à droite. */
    fun landMask(): BooleanArray {
        val bytes = android.util.Base64.decode(ENCODED, android.util.Base64.DEFAULT)
        val mask = BooleanArray(COLUMNS * ROWS)
        for (index in mask.indices) {
            val byte = bytes[index / 8].toInt()
            mask[index] = (byte shr (7 - (index % 8))) and 1 == 1
        }
        return mask
    }
}
