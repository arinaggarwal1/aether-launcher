# Personal Refract fork

- Permanent application ID: `com.arinaggarwal.refract`. Source lives in
  `src/com/arinaggarwal/refract`. Keep the package, signing identity, and profile/preference storage
  names stable so updates preserve settings and layouts.
- Read `docs/custom-updates.md` before changing build, signing, migration, or TV deployment behavior.
  Follow `CONTRIBUTING.md` for native UI and performance conventions.
- Build with `./build.sh`. It signs with the permanent private key outside Git, checks
  `config/signing-cert.sha256`, and reserves an increasing versionCode in GitHub. It requires network
  access and authenticated `gh` repository write access. Do not replace it with debug signing or
  manually reset/override version codes. Never delete the `refract-build/*` reservation tags.
- Private signing material lives in `~/.config/refract-launcher/signing` (or REFRACT_SIGNING_DIR).
  Never print or commit it, include it in APK artifacts, or generate a replacement when it is
  missing. Restore the user's existing encrypted backup instead. Keep only the public pin in Git.
- The original working installation is `com.hydrainnit.refract` on a rooted Fire TV Cube 3rd gen,
  Fire OS 7.7.1.7 (Android API 28, ARM 32-bit). Never automatically uninstall, replace, disable, or
  clear its data. Build/update infrastructure must keep the two packages separate. Keep minSdk 24
  and compatibility with API 28; guard newer platform APIs. Avoid native ABI dependencies.
- Never install on a connected physical TV, force-stop its apps, migrate its data, alter Amazon
  component states, or switch preferred Home unless the user explicitly requests that operation.
  An attached device is not permission to modify it. Always select an explicit adb serial.
- Amazon HomeActivity_vNext and Fire Home Starter are already disabled in the user's setup.
  Preserve their states. Migration only selects the custom preferred Home after the user verifies
  it; rollback selects the still-installed original. Partner app buttons are separate.
- Use a feature branch and PR; do not push to main. A build or PR does not authorize publishing a
  release or changing the TV. User-authorized remote operations still follow session approvals.
- Validate infrastructure changes with `python3 -m unittest discover -s tools/tests -v`, build the
  APK, and run `git diff --check`. For Home changes, exercise remote focus, Back/Home, persistence,
  and update retention on an emulator or explicitly authorized device. Report untested Fire OS
  behavior and hardware performance honestly.
