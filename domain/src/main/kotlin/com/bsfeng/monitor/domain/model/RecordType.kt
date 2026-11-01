package com.bsfeng.monitor.domain.model

sealed interface RecordType {
    data object SCREEN : RecordType
    data object CHARGING : RecordType
    data object APP_USAGE : RecordType
    data object WIFI : RecordType
    data object AUDIO : RecordType
    data object BATTERY : RecordType
    data object PACKAGE : RecordType
    data object POWER_SAVE : RecordType
    data object AIRPLANE : RecordType
    data object PHONE_STATE : RecordType
    data object LOCATION : RecordType

    companion object {

        /**
         * 落库口径：`records.type` 列的全部合法取值（顺序 = UI 展示顺序）。
         *
         * 用**固定字符串字面量**，不依赖 `::class.simpleName`。
         * 历史事故：release 构建被 R8 重命名 data object 的类名，库里写进了 `qg1`/`ig1`/
         * `kg1` 等混淆名；读回时 `fromString` 不认识 → 回落 SCREEN，广告页
         * `type='SCREEN' AND action='AD_SKIPPED'` 查不到新记录（近 7 天 51 条只命中 43 条）。
         * 混淆名到规范名的补救见 [LEGACY_ALIASES]。
         */
        val CANONICAL_NAMES: List<String> = listOf(
            "SCREEN", "CHARGING", "APP_USAGE", "WIFI", "AUDIO", "BATTERY",
            "PACKAGE", "POWER_SAVE", "AIRPLANE", "PHONE_STATE", "LOCATION",
        )

        private val BY_NAME: Map<String, RecordType> = mapOf(
            "SCREEN" to SCREEN, "CHARGING" to CHARGING, "APP_USAGE" to APP_USAGE,
            "WIFI" to WIFI, "AUDIO" to AUDIO, "BATTERY" to BATTERY,
            "PACKAGE" to PACKAGE, "POWER_SAVE" to POWER_SAVE, "AIRPLANE" to AIRPLANE,
            "PHONE_STATE" to PHONE_STATE, "LOCATION" to LOCATION,
        )

        /**
         * 历史混淆名（**原样小写**，与库里的值逐字节匹配）→ 规范类型。
         *
         * 来源：1.0 release 包（R8 开启）落到本机 `records.type` 的实测值，按同批记录的
         * `action`/`detail` 反推得到 —— qg1/bg1=亮屏、ig1/zf1/tf1/z41=前台使用、
         * kg1=电量、lg1/cg1=充电、rg1=网络。R8 的短名**每个构建版本都可能不同**，
         * 因此这张表只是「老数据的抢救名册」，不能当作长期兼容层：真正兜底的是
         * [inferFromAction] + v5 迁移（把库里的值改写成规范名）。
         */
        val LEGACY_ALIASES: Map<String, RecordType> = mapOf(
            "qg1" to SCREEN,
            "bg1" to SCREEN,
            "ig1" to APP_USAGE,
            "zf1" to APP_USAGE,
            "tf1" to APP_USAGE,
            "z41" to APP_USAGE,
            "kg1" to BATTERY,
            "lg1" to CHARGING,
            "cg1" to CHARGING,
            "rg1" to WIFI,
        )

        /**
         * action 的唯一归属反推表（仅收「一个 action 只可能属于一个类型」的取值）。
         *
         * 刻意排除有歧义的 action：`OFF`（SCREEN / POWER_SAVE / AIRPLANE 都在用）、
         * `ON`（POWER_SAVE / AIRPLANE）—— 猜错比不猜更糟。
         */
        private val BY_ACTION: Map<String, RecordType> = mapOf(
            "UNLOCKED" to SCREEN, "AD_SKIPPED" to SCREEN,
            "USAGE" to APP_USAGE, "CLOSED" to APP_USAGE,
            "STARTED" to CHARGING, "STOPPED" to CHARGING,
            "LOW" to BATTERY, "OKAY" to BATTERY,
            "CONNECTED" to WIFI, "DISCONNECTED" to WIFI,
            "PLUGGED" to AUDIO, "UNPLUGGED" to AUDIO,
            "ADDED" to PACKAGE, "REMOVED" to PACKAGE, "REPLACED" to PACKAGE,
            "RINGING" to PHONE_STATE, "OFFHOOK" to PHONE_STATE, "IDLE" to PHONE_STATE,
            "COORDINATE" to LOCATION,
        )

        /** 规范名（写库用）。未知入参不应出现；真出现时返回空串而不是类名。 */
        fun toString(type: RecordType): String = when (type) {
            SCREEN -> "SCREEN"
            CHARGING -> "CHARGING"
            APP_USAGE -> "APP_USAGE"
            WIFI -> "WIFI"
            AUDIO -> "AUDIO"
            BATTERY -> "BATTERY"
            PACKAGE -> "PACKAGE"
            POWER_SAVE -> "POWER_SAVE"
            AIRPLANE -> "AIRPLANE"
            PHONE_STATE -> "PHONE_STATE"
            LOCATION -> "LOCATION"
        }

        /**
         * 名称 → 类型。规范名**不区分大小写**（CLI 传 `--es type page` 也能用），
         * 混淆名按库里原样小写匹配。
         *
         * 返回 null 表示「不认识」—— 调用方按 [fromStored] 兜底，别静默当成 SCREEN。
         */
        fun fromString(name: String): RecordType? {
            val text = name.trim()
            if (text.isEmpty()) return null
            BY_NAME[text.uppercase()]?.let { return it }
            return LEGACY_ALIASES[text] ?: LEGACY_ALIASES[text.lowercase()]
        }

        /**
         * 按 action 反推类型；歧义 / 不认识一律 null。
         *
         * 用途：迁移与读路径的兜底 —— 其他构建版本的混淆名不在 [LEGACY_ALIASES] 里，
         * 但 action 是业务常量、不受 R8 影响。
         */
        fun inferFromAction(action: String): RecordType? =
            BY_ACTION[action.trim().uppercase()]

        /**
         * 读库统一入口：先按名字（规范名 / 混淆名）解析，再按同一条记录的 action 推断。
         *
         * 仍返回 null = 这条记录的 type 列彻底不可识别，由调用方记日志并决定兜底值。
         */
        fun fromStored(raw: String?, action: String?): RecordType? {
            raw?.let { fromString(it) }?.let { return it }
            action?.let { inferFromAction(it) }?.let { return it }
            return null
        }
    }
}
