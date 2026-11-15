![PhobosLT](assets/wq.png)![Logo](assets/logo.png)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT) [![Join us on Discord!](https://img.shields.io/discord/1180943146696319126)](https://discord.gg/D3MgfvsnAw)
# Support PhobosLT
**Affordable FPV single node Race Timing solution**

If you like this project you can support it by contributing to the codebase, testing and giving feedback, sharing new ideas and helping spread the PhobosLT gospel. You can also consider buying me a bubble tea or a beer, it will fuel me to make the project better!

[![Donate to PhobosLT](https://img.shields.io/badge/Donate-PayPal-blue.svg)](https://paypal.me/phoboslt)

# About

Phobos LapTimer (or PhobosLT) is a simple but mighty lap timing solution for 5.8GHz FPV pilots. It is designed for personal use and with small drones in mind (2 inch or less), but there is nothing against running it with bigger drones! It is a small standalone device that is self powered and communicates over Bluetooth Low Energy with an Android phone. The goal of the project is to have a simple to use and small device that can be used anywhere whenever you want to do a whoop race practice session in your living room or at a local training venue with friends with no fuss.
Parts of the code are based on [RotorHazard](https://github.com/RotorHazard/RotorHazard) and [ExpressLRS](https://github.com/ExpressLRS/ExpressLRS). Big kudos to people behind these organizations, they are doing A LOT for the community and are shaping the future of FPV. If you don't know these projects yet and have some time, do check them out!

### Features

PhobosLT has the following features:
- Single node timing of 5.8GHz Analog, HDZero, Walksnail systems.
- Voice callouts, optionally including pilot name.
- Real time RSSI readout and calibration - allows for timing even in small spaces like a 16m^2 or 200 ft^2 room.
- 2 lap consecutive and 3 lap consecutive times and optional readouts on the fly (specially developed for [RaceGOW](https://www.racegow.com/)).
- Viewable lap history.
- Ability to set a minimal measurable lap time to avoid false positives.
- Configurable low voltage alarm.
- Small size, easy to build.
- Relatively cheap.
- Native Android app: callouts keep working with the screen off, and every race is saved on the phone with best lap, average and consistency stats.
- Firmware updates over Bluetooth from the app.
- Open [BLE protocol](docs/BLE_PROTOCOL.md), so other clients can be written.

Future development plans:
- iOS app.
- Custom hardware to minimize soldering.
- Integration with ExpressLRS and goggle VRXs to forward lap times to the OSD.
- Integration with RotorHazard to measure split times.
- Supporting multiple pilots at the same time at a cost of lower accuracy.

### How it works

The core of the project is an ESP32 board + an RX5808 module. The ESP32 is a Bluetooth Low Energy (BLE) peripheral, and the PhobosLT Android app connects to it to configure the timer and measure lap times.

The lap timing happens by measuring the RSSI over time, filtering it and checking for peaks in the RSSI as the closer the drone is to the timer the higher the RSSI. Based on that we set up an `Enter RSSI` and `Exit RSSI` thresholds, that tell us when to cut a peak. The time between the enter and exit RSSI is then used to measure the time between the last peak and the current peak which is a lap.

The timer exposes a single BLE service: the configuration is read and written as JSON, the race is started and stopped with control commands, and laps, battery status and (on the calibration screen only) live RSSI are sent as notifications. Firmware updates use the same connection. The full protocol is described in [docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md). Dropping WiFi keeps the 2.4GHz radio quiet while timing and leaves more memory free.

The phone emits the start tone and calls out lap times with Android's text-to-speech. An optional (but recommended) beeper can be installed on the timer to also emit a sound every time a peak is detected or to alert e.g. when the timer battery voltage is low.

# How to build one

### Hardware

To build a Phobos LapTimer you will need:
- An ESP32 breakout board, preferably with USB. The codebase is plug and play with `LilyGo T-ENERGY` which has a built in 1s 18650 Li-Ion cell slot and voltage sensing circuit out of the box. Supported boards:
    - LilyGo T-ENERGY - recommended.
    - LilyGo T-CELL - more expensive than the one above but has a charging circuit.
    - ESP32-DevKit - bare bones but cheap.
- An RX5808 VRx module with [SPI mod](https://sheaivey.github.io/rx5808-pro-diversity/docs/rx5808-spi-mod.html).
- A voltage supply of any sort - a battery, a powerbank, etc. It will depend on the ESP32 module used.
- (Optional) An LED of any color (+ a matching resistor to manage current).
- (Optional) A 3v3 to 5v buzzer WITH a generator (so active and not passive).

To connect the RX5808 to the ESP32 use below pinout table. Please note that +5v pin on the RX5808 should be connected to a 3v3 source to undervolt the RX5808 to get a better RSSI resolution and to help with cooling:
| ESP32 PIN | RX5880 |
| :------------- |:-------------|
| 33 | RSSI |
| GND | GND |
| 19 | CH1 |
| 22 | CH2 |
| 23 | CH3 |
| 3V3 | +5V |

Optional but recommended LED, Buzzer and Battery voltage input pinout:
| ESP32 PIN | Peripheral |
| :------------- |:-------------|
| 21 | LED anode (+) |
| 27 | Buzzer positive (+) |
| 35 | VBAT input MAX 3.3v (the code assumes a 1/2 voltage divider with a 1s Li-Ion  cell) |

You can find a connection diagram of the peripherals below. **For T-Energy and T-Cell you just need to connect the RX5808 and a buzzer.**

![Connection](assets/connection_diagram.png)

Alternatively you can ask the community to build a timer for you. Ask around on discord!

#### 3D printed cases
[ESP-WROOM-32 case by porlock](https://makerworld.com/en/models/400720)

### Firmware

Currently building the firmware happens via Visual Studio Code. The toolchain setup is exactly the same as for ExpressLRS, so if you already have an ExpressLRS toolchain set up and running, you should be good. The requirements to build the firmware are as follows:
- Visual Studio Code.
- PlatformIO.
- Git.

#### Toolchain setup

Perform these steps to set the toolchain up on your computer:
1. Download and install [vscode](https://code.visualstudio.com/).
2. Open vscode, and click on the `Extensions` icon in the toolbar on the right (see [Managing Extensions](https://code.visualstudio.com/docs/editor/extension-marketplace)).
3. In the search box, enter platformio, and install the extension (see the `pio install` [documentation](https://platformio.org/install/ide?install=vscode)).
4. Install [git](https://github.com/git-guides/install-git).

The last step before you can build the firmware is to clone this repository to your computer:
- In VSCode open the command palette (using `Cmd+Shift+P` on MacOS or `Ctrl+Shift+P` on Windows)
- Enter `Git: Clone`.
- Click it.
- Then, enter PhobosLT repo URL (can be found on top of the github page under the `Clone` button).
- Choose a folder where you want your copy of the repository to be located.

#### Building

To build the firmware, click the `PlatformIO` icon in the toolbar on the left, which will show the list of tasks. Now, select `Project Tasks`, expand `PhobosLT` -> `General` and select `Build`. You should see the result in the terminal after a few seconds (`Success`).

#### Flashing

Before attempting to flash ensure there is a connection between the ESP32 and the computer via USB. Click the `PlatformIO` icon in the toolbar on the left, which will show the list of tasks. Now, select `Project Tasks`, expand your target (e.g. `PhobosLT`, `ESP32C3`, `ESP32S3`) -> `General` and select `Upload`. You should see the result in the terminal (`Success`). That's it! Your timer is ready to use.

From the command line, the same is `pio run -e PhobosLT -t upload`.

If something went wrong - please check the Terminal, too. It will contain at least a hint of what the issue is. Please ask the community for further help on discord!

**Upgrading from a WiFi version:** the first Bluetooth firmware has to be flashed over USB. The stored settings are reset once to defaults, because the WiFi fields were removed from them. There is no file system image to upload anymore.

#### Updating over Bluetooth

Once a Bluetooth firmware is installed, later updates can be done from the app without a cable: open `Settings`, scroll to `Firmware update`, choose the new `firmware.bin` (found in `.pio/build/<target>/firmware.bin` after a build) and tap `Update timer`. The app refuses images built for another chip, and the timer checks the image's MD5 and validity before switching to it, so an interrupted or corrupt transfer leaves the current firmware running. The timer reboots when done and the app reconnects by itself. Use `firmware.bin`, not a merged/factory image.

### Android app

The app lives in the `android/` folder. It needs Android 8.0 or newer with Bluetooth LE.

To build it you need a JDK 17 or 21 (a full JDK, not just a runtime) and the Android SDK (installed with [Android Studio](https://developer.android.com/studio), or the command line tools). Either open the `android/` folder in Android Studio and press Run, or build from the command line:

```
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew testDebugUnitTest` runs the unit tests. A release build (`./gradlew assembleRelease`) needs to be signed with your own key before it can be installed.

# Usage

This section describes the usage and configuration of the timer with the Android app. The app has 4 tabs: `Race`, `Calibrate`, `Settings` and `History`.

### First connect

1. Turn on the timer, you should hear a short and a long beep.
2. Open the PhobosLT app and grant Bluetooth access when asked (on Android 11 and older Android calls it location access, and location services must be on to scan). Allow notifications too: they show the connection status while racing.
3. The app scans for timers. Tap the one named `PhobosLT_xxxx`. The timer beeps when a phone connects.
4. Voila! Next time, `Reconnect to PhobosLT_xxxx` connects in one tap.

If the timer goes out of range or reboots, the app reconnects on its own as soon as it is back. Up to 3 phones can be connected at the same time, e.g. a spotter can follow the laps too.

### Configuration

To configure the timer open the `Settings` tab. Below you can find all the config parameters and their description:

**Band and Channel** - set to the same Band and Channel as your drone. Supported Bands - A, B, E, Fatshark, RaceBand and LowBand, 8 channel each. The frequency is displayed below. Until a channel is chosen the RX5808 stays powered down (`RX off`).
**Minimum lap time** - you can set a minimum lap time that can be timed. This avoids false positives when you crash in the start gate, or when your track is very tight and you fly in close proximity to the timer multiple times during one lap.
**Low battery alarm** - sets a battery voltage alarm that will trigger once the timer battery reaches the desired voltage. Set it to 0 to turn the alarm off. The current voltage is shown at the top of the app.
**Announcer** - you have a few options on how you want the app to report lap times:
- `None` is no sound at all.
- `Beep` will just emit a short beep on crossing to let you know it registered a lap.
- `Lap time` will announce the lap time (including your pilot name if the **Pilot name** field is filled).
- `2 consecutive laps` will announce the two consecutive lap time.
- `3 consecutive laps` will announce the three consecutive lap time.

**Voice speed** - controls the speed of the announcer reading the lap time. `Test voice` lets you try it.
**Pilot name** - when filled it will include pilot name when reading the times, e.g. `Pilot1 Lap 3, 23.45`. It is useful when there is more than just one timer running at the same time. When practicing alone leave it empty.

Callouts use the phone's media volume, and keep working with the screen off or another app in front.

**NOTE: Once configured make sure to save the configuration by tapping `Save configuration`, otherwise the changes will not take effect.** The `Timer` section at the bottom shows the timer's chip and firmware version and hosts the [firmware update](#updating-over-bluetooth).

### Calibration

Calibartion is a very important step and it needs to be done properly to ensure the timing happens correctly and every lap is counted by the timer.

To perform the calibration open the `Calibrate` tab. You should be presented with two sliders and a graph representing the RSSI over time, updated 10 times per second. You can think of RSSI as an inverse of the distance between the drone and the timer. The higher the RSSI the shorter the distance.

A node can be either `Crossing` or `Clear`. If a node is `Clear`, the timer believes a drone is not near the timer because the RSSI is low. If it is `Crossing`, the timer believes a drone is passing by the timer because the RSSI is high. A lap pass will be recorded once the `Crossing` is finished and the RSSI returns to `Clear` zone. We calibrate that by setting `Enter` and `Exit` RSSI thresholds.

A well calibrated timer will show the lap time when we have only one crossing when drone is the closest to the timer, e.g. (screenshot from the previous web UI, the app graph uses the same colors):

![Configuration](assets/plt3.png)

`Crossing` will always be marked in green, the RSSI is drawn in blue, and the `Enter` and `Exit` thresholds are the red and orange lines.


To come up with good initial values for `Enter` and `Exit` RSSI perform these steps:
1. Turn on the timer and your drone, set it to the desired VTx power, wait 30 seconds for the VTx to reach its running temperature.
2. Place the drone at a distance of slightly more than one gate above the timer. 
3. Note the RSSI, deduct 2-5 points to be safe - that should be your `Enter RSSI`. 
4. Deduct another 8-10 points and set it as your `Exit RSSI`. 
5. **Tap `Save RSSI thresholds` - otherwise the changes will not take effect.**

When flying with other pilots the RSSI readings might be lower due to all the noise generated by other VTxs on adjecent channels. A good practice is to lower both thresholds by a few points when flying with other pilots in the air.

### Race and lap management

The `Race` tab lets you start or stop a race and view and clear your lap times. The screen stays on while it is open.

Functions of the buttons:
- `Start` - starts the countdown: the app says "Arm your quad", then "Starting on the tone in less than five", waits a random 1 to 5 seconds and plays the start tone, at which point the timer starts counting laps.
- `Stop` - press it when you want to stop counting new laps. It does not clear the laps collected so far.
- `Clear` - clears the laps on the screen, can be done when the race is running as well.

The first lap reported is the hole shot (`HS`), the time from the start tone to the first gate crossing. Each following row shows the lap time and the 2 and 3 consecutive lap times, with the best lap highlighted, and the number of laps, best lap, average and best 3 consecutive laps are shown above the table.

### History

Every race with at least one lap is saved on the phone. The `History` tab lists them with their date, pilot, number of laps and best lap. Tap a race to see all its laps, the hole shot, and its consistency (standard deviation of the lap times: lower is more consistent). Races can be shared as text or deleted.

# Community

Join our [Discord](https://discord.gg/D3MgfvsnAw) channel for support and questions or just to hang out! Everyone is welcome!
