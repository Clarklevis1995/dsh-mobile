"""长表格实际惰性阅读与长代码展开／重入；不冒充 GPU 基准。"""
import re
import time
import os


def run(ui,m,folder,passed):
    def swipe_list(identifier,down=True):
        row=ui.wait(identifier);l,t,r,b=map(int,re.findall(r'-?\d+',row['bounds']));x=(l+r)//2;a=t+(b-t)//4;c=t+(b-t)*3//4
        m.hdc('shell','uitest','uiInput','swipe',str(x),str(c if down else a),str(x),str(a if down else c),'5000' if identifier == 'table-reader-list' else '650');time.sleep(.3)
    m.connect(ui);ui.wait('message-draft');ui.text('一千条历史加载完成',timeout=25)
    row=next(row for row in ui.nodes() if 'table-all-' in row.get('id',''))
    ui.navigate(row['id'],'table-reader-list');ui.text('长表格 第0行')
    count=ui.wait('160 行',key='description');assert count['id']=='table-reader-list';ui.screenshot('long-table-first')
    last=False
    for index in range(28):
        if any('长表格 第159行' in row.get('text','') for row in ui.nodes()):last=True;break
        swipe_list('table-reader-list')
    assert last,'完整表格末行无法到达';ui.screenshot('long-table-last');ui.navigate('page-back','message-draft')
    if os.environ.get('HARMONY_READING_SCOPE') == 'table':
        passed('P3-long-table-preview-F','生产TableReader无障碍行数160、惰性滚动至真实第159行与原生返回聊天通过')
        return
    for index in range(24):
        rows = ui.nodes(); found = next((row for row in rows if 'message-body-harmony-smoke-1003-code-expand-' in row.get('id','')),None)
        if found:break
        turns = [int(match.group(1)) for row in rows if (match := re.search(r'message-body-harmony-smoke-(\d+)', row.get('id','')))]
        # 代码预览高于一屏；标题已可见时，要向下读到卡片底部，不能继续往更旧消息滑。
        newer = bool(turns) and min(turns) <= 1003
        print('代码定位', index, min(turns) if turns else None, max(turns) if turns else None, 'newer' if newer else 'older', flush=True)
        swipe_list('conversation-list',newer)
    assert found,'未找到固定千行代码卡片';ui.click(found['id']);time.sleep(.6)
    for index in range(6):
        collapse=next((row for row in ui.nodes() if '-code-collapse-top-' in row.get('id','')),None)
        if collapse:break
        swipe_list('conversation-list',False)
    assert collapse,'长代码没有可达的顶部收起入口';ui.screenshot('long-code-expanded-top')
    ui.navigate('conversation-back','home-settings');ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft');time.sleep(1)
    for index in range(5):
        again=next((row for row in ui.nodes() if row.get('id')==collapse['id']),None)
        if again:break
        swipe_list('conversation-list',False)
    assert again,'页面重入丢失代码展开状态';ui.click(again['id']);ui.screenshot('long-code-folded-after-reentry')
    passed('P3-P7-long-reading-F','完整 160 行表格实际末行可达；千行代码展开、顶部收起入口、返回重入后仍展开；不替代横向宽表格、工具长输出和 GPU／D')
