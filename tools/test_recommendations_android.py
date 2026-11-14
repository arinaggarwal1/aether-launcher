#!/usr/bin/env python3
"""Run recommendation routing checks on an emulator; installs minimal player intent stubs."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
from release import CONFIG, ROOT, KEY, signing_env, sdk_paths


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        raise SystemExit('This test only runs on an emulator, never a physical TV')
    bt, jar = sdk_paths()
    env = signing_env()
    adb = ['adb', '-s', args.serial]
    with tempfile.TemporaryDirectory(prefix='refract-recommendations-test-') as tmp:
        t = Path(tmp)
        # Never replace a real player on an emulator with a test stub.
        for package, scheme in [('com.nuvio.tv', 'nuvio'), ('com.stremio.one', 'stremio')]:
            existing = run(adb + ['shell', 'pm', 'list', 'packages', package], capture_output=True, text=True)
            if existing.stdout.strip():
                raise SystemExit(f'Requires an emulator without {package}; will not replace it')
        def apk(manifest, name, dex=None):
            (t / 'AndroidManifest.xml').write_text(manifest)
            run([bt / 'aapt2', 'link', '-o', t / 'unsigned.apk', '-I', jar,
                 '--manifest', t / 'AndroidManifest.xml', '--min-sdk-version', CONFIG['minSdk'],
                 '--target-sdk-version', CONFIG['targetSdk']])
            if dex:
                with zipfile.ZipFile(t / 'unsigned.apk', 'a') as out:
                    out.write(dex, 'classes.dex')
            run([bt / 'zipalign', '-f', '4', t / 'unsigned.apk', t / 'aligned.apk'])
            run([bt / 'apksigner', 'sign', '--ks', KEY, '--ks-key-alias', CONFIG['keyAlias'],
                 '--ks-pass', 'env:REFRACT_KEY_PASSWORD', '--key-pass', 'env:REFRACT_KEY_PASSWORD',
                 '--out', t / name, t / 'aligned.apk'], env=env)
            run(adb + ['install', '-r', '-t', t / name])
        stubs = []
        try:
            for package, scheme in [('com.nuvio.tv', 'nuvio'), ('com.stremio.one', 'stremio')]:
                apk('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
                  <application android:label="Player test stub" android:testOnly="true" android:hasCode="false">
                    <activity android:name="android.app.Activity" android:exported="true">
                      <intent-filter><action android:name="android.intent.action.VIEW" />
                        <category android:name="android.intent.category.DEFAULT" />
                        <data android:scheme="{scheme}" /></intent-filter>
                      <intent-filter><action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LEANBACK_LAUNCHER" /></intent-filter>
                    </activity></application></manifest>''', scheme + '.apk')
                stubs.append(package)
            (t / 'classes').mkdir()
            (t / 'dex').mkdir()
            sources = list((ROOT / 'src').rglob('*.java'))
            # Resource symbols are required by the application classes used by the harness.
            run([bt / 'aapt2', 'compile', '--dir', ROOT / 'res', '-o', t / 'resources.zip'])
            run([bt / 'aapt2', 'link', '-o', t / 'resources.apk', '-I', jar,
                 '--manifest', ROOT / 'AndroidManifest.xml', '--java', t / 'gen', t / 'resources.zip'])
            run(['javac', '--release', '11', '-classpath', jar, '-d', t / 'classes'] + sources +
                list((t / 'gen').rglob('*.java')) + [ROOT / 'tools/tests/android/RecommendationsInstrumentation.java'])
            classes = list((t / 'classes/com/arinaggarwal/refract').glob('RecommendationsInstrumentation*.class'))
            run([bt / 'd8', '--release', '--min-api', CONFIG['minSdk'], '--lib', jar,
                 '--output', t / 'dex'] + classes)
            apk('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.arinaggarwal.refract.recommendationtests">
                <application android:label="Recommendation tests" android:testOnly="true" />
                <instrumentation android:name="com.arinaggarwal.refract.RecommendationsInstrumentation"
                  android:targetPackage="com.arinaggarwal.refract" /></manifest>''', 'tests.apk', t / 'dex/classes.dex')
            result = run(adb + ['shell', 'am', 'instrument', '-w', '-r',
                'com.arinaggarwal.refract.recommendationtests/com.arinaggarwal.refract.RecommendationsInstrumentation'],
                capture_output=True, text=True)
            print(result.stdout)
            if 'PASS: TMDB' not in result.stdout or 'INSTRUMENTATION_CODE: 0' not in result.stdout:
                raise SystemExit('Recommendation instrumentation failed')
            run(adb + ['install', '-r', ROOT / 'build/refract-custom.apk'])
            result = run(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'retention', '1',
                'com.arinaggarwal.refract.recommendationtests/com.arinaggarwal.refract.RecommendationsInstrumentation'],
                capture_output=True, text=True)
            print(result.stdout)
            if 'PASS: update retained' not in result.stdout or 'INSTRUMENTATION_CODE: 0' not in result.stdout:
                raise SystemExit('Recommendation update retention failed')
        finally:
            # These packages were created above on this emulator only.
            for package in stubs:
                run(adb + ['uninstall', package])


if __name__ == '__main__':
    main()
