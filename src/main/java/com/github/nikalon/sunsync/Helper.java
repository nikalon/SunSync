package com.github.nikalon.sunsync;

public class Helper {
    // Common functions used when performing astronomical calculations

    static double modulo(double dividend, double divisor) {
        // Modulo defined as floor division, where the sign is determined by the divisor. I couldn't be able find a
        // similar method in the standard library, so I write my own.
        return dividend - divisor * Math.floor(dividend / divisor);
    }

    static double GreenwichToJulianDate(double gDay, int gMonth, int gYear) {
        int monthP = gMonth;
        int yearP = gYear;

        if (gMonth == 1 || gMonth == 2) {
            monthP = gMonth + 12;
            yearP = gYear - 1;
        }

        // Omit 1582 October 15 date check because we only want to convert dates from 2022 January 01 onwards
        int A = (int) (yearP / 100.0);    // truncate integer part
        int B = 2 - A + ((int) (A / 4.0)); // truncate integer part

        int C;
        if (yearP < 0) C = (int) ((365.25 * yearP) - 0.75); // truncate integer part
        else           C = (int) (365.25 * yearP);          // truncate integer part

        int D = (int) (30.6001 * (monthP + 1)); // truncate integer part
        return B + C + D + gDay + 1720994.5;
    }
}
