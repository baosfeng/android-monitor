package com.bsfeng.monitor.pipeline

class NavigationContextTracker(
    private val maxSize: Int = 20
) {
    private val stack = ArrayDeque<ContextFrame>(maxSize)

    @Synchronized
    fun onScreenChanged(frame: ContextFrame) {
        if (stack.size >= maxSize) stack.removeFirst()
        stack.addLast(frame)
    }

    @Synchronized
    fun getCurrentContext(): List<ContextFrame> = stack.toList()

    @Synchronized
    fun navigateBack(toScreenLabel: String) {
        while (stack.isNotEmpty() && stack.last().screenLabel != toScreenLabel) {
            stack.removeLast()
        }
    }

    @Synchronized
    fun clear() {
        stack.clear()
    }
}
