"""通过真实 Native／ArkUI 验证 Host 元数据命令，不按名称猜测行为。"""
import time
import urllib.request


def run(ui, m, folder, passed):
    m.connect(ui); ui.wait('message-draft')
    ui.input('message-draft', '/', dismiss=False)
    ui.wait('slash-command-cmd:args'); ui.screenshot('slash-catalog')
    ui.click('slash-command-cmd:args'); ui.text('/prepare ')
    ui.input('message-draft', '/prepare 参数中文'); ui.click('message-send')
    ui.text('命令执行完成：/prepare 参数中文'); ui.screenshot('slash-executed')
    assert ui.find('message-draft').get('text', '') == ''
    passed('P4-slash-input-F', '服务端命令分组、输入参数、command-execute 与确认后草稿清理通过')

    ui.input('message-draft', '/', dismiss=False); ui.wait('slash-command-cmd:switch'); ui.click('slash-command-cmd:switch')
    ui.wait('slash-option-reason'); ui.screenshot('slash-options'); ui.click('slash-option-reason')
    time.sleep(.5); ui.input('message-draft', '保留确认后的新草稿')
    urllib.request.urlopen('http://127.0.0.1:18783/_fixture/replay-command-selected', timeout=5).close()
    time.sleep(.6); assert ui.find('message-draft').get('text') == '保留确认后的新草稿'
    ui.screenshot('slash-late-receipt-keeps-draft')
    ui.click('conversation-controls'); ui.wait('control-model-fixture-reason'); ui.text('✓ 推理模型')
    ui.navigate('page-back', 'message-draft')
    passed('P4-slash-options-F', '服务端选项选择与模型确认通过；重放旧确认不会清除新草稿')

    ui.input('message-draft', '/', dismiss=False); ui.wait('slash-command-cmd:echo'); ui.click('slash-command-cmd:echo')
    ui.text('流式回复验证完成', timeout=25)
    assert ui.find('message-draft').get('text', '') == ''
    ui.screenshot('slash-immediate-message')
    passed('P4-slash-immediate-F', '立即执行项按 Host submitRequest=message 发送指定文本并正常接收回复')
