package io.github.hoerire.free.platform.coloros

import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.RadialGroup
import io.github.hoerire.free.config.RadialGroupSide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogSnapshotTest {
    @Test
    fun lateCatalogCannotReplaceNewerSelection() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested))
        assertFalse(completed.matches(requested.copy(pinsSaved = true)))
    }

    @Test
    fun unrelatedGestureSettingsDoNotInvalidatePreparedIcons() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertTrue(completed.matches(requested.copy(cornerTriggerRangeDp = 100, enabled = true)))
    }

    @Test
    fun editingTheAlternateGroupInvalidatesPreparedCatalog() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        assertFalse(
            completed.matches(
                requested.copy(
                    alternatePinnedTargets = listOf(PinnedTarget.App("com.alt", "com.alt.Main")),
                ),
            ),
        )
    }

    @Test
    fun switchingSideGroupDoesNotInvalidatePreparedIcons() {
        val requested = ModuleSettingsSnapshot()
        val completed = AppCatalogSnapshot(settings = requested)
        // 分组只是「这一侧取哪一份列表」，两组目录始终一起准备，换边不需要重建。
        assertTrue(
            completed.matches(
                requested.copy(
                    secondaryGroupSide = RadialGroupSide.Left,
                ),
            ),
        )
    }
}
