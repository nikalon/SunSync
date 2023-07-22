package com.github.nikalon.sunsync;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate;

class Configuration {
    private static final long SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT = 5L;
    private static final long SYNCHRONIZATION_INTERVAL_MIN_VALUE = 1L;
    private static final long SYNCHRONIZATION_INTERVAL_MAX_VALUE = 1800L;
    private static final boolean DEBUG_MODE_DEFAULT = false;
    private static final Pattern REGEX_DECIMAL_DEGREES = Pattern.compile("(?<latitude>-?\\d+(?:\\.\\d+)?),?\\s+(?<longitude>-?\\d+(?:\\.\\d+)?)");
    private static final Pattern REGEX_SEXAGESIMAL_DEGREES = Pattern.compile("(?<LatDeg>\\d+)°(?: *(?<LatArcMin>\\d+)')?(?: *(?<LatArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LatDirection>[NS]),?\\s+(?<LonDeg>\\d+)°(?: *(?<LonArcMin>\\d+)')?(?: *(?<LonArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LonDirection>[EW])");

    private String location;
    private long syncIntervalSeconds;
    private boolean debugMode;
    private GeographicCoordinate geographicCoordinates;

   Configuration(Logger logger) {
        this.location = "auto";
        this.geographicCoordinates = parseLocationOption(this.location);
        this.syncIntervalSeconds = SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT;
        this.debugMode = DEBUG_MODE_DEFAULT;
    }

    static long getSyncIntervalLowestValidValue() {
        return SYNCHRONIZATION_INTERVAL_MIN_VALUE;
    }

    static long getSyncIntervalHighestValidValue() {
        return SYNCHRONIZATION_INTERVAL_MAX_VALUE;
    }

    String getLocation() {
        return this.location;
    }

    GeographicCoordinate getGeographicCoordinates() {
        return this.geographicCoordinates;
    }

    boolean setLocation(String newLocation) {
        if (isValidLocation(newLocation)) {
            this.location = newLocation;
            this.geographicCoordinates = parseLocationOption(newLocation);
            return true;
        } else {
            return false;
        }
    }

    private GeographicCoordinate parseLocationOption(String location) {
        if (location.equals("auto")) {
            // Automatically detect geographic coordinates based on some heuristics
            String systemRegion = System.getProperty("user.country");
            if (systemRegion == null) return GeographicCoordinate.defaultCoordinate();

            GeographicCoordinate defaultCoordinates = GeographicCoordinate.defaultCoordinate();
            return Regions.REGIONS.getOrDefault(systemRegion, defaultCoordinates);
        } else {
            // Try parse as decimal degrees
            Matcher decimalMatcher = REGEX_DECIMAL_DEGREES.matcher(location);
            if (decimalMatcher.matches()) {
                try {
                    double latitude = Double.parseDouble(decimalMatcher.group("latitude"));
                    double longitude = Double.parseDouble(decimalMatcher.group("longitude"));
                    return GeographicCoordinate.fromDecimalDegrees(latitude, longitude);
                } catch (NumberFormatException ignored) {
                    // Ignored
                }
            }

            // Try parse as sexagesimal degrees
            Matcher sexagesimalMatcher = REGEX_SEXAGESIMAL_DEGREES.matcher(location);
            if (sexagesimalMatcher.matches()) {
                try {
                    // Latitude
                    String latDirection = sexagesimalMatcher.group("LatDirection");
                    double latDegrees = Double.parseDouble(sexagesimalMatcher.group("LatDeg"));
                    double latArcMin = 0.0f;
                    double latArcSec = 0.0f;

                    String LatArcMin = sexagesimalMatcher.group("LatArcMin");
                    if (LatArcMin != null) {
                        latArcMin = Double.parseDouble(LatArcMin);
                    }

                    String LatArcSec = sexagesimalMatcher.group("LatArcSec");
                    if (LatArcSec != null) {
                        latArcSec = Double.parseDouble(LatArcSec);
                    }

                    if (latDirection.equals("S")) {
                        // South latitude
                        latDegrees *= -1;
                        latArcMin *= -1;
                        latArcSec *= -1;
                    }

                    // Longitude
                    String lonDirection = sexagesimalMatcher.group("LonDirection");
                    double lonDegrees = Double.parseDouble(sexagesimalMatcher.group("LonDeg"));
                    double lonArcMin = 0.0f;
                    double lonArcSec = 0.0f;

                    String LonArcMin = sexagesimalMatcher.group("LonArcMin");
                    if (LonArcMin != null) {
                        lonArcMin = Double.parseDouble(LonArcMin);
                    }

                    String LonArcSec = sexagesimalMatcher.group("LonArcSec");
                    if (LonArcSec != null) {
                        lonArcSec = Double.parseDouble(LonArcSec);
                    }

                    if (lonDirection.equals("W")) {
                        // West longitude
                        lonDegrees *= -1;
                        lonArcMin *= -1;
                        lonArcSec *= -1;
                    }

                    return GeographicCoordinate.fromSexagesimalDegrees(latDegrees, latArcMin, latArcSec,
                            lonDegrees, lonArcMin, lonArcSec);
                } catch (NumberFormatException ignored) {
                    // Ignored
                }
            }
        }

        return null;
    }

    private boolean isValidLocation(String location) {
        if (location.equals("auto")) {
            return true;
        } else {
            GeographicCoordinate coordinates = parseLocationOption(location);
            if (coordinates == null) {
                return false;
            }
            if (coordinates.latitude < -90.0f || coordinates.latitude > 90.0f) {
                return false;
            }
            if (coordinates.longitude < -180.0f || coordinates.longitude > 180.0f) {
                return false;
            }

            return true;
        }
    }

    long getSynchronizationIntervalSeconds() {
        return syncIntervalSeconds;
    }

    boolean setSynchronizationIntervalSeconds(long seconds) {
        if (seconds < SYNCHRONIZATION_INTERVAL_MIN_VALUE || seconds > SYNCHRONIZATION_INTERVAL_MAX_VALUE) {
            return false;
        } else {
            this.syncIntervalSeconds = seconds;
            return true;
        }
    }

    boolean getDebugMode() {
        return debugMode;
    }

    void setDebugMode(boolean mode) {
        this.debugMode = mode;
    }
}