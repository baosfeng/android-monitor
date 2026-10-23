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

    /** App 内页面切换（指纹档：detail 只存包名/应用名/指纹，不含任何页面文本）。 */
    data object PAGE : RecordType

    companion object {
        fun fromString(name: String): RecordType? = when (name) {
            "SCREEN" -> SCREEN; "CHARGING" -> CHARGING; "APP_USAGE" -> APP_USAGE
            "WIFI" -> WIFI; "AUDIO" -> AUDIO; "BATTERY" -> BATTERY
            "PACKAGE" -> PACKAGE; "POWER_SAVE" -> POWER_SAVE; "AIRPLANE" -> AIRPLANE
            "PHONE_STATE" -> PHONE_STATE; "LOCATION" -> LOCATION
            "PAGE" -> PAGE
            else -> null
        }
        fun toString(type: RecordType): String = type::class.simpleName ?: ""
    }
}
