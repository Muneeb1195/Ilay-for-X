package com.example.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe bounded LRU cache with per-entry TTL expiration.
 * Eliminates unbounded map growth over long browsing sessions and deduplicates
 * cache boilerplate across repositories and network clients.
 */
class BoundedTtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val defaultTtlMs: Long,
) {
    private data class Entry<V>(
        val value: V,
        val savedAtMs: Long = System.currentTimeMillis(),
    )

    private val map = object : LinkedHashMap<K, Entry<V>>(
        minOf(maxEntries, 32).coerceAtLeast(8),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K, ttlMs: Long = defaultTtlMs): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.savedAtMs > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value)
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun transformAll(transform: (V) -> V) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val updated = transform(entry.value.value)
            if (updated !== entry.value.value) {
                entry.setValue(Entry(updated, entry.value.savedAtMs))
            }
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/**
 * Cancellation-safe in-flight request coalescer.
 * Overlapping callers for the same key share a single network round-trip.
 * Rethrows [CancellationException] properly without poisoning shared waiters or
 * swallowing structured coroutine cancellation.
 */
class RequestCoalescer<K : Any, V> {
    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun coalesce(key: K, block: suspend () -> V): V {
        val deferred = CompletableDeferred<V>()
        val existing = inFlight.putIfAbsent(key, deferred)
        if (existing != null) {
            return existing.await()
        }
        return try {
            val value = block()
            deferred.complete(value)
            value
        } catch (ce: CancellationException) {
            deferred.cancel(ce)
            throw ce
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, deferred)
        }
    }
}
