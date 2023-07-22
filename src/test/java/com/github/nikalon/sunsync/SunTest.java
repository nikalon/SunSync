package com.github.nikalon.sunsync;

import org.junit.jupiter.api.Test;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate;
import com.github.nikalon.sunsync.Sun.NeverRaisesException;
import com.github.nikalon.sunsync.Sun.NeverSetsException;
import com.github.nikalon.sunsync.Sun.RiseAndSet;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SunTest {
    @Test
    void convertGreenwichToJulianDateTest() {
        assertEquals(2455002.25, Helper.GreenwichToJulianDate(19.75, 6, 2009));
        assertEquals(2458849.5, Helper.GreenwichToJulianDate(1, 1, 2020));
    }

    @Test
    void convertGSTToUtTest() {
        //assertEquals(14.614352889484806, Sun.GSTToUT(4.668119444444445, 22, 4, 1980));
        assertEquals(LocalTime.of(14, 36, 52), Sun.GSTToUT(4.668119444444445, 22, 4, 1980));
    }

    @Test
    void invalidValueForSecondOfMinuteRegressionTest() throws NeverRaisesException, NeverSetsException {
        // Test rounding error for seconds. This test should not throw any exceptions.
        GeographicCoordinate coordinates = GeographicCoordinate.fromDecimalDegrees(8.0, 1.0);
        LocalDate date = LocalDate.of(2023, 1, 29);
        RiseAndSet riseAndSet = Sun.sunriseAndSunsetTimes(coordinates, date);
        assertNotNull(riseAndSet);
    }
}
