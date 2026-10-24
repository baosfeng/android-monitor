package com.bsfeng.monitor.skip.json

// 来源：原 skip/json/Json5Parser.kt；职责：JSON5 解析入口（对外只暴露 parse，实现分片在
// Json5Reader / Json5ContainerReader / Json5StringReader / Json5NumberReader）。

/**
 * 纯 Kotlin 实现的 JSON5 解析器（零第三方依赖），用于解析 GKD 订阅规则文件。
 *
 * 支持的语法：
 * - 字符串：单引号、双引号、反引号三种引号，支持转义 \\ \' \" \/ \n \r \t \b \f \v \0 \uXXXX \xXX
 * - 对象键：可加引号，也可为不加引号的标识符
 * - 注释：双斜杠行注释与斜杠星号块注释
 * - 尾随逗号：对象与数组末尾均允许
 * - 数字：十进制、十六进制（0x 前缀）、科学计数法、正号前缀、NaN、Infinity
 *
 * 返回类型约定：
 * - 对象 → [LinkedHashMap]（保持键的插入顺序）
 * - 数组 → [ArrayList]
 * - 字符串 → [String]；布尔 → [Boolean]；null → null
 * - 数字按数值归一化：整数值且可用 [Int] 表示时返回 [Int]，其次 [Long]，其余返回 [Double]；
 *   因此 1.0、2e3 也返回 [Int]，而 1.5、NaN、Infinity 返回 [Double]
 *
 * 实现为单遍扫描，不使用正则做整体解析。
 */
object Json5Parser {

    /**
     * 解析 JSON5 文本。
     *
     * @param text 待解析的 JSON5 文本
     * @return 解析结果，类型约定见 [Json5Parser]
     * @throws IllegalArgumentException 语法非法时抛出，异常信息包含出错偏移
     */
    fun parse(text: String): Any? {
        val reader = Json5Reader(text)
        val value = reader.readValue(0)
        reader.skipSpace()
        if (!reader.atEnd) {
            throw IllegalArgumentException("偏移 ${reader.pos} 处存在多余内容 '${text[reader.pos]}'")
        }
        return value
    }
}
