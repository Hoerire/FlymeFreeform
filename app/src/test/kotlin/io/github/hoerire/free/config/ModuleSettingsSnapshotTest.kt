package io.github.hoerire.free.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsSnapshotTest {
    @Test
    fun legacyPinsMigrateOnWriteAndKeepOrder() {
        val preferences = InMemoryPreferences(ModulePreferences.KEY_CORNER_PINS to "com.first/.A\ncom.second/.B")
        val migrated = ModuleSettingsSnapshot.readFrom(preferences)
        assertTrue(migrated.pinsSaved)
        assertEquals(listOf(PinnedTarget.App("com.first", "com.first.A"), PinnedTarget.App("com.second", "com.second.B")), migrated.pinnedTargets)
        migrated.writeTo(preferences.edit()).commit()
        assertFalse(preferences.contains(ModulePreferences.KEY_CORNER_PINS))
        assertTrue(preferences.contains(ModulePreferences.KEY_PINNED_TARGETS))
        assertEquals(migrated, ModuleSettingsSnapshot.readFrom(preferences))
    }

    @Test
    fun explicitEmptyNewPinsOverrideLegacyAndRemainEmptyAfterSaving() {
        val preferences = InMemoryPreferences(
            ModulePreferences.KEY_PINNED_TARGETS to "",
            ModulePreferences.KEY_CORNER_PINS to "com.old/.Main",
        )
        val empty = ModuleSettingsSnapshot.readFrom(preferences)
        assertTrue(empty.pinsSaved)
        assertTrue(empty.pinnedTargets.isEmpty())
        empty.writeTo(preferences.edit()).commit()
        assertEquals(empty, ModuleSettingsSnapshot.readFrom(preferences))
    }

    @Test
    fun mixedUnavailableTargetsArePreservedByUnrelatedSettingsWrites() {
        val targets = listOf(
            PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
            PinnedTarget.App("com.example", "com.example.Main"),
            PinnedTarget.Sidebar(PinnedTarget.Kind.Shortcut, "com.example_note", 0),
        )
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(pinsSaved = true, pinnedTargets = targets).writeTo(preferences.edit()).commit()
        ModuleSettingsSnapshot.readFrom(preferences).copy(pauseInLandscape = false).writeTo(preferences.edit()).commit()
        assertEquals(targets, ModuleSettingsSnapshot.readFrom(preferences).pinnedTargets)
    }

    @Test
    fun missingKeysUseProductDefaults() {
        val snapshot = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())

        assertEquals(84, snapshot.cornerTriggerRangeDp)
        assertEquals(OutsideTapCloseMode.Disabled, snapshot.outsideTapCloseMode)
        assertFalse(snapshot.handleSwipeUpToMiniEnabled)
        assertTrue(snapshot.pauseInLandscape)
        assertTrue(snapshot.pauseInGameMode)
    }

    @Test
    fun retiredAppearanceSettingsDoNotAffectCurrentSettings() {
        val preferences = InMemoryPreferences(
            "radial_icon_mask_scale_percent" to "retired",
            "radial_icon_content_scale_percent" to "retired",
            "radial_circular_icons_enabled" to "retired",
            "radial_icon_size_percent_v1" to "retired",
            "radial_icon_roundness_percent_v1" to "retired",
        )
        val expected = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())
        val actual = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(expected, actual)
        actual.writeTo(preferences.edit()).commit()
        assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
        assertEquals("retired", preferences.getString("radial_icon_size_percent_v1", null))
        assertEquals("retired", preferences.getString("radial_icon_roundness_percent_v1", null))
    }

    @Test
    fun interactionSettingsRoundTrip() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            outsideTapCloseMode = OutsideTapCloseMode.DoubleTap,
            handleSwipeUpToMiniEnabled = false,
        ).writeTo(preferences.edit()).commit()

        val restored = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(OutsideTapCloseMode.DoubleTap, restored.outsideTapCloseMode)
        assertFalse(restored.handleSwipeUpToMiniEnabled)
    }

    @Test
    fun pauseSwitchesRoundTripIndependently() {
        for (landscape in listOf(false, true)) {
            for (game in listOf(false, true)) {
                val preferences = InMemoryPreferences()
                val expected = ModuleSettingsSnapshot(
                    enabled = true,
                    pauseInLandscape = landscape,
                    pauseInGameMode = game,
                )
                expected.writeTo(preferences.edit()).commit()
                assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
            }
        }
    }

    @Test
    fun eitherEnabledPauseRuleBlocksItsEnvironment() {
        val defaults = ModuleSettingsSnapshot()
        assertFalse(defaults.isPausedByEnvironment(landscape = false, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = false, gameMode = true))

        val allowLandscape = defaults.copy(pauseInLandscape = false)
        assertFalse(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowGames = defaults.copy(pauseInGameMode = false)
        assertFalse(allowGames.isPausedByEnvironment(landscape = false, gameMode = true))
        assertTrue(allowGames.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowBoth = defaults.copy(pauseInLandscape = false, pauseInGameMode = false)
        assertFalse(allowBoth.isPausedByEnvironment(landscape = true, gameMode = true))
    }

    @Test
    fun unknownOutsideTapModeFailsClosed() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE to 99)

        assertEquals(
            OutsideTapCloseMode.Disabled,
            ModuleSettingsSnapshot.readFrom(preferences).outsideTapCloseMode,
        )
    }

    @Test
    fun cornerRangeIsClampedWhenRead() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP to 8,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(24, snapshot.cornerTriggerRangeDp)
    }

    @Test
    fun bothCornersDisabledInStoredPreferencesNormalizeToBothEnabled() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_LEFT_CORNER_ENABLED to false,
                ModulePreferences.KEY_RIGHT_CORNER_ENABLED to false,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertTrue(snapshot.leftCornerEnabled)
        assertTrue(snapshot.rightCornerEnabled)
    }

    @Test
    fun cornerRangeIsClampedAndRoundTripsWhenWritten() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            cornerTriggerRangeDp = 200,
        ).writeTo(preferences.edit()).commit()

        assertEquals(
            160,
            preferences.getInt(ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP, 0),
        )
        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(160, restored.cornerTriggerRangeDp)
    }

    @Test
    fun alternateGroupAndEffectiveSideRoundTripIndependently() {
        val alternate =
            listOf(
                PinnedTarget.App("com.alt", "com.alt.Main"),
                PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0),
            )
        val expected =
            ModuleSettingsSnapshot(
                pinsSaved = true,
                pinnedTargets = listOf(PinnedTarget.App("com.main", "com.main.Main")),
                alternatePinnedTargets = alternate,
                secondaryGroupSide = RadialGroupSide.Right,
            )
        val preferences = InMemoryPreferences()
        expected.writeTo(preferences.edit()).commit()

        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(expected, restored)
        assertEquals(expected.pinnedTargets, restored.pinsFor(RadialGroup.Primary))
        assertEquals(alternate, restored.pinsFor(RadialGroup.Alternate))
        assertEquals(RadialGroupSide.Right, restored.secondaryGroupSide)
        // 右侧换成第二组，左侧仍是默认组。
        assertEquals(RadialGroup.Primary, restored.groupForSide(left = true))
        assertEquals(RadialGroup.Alternate, restored.groupForSide(left = false))
    }

    @Test
    fun missingAlternateGroupLeavesPrimaryPinsUntouched() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_PINNED_TARGETS to "A|com.a/com.a.A")

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertTrue(snapshot.alternatePinnedTargets.isEmpty())
        assertEquals(
            listOf(PinnedTarget.App("com.a", "com.a.A")),
            snapshot.pinnedTargets,
        )
        // 默认不启用：两侧都用默认组。
        assertEquals(RadialGroupSide.Disabled, snapshot.secondaryGroupSide)
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }

    @Test
    fun unknownStoredEffectiveSideFallsBackToDisabled() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_SECONDARY_GROUP_SIDE to 99)

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(RadialGroupSide.Disabled, snapshot.secondaryGroupSide)
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }

    @Test
    fun pinnedMembershipCoversBothGroups() {
        val primaryPin = PinnedTarget.App("com.main", "com.main.Main")
        // 第二组里的工具以前会被启动前的准入校验当成「不在固定项」，导致点了没反应。
        val alternatePin = PinnedTarget.Sidebar(PinnedTarget.Kind.Tool, "capture", 0)
        val snapshot =
            ModuleSettingsSnapshot(
                pinsSaved = true,
                pinnedTargets = listOf(primaryPin),
                alternatePinnedTargets = listOf(alternatePin),
            )

        assertTrue(snapshot.isPinned(primaryPin))
        assertTrue(snapshot.isPinned(alternatePin))
        assertFalse(snapshot.isPinned(PinnedTarget.App("com.other", "com.other.Main")))
    }

    @Test
    fun leftSelectedSecondaryGroupLeavesRightSideOnPrimary() {
        val snapshot =
            ModuleSettingsSnapshot(secondaryGroupSide = RadialGroupSide.Left)

        assertEquals(RadialGroup.Alternate, snapshot.groupForSide(left = true))
        assertEquals(RadialGroup.Primary, snapshot.groupForSide(left = false))
    }
}

private class InMemoryPreferences(vararg initialValues: Pair<String, Any>) : SharedPreferences {
    private val values = linkedMapOf(*initialValues)

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key]?.let { it as Set<String> } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any>()
        private val removals = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            updateNullable(key, value)

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            updateNullable(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = update(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = update(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = update(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = update(key, value)

        override fun remove(key: String): SharedPreferences.Editor = apply {
            updates.remove(key)
            removals += key
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clear = true
            updates.clear()
            removals.clear()
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun update(key: String, value: Any): SharedPreferences.Editor = apply {
            removals -= key
            updates[key] = value
        }

        private fun updateNullable(key: String, value: Any?): SharedPreferences.Editor =
            if (value == null) remove(key) else update(key, value)

        private fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }
}
