"""受控目标协议，实际核对 CAS；不执行用户目标。"""
import copy

STATE = {}


def state(session):
    if session not in STATE: STATE[session] = dict(goal=dict(goal=dict(id='fixture-goal', revision=1, objective='完成鸿蒙目标闭环', phase='active'), roundsStarted=2), seq=100)
    return STATE[session]


def handle(client, request, session):
    kind = request.get('type')
    if kind == 'tasks': client.send(dict(kind='tasks', sessionId=session, asOfSeq=100, todos=[dict(content='已完成任务样本', status='completed'), dict(content='当前执行任务样本', status='in_progress')]))
    elif kind == 'goal':
        row = state(session); client.send(dict(kind='goal', sessionId=session, asOfSeq=row['seq'], goal=copy.deepcopy(row['goal'])))
    elif kind in ['goal-pause', 'goal-resume', 'goal-edit', 'goal-clear']:
        row = state(session); goal = row['goal']; expected = request['ref']
        if not goal or expected != dict(id=goal['goal']['id'], revision=goal['goal']['revision']):
            client.send(dict(kind='error', sessionId=session, requestType=kind, message='目标版本已更新')); return True
        if kind == 'goal-clear': row['goal'] = None
        else:
            goal['goal']['revision'] += 1
            if kind == 'goal-edit': goal['goal']['objective'] = request['objective']
            else: goal['goal']['phase'] = 'paused' if kind == 'goal-pause' else 'active'
        row['seq'] += 1
        client.send(dict(kind=kind, sessionId=session))
    else: return False
    return True
