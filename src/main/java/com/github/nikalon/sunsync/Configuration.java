package com.github.nikalon.sunsync;

import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.plugin.Plugin;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate;

class Configuration {
    private static final long SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT = 5L;
    private static final long SYNCHRONIZATION_INTERVAL_MIN_VALUE = 1L;
    private static final long SYNCHRONIZATION_INTERVAL_MAX_VALUE = 1800L;
    private static final boolean DEBUG_MODE_DEFAULT = false;
    private static final Pattern REGEX_DECIMAL_DEGREES = Pattern.compile("(?<latitude>-?\\d+(?:\\.\\d+)?),?\\s+(?<longitude>-?\\d+(?:\\.\\d+)?)");
    private static final Pattern REGEX_SEXAGESIMAL_DEGREES = Pattern.compile("(?<LatDeg>\\d+)°(?: *(?<LatArcMin>\\d+)')?(?: *(?<LatArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LatDirection>[NS]),?\\s+(?<LonDeg>\\d+)°(?: *(?<LonArcMin>\\d+)')?(?: *(?<LonArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LonDirection>[EW])");

    private Logger logger;
    private String location;
    private long syncIntervalSeconds;
    private boolean debugMode;
    private GeographicCoordinate geographicCoordinates;
    private Hashtable<String, GeographicCoordinate> worldRegions;

   Configuration(Logger logger, InputStream regionsFileStream) {
        this.logger = logger;
        this.worldRegions = new Hashtable<>();

        // Parse regions file
        var pattern = Pattern.compile("(?<region>\\w+),Point\\((?<longitude>-?\\d+(?:\\.\\d+)?) (?<latitude>-?\\d+(?:\\.\\d+)?)\\)");
        var regionsFile = readEntireFile(regionsFileStream);
        var lines = regionsFile.split("\n");
        var totalParsedRegions = 0;
        for (String line : lines) {
            var matcher = pattern.matcher(line);
            if (matcher.find()) {
                String region = matcher.group("region");
                try {
                    double latitude = Double.parseDouble(matcher.group("latitude"));
                    double longitude = Double.parseDouble(matcher.group("longitude"));
                    GeographicCoordinate coordinates = GeographicCoordinate.fromDecimalDegrees(latitude, longitude);

                    this.worldRegions.put(region, coordinates);
                    totalParsedRegions += 1;
                } catch (NumberFormatException ignored) {
                    this.logger.warning(String .format("Parse error when processing geographic coordinates for \"%s\" region ", region));
                }
            }
        }
        this.logger.info(String.format("Loaded %d regions from", totalParsedRegions));

        this.location = "auto";
        this.geographicCoordinates = parseLocationOption(this.location);
        this.syncIntervalSeconds = SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT;
        this.debugMode = DEBUG_MODE_DEFAULT;
    }

    private String readEntireFile(InputStream fileInputStream) {
        try {
            var data = fileInputStream.readAllBytes();
            return new String(data);
        } catch (IOException e) {
            return "";
        }
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
            var systemRegion = System.getProperty("user.country");
            var defaultCoordinates = GeographicCoordinate.defaultCoordinate();
            return this.worldRegions.getOrDefault(systemRegion, defaultCoordinates);
        } else {
            // Try parse as decimal degrees
            var decimalMatcher = REGEX_DECIMAL_DEGREES.matcher(location);
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
            var sexagesimalMatcher = REGEX_SEXAGESIMAL_DEGREES.matcher(location);
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
            var coordinates = parseLocationOption(location);
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