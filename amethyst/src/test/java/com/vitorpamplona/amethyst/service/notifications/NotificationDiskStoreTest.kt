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

import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationDiskStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pk = "2".repeat(64)
    private val sig = "f".repeat(128)
    private val nowSec = System.currentTimeMillis() / 1000

    private fun reaction(
        idChar: Char,
        createdAt: Long,
    ): ReactionEvent =
        ReactionEvent(
            idChar.toString().repeat(64),
            pk,
            createdAt,
            arrayOf(arrayOf("e", "d".repeat(64)), arrayOf("p", pk)),
            "+",
            sig,
        )

    private fun file() = File(tmp.root, "accounts/$pk/notifications.jsonl")

    @Test
    fun `persist then load round-trips the stored event ids`() {
        val a = reaction('a', nowSec - 20)
        val b = reaction('b', nowSec - 10)

        NotificationDiskStore(file()).persist(listOf(a, b))

        val reloaded = NotificationDiskStore(file()).load().map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), reloaded)
    }

    @Test
    fun `persisting an already-stored event is a no-op`() {
        val a = reaction('a', nowSec - 10)
        val store = NotificationDiskStore(file())

        store.persist(listOf(a))
        store.persist(listOf(a))

        assertEquals(1, NotificationDiskStore(file()).load().size)
    }

    @Test
    fun `count cap keeps only the newest events`() {
        val a = reaction('a', nowSec - 30)
        val b = reaction('b', nowSec - 20)
        val c = reaction('c', nowSec - 10)

        NotificationDiskStore(file(), maxEvents = 2).persist(listOf(a, b, c))

        val kept = NotificationDiskStore(file()).load().map { it.id }.toSet()
        assertEquals(setOf(b.id, c.id), kept)
        assertTrue(a.id !in kept)
    }

    @Test
    fun `age cap drops events older than the window`() {
        val old = reaction('a', nowSec - 1000)
        val fresh = reaction('b', nowSec - 10)

        NotificationDiskStore(file(), maxAgeSeconds = 100).persist(listOf(old, fresh))

        val kept = NotificationDiskStore(file()).load().map { it.id }.toSet()
        assertEquals(setOf(fresh.id), kept)
    }
}
