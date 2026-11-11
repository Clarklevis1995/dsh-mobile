"""首次门禁之前已冻结；只报告实际 SDK 与实际应用回调数据。"""
import json
import time
import os


def run(ui, m, folder, passed):
    if os.environ.get('HARMONY_PERF_SNAPSHOTS_ONLY') == '1':
        from perf_snapshot_scenarios import run as snapshot_control
        return snapshot_control(ui,m,folder,passed)
    if os.environ.get('HARMONY_PERF_LONG_STREAM') == '1':
        from perf_stream_scenarios import run as streaming
        return streaming(ui,m,folder,passed)
    gates = json.loads((m.ROOT / 'harmony/testing/baseline/p7-performance-gates.json').read_text()); samples = []
    def capture(label):
        time.sleep(1.1); row = ui.wait('test-p7-stats'); value = json.loads(row['text']); value['label'] = label; samples.append(value)
        (folder / 'performance.json').write_text(json.dumps(dict(gates=gates, samples=samples, measurement='Actual SDK PSS; JS bridge/view/draft callback elapsed; TaskPool round-trip; draw callbacks are not GPU frames'), ensure_ascii=False, indent=2)); return value
    if os.environ.get('HARMONY_PERF_MEMORY_ONLY') != '1':
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

    else:
        m.connect(ui);ui.wait('message-draft');ui.text('一千条历史加载完成',timeout=30)
    if os.environ.get('HARMONY_PERF_HOST_SWITCH') == '1':
        def memory(label):
            time.sleep(1.2)
            raw = ui.wait('test-memory-hosts')['text']
            rows = [dict(zip(['host','bytes','sessions','cards'], item.split(':'))) for item in raw.split('|')]
            (folder / ('memory-' + label + '.json')).write_text(json.dumps(rows, indent=2))
            return rows
        first_memory = memory('host-A-loaded'); assert len(first_memory) == 1 and int(first_memory[0]['bytes']) > 0
        host_a = first_memory[0]['host']
        ui.input('message-draft','跨主机回收保留草稿 A')
        ui.navigate('conversation-back','home-settings');ui.navigate('home-auth','host-add');ui.click('host-add')
        ui.input('gateway-endpoint','ws://127.0.0.1:18784/ws/mobile');ui.input('gateway-pairing-code','harmony-smoke');ui.click('gateway-connect')
        ui.text('已连接',timeout=25);ui.click('B 鸿蒙原生会话验证','text');ui.text('一千条历史加载完成',timeout=30)
        second_memory = memory('host-B-loaded')
        old = next(row for row in second_memory if row['host'] == host_a)
        assert [int(old[key]) for key in ['bytes','sessions','cards']] == [0,0,0], old
        ui.input('message-draft','跨主机回收保留草稿 B')
        ui.navigate('conversation-back','home-settings');ui.navigate('home-auth','host-add');ui.click('host-select-'+host_a)
        ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft')
        deadline=time.monotonic()+30
        while json.loads(ui.wait('test-p7-stats')['text'])['messages'] < gates['stableHistoryEventsMinimum']:
            assert time.monotonic()<deadline,'未恢复完整网络窗口'
            time.sleep(.4)
        assert ui.wait('message-draft').get('text')=='跨主机回收保留草稿 A'
        third_memory = memory('host-A-restored')
        inactive = next(row for row in third_memory if row['host'] != host_a)
        assert [int(inactive[key]) for key in ['bytes','sessions','cards']] == [0,0,0], inactive
        assert int(next(row for row in third_memory if row['host'] == host_a)['bytes']) > 0
        ui.screenshot('cross-host-history-restored')
        passed('P6-cross-host-history-release-F','两台隔离主机相同会话ID各载入1005条历史，A→B→A后非活动主机Native历史字节/会话数/ArkUI镜像均为0；当前主机历史重新接管且文字草稿逐字保持')
