# Your custom Aether: build, migrate once, update forever

Permanent application ID: `com.arinaggarwal.refract`. TV label: **Aether Launcher**.
Original installation: `com.hydrainnit.refract`. It was backed up and later uninstalled on the user's Cube. Do not change the custom
package ID or signing certificate after installing it. This fork retains minSdk 24, targetSdk 34,
plain Java/Dex with no native libraries, and v1/v2 signing. The Cube 3rd gen on Fire OS 7 uses
Android 9/API 28; a compile SDK of 35 does not raise the minimum OS requirement.

## Everyday workflow

```sh
./build.sh
# Output: build/refract-custom.apk, build/SHA256SUMS, build/release.json
adb -s TV_IP:5555 install -r build/refract-custom.apk
```

Use this optional guarded installer to check the package, signing certificate, and increasing
versionCode before installation:

```sh
python3 tools/tv.py --serial TV_IP:5555 install build/refract-custom.apk
```

Neither build nor install changes default Home. Never uninstall, clear data, or use `-d` to
force a downgrade. Updates preserve the custom app's data directory, profiles, layouts, folders,
and Android Keystore keys. On a signature mismatch, restore the correct signing key and rebuild.
On a version downgrade error, build again. No script contains an uninstall or clear-data command.

Each build reserves a unique, increasing `versionCode` by creating a `refract-build/<number>` tag
in your GitHub repo. The number is the larger of UTC seconds since 2020 and the previous reservation
plus one. Create-only tags and retries prevent local/CI races, including reruns and clock rollback.
Failed builds can leave gaps. Keep these reservation tags; do not publish releases from them.
This deliberate shared counter requires network access, `gh auth login`, and repository write
permission even for local builds. VERSION_CODE overrides are intentionally unsupported.
Set the optional display version with `./build.sh --name 1.2.0`; it does not affect update ordering.

Requirements: Python 3.9+, GitHub CLI, JDK 17, Android SDK build-tools 36.0.0 and platform android-35:

```sh
sdkmanager 'build-tools;36.0.0' 'platforms;android-35' 'platform-tools'
gh auth login
```

On macOS the wrapper selects JDK 17 when JAVA_HOME is unset. ANDROID_HOME/ANDROID_SDK_ROOT and
JAVA_HOME can override tool locations. There is no Gradle dependency.

## Permanent signing key and recovery

The private key and random password live outside Git at:

- `~/.config/refract-launcher/signing/release.p12`
- `~/.config/refract-launcher/signing/password`

The directory is mode 700 and both files mode 600. `config/signing-cert.sha256` is the public
certificate fingerprint, safe to commit. Every build verifies it before signing, then verifies
the APK again. Builds fail when the key is missing or different; they never generate a debug or
replacement key. The same key signs local and GitHub builds.

**Make an encrypted offline backup of the entire signing directory now**, including its password.
GitHub secrets cannot be downloaded as a recovery copy. Losing this key means losing the ability
to update the installed custom app without migrating to another package. Keep the backup separate
from this computer. On another trusted computer, restore both files with their original permissions.
Do not store the key, password, TV backups, or an unencrypted signing backup in the repository.

One-time provisioning tools (already run for this fork):

```sh
python3 tools/release.py init-signing      # Refuses if a key or public pin already exists
python3 tools/release.py github-secrets    # Uploads same identity; never creates another key
```

GitHub repository secrets are `REFRACT_KEYSTORE_BASE64` and `REFRACT_KEYSTORE_PASSWORD`. They contain
the same permanent key, encrypted by GitHub at rest. The public repo never receives the key as a
file or artifact. Anyone able to change a trusted signing workflow and run it can use its secrets:
restrict write access and protect release/workflow changes. Secrets are never used for pull_request
builds in this workflow. Signing material is removed from the ephemeral runner after every run.

## GitHub releases

