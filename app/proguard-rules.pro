# ProGuard rules for android-monitor

# ========== Android 基本 ==========
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepattributes SourceFile, LineNumberTable

# ========== 事件数据模型（JSON 序列化/反序列化） ==========
-keep class com.bsfeng.monitor.event.UnifiedEvent { *; }
-keep class com.bsfeng.monitor.event.EventType { *; }
-keep class com.bsfeng.monitor.event.ScreenContent { *; }
-keep class com.bsfeng.monitor.data.EventEntity { *; }

# ========== 网络请求/响应 DTO（JSON 序列化） ==========
-keep class com.bsfeng.monitor.network.EventsBatch { *; }
-keep class com.bsfeng.monitor.network.DeviceRegisterRequest { *; }
-keep class com.bsfeng.monitor.network.UploadResponse { *; }
-keep class com.bsfeng.monitor.network.UploadData { *; }
-keep class com.bsfeng.monitor.network.DeviceRegisterResponse { *; }
-keep class com.bsfeng.monitor.network.DeviceRegisterData { *; }
-keep class com.bsfeng.monitor.network.ServerConfig { *; }
-keep class com.bsfeng.monitor.network.HeartbeatResponse { *; }
-keep class com.bsfeng.monitor.network.HeartbeatData { *; }

# ========== 诊断计数器（@Volatile 字段跨线程） ==========
-keep class com.bsfeng.monitor.util.DiagnosticCounters { *; }

# ========== 应用配置（BuildConfig 字段） ==========
-keep class com.bsfeng.monitor.AppConfig { *; }
-keep class com.bsfeng.monitor.BuildConfig { *; }

# ========== Kotlin 协程（防止挂起函数被裁剪） ==========
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ========== WorkManager + Room（Glance 的小组件更新调度依赖） ==========
# WorkDatabase_Impl 由 Room 通过反射按类名创建，R8 看不到这条引用；
# 缺了 keep，App 会在 Application 启动阶段（InitializationProvider）直接崩：
#   Failed to create an instance of androidx.work.impl.WorkDatabase
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.work.**

# WorkManager 还会按类名反射创建 InputMerger 与 Worker（如 OverwritingInputMerger），
# R8 看不到引用 → 缺规则时输入合并器实例化失败，Glance 的更新永远停在加载圈。
-keep class androidx.work.*InputMerger { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * implements androidx.work.WorkerFactory { *; }

# Glance 的组件更新走内部 session（动态创建 RemoteViews 与状态读写）
-keep class androidx.glance.appwidget.** { *; }
-dontwarn androidx.glance.**
