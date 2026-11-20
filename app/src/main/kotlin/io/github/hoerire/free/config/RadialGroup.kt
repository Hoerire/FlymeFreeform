package io.github.hoerire.free.config

/**
 * 扇形应用的分组。
 *
 * 两组数据完全独立，同一个条目只能出现在其中一组。默认组始终生效；第二组由
 * [RadialGroupSide] 决定是否启用、以及只替换哪一侧底角，未启用时的行为与单组一致。
 */
internal enum class RadialGroup(val storedValue: Int) {
    /** 默认组：固定的基础项列表，始终存在。 */
    Primary(0),

    /** 第二组：默认不启用，启用后只替换一侧底角。 */
    Alternate(1),
    ;

    /** 另一组，用于「同一项不能同时在两组」的判定。 */
    val opposite: RadialGroup
        get() = if (this == Primary) Alternate else Primary

    companion object {
        fun fromStoredValue(value: Int): RadialGroup =
            entries.firstOrNull { it.storedValue == value } ?: Primary
    }
}

/** 第二组的生效位置；[Disabled] 表示第二组暂不参与，两侧都显示默认组。 */
internal enum class RadialGroupSide(val storedValue: Int) {
    Disabled(0),
    Left(1),
    Right(2),
    ;

    companion object {
        fun fromStoredValue(value: Int): RadialGroupSide =
            entries.firstOrNull { it.storedValue == value } ?: Disabled
    }
}
