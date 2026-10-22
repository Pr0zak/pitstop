package com.pitstop.domain

import com.pitstop.domain.WicanSetup.Bond
import com.pitstop.domain.WicanSetup.Inputs
import com.pitstop.domain.WicanSetup.LinkPairing
import com.pitstop.domain.WicanSetup.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WicanSetupTest {

    private val ready = Inputs(
        deviceKnown = true, bond = Bond.Bonded, bondRequired = true,
        autoStartOn = true, cdmSupported = true, cdmAssociated = true,
    )

    @Test
    fun `fully set up is Ready with nothing to tap`() {
        assertEquals(State.Ready, WicanSetup.evaluate(ready))
        assertNull(WicanSetup.action(State.Ready))
        assertNull(WicanSetup.pill(State.Ready))
    }

    @Test
    fun `firmware 4 needs no bond`() {
        assertEquals(State.Ready, WicanSetup.evaluate(ready.copy(bond = Bond.None, bondRequired = false)))
    }

    @Test
    fun `firmware 6 without a bond can't link, and that outranks auto-start`() {
        val s = WicanSetup.evaluate(ready.copy(bond = Bond.None, cdmAssociated = false))
        assertEquals(State.BluetoothNeeded, s)
        assertTrue(WicanSetup.blocksLink(s))
        assertEquals("Connect WiCAN", WicanSetup.pill(s)!!.first)
        assertEquals("Connect WiCAN", WicanSetup.action(s))
    }

    @Test
    fun `2026-10-21 forget-in-settings case says auto-start, not pair`() {
        // BLE live + bonded, association dropped: soft state, never "Pair".
        val s = WicanSetup.evaluate(ready.copy(cdmAssociated = false))
        assertEquals(State.AutoStartOff, s)
        assertFalse(WicanSetup.blocksLink(s))
        assertEquals("Auto-start off", WicanSetup.pill(s)!!.first)
        assertEquals("Turn on auto-start", WicanSetup.action(s))
    }

    @Test
    fun `stale bond is Reconnect`() {
        val s = WicanSetup.evaluate(ready.copy(linkPairing = LinkPairing.Rejected))
        assertEquals(State.BluetoothRejected, s)
        assertEquals("Reconnect WiCAN", WicanSetup.action(s))
    }

    @Test
    fun `passkey dialog up has no button`() {
        val s = WicanSetup.evaluate(ready.copy(bond = Bond.Bonding))
        assertEquals(State.BluetoothInProgress, s)
        assertNull(WicanSetup.action(s))
    }

    @Test
    fun `manual-start users and GPS-only users are not nagged`() {
        assertEquals(State.Ready, WicanSetup.evaluate(ready.copy(autoStartOn = false, cdmAssociated = false)))
        assertEquals(State.Ready, WicanSetup.evaluate(ready.copy(bleEnabled = false, deviceKnown = false)))
        assertEquals(State.NoDevice, WicanSetup.evaluate(ready.copy(deviceKnown = false)))
    }

    @Test
    fun `no state or step line ever says Pair`() {
        for (s in State.entries) {
            for (text in listOfNotNull(WicanSetup.pill(s)?.first, WicanSetup.message(s), WicanSetup.action(s))) {
                assertFalse("'$text' uses Pair", text.contains("Pair", ignoreCase = false))
            }
        }
        for (b in Bond.entries) for (req in listOf(true, false)) {
            val i = ready.copy(bond = b, bondRequired = req)
            assertFalse(WicanSetup.bluetoothStep(i).contains("Pair"))
            assertFalse(WicanSetup.autoStartStep(i).contains("Pair"))
        }
    }
}
