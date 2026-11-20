package io.github.hoerire.free.bubble

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import io.github.hoerire.free.config.ModulePreferences

/**
 * 按身份打开通知对应的实例。
 *
 * 分身的关键不是「用包名启动」，而是让这次启动带上 ColorOS 多应用的两个内部标记：
 * `oplus.intent.extra.cached_userId`（钉住实例）和 `OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER`
 *（不要弹「选择打开的应用」）。只调 `LauncherApps.startMainActivity` 会正常打开，
 * 但系统仍会先弹分身选择器——用户点气泡时看到三个微信就是这个原因。
 *
 * 顺序：先在该用户的上下文里显式 startActivity（带标记，保留通知深链），
 * 失败才退回 LauncherApps，最后才用 contentIntent 兜底。
 */
internal class BubbleLauncher(
    private val context: Context,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val launcherApps: LauncherApps?
        get() = context.getSystemService(LauncherApps::class.java)

    fun open(item: BubbleItem): Boolean {
        val identity = item.identity
        if (identity.isClone) {
            // 分身：先把启动请求包成「目标用户 = 分身」的 PendingIntent，并带上
            // cached_userId / SKIP_CHOOSER / EXTRA_SKIP_ZOOM 三个标记。这样 ActivityStarter
            // 直接落在分身上，也不会被本模块的 ActivityStarterZoomInjector 改写成主用户小窗。
            if (startAsUserViaPendingIntent(identity)) return true
            if (startZoomWindow(identity)) return true
            if (openInUserContext(identity)) return true
            if (startMainActivity(identity)) return true
            // 分身通知的 contentIntent 由主用户实例签发，发送后会落回主应用，因此排最后。
            if (sendContentIntent(item.contentIntent, identity)) return true
        } else {
            if (sendContentIntent(item.contentIntent, identity)) return true
            if (openInUserContext(identity)) return true
            if (startMainActivity(identity)) return true
        }
        logger("BUBBLE_OPEN_FAILED_" + identity.whitelistKey, null)
        return false
    }

    /**
     * 以目标用户身份发送一个 PendingIntent。
     *
     * `PendingIntent.getActivityAsUser` 是隐藏 API，但 SystemUI 属于系统应用、又持有
     * INTERACT_ACROSS_USERS_FULL，反射可用。Intent 里的 `cached_userId` 与
     * `OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER` 让 ColorOS 把这次启动钉在分身上。
     */
    private fun startAsUserViaPendingIntent(identity: BubbleIdentity): Boolean {
        val apps = launcherApps ?: return false
        val user = userOf(identity.userId)
        return runCatching {
            val component =
                apps.getActivityList(identity.packageName, user)
                    .firstOrNull()
                    ?.componentName
                    ?: return false
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 本模块自己的 ActivityStarterZoomInjector 会把 SystemUI 发起的启动改写成
            // 主用户小窗（日志 ZOOM_DECISION_execute...tgcom.tencent.mm），分身身份因此丢失。
            // 打上「模块代发、保持原样」标记，让注入器放行这条启动。
            intent.putExtra(ModulePreferences.EXTRA_SKIP_ZOOM, true)
            applyMultiAppHint(intent, identity.userId)
            val method =
                PendingIntent::class.java.getMethod(
                    "getActivityAsUser",
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java,
                    UserHandle::class.java,
                )
            val pending =
                method.invoke(
                    null,
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    // 创建 PendingIntent 时不能再带 pendingIntentBackgroundActivityStartMode
                    // （系统会抛 IllegalArgumentException）；SystemUI 本身有
                    // START_ACTIVITIES_FROM_BACKGROUND，发送时不需要这个 mode。
                    null,
                    user,
                ) as? PendingIntent ?: return false
            pending.send()
            logger("BUBBLE_OPEN_PENDING_USER_" + identity.whitelistKey, null)
            true
        }.getOrElse {
            val cause = it.cause ?: it
            logger(
                "BUBBLE_OPEN_PENDING_USER_FAILED_" + identity.whitelistKey + "_" +
                    cause.javaClass.simpleName + "_" + cause.message,
                cause,
            )
            false
        }
    }

    /**
     * 厂商小窗入口：与侧边栏/自由窗同一条路径，第三参数就是目标 userId。
     *
     * ROM 把 userId 直接交给 ActivityStarter，不经过多应用解析，因此不会弹
     * 「选择打开的应用」。
     */
    private fun startZoomWindow(identity: BubbleIdentity): Boolean {
        val apps = launcherApps ?: return false
        val user = userOf(identity.userId)
        return runCatching {
            val component =
                apps.getActivityList(identity.packageName, user)
                    .firstOrNull()
                    ?.componentName
                    ?: return false
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .setPackage(identity.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(ModulePreferences.EXTRA_SKIP_ZOOM, true)
            // 注意：这里**不加** cached_userId / SKIP_CHOOSER。
            // 这两个标记会让 ROM 跳过解析器并选「默认实例」（= 主应用）；
            // 分身身份只靠 startZoomWindow 的第三个参数 userId 传下去。
            val managerClass = Class.forName(ZOOM_MANAGER_CLASS)
            val manager = managerClass.getMethod("getInstance").invoke(null) ?: return false
            val method =
                managerClass.getMethod(
                    "startZoomWindow",
                    Intent::class.java,
                    Bundle::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            val options =
                Bundle().apply {
                    putInt("android.activity.launchDisplayId", 0)
                    putInt("extra_window_mode", 100)
                }
            val result =
                method.invoke(manager, intent, options, identity.userId, ZOOM_CALLER) as? Int
            if (result != null && result >= 0) {
                logger("BUBBLE_OPEN_ZOOM_WINDOW_" + identity.whitelistKey, null)
                true
            } else {
                false
            }
        }.getOrElse {
            logger("BUBBLE_OPEN_ZOOM_WINDOW_FAILED_" + identity.whitelistKey, it)
            false
        }
    }

    /** 在目标用户上下文里显式启动；带 OPPO 多应用标记，避免弹出分身选择器。 */
    private fun openInUserContext(identity: BubbleIdentity): Boolean {
        val userContext = contextForUser(identity.userId)
        return runCatching {
            val intent =
                userContext.packageManager.getLaunchIntentForPackage(identity.packageName)
                    ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(ModulePreferences.EXTRA_SKIP_ZOOM, true)
            applyMultiAppHint(intent, identity.userId)
            userContext.startActivity(intent, backgroundStartOptions())
            logger("BUBBLE_OPEN_USER_CONTEXT_" + identity.whitelistKey, null)
            true
        }.getOrElse {
            logger("BUBBLE_OPEN_USER_CONTEXT_FAILED_" + identity.whitelistKey, it)
            false
        }
    }

    private fun sendContentIntent(intent: PendingIntent?, identity: BubbleIdentity): Boolean {
        if (intent == null) return false
        return runCatching {
            val fillIn =
                Intent().apply {
                    putExtra(MULTI_APP_CACHED_USER_ID_EXTRA, identity.userId)
                    addSkipChooserFlag(this)
                }
            intent.send(context, 0, fillIn, null, null, null, backgroundStartOptions())
            logger("BUBBLE_OPEN_CONTENT_INTENT_" + identity.whitelistKey, null)
            true
        }.getOrElse {
            logger("BUBBLE_CONTENT_INTENT_FAILED_" + identity.whitelistKey, it)
            false
        }
    }

    private fun startMainActivity(identity: BubbleIdentity): Boolean {
        val apps = launcherApps ?: return false
        val user = userOf(identity.userId)
        return runCatching {
            val component =
                apps.getActivityList(identity.packageName, user)
                    .firstOrNull()
                    ?.componentName
                    ?: return false
            apps.startMainActivity(component, user, null, backgroundStartOptions())
            logger("BUBBLE_OPEN_LAUNCHER_APPS_" + identity.whitelistKey, null)
            true
        }.getOrElse {
            logger("BUBBLE_LAUNCHER_ACTIVITY_FAILED_" + identity.whitelistKey, it)
            false
        }
    }

    private fun backgroundStartOptions(): Bundle? =
        runCatching {
            val options = ActivityOptions.makeBasic()
            options.setPendingIntentBackgroundActivityLaunchAllowed(true)
            options.setLaunchDisplayId(0)
            options.toBundle()
        }.getOrNull()

    private fun userOf(userId: Int): UserHandle =
        runCatching { UserHandle.getUserHandleForUid(userId * PER_USER_RANGE) }
            .getOrElse { Process.myUserHandle() }

    private fun contextForUser(userId: Int): Context {
        if (userId <= 0) return context
        return runCatching {
            val method =
                Context::class.java.getMethod(
                    "createContextAsUser",
                    UserHandle::class.java,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(context, userOf(userId), 0) as? Context
        }.getOrNull().also {
            logger("BUBBLE_CONTEXT_U" + userId + "_" + (it != null), null)
        } ?: context
    }

    /**
     * ColorOS 多应用提示：把这次启动钉在通知所属实例上，并跳过「选择打开的应用」。
     * 两个标识都是 OPPO 内部字段，失败就算了。
     */
    private fun applyMultiAppHint(intent: Intent, userId: Int) {
        runCatching { intent.putExtra(MULTI_APP_CACHED_USER_ID_EXTRA, userId) }
        addSkipChooserFlag(intent)
    }

    private fun addSkipChooserFlag(intent: Intent) {
        runCatching {
            val extClass = Class.forName("android.content.IIntentExt")
            val skipChooser = extClass.getField("OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER").getInt(null)
            val ext = Intent::class.java.getMethod("getIntentExt").invoke(intent) ?: return
            ext.javaClass.getMethod("addOplusFlags", Int::class.javaPrimitiveType)
                .invoke(ext, skipChooser)
        }
    }

    private companion object {
        const val MULTI_APP_CACHED_USER_ID_EXTRA = "oplus.intent.extra.cached_userId"
        const val PER_USER_RANGE = 100000
        const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
        /** 系统侧边栏：ROM 的 zoom 入口只认这个调用方。 */
        const val ZOOM_CALLER = "com.coloros.smartsidebar"
    }
}
