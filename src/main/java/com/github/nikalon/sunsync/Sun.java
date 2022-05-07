package com.github.nikalon.sunsync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

class Sun {
    // Acronyms used:
    // LST  Local Sidereal Time
    // GST  Greenwich Sidereal Time
    // UT   Universal Time (this program will assume UTC = UT)

    private static final double VERTICAL_SHIFT_SINE = 0.00989061960670350512825686013281; // sine of 0.5667 degrees

    private Sun() {} // Disallow instantiation

    private static double modulo(double dividend, double divisor) {
        // Modulo defined as floor division, where the sign is determined by the divisor. I couldn't find a similar
        // method in the standard library, so I write my own.
        return dividend - divisor * Math.floor(dividend / divisor);
    }

    static double GreenwichToJulianDate(double gDay, int gMonth, int gYear) {
        int month_p = gMonth;
        int year_p = gYear;

        if (gMonth == 1 || gMonth == 2) {
            month_p = gMonth + 12;
            year_p = gYear - 1;
        }

        // Omit 1582 October 15 date check because we only want to convert dates from 2022 January 01 onwards
        int A = (int) (year_p / 100.0);    // truncate integer part
        int B = 2 - A + ((int) (A / 4.0)); // truncate integer part

        int C;
        if (year_p < 0) C = (int) ((365.25 * year_p) - 0.75); // truncate integer part
        else            C = (int) (365.25 * year_p);          // truncate integer part

        int D = (int) (30.6001 * (month_p + 1)); // truncate integer part
        return B + C + D + gDay + 1720994.5;
    }

    static LocalTime GSTToUT(double GSTHour, int gDay, int gMonth, int gYear) {
        double julian_date = GreenwichToJulianDate(gDay, gMonth, gYear);
        double S = julian_date - 2451545.0;
        double T = S / 36525.0;
        double T0 = modulo((6.697374558 + (2400.051336 * T) + (0.000025862 * Math.pow(T, 2))), 24);
        double B = modulo((GSTHour - T0), 24) * 0.9972695663;

        // Split decimal hours into three integers: hour, minute and second
        double minute_d = (B - ((int) B)); // Get decimal value of hours
        minute_d *= 60;
        double second_d = (minute_d - ((int) minute_d)); // Get decimal value of minutes
        second_d *= 60;

        int hour = (int) B; // truncate integer part
        int minute = (int) minute_d; // truncate integer part
        int second = (int) Math.round(second_d);

        return LocalTime.of(hour, minute, second);
    }

    static RiseAndSet riseAndSet(EquatorialCoordinate eqCoord, GeographicCoordinate geoCoord, int gDay, int gMonth, int gYear) throws NeverRaisesException, NeverSetsException {
        double alpha_deg = eqCoord.right_ascension;
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
        double rise_LST = modulo((alpha_deg - Hour_angle_hours), 24);
        double set_LST = modulo((alpha_deg + Hour_angle_hours), 24);

        // Convert from LST to GST
        double longitude_deg = geoCoord.longitude / 15;
        double rise_GST_hour = modulo((rise_LST - longitude_deg), 24);
        double set_GST_hour = modulo((set_LST - longitude_deg), 24);

        // Convert from GST to UT
        LocalDate date = LocalDate.of(gYear, gMonth, gDay);
        LocalTime rise_UT = GSTToUT(rise_GST_hour, gDay, gMonth, gYear);
        LocalTime set_UT = GSTToUT(set_GST_hour, gDay, gMonth, gYear);

        return new RiseAndSet(LocalDateTime.of(date, rise_UT), LocalDateTime.of(date, set_UT));
    }

    static EquatorialCoordinate sunPositionAtDay(double gDay, int gMonth, int gYear) {
        // The epoch is January 0.0 2010
        double D = GreenwichToJulianDate(gDay, gMonth, gYear) - GreenwichToJulianDate(0, 1, 2010);
        double N = modulo(((360 / 365.242191) * D), 360);

        // TODO: Calculate the following constants according to the current Julian date to increase accuracy. Using pre-computed values for now...
        double epsilon = 279.557208;
        double omega = 283.112438;
        double e = 0.016705;
        double M = modulo((N + epsilon - omega), 360);

        double E = (360 / Math.PI) * e * Math.sin(Math.toRadians(M));
        double sun_longitude = modulo((N + E + epsilon), 360); // lambda

        EclipticCoordinate ecl_coord = new EclipticCoordinate(0, sun_longitude);
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

        static GeographicCoordinate from(double latitude, double longitude) throws InvalidGeographicCoordinateException {
            if (latitude < -90.0 || latitude > 90.0) {
                throw new InvalidGeographicCoordinateException("Invalid latitude value: " + latitude + ". Only decimal degrees between -90.0 and 90.0 are valid.");
            }

            if (longitude < -90.0 || longitude > 90.0) {
                throw new InvalidGeographicCoordinateException("Invalid longitude value: " + latitude + ". Only decimal degrees between -90.0 and 90.0 are valid.");
            }

            return new GeographicCoordinate(latitude, longitude);
        }

        public String toString() {
            return String.format("(%.2f, %.2f)", latitude, longitude);
        }

        static class InvalidGeographicCoordinateException extends Exception {
            public InvalidGeographicCoordinateException(String message) {
                super(message);
            }
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

    private static class EquatorialCoordinate {
        // Does not need data validation because it is only used in this class
        public final double right_ascension;
        public final double declination;

        public EquatorialCoordinate(double right_ascension, double declination) {
            this.right_ascension = right_ascension;
            this.declination = declination;
        }
    }

    private static class EclipticCoordinate {
        // Does not need data validation because it is only used in this class
        public final double latitude;
        public final double longitude;

        public EclipticCoordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public EquatorialCoordinate toEquatorial(double gDay, int gMonth, int gYear) {
            double ecl_lat_rad = Math.toRadians(this.latitude);
            double ecl_long_rad = Math.toRadians(this.longitude);

            // Mean obliquity of the ecliptic
            double T = (GreenwichToJulianDate(gDay, gMonth, gYear) - 2451545.0) / 36525.0;

            // Obliquity
            double DE = (46.815 * T - 0.0006 * Math.pow(T, 2) + 0.00181 * Math.pow(T, 3)) / 3600.0;
            double obliquity_ecliptic_rad = Math.toRadians(23.439292 - DE);

            // Conversion
            double delta_rad = Math.asin(Math.sin(ecl_lat_rad) * Math.cos(obliquity_ecliptic_rad) +
                                         Math.cos(ecl_lat_rad) * Math.sin(obliquity_ecliptic_rad) * Math.sin(ecl_long_rad));
            double delta_deg = Math.toDegrees(delta_rad);
            double y = Math.sin(ecl_long_rad) * Math.cos(obliquity_ecliptic_rad) -
                       Math.tan(ecl_lat_rad) * Math.sin(obliquity_ecliptic_rad);
            double x = Math.cos(ecl_long_rad);
            double alpha_deg = Math.toDegrees(Math.atan(y / x));

            // Fix arctangent ambiguity by adjusting it to the correct angle. Can this be done more cleanly?
            if (x < 0 && y >= 0) {
                // Second trigonometric quadrant
                alpha_deg = 180 + alpha_deg;
            } else if (x < 0 && y < 0) {
                // Third trigonometric quadrant
                alpha_deg = 180 + alpha_deg;
            } else if (x > 0 && y < 0) {
                // Fourth trigonometric quadrant
                alpha_deg = 360 + alpha_deg;
            }

            double alpha_hours = alpha_deg / 15.0;
            return new EquatorialCoordinate(alpha_hours, delta_deg);
        }
    }
}
