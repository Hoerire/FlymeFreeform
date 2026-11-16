package io.github.hoerire.free.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionPolicyTest {
    @Test
    fun emptyPinsProduceEmptyRadial() {
        assertEquals(
            emptyList<String>(),
            AppSelectionPolicy.radialItems(emptyList(), { it }, 6),
        )
    }

    @Test
    fun onlyPinnedEntriesAreShown() {
        assertEquals(
            listOf("available"),
            AppSelectionPolicy.radialItems(listOf("available"), { it }, 6),
        )
        // 重复项去重；超过上限时截断；不掺任何「最近应用」。
        assertEquals(
            listOf("a", "b"),
            AppSelectionPolicy.radialItems(listOf("a", "b", "a"), { it }, 6),
        )
        assertEquals(
            listOf("a", "b"),
            AppSelectionPolicy.radialItems(listOf("a", "b", "c"), { it }, 2),
        )
    }
}
