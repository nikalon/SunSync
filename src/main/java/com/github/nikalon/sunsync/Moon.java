package com.github.nikalon.sunsync;

import java.time.LocalDateTime;

class Moon {
    private Moon() {} // Disallow instantiation

    // Returns the phase of the Moon as a normalized value between [0.0, 1.0), starting from a New Moon at value 0.0,
    // First Quarter at 0.25, Full Moon at 0.5 and Last Quarter at 0.75
    static double phase(LocalDateTime date) {
        double hour = ((double) date.getHour()) / 24.0;
        double minute = ((double) date.getMinute()) / 1440.0; // 1440 minutes in a full day
        double second = ((double) date.getSecond()) / 86400.0; // 86400 seconds in a full day
        double day = (double) date.getDayOfMonth() + hour + minute + second;
        int month = date.getMonthValue();
        int year = date.getYear();

        final double l0 = 91.929336; // In degrees
        final double P0 = 130.143076; // In degrees

        double sunMeanAnomalyRad = Math.toRadians(Sun.meanAnomaly(day, month, year));
        double sunEclipticLongitudeDeg = Sun.eclipticLongitude(day, month, year);

        // The epoch is January 0.0 2010
        double daysElapsedSinceEpoch = Helper.GreenwichToJulianDate(day, month, year) - Helper.GreenwichToJulianDate(0, 1, 2010);
        double l = Helper.modulo(13.1763966*daysElapsedSinceEpoch + l0, 360); // Moon's mean longitude
        double Mm = Helper.modulo(l - 0.1114041*daysElapsedSinceEpoch - P0, 360); // Moon's mean anomaly

        double C = l - sunEclipticLongitudeDeg;
        double Ev = 1.2739 * Math.sin(Math.toRadians(2*C - Mm)); // Corrections for eviction
        double Ae = 0.1858 * Math.sin(sunMeanAnomalyRad); // Annual equation
        double A3 = 0.37 * Math.sin(sunMeanAnomalyRad); // Third correction
        double MPrimeMRad = Math.toRadians(Mm + Ev - Ae - A3); // Moon's corrected anomaly
        double Ec = 6.2886 * Math.sin(MPrimeMRad); // Equation of the centre
        double A4 = 0.214 * Math.sin(2*MPrimeMRad); // More corrections
        double lPrime = l + Ev + Ec - Ae + A4; // Moon's corrected longitude
        double V = 0.6583 * Math.sin(Math.toRadians( 2 * (lPrime - sunEclipticLongitudeDeg) )); // More corrections

        double lPrimePrime = lPrime + V; // Moon's true orbital longitude

        double moonAge = Helper.modulo(lPrimePrime - sunEclipticLongitudeDeg, 360.0) / 360.0;
        return moonAge;
    }

}