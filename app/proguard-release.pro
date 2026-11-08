# 只作用于 release / perf 的 ProGuard 规则（debug 不引这份）。
#
# 加规则前先确认：debug 构建依赖 logcat 回执（scripts/debug-cli.sh 全靠 tag=DebugCmd 的行），
# 任何会改变日志行为 / 剥离日志的规则都不能进 proguard-rules.pro。

# ========== 诊断日志：发布包里整句剥掉 ==========
# 为什么可以剥：诊断日志本来是给开发期看的，发布包的 logcat 只剩两类读者 ——
#   1. 同机恶意 App（读得到就等于白送内容）；
#   2. 用户自己报障（README 走「设置 → 关于」的版本与自检信息，不要求抓 logcat）。
# 而 logcat 恰恰最容易带出屏幕原文、账目、定位坐标（例如 autobook 的账单页行、
# DebugConfig 的调试闸门输出）。剥成空调用后，连字符串常量都进不了 dex，反编译也翻不到。
#
# 口径：剥 v / d / i / w，**保留 e**。理由：崩溃与致命失败是现场排查的唯一凭据，
# 且 Log.e 的文案已按「不含用户数据」审查过（只写异常类名与失败环节）。
# 若后续要连 e 一起剥，必须先把全部 Log.e 文案再审一遍。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
