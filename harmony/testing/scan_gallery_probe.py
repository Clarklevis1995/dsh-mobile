"""仅独立smoke包导入一张自建有效QR；系统扫码、共享校验和配对链路不替换。"""
import base64,json,time,subprocess,uuid
from pathlib import Path

def instrument(app):
    from photo_probe import instrument as photos
    photos(app)
    raw=app/'entry/src/main/resources/rawfile'
    value=dict(version=2,publicUrl='ws://127.0.0.1:18783/ws/mobile',pairingCode='harmony-smoke',expiresAt=int(time.time()*1000+3600000),gatewayId='a56a1098-8519-43a1-9dce-fb99863bf5bb')
    payload=base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
    rows=[dict(label='owned fixture pairing',value=payload,valid=True)]
    source=raw/'scan-fixtures.json';source.write_text(json.dumps(rows))
    subprocess.run(['swift',str(Path(__file__).with_name('generate_qr_fixtures.swift')),str(source),str(raw/'scan-images')],check=True)
    p=app/'entry/src/main/ets/pages/Index.ets';s=p.read_text().replace('n <= 3','n <= 1').replace("'orientation-fixtures/exif-' + n + '.jpg'","'scan-images/qr-0.png'").replace("'/harmony-owned-photo-' + n + '.jpg'","'/harmony-owned-qr-' + n + '.png'").replace("title: 'harmony-owned-photo-' + n, fileNameExtension: 'jpg'","title: 'harmony-owned-qr-' + n, fileNameExtension: 'png'");s=s.replace('harmony-owned-qr-', 'harmony-owned-qr-'+uuid.uuid4().hex[:8]+'-');p.write_text(s)
