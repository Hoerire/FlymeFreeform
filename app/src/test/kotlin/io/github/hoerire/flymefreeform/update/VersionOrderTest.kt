package io.github.hoerire.flymefreeform.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较的契约测试：只有严格更新才提示，同版本与更旧的发行版都不提示。 */
class VersionOrderTest {
    @Test
    fun 同版本视为相同() {
        assertEquals(0, VersionOrder.compare("2.3.4", "2.3.4"))
        assertEquals(0, VersionOrder.compare("v2.3.4", "2.3.4"))
    }

    @Test
    fun 更高版本返回正数() {
        assertTrue(VersionOrder.compare("2.3.5", "2.3.4") > 0)
        assertTrue(VersionOrder.compare("2.4.0", "2.3.9") > 0)
        assertTrue(VersionOrder.compare("3.0", "2.9.9") > 0)
    }

    @Test
    fun 更低版本返回负数() {
        assertTrue(VersionOrder.compare("2.3.3", "2.3.4") < 0)
        assertTrue(VersionOrder.compare("2.3", "2.3.1") < 0)
    }

    @Test
    fun 前缀与后缀不影响比较() {
        assertEquals(0, VersionOrder.compare("V2.3.4", "2.3.4"))
        assertEquals(0, VersionOrder.compare("2.3.4-beta", "2.3.4"))
        assertTrue(VersionOrder.compare("2.3.5-rc", "2.3.4") > 0)
    }

    @Test
    fun 缺段按零补齐() {
        assertEquals(0, VersionOrder.compare("2.3", "2.3.0"))
        assertTrue(VersionOrder.compare("2.3.0.1", "2.3") > 0)
    }
}
