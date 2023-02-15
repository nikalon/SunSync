package com.github.nikalon.sunsync;

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

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.github.nikalon.sunsync.Sun.NeverRaisesException;
import com.github.nikalon.sunsync.Sun.NeverSetsException;
import com.github.nikalon.sunsync.Sun.RiseAndSet;

public class SunSync extends JavaPlugin implements Runnable, Listener {
    private static final long ONE_SECOND_IN_MINECRAFT_TICKS = 20L;
    private static final long MINECRAFT_DAY_LENGTH_TICKS    = 14000;
    private static final long MINECRAFT_NIGHT_LENGTH_TICKS  = 10000;
    private static final long MINECRAFT_DAY_IN_TICKS        = 24000;

    // Sunrise time start as seen from the game. We cannot start from day -1000, so we use the next valid sunrise time.
    private static final long MINECRAFT_SUNRISE_START_TICKS = 23000;

    // Sunset time start as seen from the game
    private static final long MINECRAFT_SUNSET_START_TICKS  = 37000;

    private static final long MINECRAFT_MIDDAY_TICKS        = 30000;
    private static final long MINECRAFT_MIDNIGHT_TICKS      = 42000;

    private Configuration configuration;
    private Clock systemClock;
    private long currentMinecraftTime;
    private long currentMinecraftDay;
    private boolean paused;

    private BukkitTask task;
    private Logger logger;
    private ProtocolManager protocolManager;
    private PacketAdapter packetPlayOutUpdateTimeListener;

    private LocalDate lastUpdated; // Used to cache sunrise and sunset calculations for a day
    private RiseAndSet todayEvents;
    private RiseAndSet yesterdayEvents;
    private RiseAndSet tomorrowEvents;

    // Parameters used in /timesync command
    private Hashtable<String, ParameterParser> commandParameters;
    private List<String> commandTabCompletion;

    private void startTimeSynchronizationTask() {
        // Starts the time synchronization task
        stopTimeSynchronizationTask();
        var intervalSecs = configuration.getSynchronizationIntervalSeconds();
        this.task = Bukkit.getScheduler().runTaskTimer(this, this, 0, intervalSecs * ONE_SECOND_IN_MINECRAFT_TICKS);
        debugLog("Started time synchronization task");
    }

