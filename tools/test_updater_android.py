#!/usr/bin/env python3
"""Run updater integration tests on an emulator with a newer local signed APK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from release import CONFIG, ROOT, KEY, signing_env, sdk_paths


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--candidate', type=Path, default=ROOT / 'build/refract-custom.apk')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--installer', action='store_true', help='Open the system installer confirmation on the emulator')
    mode.add_argument('--permission', action='store_true', help='Exercise the install permission guidance on the emulator')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        raise SystemExit('This test only runs on an emulator, never a physical TV')
    bt, jar = sdk_paths()
    env = signing_env()
    adb = ['adb', '-s', args.serial]
    with tempfile.TemporaryDirectory(prefix='refract-updater-test-') as tmp:
        t = Path(tmp)
        (t / 'assets').mkdir()
        (t / 'classes').mkdir()
        (t / 'dex').mkdir()
        shutil.copyfile(args.candidate, t / 'assets/candidate.apk')
        run(['keytool', '-genkeypair', '-keystore', t / 'wrong.p12', '-storepass', 'android',
             '-keypass', 'android', '-alias', 'wrong', '-keyalg', 'RSA', '-validity', '1000',
             '-dname', 'CN=Updater Test'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        run([bt / 'apksigner', 'sign', '--ks', t / 'wrong.p12', '--ks-pass', 'pass:android',
             '--key-pass', 'pass:android', '--out', t / 'assets/wrong-signer.apk', args.candidate])
        (t / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.arinaggarwal.refract.updatertests">
            <application android:label="Refract updater tests" android:testOnly="true" />
            <instrumentation android:name="com.arinaggarwal.refract.UpdaterInstrumentation"
                android:targetPackage="com.arinaggarwal.refract" />
            </manifest>''')
        run([bt / 'aapt2', 'link', '-o', t / 'unsigned.apk', '-I', jar,
             '--manifest', t / 'AndroidManifest.xml', '-A', t / 'assets',
             '--min-sdk-version', CONFIG['minSdk'], '--target-sdk-version', CONFIG['targetSdk']])
        package = ROOT / 'src/com/arinaggarwal/refract'
        harness = ROOT / 'tools/tests/android/UpdaterInstrumentation.java'
        run(['javac', '--release', '11', '-classpath', jar, '-d', t / 'classes',
             package / 'UpdatePolicy.java', package / 'UpdateProvider.java', package / 'Updates.java', harness])
        classes = list((t / 'classes/com/arinaggarwal/refract').glob('UpdaterInstrumentation*.class'))
        run([bt / 'd8', '--release', '--min-api', CONFIG['minSdk'], '--lib', jar,
             '--output', t / 'dex'] + classes)
        import zipfile
        with zipfile.ZipFile(t / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as out:
            out.write(t / 'dex/classes.dex', 'classes.dex')
        run([bt / 'zipalign', '-f', '4', t / 'unsigned.apk', t / 'aligned.apk'])
        run([bt / 'apksigner', 'sign', '--ks', KEY, '--ks-key-alias', CONFIG['keyAlias'],
             '--ks-pass', 'env:REFRACT_KEY_PASSWORD', '--key-pass', 'env:REFRACT_KEY_PASSWORD',
             '--out', t / 'tests.apk', t / 'aligned.apk'], env=env)
        run(adb + ['install', '-r', '-t', t / 'tests.apk'])
        extra = []
        if args.installer:
            run(adb + ['shell', 'appops', 'set', 'com.arinaggarwal.refract', 'REQUEST_INSTALL_PACKAGES', 'allow'])
            extra = ['-e', 'install_prompt', '1']
        elif args.permission:
            run(adb + ['shell', 'appops', 'set', 'com.arinaggarwal.refract', 'REQUEST_INSTALL_PACKAGES', 'deny'])
            extra = ['-e', 'permission_prompt', '1']
        result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w'] + extra +
                                ['com.arinaggarwal.refract.updatertests/com.arinaggarwal.refract.UpdaterInstrumentation'],
                                capture_output=True, text=True, check=True)
        print(result.stdout)
        if 'PASS: release discovery' not in result.stdout or 'INSTRUMENTATION_CODE: 0' not in result.stdout:
            raise SystemExit('Updater instrumentation failed')


if __name__ == '__main__':
    main()
