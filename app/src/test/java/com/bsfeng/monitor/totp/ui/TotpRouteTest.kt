package com.bsfeng.monitor.totp.ui

// TotpRouteSaver 的编解码用例：六个落点往返、非法标签回落、Detail 主键丢失回落。

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class TotpRouteTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun roundTrip(route: TotpRoute): TotpRoute {
        val saved = with(TotpRouteSaver) { scope.save(route) }
            ?: error("save 返回 null: $route")
        return TotpRouteSaver.restore(saved) ?: error("restore 返回 null: $route")
    }

    @Test
    fun saver_roundTripsAllObjectRoutes() {
        val routes = listOf(
            TotpRoute.List,
            TotpRoute.Add,
            TotpRoute.Scan,
            TotpRoute.Manual,
            TotpRoute.Backup,
        )
        routes.forEach { route -> assertEquals(route, roundTrip(route)) }
    }

    @Test
    fun saver_roundTripsDetailWithId() {
        assertEquals(TotpRoute.Detail(42L), roundTrip(TotpRoute.Detail(42L)))
    }

    @Test
    fun restore_unknownKind_fallsBackToList() {
        assertEquals(TotpRoute.List, TotpRouteSaver.restore(listOf("unknown")))
    }

    @Test
    fun restore_detailWithoutId_fallsBackToList() {
        assertEquals(TotpRoute.List, TotpRouteSaver.restore(listOf("detail")))
    }
}
