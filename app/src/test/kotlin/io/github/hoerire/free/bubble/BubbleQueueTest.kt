package io.github.hoerire.free.bubble

import io.github.hoerire.free.config.ModuleSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleQueueTest {
    @Test
    fun sameNotificationKeyReplacesInsteadOfStacking() {
        val queue = BubbleQueue()
        queue.add(item("com.example", 0, "k1"))
        queue.add(item("com.example", 0, "k1", body = "updated"))

        assertEquals(1, queue.size)
        assertEquals("updated", queue.head?.payload?.body)
    }

    @Test
    fun newestBecomesHeadAndOldestIsDroppedAtCapacity() {
        val queue = BubbleQueue(capacity = 2)
        queue.add(item("a", 0, "k1"))
        queue.add(item("b", 0, "k2"))
        queue.add(item("c", 0, "k3"))

        assertEquals(2, queue.size)
        assertEquals("c", queue.head?.packageName)
        assertEquals(listOf("c", "b"), queue.snapshot().map { it.packageName })
    }

    @Test
    fun cloneAndMainOfSamePackageAreDifferentEntries() {
        val queue = BubbleQueue()
        queue.add(item("com.tencent.mm", 0, "main"))
        queue.add(item("com.tencent.mm", 999, "clone"))

        assertEquals(2, queue.size)
        assertEquals(999, queue.head?.userId)
        // 删主应用那条不影响分身。
        queue.remove("main")
        assertEquals(1, queue.size)
        assertEquals(999, queue.head?.userId)
    }

    @Test
    fun pruneDropsEntriesThatAreNoLongerWhitelisted() {
        val queue = BubbleQueue()
        queue.add(item("a", 0, "k1"))
        queue.add(item("b", 0, "k2"))

        queue.prune { it.packageName == "a" }

        assertEquals(listOf("a"), queue.snapshot().map { it.packageName })
    }

    @Test
    fun removalPolicyOnlyDropsUserInitiatedRemovals() {
        // 用户点掉/清掉/超时：气泡一起收。
        assertTrue(BubbleRemovalPolicy.shouldDrop(1))
        assertTrue(BubbleRemovalPolicy.shouldDrop(2))
        assertTrue(BubbleRemovalPolicy.shouldDrop(3))
        assertTrue(BubbleRemovalPolicy.shouldDrop(19))
        // 应用自己 cancel（reason 8/9/17）不处理气泡。
        assertFalse(BubbleRemovalPolicy.shouldDrop(8))
        assertFalse(BubbleRemovalPolicy.shouldDrop(9))
        assertFalse(BubbleRemovalPolicy.shouldDrop(17))
        // 无 reason 的原生流体云回调按用户清除处理。
        assertTrue(BubbleRemovalPolicy.shouldDrop(-1))
    }

    @Test
    fun whitelistKeysDistinguishMainAppFromClone() {
        val snapshot =
            ModuleSettingsSnapshot(
                enabled = true,
                bubbleEnabled = true,
                bubbleWhitelist = listOf("com.example"),
                bubbleCloneWhitelist = listOf("com.example#999"),
            )
        val settings = BubbleSettings.from(snapshot)

        assertTrue(settings.accepts(BubbleIdentity("com.example", 0)))
        assertTrue(settings.accepts(BubbleIdentity("com.example", 999)))
        // 勾了分身不等于勾了主应用，反之亦然。
        assertFalse(settings.accepts(BubbleIdentity("com.example", 998)))
        assertFalse(settings.accepts(BubbleIdentity("com.other", 0)))
    }

    @Test
    fun keyCodecRoundTripsAndRejectsMalformedKeys() {
        assertEquals("com.example", BubbleKey.key("com.example", 0))
        assertEquals("com.example#999", BubbleKey.key("com.example", 999))
        assertEquals(BubbleIdentity("com.example", 999), BubbleKey.parse("com.example#999"))
        assertEquals(BubbleIdentity("com.example", 0), BubbleKey.parse("com.example"))
        assertEquals(null, BubbleKey.parse("com.example#0"))
        assertEquals(null, BubbleKey.parse("#999"))
        assertEquals(null, BubbleKey.parse("com.example#abc"))
    }

    @Test
    fun moduleSwitchGatesBubbleWhitelist() {
        val off = ModuleSettingsSnapshot(bubbleEnabled = true, bubbleWhitelist = listOf("com.example"))
        assertFalse(BubbleSettings.from(off).active)

        val on =
            ModuleSettingsSnapshot(
                enabled = true,
                bubbleEnabled = true,
                bubbleWhitelist = listOf("com.example"),
            )
        assertTrue(BubbleSettings.from(on).active)
    }

    private fun item(
        packageName: String,
        userId: Int,
        key: String,
        body: String = "body",
    ): BubbleItem =
        BubbleItem(
            identity = BubbleIdentity(packageName, userId, key),
            payload = BubblePayload(null, null, packageName, "title", body),
            contentIntent = null,
            postedAt = 0L,
        )
}
