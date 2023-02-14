package com.github.nikalon.sunsync;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

class Sun {
    // Acronyms used:
    // LST  Local Sidereal Time
    // GST  Greenwich Sidereal Time
    // UT   Universal Time (this program will assume UTC = UT)

    private static final double VERTICAL_SHIFT_SINE = 0.00989061960670350512825686013281; // sine of 0.5667 degrees

    private Sun() {} // Disallow instantiation

    static LocalTime GSTToUT(double GSTHour, int gDay, int gMonth, int gYear) {
        double julian_date = Helper.GreenwichToJulianDate(gDay, gMonth, gYear);
        double S = julian_date - 2451545.0;
        double T = S / 36525.0;
        double T0 = Helper.modulo((6.697374558 + (2400.051336 * T) + (0.000025862 * Math.pow(T, 2))), 24);
        double B = Helper.modulo((GSTHour - T0), 24) * 0.9972695663;

        // Split decimal hours into three integers: hour, minute and second
        double minute_d = (B - ((int) B)); // Get decimal value of hours
        minute_d *= 60;
        double second_d = (minute_d - ((int) minute_d)); // Get decimal value of minutes
        second_d *= 60;

        int hour = (int) B; // truncate integer part
        int minute = (int) minute_d; // truncate integer part
        int second = (int) Math.round(second_d);

        // Handle special case for rounding error
        if (second == 60) {
            second = 59;
        }

        return LocalTime.of(hour, minute, second);
    }

    static RiseAndSet riseAndSet(EquatorialCoordinate eqCoord, GeographicCoordinate geoCoord, int gDay, int gMonth, int gYear) throws NeverRaisesException, NeverSetsException {
        double alpha_deg = eqCoord.rightAscension;
        double delta_rad = Math.toRadians(eqCoord.declination);
        double phi_rad = Math.toRadians(geoCoord.latitude);

        double hour_angle_cosine = -(VERTICAL_SHIFT_SINE + Math.sin(phi_rad) * Math.sin(delta_rad)) / (Math.cos(phi_rad) * Math.cos(delta_rad));

        if (hour_angle_cosine > 1) {
            // The Sun never rises!
            throw new NeverRaisesException();
        } else if (hour_angle_cosine < -1) {
            // The Sun never sets!
            throw new NeverSetsException();
        }

        double Hour_angle_hours = Math.toDegrees(Math.acos(hour_angle_cosine)) / 15;
        double rise_LST = Helper.modulo((alpha_deg - Hour_angle_hours), 24);
        double set_LST = Helper.modulo((alpha_deg + Hour_angle_hours), 24);

        // Convert from LST to GST
        double longitude_deg = geoCoord.longitude / 15;
        double rise_GST_hour = Helper.modulo((rise_LST - longitude_deg), 24);
        double set_GST_hour = Helper.modulo((set_LST - longitude_deg), 24);

        // Convert from GST to UT
        LocalDate date = LocalDate.of(gYear, gMonth, gDay);
        LocalTime rise_UT = GSTToUT(rise_GST_hour, gDay, gMonth, gYear);
        LocalTime set_UT = GSTToUT(set_GST_hour, gDay, gMonth, gYear);

        return new RiseAndSet(LocalDateTime.of(date, rise_UT), LocalDateTime.of(date, set_UT));
    }

    static double meanAnomaly(double gDay, int gMonth, int gYear) {
        // TODO: Use TT (Terrestrial Time) for better accuracy when calculating the position of the Sun

        // The epoch is January 0.0 2010
        double D = Helper.GreenwichToJulianDate(gDay, gMonth, gYear) - Helper.GreenwichToJulianDate(0, 1, 2010);
        double N = Helper.modulo(((360 / 365.242191) * D), 360);

        double epsilon = 279.557208;
        double omega = 283.112438;
        double M = Helper.modulo((N + epsilon - omega), 360);

        return M;
    }

    static double eclipticLongitude(double gDay, int gMonth, int gYear) {
        // Calculate the position of the Sun at a specified date. The epoch is January 0.0 2010.
        double D = Helper.GreenwichToJulianDate(gDay, gMonth, gYear) - Helper.GreenwichToJulianDate(0, 1, 2010);
        double N = Helper.modulo(((360 / 365.242191) * D), 360);

        double epsilon = 279.557208;
        double omega = 283.112438;
        double e = 0.016705;
        double M = Helper.modulo((N + epsilon - omega), 360);

        double E = (360 / Math.PI) * e * Math.sin(Math.toRadians(M));
        double ecliptic_longitude = Helper.modulo((N + E + epsilon), 360); // lambda

        return ecliptic_longitude;
    }

    static EquatorialCoordinate sunPositionAtDay(double gDay, int gMonth, int gYear) {
        double sun_ecliptic_longitude = eclipticLongitude(gDay, gMonth, gYear);
        EclipticCoordinate ecl_coord = new EclipticCoordinate(0, sun_ecliptic_longitude);
        return ecl_coord.toEquatorial(gDay, gMonth, gYear);
    }

    static RiseAndSet sunriseAndSunsetTimes(GeographicCoordinate geo_coord, LocalDate date) throws NeverRaisesException, NeverSetsException {
        // Calculates the approximate UTC times of sunrise and sunset (at sea level) given by a geographical location
        // on Earth and a date. The returned times should be correct within a few minutes of the real times. It is not
        // intended to be an exact calculation.
        double day = ((double) date.getDayOfMonth()) + 0.5;
        EquatorialCoordinate sun_pos = sunPositionAtDay(day, date.getMonthValue(), date.getYear());
        return riseAndSet(sun_pos, geo_coord, date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    static class GeographicCoordinate {
        public final double latitude;
        public final double longitude;

        private GeographicCoordinate(double latitude, double longitude) {
            // Set as private constructor to disallow direct instantiation
            this.latitude = latitude;
            this.longitude = longitude;
        }

        static GeographicCoordinate defaultCoordinate() {
            return new GeographicCoordinate(0.0, 0.0);
        }

        static GeographicCoordinate fromDecimalDegrees(double latitude, double longitude) { return new GeographicCoordinate(latitude, longitude); }

        static GeographicCoordinate fromSexagesimalDegrees(double northDegrees, double northArcMinutes, double northArcSeconds, double westDegrees, double westArcMinutes, double westArcSeconds) {
            // Convert sexagesimal degrees to decimal degrees
            double latitude = northDegrees + northArcMinutes/60.0d + northArcSeconds/3600.0d;
            double longitude = westDegrees + westArcMinutes/60.0d + westArcSeconds/3600.0d;
            return GeographicCoordinate.fromDecimalDegrees(latitude, longitude);
        }

        public String toString() {
            var fmt = new DecimalFormat("0.0#####", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
            return String.format(Locale.ENGLISH, "%s, %s", fmt.format(latitude), fmt.format(longitude));
        }
    }

    static class RiseAndSet {
        public final LocalDateTime riseUTCTime;
        public final LocalDateTime setUTCTime;

        public RiseAndSet(LocalDateTime riseUTCTime, LocalDateTime setUTCTime) {
            this.riseUTCTime = riseUTCTime;
            this.setUTCTime = setUTCTime;
        }
    }

    static class NeverRaisesException extends Exception {
        public NeverRaisesException() {
            super("The celestial object never raises above the horizon!");
        }
    }

    static class NeverSetsException extends Exception {
        public NeverSetsException() {
            super("The celestial object never sets below the horizon!");
        }
    }
}