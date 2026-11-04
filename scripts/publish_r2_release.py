#!/usr/bin/env python3
"""生成更新清单并发布到 R2；密钥仅从用户目录读取。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import zipfile

CONFIG = Path.home() / '.config/whaleness/r2-release.json'
PUBLIC_BASE = 'https://download.whaleness.cc'


def digest_stream(stream):
    result = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        result.update(chunk)
    return result.hexdigest()


def load_config():
    if CONFIG.stat().st_mode & 0o077:
        raise ValueError('凭据文件权限过宽，请执行 chmod 600 ~/.config/whaleness/r2-release.json')
    config = json.loads(CONFIG.read_text())
    if (config['bucket'] != 'whaleness-apks' or config['publicBaseUrl'] != PUBLIC_BASE or
            config['endpoint'] != 'https://878f37e94a0b2f19271aed151dff95b2.r2.cloudflarestorage.com'):
        raise ValueError('R2 发布目标与项目配置不一致')
    return config


def make_client(config):
    import boto3
    from botocore.config import Config
    return boto3.client('s3', endpoint_url=config['endpoint'], region_name='auto',
        aws_access_key_id=config['accessKeyId'], aws_secret_access_key=config['secretAccessKey'],
        config=Config(signature_version='s3v4', connect_timeout=10, read_timeout=60,
            retries={'max_attempts': 2}, request_checksum_calculation='when_required',
            response_checksum_validation='when_required'))


def remote_manifest(client, bucket):
    from botocore.exceptions import ClientError
    try:
        response = client.get_object(Bucket=bucket, Key='latest.json')
    except ClientError as error:
        if error.response['Error']['Code'] in ('NoSuchKey', '404'):
            return None, None
        raise
    with response['Body'] as body:
        data = json.loads(body.read(256 * 1024))
    return data, response['ETag']


def prepare(release_directory, aapt):
    # 顶层通用包兼容旧客户端；新客户端按设备支持顺序选择 ABI。
    apks = [p for p in (release_directory / 'artifacts/android').glob('*.apk')
            if re.fullmatch(r'DshMobile-android-v\d+\.\d+\.\d+\.apk', p.name)]
    if len(apks) != 1:
        raise ValueError('发布目录中必须恰好包含一个 APK')
    apk = apks[0]
    output = subprocess.check_output([aapt, 'dump', 'badging', str(apk)], text=True)
    fields = dict(re.findall(r"(\w+)='([^']*)'", output.splitlines()[0]))
    version = fields['versionName']
    build = int(fields['versionCode'])
    if fields['name'] != 'com.clarklevis.dsh.android' or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('APK 包名或版本无效')
    with apk.open('rb') as stream:
        sha = digest_stream(stream)
    checksum = Path(str(apk) + '.sha256')
    if not checksum.exists() or checksum.read_text().split()[0] != sha:
        raise ValueError('APK 与打包时的 SHA-256 不一致')
    notes = (release_directory / f'RELEASE_NOTES-v{version}.md').read_text()
    key = f'DshMobile-android-v{version}-build{build}.apk'
    manifest = dict(schemaVersion=1, version=version, versionCode=build,
        packageName=fields['name'], downloadUrl=f'{PUBLIC_BASE}/{key}',
        githubDownloadUrl=f'https://github.com/Clarklevis1995/dsh-mobile/releases/download/v{version}/{apk.name}',
        releasePageUrl='https://github.com/Clarklevis1995/dsh-mobile/releases',
        size=apk.stat().st_size, sha256=sha, releaseNotes=notes)
    variants = {}
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        variant = apk.parent / f'DshMobile-android-v{version}-{abi}.apk'
        if not variant.is_file():
            raise ValueError(f'缺少 ABI 安装包：{abi}')
        info = subprocess.check_output([aapt, 'dump', 'badging', str(variant)], text=True)
        variant_fields = dict(re.findall(r"(\w+)='([^']*)'", info.splitlines()[0]))
        if any(variant_fields.get(k) != fields[k] for k in ('name', 'versionName', 'versionCode')):
            raise ValueError(f'ABI 安装包版本或包名不一致：{abi}')
        with zipfile.ZipFile(variant) as archive:
            abis = {name.split('/')[1] for name in archive.namelist()
                    if name.startswith('lib/') and len(name.split('/')) > 2}
        if abis != {abi}:
            raise ValueError(f'ABI 安装包架构不一致：{abi}')
        with variant.open('rb') as stream:
            variant_sha = digest_stream(stream)
        checksum = Path(str(variant) + '.sha256')
        if not checksum.exists() or checksum.read_text().split()[0] != variant_sha:
            raise ValueError(f'ABI 安装包 SHA-256 不一致：{abi}')
        variant_key = f'DshMobile-android-v{version}-build{build}-{abi}.apk'
        variants[abi] = dict(downloadUrl=f'{PUBLIC_BASE}/{variant_key}',
            githubDownloadUrl=f'https://github.com/Clarklevis1995/dsh-mobile/releases/download/v{version}/{variant.name}',
            size=variant.stat().st_size, sha256=variant_sha)
    manifest['abiApks'] = variants
    (release_directory / 'latest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return apk, key, manifest


def validate_upgrade(current, manifest):
    if current is None:
        return
    if int(current['versionCode']) > manifest['versionCode']:
        raise ValueError('R2 已发布更高构建号，拒绝回退 latest.json')
    if int(current['versionCode']) == manifest['versionCode'] and current['sha256'] != manifest['sha256']:
        raise ValueError('同一构建号对应不同 APK，拒绝发布')


def publish(client, bucket, apk, key, manifest, verify_public):
    from botocore.exceptions import ClientError
    current, etag = remote_manifest(client, bucket)
    validate_upgrade(current, manifest)
    # 所有包上传并校验后才更新清单，避免客户端遇到缺失或损坏的包。
    packages = [(apk, key, manifest)]
    for abi, entry in manifest.get('abiApks', {}).items():
        variant = apk.parent / f"DshMobile-android-v{manifest['version']}-{abi}.apk"
        variant_key = f"DshMobile-android-v{manifest['version']}-build{manifest['versionCode']}-{abi}.apk"
        packages.append((variant, variant_key, entry))
    for path, object_key, entry in packages:
        try:
            with path.open('rb') as body:
                client.put_object(Bucket=bucket, Key=object_key, Body=body,
                    ContentType='application/vnd.android.package-archive',
                    CacheControl='public, max-age=31536000, immutable', IfNoneMatch='*')
        except ClientError as error:
            if error.response['Error']['Code'] not in ('PreconditionFailed', '412'):
                raise
        response = client.get_object(Bucket=bucket, Key=object_key)
        with response['Body'] as stream:
            if response['ContentLength'] != entry['size'] or digest_stream(stream) != entry['sha256']:
                raise ValueError('R2 APK 完整性校验失败，未更新 latest.json')
        verify_public(entry)
    # 最后更新清单；并发发布时按读取到的 ETag 条件更新，避免覆盖新版本。
    condition = {'IfMatch': etag} if etag else {'IfNoneMatch': '*'}
    client.put_object(Bucket=bucket, Key='latest.json',
        Body=(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode(),
        ContentType='application/json', CacheControl='no-store', **condition)
    saved, _ = remote_manifest(client, bucket)
    if saved != manifest:
        raise ValueError('latest.json 写入后核对失败，请检查是否发生并发发布')


def verify_public(manifest):
    request = urllib.request.Request(manifest['downloadUrl'], headers={'User-Agent': 'Whaleness-Release'})
    with urllib.request.urlopen(request, timeout=60) as response:
        if digest_stream(response) != manifest['sha256']:
            raise ValueError('公开下载地址校验失败，未更新 latest.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true', help='只读验证连接')
    mode.add_argument('--prepare', type=Path, metavar='RELEASE_DIR', help='仅生成 latest.json')
    mode.add_argument('--publish', type=Path, metavar='RELEASE_DIR', help='上传 APK 并更新 latest.json')
    parser.add_argument('--aapt', default=os.environ.get('AAPT', str(Path.home() / 'Library/Android/sdk/build-tools/36.0.0/aapt')))
    args = parser.parse_args()
    if args.prepare:
        _, _, manifest = prepare(args.prepare.resolve(), args.aapt)
        print(f"已生成 latest.json：{manifest['version']}，构建号 {manifest['versionCode']}")
        return
    config = load_config()
    client = make_client(config)
    if args.check:
        client.list_objects_v2(Bucket=config['bucket'], MaxKeys=1)
        current, _ = remote_manifest(client, config['bucket'])
        print('R2 只读连接验证通过；未上传或修改对象。')
        if current:
            print(f"当前发布版本 {current['version']}，构建号 {current['versionCode']}")
        return
    apk, key, manifest = prepare(args.publish.resolve(), args.aapt)
    publish(client, config['bucket'], apk, key, manifest, verify_public)
    print(f"发布成功：{manifest['downloadUrl']}")
    print(f"更新配置：{PUBLIC_BASE}/latest.json")


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # 不输出 SDK 请求详情或任何凭据。
        code = getattr(error, 'response', {}).get('Error', {}).get('Code')
        message = str(error) if isinstance(error, (ValueError, FileNotFoundError)) else f'{type(error).__name__} ({code or "网络或配置错误"})'
        print(f'发布操作失败：{message}', file=sys.stderr)
        sys.exit(1)
