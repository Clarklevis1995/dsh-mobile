# coding: utf-8
"""真实Host新消息进入实际索引后，断网验证中文/大小写/百分号字面量。"""
import json
import time
import uuid


def prepare(ui, call, session, seed):
    suffix = uuid.uuid4().hex[:8].upper()
    value = dict(chinese='鲸鱼离线检索_' + suffix,
                 english='MixedCase_' + suffix,
                 literal='100%_literal_' + suffix)
    marker = 'HARMONY_HISTORY_' + seed['run'] + '_' + ' '.join(value.values())
    def records(): return call('/history', {'id': session['id']})['records']
    before = sum((r.get('event') or {}).get('type') == 'turn/end' for r in records())
    call('/message', {'id': session['id'], 'text': marker})
    end = time.monotonic() + 40
    while time.monotonic() < end:
        source = records()
        if sum((r.get('event') or {}).get('type') == 'turn/end' for r in source) > before: break
        time.sleep(.4)
    else: raise AssertionError('真实双语消息未完成持久化')
    assert any(marker in json.dumps(r, ensure_ascii=False) for r in source)
    ui.text(marker, timeout=35)
    time.sleep(1.2)
    value['marker'] = marker
    return value


def offline(ui, storage, wait, values, draft, folder):
    storage(); ui.navigate('storage-search', 'offline-search-input')
    observed = []
    def results():
        return sorted({r['id'] for r in ui.nodes() if r.get('id', '').startswith('offline-result-')})
    for kind, query in [('Chinese', values['chinese']), ('English-casefold', values['english'].lower()), ('literal-percent-underscore', values['literal'])]:
        ui.click('offline-search-host'); ui.input('offline-search-input', query)
        host_results = wait(results)
        assert len(host_results) <= 2, '单条真实消息不应将百分号/下划线当作通配符'
        ui.click('offline-search-session')
        session_results = wait(results)
        assert host_results == session_results
        observed.append(dict(kind=kind, query=query, messageIds=host_results, sessionIdsMatch=True))
        ui.screenshot('real-cache-search-' + kind)
    ui.input('offline-search-input', '未出现的鲸鱼_' + uuid.uuid4().hex)
    ui.wait('offline-search-empty')
    ui.input('offline-search-input', values['chinese'])
    key = wait(results)[0]; ui.click(key); ui.wait('message-draft')
    ui.text(values['marker']); assert ui.find('message-draft')['text'] == draft
    ui.screenshot('real-cache-Chinese-located')
    ui.navigate('conversation-back', 'offline-search-input')
    assert ui.find('offline-search-input')['text'] == values['chinese']
    ui.navigate('page-back', 'storage-refresh'); ui.navigate('page-back', 'settings-style'); ui.navigate('page-back', 'home-settings')
    (folder / 'real-cache-bilingual-search.json').write_text(json.dumps(observed, ensure_ascii=False, indent=2))
    return observed
