/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.load.pipline.object_storage

import io.airbyte.cdk.load.command.DestinationCatalog
import io.airbyte.cdk.load.command.DestinationConfiguration
import io.airbyte.cdk.load.file.object_storage.ObjectStorageClient
import io.airbyte.cdk.load.file.object_storage.RemoteObject
import io.airbyte.cdk.load.file.object_storage.StreamingUpload
import io.airbyte.cdk.load.message.BatchState
import io.airbyte.cdk.load.message.WithBatchState
import io.airbyte.cdk.load.pipeline.BatchAccumulator
import io.airbyte.cdk.load.pipeline.BatchAccumulatorResult
import io.airbyte.cdk.load.pipeline.FinalOutput
import io.airbyte.cdk.load.pipeline.IntermediateOutput
import io.airbyte.cdk.load.write.object_storage.ObjectLoader
import io.airbyte.cdk.load.write.object_storage.metadataFor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

/**
 * In order to allow streaming uploads on the same key to be parallelized, upload state needs to be
 * shared across workers.
 */
@Singleton
@Requires(bean = ObjectLoader::class)
class UploadsInProgress<T : RemoteObject<*>> {
    val byKey: ConcurrentHashMap<String, ObjectLoaderPartLoader.State<T>> = ConcurrentHashMap()
}

// TODO: Add unit tests
@Singleton
@Requires(bean = ObjectLoader::class)
class ObjectLoaderPartLoader<T : RemoteObject<*>>(
    private val client: ObjectStorageClient<T>,
    private val catalog: DestinationCatalog,
    private val uploads: UploadsInProgress<T>,
    private val destinationConfig: DestinationConfiguration,
) :
    BatchAccumulator<
        ObjectLoaderPartLoader.State<T>,
        ObjectKey,
        ObjectLoaderPartFormatter.FormattedPart,
        ObjectLoaderPartLoader.PartResult<T>
    > {
    private val log = KotlinLogging.logger {}

    data class State<T : RemoteObject<*>>(
        val objectKey: String,
        val streamingUpload: Deferred<StreamingUpload<T>>,
    ) : AutoCloseable {
        override fun close() {
            // Do Nothing
        }
    }

    sealed interface PartResult<T : RemoteObject<*>> : WithBatchState {
        val objectKey: String
    }
    data class LoadedPart<T : RemoteObject<*>>(
        val upload: Deferred<StreamingUpload<T>>,
        override val objectKey: String,
        val partIndex: Int,
        val isFinal: Boolean,
        // keep track of whether it's empty so the bookkeeper can ignore it
        val empty: Boolean = false,
    ) : PartResult<T> {
        override val state: BatchState = BatchState.STAGED
    }
    data class NoPart<T : RemoteObject<*>>(override val objectKey: String) : PartResult<T> {
        override val state: BatchState = BatchState.STAGED
    }

    override suspend fun start(key: ObjectKey, part: Int): State<T> {
        val stream = catalog.getStream(key.stream)
        return uploads.byKey.computeIfAbsent(key.uploadId ?: key.objectKey) {
            State(
                key.objectKey,
                CoroutineScope(Dispatchers.IO).async {
                    client.startStreamingUpload(
                        key.objectKey,
                        metadata = destinationConfig.metadataFor(stream)
                    )
                },
            )
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun acceptWithExperimentalCoroutinesApi(
        input: ObjectLoaderPartFormatter.FormattedPart,
        state: State<T>
    ): BatchAccumulatorResult<State<T>, PartResult<T>> {
        log.debug { "Uploading part $input" }
        if (!input.part.isFinal && input.part.bytes == null) {
            throw IllegalStateException("Empty non-final part received: this should not happen")
        }

        val upload =
            if (state.streamingUpload.isCompleted) {
                state.streamingUpload.getCompleted()
            } else {
                state.streamingUpload.await()
            }

        input.part.bytes?.let { bytes -> upload.uploadPart(bytes, input.part.partIndex) }
        val output =
            LoadedPart(
                state.streamingUpload,
                input.part.key,
                input.part.partIndex,
                input.part.isFinal,
                input.part.bytes == null,
            )
        return IntermediateOutput(state, output)
    }

    override suspend fun accept(
        input: ObjectLoaderPartFormatter.FormattedPart,
        state: State<T>
    ): BatchAccumulatorResult<State<T>, PartResult<T>> {
        log.info { "Uploading part $input" }
        if (!input.part.isFinal && input.part.bytes == null) {
            throw IllegalStateException("Empty non-final part received: this should not happen")
        }
        input.part.bytes?.let { state.streamingUpload.await().uploadPart(it, input.part.partIndex) }
        val output =
            LoadedPart(
                state.streamingUpload,
                input.part.key,
                input.part.partIndex,
                input.part.isFinal,
                input.part.bytes == null,
            )
        return IntermediateOutput(state, output)
    }

    override suspend fun finish(state: State<T>): FinalOutput<State<T>, PartResult<T>> {
        return FinalOutput(NoPart(state.objectKey))
    }
}
