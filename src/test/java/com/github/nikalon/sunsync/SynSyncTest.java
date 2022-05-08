package com.github.nikalon.sunsync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SynSyncTest {
    @Test
    public void shouldDetectCommandThatChangesGameTimeTest() {
        // time set with slash
        assertTrue(SunSync.commandChangesGameTime("/time set day"));
        assertTrue(SunSync.commandChangesGameTime("  /time set night  "));
        assertTrue(SunSync.commandChangesGameTime("  /time     set    5  "));
        assertTrue(SunSync.commandChangesGameTime("/TIME set 4"));

        // time set without slash
        assertTrue(SunSync.commandChangesGameTime("time set day"));
        assertTrue(SunSync.commandChangesGameTime("  time set night  "));
        assertTrue(SunSync.commandChangesGameTime("  time     set    5  "));
        assertTrue(SunSync.commandChangesGameTime("TIME set 4"));

        // time add with slash
        assertTrue(SunSync.commandChangesGameTime("/time add day"));
        assertTrue(SunSync.commandChangesGameTime("  /time add night  "));
        assertTrue(SunSync.commandChangesGameTime("  /time     add    5  "));
        assertTrue(SunSync.commandChangesGameTime("/TIME add 4"));

        // time add without slash
        assertTrue(SunSync.commandChangesGameTime("time add day"));
        assertTrue(SunSync.commandChangesGameTime("  time add night  "));
        assertTrue(SunSync.commandChangesGameTime("  time     add    5  "));
        assertTrue(SunSync.commandChangesGameTime("TIME add 4"));
    }

    @Test
    public void shouldDetectFullyQualifiedCommandThatChangesGameTimeTest() {
        // time set with slash
        assertTrue(SunSync.commandChangesGameTime("/minecraft:time set day"));
        assertTrue(SunSync.commandChangesGameTime("  /minecraft:time set night  "));
        assertTrue(SunSync.commandChangesGameTime("  /minecraft:time     set    5  "));
        assertTrue(SunSync.commandChangesGameTime("/MINECRAFT:TIME set 4"));

        // time set without slash
        assertTrue(SunSync.commandChangesGameTime("minecraft:time set day"));
        assertTrue(SunSync.commandChangesGameTime("  minecraft:time set night  "));
        assertTrue(SunSync.commandChangesGameTime("  minecraft:time     set    5  "));
        assertTrue(SunSync.commandChangesGameTime("MINECRAFT:TIME set 4"));

        // time add with slash
        assertTrue(SunSync.commandChangesGameTime("/minecraft:time add day"));
        assertTrue(SunSync.commandChangesGameTime("  /minecraft:time add night  "));
        assertTrue(SunSync.commandChangesGameTime("  /minecraft:time     add    5  "));
        assertTrue(SunSync.commandChangesGameTime("/MINECRAFT:TIME add 4"));

        // time add without slash
        assertTrue(SunSync.commandChangesGameTime("minecraft:time add day"));
        assertTrue(SunSync.commandChangesGameTime("  minecraft:time add night  "));
        assertTrue(SunSync.commandChangesGameTime("  minecraft:time     add    5  "));
        assertTrue(SunSync.commandChangesGameTime("MINECRAFT:TIME add 4"));
    }

    @Test
    public void shouldNotDetectCommandThatDoesNotChangeTimeTest() {
        // time query with slash
        assertFalse(SunSync.commandChangesGameTime("/time query day"));
        assertFalse(SunSync.commandChangesGameTime("  /time query daytime  "));
        assertFalse(SunSync.commandChangesGameTime("  /time     query    gametime  "));
        assertFalse(SunSync.commandChangesGameTime("/TIME query gametime"));

        // time query without slash
        assertFalse(SunSync.commandChangesGameTime("time query day"));
        assertFalse(SunSync.commandChangesGameTime("  time query daytime  "));
        assertFalse(SunSync.commandChangesGameTime("  time     query    gametime  "));
        assertFalse(SunSync.commandChangesGameTime("TIME query gametime"));

        // time query, fully qualified
        assertFalse(SunSync.commandChangesGameTime("minecraft:time query day"));
        assertFalse(SunSync.commandChangesGameTime("  minecraft:time query daytime  "));
        assertFalse(SunSync.commandChangesGameTime("  minecraft:time     query    gametime  "));
        assertFalse(SunSync.commandChangesGameTime("MINECRAFT:TIME query gametime"));
    }
}
