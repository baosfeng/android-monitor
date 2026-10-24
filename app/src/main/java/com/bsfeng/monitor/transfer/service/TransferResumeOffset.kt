package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：续传偏移的计算规则（协议 §6）。
// 只依赖「文件大小 + .part 实际长度」，不碰会话状态机。

import com.bsfeng.monitor.transfer.transport.StreamCopier
import java.io.File

/** 续传偏移规则。 */
internal object TransferResumeOffset {

    /**
     * 接收侧已收字节数，以 `.part` 实际大小为准。
     *
     * 接收端进程被系统回收后重启，会话内存里的 `received` 是 0，但磁盘 `.part` 已有部分字节；
     * 若按内存值应答 `offset=0`，发送端会从头传、随后被严格校验打成 4003，续传能力丢失。
     *
     * - `.part` 不存在 → 0；
     * - 长度在 `0..file.size` → 该长度；
     * - 长度超出声明大小 → 脏数据，删除后按 0；
     * - 已收满（`received >= size`）→ 返回 size（此时 `.part` 已落盘并删除，不能回落成 0）。
     *
     * @return 偏移；同时把算出来的 `.part` 回写给调用方（引擎要复用它写盘）。
     */
    fun compute(
        partDir: File,
        fileId: String,
        declaredSize: Long,
        memoryReceived: Long,
    ): Result {
        if (declaredSize > 0L && memoryReceived >= declaredSize) {
            return Result(offset = declaredSize, part = StreamCopier.partFile(partDir, fileId))
        }
        val part = StreamCopier.partFile(partDir, fileId)
        if (!part.exists()) return Result(offset = 0L, part = part)
        val length = part.length()
        if (length <= declaredSize) return Result(offset = length, part = part)
        part.delete()
        return Result(offset = 0L, part = part)
    }

    /** 偏移 + 对应的 `.part` 文件。 */
    class Result(val offset: Long, val part: File)
}
