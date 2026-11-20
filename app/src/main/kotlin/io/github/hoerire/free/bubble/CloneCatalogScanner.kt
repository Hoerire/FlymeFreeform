package io.github.hoerire.free.bubble

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.graphics.drawable.toBitmap

/**
 * 分身扫描：只在持有 MANAGE_USERS 的进程里跑。
 *
 * SystemUI 以系统 uid 运行并持有 `MANAGE_USERS`，所以扫描直接放在气泡子系统自己的进程里，
 * 不依赖 system_server 侧的实现——装完模块重启一次 SystemUI 就能拿到画像。
 * 扫描放后台线程，图标压到 72px，结果上限 64 条。
 */
internal class CloneCatalogScanner(
    private val context: Context,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    fun scan(): List<CloneApp> {
        val userManager = context.getSystemService(UserManager::class.java) ?: return emptyList()
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val density =
            runCatching { context.resources.displayMetrics.densityDpi }.getOrDefault(320)
        val result = ArrayList<CloneApp>()
        val selfUserId = runCatching { Process.myUid() / PER_USER_RANGE }.getOrDefault(0)
        for ((userId, user) in usersOf(userManager)) {
            if (userId == selfUserId) continue
            val activities =
                runCatching { launcherApps.getActivityList(null, user) }
                    .getOrElse {
                        logger("BUBBLE_CLONE_USER_FAILED", it)
                        continue
                    }
            for (info in activities.sortedBy { it.componentName.packageName }) {
                if (result.size >= MAX_ENTRIES) break
                val packageName = info.componentName.packageName
                if (result.any { it.packageName == packageName && it.userId == userId }) {
                    continue
                }
                val label =
                    runCatching { info.label?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                        .ifBlank { packageName }
                val icon =
                    runCatching {
                        info.getBadgedIcon(density).toBitmap(ICON_PX, ICON_PX)
                    }.getOrNull()
                result += CloneApp(packageName, userId, label, icon)
            }
        }
        return result
    }

    /**
     * `UserManager.getUsers()` 是隐藏 API；SystemUI 属于系统应用，隐藏 API 限制不生效。
     *
     * 返回元素类型随 ROM 而异：AOSP 为 `UserHandle`，ColorOS 为 `UserInfo`。两种都接，
     * 因为把后者强转成前者会抛 `ClassCastException`。
     */
    private fun usersOf(userManager: UserManager): List<Pair<Int, UserHandle>> {
        val raw: List<*> =
            runCatching {
                val method = UserManager::class.java.getMethod("getUsers")
                method.invoke(userManager) as? List<*> ?: emptyList<Any?>()
            }.getOrElse {
                logger("BUBBLE_CLONE_USERS_FAILED", it)
                return emptyList()
            }
        return raw.mapNotNull { item ->
            when (item) {
                is UserHandle -> userIdOf(item).takeIf { it > 0 }?.let { it to item }
                null -> null
                else -> {
                    val id =
                        runCatching {
                            val field = item.javaClass.getField("id")
                            field.getInt(item)
                        }.getOrElse {
                            runCatching {
                                item.javaClass.getMethod("getIdentifier").invoke(item) as Int
                            }.getOrDefault(-1)
                        }
                    if (id > 0) {
                        id to UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
                    } else {
                        null
                    }
                }
            }
        }
    }

    /** `UserHandle.getIdentifier()` 在 android.jar 里没有，但运行时存在；兜底按 mHandle 折算。 */
    private fun userIdOf(user: UserHandle): Int =
        runCatching {
            val method = UserHandle::class.java.getMethod("getIdentifier")
            method.invoke(user) as Int
        }.getOrElse { runCatching { user.hashCode() / PER_USER_RANGE }.getOrDefault(0) }

    private companion object {
        const val MAX_ENTRIES = 64
        const val ICON_PX = 72
        const val PER_USER_RANGE = 100000
    }
}
