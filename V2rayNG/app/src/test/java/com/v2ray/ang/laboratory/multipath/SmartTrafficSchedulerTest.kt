package com.v2ray.ang.laboratory.multipath

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartTrafficSchedulerTest {

    @Test
    fun `first selector needs a reload`() {
        val scheduler = SmartTrafficScheduler()
        assertNull(scheduler.applied)
        assertTrue(scheduler.shouldReload(listOf("path-a")))
    }

    @Test
    fun `an unchanged selector does not reload`() {
        val scheduler = SmartTrafficScheduler()
        assertTrue(scheduler.markAppliedIfNeeded(listOf("path-a", "path-b")))
        assertFalse(scheduler.shouldReload(listOf("path-a", "path-b")))
        assertFalse(scheduler.markAppliedIfNeeded(listOf("path-a", "path-b")))
    }

    @Test
    fun `a different selector reloads once`() {
        val scheduler = SmartTrafficScheduler()
        scheduler.markApplied(listOf("path-a"))
        assertTrue(scheduler.markAppliedIfNeeded(listOf("path-b")))
        assertEquals(listOf("path-b"), scheduler.applied)
        assertFalse(scheduler.markAppliedIfNeeded(listOf("path-b")))
    }

    @Test
    fun `an empty selector is ignored`() {
        val scheduler = SmartTrafficScheduler()
        assertFalse(scheduler.shouldReload(emptyList()))
        assertFalse(scheduler.markAppliedIfNeeded(emptyList()))
    }

    @Test
    fun `reset forgets the applied selector`() {
        val scheduler = SmartTrafficScheduler()
        scheduler.markApplied(listOf("path-a"))
        scheduler.reset()
        assertNull(scheduler.applied)
        assertTrue(scheduler.markAppliedIfNeeded(listOf("path-a")))
    }
}
