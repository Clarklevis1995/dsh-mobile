"""扩展页面协议夹具；仅在独立产品回归中启用，不进入应用。"""
import copy
import time

BUNDLE_ENABLED = False
ENTRY_ENABLED = False
TASKS = [dict(id='weekly', sessionId='harmony-smoke', title='每周验收任务', prompt='原始任务内容', kind='weekly', status='active', scheduledAt='2030-01-02T14:00:45.123Z', time='09:00:45.123', timeZone='America/New_York', weekdays=[1, 3]),
    dict(id='every', sessionId='harmony-smoke', title='定期检查', prompt='每小时检查一次', kind='every', status='active', scheduledAt='2030-01-01T00:00:00.000Z', everySeconds=3600),
    dict(id='ended', sessionId='harmony-smoke', title='已结束任务', prompt='旧内容', kind='at', status='ended', scheduledAt='2026-01-01T00:00:00.000Z')]


def handle(client, request, identifier):
    global BUNDLE_ENABLED
    kind = request.get('type'); rid = request.get('requestId')
    if kind == 'plugin-catalog':
        client.send(dict(kind=kind, requestId=rid, managementAvailable=True, settingsWritable=True,
            bundles=[dict(name='test-plugin', installed=True, enabled=BUNDLE_ENABLED, version='1.0.0', meta=dict(title={'zh-CN': '验收插件'}, description={'zh-CN': '原生插件交互验收'}), rows=[dict(rowId='row', entryId='entry', moduleName='fixture-module')]), dict(name='dsh-plugin-mobile-gateway', installed=True, enabled=True, meta=dict(title={'zh-CN': '移动网关'}), rows=[])],
            plugins=[dict(entryId='entry', name='验收组件', enabled=ENTRY_ENABLED, fiberPhase='active')], officialItems=[dict(id='shell', namespaces=['shell.exec', 'shell.read'])]))
    elif kind in ['plugin-bundle-set-enabled', 'plugin-entry-set-enabled']:
        time.sleep(.4)
        if kind == 'plugin-bundle-set-enabled':
            if request.get('name') == 'dsh-plugin-mobile-gateway': raise AssertionError('测试只应取消关闭网关，不能执行')
            BUNDLE_ENABLED = request['enabled']
        client.send(dict(kind=kind, requestId=rid, result=dict(application='applied' if kind == 'plugin-bundle-set-enabled' else 'restart-required')))
    elif kind == 'schedule-catalog': client.send(dict(kind=kind, items=copy.deepcopy(TASKS)))
    elif kind == 'schedule-update':
        record = next(row for row in TASKS if row['id'] == request['id'] and row['sessionId'] == identifier)
        changed = request.get('expected') == record
        if changed:
            record.update(title=request['title'], prompt=request['prompt'])
            change = request.get('change')
            if change:
                if change['kind'] == 'weekly': record.update(time=change['weekly']['time'], timeZone=change['weekly']['time_zone'], weekdays=change['weekly']['weekdays'])
                elif change['kind'] == 'daily': record.update(time=change['daily']['time'], timeZone=change['daily']['time_zone'])
                elif change['kind'] == 'at': record.update(kind='at', scheduledAt=change['at'])
        client.send(dict(kind=kind, sessionId=identifier, requestId=rid, updated=changed))
    elif kind == 'schedule-delete':
        old = len(TASKS); TASKS[:] = [row for row in TASKS if not (row['id'] == request['id'] and row['sessionId'] == identifier)]
        client.send(dict(kind=kind, sessionId=identifier, requestId=rid, deleted=len(TASKS) != old))
    elif kind == 'agent-team':
        client.send(dict(kind=kind, sessionId=identifier, requestId=rid, asOfSeq=10, agentTeam=dict(members=[dict(id=identifier, name='主智能体', role='lead', phase='active'), dict(id='harmony-child', name='团队成员甲', role='teammate', phase='active')], tasks=[dict(id='task-ready', subject='待处理验收任务', description='详情：保持共享任务与内部 todos 分离。', status='pending', ready=True), dict(id='task-done', subject='已完成验收任务', description='已完成详情', status='completed')]) if identifier == 'harmony-smoke' else None))
    elif kind == 'subagents':
        client.send(dict(kind=kind, sessionId=identifier, requestId=rid, asOfSeq=10, subagentCatalog=[dict(id='harmony-once', label='一次性验收任务', mode='oneshot')] if identifier == 'harmony-smoke' else []))
    elif kind == 'history' and str(rid).startswith('team-profile-'):
        client.send(dict(kind=kind, sessionId=identifier, requestId=rid, parentSessionId='harmony-smoke', projections=dict(asOfSeq=10, values=dict(subagent=dict(label='成员资料已确认', mode='oneshot' if identifier == 'harmony-once' else 'continuable'), modelSelection=dict(model='fixture')))))
    else: return False
    return True
