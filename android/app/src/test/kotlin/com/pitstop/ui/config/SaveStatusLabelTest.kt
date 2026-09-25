package com.pitstop.ui.config

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings has no Save button; this line is the only save feedback. */
class SaveStatusLabelTest {
    @Test
    fun `pending edits read as saving until disk matches`() {
        assertEquals("Saving…", saveStatusLabel(SaveStatus.Idle, formSaved = false))
        assertEquals("Saving…", saveStatusLabel(SaveStatus.Saving, formSaved = true))
        assertEquals("All changes saved", saveStatusLabel(SaveStatus.Saved, formSaved = true))
        assertEquals("All changes saved", saveStatusLabel(SaveStatus.Idle, formSaved = true))
    }

    @Test
    fun `a failure is never reported as saved`() {
        assertEquals(
            "Couldn't save — will retry on the next change",
            saveStatusLabel(SaveStatus.Failed, formSaved = true),
        )
    }
}
