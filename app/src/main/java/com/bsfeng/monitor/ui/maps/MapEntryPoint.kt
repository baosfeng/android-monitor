package com.bsfeng.monitor.ui.maps

// 「在地图中查看」该打开哪个点 —— 纯函数，便于 JVM 单测（MapEntryPointTest）。
//
// ## 为什么单独抽出来
// TripScreen 已经是一屏一函数的大文件（既有超限），把「挑点 + 挑名字」塞进组合函数体内
// 会让它更长、也让这段判定没法单测。这里的输入只有采样列表与停留列表两个纯数据，
// 因此可以完整跑在 JVM 上。
//
// ## 挑点口径（优先级从高到低）
// 1. 当天**最后一个**有效轨迹采样 —— 语义是「我现在在哪」，也是用户点「在地图中查看」时最想看的；
// 2. 第一个**带坐标**的停留点 —— 历史日期里采样可能已被清理，但停留点坐标还在；
// 3. 都没有 → null，入口整块不渲染（没有坐标就没有「在地图中查看」这回事）。
//
// 非法采样（NaN / 越界）直接跳过，不做插值也不做纠正 —— 一个脏点不该把入口干掉。

import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.trip.TraceSample

object MapEntryPoint {

    /** 选出要在地图里打开的点；没有任何可用坐标时返回 null。 */
    fun pick(samples: List<TraceSample>, stays: List<Stay>): GeoPoint? {
        val last = samples.lastOrNull { it.geo().isValid }
        if (last != null) return last.geo()
        return stays.firstNotNullOfOrNull { stay -> stay.geo() }
    }

    /** 入口副标题用的地点名：优先带名字的采样，其次带坐标的停留点名字；都没有返回 null。 */
    fun label(samples: List<TraceSample>, stays: List<Stay>): String? {
        val named = samples.lastOrNull { !it.label.isNullOrBlank() }?.label
        if (named != null) return named.trim()
        return stays.firstOrNull { it.geo() != null }?.place?.label?.takeIf { it.isNotBlank() }
    }

    private fun TraceSample.geo(): GeoPoint = GeoPoint(lat, lng)

    private fun Stay.geo(): GeoPoint? {
        val lat = place.lat ?: return null
        val lng = place.lng ?: return null
        val point = GeoPoint(lat, lng)
        return point.takeIf { it.isValid }
    }
}
