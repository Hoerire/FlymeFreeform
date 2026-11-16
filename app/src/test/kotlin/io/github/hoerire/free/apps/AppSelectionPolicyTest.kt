package io.github.hoerire.free.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun emptyPinsProduceEmptyRadial() {
        assertEquals(
            emptyList<String>(),
            AppSelectionPolicy.radialItems(emptyList(), { value: String -> value }, 6),
        )
    }

    @Test
    fun onlyPinnedEntriesAreShown() {
        assertEquals(
            listOf("available"),
            AppSelectionPolicy.radialItems(listOf("available"), { value: String -> value }, 6),
        )
        // 重复项去重；超过上限时截断；不掺任何「最近应用」。
        assertEquals(
            listOf("a", "b"),
            AppSelectionPolicy.radialItems(listOf("a", "b", "a"), { value: String -> value }, 6),
        )
        assertEquals(
            listOf("a", "b"),
            AppSelectionPolicy.radialItems(listOf("a", "b", "c"), { value: String -> value }, 2),
        )
    }
}
