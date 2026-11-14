> For this personal Fire TV fork, follow **[Custom updates and safe migration](custom-updates.md)**.
> Keep the original `com.hydrainnit.refract` installed as your fallback.

# Install and set up

## Requirements

- An Android TV box on Android 7.0+ (tested on a Xiaomi Mi Box, `MIBOX4`, Android 12).
- Developer options and **network debugging** turned on (Settings → Device Preferences → About →
  click *Build* seven times).
- A computer with `adb` (Android SDK platform-tools) on the same network.

## Build

```sh
./build.sh
./build.sh --name 1.0.0-custom.1     # optional display version
```

Every build uses the same permanent custom key and reserves an increasing versionCode.
See [Custom updates](custom-updates.md) for tool setup, signing-key recovery and GitHub releases.

## Install and make it the home screen

On your rooted Fire TV Cube, use the **[one-time migration procedure](custom-updates.md#one-time-migration-on-your-rooted-cube)**.
It backs up the working original, installs the custom package alongside it, copies settings before
first launch, and switches Home only when you explicitly choose. Do not change Amazon component
states again. Future updates are simply `adb -s TV_IP:5555 install -r build/refract-custom.apk`.

## One-time permissions

Refract asks for these **on screen** (setup's last page and Settings → Permissions): Android's prompts,
the system's own settings screens, or Refract's accessibility switch. The commands below are only a
last resort for TVs that have no such screen.

| Feature | Command |
|---|---|
| Continue Watching + Do Not Disturb | `adb shell cmd notification allow_listener com.arinaggarwal.refract/com.arinaggarwal.refract.MediaWatchService` |
| Power Off + Sleep Timer | `adb shell dpm set-active-admin com.arinaggarwal.refract/.SleepReceiver\$Admin` |
| Wi-Fi name in Control Center | `adb shell pm grant com.arinaggarwal.refract android.permission.ACCESS_FINE_LOCATION` |
| Search dictation (microphone) | `adb shell pm grant com.arinaggarwal.refract android.permission.RECORD_AUDIO` — not normally needed: without it, Search uses the TV's own voice prompt |

## Optional: recommendation source

Recommendations use JustWatch by default (no setup). **Settings → Top Shelf → Source** also offers
**Stremio** (Cinemeta's popular movies and series, no setup; titles open in the Stremio app, or in
Nuvio when only Nuvio is installed) and **TMDB**. For TMDB, enter a free API key from themoviedb.org (Settings → API). The short v3 *API Key* is
easiest to type with a remote. It can also be set from a computer:

```sh
adb shell am start -n com.arinaggarwal.refract/.SettingsActivity --es tmdb_token <token>
```

## Optional: AirPlay receiver

To AirPlay from a Mac, iPhone or iPad to the TV, install the open-source
[AirPlay Server](https://github.com/jqssun/android-airplay-server) app (GPL-3.0; a separate app, not
part of Refract). Download `app-release.apk` from its
[latest release](https://github.com/jqssun/android-airplay-server/releases/latest) and install it:

```sh
adb install -r app-release.apk
```

Open it once from Refract, set a name (e.g. "Mi Box") in its Settings and keep *Start on boot* on. The
TV then appears in AirPlay on Apple devices on the same Wi-Fi. Refract's AirPlay guard keeps it visible
after the TV sleeps ([features](features.md#airplay-with-the-airplay-server-app)). Tested with
version 0.0.36 on a Mi Box (Android 12) and an Android 9 TV.

## Undo

Switch back to the still-installed original, preserving both apps' data:

```sh
python3 tools/tv.py --serial TV_IP:5555 rollback-home
```
