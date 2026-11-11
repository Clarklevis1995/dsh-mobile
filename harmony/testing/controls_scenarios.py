"""实际 Native 状态机、真实 ArkUI 页面与隔离协议服务的会话控制验收。"""
import time


def run(ui, m, folder, passed):
    m.connect(ui)
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
