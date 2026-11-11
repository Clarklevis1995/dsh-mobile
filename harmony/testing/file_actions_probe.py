"""只在独立测试副本回读刚保存的系统URI，清理由驱动逐字确认唯一文件名后完成。"""
def instrument(app):
    p=app/'entry/src/main/ets/core/FileWorker.ets';s=p.read_text()
    old='export function exportWorkspaceFile(path: string, uri: string): void {'
    assert old in s;s=s.replace(old,old.replace(': void',': string'),1)
    start=s.index('export function exportWorkspaceFile(');end=s.index('\n}',start)
    s=s[:end]+"\n  const resultFile = fs.openSync(uri, fs.OpenMode.READ_ONLY);\n  try {\n    const length = fs.statSync(resultFile.fd).size; if (length > 524288) { throw new Error('probe file too large'); }\n    const bytes = new ArrayBuffer(length); if (fs.readSync(resultFile.fd, bytes) !== length) { throw new Error('incomplete export'); }\n    return JSON.stringify({ uri: uri, text: new util.TextDecoder().decodeToString(new Uint8Array(bytes)) });\n  } finally { fs.closeSync(resultFile); }"+s[end:];p.write_text(s)
    p=app/'entry/src/main/ets/pages/FilePreviewPage.ets';s=p.read_text()
    s=s.replace("  @State private exporting: boolean = false;", "  @State private exporting: boolean = false;\n  @State private testExportReceipt: string = '';")
    old='await taskpool.execute(new taskpool.Task(exportWorkspaceFile, file.localPath, uris[0]), taskpool.Priority.HIGH);'
    assert old in s;s=s.replace(old,'this.testExportReceipt = await taskpool.execute(new taskpool.Task(exportWorkspaceFile, file.localPath, uris[0]), taskpool.Priority.HIGH) as string;',1)
    s=s.replace('      PageHeader({ a: this.a, title: this.file.name, onBack: this.onBack })', "      Text(this.testExportReceipt).id('test-export-receipt').fontSize(1).height(1)\n      PageHeader({ a: this.a, title: this.file.name, onBack: this.onBack })",1)
    p.write_text(s)
