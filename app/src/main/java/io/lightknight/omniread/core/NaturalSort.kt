package io.lightknight.omniread.core

/**
 * Alphanumeric natural comparator so "page_2.jpg" sorts before "page_10.jpg".
 */
object NaturalSort : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var numEndA = i
                while (numEndA < a.length && a[numEndA].isDigit()) numEndA++
                var numEndB = j
                while (numEndB < b.length && b[numEndB].isDigit()) numEndB++

                val segA = a.substring(i, numEndA).trimStart('0')
                val segB = b.substring(j, numEndB).trimStart('0')

                if (segA.length != segB.length) {
                    return segA.length.compareTo(segB.length)
                }
                val cmp = segA.compareTo(segB)
                if (cmp != 0) return cmp

                // Tie-breaker: fewer leading zeros first
                val rawLenCmp = (numEndA - i).compareTo(numEndB - j)
                if (rawLenCmp != 0) return rawLenCmp

                i = numEndA
                j = numEndB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }
}
