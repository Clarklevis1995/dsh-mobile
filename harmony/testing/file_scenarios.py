"""实际 TaskPool 写盘、原生 Markdown／图片／PDF 阅读的夹具 F。"""
import time


def run(ui, m, folder, passed):
    m.connect(ui); ui.wait('message-draft'); ui.navigate('conversation-files', 'files-refresh')
    ui.navigate('workspace-file-资料', 'workspace-file-资料/报告 空格.md')
    ui.navigate('workspace-file-资料/报告 空格.md', 'file-preview-save'); ui.text('原生文件预览', timeout=25); ui.text('worker native syntax', timeout=25); ui.text('完整校验', timeout=25)
    assert not ui.find('file-preview-error'); ui.screenshot('native-Markdown-code-table-file')
    ui.click('file-preview-mode'); ui.wait('file-preview-text'); assert '```kotlin' in ui.find('file-preview-text').get('text', '')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('workspace-file-资料/示例.pdf', 'native-pdf-view'); ui.wait('pdf-pages', timeout=25)
    assert ui.find('pdf-pages').get('text') == '1 页'; assert not ui.find('pdf-error'); ui.screenshot('native-PDF-file')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('files-parent', 'workspace-file-图片.png'); ui.navigate('workspace-file-图片.png', 'file-preview-save'); time.sleep(1); ui.screenshot('native-image-file')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('workspace-file-分块验收.txt', 'file-preview-text'); ui.text('后台逐块写入与完整校验', timeout=30)
    assert not ui.find('file-preview-error'); ui.screenshot('native-multichunk-text-file')
    ui.navigate('page-back', 'files-refresh'); ui.click('workspace-file-损坏下载.txt'); ui.wait('files-error', timeout=20)
    assert not ui.find('file-preview-save'), '摘要错误文件仍进入预览'; ui.screenshot('bad-download-digest-rejected')
    passed('P5-workspace-files-F', '实际目录／中文空格路径，下载背压与落盘、Markdown／代码／表格、单页原生 PDF、图片和多块文本阅读；错误摘要拒绝预览')
