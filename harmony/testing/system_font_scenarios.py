"""系统设置真实最大字号下的生产页面；不注入字号或替换平台配置。"""
import json,re,time

def run(ui,m,folder,passed):
    def capture(name):
        (folder/(name+'-layout.json')).write_text(json.dumps(ui.nodes(),ensure_ascii=False,indent=2));ui.screenshot(name)
    ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-font');ui.navigate('settings-font','font-follow-system')
    if str(ui.find('font-follow-system').get('checked')).lower()!='true':ui.click('font-follow-system')
    ui.text('跟随系统');capture('system-font-preview')
    row=ui.find('font-preview-markdown');bounds=list(map(int,re.findall(r'-?\d+',row['origBounds'])));assert bounds[3]-bounds[1]>240,'系统最大字号未实际应用到预览'
    ui.navigate('page-back','settings-style');ui.navigate('page-back','home-settings');ui.click('session-harmony-smoke',scroll=True);ui.wait('message-draft')
    draft='系统最大字号下完整保留中文输入。Long English words and source_code_identifiers_should_remain_readable. 第二行继续编辑。'
    ui.input('message-draft',draft);assert ui.find('message-draft').get('text')==draft;capture('system-font-chat-draft')
    ui.message('approval');ui.text('确认本机测试命令');rows=ui.nodes();key=next(r['id'] for r in rows if r.get('id','').startswith('approval-details-'));ui.click(key);ui.text('echo harmony-approval');capture('system-font-approval-parameters')
    ui.control(key.replace('approval-details-','approval-reject-'),scroll=True);capture('system-font-approval')
    allow=next(r for r in ui.nodes() if r.get('id','').startswith('approval-allow-'));reject=next(r for r in ui.nodes() if r.get('id','').startswith('approval-reject-'))
    for row in [allow,reject]:
        b=list(map(int,re.findall(r'-?\d+',row['bounds'])));o=list(map(int,re.findall(r'-?\d+',row['origBounds'])));assert b==o and b[2]>b[0] and b[3]-b[1]>=140, '审批按钮被裁切或触摸高度不足'
    ui.click(reject['id']);ui.text('已拒绝工具调用');capture('system-font-approval-resolved')
    passed('P7-system-font-max-F','系统设置字体最大5档（非应用字号），跟随系统预览实际放大，长中英文草稿完整、审批参数可展开且拒绝按钮完整可点击')
