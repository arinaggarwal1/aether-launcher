# Root and Fire TV

Refract's ordinary launcher features do not require root. A rooted TV can offer additional maintenance
options, but root does not automatically turn them on, change the launcher into a system app, or
grant access to every vendor API.

## What works without root

Home, profiles, folders, search, weather, Control Center, Presentation mode, and the TV-side update
flow run as a regular Android app. Specific features still need the appropriate Android permission,
a compatible TV, a network connection, or a separate app. For example:

- Wi-Fi scanning needs Location access and enabled location services; network control depends on
  Android version and network security. Bluetooth access and audio controls also vary by version.
- Continue Watching and Do Not Disturb use notification access. Power Off and Sleep Timer use device
  administration or the user's enabled Refract accessibility service where the device supports it.
- Refract Remote is a separate companion app with its own setup; it is not included in the launcher.

See [Features](../features.md) for the permission and compatibility details.

## What root enables for maintenance

The optional [`tools/tv.py`](../../tools/tv.py) helper accepts an explicit ADB serial. It uses root
only for operations that need access to private app data or the device's preferred Home setting:

| Operation | What it does | Important limit |
|---|---|---|
| Back up an **original** launcher | Reads its app-private settings/files/databases and installed APKs into a private backup. It temporarily force-stops that app for consistency. | Requires the original app to be installed. The backup contains private data. |
| Migrate an original backup to the custom package | Copies a filtered archive into an empty, not-yet-initialized custom app data directory, after verifying the backup and device. | One-time path; it refuses an initialized destination. It excludes API-key ciphertext and pairing tokens, and disables Presentation autostart in the copied settings. |
| Select or roll back preferred Home | Attempts to set a selected installed package as Home and verifies the result. | Firmware can restrict it. Rollback cannot work unless the original package is installed. |

These commands modify the TV only when explicitly invoked; they are not part of a normal build,
release, or update. The guarded `install` subcommand checks the package, signing certificate, and
version before an in-place update; it does **not** require root or switch Home.

The migration procedure is historical for this Cube. The original `com.hydrainnit.refract` was
uninstalled after backup, and `com.arinaggarwal.refract` is already installed and selected as Home.
The helper requires the original to be installed and refuses to overwrite existing custom data.
Restoring an original app or its data would be a separate recovery operation. See
[Custom updates](../custom-updates.md) and [Updating your launcher](../updating-your-launcher.md).

## What root does not promise

- Root does not make the launcher's Bluetooth or Wi-Fi panels bypass all platform and firmware
  restrictions. Some actions open system settings; supported behavior varies across Android versions.
- Root does not give the Cube a discoverable list of the television's separate HDMI 1, 2, and 3 ports.
  Refract lists hardware inputs exposed by the device; Alexa equipment control may switch compatible
  televisions by voice.
- Root does not itself provide remote-button remapping or the companion app. Refract Remote is a
  separate installation and setup.
- Root does not preserve Android Keystore keys through an uninstall or device reset. Keep API keys
  separately for recovery; keep the launcher's signing key and its password in an encrypted offline
  backup. App-private data and keys may be exposed to someone with privileged access to the device.

On this setup, Amazon HomeActivity_vNext and Fire Home Starter are already disabled and Refract is
already Home. Leave those component states as they are. A connected device is not permission to
install, force-stop apps, migrate data, change components, or switch Home; choose each operation
explicitly and always identify the ADB serial.
