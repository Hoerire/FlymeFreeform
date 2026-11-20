package io.github.hoerire.free.bubble

import android.app.Notification
import android.content.Context
import android.content.pm.LauncherApps
import android.app.Person
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.text.TextUtils

/**
 * 通知 → 气泡内容的唯一转换点。
 *
 * 图标优先级与系统通知一致：大图标 > 会话头像 > 快捷方式图标 > 应用图标 > 小图标。
 * 分身通知用该用户的 LauncherApps 取 badged icon，主应用与分身因此看起来是同一个
 * 应用、不同实例。任何一步失败都退回下一级，绝不抛异常给通知监听线程。
 */
internal class BubblePayloadFactory(
    private val context: Context,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val density: Int
        get() = context.resources.displayMetrics.densityDpi

    private val launcherApps: LauncherApps?
        get() = context.getSystemService(LauncherApps::class.java)

    fun build(sbn: StatusBarNotification): BubblePayload {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val user = sbn.user
        val userContext = userContext(user)
        val packageContext =
            runCatching { userContext.createPackageContext(packageName, 0) }.getOrNull()
                ?: userContext
        val appActivity = activityOf(packageName, user)
        val label =
            runCatching { appActivity?.label?.toString()?.trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching {
                    launcherApps?.getApplicationInfo(packageName, 0, user)
                        ?.loadLabel(context.packageManager)
                        ?.toString()
                        ?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: packageName
        val appIcon =
            runCatching { appActivity?.getBadgedIcon(density) }.getOrNull()
                ?: runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0)
                        .loadIcon(context.packageManager)
                }.getOrNull()
        val icon =
            runCatching { notification.getLargeIcon()?.loadDrawable(packageContext) }.getOrNull()
                ?: personIcon(notification, packageContext)
                ?: shortcutIcon(sbn)
                ?: appIcon
                ?: runCatching { notification.getSmallIcon()?.loadDrawable(packageContext) }.getOrNull()
                ?: runCatching { context.getDrawable(android.R.drawable.sym_def_app_icon) }.getOrNull()
        val extras = notification.extras
        val title: String
        val body: String
        if (extras == null) {
            title = ""
            body = ""
        } else {
            val messages =
                runCatching {
                    extras.getParcelableArray(EXTRA_MESSAGES)
                        ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                }.getOrNull().orEmpty()
            var resolvedTitle =
                extras.getCharSequence(EXTRA_CONVERSATION_TITLE)
                    ?: extras.getCharSequence(EXTRA_TITLE)
            var resolvedBody: CharSequence? = null
            val last = messages.lastOrNull()
            if (last != null) {
                resolvedBody = last.text
                if (TextUtils.isEmpty(resolvedTitle)) {
                    resolvedTitle = last.senderPerson?.name
                }
            }
            if (TextUtils.isEmpty(resolvedBody)) {
                resolvedBody = extras.getCharSequence(EXTRA_BIG_TEXT)
            }
            if (TextUtils.isEmpty(resolvedBody)) {
                resolvedBody = extras.getCharSequence(EXTRA_TEXT)
            }
            if (TextUtils.isEmpty(resolvedBody)) {
                resolvedBody = extras.getCharSequence(EXTRA_SUB_TEXT)
            }
            title = resolvedTitle?.toString().orEmpty()
            body = resolvedBody?.toString().orEmpty()
        }
        return BubblePayload(icon, appIcon, label, title, body)
    }

    /** 同一张 Drawable 被多个气泡共享时，动画会互相改 bounds；取一份可变的副本。 */
    fun clone(drawable: Drawable?): Drawable? {
        if (drawable == null) return null
        val state = drawable.constantState ?: return drawable
        return runCatching {
            state.newDrawable(context.resources).mutate()
        }.getOrElse { drawable }
    }

    private fun activityOf(packageName: String, user: UserHandle) =
        runCatching {
            launcherApps?.getActivityList(packageName, user)?.firstOrNull()
        }.getOrNull()

    /**
     * 分身上下文：`createContextAsUser` 是隐藏 API，但 SystemUI（uid 1000）属于平台调用方，
     * 反射可用；失败就退回 SystemUI 自己的上下文——图标可能落回主应用，但不影响通知本身。
     */
    private fun userContext(user: UserHandle): Context {
        if (user == Process.myUserHandle()) return context
        return runCatching {
            val method =
                Context::class.java.getMethod(
                    "createContextAsUser",
                    UserHandle::class.java,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(context, user, 0) as? Context
        }.getOrNull() ?: context
    }

    private fun personIcon(notification: Notification, packageContext: Context): Drawable? {
        val extras = notification.extras ?: return null
        val messages =
            runCatching {
                extras.getParcelableArray(EXTRA_MESSAGES)
                    ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            }.getOrNull().orEmpty()
        for (index in messages.indices.reversed()) {
            val icon = messages[index].senderPerson?.icon ?: continue
            val drawable = runCatching { icon.loadDrawable(packageContext) }.getOrNull()
            if (drawable != null) return drawable
        }
        val people =
            runCatching {
                extras.getParcelableArrayList(EXTRA_PEOPLE_LIST, Person::class.java)
            }.getOrNull().orEmpty()
        for (person in people) {
            val icon = person?.icon ?: continue
            val drawable = runCatching { icon.loadDrawable(packageContext) }.getOrNull()
            if (drawable != null) return drawable
        }
        return null
    }

    private fun shortcutIcon(sbn: StatusBarNotification): Drawable? {
        val shortcutId = sbn.notification.shortcutId
        if (TextUtils.isEmpty(shortcutId)) return null
        return runCatching {
            val apps = launcherApps ?: return null
            val query =
                LauncherApps.ShortcutQuery()
                    .setPackage(sbn.packageName)
                    .setShortcutIds(listOf(shortcutId))
                    .setQueryFlags(SHORTCUT_QUERY_FLAGS)
            val shortcuts: List<ShortcutInfo>? = apps.getShortcuts(query, sbn.user)
            val shortcut = shortcuts?.firstOrNull() ?: return null
            apps.getShortcutIconDrawable(shortcut, density)
        }.getOrNull()
    }

    private companion object {
        const val SHORTCUT_QUERY_FLAGS = 19
        const val EXTRA_MESSAGES = "android.messages"
        const val EXTRA_CONVERSATION_TITLE = "android.conversationTitle"
        const val EXTRA_TITLE = "android.title"
        const val EXTRA_BIG_TEXT = "android.bigText"
        const val EXTRA_TEXT = "android.text"
        const val EXTRA_SUB_TEXT = "android.subText"
        const val EXTRA_PEOPLE_LIST = "android.people.list"
    }
}
