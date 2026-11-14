# Updating your Refract launcher

Your custom launcher is already installed on the Fire TV Cube as
`com.arinaggarwal.refract`. Future updates install over that same app and keep its settings,
profiles, app layout, and folders. You do not need to repeat the migration or uninstall the app.

## The easy way: ask Codex

Tell Codex what you want to change. Once the change is ready, ask:

> Build the launcher and install the update on my Fire TV.

Codex can build the signed APK and install it while the Cube is connected to your Mac through
ADB. The current address is `172.20.25.97:5555`; the IP may change, so check the Cube's network
settings and `adb devices` if it cannot connect. If you only ask for a code change, installing it
on the TV is a separate step: explicitly ask Codex to install the update when you are ready.

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

A local build is enough for everyday updates. If you want a downloadable GitHub release, first make
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
