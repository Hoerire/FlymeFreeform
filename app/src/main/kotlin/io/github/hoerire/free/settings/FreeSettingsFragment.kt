package io.github.hoerire.free.settings

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.hoerire.free.config.ModulePreferences

/**
 * 模块的设置页，由系统设置进程渲染。
 *
 * 页面在设置进程内构建，用的是设置自己的 `PreferenceFragmentCompat` 与主题，
 * 因此工具栏、条目样式、开关控件都与系统设置一致，不依赖模块自带的界面库。
 * 偏好以代码构建：设置 APK 里没有模块的资源表，无法通过 XML 资源展开。
 */
internal class FreeSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val general = PreferenceCategory(context).apply { title = "通用" }
        screen.addPreference(general)
        general.addPreference(
            toggle(
                context,
                title = "启用模块",
                key = ModulePreferences.KEY_MODULE_ENABLED,
                fallback = true,
            ),
        )
        general.addPreference(
            toggle(
                context,
                title = "详细日志",
                key = ModulePreferences.KEY_VERBOSE_LOG,
                fallback = false,
            ),
        )

        val about = Preference(context).apply {
            title = "版本"
            summary = VERSION
            isPersistent = false
        }
        screen.addPreference(about)
        setPreferenceScreen(screen)
    }

    private fun toggle(
        context: Context,
        title: String,
        key: String,
        fallback: Boolean,
    ): SwitchPreferenceCompat =
        SwitchPreferenceCompat(context).apply {
            this.title = title
            this.key = key
            isPersistent = false
            isChecked = ModuleRuntime.readBoolean(key, fallback)
            setOnPreferenceChangeListener { _, value ->
                val checked = value as? Boolean ?: return@setOnPreferenceChangeListener false
                val ok = ModuleRuntime.writeBoolean(key, checked)
                ModuleRuntime.log("SETTINGS_PAGE_WRITE_" + key + "_" + checked + "_" + ok)
                ok
            }
        }

    private companion object {
        const val VERSION = "3.0"
    }
}
