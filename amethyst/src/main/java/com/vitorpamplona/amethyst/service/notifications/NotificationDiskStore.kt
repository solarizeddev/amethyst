/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.service.notifications

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.Log
import java.io.File

/**
 * Per-account, on-disk store for the events that back notification cards.
 *
 * Amethyst keeps events only in memory ([com.vitorpamplona.amethyst.model.LocalCache] →
 * `LargeSoftCache`), so on every cold start the notification feed is rebuilt
 * entirely from relays — anything a relay has pruned, or that falls outside the
 * fetch `limit`, is gone. This store persists just the events the notification
 * feed accepts (plus, by reuse of the cache, whatever parents are already
 * loaded), so a cold start can render instantly from disk and retain history a
 * relay no longer serves.
 *
 * It deliberately stores **only notification-relevant events**, never the whole
 * event graph, and bounds itself by two axes so disk usage stays small:
 *  - [maxEvents]: keep at most the newest N events.
 *  - [maxAgeSeconds]: drop anything older than this regardless of count.
 *
 * Eviction here is by `created_at` (the cheap, predictable policy); an
 * LRU-by-display upgrade is noted in
 * `amethyst/plans/2026-06-22-notification-local-store.md` as future work.
 *
 * The file is line-delimited JSON (one signed event per line). quartz owns
 * (de)serialization, so there is no schema to migrate. All public methods are
 * safe to call concurrently from the feed coroutines.
 */
class NotificationDiskStore(
    private val file: File,
    private val maxEvents: Int = MAX_EVENTS,
    private val maxAgeSeconds: Long = MAX_AGE_SECONDS,
) {
    private val lock = Any()

    // Ids already on disk. Lets [persist] append only genuinely new events
    // (the feed re-offers the same notifications on every refresh) and avoids
    // a read-back per append. Populated by [load] and by each [persist].
    private val knownIds = HashSet<String>()

    /**
     * Reads and parses every persisted event. Intended to run once on cold
     * start, feeding the result into the in-memory cache. Unparseable lines are
     * skipped rather than aborting the whole load.
     */
    fun load(): List<Event> =
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val events = ArrayList<Event>()
            try {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        Event.fromJsonOrNull(line)?.let {
                            if (knownIds.add(it.id)) events.add(it)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("NotificationDiskStore", "Failed to read $file", e)
            }
            events
        }

    /**
     * Appends the events not already stored, then enforces the size/age caps.
     * No-op for events already on disk, so it is cheap to call on every feed
     * update with the full accepted set.
     */
    fun persist(events: Collection<Event>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            val cutoff = nowSeconds() - maxAgeSeconds
            val toAdd = events.filter { it.createdAt >= cutoff && knownIds.add(it.id) }
            if (toAdd.isEmpty()) return

            try {
                file.parentFile?.mkdirs()
                file.appendText(
                    buildString {
                        toAdd.forEach {
                            append(it.toJson())
                            append('\n')
                        }
                    },
                )
            } catch (e: Exception) {
                // Roll back the in-memory ids so a later attempt can retry.
                toAdd.forEach { knownIds.remove(it.id) }
                Log.w("NotificationDiskStore", "Failed to append to $file", e)
                return
            }

            if (knownIds.size > maxEvents) prune()
        }
    }

    /**
     * Keeps the newest [maxEvents] events within [maxAgeSeconds] and rewrites
     * the file. Must be called while holding [lock].
     */
    private fun prune() {
        val cutoff = nowSeconds() - maxAgeSeconds
        val kept =
            try {
                file
                    .useLines { lines ->
                        lines
                            .mapNotNull { if (it.isBlank()) null else Event.fromJsonOrNull(it) }
                            .toList()
                    }.filter { it.createdAt >= cutoff }
                    .sortedByDescending { it.createdAt }
                    .take(maxEvents)
            } catch (e: Exception) {
                Log.w("NotificationDiskStore", "Failed to read $file for prune", e)
                return
            }

        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                buildString {
                    kept.forEach {
                        append(it.toJson())
                        append('\n')
                    }
                },
            )
            file.delete()
            if (tmp.renameTo(file)) {
                knownIds.clear()
                kept.forEach { knownIds.add(it.id) }
            } else {
                Log.w("NotificationDiskStore", "Failed to swap pruned file into $file")
            }
        } catch (e: Exception) {
            Log.w("NotificationDiskStore", "Failed to rewrite $file", e)
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    companion object {
        const val MAX_EVENTS = 10_000

        // ~6 months. Older interactions age out even if the count cap is not hit.
        const val MAX_AGE_SECONDS = 60L * 60 * 24 * 180
    }
}
