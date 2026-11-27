# Developer tools

| Path | Purpose |
|---|---|
| `release.py` | Build the custom APK, check the pinned signer, and reserve increasing version codes. Normally invoked via `../build.sh`. |
| `tv.py` | Explicit, serial-scoped TV operations: guarded install and optional rooted backup, historical migration, or Home selection. |
| `tests/` | Host infrastructure tests plus Java and Android instrumentation harnesses. |
| `test_updater_android.py` | Explicit Android updater instrumentation runner. |
| `test_recommendations_android.py` | Explicit Android recommendations instrumentation runner. |
| `brand.py`, `fonts/` | Branding asset generator and its font assets. |

Run host checks from the repository root:

```sh
python3 -m unittest discover -s tools/tests -v
git diff --check
```

The signed build, Android instrumentation runners, and TV operations can have device, network, or
signing side effects. Read [Custom updates](../docs/custom-updates.md),
[Getting started](../docs/getting-started.md), and [AGENTS.md](../AGENTS.md) first. Use an explicit
ADB serial; a connected TV is not permission to alter it.
