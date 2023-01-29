package com.github.nikalon.sunsync;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent.SkipReason;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate;
import com.github.nikalon.sunsync.Sun.NeverRaisesException;
import com.github.nikalon.sunsync.Sun.NeverSetsException;
import com.github.nikalon.sunsync.Sun.RiseAndSet;

public class SunSync extends JavaPlugin implements Runnable, Listener {
    private static final long ONE_SECOND_IN_MINECRAFT_TICKS = 20L;
    private static final long MINECRAFT_DAY_LENGTH_TICKS    = 14000;
    private static final long MINECRAFT_NIGHT_LENGTH_TICKS  = 10000;

    // Sunrise time start as seen from the game. We cannot start from day -1000, so we use the next valid sunrise time.
    private static final long MINECRAFT_SUNRISE_START_TICKS = 23000;

    // Sunset time start as seen from the game
    private static final long MINECRAFT_SUNSET_START_TICKS  = 37000;

    private static final long MINECRAFT_MIDDAY_TICKS        = 30000;
    private static final long MINECRAFT_MIDNIGHT_TICKS      = 42000;

    private Configuration configuration;
    private Clock systemClock;
    private boolean syncPaused;

    private BukkitTask task;
    private Logger logger;

    private LocalDate lastUpdated; // Used to cache sunrise and sunset calculations for a day
    private RiseAndSet todayEvents;
    private RiseAndSet yesterdayEvents;
    private RiseAndSet tomorrowEvents;

    // Parameters used in /timesync command
    private Hashtable<String, ParameterParser> commandParameters;
    private List<String> commandTabCompletion;

    private void startTimeSynchronization() {
        stopTimeSynchronization();
        task = Bukkit.getScheduler().runTaskTimer(this, this, 0, configuration.getSynchronizationIntervalSeconds() * ONE_SECOND_IN_MINECRAFT_TICKS);
        syncPaused = false;
        debugLog("Started/Restarted time synchronization");
    }

    private void stopTimeSynchronization() {
        syncPaused = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        debugLog("Time synchronization stopped");
    }

    private void synchronizeTimeNow() {
        if (syncPaused) {
            synchronizeTimeOnce();
        } else {
            startTimeSynchronization();
        }
    }

    private void synchronizeTimeOnce() {
        // Whenever the term "event" is used it means either the sunrise or sunset in the real world

        LocalDateTime now = LocalDateTime.now(systemClock);
        debugLog(String.format("The time is %s (UTC)", now.toLocalTime()));

        try {
            if (lastUpdated == null || now.toLocalDate().isAfter(lastUpdated)) {
                // Cache calculations until 23:59:59 (UTC)
                lastUpdated = now.toLocalDate();
                todayEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.toLocalDate());
                yesterdayEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.minusDays(1).toLocalDate());
                tomorrowEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.plusDays(1).toLocalDate());

