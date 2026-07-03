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
