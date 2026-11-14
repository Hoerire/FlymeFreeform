package io.github.hoerire.flymefreeform.config

/** 窗外短点击关闭协议；存储值属于已发布配置格式，不得改作其他含义。 */
internal enum class OutsideTapCloseMode(val storedValue: Int) {
    Disabled(0),
    SingleTap(1),
    DoubleTap(2),

    /**
     * 底部关闭：只认"点小窗底部横条"。
     *
     * 与单击/双击三选一。这一档**不做防穿透**：触摸区保持窗口原样，
     * 窗外点击照常落到下层应用，模块只处理横条上的那一下。
     */
    BottomTap(3),
    ;

    companion object {
        fun fromStoredValue(value: Int): OutsideTapCloseMode =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
