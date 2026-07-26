package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.AirplaneHandler
import com.bsfeng.monitor.domain.model.AppUsageHandler
import com.bsfeng.monitor.domain.model.AudioHandler
import com.bsfeng.monitor.domain.model.BatteryHandler
import com.bsfeng.monitor.domain.model.ChargingHandler
import com.bsfeng.monitor.domain.model.LocationHandler
import com.bsfeng.monitor.domain.model.PackageHandler
import com.bsfeng.monitor.domain.model.PhoneStateHandler
import com.bsfeng.monitor.domain.model.PowerSaveHandler
import com.bsfeng.monitor.domain.model.RecordHandler
import com.bsfeng.monitor.domain.model.ScreenHandler
import com.bsfeng.monitor.domain.model.WifiHandler
import com.bsfeng.monitor.domain.model.RecordType

class RecordHandlerRegistry {
    private val handlers = mutableMapOf<RecordType, RecordHandler>()

    fun register(handler: RecordHandler) {
        handlers[handler.type] = handler
    }

    fun get(type: RecordType): RecordHandler? = handlers[type]

    fun validate(type: RecordType, action: String, detail: Map<String, String>?): Boolean =
        handlers[type]?.validate(action, detail) ?: false

    fun createLabel(type: RecordType, action: String, detail: Map<String, String>?): String =
        handlers[type]?.createLabel(action, detail) ?: action

    companion object {
        fun createDefault(): RecordHandlerRegistry = RecordHandlerRegistry().apply {
            register(ScreenHandler())
            register(ChargingHandler())
            register(AppUsageHandler())
            register(WifiHandler())
            register(AudioHandler())
            register(BatteryHandler())
            register(PackageHandler())
            register(PowerSaveHandler())
            register(AirplaneHandler())
            register(PhoneStateHandler())
            register(LocationHandler())
        }
    }
}
