package com.muguang.album.playback

/** Numeric runs are compared by magnitude without parsing, so arbitrarily long names are safe. */
fun compareNaturally(left: String, right: String): Int {
    var a = 0
    var b = 0
    while (a < left.length && b < right.length) {
        val ca = left[a]
        val cb = right[b]
        if (ca in '0'..'9' && cb in '0'..'9') {
            val aStart = a
            val bStart = b
            while (a < left.length && left[a] in '0'..'9') a++
            while (b < right.length && right[b] in '0'..'9') b++
            var aSignificant = aStart
            var bSignificant = bStart
            while (aSignificant < a - 1 && left[aSignificant] == '0') aSignificant++
            while (bSignificant < b - 1 && right[bSignificant] == '0') bSignificant++
            val lengthResult = (a - aSignificant).compareTo(b - bSignificant)
            if (lengthResult != 0) return lengthResult
            for (index in 0 until a - aSignificant) {
                val result = left[aSignificant + index].compareTo(right[bSignificant + index])
                if (result != 0) return result
            }
            val zeroResult = (a - aStart).compareTo(b - bStart)
            if (zeroResult != 0) return zeroResult
        } else {
            val result = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (result != 0) return result
            a++
            b++
        }
    }
    val remaining = (left.length - a).compareTo(right.length - b)
    return if (remaining != 0) remaining else left.compareTo(right)
}

fun <T> naturalComparator(name: (T) -> String): Comparator<T> =
    Comparator { left, right -> compareNaturally(name(left), name(right)) }

/** A pure queue helper, shared by music and its tests. */
fun <T> shuffledRound(items: List<T>, previousLast: T? = null, random: kotlin.random.Random = kotlin.random.Random.Default): List<T> {
    val result = items.shuffled(random).toMutableList()
    if (result.size > 1 && previousLast != null && result.first() == previousLast) {
        val other = random.nextInt(1, result.size)
        val first = result[0]
        result[0] = result[other]
        result[other] = first
    }
    return result
}
