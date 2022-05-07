package com.github.nikalon.sunsync;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.nikalon.sunsync.Sun.*;

public class SunSync extends JavaPlugin implements Runnable {
    private static final long MINECRAFT_DAY_LENGTH_TICKS    = 14000;
    private static final long MINECRAFT_NIGHT_LENGTH_TICKS  = 10000;

    // Sunrise time start as seen from the game. We cannot start from day -1000, so we use the next valid sunrise time.
    private static final int MINECRAFT_SUNRISE_START_TICKS  = 23000;

    // Sunset time start as seen from the game
    private static final int MINECRAFT_SUNSET_START_TICKS   = 37000;

    private BukkitTask task;
    private FileConfiguration configFile;
    private Logger logger;

    // Configuration values
    private GeographicCoordinate location;
    private long syncIntervalSec;

    @Override
    public void onLoad() {
        // Load configuration
        saveDefaultConfig();
        configFile = getConfig();

        this.logger = getLogger();
        this.configFile = getConfig();

        // Location on Earth
        List<Double> geo = this.configFile.getDoubleList("location");
        if (geo == null || geo.size() != 2) {
            this.location = new GeographicCoordinate(0.0, 0.0);
            logger.log(Level.SEVERE, "\"location\" value in config.yml is incorrect, using default values. Please, check the data.");
        } else {
            this.location = new GeographicCoordinate(geo.get(0), geo.get(1));
        }

        // Synchronization interval
        long sync_interval = this.configFile.getLong("synchronization_interval_seconds", 1L);
        if (sync_interval < 1L || sync_interval > 1800L) {
            logger.log(Level.SEVERE, "\"synchronization_interval_seconds\" value in config.yml is incorrect, using default value. Please, check the data.");
            sync_interval = 1L;
        }
        this.syncIntervalSec = 20L * sync_interval;
    }

    @Override
    public void onEnable() {
        // TODO: Disable time-related things to let this plugin do its job!
        task = Bukkit.getScheduler().runTaskTimer(this, this, 0, syncIntervalSec);
    }

    @Override
    public void onDisable() {
        task.cancel();
        task = null;
        saveConfig();
    }

    @Override
    public void run() {
        // Whenever the term "event" is used it means either the sunrise or sunset in the real world

        // TODO: Cache calculations as long as necessary
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RiseAndSet today_events = Sun.sunriseAndSunsetTimes(location, now.toLocalDate());
        RiseAndSet yesterday_events = Sun.sunriseAndSunsetTimes(location, now.minusDays(1).toLocalDate());
        RiseAndSet tomorrow_events = Sun.sunriseAndSunsetTimes(location, now.plusDays(1).toLocalDate());

        // Figures out if it's daytime or nighttime right now
        LocalDateTime last_event_time;
        LocalDateTime next_event_time;
        boolean is_daytime;
        if (now.isBefore(today_events.riseUTCTime)) {
            // Nighttime. Last event was yesterday's sunset. Next event is today's sunrise.
            is_daytime = false;
            last_event_time = yesterday_events.setUTCTime;
            next_event_time = today_events.riseUTCTime;
        } else if (now.isAfter(today_events.setUTCTime)) {
            // Nighttime. Last event was today's sunset. Next event is tomorrow's sunrise.
            is_daytime = false;
            last_event_time = today_events.setUTCTime;
            next_event_time = tomorrow_events.riseUTCTime;
        } else {
            // Daytime. Last event was today's sunrise. Next event is today's sunset.
            is_daytime = true;
            last_event_time = today_events.riseUTCTime;
            next_event_time = today_events.setUTCTime;
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
        logger.info("All worlds synchronized to time " + minecraft_time);
    }
}