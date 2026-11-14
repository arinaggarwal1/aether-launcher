#!/usr/bin/env python3
"""Permanent signing identity, globally ordered build numbers, and plain-SDK APK builds."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / 'config/fork.json').read_text())
PIN = ROOT / 'config/signing-cert.sha256'
SIGNING = Path(os.environ.get('REFRACT_SIGNING_DIR', Path.home() / '.config/refract-launcher/signing'))
KEY = SIGNING / 'release.p12'
PASSWORD = SIGNING / 'password'


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def output(args, **kwargs):
    return subprocess.check_output([str(a) for a in args], text=True, **kwargs).strip()


def api(path, method='GET', fields=None):
    args = ['gh', 'api', 'repos/' + CONFIG['repository'] + ('/' + path if path else '')]
    if method != 'GET':
        args += ['--method', method]
    for k, v in (fields or {}).items():
        args += ['-f', k + '=' + str(v)]
    return json.loads(output(args))


def signing_env():
    if not KEY.is_file() or not PASSWORD.is_file():
        raise RuntimeError('Signing key missing. Restore your secure signing backup; never create a replacement key.')
    if os.name == 'posix':
        for p in (KEY, PASSWORD):
            if p.stat().st_mode & 0o077:
                raise RuntimeError('Signing files must be private (chmod 600): ' + str(p))
    return dict(os.environ, REFRACT_KEY_PASSWORD=PASSWORD.read_text().strip())


def certificate(env):
    data = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(KEY),
                                   '-alias', CONFIG['keyAlias'], '-storepass:env', 'REFRACT_KEY_PASSWORD'], env=env)
    return hashlib.sha256(data).hexdigest()


def init_signing():
    # An existing public pin means this identity has already shipped: never silently replace it.
    if PIN.exists() or KEY.exists() or PASSWORD.exists():
        raise RuntimeError('Signing identity already exists or is partially initialized. Restore it; initialization refused.')
    SIGNING.mkdir(parents=True, mode=0o700, exist_ok=True)
    SIGNING.chmod(0o700)
    password = secrets.token_urlsafe(48)
    with PASSWORD.open('x') as f:
        os.chmod(PASSWORD, 0o600)
        f.write(password + '\n')
    env = dict(os.environ, REFRACT_KEY_PASSWORD=password)
    run(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', KEY,
         '-alias', CONFIG['keyAlias'], '-storepass:env', 'REFRACT_KEY_PASSWORD',
         '-keypass:env', 'REFRACT_KEY_PASSWORD', '-keyalg', 'RSA', '-keysize', '3072',
         '-sigalg', 'SHA256withRSA', '-validity', '25000', '-dname', 'CN=Arin Refract Custom'], env=env)
    KEY.chmod(0o600)
    PIN.write_text(certificate(env) + '\n')
    print('Created private signing identity in ' + str(SIGNING))
    print('Back up this entire directory to encrypted offline storage. Public fingerprint: ' + PIN.read_text().strip())


def reserve_version():
    """Create-only remote tags act as a counter shared by all machines and CI; races retry."""
    for attempt in range(10):
        refs = api('git/matching-refs/tags/refract-build/')
        previous = [int(r['ref'].rsplit('/', 1)[1]) for r in refs
                    if re.fullmatch(r'refs/tags/refract-build/\d+', r['ref'])]
        code = max([int(time.time()) - 1577836800] + [n + 1 for n in previous])
        if not 1 <= code <= 2100000000:
            raise RuntimeError('Version counter exceeds Android limits')
        # Point reservation at an existing remote commit; local edits need not be pushed to build.
        repo = api('')
        sha = api('git/ref/heads/' + repo['default_branch'])['object']['sha']
        try:
            api('git/refs', 'POST', {'ref': 'refs/tags/refract-build/' + str(code), 'sha': sha})
            return code
        except subprocess.CalledProcessError:
            if attempt == 9:
                raise
    raise RuntimeError('Unable to reserve version code')


def sdk_paths():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               (Path.home() / ('Library/Android/sdk' if sys.platform == 'darwin' else 'Android/Sdk')))
    bt = sdk / 'build-tools' / CONFIG['buildTools']
    jar = sdk / 'platforms' / ('android-' + str(CONFIG['compileSdk'])) / 'android.jar'
    for p in (bt / 'aapt2', bt / 'd8', bt / 'zipalign', bt / 'apksigner', jar):
        if not p.exists():
            raise RuntimeError('Missing SDK tool: ' + str(p) + '. See docs/custom-updates.md.')
    return bt, jar


def build(name=None):
    env = signing_env()
    expected = PIN.read_text().strip().lower()
    if certificate(env) != expected:
        raise RuntimeError('Signing certificate differs from the permanent public fingerprint. Build refused.')
    bt, jar = sdk_paths()
    for tool in ('javac', 'java', 'keytool', 'gh'):
        if not shutil.which(tool):
            raise RuntimeError('Missing tool: ' + tool)
    import xml.etree.ElementTree as ET
    manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    if manifest.attrib['package'] != CONFIG['applicationId']:
        raise RuntimeError('Manifest package differs from permanent applicationId')
    code = reserve_version()
    name = name or os.environ.get('VERSION_NAME') or 'custom-' + str(code)
    out = ROOT / 'build'
    # Serialize locally; never delete an existing distributable while another build is running.
    with tempfile.TemporaryDirectory(prefix='refract-build-') as temp:
        work = Path(temp)
        for d in ('gen', 'classes', 'dex'):
            (work / d).mkdir()
        run([bt / 'aapt2', 'compile', '--dir', ROOT / 'res', '-o', work / 'res.zip'])
        run([bt / 'aapt2', 'link', '-o', work / 'unsigned.apk', '-I', jar,
             '--manifest', ROOT / 'AndroidManifest.xml', '-A', ROOT / 'assets', '--java', work / 'gen',
             '--min-sdk-version', CONFIG['minSdk'], '--target-sdk-version', CONFIG['targetSdk'],
             '--version-code', code, '--version-name', name, work / 'res.zip'])
        sources = sorted((ROOT / 'src').rglob('*.java')) + sorted((work / 'gen').rglob('*.java'))
        run(['javac', '-encoding', 'UTF-8', '--release', '11', '-classpath', jar,
             '-d', work / 'classes'] + sources)
        run([bt / 'd8', '--release', '--min-api', CONFIG['minSdk'], '--lib', jar,
             '--output', work / 'dex'] + sorted((work / 'classes').rglob('*.class')))
        import zipfile
        with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
            for dex in sorted((work / 'dex').glob('*.dex')):
                apk.write(dex, dex.name)
        run([bt / 'zipalign', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk'])
        run([bt / 'apksigner', 'sign', '--ks', KEY, '--ks-key-alias', CONFIG['keyAlias'],
             '--ks-pass', 'env:REFRACT_KEY_PASSWORD', '--key-pass', 'env:REFRACT_KEY_PASSWORD',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
             '--out', work / 'refract-custom.apk', work / 'aligned.apk'], env=env)
        verification = output([bt / 'apksigner', 'verify', '--verbose', '--print-certs',
                               '--min-sdk-version', '28', work / 'refract-custom.apk'])
        if expected not in verification.lower():
            raise RuntimeError('APK signer fingerprint does not match')
        run([bt / 'zipalign', '-c', '4', work / 'refract-custom.apk'])
        badging = output([bt / 'aapt', 'dump', 'badging', work / 'refract-custom.apk'])
        if ("package: name='" + CONFIG['applicationId'] + "'") not in badging:
            raise RuntimeError('Unexpected APK applicationId')
        sha = hashlib.sha256((work / 'refract-custom.apk').read_bytes()).hexdigest()
        metadata = dict(applicationId=CONFIG['applicationId'], versionCode=code, versionName=name,
                        certificateSha256=expected, apkSha256=sha,
                        minSdk=CONFIG['minSdk'], targetSdk=CONFIG['targetSdk'],
                        sourceCommit=output(['git', '-C', ROOT, 'rev-parse', 'HEAD']),
                        dirty=bool(output(['git', '-C', ROOT, 'status', '--porcelain'])))
        out.mkdir(exist_ok=True)
        shutil.copy2(work / 'refract-custom.apk', out / 'refract-custom.apk')
        (out / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
        (out / 'SHA256SUMS').write_text(sha + '  refract-custom.apk\n')
    print('Built ' + str(out / 'refract-custom.apk') + ' (versionCode ' + str(code) + ')')


def provision_github():
    env = signing_env()
    if certificate(env) != PIN.read_text().strip():
        raise RuntimeError('Signing identity mismatch')
    values = {'REFRACT_KEYSTORE_BASE64': base64.b64encode(KEY.read_bytes()),
              'REFRACT_KEYSTORE_PASSWORD': PASSWORD.read_bytes().strip()}
    for name, value in values.items():
        run(['gh', 'secret', 'set', name, '--repo', CONFIG['repository']], input=value)
    print('Configured GitHub repository signing secrets using the permanent local key.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('init-signing')
    sub.add_parser('github-secrets')
    b = sub.add_parser('build')
    b.add_argument('--name')
    args = parser.parse_args()
    os.chdir(ROOT)
    if args.command == 'init-signing':
        init_signing()
    elif args.command == 'github-secrets':
        provision_github()
    else:
        # flock is process-scoped and releases even after an interrupted build.
        import fcntl
        with (ROOT / '.build.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            build(args.name)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as e:
        sys.exit(str(e))
