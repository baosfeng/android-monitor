package com.bsfeng.monitor.ui.quota

/**
 * 账号卡的锚点映射（原型 s18-body-/row-/detail-<key>，chart 只有第一个账号有）。
 *
 * 单独成文件：锚点对账脚本从 testTag 调用点沿「函数体里的字面量」取证，
 * 锚点表与业务推导分开后，改徽标 / 列选择不会碰锚点。
 */
/** 账本里的账号键（锚点 s18-body-/row-/detail-<key> 的 key）；表外的类型 / 序号没有锚点。 */
internal fun quotaAccountKey(providerTypeId: String, seq: Int): String? = when {
    providerTypeId == "opencode-go" && seq == 1 -> "oc-main"
    providerTypeId == "opencode-go" && seq == 2 -> "oc-alt"
    providerTypeId == OLLAMA_ID && seq == 1 -> "ol-main"
    providerTypeId == "opendesign" && seq == 1 -> "od-a"
    providerTypeId == "opendesign" && seq == 2 -> "od-b"
    providerTypeId == "deepseek" && seq == 1 -> "ds-1"
    providerTypeId == "deepseek" && seq == 2 -> "ds-2"
    else -> null
}

/** 账号容器锚点（原型 s18-body-<key>）：一行 = 一个账号（含展开区）。 */
internal fun quotaAccountBodyTag(key: String): String = when (key) {
    "oc-main" -> "s18-body-oc-main"
    "oc-alt" -> "s18-body-oc-alt"
    "ol-main" -> "s18-body-ol-main"
    "od-a" -> "s18-body-od-a"
    "od-b" -> "s18-body-od-b"
    "ds-1" -> "s18-body-ds-1"
    else -> "s18-body-ds-2"
}

/** 行按钮锚点（原型 s18-row-<key>）。 */
internal fun quotaAccountRowTag(key: String): String = when (key) {
    "oc-main" -> "s18-row-oc-main"
    "oc-alt" -> "s18-row-oc-alt"
    "ol-main" -> "s18-row-ol-main"
    "od-a" -> "s18-row-od-a"
    "od-b" -> "s18-row-od-b"
    "ds-1" -> "s18-row-ds-1"
    else -> "s18-row-ds-2"
}

/** 展开区锚点（原型 s18-detail-<key>）。 */
internal fun quotaAccountDetailTag(key: String): String = when (key) {
    "oc-main" -> "s18-detail-oc-main"
    "oc-alt" -> "s18-detail-oc-alt"
    "ol-main" -> "s18-detail-ol-main"
    "od-a" -> "s18-detail-od-a"
    "od-b" -> "s18-detail-od-b"
    "ds-1" -> "s18-detail-ds-1"
    else -> "s18-detail-ds-2"
}

/** 柱图锚点：原型只给第一个账号画了 data-od-id（s18-chart-oc-main），其余不造锚点。 */
internal fun quotaAccountChartTag(key: String): String? =
    if (key == "oc-main") "s18-chart-oc-main" else null
