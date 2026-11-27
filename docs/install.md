# Install and set up

Aether Launcher is a separate app with the package `com.arinaggarwal.refract`. An APK from this fork
cannot update the original `com.hydrainnit.refract` package. The personal target is a Fire TV Cube
3rd gen on Fire OS 7; the launcher supports Android 7.0 / API 24 and newer, though available Home
and permission screens vary by device.

## Already installed on the personal Cube

The custom launcher is already Home. Its installed version may still show the Refract name until
the Aether branding update is installed. The original launcher was backed up and then uninstalled. Do not
repeat the historical migration, disable additional Amazon components, or assume the original is
still available for rollback. For normal updates, follow [Updating your launcher](updating-your-launcher.md).
Restoring the original would be a separate recovery operation; see [Custom updates](custom-updates.md).

## First install on another device

1. Download `refract-custom.apk` from the project's [GitHub releases](https://github.com/arinaggarwal1/aether-launcher/releases).
2. Install it using an installation method supported by the device. When using ADB, select the
   intended serial explicitly, for example `adb -s TV_IP:5555 install -r refract-custom.apk`.
3. Open **Aether Launcher** and follow setup. Use the device's own Home chooser or settings when
   offered; availability differs across devices.
4. Grant optional permissions only for features you want, using the on-screen paths in setup or
   **Settings → Permissions**.

A build of this personal fork uses the permanent signing identity and a GitHub version reservation.
Do not create a replacement key for an existing installation. See
[Build and signing](custom-updates.md) and [Getting started](getting-started.md).

## Permissions and optional integrations

| Feature | What it needs |
|---|---|
| Wi-Fi name and nearby networks | Location permission; Wi-Fi scanning also needs location services enabled. Connection options vary by Android version and network security. |
| Bluetooth devices | On Android 12+, Nearby devices permission. Direct audio controls depend on the device and OS. |
| Continue Watching and Do Not Disturb | Notification access, where the TV supports it. |
| Power Off, Sleep Timer, and full Clean | Supported device-admin or the Aether accessibility service, enabled through the TV's settings. |
| Voice search | Microphone permission or the TV's own voice prompt, depending on the selected input method. |
| Movies and TMDB content | Your own TMDB key, entered in Settings. Other recommendation sources may not need a key. |
| AirPlay receiver | A separate compatible receiver app, such as [AirPlay Server](https://github.com/jqssun/android-airplay-server). |

See [Features](features.md) for details and [Root and Fire TV](guides/root-and-fire-tv.md) for the
limits of privileged device operations. Aether can request access on screen where supported;
some firmware omits or limits these paths.

## Returning to another launcher

Use the device's supported Home selection flow. The optional helper's `rollback-home` operation
only applies if the original package is actually installed; it cannot restore an uninstalled app or
its data. Do not uninstall or clear Aether Launcher to troubleshoot an update, because doing so can
remove profiles, layouts, preferences, and Keystore-backed keys.
