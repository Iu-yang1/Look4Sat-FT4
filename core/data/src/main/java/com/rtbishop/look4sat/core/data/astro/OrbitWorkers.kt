package com.rtbishop.look4sat.core.data.astro

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

internal fun orbitWorkerCount(processors: Int = Runtime.getRuntime().availableProcessors()): Int =
    (processors - 1).coerceIn(1, 8)

/** Bounded structured workers; input order is retained without one job per catalogue entry. */
internal suspend fun <T, R> List<T>.orbitParallelMap(
    parallelism: Int = orbitWorkerCount(),
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    require(parallelism > 0)
    val next = AtomicInteger()
    val results = arrayOfNulls<Any?>(size)
    List(minOf(parallelism, size)) {
        async {
            while (true) {
                coroutineContext.ensureActive()
                val index = next.getAndIncrement()
                if (index >= size) break
                results[index] = transform(this@orbitParallelMap[index])
                yield()
            }
        }
    }.awaitAll()
    results
}.map {
    @Suppress("UNCHECKED_CAST")
    (it as R)
}
