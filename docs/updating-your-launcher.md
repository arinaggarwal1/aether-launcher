# Updating your Refract launcher

Your custom launcher is already installed on the Fire TV Cube as
`com.arinaggarwal.refract`. Future updates install over that same app and keep its settings,
profiles, app layout, and folders. You do not need to repeat the migration or uninstall the app.
Saved API keys and other app preferences also remain in place: Android retains the app's private
data and its Android Keystore key when the signed update is installed over the existing app.
Future changes must preserve these storage names or safely migrate their contents.

This applies to an in-place update on the same device. Uninstalling the launcher, clearing its
data, resetting the device or losing its Android Keystore can remove settings or make encrypted API
keys unreadable; reinstalling the APK or restoring preference files alone cannot restore that
Keystore key. Keep your API keys separately in a secure password manager so you can re-enter them
if you ever need to recover or move to another device.

## The easy way: ask Codex

Tell Codex what you want to change. Once the change is ready, ask:

> Build the launcher and install the update on my Fire TV.

Codex can build the signed APK and install it while the Cube is connected to your Mac through
ADB. The current address is `172.20.25.97:5555`; the IP may change, so check the Cube's network
settings and `adb devices` if it cannot connect. If you only ask for a code change, installing it
on the TV is a separate step: explicitly ask Codex to install the update when you are ready.

## Check for updates on the TV

Once the version containing the updater has been installed, open **Settings > About > Check for
Updates**. If a newer GitHub release is available, choose **Download**, then **Install**. The TV
will show its own installation confirmation. If asked, allow Refract Custom under the TV's
**Install unknown apps** setting, return to About, and choose **Check for Updates** again to install.
Your folders and settings stay in place.

The first updater build must be installed once using ADB or Codex, because the currently installed
version cannot update itself yet. A release must also be published on GitHub before there is
anything to find. If you see **No Releases Yet**, nothing has been published. If the release is
still being built, wait a few minutes and check again. A release is available after the signed APK
and its `release.json` metadata have both been attached by GitHub Actions.

The repository must remain public for the TV to read releases without a GitHub login.
The launcher checks the release package, version, SHA-256 checksum, and permanent signing
certificate before opening the system installer. Android verifies the update again during install.
This check is manual and only contacts GitHub when you select the row.

## Update it yourself

On your Mac, open Terminal and run:

```sh
cd "/Users/arinaggarwal/Documents/Software Dev/refract-launcher"
adb connect 172.20.25.97:5555
adb devices
./build.sh
python3 tools/tv.py --serial 172.20.25.97:5555 install build/refract-custom.apk
```

Replace the address in both commands if the Cube's IP changes. Accept an ADB prompt on the TV if
one appears, and check that `adb devices` lists it as `device`. You can also install the built APK
with Android's update command:

```sh
adb -s 172.20.25.97:5555 install -r build/refract-custom.apk
```

The Python installer adds checks for the package name, signing certificate, and version before
running `adb install -r`. It does not change the default Home launcher. If a command fails, stop
and ask Codex to check the output. Do not uninstall the app or force a downgrade to fix an error.

## What the build does

`./build.sh` compiles the current files on your Mac, including uncommitted changes. It reserves a
new version number in the GitHub repository, signs the APK with your permanent key, and verifies
the package, signature, and APK alignment. The results are:

- `build/refract-custom.apk` - the installable update.
- `build/SHA256SUMS` - the APK's checksum.
- `build/release.json` - its version, signing fingerprint, source commit, and whether the checkout
  had uncommitted changes.

The build needs internet access, GitHub CLI authentication with write access to the repository,
JDK 17, and the Android SDK. If GitHub authentication expires, run `gh auth login` and retry. A
failed build may skip a version number; that is fine. Keep the `refract-build/*` GitHub tags: they
coordinate version numbers across this Mac and GitHub Actions.

Android accepts an update when its package and signing identity match the installed app and the
version is newer. Keep using `com.arinaggarwal.refract` and the same signing key. If the installer
reports a different signature, restore your existing key; if the version is too old, build again.

## Keep the signing key safe

The permanent key and its password are in `~/.config/refract-launcher/signing` on this Mac. Back up
that entire directory to encrypted offline storage, separately from this computer. Do not commit it
to Git or share it. GitHub Actions has encrypted copies of the signing secrets for building releases,
but those secrets cannot be downloaded as a backup. If the local key is missing, restore your
backup; do not generate a new one for the existing installation.

## Optional: download a build from GitHub

A local build is enough for everyday updates. For TV-side updates, first make
sure the build workflow and your changes are committed and pushed to the commit you will release.
Publish a release with a new tag in GitHub; the **Signed custom APK** action attaches
`refract-custom.apk`, `SHA256SUMS`, and `release.json`. Download the APK and install it with
`adb -s 172.20.25.97:5555 install -r /path/to/refract-custom.apk`.

A pull request is not required for this personal repository. The original feature work was pushed
to a feature branch, so check that the commit you release actually contains the changes and workflow
you want. A release from another branch or an older commit will build that commit's code.

## Original launcher backup

The original `com.hydrainnit.refract` was uninstalled after its settings and APK were backed up at
`backups/original-before-custom` on this Mac. This backup contains private app data; keep an encrypted
copy and do not commit it. Switching back would require reinstalling and restoring the original;
the old `rollback-home` command alone cannot work while that app is uninstalled.

For the full signing, release, and historical migration details, see
[Custom updates and migration](custom-updates.md).
