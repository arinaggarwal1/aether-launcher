# Getting started: build and run Aether Launcher

From a fresh machine to Aether running on a TV (or the Android TV emulator) in about 20 minutes. No
Android Studio or Gradle needed: the project builds with the plain Android SDK command-line tools.

## 1. What you need

| | Version | Why |
|---|---|---|
| JDK | 17 or newer | `javac` compiles the Java sources (`--release 11`) |
| Android SDK build-tools | **36.0.0** | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android SDK platform | **android-35** | `android.jar` to compile against |
| Android SDK platform-tools | latest | `adb` to install and debug |
| A device | Android TV 7.0+ (API 24+) | A TV box/TV, or the Android TV emulator |

Tested devices: Xiaomi Mi Box 4 (Android 12, 32-bit ARM, Mali-450 GPU) and an SPPL Android 9 TV
(1 GB RAM). Low-end hardware is the target, so test on it when you can.

## 2. Install the tools

### macOS

```sh
brew install --cask temurin@17                      # JDK 17 (or any JDK 17+)
brew install --cask android-commandlinetools        # sdkmanager
export ANDROID_HOME="$HOME/Library/Android/sdk"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "build-tools;36.0.0" "platforms;android-35"
```

### Linux (Debian/Ubuntu)

```sh
sudo apt install openjdk-17-jdk unzip
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;36.0.0" "platforms;android-35"
```

### Windows

Use **WSL 2** (Ubuntu) and follow the Linux steps; `build.sh` is a bash script. To install on a TV
over the network from WSL, the TV and PC just need to be on the same LAN.

Put `ANDROID_HOME` (and `JAVA_HOME` if `javac` isn't on your `PATH`) in your shell profile.
`build.sh` reads them, and falls back to `~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux).

## 3. Get the code and build

```sh
git clone https://github.com/arinaggarwal1/aether-launcher.git
cd refract-launcher
./build.sh                                                 # → build/refract-custom.apk
./build.sh --name 1.0.0-custom.1                          # optional display version
```

`build.sh` compiles Java/resources with the Android SDK and signs with your permanent private
key. It reserves a unique increasing versionCode in GitHub. Restore your existing signing key
on a new machine; never create another one. See **[Custom updates](custom-updates.md)** for the
required one-time signing/GitHub setup and Fire TV migration. Ordinary builds never install apps.

## 4. Run it on a TV

For the personal Fire TV Cube, Aether Launcher is already installed and is already Home. For an
in-place update, follow [Updating your launcher](updating-your-launcher.md); do not repeat migration
or change Amazon component states. On another device, use [Install and setup](install.md).

When you explicitly choose to install a build, enable the TV's supported debugging option, connect
from the same network, and select the device by serial:

```sh
adb connect TV_IP:5555
adb devices -l
python3 tools/tv.py --serial TV_IP:5555 install build/refract-custom.apk
```

Replace `TV_IP:5555` with the intended device. Accept the TV's debugging prompt if one appears.
Do not disable another launcher as a troubleshooting shortcut.

## 5. Use the Android TV emulator

```sh
sdkmanager --list | grep "android-tv"
sdkmanager "emulator" "system-images;android-<api>;android-tv;<abi>"
avdmanager create avd -n tv -k "system-images;android-<api>;android-tv;<abi>" -d tv_1080p
emulator -avd tv &
adb devices -l
adb -s EMULATOR_SERIAL install -r build/refract-custom.apk
```

Replace `EMULATOR_SERIAL` with the emulator listed by `adb devices -l`. The emulator is useful for
layout and navigation, but does not establish Fire OS behavior, hardware performance, or HDMI and
Bluetooth compatibility. Use the existing permanent signing identity for a signed build. Build and
install only when explicitly requested.

## 6. Your first change

1. Read [CONTRIBUTING.md](../CONTRIBUTING.md) and inspect the relevant code.
2. Use the source and host checks appropriate for the change. Build an APK when one is explicitly
   requested; never automatically install it on a connected TV.
3. Use an emulator or an explicitly authorized device for UI verification, with an explicit ADB
   serial. Include Back, Home and remote focus where relevant.

### Developer shortcuts (adb)

Open any screen without clicking through the UI:

| Command | Opens |
|---|---|
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.MainActivity --es panel cc` | Control Center (`inputs`, `weather`, `present` also work) |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.MainActivity --es panel search --es q panchayat` | Search, with a query typed in |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.MainActivity --ez pick_profile true` | Profile switcher |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.SettingsActivity --es page "Top Shelf"` | A Settings page by its title |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.SetupActivity --ei step 3` | Setup page 0–7 |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.SetupActivity --ez skip true` | Marks setup as done |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.SettingsActivity --es tmdb_token <key>` | Sets a TMDB key |
| `adb -s TV_SERIAL shell am start -n com.arinaggarwal.refract/.MainActivity --es perf weather` | Hides one layer to measure its GPU cost (`status`, `hero`, `dockglass`, `shadows`; `reset` restores) |

Useful logs: `adb -s TV_SERIAL logcat -s TvHome` (Aether's own messages) and
`adb -s TV_SERIAL shell dumpsys gfxinfo com.arinaggarwal.refract` (frame times; see CONTRIBUTING.md).

## Troubleshooting

| Problem | Fix |
|---|---|
| `Missing …/aapt2` | Install `build-tools;36.0.0` and `platforms;android-35`, or set `ANDROID_HOME` |
| `javac not found` | Install JDK 17+ or set `JAVA_HOME` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key (e.g. a release build): restore the permanent signing key and rebuild; do not uninstall or clear settings |
| `adb: device offline` / `unauthorized` | Accept the prompt on the TV; `adb disconnect TV_IP:5555` then `adb connect TV_IP:5555` again |
| Home button still opens the old launcher | Finish setup's *Make Aether the Home Screen*, or see install.md |
| Install over Wi-Fi is very slow | Normal for 1 MB+ over a busy network; it finishes — don't wrap it in fixed sleeps |
