import io
import json
from pathlib import Path
import sys
import tarfile
import unittest
from unittest.mock import patch
import subprocess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release
import tv


def archive(files):
    result = io.BytesIO()
    with tarfile.open(fileobj=result, mode='w') as out:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            out.addfile(info, io.BytesIO(data))
    return result.getvalue()


class MigrationTests(unittest.TestCase):
    def test_preserves_layout_but_drops_identity_bound_secrets(self):
        data = archive({'shared_prefs/home.xml': b'<map><string name="order">app.a,app.b</string></map>',
                        'shared_prefs/secrets.xml': b'ciphertext',
                        'shared_prefs/present_pairing.xml': b'bearer token',
                        'shared_prefs/presentation.xml': b'<map><boolean name="enabled" value="true"/></map>',
                        'shared_prefs/suggestions.xml': b'<map><string name="tmdb_token">secret</string></map>',
                        'files/continue.json': b'[]', 'cache/image': b'large cache'})
        result = tv.filtered_archive(data)
        with tarfile.open(fileobj=io.BytesIO(result)) as out:
            names = out.getnames()
            self.assertNotIn('shared_prefs/secrets.xml', names)
            self.assertNotIn('shared_prefs/present_pairing.xml', names)
            self.assertNotIn('cache/image', names)
            self.assertIn(b'app.a,app.b', out.extractfile('shared_prefs/home.xml').read())
            self.assertNotIn(b'secret', out.extractfile('shared_prefs/suggestions.xml').read())
            self.assertIn(b'value="false"', out.extractfile('shared_prefs/presentation.xml').read())
            self.assertEqual(b'[]', out.extractfile('files/continue.json').read())

    def test_rejects_path_traversal_and_absolute_paths(self):
        for name in ('../../outside', '/shared_prefs/home.xml', 'files/../../escape'):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                tv.filtered_archive(archive({name: b'bad'}))

    def test_rejects_symlinks(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as out:
            info = tarfile.TarInfo('files/link')
            info.type, info.linkname = tarfile.SYMTYPE, '/data/user/0/other'
            out.addfile(info)
        with self.assertRaises(RuntimeError):
            tv.filtered_archive(data.getvalue())

    def test_rejects_invalid_package_in_shell(self):
        with self.assertRaises(RuntimeError):
            tv.valid_package('com.example; rm -rf /')


class InstallTests(unittest.TestCase):
    def test_first_install_uses_replace_flag_only_for_custom_package(self):
        device = tv.TV.__new__(tv.TV)
        device.adb = ['adb', '-s', 'emulator-test']
        with patch.object(device, 'shell', return_value='') as shell, \
                patch('tv.sdk_paths', return_value=(Path('/sdk'), Path('/sdk/android.jar'))), \
                patch('tv.output', side_effect=[release.PIN.read_text().strip(),
                     "package: name='com.arinaggarwal.refract' versionCode='123'"]), patch('tv.run') as run:
            device.install(Path('test.apk'))
            self.assertEqual('pm path com.arinaggarwal.refract || true', shell.call_args.args[0])
            self.assertEqual(['adb', '-s', 'emulator-test', 'install', '-r', Path('test.apk')], run.call_args.args[0])

    def test_wrong_package_never_reaches_adb_install(self):
        device = tv.TV.__new__(tv.TV)
        device.adb = ['adb', '-s', 'emulator-test']
        with patch('tv.sdk_paths', return_value=(Path('/sdk'), None)), \
                patch('tv.output', side_effect=[release.PIN.read_text().strip(),
                     "package: name='com.hydrainnit.refract' versionCode='123'"]), patch('tv.run') as run:
            with self.assertRaises(RuntimeError):
                device.install(Path('test.apk'))
            run.assert_not_called()


class VersionTests(unittest.TestCase):
    @patch('release.time.time', return_value=1577836800 + 100)
    def test_remote_high_water_wins_over_local_clock(self, _):
        with patch('release.api', side_effect=[
            [{'ref': 'refs/tags/refract-build/150'}], {'default_branch': 'main'},
            {'object': {'sha': 'abc'}}, {'ref': 'created'}]) as api:
            self.assertEqual(151, release.reserve_version())
            self.assertEqual('refs/tags/refract-build/151', api.call_args.args[2]['ref'])

    @patch('release.time.time', return_value=1577836800 + 100)
    def test_concurrent_reservation_retries_with_new_high_water(self, _):
        with patch('release.api', side_effect=[
            [], {'default_branch': 'main'}, {'object': {'sha': 'abc'}}, subprocess.CalledProcessError(1, 'gh'),
            [{'ref': 'refs/tags/refract-build/100'}], {'default_branch': 'main'},
            {'object': {'sha': 'abc'}}, {'ref': 'created'}]):
            self.assertEqual(101, release.reserve_version())

    @patch('release.time.time', return_value=1577836800 + 100)
    def test_counter_cannot_overflow_android(self, _):
        with patch('release.api', return_value=[{'ref': 'refs/tags/refract-build/2100000000'}]):
            with self.assertRaises(RuntimeError):
                release.reserve_version()

    def test_missing_key_never_auto_generates(self):
        with patch('release.KEY', Path('/nonexistent/refract-key')):
            with self.assertRaises(RuntimeError):
                release.signing_env()

    def test_apk_build_rejects_wrong_signer_before_reserving_version(self):
        with patch('release.signing_env', return_value={}), patch('release.certificate', return_value='wrong'), \
                patch('release.reserve_version') as reserve:
            with self.assertRaises(RuntimeError):
                release.build()
            reserve.assert_not_called()


if __name__ == '__main__':
    unittest.main()
