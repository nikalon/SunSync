package com.github.nikalon.sunsync;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.nikalon.sunsync.Sun.GeographicCoordinate.InvalidGeographicCoordinateException;

import static com.github.nikalon.sunsync.Sun.*;

public class SunSync extends JavaPlugin implements Runnable, Listener {
    private static final long MINECRAFT_DAY_LENGTH_TICKS    = 14000;
    private static final long MINECRAFT_NIGHT_LENGTH_TICKS  = 10000;

    // Sunrise time start as seen from the game. We cannot start from day -1000, so we use the next valid sunrise time.
    private static final long MINECRAFT_SUNRISE_START_TICKS = 23000;

    // Sunset time start as seen from the game
    private static final long MINECRAFT_SUNSET_START_TICKS  = 37000;

    private static final long MINECRAFT_MIDDAY_TICKS        = 30000;
    private static final long MINECRAFT_MIDNIGHT_TICKS      = 42000;

    private BukkitTask task;
    private FileConfiguration configFile;
    private Logger logger;

    LocalDate lastUpdated; // Used to cache sunrise and sunset calculations for a day
    RiseAndSet todayEvents;
    RiseAndSet yesterdayEvents;
    RiseAndSet tomorrowEvents;

    // Configuration values
    private static final long SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT  = 5L;
    private static final long SYNCHRONIZATION_INTERVAL_MIN_VALUE        = 1L;
    private static final long SYNCHRONIZATION_INTERVAL_MAX_VALUE        = 1800L;
    private static final boolean DEBUG_MODE_DEFAULT                     = false;

    private GeographicCoordinate location;
    private long syncIntervalSec;
    private boolean debugMode;

    @Override
    public void onLoad() {
        this.logger = getLogger();

        // Load configuration
        saveDefaultConfig();
        this.configFile = getConfig();

        // Debug mode
        Object debugVal = this.configFile.get("debug_mode");
        if (debugVal != null && debugVal instanceof Boolean) {
            this.debugMode = (Boolean) debugVal;
        } else {
            logger.severe("\"debug_mode\" value in config.yml is invalid, using default value. Please, use a boolean value (true or false).");
            this.debugMode = DEBUG_MODE_DEFAULT;
        }

        if (this.debugMode) {
            logger.warning("debug mode is enabled. To disable debug mode set the option \"debug_mode\" to false in config.yml and restart the server.");
        }

        // Location of Earth
        List<Double> geo = this.configFile.getDoubleList("location");
        if (geo == null || geo.size() != 2) {
            this.location = GeographicCoordinate.defaultCoordinate();
            logger.severe("\"location\" value in config.yml is invalid, using default values. Please, use decimal values between -90.0 and 90.0 degrees.");
        } else {
            try {
                this.location = GeographicCoordinate.from(geo.get(0), geo.get(1));
            } catch (InvalidGeographicCoordinateException e) {
                this.location = GeographicCoordinate.defaultCoordinate();
                logger.severe("\"location\" value in config.yml is invalid, using default values. Please, use decimal values between -90.0 and 90.0 degrees.");
            }
        }
        debugLog(String.format("Using geographic coordinates: %s", this.location));

        // Synchronization interval
        long sync_interval = this.configFile.getLong("synchronization_interval_seconds", SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT);
        if (sync_interval < SYNCHRONIZATION_INTERVAL_MIN_VALUE || sync_interval > SYNCHRONIZATION_INTERVAL_MAX_VALUE) {
            logger.log(Level.SEVERE, "\"synchronization_interval_seconds\" value in config.yml is invalid, using default value. Please, use integer values between " + SYNCHRONIZATION_INTERVAL_MIN_VALUE + " and " + SYNCHRONIZATION_INTERVAL_MAX_VALUE + ".");
            sync_interval = SYNCHRONIZATION_INTERVAL_SECONDS_DEFAULT;
        }
        this.syncIntervalSec = 20L * sync_interval;
        debugLog(String.format("Synchronization interval set to %d seconds", this.syncIntervalSec));
    }

    @Override
    public void onEnable() {
        // TODO: There should be a way to "fake" this setting for each connected player without actually changing the game rule
        Bukkit.getWorlds().forEach(world -> world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false));
        logger.warning("While this plugin is active the game rule doDaylightCycle will be set to false");

        task = Bukkit.getScheduler().runTaskTimer(this, this, 0, syncIntervalSec);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        task.cancel();
        task = null;
        HandlerList.unregisterAll((Listener) this);
        saveConfig();
    }

    @Override
    public void run() {
        // Whenever the term "event" is used it means either the sunrise or sunset in the real world

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        debugLog(String.format("The time is %s (UTC)", now.toLocalTime()));

        try {
            if (lastUpdated == null || now.toLocalDate().isAfter(lastUpdated)) {
                // Cache calculations until 23:59:59 (UTC)
                lastUpdated = now.toLocalDate();
                todayEvents = Sun.sunriseAndSunsetTimes(location, now.toLocalDate());
                yesterdayEvents = Sun.sunriseAndSunsetTimes(location, now.minusDays(1).toLocalDate());
                tomorrowEvents = Sun.sunriseAndSunsetTimes(location, now.plusDays(1).toLocalDate());

                debugLog(String.format("Today the Sun will rise at %s (UTC), and will set at %s (UTC)", todayEvents.riseUTCTime.toLocalTime(), todayEvents.setUTCTime.toLocalTime()));
            }
        } catch (NeverRaisesException e) {
            Bukkit.getWorlds().forEach((world) -> world.setTime(MINECRAFT_MIDNIGHT_TICKS)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
            logger.warning(String.format("The Sun will not rise today. Setting game time to midnight (Minecraft time %d).", MINECRAFT_MIDNIGHT_TICKS));
            return;
        } catch (NeverSetsException e) {
            Bukkit.getWorlds().forEach((world) -> world.setTime(MINECRAFT_MIDDAY_TICKS)); // TODO: Select desired worlds in config. Synchronizing all worlds for now...
            logger.warning(String.format("The Sun will not set today. Setting game time to midday (Minecraft time %d).", MINECRAFT_MIDDAY_TICKS));
            return;
        }

        // Error condition reached. The Sun will not rise or will not set today. Skipping time synchronization...
        if (todayEvents == null) return;

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
        String command = event.getMessage();
        if (command.startsWith("/time set") || command.startsWith("/time add")) {
            // TODO: Use colors!
            event.getPlayer().sendRawMessage(String.format("This command will have no effect while the plugin %s is enabled.", getName()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerIssuedTimeSetCommandEvent(ServerCommandEvent event) {
        String command = event.getCommand();
        if (command.contains("time set") || command.contains("time add")) {
            // TODO: Use colors!
            logger.warning(String.format("This command will have no effect while the plugin %s is enabled.", getName()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerBedEnterEvent(PlayerBedEnterEvent event) {
        // TODO: Use colors! Maybe use a toast message instead?
        event.getPlayer().sendRawMessage(String.format("Beds will not skip the night while the plugin %s is enabled.", getName()));
    }

    private void debugLog(String message) {
        if (debugMode) {
            logger.info(String.format("DEBUG: %s", message));
        }
    }
}