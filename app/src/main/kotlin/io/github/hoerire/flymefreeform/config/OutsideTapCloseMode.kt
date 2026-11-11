package io.github.hoerire.flymefreeform.config

/** 窗外短点击关闭协议；存储值属于已发布配置格式，不得改作其他含义。 */
internal enum class OutsideTapCloseMode(val storedValue: Int) {
    Disabled(0),
    SingleTap(1),
    DoubleTap(2),

    /**
     * 底部关闭：只认"点小窗底部横条"，窗外点击不关闭。
     *
     * 与单击/双击是并列的三选一，不是额外开关——三者都要处理"这一下点在哪里"，
     * 同时开启会互相抢判定，所以只能选其一。
     */
    BottomTap(3),
    ;

    companion object {
        fun fromStoredValue(value: Int): OutsideTapCloseMode =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
