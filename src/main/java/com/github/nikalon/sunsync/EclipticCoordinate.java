package com.github.nikalon.sunsync;

class EclipticCoordinate {
    // WARNING! No data validation is performed
    public final double latitude;
    public final double longitude;

    public EclipticCoordinate(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public EquatorialCoordinate toEquatorial(double gDay, int gMonth, int gYear) {
        double eclLatRad = Math.toRadians(this.latitude);
        double eclLongRad = Math.toRadians(this.longitude);

        // Mean obliquity of the ecliptic
        double T = (Helper.GreenwichToJulianDate(gDay, gMonth, gYear) - 2451545.0) / 36525.0;

        // Obliquity
        double DE = (46.815 * T - 0.0006 * Math.pow(T, 2) + 0.00181 * Math.pow(T, 3)) / 3600.0;
        double obliquityEclipticRad = Math.toRadians(23.439292 - DE);

        // Conversion
        double deltaRad = Math.asin(Math.sin(eclLatRad) * Math.cos(obliquityEclipticRad) +
                           Math.cos(eclLatRad) * Math.sin(obliquityEclipticRad) * Math.sin(eclLongRad));
        double deltaDeg = Math.toDegrees(deltaRad);
        double y = Math.sin(eclLongRad) * Math.cos(obliquityEclipticRad) -
                   Math.tan(eclLatRad) * Math.sin(obliquityEclipticRad);
        double x = Math.cos(eclLongRad);
        double alphaDeg = Math.toDegrees(Math.atan(y / x));

        // Fix arctangent ambiguity by adjusting it to the correct angle
        if (x < 0 && y >= 0) {
            // Second trigonometric quadrant
            alphaDeg = 180 + alphaDeg;
        } else if (x < 0 && y < 0) {
            // Third trigonometric quadrant
            alphaDeg = 180 + alphaDeg;
        } else if (x > 0 && y < 0) {
            // Fourth trigonometric quadrant
            alphaDeg = 360 + alphaDeg;
        }

        double alphaHours = alphaDeg / 15.0;
        return new EquatorialCoordinate(alphaHours, deltaDeg);
    }
}