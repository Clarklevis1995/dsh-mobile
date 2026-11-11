"""目标与全局 Agent 默认的原生 UI F，夹具校验目标 CAS。"""
import time
import os


def run(ui, m, folder, passed):
    m.connect(ui); ui.wait('message-draft')
    if os.environ.get('HARMONY_GOAL_START') != 'agents':
        ui.wait('session-goal-objective'); ui.input('message-draft', '目标操作期间保留的原稿')
        ui.click('session-tasks-toggle'); ui.text('当前执行任务样本'); ui.click('session-tasks-toggle')
        ui.click('session-goal-toggle'); ui.text('目标已暂停'); ui.click('session-goal-toggle'); ui.text('目标进行中')
        ui.navigate('session-goal-edit', 'goal-editor-objective'); ui.input('goal-editor-objective', '由鸿蒙原生界面更新的目标')
        ui.click('goal-editor-save'); ui.wait('session-goal-objective'); ui.text('由鸿蒙原生界面更新的目标'); ui.screenshot('native-goal-confirmed-edit')
        assert ui.find('message-draft').get('text') == '目标操作期间保留的原稿'
        ui.click('session-goal-clear'); ui.wait('confirmation-accept'); ui.click('取消', 'text'); ui.wait('session-goal-objective')
        ui.click('session-goal-clear'); ui.wait('confirmation-accept'); ui.click('confirmation-accept')
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline and ui.find('session-goal-objective'): time.sleep(.4)
        assert not ui.find('session-goal-objective'); assert ui.find('message-draft').get('text') == '目标操作期间保留的原稿'
        passed('P4-goal-tasks-F', '原生任务进度、目标暂停／继续、按版本编辑、确认后删除与取消；操作不覆盖聊天草稿')
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-style'); ui.click('settings-agent-presets', scroll=True); ui.wait('agent-preset-card-build')
    ui.click('agent-preset-card-read'); ui.wait('confirmation-accept'); ui.click('取消', 'text'); ui.wait('agent-preset-card-read')
    ui.click('agent-preset-card-read'); ui.wait('confirmation-accept'); ui.click('confirmation-accept'); time.sleep(.8)
    ui.wait('agent-preset-current-read')
    ui.click('agent-preset-card-read'); assert not ui.find('confirmation-accept')
    ui.screenshot('agent-global-default-confirmed'); ui.navigate('page-back', 'settings-style'); ui.click('settings-agent-presets', scroll=True); ui.wait('agent-preset-card-read')
    ui.wait('agent-preset-current-read')
    passed('P4-agent-management-F', '独立预设卡片、全局默认变更取消／确认，确认后禁重复选择，重开读取真实默认')
