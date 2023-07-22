package com.github.nikalon.sunsync;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.github.nikalon.sunsync.Sun.NeverRaisesException;
import com.github.nikalon.sunsync.Sun.NeverSetsException;

public class MoonTest {
    // We can safely use absolute errors because we are only checking values between 0.0 and 1.0 and the floating point
    // precision doesn't matter that much
    private static final double MOON_PHASE_ABSOLUTE_ERROR_MARGIN = 0.05;

    private static double distanceBetweenNumbers(double x1, double x2) {
        // Returns the distance between the values x1 and x2 in a circular interval. Both numbers must be between the
        // range [0.0, 1.0)
        return Math.min(Math.abs(x1-x2), 1 - Math.abs(x1-x2));
    }

    @Test
    void fullMoonTest5February2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 2, 5, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.5;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void fullMoonTest27December2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 12, 27, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.5;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void newMoonTest20February2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 2, 20, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.0;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void newMoonTest12December2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 12, 12, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.0;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void lastQuarter12February2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 2, 13, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.75;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void lastQuarter5December2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 12, 5, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.75;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void firstQuarter27February2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 2, 27, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.25;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }

    @Test
    void firstQuarter20December2023Test() throws NeverRaisesException, NeverSetsException {
        LocalDateTime date = LocalDateTime.of(2023, 12, 20, 0, 0);
        double moon_phase = Moon.phase(date);

        double expected = 0.25;
        assertTrue(moon_phase >= 0.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(moon_phase <  1.0, "Moon phase must be in the range [0.0, 1.0)");
        assertTrue(distanceBetweenNumbers(moon_phase, expected) <= MOON_PHASE_ABSOLUTE_ERROR_MARGIN);
    }
}