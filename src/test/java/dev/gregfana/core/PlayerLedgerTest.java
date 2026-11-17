package dev.gregfana.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlayerLedgerTest {

    @Test
    public void importDoesNotChangeObservedOrDoubleCount() {
        PlayerLedger ledger = new PlayerLedger();
        ledger.observe("offline-player", "play", 200);
        PlayerLedger.Entry e = ledger.observe("offline-player", "play", 240);
        assertEquals(40, e.observed);
        assertEquals(1, ledger.importHistory("offline-player", false));
        assertFalse(e.imported);
        assertEquals(1, ledger.importHistory("offline-player", true));
        assertEquals(0, ledger.importHistory("offline-player", true));
        assertEquals(200, e.baseline);
        assertEquals(40, e.observed);
    }

    @Test
    public void sourceResetAndNegativeOverflowNeverSubtractActivity() {
        PlayerLedger ledger = new PlayerLedger();
        ledger.observe("p", "s", 100);
        ledger.observe("p", "s", 120);
        ledger.observe("p", "s", 3);
        PlayerLedger.Entry e = ledger.observe("p", "s", -1);
        assertEquals(23, e.observed);
        assertEquals(1, ledger.resets);
        ledger.observe("p", "s", 7);
        assertEquals(27, e.observed);
    }

    @Test
    public void copiesAreIndependentAndPlayersIsolated() {
        PlayerLedger ledger = new PlayerLedger();
        ledger.observe("a", "s", 50);
        ledger.observe("b", "s", 10);
        PlayerLedger copy = ledger.copy();
        ledger.observe("a", "s", 60);
        ledger.importHistory("a", true);
        assertEquals(
            0,
            copy.players.get("a")
                .get("s").observed);
        assertFalse(
            copy.players.get("a")
                .get("s").imported);
        assertEquals(
            10,
            copy.players.get("b")
                .get("s").baseline);
    }
}
