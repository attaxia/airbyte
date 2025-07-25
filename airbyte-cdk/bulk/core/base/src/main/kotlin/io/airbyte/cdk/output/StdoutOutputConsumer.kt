/*
 * Copyright (c) 2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.output

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SequenceWriter
import io.airbyte.cdk.util.Jsons
import io.airbyte.protocol.models.v0.AirbyteMessage
import io.airbyte.protocol.models.v0.AirbyteRecordMessage
import io.airbyte.protocol.models.v0.AirbyteRecordMessageMeta
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Secondary
import io.micronaut.context.annotation.Value
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Configuration properties prefix for [StdoutOutputConsumer]. */
const val CONNECTOR_OUTPUT_PREFIX = "airbyte.connector.output"

abstract class BaseStdoutOutputConsumer(
    clock: Clock,
) : OutputConsumer(clock) {
    protected open val buffer = ByteArrayOutputStream()
    protected val jsonGenerator: JsonGenerator = Jsons.createGenerator(buffer)
    protected val sequenceWriter: SequenceWriter = Jsons.writer().writeValues(jsonGenerator)

    override fun accept(airbyteMessage: AirbyteMessage) {
        // This method effectively println's its JSON-serialized argument.
        // Using println is not particularly efficient, however.
        // To improve performance, this method accumulates RECORD messages into a buffer
        // before writing them to standard output in a batch.
        if (
            airbyteMessage.type == AirbyteMessage.Type.RECORD &&
                airbyteMessage.record.additionalProperties[IS_DUMMY_STATS_MESSAGE] != true
        ) {
            // RECORD messages undergo a different serialization scheme.
            accept(airbyteMessage.record)
        } else {
            synchronized(this) {
                // Write a newline character to the buffer if it's not empty.
                withLockMaybeWriteNewline()
                // Non-RECORD AirbyteMessage instances are serialized and written to the buffer
                // using standard jackson object mapping facilities.
                sequenceWriter.write(airbyteMessage)
                sequenceWriter.flush()
                // Such messages don't linger in the buffer, they are flushed to stdout immediately,
                // along with whatever might have already been lingering inside.
                // This prints a newline after the message.
                withLockFlush()
            }
        }
    }

    protected fun withLockMaybeWriteNewline() {
        if (buffer.size() > 0) {
            buffer.write('\n'.code)
        }
    }

    override fun close() {
        synchronized(this) {
            // Flush any remaining buffer contents to stdout before closing.
            withLockFlush()
        }
    }

    abstract fun withLockFlush()

    protected val metaPrefixBytes: ByteArray = META_PREFIX.toByteArray()

    protected open fun getOrCreateRecordTemplate(
        stream: String,
        namespace: String?
    ): RecordTemplate {
        val streamToTemplateMap: StreamToTemplateMap =
            if (namespace == null) {
                unNamespacedTemplates
            } else {
                namespacedTemplates.getOrPut(namespace) { StreamToTemplateMap() }
            }
        return streamToTemplateMap.getOrPut(stream) {
            RecordTemplate.create(stream, namespace, recordEmittedAt)
        }
    }

    protected val namespacedTemplates = ConcurrentHashMap<String, StreamToTemplateMap>()
    protected val unNamespacedTemplates = StreamToTemplateMap()

    companion object {
        const val META_PREFIX = ""","meta":"""
    }
}

typealias StreamToTemplateMap = ConcurrentHashMap<String, RecordTemplate>

class RecordTemplate(
    /** [prefix] is '{"type":"RECORD","record":{"namespace":"...","stream":"...","data":' */
    val prefix: ByteArray,
    /** [suffix] is ',"emitted_at":...}}' */
    val suffix: ByteArray,
) {
    companion object {
        fun create(
            stream: String,
            namespace: String?,
            emittedAt: Instant,
            additionalProperties: Map<String, String> = emptyMap(),
        ): RecordTemplate {
            // Generate a dummy AirbyteRecordMessage instance for the given args
            // using an empty object (i.e. '{}') for the "data" field value.
            val recordMessage =
                AirbyteRecordMessage()
                    .withStream(stream)
                    .withNamespace(namespace)
                    .withEmittedAt(emittedAt.toEpochMilli())
                    .withData(Jsons.objectNode())

            for (additionalProperty in additionalProperties) {
                recordMessage.withAdditionalProperty(
                    additionalProperty.key,
                    additionalProperty.value
                )
            }
            // Generate the corresponding dummy AirbyteMessage instance.
            val airbyteMessage =
                AirbyteMessage().withType(AirbyteMessage.Type.RECORD).withRecord(recordMessage)
            // Serialize to JSON.
            val json: String = Jsons.writeValueAsString(airbyteMessage)
            // Split the string in 2 around the '"data":{}' substring.
            val parts: List<String> = json.split(DATA_SPLIT_DELIMITER, limit = 2)
            require(parts.size == 2) { "expected to find $DATA_SPLIT_DELIMITER in $json" }
            // Re-attach the '"data":' substring to the first part
            // and return both parts in a RecordTemplate instance for this stream.
            return RecordTemplate(
                prefix = (parts.first() + DATA_PREFIX).toByteArray(),
                suffix = parts.last().toByteArray()
            )
        }

        private const val DATA_PREFIX = """"data":"""
        private const val DATA_SPLIT_DELIMITER = "$DATA_PREFIX{}"
    }
}

