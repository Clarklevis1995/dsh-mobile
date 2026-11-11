"""首次门禁之前已冻结；只报告实际 SDK 与实际应用回调数据。"""
import json
import time


def run(ui, m, folder, passed):
    gates = json.loads((m.ROOT / 'harmony/testing/baseline/p7-performance-gates.json').read_text()); samples = []
    def capture(label):
        time.sleep(1.1); row = ui.wait('test-p7-stats'); value = json.loads(row['text']); value['label'] = label; samples.append(value)
        (folder / 'performance.json').write_text(json.dumps(dict(gates=gates, samples=samples, measurement='Actual SDK PSS; JS bridge/view/draft callback elapsed; TaskPool round-trip; draw callbacks are not GPU frames'), ensure_ascii=False, indent=2)); return value
    ui.wait('home-settings', timeout=35); capture('home-baseline'); m.connect(ui); ui.wait('message-draft'); ui.text('一千条历史加载完成', timeout=30); time.sleep(2)
    first = capture('1005-history-first-tail'); ui.screenshot('1005-history-first-tail')
    assert first['messages'] >= gates['stableHistoryEventsMinimum'], first
    assert first['firstTailMs'] >= 0, '未得到真实尾部卡片完成回调'
    for index in range(8): ui.input('message-draft', '性能输入样本 ' + str(index))
    capture('typing')
    for index in range(12): ui.scroll(False)
    capture('after-12-older-swipes'); ui.screenshot('virtualized-reading-older')
    for index in range(12): ui.scroll(True)
    final = capture('after-12-newer-swipes'); ui.screenshot('virtualized-reading-return')
    failures = []
    checks = [
        (first['firstTailMs'] <= gates['firstTailReadyMillisecondsMaximum'], '尾部首屏'),
        (max(row['peakCards'] for row in samples) <= gates['liveMessageCardsMaximum'], '挂载数量'),
        (final['bridgeP95Ms'] <= gates['bridgeP95MillisecondsMaximum'], '桥接 P95'),
        (final['updateP95Ms'] <= gates['viewUpdateP95MillisecondsMaximum'], '镜像更新 P95'),
        (0 <= final['draftP95Ms'] <= gates['draftCallbackP95MillisecondsMaximum'], '编辑回调 P95'),
        (max(int(row['pssKiB']) / 1024 for row in samples) <= gates['processPssMiBMaximum'], '真实 PSS 峰值'),
        ((int(final['pssKiB']) - int(first['pssKiB'])) / 1024 <= gates['scrollIdlePssGrowthMiBMaximum'], '滚动后 PSS 增量'),
    ]
    for ok, label in checks:
        if not ok: failures.append(label)
    (folder / 'performance-gates.json').write_text(json.dumps(dict(checks=[dict(label=label, passed=ok) for ok,label in checks], failures=failures), ensure_ascii=False, indent=2))
    assert not failures, failures
    passed('P7-1005-history-F', '1005 条真实投影历史、惰性卡片数量、尾部完成、桥接／镜像／编辑回调和真实 PSS 门禁通过；draw 回调不冒充 GPU 帧时间')
