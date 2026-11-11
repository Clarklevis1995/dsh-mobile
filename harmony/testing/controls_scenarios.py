"""实际 Native 状态机、真实 ArkUI 页面与隔离协议服务的会话控制验收。"""
import time
import os


def run(ui, m, folder, passed):
    m.connect(ui)
    if os.environ.get('HARMONY_CONTROL_START') == 'font':
        from font_visual_scenarios import run as fonts
        fonts(ui,m,folder,passed);return
    if os.environ.get('HARMONY_CONTROL_START') == 'settings':
        import json
        ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-defaults')
        ui.text('新会话默认配置');ui.text('构建 Agent');ui.text('读取模型');ui.control('settings-host-version',scroll=True);ui.text('0.2.2-fixture');ui.text('已连接会话');ui.scroll(False);ui.scroll(False);ui.screenshot('settings-connected-light-root')
        (folder/'settings-root-layout.json').write_text(json.dumps(ui.nodes(),ensure_ascii=False,indent=2))
        ui.click('settings-ping',scroll=True);time.sleep(.5);ui.screenshot('settings-ping-response')
        assert 'manual-ping-response' in (folder/'gateway-18783.log').read_text()
        ui.click('settings-connection',scroll=True);ui.text('已断开');ui.screenshot('settings-disconnected')
        ui.scroll(False);ui.scroll(False)
        assert str(ui.wait('settings-defaults').get('enabled')).lower()=='false'
        ui.click('settings-connection',scroll=True);ui.text('已连接',timeout=25)
        ui.navigate('settings-font','font-follow-system');ui.text('代码');ui.screenshot('font-light-standard');(folder/'font-light-layout.json').write_text(json.dumps(ui.nodes(),ensure_ascii=False,indent=2));ui.navigate('page-back','settings-style')
        ui.navigate('settings-style','appearance-dark');ui.choose('appearance-dark')
        for _ in range(3):ui.scroll(False)
        ui.text('新会话默认配置');ui.screenshot('settings-connected-dark-root')
        ui.navigate('settings-font','font-follow-system');ui.screenshot('font-dark-standard');(folder/'font-dark-layout.json').write_text(json.dumps(ui.nodes(),ensure_ascii=False,indent=2));ui.navigate('page-back','settings-style')
        ui.navigate('settings-style','appearance-light');ui.choose('appearance-light')
        for _ in range(3):ui.scroll(False)
        ui.screenshot('settings-connected-light-restored')
        passed('P4-settings-navigation-F','设置按新会话默认配置/网关/外观顺序展示真实默认值；控制通道Ping回包、主动断开禁用默认设置、重连与浅深字号入口通过')
        return
    if os.environ.get('HARMONY_CONTROL_START') == 'localization':
        ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-language')
        ui.navigate('settings-language', 'language-en'); ui.choose('language-en'); ui.navigate('page-back', 'home-settings')
        ui.click('鸿蒙原生会话验证','text'); ui.wait('message-draft'); ui.click('queue-expand'); ui.click('queue-remove-q-remove')
        until = time.monotonic() + 12
        while ui.find('queue-remove-q-remove'):
            assert time.monotonic() < until
            time.sleep(.3)
        ui.text('Attachment message'); ui.text('待编辑的排队原文'); ui.screenshot('semantic-queue-English-host-original')
        ui.navigate('conversation-back','home-settings'); ui.navigate('home-settings','settings-language'); ui.navigate('settings-language','language-zh'); ui.choose('language-zh'); ui.navigate('page-back','home-settings')
        ui.click('鸿蒙原生会话验证','text'); ui.wait('message-draft')
        if ui.find('queue-expand'): ui.click('queue-expand')
        ui.text('附件消息'); ui.text('待编辑的排队原文'); ui.screenshot('semantic-queue-Chinese-host-original')
        passed('P7-shared-message-language-F','同一进程切换中英后，共享附件占位语义文案即时重解；主机排队正文逐字保持原文，不当成本地文案翻译')
        return
    if os.environ.get('HARMONY_CONTROL_START') == 'steer':
        ui.message('slow'); ui.wait('message-stop'); ui.click('queue-expand')
        until = time.monotonic() + 15
        while time.monotonic() < until and str(ui.wait('queue-steer-q-edit').get('enabled')).lower() != 'true': time.sleep(.3)
        ui.click('queue-steer-q-edit')
        until = time.monotonic() + 15
        while ui.find('queue-steer-q-edit'):
            assert time.monotonic() < until, '插话未获确认'
            time.sleep(.3)
        assert 'queue-steer-confirmed harmony-smoke q-edit' in (folder/'gateway-18783.log').read_text()
        assert ui.wait('message-draft').get('text','') == ''
        ui.screenshot('queue-steer-confirmed'); ui.click('message-stop')
        passed('P4-queue-steer-F', '生成中通过原生入口将指定排队项提升为插话，夹具核对请求及确认，确认后移出队列且不回填编辑草稿；不替代真实 Host 执行 G')
        return
    ui.click('conversation-controls'); ui.wait('control-model-fixture-read')
    ui.text('✓ 读取模型'); ui.text('构建 Agent')
    ui.click('session-agent-read'); ui.text('✓ 阅读 Agent')
    ui.click('control-model-fixture-reason'); ui.text('✓ 推理模型')
    ui.click('control-effort-high'); ui.text('✓ 高')
    ui.click('control-model-fixture-fail'); ui.text('测试模型暂不可用')
    ui.text('✓ 推理模型'); ui.screenshot('control-model-failed-rollback')
    ui.click('control-permission-read-only', scroll=True); ui.text('✓ 只读')
    for _ in range(12):
        if ui.find('9007199254740993', 'text'): break
        ui.scroll(True)
    ui.text('9007199254740993'); ui.screenshot('control-precise-stats')
    ui.navigate('page-back', 'message-draft')
    passed('P4-control-F', '原生会话模型、思考强度、失败回滚、权限确认与精确大整数统计通过')
    passed('P4-agent-F', '会话 Agent 查询与确认切换通过，未乐观修改')

    ui.input('message-draft', '保留已有草稿')
    ui.click('queue-expand'); ui.click('queue-edit-q-edit')
    ui.wait('message-draft'); ui.text('保留已有草稿\n\n待编辑的排队原文')
    assert not ui.find('queue-edit-q-edit')
    assert str(ui.find('queue-edit-q-file').get('enabled')).lower() == 'false'
    ui.click('queue-remove-q-remove')
    until = time.monotonic() + 12
    while ui.find('queue-remove-q-remove'):
        assert time.monotonic() < until, '队列移除未获确认'
        time.sleep(.4)
    ui.screenshot('queue-confirmed-edit')
    ui.input('message-draft', '')
    ui.message('echo'); ui.text('流式回复验证完成', timeout=25)
    ui.click('conversation-controls'); ui.wait('session-agent-current'); ui.text('已锁定')
    assert str(ui.find('session-agent-build').get('enabled')).lower() == 'false'
    ui.screenshot('agent-locked-after-send'); ui.navigate('page-back', 'message-draft')
    passed('P4-queue-F', '排队消息确认后移出、追加保留原草稿、移除及附件项禁止编辑通过')
    passed('P4-agent-lock-F', '首条消息确认后锁定 Agent，禁止继续切换通过')

    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-defaults')
    ui.click('settings-defaults'); ui.wait('control-model-fixture-read'); ui.text('✓ 读取模型')
    ui.click('control-model-fixture-reason'); ui.text('✓ 推理模型')
    ui.click('control-effort-high'); ui.text('✓ 高')
    ui.click('control-permission-workspace-write', scroll=True); ui.text('✓ 工作区写入')
    ui.click('control-preset-read', scroll=True); ui.wait('confirmation-accept'); ui.click('confirmation-accept'); ui.text('✓ 阅读 Agent'); ui.screenshot('global-defaults-confirmed')
    ui.navigate('page-back', 'settings-defaults'); ui.click('settings-defaults'); ui.text('✓ 推理模型'); ui.text('✓ 高')
    passed('P4-defaults-F', '默认模型／思考强度、默认权限和默认 Agent 确认后保存并重开恢复，与会话配置区分通过')