                debugLog(String.format("Yesterday the Sun rose at %s (UTC), set at %s (UTC)", yesterdayEvents.riseUTCTime, yesterdayEvents.setUTCTime));
                debugLog(String.format("Today's events -> rise at %s (UTC), set at %s (UTC)", todayEvents.riseUTCTime, todayEvents.setUTCTime));
                debugLog(String.format("Tomorrow's events -> rise at %s (UTC), set at %s (UTC)", tomorrowEvents.riseUTCTime, tomorrowEvents.setUTCTime));
            }
        } catch (NeverRaisesException e) {
            Bukkit.getWorlds().forEach((world) -> world.setTime(MINECRAFT_MIDNIGHT_TICKS)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
            logger.warning(String.format("The Sun will not rise today. Setting game time to midnight (Minecraft time %d).", MINECRAFT_MIDNIGHT_TICKS));
            debugLog(String.format("All worlds synchronized to Minecraft time %d", MINECRAFT_MIDNIGHT_TICKS));
            return;
        } catch (NeverSetsException e) {
            Bukkit.getWorlds().forEach((world) -> world.setTime(MINECRAFT_MIDDAY_TICKS)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
            logger.warning(String.format("The Sun will not set today. Setting game time to midday (Minecraft time %d).", MINECRAFT_MIDDAY_TICKS));
            debugLog(String.format("All worlds synchronized to Minecraft time %d", MINECRAFT_MIDDAY_TICKS));
            return;
        }

        // Error condition reached. The Sun will not rise or will not set today. Skipping time synchronization...
        if (yesterdayEvents == null || todayEvents == null || tomorrowEvents == null) return;

        // Figures out if it's daytime or nighttime right now
        LocalDateTime last_event_time;
        LocalDateTime next_event_time;
        boolean is_daytime;
        if (now.isBefore(todayEvents.riseUTCTime)) {
            // Nighttime. Last event was yesterday's sunset. Next event is today's sunrise.
            is_daytime = false;
            last_event_time = yesterdayEvents.setUTCTime;
            next_event_time = todayEvents.riseUTCTime;
        } else if (now.isAfter(todayEvents.setUTCTime)) {
            // Nighttime. Last event was today's sunset. Next event is tomorrow's sunrise.
            is_daytime = false;
            last_event_time = todayEvents.setUTCTime;
            next_event_time = tomorrowEvents.riseUTCTime;
        } else {
            // Daytime. Last event was today's sunrise. Next event is today's sunset.
            is_daytime = true;
            last_event_time = todayEvents.riseUTCTime;
            next_event_time = todayEvents.setUTCTime;
        }

        double event_interval_duration = Duration.between(last_event_time, next_event_time).getSeconds();
        // Time elapsed since the last event
        double delta_time = Duration.between(last_event_time, now).getSeconds();

        // Apply a linear interpolation between the last and next event times. Then, convert it into a Minecraft time.
        long minecraft_time;
        if (is_daytime) {
            minecraft_time = (long) ((MINECRAFT_DAY_LENGTH_TICKS / event_interval_duration) * delta_time + MINECRAFT_SUNRISE_START_TICKS);
        } else {
            minecraft_time = (long) ((MINECRAFT_NIGHT_LENGTH_TICKS / event_interval_duration) * delta_time + MINECRAFT_SUNSET_START_TICKS);
        }

        Bukkit.getWorlds().forEach((world) -> world.setTime(minecraft_time)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
        debugLog(String.format("All worlds synchronized to Minecraft time %d", minecraft_time));
    }

    private void saveConfiguration() {
        var conFile = getConfig();

        // Debug mode
        conFile.set("debug_mode", configuration.getDebugMode());

        // Location
        conFile.set("location", configuration.getLocation());

        // Synchronization interval
        conFile.set("synchronization_interval_seconds", configuration.getSynchronizationIntervalSeconds());

        saveConfig(); // Save to config.yml
        debugLog("Configuration saved to config.yml");
    }

    @Override
    public void onLoad() {
        // Setup /timesync command
        commandParameters = new Hashtable<>();
        commandParameters.put("location", (sender, args) -> parseLocationCommand(sender, args));
        commandParameters.put("syncIntervalSec", (sender, args) -> parseSyncIntervalSecCommand(sender, args));
        commandParameters.put("clock", (sender, args) -> parseClockCommand(sender, args));
        commandParameters.put("debugMode", (sender, args) -> parseDebugModeCommand(sender, args));
        commandParameters.put("continue", (sender, args) -> parseContinueCommand(sender));
        commandParameters.put("pause", (sender, args) -> parsePauseCommand(sender));

        commandTabCompletion = new ArrayList<String>();
        for (String key : commandParameters.keySet()) {
            commandTabCompletion.add(key);
        }

        this.logger = getLogger();

        // Load configuration
        saveDefaultConfig();
        FileConfiguration configFile = getConfig();
        this.configuration = new Configuration();

        // Debug mode
        Object debugVal = configFile.get("debug_mode");
        if (debugVal != null && debugVal instanceof Boolean) {
            configuration.setDebugMode((Boolean) debugVal);
        } else {
            logger.severe("\"debug_mode\" value in config.yml is invalid, using default value. Please, use a boolean value (true or false).");
        }

        if (configuration.getDebugMode()) {
            logger.warning("debug mode is enabled. To disable debug mode set the option \"debug_mode\" to false in config.yml and restart the server.");
        }

        // Geographic coordinates
        String defaultValue = "auto";
        String location = configFile.getString("location", defaultValue);
        if (!configuration.setLocation(location)) {
            logger.severe("\"location\" value in config.yml is invalid, using default coordinates. Please, set a valid geographic coordinate or \"auto\"");
        }
        debugLog(String.format("Using geographic coordinates: %s", configuration.getGeographicCoordinates()));

        // Synchronization interval
        long sync_interval = configFile.getLong("synchronization_interval_seconds", -1);
        if (! configuration.setSynchronizationIntervalSeconds(sync_interval)) {
            logger.log(Level.SEVERE, String.format("\"synchronization_interval_seconds\" value in config.yml is invalid, using default value. Please, use integer values between %d and %d.", Configuration.getSyncIntervalLowestValidValue(), Configuration.getSyncIntervalHighestValidValue()));
        }
        debugLog(String.format("Synchronization interval set to %d seconds", configuration.getSynchronizationIntervalSeconds()));
    }

    @Override
    public void onEnable() {
        systemClock = Clock.systemUTC();
        syncPaused = false;

        // TODO: There should be a way to "fake" this setting for each connected player without actually changing the game rule
        Bukkit.getWorlds().forEach(world -> world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false));
        logger.warning("While this plugin is active the game rule doDaylightCycle will be set to false");

        var command = getCommand("timesync");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        startTimeSynchronization();
    }

    @Override
    public void onDisable() {
        stopTimeSynchronization();
        HandlerList.unregisterAll((Listener) this);
        saveConfiguration();
    }

    @Override
    public void run() {
        synchronizeTimeOnce();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // TODO: Use colors!
        // Command /timesync
        if (args.length == 0) return false; // Show usage (set in plugin.yml)

        var parameter = args[0];
        var parser = commandParameters.get(parameter);
        if (parser == null) {
            sender.sendMessage(String.format("Unknown parameter \"%s\"", parameter));
        } else {
            parser.parse(sender, Arrays.asList(args).subList(1, args.length));
        }

        return true; // Do not show usage
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // TODO: Incomplete. Add suggestions for each argument
        if (args.length == 1)   return commandTabCompletion;
        else                    return null;
    }

    private void parseLocationCommand(CommandSender sender, List<String> args) {
        if (args.size() == 0) {
            // Get location
            sender.sendMessage(String.format("Current location is %s", configuration.getGeographicCoordinates()));
        } else {
            // Set location
            String location = String.join(" ", args);
            if (configuration.setLocation(location)) {
                // Force to recalculate sunrise and sunset times
                lastUpdated = null;
                todayEvents = null;
                yesterdayEvents = null;
                tomorrowEvents = null;

                synchronizeTimeNow();
                sender.sendMessage(String.format("Location set to %s", configuration.getGeographicCoordinates()));
            } else {
                sender.sendMessage("Invalid coordinates. Please, set a valid geographic coordinate or \"auto\"");
            }
        }
    }

    private void parseSyncIntervalSecCommand(CommandSender sender, List<String> args) {
        String value = null;
        if (args.size() >= 1) value = args.get(0);

        if (value == null) {
            sender.sendMessage(String.format("Synchronization interval is set to %d seconds", configuration.getSynchronizationIntervalSeconds()));
        } else {
            long syncIntervalSec;
            try {
                syncIntervalSec = Long.parseLong(value);
                if (configuration.setSynchronizationIntervalSeconds(syncIntervalSec)) {
                    if (! syncPaused) {
                        startTimeSynchronization();
                    }
                    sender.sendMessage(String.format("Synchronization interval set to %d seconds", syncIntervalSec));
                } else {
                    sender.sendMessage(String.format("Invalid value. Please, enter a integer value between %d and %d", Configuration.getSyncIntervalLowestValidValue(), Configuration.getSyncIntervalHighestValidValue()));
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(String.format("Invalid value. Please, enter a integer value between %d and %d", Configuration.getSyncIntervalLowestValidValue(), Configuration.getSyncIntervalHighestValidValue()));
            }
        }
    }

    private void parseDebugModeCommand(CommandSender sender, List<String> args) {
        String value = null;
        if (args.size() >= 1) value = args.get(0);

        if (value == null) {
            if (configuration.getDebugMode())   sender.sendMessage("Debug mode is enabled");
            else                                sender.sendMessage("Debug mode is disabled");
        } else {
            var newDebugMode = false;
            if (value.equals("true")) {
                newDebugMode = true;
            } else if (value.equals("false")) {
                newDebugMode = false;
            } else {
                sender.sendMessage("Invalid value. Please, enter a boolean value (true|false)");
                return;
            }

            if (newDebugMode && configuration.getDebugMode()) {
                sender.sendMessage("Debug mode is already enabled!");
            } else if (newDebugMode == false && configuration.getDebugMode() == false) {
                sender.sendMessage("Debug mode is already disabled!");
            } else {
                configuration.setDebugMode(newDebugMode);
                if (configuration.getDebugMode()) {
                    sender.sendMessage("Debug mode enabled");
                } else {
                    sender.sendMessage("Debug mode disabled");
                }
            }
        }
    }

    private void parseClockCommand(CommandSender sender, List<String> args) {
        String value = null;
        if (args.size() >= 1) value = args.get(0);

        if (value == null) {
            var time = LocalTime.now(systemClock);
            sender.sendMessage(String.format("The system time is %s (UTC)", time));
        } else {
            // Two possible values: "default" or a time in the format "HH:MM" or "HH:MM:SS"
            if (value.equals("default")) {
                // Resets the fake system clock to the actual system clock
                systemClock = Clock.systemUTC();
                sender.sendMessage(String.format("System time set to %s (UTC)", LocalTime.now(systemClock)));
            } else {
                // Sets a fake system clock to a given time (UTC). It does not change the system time.
                var time = value.split(":");
                if (time.length < 2 || time.length > 3) {
                    sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                    return;
                }

                try {
                    var hour = Integer.parseInt(time[0]);
                    var minute = Integer.parseInt(time[1]);

                    // Seconds are optional
                    var second = 0;
                    if (time.length == 3) {
                        second = Integer.parseInt(time[2]);
                    }

                    // Data validation
                    if (hour < 0 || hour > 24) {
                        sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                        return;
                    }

                    if (minute < 0 || minute >= 60) {
                        sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                        return;
                    }

                    if (second < 0 || second >= 60) {
                        // There is the leap second thing in UTC, but I am going to ignore it for now...
                        sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                        return;
                    }

                    var now = LocalTime.now(ZoneOffset.UTC);
                    var then = LocalTime.of(hour, minute, second);
                    systemClock = Clock.offset(Clock.systemUTC(), Duration.between(now, then));

                    sender.sendMessage(String.format("System time set to %s (UTC)", then));
                } catch (NumberFormatException e) {
                    sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                }
            }

            synchronizeTimeNow();
        }
    }

    private void parseContinueCommand(CommandSender sender) {
        if (syncPaused) {
            startTimeSynchronization();
            sender.sendMessage("Time synchronization restarted");
        } else {
            sender.sendMessage("Time synchronization is already running!");
        }
    }

    private void parsePauseCommand(CommandSender sender) {
        if (syncPaused) {
            sender.sendMessage("Time synchronization is already paused!");
        } else {
            stopTimeSynchronization();
            sender.sendMessage("Time synchronization paused");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTimeSkipEvent(TimeSkipEvent event) {
        // This will prevent anything from changing the time, except this plugin itself
        if (! event.getSkipReason().equals(SkipReason.CUSTOM)) {
            // FIXME: There should be a way to detect whether this event was fired from this plugin. For now, it will not prevent other plugins from changing the time
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerIssuedTimeSetCommandEvent(PlayerCommandPreprocessEvent event) {
        if (SunSync.commandChangesGameTime(event.getMessage())) {
            // TODO: Use colors!
            event.getPlayer().sendRawMessage(String.format("This command will have no effect while the plugin %s is enabled.", getName()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerIssuedTimeSetCommandEvent(ServerCommandEvent event) {
        if (commandChangesGameTime(event.getCommand())) {
            // TODO: Use colors!
            logger.warning(String.format("This command will have no effect while the plugin %s is enabled.", getName()));
        }
    }

    static boolean commandChangesGameTime(String command) {
        // Should detect the following commands:
        // - time set
        // - time add
        //
        // But not:
        // - time query
        String com = command.trim().toLowerCase();
        return Pattern.matches("/?(minecraft:)?time\\p{javaWhitespace}+(set|add).*", com);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerBedEnterEvent(PlayerBedEnterEvent event) {
        // TODO: Use colors! Maybe use a toast message instead?
        event.getPlayer().sendRawMessage(String.format("Beds will not skip the night while the plugin %s is enabled.", getName()));
    }

    private void debugLog(String message) {
        if (configuration.getDebugMode()) {
            logger.info(String.format("DEBUG: %s", message));
        }
    }

    private class Configuration {
        private static final long SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT  = 5L;
        private static final long SYNCHRONIZATION_INTERVAL_MIN_VALUE        = 1L;
        private static final long SYNCHRONIZATION_INTERVAL_MAX_VALUE        = 1800L;
        private static final boolean DEBUG_MODE_DEFAULT                     = false;
        private static final Pattern REGEX_DECIMAL_DEGREES                  = Pattern.compile("(?<latitude>-?\\d+(?:\\.\\d+)?)\\s*,?\\s*(?<longitude>-?\\d+(?:\\.\\d+)?)");
        private static final Pattern REGEX_SEXAGESIMAL_DEGREES              = Pattern.compile("(?<LatDeg>\\d+)°(?: *(?<LatArcMin>\\d+)')?(?: *(?<LatArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LatDirection>[NS])\\s*,?\\s*(?<LonDeg>\\d+)°(?: *(?<LonArcMin>\\d+)')?(?: *(?<LonArcSec>\\d+(?:\\.\\d+)?)\")? *(?<LonDirection>[EW])");

        private String location;
        private long syncIntervalSeconds;
        private boolean debugMode;
        private GeographicCoordinate geographicCoordinates;
        private Hashtable<String, GeographicCoordinate> worldRegions;

        Configuration() {
            this.worldRegions = new Hashtable<>();

            // Parse regions.csv
            var pattern = Pattern.compile("(?<region>\\w+),Point\\((?<longitude>-?\\d+(?:\\.\\d+)?) (?<latitude>-?\\d+(?:\\.\\d+)?)\\)");
            var regionsFile = readEntireFile("regions.csv");
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
                        logger.warning(String.format("Parse error when processing geographic coordinates for \"%s\" region ", region));
                    }
                }
            }
            logger.info(String.format("Parsed %d regions from regions.csv", totalParsedRegions));

            this.location = "auto";
            this.geographicCoordinates = parseLocationOption(this.location);
            this.syncIntervalSeconds = SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT;
            this.debugMode = DEBUG_MODE_DEFAULT;
        }

        private String readEntireFile(String file) {
            try {
                var data = getResource(file).readAllBytes();
                return new String(data);
            } catch (IOException e) {
                return "";
            }
        }

        static long getSyncIntervalLowestValidValue() { return SYNCHRONIZATION_INTERVAL_MIN_VALUE; }

        static long getSyncIntervalHighestValidValue() { return SYNCHRONIZATION_INTERVAL_MAX_VALUE; }

        String getLocation() { return this.location; }

        GeographicCoordinate getGeographicCoordinates() { return this.geographicCoordinates; }

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
                if (coordinates == null) { return false; }
                if (coordinates.latitude < -90.0f || coordinates.latitude > 90.0f) { return false; }
                if (coordinates.longitude < -180.0f || coordinates.longitude > 180.0f) { return false; }

                return true;
            }
        }

        long getSynchronizationIntervalSeconds() { return syncIntervalSeconds; }

        boolean setSynchronizationIntervalSeconds(long seconds) {
            if (seconds < SYNCHRONIZATION_INTERVAL_MIN_VALUE || seconds > SYNCHRONIZATION_INTERVAL_MAX_VALUE) {
                return false;
            } else {
                this.syncIntervalSeconds = seconds;
                return true;
            }
        }

        boolean getDebugMode() { return debugMode; }

        void setDebugMode(boolean mode) { this.debugMode = mode; }
    }

    private interface ParameterParser {
        void parse(CommandSender sender, List<String> arguments);
    }
}