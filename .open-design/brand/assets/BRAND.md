# 行为记录 · 品牌规范（C2 时间切片）

## 资产
| 文件 | 用途 |
|---|---|
| brand-mark.svg | 主标识，256 viewBox，>= 32px 一律用这个 |
| brand-mark-24.svg | 24px 专用简化版（柱 3->2、描边加粗、珠加大），<= 24px 才用 |
| brand-lockup.svg | mark + 「行为记录」横向锁定，H40 / gap12 / 26px / 0.12em |
| ic_launcher_foreground.xml | 自适应图标前景层（66dp 官方安全区落图：translate 21,21 / scale 0.2578125） |
| ic_launcher_background.xml | 自适应图标背景层（满幅 108dp） |
| ic_launcher_monochrome.xml | 自适应图标单色层（纯黑，系统着色） |
| ic_launcher.xml | adaptive-icon 入口，放 mipmap-anydpi-v26 |

## 最小尺寸
- 主标识：>= 32px（数字）/ >= 10mm（印刷）；24px 必须换 brand-mark-24.svg。
- 锁定：整体宽 >= 132px；更窄只保留 mark，不加字标。

## 安全区
- 自适应图标（官方规格 developer.android.com/develop/ui/views/launch/icon_design_adaptive）：三层均为 108dp 画布；**安全区是 66x66dp 圆形**（圆心 54,54，永不被 OEM 形状遮罩裁切），logo 建议 >= 48dp 且 <= 66dp。官方没有「72dp 安全区」这个数字。
- 本资产实测：256 viewBox 标识等比缩放 translate(21,21) scale(0.2578125) → 映射矩形 66dp 居中；外接圆直径 60.54dp <= 66dp，圆心 (54,54) 偏差 0，任何遮罩都不切边。foreground / monochrome 两层几何完全一致，仅填充色不同。
- background 层满幅 108dp，不受安全区约束，也不缩到 66dp。
- 标识四周留白 >= 0.25H（H = 标识方框高）；不得紧贴边框或被裁切。

## 禁止
- 禁止拉伸 / 压扁 / 旋转 / 倾斜；只允许等比缩放。
- 禁止加阴影、发光、描边外扩、渐变、贴图、3D 效果。
- 禁止改色：环与柱只用 ink 或 paper；珠子只在 accent / accent-2 之间按底色二选一。
- 禁止把珠子挪离圆环，禁止增删强度柱数量，禁止拆散环与柱单独使用。
- 禁止在标识上叠加文字、角标、水印。

## 浅色底与深色底
- 浅色底（默认、唯一主调）：环与柱 #0E1218，珠 #1D6E9E。
- 深色底（备用资产，不改项目主调）：环与柱 #FFFFFF，珠 #63D8FF。
- 单色场景（印刷 / 水印 / 图标）：全 currentColor 单色，浅底用黑、深底用白。
