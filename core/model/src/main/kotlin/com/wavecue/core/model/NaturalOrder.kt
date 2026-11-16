package com.wavecue.core.model

/**
 * Natural, case-insensitive ordering tuned for album folders:
 * "2 Song" sorts before "10 Song", ignores a leading track number when it is
 * followed by a separator ("01 - ", "02. ", "3_"), and falls back to
 * case-sensitive comparison only when everything else matches.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ta = tokens(a)
        val tb = tokens(b)
        var i = 0
        while (i < ta.size && i < tb.size) {
            val x = ta[i]
            val y = tb[i]
            val cmp = if (x is Long && y is Long) x.compareTo(y) else x.toString().compareTo(y.toString(), ignoreCase = true)
            if (cmp != 0) return cmp
            i++
        }
        val byLength = ta.size.compareTo(tb.size)
        if (byLength != 0) return byLength
        return a.compareTo(b)
    }

    private fun tokens(value: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isDigit()) {
                var j = i
                while (j < value.length && value[j].isDigit()) j++
                out.add(value.substring(i, j).toLongOrNull() ?: value.substring(i, j))
                i = j
            } else {
                var j = i
                while (j < value.length && !value[j].isDigit()) j++
                out.add(value.substring(i, j))
                i = j
            }
        }
        // Drop a leading pure-number token when it reads like a track prefix.
        if (out.size >= 2 && out[0] is Long) {
            val rest = out[1].toString().trimStart()
            if (rest.startsWith("-") || rest.startsWith(".") || rest.startsWith("_") || rest.startsWith(" ")) {
                out.removeAt(0)
            }
        }
        return out
    }
}
