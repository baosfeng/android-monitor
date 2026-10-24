package com.bsfeng.monitor.skip

// 来源：原 skip/BuiltInRules.kt 的常量部分；职责：内置规则共用的选择器文本片段（跳过文本/描述/View ID/关闭广告/小尺寸）。

/** 两次动作最小间隔（毫秒） */
internal const val BUILT_IN_ACTION_CD_MS = 1_000L

/** 跳过按钮文本特征：可见 + 短文本 + 含"跳过/skip" */
internal const val TEXT_SKIP =
    "[childCount=0][visibleToUser=true][text.length<10][text*=\"跳过\"||text*=\"跳過\"||text*=\"skip\"||text*=\"Skip\"]"

/** 跳过按钮描述特征 */
internal const val DESC_SKIP =
    "[childCount=0][visibleToUser=true][desc.length<10][desc*=\"跳过\"||desc*=\"skip\"||desc*=\"Skip\"]"

/** 跳过按钮 View ID 特征（字节系穿山甲 SDK、倒计时按钮） */
internal const val VIEW_ID_SKIP =
    "[visibleToUser=true][id$=\"tt_splash_skip_btn\"||vid*=\"skip\"||vid*=\"Skip\"||(vid*=\"count\"&&vid*=\"down\"&&vid!*=\"download\")]"

/** 关闭广告文本特征（不含裸"关闭"，避免误触普通弹窗） */
internal const val CLOSE_AD_TEXT =
    "[childCount=0][visibleToUser=true][text.length<10][text*=\"关闭广告\"||desc*=\"关闭广告\"]"

/** 小尺寸跳过按钮（限制宽高，进一步降低误触） */
internal const val TEXT_SKIP_SMALL =
    "[visibleToUser=true][text*=\"跳过\"][text.length<10][width<500&&height<300]"
