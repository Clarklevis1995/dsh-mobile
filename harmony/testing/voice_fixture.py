"""语音协议 F：只处理确定性录音，不连接任何外部语音服务。"""
import base64
import os
from pathlib import Path
import struct
import threading
import time

CANCELLED = set()


def handle(client, request, session):
    kind = request.get('type'); rid = request.get('requestId')
    if kind == 'speech-catalog':
        client.send(dict(kind=kind, requestId=rid, available=True, selection=dict(providerId='fixture-voice'), providers=[dict(id='fixture-voice', preparation=dict(phase='ready'))]))
    elif kind == 'speech-subscribe': client.send(dict(kind='speech-subscribed', requestId=rid))
    elif kind == 'speech-unsubscribe': client.send(dict(kind='speech-unsubscribed', requestId=rid))
    elif kind == 'speech-transcribe':
        wav = base64.b64decode(request['audioBase64'], validate=True)
        assert wav[:4] == b'RIFF' and wav[8:12] == b'WAVE' and struct.unpack_from('<I', wav, 24)[0] == 16000
        assert struct.unpack_from('<H', wav, 22)[0] == 1 and struct.unpack_from('<H', wav, 34)[0] == 16 and len(wav) > 10000
        print('voice-pcm-wave-validated', len(wav), flush=True)
        def finish():
            release = Path(os.environ['HARMONY_FIXTURE_VOICE_RELEASE']); until = time.monotonic() + 90
            while not release.exists() and rid not in CANCELLED and time.monotonic() < until: time.sleep(.1)
            if rid not in CANCELLED and release.exists(): client.send(dict(kind='speech-transcript', requestId=rid, text='语音识别后的中文内容'))
        threading.Thread(target=finish, daemon=True).start()
    elif kind == 'speech-cancel':
        CANCELLED.add(request.get('targetRequestId')); client.send(dict(kind='speech-cancelled', requestId=rid))
    else: return False
    return True
