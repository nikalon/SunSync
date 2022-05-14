package com.github.nikalon.sunsync;

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

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate.InvalidGeographicCoordinateException;

import static com.github.nikalon.sunsync.Sun.*;

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

    // Used for command completion
    private final List<String> ACTIONS = new ArrayList<>(); // get, set, ...
    private final List<String> PARAMETERS = new ArrayList<>(); // location, debugMode, ...
    private final List<String> CLOCK_VALUES = new ArrayList<>(); // "default"
    private final List<String> DEBUG_MODE_VALUES = new ArrayList<>(); // "true", "false"
    private final List<String> NO_COMPLETION = new ArrayList<>();

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
                todayEvents = Sun.sunriseAndSunsetTimes(configuration.getLocation(), now.toLocalDate());
                yesterdayEvents = Sun.sunriseAndSunsetTimes(configuration.getLocation(), now.minusDays(1).toLocalDate());
                tomorrowEvents = Sun.sunriseAndSunsetTimes(configuration.getLocation(), now.plusDays(1).toLocalDate());

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
        var coordinates = new ArrayList<Double>();
        coordinates.add(configuration.getLocation().latitude);
        coordinates.add(configuration.getLocation().longitude);
        conFile.set("location", coordinates);

        // Synchronization interval
        conFile.set("synchronization_interval_seconds", configuration.getSynchronizationIntervalSeconds());

        saveConfig(); // Save to config.yml
        debugLog("Configuration saved to config.yml");
    }

    @Override
    public void onLoad() {
        ACTIONS.add("set");
        ACTIONS.add("get");
        ACTIONS.add("continue");
        ACTIONS.add("pause");

        PARAMETERS.add("location");
        PARAMETERS.add("syncIntervalSec");
        PARAMETERS.add("debugMode");
        PARAMETERS.add("clock");

        CLOCK_VALUES.add("default");

        DEBUG_MODE_VALUES.add("true");
        DEBUG_MODE_VALUES.add("false");

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

        // Location on Earth
        List<Double> geo = configFile.getDoubleList("location");
        if (geo == null || geo.size() != 2) {
            logger.severe("\"location\" value in config.yml is invalid, using default values. Please, use decimal values between -90.0 and 90.0 degrees (latitude) and -180.0 and 180 degrees (longitude).");
        } else {
            try {
                configuration.setLocation(GeographicCoordinate.from(geo.get(0), geo.get(1)));
            } catch (InvalidGeographicCoordinateException e) {
                logger.severe("\"location\" value in config.yml is invalid, using default values. Please, use decimal values between -90.0 and 90.0 degrees.");
            }
        }
        debugLog(String.format("Using geographic coordinates: %s", configuration.getLocation()));

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
        // 1. Should parameter names differ from config.yml?
        // 2. We don't need a way to automatically add parameters to parse when we add more configuration options
        // 3. Value parsing should be handled in a central way, in a per-setting basis
        // 4. Data validation should be handled in a central way
        // 5. Re-schedule time synchronization routines whenever we change settings
        // 6. Should I add an option to reset do defaults?
        // 7. Should I add an option to discard setting changes? This would reload settings from config.yml, not defaults

        if (args.length == 0) return false; // Show usage (set in plugin.yml)

        // TODO: It should be desirable to save these options when closing the plugin
        String parameter;
        switch (args[0]) {
            case "get":
                parameter = args[1];
                switch(parameter) {
                    case "location":
                        sender.sendMessage(String.format("Current location set to %s", configuration.getLocation()));
                        return true;

                    case "syncIntervalSec":
                        sender.sendMessage(String.format("Synchronization interval set to %d seconds", configuration.getSynchronizationIntervalSeconds()));
                        return true;

                    case "debugMode":
                        if (configuration.getDebugMode())   sender.sendMessage("Debug mode is enabled");
                        else                                sender.sendMessage("Debug mode is disabled");
                        return true;

                    case "clock":
                        // Prints the system clock time (UTC)
                        var time = LocalTime.now(systemClock);
                        sender.sendMessage(String.format("The system time is %s (UTC)", time));
                        return true;

                    default:
                        sender.sendMessage(String.format("Unknown parameter \"%s\"", parameter));
                        return true;
                }

            case "set":
                parameter = args[1];
                if (args.length < 3) {
                    sender.sendMessage("Missing value after parameter");
                    return true;
                }

                var value = args[2];
                switch(parameter) {
                    case "location":
                        if (args.length < 4) {
                            logger.severe("Invalid coordinates. Please, use decimal values between -90.0 and 90.0 degrees (latitude) and -180.0 and 180 degrees (longitude).");
                            return true;
                        }

                        var value2 = args[3];
                        double latitude;
                        double longitude;

                        try {
                            latitude = Double.parseDouble(value);
                            longitude = Double.parseDouble(value2);
                            var loc = GeographicCoordinate.from(latitude, longitude);
                            configuration.setLocation(loc);

                            // Force to recalculate sunrise and sunset times
                            lastUpdated = null;
                            todayEvents = null;
                            yesterdayEvents = null;
                            tomorrowEvents = null;

                            synchronizeTimeNow();
                            sender.sendMessage(String.format("Location set to %s", loc));
                        } catch (NumberFormatException e) {
                            sender.sendMessage("Invalid coordinates. Please, enter a valid longitude and latitude as decimals");
                        } catch (GeographicCoordinate.InvalidGeographicCoordinateException e) {
                            sender.sendMessage("Invalid coordinates. Please, enter a valid longitude and latitude as decimals");
                        }

                        return true;

                    case "syncIntervalSec":
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
                        return true;

                    case "debugMode":
                        var newDebugMode = false;
                        if (value.equals("true")) {
                            newDebugMode = true;
                        } else if (value.equals("false")) {
                            newDebugMode = false;
                        } else {
                            sender.sendMessage("Invalid value. Please, enter a boolean value (true|false)");
                            return true;
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

                        return true;

                    case "clock":
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
                                return true;
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
                                    return true;
                                }

                                if (minute < 0 || minute >= 60) {
                                    sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                                    return true;
                                }

                                if (second < 0 || second >= 60) {
                                    // There is the leap second thing in UTC, but I am going to ignore it for now...
                                    sender.sendMessage("Invalid time. Please, enter a valid UTC time in the given 24-hour format: HH:MM or HH:MM:SS");
                                    return true;
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
                        return true;

                    default:
                        sender.sendMessage(String.format("Unknown parameter \"%s\"", parameter));
                        return true;
                }

            case "continue":
                if (syncPaused) {
                    startTimeSynchronization();
                    sender.sendMessage("Time synchronization restarted");
                } else {
                    sender.sendMessage("Time synchronization is already running!");
                }
                return true;

            case "pause":
                if (syncPaused) {
                    sender.sendMessage("Time synchronization is already paused!");
                } else {
                    stopTimeSynchronization();
                    sender.sendMessage("Time synchronization paused");
                }
                return true;
        }

        return false; // Show usage (set in plugin.yml)
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // TODO: This is a hacky way to add tab completion. It is ugly as hell, but it works. I should find a way to
        // clean this up in a way that it sends tab completion according to a tree structure or something. Maybe I can
        // find a way to do this effortlessly if I clean up the command execution routine in the first place.
        if (args.length == 1) return ACTIONS; // first-level argument
        else {
            var action = args[0];
            if (args.length == 2 && (action.equals("get") || action.equals("set"))) return PARAMETERS; // Second-level argument. Parameters.
            else if (action.equals("set") && args.length == 3) {
                // Third-level argument. Parameter values.
                // example: "/timesync set parameter "
                var parameter = args[1];
                if (parameter.equals("clock")) return CLOCK_VALUES;
                else if (parameter.equals("debugMode")) return DEBUG_MODE_VALUES;
            }
        }

        return NO_COMPLETION;
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

        private GeographicCoordinate location;
        private long syncIntervalSeconds;
        private boolean debugMode;

        Configuration() {
            this.location = GeographicCoordinate.defaultCoordinate();
            this.syncIntervalSeconds = SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT;
            this.debugMode = DEBUG_MODE_DEFAULT;
        }

        static long getSyncIntervalLowestValidValue() { return SYNCHRONIZATION_INTERVAL_MIN_VALUE; }

        static long getSyncIntervalHighestValidValue() { return SYNCHRONIZATION_INTERVAL_MAX_VALUE; }

        GeographicCoordinate getLocation() { return location; }

        void setLocation(GeographicCoordinate newLocation) { this.location = newLocation; }

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
}