package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：Activity 类名 → 中文页面描述。
// 纯映射（无状态、无 Android 依赖），按「微信各界面 → 通用 UI 模式 → 兜底」顺序匹配。


internal fun describeActivity(shortClass: String, packageName: String): String {
    return when {
        // 微信聊天
        shortClass.contains("ChattingUI", ignoreCase = true) -> "聊天界面"
        shortClass.contains("Conversation", ignoreCase = true) ||
            shortClass.contains("LauncherUI", ignoreCase = true) -> "聊天列表"
        // 微信发现页
        shortClass.contains("SnsTimeLineUI", ignoreCase = true) ||
            shortClass.contains("ImproveSnsTimeline", ignoreCase = true) -> "朋友圈"
        shortClass.contains("SnsBrowseUI", ignoreCase = true) ||
            shortClass.contains("SnsComment", ignoreCase = true) -> "朋友圈详情"
        // 微信公众号
        shortClass.contains("BizTimeLineUI", ignoreCase = true) ||
            shortClass.contains("BizChattingUI", ignoreCase = true) -> "公众号列表"
        shortClass.contains("BizFlutterTLFlutterView", ignoreCase = true) -> "公众号主页"
        shortClass.contains("TmplWebViewMMUI", ignoreCase = true) -> "公众号文章"
        // 微信小程序
        shortClass.contains("AppBrandUI", ignoreCase = true) ||
            shortClass.contains("MiniProgram", ignoreCase = true) -> "小程序"
        // 微信支付
        shortClass.contains("Wallet", ignoreCase = true) ||
            shortClass.contains("PayUI", ignoreCase = true) -> "支付页面"
        // 微信视频号
        shortClass.contains("Finder", ignoreCase = true) ||
            shortClass.contains("VideoCollection", ignoreCase = true) -> "视频号"
        // 通讯录
        shortClass.contains("AddressUI", ignoreCase = true) ||
            shortClass.contains("Contact", ignoreCase = true) -> "通讯录"
        // 搜索
        shortClass.contains("Search", ignoreCase = true) -> "搜索页面"
        // 设置
        shortClass.contains("Setting", ignoreCase = true) -> "设置页面"
        // 相册
        shortClass.contains("Album", ignoreCase = true) ||
            shortClass.contains("Gallery", ignoreCase = true) -> "相册"
        // 浏览器/WebView
        shortClass.contains("Browser", ignoreCase = true) ||
            shortClass.contains("WebView", ignoreCase = true) -> {
            if (packageName == "com.tencent.mm") "公众号文章"
            else "网页浏览"
        }
        // 通用 UI 模式
        shortClass.contains("Progress", ignoreCase = true) ||
            shortClass.contains("Loading", ignoreCase = true) -> "加载中"
        shortClass.contains("Toast", ignoreCase = true) ||
            shortClass.contains("Snackbar", ignoreCase = true) -> "提示"
        shortClass.contains("Permission", ignoreCase = true) ||
            shortClass.contains("Rationale", ignoreCase = true) -> "权限请求"
        shortClass.contains("Share", ignoreCase = true) ||
            shortClass.contains("Send", ignoreCase = true) ||
            shortClass.contains("Forward", ignoreCase = true) -> "分享页面"
        shortClass.contains("Image", ignoreCase = true) ||
            shortClass.contains("Photo", ignoreCase = true) ||
            shortClass.contains("Picture", ignoreCase = true) -> "图片"
        shortClass.contains("Scan", ignoreCase = true) ||
            shortClass.contains("QR", ignoreCase = true) ->
                if (shortClass.contains("Result", ignoreCase = true)) "扫码结果" else "扫码"
        shortClass.contains("Location", ignoreCase = true) ||
            shortClass.contains("Map", ignoreCase = true) -> "位置"
        shortClass.contains("Download", ignoreCase = true) ||
            shortClass.contains("Update", ignoreCase = true) -> "下载"
        shortClass.contains("Notice", ignoreCase = true) ||
            shortClass.contains("Notification", ignoreCase = true) -> "通知"
        shortClass.contains("Input", ignoreCase = true) ||
            shortClass.contains("Keyboard", ignoreCase = true) -> "输入"
        shortClass.contains("Picker", ignoreCase = true) ||
            shortClass.contains("Selector", ignoreCase = true) -> "选择器"
        // 电商
        shortClass.contains("Order", ignoreCase = true) ||
            shortClass.contains("Confirm", ignoreCase = true) -> "订单页面"
        shortClass.contains("Detail", ignoreCase = true) &&
            packageName.contains("taobao", ignoreCase = true) -> "商品详情"
        // 兜底
        packageName == "com.tencent.mm" -> "微信"
        else -> shortClass
    }
}
