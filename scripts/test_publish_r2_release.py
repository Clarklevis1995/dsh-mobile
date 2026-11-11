import io
import json
from pathlib import Path
import tempfile
import unittest
from botocore.exceptions import ClientError
from publish_r2_release import publish


class FakeR2:
    def __init__(self, fail_apk=False, current=None):
        self.objects = {} if current is None else {'latest.json': json.dumps(current).encode()}
        self.writes = []
        self.fail_apk = fail_apk

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise ClientError({'Error': {'Code': 'NoSuchKey'}}, 'GetObject')
        data = self.objects[Key]
        return {'Body': io.BytesIO(data), 'ContentLength': len(data), 'ETag': 'etag'}

    def put_object(self, Bucket, Key, Body, **kwargs):
        if Key.endswith('.apk') and self.fail_apk:
            raise RuntimeError('upload failed')
        if kwargs.get('IfNoneMatch') == '*' and Key in self.objects:
            raise ClientError({'Error': {'Code': 'PreconditionFailed'}}, 'PutObject')
        self.objects[Key] = Body.read() if hasattr(Body, 'read') else Body
        self.writes.append(Key)


class PublishTest(unittest.TestCase):
    def setUp(self):
        import hashlib
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'app.apk'
        self.apk.write_bytes(b'APK fixture')
        self.manifest = {'version': '1.7.4', 'versionCode': 22,
                         'size': self.apk.stat().st_size,
                         'sha256': hashlib.sha256(self.apk.read_bytes()).hexdigest()}

    def test_manifest_is_last_and_retry_does_not_overwrite_apk(self):
        client = FakeR2()
        for _ in range(2):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, lambda _: None)
        self.assertEqual(client.writes, ['build22.apk', 'latest.json', 'latest.json'])

    def test_failed_apk_never_updates_manifest(self):
        client = FakeR2(fail_apk=True)
        with self.assertRaises(RuntimeError):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, lambda _: None)
        self.assertNotIn('latest.json', client.objects)

    def test_public_download_failure_keeps_existing_manifest(self):
        old = dict(self.manifest, versionCode=21)
        client = FakeR2(current=old)
        def fail(_):
            raise ValueError('public download failed')
        with self.assertRaises(ValueError):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, fail)
        self.assertEqual(json.loads(client.objects['latest.json']), old)

    def test_newer_remote_build_blocks_downgrade(self):
        client = FakeR2(current=dict(self.manifest, versionCode=23))
        with self.assertRaises(ValueError):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, lambda _: None)
        self.assertEqual(client.writes, [])

    def test_existing_corrupt_apk_never_updates_manifest(self):
        client = FakeR2()
        client.objects['build22.apk'] = b'corrupt'
        with self.assertRaises(ValueError):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, lambda _: None)
        self.assertNotIn('latest.json', client.objects)

    def test_variant_failure_does_not_publish_manifest(self):
        variant = self.apk.parent / 'DshMobile-android-v1.7.4-arm64-v8a.apk'
        variant.write_bytes(b'variant')
        self.manifest['abiApks'] = {'arm64-v8a': {'size': 7, 'sha256': 'incorrect'}}
        client = FakeR2()
        with self.assertRaises(ValueError):
            publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, lambda _: None)
        self.assertNotIn('latest.json', client.objects)

    def test_variant_is_verified_before_manifest(self):
        import hashlib
        variant = self.apk.parent / 'DshMobile-android-v1.7.4-arm64-v8a.apk'
        variant.write_bytes(b'variant')
        self.manifest['abiApks'] = {'arm64-v8a': {
            'size': 7, 'sha256': hashlib.sha256(b'variant').hexdigest()}}
        client = FakeR2()
        verified = []
        publish(client, 'bucket', self.apk, 'build22.apk', self.manifest, verified.append)
        self.assertEqual(len(verified), 2)
        self.assertEqual(client.writes[-1], 'latest.json')


if __name__ == '__main__':
    unittest.main()
