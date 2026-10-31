package com.bsfeng.monitor.ui.maps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 侧装配回归（Robolectric，真 PackageManager 影子实现）。
 *
 * 覆盖「装了什么 → 算出什么候选 → Intent 长什么样 → 打开失败怎么降级」这条链。
 * 纯逻辑部分已在 [MapEntryPlanTest] 钉住，这里只验 Android 接线：
 * - 已装判定走 getPackageInfo，能解析判定走 resolveActivity，两者缺一不可；
 * - Intent 对地图 App 显式钉包名（否则会被别的 App 抢答），浏览器不钉；
 * - startActivity 抛异常时返回 false 而不是让调用栈崩掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLauncherTest {

    private lateinit var context: Context
    private val point = GeoPoint(lat = 39.9063, lng = 116.3912)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    /** 让 Robolectric 影子认为某个包已安装。 */
    private fun install(packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                versionName = "1.0"
                versionCode = 1
            },
        )
    }

    /** 登记一条可解析的 resolveInfo（按 Intent 精确匹配 + 包名）。 */
    private fun resolvable(packageName: String, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            intent,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = packageName + ".MapActivity"
                }
            },
        )
    }

    // ── 1 · 正常路径 ──

    @Test
    fun `装了高德并注册了 scheme 时能算出高德候选`() {
        install(MapTarget.AMAP.packageName)
        val realUri = MapUri.uriFor(MapTarget.AMAP, point, "天安门", context.packageName)!!
        resolvable(MapTarget.AMAP.packageName, realUri)

        val installed = MapLauncher.installedMapPackages(context, point, "天安门")
        assertTrue("应识别出高德已装：" + installed, installed.contains(MapTarget.AMAP.packageName))
    }

    @Test
    fun `地图 Intent 显式钉包名，浏览器 Intent 不钉`() {
        val amap = MapLauncher.intentFor(MapTarget.AMAP, "androidamap://viewMap?lat=1&lon=2")
        assertEquals(MapTarget.AMAP.packageName, amap.`package`)
        assertEquals(Intent.ACTION_VIEW, amap.action)
        assertTrue("需 NEW_TASK 才能从非 Activity 上下文拉起", amap.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val browser = MapLauncher.intentFor(MapTarget.BROWSER, "https://www.openstreetmap.org/")
        // 浏览器不钉包名：用户可能装了多个，交给系统选
        assertNull(browser.`package`)
    }

    @Test
    fun `四家地图都装时 planFor 给出四个地图候选且不含浏览器`() {
        MapTarget.MAP_APPS.forEach { target ->
            install(target.packageName)
            resolvable(target.packageName, MapUri.uriFor(target, point, "天安门", context.packageName)!!)
        }
        val plan = MapLauncher.planFor(context, point, "天安门")
        assertEquals(MapTarget.MAP_APPS.size, plan.candidates.count { it.target.isMapApp })
        // 浏览器是兜底而非并列选项：有地图时不得混进候选，否则每次都要多弹一次选择器
        assertFalse("有地图时不该出现浏览器候选", plan.candidates.any { it.target == MapTarget.BROWSER })
        assertFalse(plan.usingBrowserFallback)
        assertEquals("候选顺序应跟随偏好序", MapTarget.MAP_APPS, plan.candidates.map { it.target })
    }

    // ── 2 · 边界路径 ──

    @Test
    fun `包已装但没注册 scheme 时不算可用`() {
        install(MapTarget.AMAP.packageName)
        // 故意不登记 resolveInfo：装了 App 但没注册 androidamap:// 的情况真实存在
        val installed = MapLauncher.installedMapPackages(context, point, "天安门")
        assertFalse("解析不到就不该算可用：" + installed, installed.contains(MapTarget.AMAP.packageName))
    }

    @Test
    fun `一个地图都没装时退化成只有浏览器`() {
        val plan = MapLauncher.planFor(context, point, "天安门")
        assertTrue(
            "没装地图时不该冒出地图候选：" + plan.candidates,
            plan.candidates.none { it.target.isMapApp },
        )
    }

    // ── 3 · 异常路径 ──

    @Test
    fun `打开成功时把 Intent 交给了系统，且带着正确的包名与 URI`() {
        val candidate = MapCandidate(MapTarget.AMAP, "androidamap://viewMap?lat=39.9&lon=116.3")
        assertTrue("打开应返回 true", MapLauncher.open(context, candidate))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("没有派发 Intent", started)
        assertEquals(MapTarget.AMAP.packageName, started.`package`)
        assertEquals("androidamap://viewMap?lat=39.9&lon=116.3", started.data.toString())
    }

    @Test
    fun `startActivity 抛异常时返回 false 而不是让调用栈崩掉`() {
        // 影子环境的 startActivity 不会抛 ActivityNotFoundException，所以这里直接注入一个
        // 一定抛异常的 Context：这是「地图 App 被冻结 / 被卸载 / 不认该 scheme」在真机上的表现。
        // 断言的是 MapLauncher.open 把 Throwable 吞成 false，而不是把异常抛给组合函数。
        val throwing = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw android.content.ActivityNotFoundException("no activity for " + intent)
            }
        }
        val candidate = MapCandidate(MapTarget.AMAP, "androidamap://viewMap?lat=39.9&lon=116.3")
        assertFalse("异常必须被吞成 false", MapLauncher.open(throwing, candidate))
    }

    @Test
    fun `包查询抛异常时按未安装处理`() {
        // 未安装的包 getPackageInfo 抛 NameNotFoundException，必须被吞成「未安装」
        assertEquals(emptySet<String>(), MapLauncher.installedMapPackages(context, point, "天安门"))
    }

    @Test
    fun `非法坐标不会产出任何已装地图`() {
        MapTarget.MAP_APPS.forEach { install(it.packageName) }
        val installed = MapLauncher.installedMapPackages(context, GeoPoint(Double.NaN, 0.0), null)
        assertTrue("非法坐标不该产出候选：" + installed, installed.isEmpty())
    }

    @Test
    fun `intentFor 保留 data`() {
        val intent = MapLauncher.intentFor(MapTarget.GOOGLE, "geo:39.9,116.3")
        assertNotNull("Intent 丢了 data", intent.data)
        assertEquals("geo:39.9,116.3", intent.data.toString())
    }
}
