"""仅用于隔离附件回归；按真实协议确认偏移、摘要和图片元数据。"""
import base64
import hashlib
import itertools
import struct
import os
import time

IDS = itertools.count(1)
UPLOADS = {}
RECEIPTS = {}
IMAGES = {}
SENT = []
FAILED_NAMES = set()


def handle(client, request, session):
    kind = request.get('type'); rid = request.get('requestId')
    if kind == 'file-upload-open':
        identity = 'fixture-upload-' + str(next(IDS))
        UPLOADS[identity] = dict(session=session, name=request['name'], size=request['size'], sha256=request['sha256'], data=bytearray(), receipt=None)
        client.send(dict(kind='file-upload-opened', requestId=rid, sessionId=session, uploadId=identity, size=request['size'], chunkBytes=65536, maxBytes=536870912))
    elif kind == 'file-upload-chunk':
        identity = request['uploadId']; entry = UPLOADS[identity]; assert request['offset'] == len(entry['data'])
        if os.environ.get('HARMONY_UPLOAD_FAILURES') == '1' and entry['name'].startswith('harmony-文件验收') and entry['name'] not in FAILED_NAMES:
            FAILED_NAMES.add(entry['name']); print('upload-failed-once', identity, flush=True)
            client.send(dict(kind='error', requestType=kind, requestId=rid, sessionId=entry['session'], uploadId=identity, message='受控上传失败，请重试')); return True
        if entry['name'].startswith('harmony-cancel-') and request['offset'] == 0:
            print('upload-chunk-delayed', identity, flush=True); time.sleep(8)
        block = base64.b64decode(request['data'], validate=True); assert 0 < len(block) <= 65536
        entry['data'].extend(block); assert len(entry['data']) <= entry['size']
        client.send(dict(kind='file-upload-chunk-accepted', requestId=rid, sessionId=entry['session'], uploadId=identity, nextOffset=len(entry['data'])))
    elif kind == 'file-upload-finish':
        identity = request['uploadId']; entry = UPLOADS[identity]
        assert len(entry['data']) == entry['size'] and hashlib.sha256(entry['data']).hexdigest() == entry['sha256']
        receipt = entry['receipt'] or 'fixture-receipt-' + str(next(IDS)); entry['receipt'] = receipt
        file = dict(path='uploads/' + entry['name'], attachmentId='fixture-file-' + identity, name=entry['name'], bytes=entry['size'])
        RECEIPTS[receipt] = (entry['session'], file)
        client.send(dict(kind='file-upload-completed', requestId=rid, sessionId=entry['session'], uploadId=identity, receiptId=receipt, sha256=entry['sha256'], file=file))
    elif kind == 'file-upload-cancel':
        entry = UPLOADS.pop(request['uploadId'], None)
        if entry: print('upload-cancel-confirmed', entry['name'], flush=True)
        client.send(dict(kind='file-upload-cancelled', requestId=rid, sessionId=entry['session'] if entry else session, uploadId=request['uploadId']))
    elif kind == 'attachment':
        image, data = IMAGES[(session, request['attachmentId'])]
        client.send(dict(kind='attachment', sessionId=session, attachment=image, data=base64.b64encode(data).decode()))
    else: return False
    return True


def jpeg_size(data):
    assert data[:2] == b'\xff\xd8'; index = 2
    while index < len(data):
        assert data[index] == 255; marker = data[index + 1]; index += 2
        length = struct.unpack('>H', data[index:index + 2])[0]
        if marker in [192, 193, 194]:
            height, width = struct.unpack('>HH', data[index + 3:index + 7]); return width, height
        index += length
    raise AssertionError('未找到 JPEG 尺寸')


def content(request, session):
    files = []
    for item in request.get('files', []):
        owner, file = RECEIPTS[item['receiptId']]; assert owner == session; files.append(file)
    images = []
    for item in request.get('images', []):
        data = base64.b64decode(item['data'], validate=True); assert item['mediaType'] == 'image/jpeg' and len(data) <= 1048576
        width, height = jpeg_size(data); assert max(width, height) <= 2048
        image = dict(attachmentId='fixture-image-' + str(next(IDS)), mediaType=item['mediaType'], bytes=len(data), width=width, height=height, name=item.get('name'))
        IMAGES[(session, image['attachmentId'])] = (image, data); images.append(image)
    if images: print('photo-payload-validated', len(images), [(image['width'], image['height'], image['bytes']) for image in images], flush=True)
    SENT.append(dict(sessionId=session, files=files, images=images, text=request['text'], requestId=request.get('requestId')))
    return files, images
