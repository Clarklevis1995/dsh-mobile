"""系统默认扫码入口的打开/取消；图像识码与Native校验由ScanProbe独立验证。"""
import json,time

def run(ui,m,folder,passed):
    ui.wait('home-settings');ui.navigate('home-auth','host-add');ui.navigate('host-add','gateway-payload')
    ui.input('gateway-endpoint','ws://127.0.0.1:18783/ws/mobile')
    ui.input('gateway-payload','scan-cancel-preserves-edit')
    ui.click('gateway-scan');time.sleep(3)
    rows=ui.nodes(system=True)
    (folder/'scan-system-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    print('SCAN_SYSTEM',[(r.get('id'),r.get('text'),r.get('bundleName')) for r in rows if r.get('text')],flush=True)
    assert not any(r.get('id')=='gateway-scan' and str(r.get('enabled')).lower()!='false' for r in rows), '系统扫码未覆盖应用'
    m.hdc('shell','uitest','uiInput','keyEvent','Back');ui.wait('gateway-scan',timeout=20)
    assert ui.find('gateway-payload').get('text')=='scan-cancel-preserves-edit'
    assert ui.find('gateway-endpoint').get('text')=='ws://127.0.0.1:18783/ws/mobile'
    assert str(ui.find('gateway-scan').get('enabled')).lower()=='true'
    assert 'pair ' not in (folder/'gateway-18783.log').read_text()
    ui.screenshot('scan-cancel-preserved-form')
    passed('P5-system-scan-cancel-F','实际默认扫码界面可打开，系统返回取消后表单和地址保留，按钮恢复且未认证')