    private void stopTimeSynchronizationTask() {
        // Stops the time synchronization task
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
            debugLog("Stopped time synchronization task");
        }
    }

    private void synchronizeTime() {
        // Whenever the term "event" is used it means either the sunrise or sunset in the real world

        var needsToRecalculateEventsTimes = ! this.paused || this.lastUpdated == null;
        if (needsToRecalculateEventsTimes) {
            LocalDateTime now = LocalDateTime.now(systemClock);
            debugLog(String.format("The time is %s (UTC)", now.toLocalTime()));

            try {
                if (lastUpdated == null || now.toLocalDate().isAfter(lastUpdated)) {
                    // Calculate sunrise and sunset times and cache it until 23:59:59 (UTC)
                    lastUpdated = now.toLocalDate();
                    todayEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.toLocalDate());
                    yesterdayEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.minusDays(1).toLocalDate());
                    tomorrowEvents = Sun.sunriseAndSunsetTimes(configuration.getGeographicCoordinates(), now.plusDays(1).toLocalDate());

                    debugLog(String.format("Yesterday the Sun rose at %s (UTC), set at %s (UTC)", yesterdayEvents.riseUTCTime, yesterdayEvents.setUTCTime));
                    debugLog(String.format("Today's events -> rise at %s (UTC), set at %s (UTC)", todayEvents.riseUTCTime, todayEvents.setUTCTime));
                    debugLog(String.format("Tomorrow's events -> rise at %s (UTC), set at %s (UTC)", tomorrowEvents.riseUTCTime, tomorrowEvents.setUTCTime));

                    // Calculate today's Moon phase and cache it until 23:59:59 (UTC)
                    var moonPhase = Moon.phase(now);
                    debugLog(String.format("Current Moon phase: " + moonPhase));

                    /*
                    Minecraft has 8 Moon phases according to the wiki (https://minecraft.fandom.com/wiki/Moon#Phases)
                    Starting from day 0 it goes through the phases as this:
                    Day 0: Full Moon
                    Day 1: Waning gibbous
                    Day 2: Last Quarter
                    Day 3: Waning Crescent
                    Day 4: New Moon
                    Day 5: Waxing crescent
                    Day 6: First quarter
                    Day 7: Waxing gibbous

                    What we're doing here is calculate the current real world Moon phase and convert it to the corresponding
                    Minecraft day defined above. This plugin will always stay in the range of days 0-7 at all times. This
                    means that as the days go by the Minecraft server will go back and forth in time. That's expected
                    behaviour for now, because as of Minecraft 1.18-1.19 we have no way to modify the orbit, moonrise
                    time, moonset time, phase, etc of the Moon directly in the server side.
                    */

                    // TODO: Match visual appearance of the the Moon. Maybe add a setting for this?
                    this.currentMinecraftDay = Math.round(Helper.modulo(4.0 + (moonPhase * 8.0), 8.0));
                    debugLog(String.format("Current Minecraft day (for moon phase): " + this.currentMinecraftDay));

                }
            } catch (NeverRaisesException e) {
                this.currentMinecraftTime = MINECRAFT_MIDNIGHT_TICKS;
                this.currentMinecraftTime = this.currentMinecraftTime % MINECRAFT_DAY_IN_TICKS;
                logger.warning(String.format("The Sun will not rise today. Setting game time to midnight (Minecraft time %d).", this.currentMinecraftTime));
            } catch (NeverSetsException e) {
                this.currentMinecraftTime = MINECRAFT_MIDDAY_TICKS;
                this.currentMinecraftTime = this.currentMinecraftTime % MINECRAFT_DAY_IN_TICKS;
                logger.warning(String.format("The Sun will not set today. Setting game time to midday (Minecraft time %d).", this.currentMinecraftTime));
            }

            if (yesterdayEvents == null || todayEvents == null || tomorrowEvents == null) {
                // Error condition reached. The Sun will not rise and/or set today.
            } else {
                // Determine if it's daytime or nighttime
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
                if (is_daytime) {
                    this.currentMinecraftTime = (long) ((MINECRAFT_DAY_LENGTH_TICKS / event_interval_duration) * delta_time + MINECRAFT_SUNRISE_START_TICKS);
                    this.currentMinecraftTime = this.currentMinecraftTime % MINECRAFT_DAY_IN_TICKS;
                } else {
                    this.currentMinecraftTime = (long) ((MINECRAFT_NIGHT_LENGTH_TICKS / event_interval_duration) * delta_time + MINECRAFT_SUNSET_START_TICKS);
                    this.currentMinecraftTime = this.currentMinecraftTime % MINECRAFT_DAY_IN_TICKS;
                }
            }
        }

        // Synchronize Minecraft time
        long fullMinecraftTime = this.currentMinecraftTime + (this.currentMinecraftDay * MINECRAFT_DAY_IN_TICKS);
        Bukkit.getWorlds().forEach((world) -> world.setFullTime(fullMinecraftTime)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
        debugLog(String.format("All worlds synchronized to Minecraft time %d", this.currentMinecraftTime));
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
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.packetPlayOutUpdateTimeListener = new PacketAdapter(
            this,
            ListenerPriority.HIGHEST,
            PacketType.Play.Server.UPDATE_TIME
        ) {
            /*
                This plugin listens for all outgoing time update packets and makes modifications in some circumstances.
                The purpose of this modification is to resolve a jittering Sun bug when the gamerule doDaylightCycle is
                set to true.

                Initially, the idea was to enforce the doDaylightCycle gamerule to be false as long as the plugin is
                active and running on the server. However, this approach had two drawbacks:

                1. Silently modifying server settings could result in confusion among server administrators.
                2. The Spigot API doesn't support listening for gamerule modifications. So whenever a player changes
                   the doDaylightCycle to true this plugin will never notice.

                To fix the jittering Sun bug, the plugin uses ProtocolLib to modify all outgoing time update packets
                without touching the doDaylightCycle gamerule. The Minecraft client determines the value of the gamerule
                based on the sign of the time received. If a negative time is received, the client considers doDaylightCycle
                to be false, while a positive time indicates that doDaylightCycle is true. Here we ensure that all
                packets are always negative signed.

                Source: https://wiki.vg/Protocol#Update_Time
            */
            @Override
            public void onPacketSending(PacketEvent event) {
                final int TIME_OF_DAY_FIELD = 1;
                var fields = event.getPacket().getLongs();
                var timeOfDay = fields.read(TIME_OF_DAY_FIELD);
                if (timeOfDay >= 0) {
                    // The gamerule doDaylightCycle is set to true. Change the sign of the time to make the client
                    // believe that the gamerule is set to false
                    fields.write(TIME_OF_DAY_FIELD, -currentMinecraftTime);
                }
            }
        };

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
        var regionsFile = getResource("regions.csv");
        this.configuration = new Configuration(this.logger, regionsFile);

        // Debug mode
        Object debugVal = configFile.get("debug_mode");
        if (debugVal == null) {
            // Set debug mode to default value. No action is required.
        } else if (debugVal instanceof Boolean) {
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
        this.protocolManager.addPacketListener(this.packetPlayOutUpdateTimeListener);

        systemClock = Clock.systemUTC();
        this.paused = false;

        var command = getCommand("timesync");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        startTimeSynchronizationTask();
    }

    @Override
    public void onDisable() {
        this.protocolManager.removePacketListener(this.packetPlayOutUpdateTimeListener);
        stopTimeSynchronizationTask();
        HandlerList.unregisterAll((Listener) this);
        saveConfiguration();
    }

    @Override
    public void run() {
        // Task timer callback
        synchronizeTime();
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

                synchronizeTime();
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
                    startTimeSynchronizationTask(); // Restart time synchronization task
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

            synchronizeTime();
        }
    }

    private void parseContinueCommand(CommandSender sender) {
        if (this.paused) {
            this.paused = false;
            sender.sendMessage("Time synchronization restarted");
        } else {
            sender.sendMessage("Time synchronization is already running!");
        }
    }

    private void parsePauseCommand(CommandSender sender) {
        if (this.paused) {
            sender.sendMessage("Time synchronization is already paused!");
        } else {
            this.paused = true;
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

    private interface ParameterParser {
        void parse(CommandSender sender, List<String> arguments);
    }
}