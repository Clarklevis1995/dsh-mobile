"""正式 Native 与 ArkUI 扩展页面的隔离交互验收。"""
import re
import os
import time


def run(ui, m, folder, passed):
    def expect(control, text):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            node = ui.find(control)
            if node and node.get('text') == text: return
            time.sleep(.3)
        raise AssertionError('状态未确认：' + control + ' / ' + text)
    def home():
        ui.navigate('conversation-back', 'home-settings')
        ui.navigate('home-settings', 'settings-style')
    def settings_entry(control, ready):
        ui.click(control, scroll=True); ui.wait(ready)
    m.connect(ui)
    if os.environ.get('HARMONY_PRODUCT_START') not in ['plugins', 'schedules']:
        ui.input('message-draft', '团队导航保留的主会话草稿')
        ui.click('conversation-team'); ui.wait('team-member-harmony-child'); ui.screenshot('team-members')
        ui.click('team-task-task-ready', scroll=True); ui.text('保持共享任务与内部 todos 分离')
        ui.click('team-filter-completed', scroll=True); ui.text('已完成验收任务'); assert not ui.find('team-task-task-ready')
        ui.click('team-filter-all');
        for _ in range(6):
            if ui.find('team-member-harmony-child'): break
            ui.scroll(False)
        ui.click('team-member-harmony-child'); ui.wait('message-draft'); ui.text('成员会话的真实历史')
        ui.message('echo'); ui.text('流式回复验证完成', timeout=25); ui.screenshot('team-child-conversation')
        ui.navigate('conversation-back', 'team-member-harmony-once')
        ui.click('team-member-harmony-once'); ui.wait('child-read-only'); ui.text('一次性任务的只读结果')
        assert str(ui.find('message-send').get('enabled')).lower() == 'false'
        ui.screenshot('team-child-read-only')
        boxes = [list(map(int, re.findall(r'-?\d+', n['bounds']))) for n in ui.nodes() if len(re.findall(r'-?\d+', n.get('bounds', ''))) == 4]
        width = max(box[2] for box in boxes); height = max(box[3] for box in boxes)
        m.hdc('shell', 'uinput', '-T', '-m', '2', str(int(height*.65)), str(int(width*.7)), str(int(height*.65)), '160')
        ui.wait('team-member-harmony-child'); ui.navigate('page-back', 'message-draft')
        assert ui.find('message-draft').get('text') == '团队导航保留的主会话草稿'
        passed('P4-team-F', '共享成员/任务筛选详情、可继续成员的真实发送、一次性任务只读、系统手势返回团队及主会话草稿恢复通过')
    
    home()
    if os.environ.get('HARMONY_PRODUCT_START') != 'schedules':
        settings_entry('settings-plugins', 'plugin-toggle-test-plugin')
        expect('plugin-state-test-plugin', '已停用'); ui.click('plugin-toggle-test-plugin'); expect('plugin-state-test-plugin', '已开启')
        ui.screenshot('plugins-confirmed-toggle')
        ui.click('plugin-open-test-plugin'); ui.wait('plugin-entry-open-row'); ui.text('验收组件')
        ui.click('plugin-entry-toggle-row'); ui.text('重启'); ui.screenshot('plugins-restart-required')
        ui.click('plugin-entry-open-row'); ui.text('组件详情'); ui.text('fixture-module'); ui.navigate('page-back', 'plugin-entry-open-row')
        ui.navigate('page-back', 'plugin-toggle-dsh-plugin-mobile-gateway')
        ui.click('plugin-toggle-dsh-plugin-mobile-gateway'); ui.wait('confirmation-accept'); ui.screenshot('plugins-gateway-confirm')
        ui.click('取消', 'text'); ui.wait('plugin-toggle-dsh-plugin-mobile-gateway')
        ui.click('plugin-official-shell', scroll=True); ui.text('shell.exec'); ui.screenshot('plugins-official-details')
        ui.navigate('page-back', 'plugin-toggle-test-plugin'); ui.navigate('page-back', 'settings-plugins')
        passed('P4-plugins-F', '本地化插件目录、Host确认后开关、组件需重启保持真实状态、组件/官方详情及关闭网关取消通过')
    
    settings_entry('settings-schedules', 'schedule-edit-weekly')
    ui.click('schedule-details-weekly'); ui.text('America/New_York'); ui.text('周一、周三'); ui.screenshot('schedules-weekly-zone')
    ui.click('schedule-edit-weekly', scroll=True); ui.wait('schedule-title-input')
    ui.input('schedule-title-input', '修改后的中文任务'); ui.input('schedule-prompt-input', '保留完整 expected 的任务内容')
    ui.click('schedule-timing-clock', scroll=True); ui.input('schedule-clock-input', '10:15', scroll=True)
    ui.click('schedule-save', scroll=True); ui.wait('schedule-edit-weekly')
    for _ in range(12):
        if ui.find('修改后的中文任务','text'): break
        ui.scroll(False)
    ui.text('修改后的中文任务')
    
    if ui.find('schedule-details-weekly').get('text') == '详情': ui.click('schedule-details-weekly')
    ui.text('America/New_York'); ui.text('10:15:00'); ui.text('周一、周三'); ui.screenshot('schedules-confirmed-edit')
    ui.click('schedule-edit-weekly', scroll=True); ui.wait('schedule-title-input'); assert ui.find('schedule-title-input').get('text') == '修改后的中文任务'
    ui.navigate('page-back', 'schedule-edit-weekly')
    for _ in range(7):
        if ui.find('schedule-filter-ended'): break
        ui.scroll(False)
    ui.click('schedule-filter-ended'); ui.wait('schedule-edit-ended')
    assert str(ui.find('schedule-edit-ended').get('enabled')).lower() == 'false'
    ui.click('schedule-delete-ended'); ui.wait('confirmation-accept'); ui.click('取消', 'text'); ui.wait('schedule-delete-ended')
    ui.click('schedule-delete-ended'); ui.wait('confirmation-accept'); ui.click('confirmation-accept'); ui.text('暂无定时任务')
    assert not ui.find('schedule-delete-ended'); ui.screenshot('schedules-deleted-filtered')
    passed('P4-schedules-F', '真实协议目录、周规则/原时区展示、完整expected编辑确认重开、已结束任务禁编辑以及删除取消/确认通过')
