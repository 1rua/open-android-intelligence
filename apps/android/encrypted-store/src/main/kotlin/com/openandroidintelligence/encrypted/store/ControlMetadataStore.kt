package com.openandroidintelligence.encrypted.store

import com.openandroidintelligence.control.remote.ControlExecutionStatus
import com.openandroidintelligence.control.remote.ControlMetadataLedger
import com.openandroidintelligence.control.remote.ControlRequestMetadata
import com.openandroidintelligence.control.remote.ControlRequestState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** 重启仅恢复去重事实；执行栅栏之后的不确定请求保守结束，绝不恢复队列或正文。 */
class ControlMetadataStore(private val encrypted: AesGcmEncryptedBlobStore) : ControlMetadataLedger {
    private var records: Map<String, ControlRequestMetadata> = restore()

    @Synchronized
    override fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T {
        val next = records.toMutableMap()
        val result = change(next)
        if (next != records) {
            encrypted.writePlaintext(encode(next))
            records = next.toMap()
        }
        return result
    }

    private fun restore(): Map<String, ControlRequestMetadata> {
        val bytes = encrypted.readPlaintext() ?: return emptyMap()
        return try {
            val restored = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readUTF() == "OAI_CONTROL_METADATA_V1")
                val count = input.readInt()
                check(count >= 0 && count <= bytes.size / 12)
                val items = linkedMapOf<String, ControlRequestMetadata>()
                repeat(count) {
                    val item = ControlRequestMetadata(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                        input.readUTF(), ControlRequestState.valueOf(input.readUTF()), ControlExecutionStatus.valueOf(input.readUTF()),
                        input.readBoolean(), input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null })
                    check(items.put(item.key, item) == null)
                }
                check(input.available() == 0)
                items.mapValues { (_, item) ->
                    if (item.state.terminal) item else if (item.executionFence) item.copy(
                        state = ControlRequestState.OUTCOME_UNKNOWN, execution = ControlExecutionStatus.UNKNOWN, errorCode = "PROCESS_RESTARTED")
                    else item.copy(state = ControlRequestState.CANCELLED, execution = ControlExecutionStatus.NOT_STARTED, errorCode = "PROCESS_RESTARTED")
                }
            }
            // 恢复裁决本身也先持久，不能让下次启动重新解释同一事实。
            encrypted.writePlaintext(encode(restored))
            restored
        } catch (_: Exception) { throw IllegalStateException("CONTROL_LEDGER_UNAVAILABLE") }
        finally { bytes.fill(0) }
    }

    private fun encode(items: Map<String, ControlRequestMetadata>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("OAI_CONTROL_METADATA_V1"); out.writeInt(items.size)
            items.values.forEach { item ->
                out.writeUTF(item.ownerKey); out.writeUTF(item.requestId); out.writeUTF(item.taskSessionId)
                out.writeUTF(item.operation); out.writeUTF(item.parameterDigest); out.writeUTF(item.state.name)
                out.writeUTF(item.execution.name); out.writeBoolean(item.executionFence)
                out.writeUTF(item.cancellationTarget.orEmpty()); out.writeUTF(item.errorCode.orEmpty())
            }
        }
        bytes.toByteArray()
    }
}
