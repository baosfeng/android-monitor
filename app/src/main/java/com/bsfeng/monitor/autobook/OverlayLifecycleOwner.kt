package com.bsfeng.monitor.autobook

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

// 从 autobook/AutoBookOverlay.kt 拆出：无障碍浮窗的宿主生命周期。
// Compose 在 Activity 之外渲染时必须的 Lifecycle / ViewModelStore / SavedState 最小实现。

/**
 * 浮窗的宿主生命周期。
 *
 * Compose 在 Activity 之外渲染（WindowManager 直挂 View）时不会有人替它推进
 * Lifecycle / SavedState，必须自己提供一个最小实现，否则 ComposeView 会直接崩在
 * 「ViewTreeLifecycleOwner not found」。
 */
internal class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun attach() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