/** A simple [OutputConsumer] such as standard output or buffering test output consumer. */
abstract class StandardOutputConsumer(clock: Clock) : BaseStdoutOutputConsumer(clock)

/** Default implementation of [OutputConsumer]. */
@Singleton
@Secondary
class StdoutOutputConsumer(
    val stdout: PrintStream,
    clock: Clock,
    /**
     * [bufferByteSizeThresholdForFlush] triggers flushing the record buffer to stdout once the
     * buffer's size (in bytes) grows past this value.
     *
     * Flushing the record buffer to stdout is done by calling [println] which is synchronized. The
     * choice of [println] is imposed by our use of the ConsoleJSONAppender log4j appended which
     * concurrently calls [println] to print [AirbyteMessage]s of type LOG to standard output.
     *
     * Because calling [println] incurs both a synchronization overhead and a syscall overhead, the
     * connector's performance will noticeably degrade if it's called too often. This happens
     * primarily when emitting lots of tiny RECORD messages, which is typical of source connectors.
     *
     * For this reason, the [bufferByteSizeThresholdForFlush] value should not be too small. The
     * default value of 4kB is good in this respect. For example, if the average serialized record
     * size is 100 bytes, this will reduce the volume of [println] calls by a factor of 40.
     *
     * Conversely, the [bufferByteSizeThresholdForFlush] value should also not be too large.
     * Otherwise, the output becomes bursty and this also degrades performance. As of today (and
     * hopefully not for long) the platform still pipes the connector's stdout into socat to emit
     * the output as TCP packets. While socat is buffered, its buffer size is only 8 kB. In any
     * case, TCP packet sized (capped by the MTU) are also in the low kilobytes.
     */
    @Value("\${$CONNECTOR_OUTPUT_PREFIX.buffer-byte-size-threshold-for-flush:4096}")
    val bufferByteSizeThresholdForFlush: Int,
) :
    StandardOutputConsumer(
        clock,
    ) {
    override fun withLockFlush() {
        if (buffer.size() > 0) {
            stdout.println(buffer.toString(Charsets.UTF_8))
            stdout.flush()
            buffer.reset()
        }
    }

    override fun accept(record: AirbyteRecordMessage) {
        // The serialization of RECORD messages can become a performance bottleneck for source
        // connectors because they can come in much higher volumes than other message types.
        // Specifically, with jackson, the bottleneck is in the object mapping logic.
        // As it turns out, this object mapping logic is not particularly useful for RECORD messages
        // because within a given stream the only variations occur in the "data" and the "meta"
        // fields:
        // - the "data" field is already an ObjectNode and is cheap to serialize,
        // - the "meta" field is often unset.
        // For this reason, this method builds and reuses a JSON template for each stream.
        // Then, for each record, it serializes just "data" and "meta" to populate the template.
        val template: RecordTemplate = getOrCreateRecordTemplate(record.stream, record.namespace)
        synchronized(this) {
            // Write a newline character to the buffer if it's not empty.
            withLockMaybeWriteNewline()
            // Write '{"type":"RECORD","record":{"namespace":"...","stream":"...","data":'.
            buffer.write(template.prefix)
            // Serialize the record data ObjectNode to JSON, writing it to the buffer.
            Jsons.writeTree(jsonGenerator, record.data)
            jsonGenerator.flush()
            // If the record has a AirbyteRecordMessageMeta instance set,
            // write ',"meta":' followed by the serialized meta.
            val meta: AirbyteRecordMessageMeta? = record.meta
            if (meta != null) {
                buffer.write(metaPrefixBytes)
                sequenceWriter.write(meta)
                sequenceWriter.flush()
            }
            // Write ',"emitted_at":...}}'.
            buffer.write(template.suffix)
            // Flush the buffer to stdout only once it has reached a certain size.
            // Flushing to stdout incurs some overhead (mutex, syscall, etc.)
            // which otherwise becomes very apparent when lots of tiny records are involved.
            if (buffer.size() >= bufferByteSizeThresholdForFlush) {
                withLockFlush()
            }
        }
    }
}

@Factory
private class PrintStreamFactory {

    @Singleton @Requires(notEnv = [Environment.TEST]) fun stdout(): PrintStream = System.out
}
