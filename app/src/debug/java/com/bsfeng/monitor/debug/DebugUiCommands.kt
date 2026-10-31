package com.bsfeng.monitor.debug

/**
 * `ui` 命令族的动作（**纯数据**，解析在 [DebugCommands]，执行在 [DebugUiBridge]）。
 *
 * 为什么单独拆出来：这些动作在 debug 源码集里、release 包里不存在；参数校验与执行分家后，
 * 「选择器怎么算命中」「dir/ms 越界怎么拒」都能纯 JVM 断言，不必上真机。
 */
sealed interface UiAction {

    /** `RESULT cmd=ui.<op>` 用的稳定标识，改它等于改 CLI 契约。 */
    val op: String

    /** 读控件树落盘：根窗口 + `getWindows()` 里的全部交互窗口。`tag` 是文件名标签。 */
    data class Dump(val tag: String?) : UiAction {
        override val op: String = "dump"
    }

    /** 命中节点或其可点祖先上做 ACTION_CLICK，找不到可点祖先回落到手势点击。 */
    data class Click(val selector: UiSelector) : UiAction {
        override val op: String = "click"
    }

    /** 同 [Click]，动作为 ACTION_LONG_CLICK / 长按手势。 */
    data class LongClick(val selector: UiSelector) : UiAction {
        override val op: String = "longclick"
    }

    /** 第一个可滚动节点上 ACTION_SCROLL_FORWARD/BACKWARD，没有则手势滑动。 */
    data class Scroll(val dir: ScrollDir) : UiAction {
        override val op: String = "scroll"
    }

    /** 指定起止点的滑动手势（不经过节点树，给轮播/画板这类没有 scrollable 节点的界面）。 */
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int) : UiAction {
        override val op: String = "swipe"
    }

    /** 系统返回。 */
    data object Back : UiAction {
        override val op: String = "back"
    }

    /** 回桌面。 */
    data object Home : UiAction {
        override val op: String = "home"
    }

    /** 打开最近任务。 */
    data object Recents : UiAction {
        override val op: String = "recents"
    }

    /** 纯延时：点完等界面动画/网络回包的稳定，比 CLI 侧 sleep 更早开始计时。 */
    data class Wait(val ms: Int) : UiAction {
        override val op: String = "wait"
    }

    companion object {
        /** op 取值表（顺序 = 文档顺序，错误信息直接复用）。 */
        fun list(): String = "dump|click|longclick|scroll|swipe|back|home|recents|wait"
    }
}

/**
 * `ui scroll` 的方向：**内容前进方向**，不是手指方向。
 *
 * `down` = 往下看后面的内容（ACTION_SCROLL_FORWARD，对应手指上滑）；
 * `right` = 往右看后面的内容（横向 FORWARD，对应手指左滑）。这样 agent 不用记「手指反着来」。
 */
enum class ScrollDir(val value: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    /** 无障碍滚动动作里的「前进」方向。 */
    val forward: Boolean get() = this == DOWN || this == RIGHT

    companion object {
        fun from(text: String): ScrollDir? = entries.firstOrNull { it.value == text }

        fun list(): String = entries.joinToString("|") { it.value }
    }
}
