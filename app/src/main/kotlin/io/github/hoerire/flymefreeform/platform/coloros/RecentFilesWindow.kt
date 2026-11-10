package io.github.hoerire.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 「最近文件和文档」独立窗口。
 *
 * 原先是在面板卡片上叠一层，观感是在面板上盖遮罩；这里改成独立窗口：
 * 面板先收起，再由这个窗口承载列表，返回键或点空白处关闭。
 */
internal object RecentFilesWindow {
    private var root: View? = null
    private var manager: WindowManager? = null

    fun show(context: Context) {
        dismiss()
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        val backdrop = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x66000000)
            isFocusableInTouchMode = true
        }
        val page = RecentFilesPage(context).build { dismiss() }
        val margin = (16 * density).toInt()
        backdrop.addView(
            page,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                leftMargin = margin
                rightMargin = margin
            },
        )
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformRecentFiles"
        }
        runCatching { wm.addView(backdrop, params) }.onSuccess {
            root = backdrop
            manager = wm
            backdrop.requestFocus()
        }
    }

    fun dismiss() {
        val view = root ?: return
        val wm = manager
        root = null
        manager = null
        runCatching { wm?.removeViewImmediate(view) }
    }
}