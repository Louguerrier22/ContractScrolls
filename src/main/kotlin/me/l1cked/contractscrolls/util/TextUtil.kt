package me.l1cked.contractscrolls.util

object TextUtil {
    fun stripLegacyColors(input: String?): String {
        if (input.isNullOrEmpty()) {
            return ""
        }
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\u00A7') {
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
