# SunSync
SunSync is a Spigot plugin that will make your world experience the same day/night cycle as the real world, and the moon will follow the same phases as in real life.

This plugin doesn't need an internet connection at all and does not collect any telemetry data whatsoever.

## Disclaimer
Before installing and using this plugin, please note that it can affect certain gameplay mechanics that rely on the vanilla Minecraft day/night cycle. It's important to thoroughly test your Minecraft world after installing this plugin to ensure that everything works as expected. Note that:

* Some farms may break or become less efficient.
* Some redstone clocks may break.
* Some mobs may exhibit different behavior or may not spawn at all.
* Beds will not work as intended.
* Other time-dependent events or mechanics that rely on time may not function as intended.

Additionally, if you open the debug screen (F3) and check the in-game Minecraft time, you may notice that it will go back and forth in time as the days progress. This is expected behavior and is a result of the way this plugin performs the time synchronization.

## Installation
To install this plugin follow these steps:

1. Make sure your server is running Spigot version `1.18` or higher.
2. Download the latest version of [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) from the Spigot website and install it in your server's plugins folder. **It is a required dependency.**
3. Download the latest version of SunSync from the [releases page on GitHub](https://github.com/nikalon/SunSync/releases).
4. Copy the downloaded JAR file to your server's plugins folder.
5. Start the server

## Command Usage
SunSync comes with several commands that you can use to customize the plugin's behavior.

* `/timesync location`: Gets the current location.
* `/timesync location <coordinate>`: Sets the geographic location that the plugin uses to perform some astronomical calculations. Allowed values:
    * Decimal coordinates (e.g. /timesync location 37.7749 -122.4194)
    * Sexagesimal coordinates (e.g. /timesync location 37°46'29"N 122°25'10"W)
    * auto. If this value is set the plugin will try to guess the server's geographic location based on some heuristics. This is the default value.

* `/timesync syncIntervalSec`: Gets the current update interval (in seconds).
* `/timesync syncIntervalSec <time>`: Sets the update interval (in seconds). The default is 5.

* `/timesync clock`: Query the server's UTC+0 time (your local time without time offset and daylight saving time)

* `/timesync debugMode`: Tells if debug mode is enabled or not.
* `/timesync debugMode <bool>`: Enable or disable debug mode. You can set a boolean value (true or false). The default value is false.

The following commands can only be used when debug mode is enabled. These are only intended for debugging purposes and any changes applied with these commands will not be saved, so don't rely on them. When debug mode is enabled it will generate A LOT of debug info in your server console.
* `/timesync pause`: Pauses the time synchronization without stopping it completely.
* `/timesync continue`: Resumes the time synchronization if it was paused.
* `/timesync clock`: Displays the current real-world time and the Minecraft time.


## Configuration
SunSync comes with a default configuration file called config.yml, which is located in the plugins/SunSync folder. You can customize the plugin's behavior by editing this file. Here are some of the options you can set:

* `location = [geographic location]`: The location that the plugin uses to determine the real-world time. By default, the plugin uses the server's location, but you can set it to a specific location using a decimal coordinate, a sexagesimal coordinate, or "auto". If you set it to "auto", the plugin will try to automatically determine your location based on some heuristics.

* `synchronization_interval_seconds = [number]`: The interval (in seconds) at which the plugin updates the Minecraft world's time. The default is 5 seconds.

* `debug_mode = [boolean]`: Whether to enable debug mode. The default is false.

## Contributing
If you encounter a bug or have a feature request, please open an issue on GitHub. Pull requests are also welcome!

## References
To develop this plugin, the following sources served as a reference:

- Practical Astronomy with your Calculator or Spreadsheet, 4th Edition.
