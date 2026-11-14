#!/usr/bin/env python3
"""Explicit, serial-scoped TV operations. No uninstall, data clearing, or Amazon component changes."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET

from release import CONFIG, PIN, ROOT, output, run, sdk_paths

ORIGINAL = 'com.hydrainnit.refract'
CUSTOM = CONFIG['applicationId']


def valid_package(package):
    if not re.fullmatch(r'[A-Za-z]\w*(?:\.[A-Za-z]\w*)+', package):
        raise RuntimeError('Invalid Android package')
    return package


def filtered_archive(data):
    """Repack only regular app data, never links, traversal, caches, or Keystore ciphertext."""
    result = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(data)) as source, tarfile.open(fileobj=result, mode='w') as dest:
        for member in source:
            path = PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or not path.parts:
                raise RuntimeError('Unsafe backup path: ' + member.name)
            if path.parts[0] not in ('shared_prefs', 'files', 'databases'):
                continue
            if not member.isfile():
                if member.isdir():
                    continue
                raise RuntimeError('Backup contains a link or special file')
            if path.parts[0] == 'shared_prefs' and path.name.startswith(('secrets.', 'present_pairing.')):
                continue
            content = source.extractfile(member).read()
            if path.parts[0] == 'shared_prefs' and path.suffix == '.xml':
                prefs = ET.fromstring(content)
                # Re-enter keys under the new UID and keep competing presentation servers off.
                for item in list(prefs):
                    if item.get('name') in ('tmdb_token', 'api_key'):
                        prefs.remove(item)
                    if path.name == 'presentation.xml' and item.get('name') in ('enabled', 'start_on_boot'):
                        item.set('value', 'false')
                content = ET.tostring(prefs, encoding='utf-8', xml_declaration=True)
            clean = tarfile.TarInfo(str(path))
            clean.size, clean.mode = len(content), 0o600
            dest.addfile(clean, io.BytesIO(content))
    return result.getvalue()


class TV:
    def __init__(self, serial):
        self.adb = ['adb', '-s', serial]
        if self.shell('am get-current-user').strip() != '0':
            raise RuntimeError('Only Android user 0 is supported; refusing to touch another user')
        self.root_prefix = None

    def shell(self, command):
        return output(self.adb + ['shell', command])

    def root_command(self, command):
        if self.root_prefix is None:
            self.root_prefix = 'sh -c ' if self.shell('id -u') == '0' else 'su -c '
            if self.shell(self.root_prefix + shlex.quote('id -u')) != '0':
                raise RuntimeError('Root is required for migration and preferred Home changes')
        return self.root_prefix + shlex.quote('set -eu; ' + command)

    def root(self, command):
        return self.shell(self.root_command(command))

    def paths(self, package):
        paths = self.shell('pm path ' + valid_package(package)).splitlines()
        if not paths or any(not p.startswith('package:/') for p in paths):
            raise RuntimeError('Package is not installed: ' + package)
        return [p[len('package:'):] for p in paths]

    def home(self):
        text = self.shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
        return next((line.strip() for line in reversed(text.splitlines())
                     if re.fullmatch(r'[\w.]+/[\w.$]+', line.strip())), None)

    def backup(self, source, directory):
        source = valid_package(source)
        if source == CUSTOM:
            raise RuntimeError('Use backup for the original installation, not the custom package')
        apks = self.paths(source)
        directory.mkdir(parents=True, mode=0o700, exist_ok=False)
        metadata = dict(source=source, serial=self.adb[2], previousHome=self.home(), apkFiles=[])
        self.root('id -u')  # Verify root before stopping anything.
        running = bool(self.shell('pidof ' + source + ' || true'))
        self.shell('am force-stop ' + source)
        try:
            data_dir = '/data/user/0/' + source
            cmd = ('cd ' + shlex.quote(data_dir) + '; test "$PWD" = ' + shlex.quote(data_dir) + ' || exit 1; '
                   'set --; for d in shared_prefs files databases; do [ ! -d "$d" ] || set -- "$@" "$d"; done; '
                   '[ "$#" -gt 0 ] || exit 1; tar -cf - "$@"')
            with (directory / 'settings.tar').open('xb') as f:
                os.chmod(f.name, 0o600)
                run(self.adb + ['exec-out', self.root_command(cmd)], stdout=f)
            # Parse now, so an error message can never be mistaken for a usable backup.
            with tarfile.open(directory / 'settings.tar') as archive:
                archive.getmembers()
            for i, apk in enumerate(apks):
                name = 'original-' + str(i) + '.apk'
                run(self.adb + ['pull', apk, directory / name], stdout=subprocess.DEVNULL)
                (directory / name).chmod(0o600)
                metadata['apkFiles'].append(name)
            metadata['sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in directory.iterdir() if p.is_file()}
            (directory / 'backup.json').write_text(json.dumps(metadata, indent=2) + '\n')
            (directory / 'backup.json').chmod(0o600)
        finally:
            if running:
                self.shell('am start -n ' + source + '/.MainActivity')
        print('Backup saved privately in ' + str(directory))

    def migrate(self, directory):
        meta = json.loads((directory / 'backup.json').read_text())
        if meta['serial'] != self.adb[2] or meta['source'] == CUSTOM:
            raise RuntimeError('Backup must belong to this TV and the original launcher')
        self.paths(meta['source'])  # Original must still be installed as the fallback.
        self.paths(CUSTOM)
        for name, expected in meta['sha256'].items():
            if Path(name).name != name or hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
                raise RuntimeError('Backup checksum mismatch')
        data = filtered_archive((directory / 'settings.tar').read_bytes())
        target = '/data/user/0/' + CUSTOM
        self.root('test ! -e ' + target + '/.refract-migrated && '
                  'test ! -d ' + target + '/shared_prefs && test ! -d ' + target + '/databases && '
                  'test ! -d ' + target + '/files')
        self.shell('am force-stop ' + CUSTOM)
        # Confirm no existing settings and no staged migration before writing anything.
        self.root('test ! -e ' + target + '/.migration-stage')
        uid = self.root('stat -c %u ' + target).strip()
        if not uid.isdigit() or int(uid) < 10000:
            raise RuntimeError('Cannot determine custom app UID safely')
        with tempfile.TemporaryDirectory(prefix='refract-migrate-') as temp:
            archive = Path(temp) / 'migration.tar'
            archive.write_bytes(data)
            archive.chmod(0o600)
            remote = '/data/local/tmp/refract-migration-' + os.urandom(8).hex() + '.tar'
            try:
                run(self.adb + ['push', archive, remote], stdout=subprocess.DEVNULL)
                self.root('mkdir ' + target + '/.migration-stage && tar -xf ' + remote + ' -C ' + target +
                          '/.migration-stage && chown -R ' + uid + ':' + uid + ' ' + target + '/.migration-stage && '
                          'for d in shared_prefs files databases; do '
                          '[ ! -d ' + target + '/.migration-stage/$d ] || mv ' + target + '/.migration-stage/$d ' + target + '/; done; '
                          'test -d ' + target + '/shared_prefs && '
                          'restorecon -RF ' + target + ' && touch ' + target + '/.refract-migrated && '
                          'chown ' + uid + ':' + uid + ' ' + target + '/.refract-migrated && '
                          'rmdir ' + target + '/.migration-stage')
            finally:
                self.shell('rm -f ' + remote)
        print('Settings migrated. Original untouched; default Home unchanged. Re-enter API keys and grant permissions again.')

    def install(self, apk):
        bt, _ = sdk_paths()
        expected = PIN.read_text().strip()
        verified = output([bt / 'apksigner', 'verify', '--print-certs', '--min-sdk-version', '28', apk])
        if expected not in verified.lower():
            raise RuntimeError('APK is not signed with your permanent key')
        badging = output([bt / 'aapt', 'dump', 'badging', apk])
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
        if not match or match[1] != CUSTOM:
            raise RuntimeError('Refusing to install a package other than ' + CUSTOM)
        installed = self.shell('pm path ' + CUSTOM + ' || true')
        if installed.startswith('package:'):
            with tempfile.TemporaryDirectory() as temp:
                old = Path(temp) / 'installed.apk'
                run(self.adb + ['pull', installed.splitlines()[0][8:], old], stdout=subprocess.DEVNULL)
                cert = output([bt / 'apksigner', 'verify', '--print-certs', old])
                old_badging = output([bt / 'aapt', 'dump', 'badging', old])
                old_code = int(re.search(r"versionCode='(\d+)'", old_badging)[1])
                if expected not in cert.lower() or int(match[2]) <= old_code:
                    raise RuntimeError('Update needs the same key and a greater versionCode. Rebuild; never uninstall.')
        run(self.adb + ['install', '-r', apk])
        print('Installed custom APK. Default Home unchanged; original package untouched.')

    def set_home(self, original=False):
        package = ORIGINAL if original else CUSTOM
        self.paths(package)
        previous = self.home()
        self.root('pm set-home-activity ' + package + '/.MainActivity')
        if self.home() not in (package + '/.MainActivity', package + '/' + package + '.MainActivity'):
            if previous:
                self.root('pm set-home-activity ' + shlex.quote(previous))
            raise RuntimeError('Home verification failed; restored previous preference when available')
        self.shell('am start -n ' + package + '/.MainActivity')
        print('Preferred Home: ' + package + '. Amazon component states were not changed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit adb device serial or TV_IP:5555')
    sub = parser.add_subparsers(dest='command', required=True)
    b = sub.add_parser('backup')
    b.add_argument('directory', type=Path)
    b.add_argument('--source', default=ORIGINAL)
    m = sub.add_parser('migrate')
    m.add_argument('directory', type=Path)
    i = sub.add_parser('install')
    i.add_argument('apk', type=Path)
    sub.add_parser('set-home')
    sub.add_parser('rollback-home')
    args = parser.parse_args()
    tv = TV(args.serial)
    if args.command == 'backup':
        tv.backup(args.source, args.directory)
    elif args.command == 'migrate':
        tv.migrate(args.directory)
    elif args.command == 'install':
        tv.install(args.apk)
    else:
        tv.set_home(args.command == 'rollback-home')


if __name__ == '__main__':
    os.umask(0o077)
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, tarfile.TarError) as e:
        sys.exit(str(e))