The launcher can check these from **Settings > About > Check for Updates** after the updater build
has been installed once. See [Updating your launcher](updating-your-launcher.md#check-for-updates-on-the-tv).

Commit and push the code and `.github/workflows/release.yml` before making a release from that
commit. Publish a release with a descriptive tag such as `v1.0.0-custom.1` in your GitHub repo.
The **Signed custom APK** workflow builds and attaches:

- `refract-custom.apk`: installable APK
- `SHA256SUMS`: download integrity check
- `release.json`: package, versionCode, signing fingerprint, APK hash, and source commit

The workflow also supports **Run workflow** and produces a downloadable Actions artifact without
publishing a release. Re-running a release build reserves a newer version and replaces that
release's assets. Prefer a new release tag for a new source change. Ordinary pushes do not publish.
Local/CI builds serialize independently and use the same remote reservation mechanism.

## One-time migration on your rooted Cube

**Already completed on this Cube:** the custom app is Home, and the original was uninstalled at the
user's request after backup. Do not repeat these steps for updates. The rollback command below only
works if the original has first been reinstalled; restoring its data needs a separate recovery. See
[Updating your launcher](updating-your-launcher.md) for the current update process.

Your supplied reference says these Amazon components are already disabled:
`com.amazon.tv.launcher/.ui.HomeActivity_vNext` and
`com.amazon.firehomestarter/.HomeStarterActivity`. **Leave those states alone.** The helper does not
enable or disable Amazon components, change Magisk modules, or remap partner app buttons.
No migration or Home command runs as part of a build, release, or install.

Keep an ADB connection available throughout. Replace TV_IP with your Cube's address, not an
emulator or another TV. The helpers require an explicit serial and Android user 0. Magisk must
allow ADB shell's `su` request. Do not use `adb backup`: the app disables Android backup.

1. Create a consistent, private backup **before installing or switching anything**:

   ```sh
   adb connect TV_IP:5555
   python3 tools/tv.py --serial TV_IP:5555 backup backups/original-before-custom
   ```

   This briefly force-stops the original launcher, archives its preferences/files/databases with
   root, copies its installed APK(s), records the prior Home component and SHA-256 hashes, and
   relaunches it if it was running. No settings are changed. Treat the backup as sensitive.
   Keep an additional encrypted offline copy. A failed backup must be resolved before continuing.

2. Build/install the **separate custom package**, then copy settings before its first launch:

   ```sh
   ./build.sh
   python3 tools/tv.py --serial TV_IP:5555 install build/refract-custom.apk
   python3 tools/tv.py --serial TV_IP:5555 migrate backups/original-before-custom
   ```

   Migration refuses a different TV, missing original app, corrupt backup, existing target data,
   unsafe archive paths, symlinks, or a repeated migration. It writes only to the custom app,
   fixes ownership and SELinux labels, and leaves the original data/default Home unchanged.
   If the custom app has already been opened, stop here: migration deliberately refuses to
   overwrite it. Keep both installs; use the original until a separate recovery is reviewed.
   If migration is interrupted, it also refuses to overwrite partial data on a retry.

   API-key ciphertext and presentation browser pairing tokens are excluded. Android Keystore keys
   are tied to the old UID, so re-enter TMDB/Pexels keys. Presentation mode/autostart are turned off
   in the copied settings to avoid two servers competing; enable later if wanted. Layouts, hidden
   apps, profiles, appearance, weather preferences, and Continue Watching files are copied.
   Notification listener, accessibility, device-admin and runtime permissions must be granted
   separately to the custom package. They are OS state, not transferable preference files.

3. Preview it while the working original remains Home:

   ```sh
   adb -s TV_IP:5555 shell am start -n com.arinaggarwal.refract/.MainActivity
   ```

   Check profiles, layout, folders, app launching and Settings. Re-enter API keys if used.
   Settings offers permission flows. Your reference's optional root notification command becomes:

   ```sh
   adb -s TV_IP:5555 shell "su -c 'cmd notification allow_listener com.arinaggarwal.refract/com.arinaggarwal.refract.MediaWatchService'"
   ```

4. Only after you verify the custom build, explicitly switch Home:

   ```sh
   python3 tools/tv.py --serial TV_IP:5555 set-home
   ```

   This selects `com.arinaggarwal.refract/.MainActivity`, checks Android's preferred Home resolver,
   and opens it. Press the physical Home button yourself to verify Amazon's input behavior.
   Root/firmware policies can differ; if it fails, leave the original installed and use rollback.
   The Hulu/partner button follows a separate mechanism and is unaffected.

If the original is still installed, switch Home back to it with:

```sh
python3 tools/tv.py --serial TV_IP:5555 rollback-home
# Equivalent emergency command:
adb -s TV_IP:5555 shell "su -c 'pm set-home-activity com.hydrainnit.refract/.MainActivity'"
adb -s TV_IP:5555 shell am start -n com.hydrainnit.refract/.MainActivity
```

When the original is still installed with its data, this switch needs no uninstall or restore.
On the user's Cube, the original was removed and needs reinstalling and data recovery first. The backup is a recovery artifact; there is intentionally no
automated destructive restore over your working original.

## Verification

```sh
python3 -m unittest discover -s tools/tests -v
git diff --check
```

The build verifies APK identity, certificate, zip alignment and signature validity with API 28 as
the minimum verifier. Device migration, hardware remote Home behavior and Fire OS performance
still need validation on your exact Cube; building an APK alone cannot prove those behaviors.

References: [Amazon Cube specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-cube.html),
[Android app signing](https://developer.android.com/studio/publish/app-signing),
[Android versioning](https://developer.android.com/studio/publish/versioning),
[GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release).
