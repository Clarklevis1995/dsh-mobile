"""有真实偏移和完整摘要的受控文件协议；不是实际 DSH Host G。"""
import base64
import hashlib
import itertools
import zlib
import struct


def png(red, green, blue):
    def chunk(kind, data): return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    pixels = (b'\0' + bytes([red, green, blue]) * 64) * 48
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 64, 48, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


def pdf():
    bodies = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>', b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    text = b'BT /F1 18 Tf 35 330 Td (Harmony native PDF fixture) Tj ET'; bodies.append(b'<< /Length ' + str(len(text)).encode() + b' >>\nstream\n' + text + b'\nendstream')
    data = b'%PDF-1.4\n'; offsets = [0]
    for index, body in enumerate(bodies, 1): offsets.append(len(data)); data += str(index).encode() + b' 0 obj\n' + body + b'\nendobj\n'
    start = len(data); data += b'xref\n0 6\n0000000000 65535 f \n' + b''.join(('%010d 00000 n \n' % off).encode() for off in offsets[1:])
    return data + b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + str(start).encode() + b'\n%%EOF\n'


FILES = {
    '资料/报告 空格.md': ('text/markdown', '# 原生文件预览\n\n下载后的中文内容与 **粗体**。\n\n```kotlin\nval harmony = "worker native syntax"\n```\n\n| 项目 | 状态 |\n|---|---|\n| 文件 | 完整校验 |\n'.encode()),
    '资料/示例.pdf': ('application/pdf', pdf()),
    '图片.png': ('image/png', png(30, 100, 220)),
    '分块验收.txt': ('text/plain', ('后台逐块写入与完整校验\n' * 20000).encode()),
    '损坏下载.txt': ('text/plain', b'must reject wrong digest'),
}
TRANSFERS = {}; IDS = itertools.count(1)


def handle(client, request, identifier):
    kind = request.get('type'); rid = request.get('requestId')
    if kind == 'file-list':
        path = request.get('path', '.'); entries = []
        if path == '.': entries.append(dict(name='资料', path='资料', kind='directory', hidden=False))
        for name, (mime, data) in FILES.items():
            parent = name.rsplit('/', 1)[0] if '/' in name else '.'
            if parent == path: entries.append(dict(name=name.split('/')[-1], path=name, kind='file', hidden=False, bytes=len(data), mediaType=mime))
        client.send(dict(kind=kind, requestId=rid, sessionId=identifier, path=path, entries=entries))
    elif kind == 'file-resolve':
        name = request['path']; mime, data = FILES[name]
        client.send(dict(kind='file-resolved', requestId=rid, sessionId=identifier, path=name, name=name.split('/')[-1], size=len(data), mediaType=mime, modifiedAt=12345))
    elif kind == 'file-download-open':
        name = request['path']; mime, data = FILES[name]; transfer = 'fixture-download-' + str(next(IDS)); TRANSFERS[transfer] = dict(data=data, offset=0, name=name)
        client.send(dict(kind='file-download-opened', requestId=rid, sessionId=identifier, transferId=transfer, path=name, name=name.split('/')[-1], size=len(data), mediaType=mime, modifiedAt=12345, chunkBytes=32768))
    elif kind == 'file-download-read':
        transfer = request['transferId']; row = TRANSFERS[transfer]; assert request['offset'] == row['offset'], '下载偏移不连续'
        offset = row['offset']; data = row['data']; chunk = data[offset:offset + 32768]; row['offset'] += len(chunk); final = row['offset'] == len(data)
        reply = dict(kind='file-download-chunk', transferId=transfer, offset=offset, data=base64.b64encode(chunk).decode(), eof=final)
        if final: reply['sha256'] = '0' * 64 if row['name'] == '损坏下载.txt' else hashlib.sha256(data).hexdigest()
        client.send(reply)
    elif kind == 'file-download-cancel':
        TRANSFERS.pop(request['transferId'], None); client.send(dict(kind='file-download-cancelled', transferId=request['transferId']))
    else: return False
    return True
