"""真实 ArkUI／TaskPool／Native／网关协议，固定 PCM 硬件适配器。"""
import re
import time


def run(ui, m, folder, passed):
    m.connect(ui); ui.wait('message-draft'); ui.click('voice-mode'); ui.wait('voice-hold'); time.sleep(.8)
    row,x,y = ui.control('voice-hold'); m.hdc('shell','uitest','uiInput','longClick',str(x),str(y)); ui.text('正在转写',timeout=20)
    assert 'voice-pcm-wave-validated' in (folder/'gateway-18783.log').read_text()
    ui.click('voice-mode'); ui.wait('message-draft'); ui.input('message-draft','转写期间手动修改的草稿'); (folder/'release-transcript').touch()
    ui.wait('voice-pending-transcript',timeout=20); assert ui.find('message-draft').get('text') == '转写期间手动修改的草稿'
    ui.screenshot('voice-transcript-conflict'); ui.click('voice-insert'); ui.wait('message-draft')
    assert ui.find('message-draft').get('text') == '转写期间手动修改的草稿\n语音识别后的中文内容'
    ui.screenshot('voice-explicit-inserted-draft'); passed('P5-voice-protocol-F','真实原生长按、松手、TaskPool 编码与 Native WAV／协议验证；转写中编辑保留新稿，明确插入后合并；麦克风仅测试适配器，不替代 AudioCapturer F／D')
