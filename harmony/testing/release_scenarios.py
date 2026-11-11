# coding: utf-8
"""原生设置/版本说明及真实HTTP栈的更新矩阵；仅测试副本改元数据地址。"""
import json
import shutil
import time


def instrument(app):
    path = app / 'entry/src/main/ets/core/ReleaseInfo.ets'
    source = path.read_text(); marker = "'https://api.github.com/repos/Clarklevis1995/dsh-mobile/releases/latest'"
    assert source.count(marker) == 1
    path.write_text(source.replace(marker,"'http://127.0.0.1:18787/latest'"))


def run(ui,m,folder,passed):
    state = folder / 'release-mode'
    ui.navigate('home-settings','settings-about')
    ui.text('≥ 0.12.0')
    ui.click('settings-release-notes',scroll=True); ui.wait('release-notes-content'); ui.text('鸿蒙版'); ui.text('相机、麦克风')
    ui.screenshot('release-notes-native')
    # 真正系统手势返回到原设置来源，不用菜单键或应用级swipe替代。
    m.hdc('shell','uinput','-T','-m','2','1800','920','1800','160'); ui.wait('settings-release-notes')
    ui.click('settings-updates',scroll=True); ui.wait('release-check')
    outcomes = []
    def check(mode,text,opened=False):
        state.write_text(mode); ui.click('release-check'); ui.text(text)
        assert str(ui.wait('release-check').get('enabled')).lower() == 'true'
        assert bool(ui.find('release-open')) == opened, mode
        ui.nodes(); shutil.copy2(folder/'layout.json',folder/('release-'+mode+'-layout.json')); ui.screenshot('release-'+mode)
        outcomes.append(dict(mode=mode,observed=text,officialReleaseButton=opened))
    check('apk','未提供鸿蒙安装包')
    check('available','发现鸿蒙安装包：v0.2.0',True)
    check('current','当前鸿蒙版本已是最新')
    check('quota','检查过于频繁')
    check('empty','尚未找到公开发布')
    check('invalid','检查更新失败')
    state.write_text('delayed');ui.click('release-check');ui.wait('release-check-loading')
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(.5)
    m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('release-check')
    assert str(ui.wait('release-check')['enabled']).lower()=='true'
    assert '检查官方项目' in ui.wait('release-check-status')['text'],'实际后台返回后检查没有取消'
    ui.screenshot('release-background-cancelled')
    check('apk','未提供鸿蒙安装包')
    state.write_text('delayed'); ui.click('release-check'); ui.wait('release-check-loading')
    ui.navigate('page-back','settings-updates'); ui.click('settings-updates',scroll=True); ui.wait('release-check')
    check('apk','未提供鸿蒙安装包')
    time.sleep(1)
    assert 'v9.9.9' not in ui.wait('release-check-status')['text']
    ui.navigate('page-back','settings-updates')
    # 冷启后回到首页，设置仍有有效本版说明与版本入口。
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
    ui.wait('home-settings');ui.navigate('home-settings','settings-about');ui.click('settings-about',scroll=True);ui.text('应用版本');ui.navigate('page-back','settings-updates')
    (folder/'release-results.json').write_text(json.dumps(dict(scope='原生生产页面/HTTP栈；仅测试副本的公开元数据URL指向本轮HTTP夹具；不提供真实HAP下载，不是公网G',outcomes=outcomes,systemGestureBack=True,lateResponseIgnored=True,actualBackgroundCancelled=True,coldStartAbout=True),ensure_ascii=False,indent=2))
    passed('P4-release-settings-F','本版说明/系统手势返回；APK隔离/HAP元数据/当前版/限流/未发布/畸形响应/重试/旧请求取消与冷启关于通过，未下载或安装更新')
