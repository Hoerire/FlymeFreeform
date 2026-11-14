package io.github.hoerire.flymefreeform.update

/** 版本号比较：按点分段比大小，缺失段按 0 处理。 */
internal object VersionOrder {
    /**
     * 返回负数表示 [left] 更旧，0 表示相同，正数表示 [left] 更新。
     *
     * 只比较数字段，允许带前缀 `v` 或后缀（如 `2.3.4-beta` 取 2.3.4）。
     */
    fun compare(left: String, right: String): Int {
        val a = segmentsOf(left)
        val b = segmentsOf(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    private fun segmentsOf(version: String): List<Int> =
        version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
