"""真实会话消息身份、跨端追加与当前会话停止隔离。"""
import json,time,uuid


def run(ui,m,folder,passed,call,session):
    checks=[]
    def wait(fn,timeout=45):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实消息矩阵未按时收束')
    def history(identity=None):return call('/history',{'id':identity or session['id']})['records']
    def kind(r):return r.get('type') or (r.get('event') or {}).get('type')
    def text(r):
        e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('message',{}).get('content',e.get('content',[])) if b.get('type')=='text')
    def users(marker,identity=None):return sum(kind(r)=='user/message' and text(r)==marker for r in history(identity))
    def reply(marker):return any(kind(r)=='assistant/message' and marker in text(r) for r in history())
    def done(name,detail,**data):
        checks.append(dict(case=name,**data));(folder/'real-message-matrix.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',checks=checks,method='real owned Host, native ArkUI, local controlled model; no user sessions'),ensure_ascii=False,indent=2));passed('P1-real-'+name+'-G',detail)
    marker='HARMONY_REAL_DOUBLETAP_'+uuid.uuid4().hex[:8].upper();ui.input('message-draft',marker);row,x,y=ui.control('message-send')
    m.hdc('shell','uitest','uiInput','doubleClick',str(x),str(y));wait(lambda:reply(marker));wait(lambda:not ui.find('message-stop'));assert users(marker)==1;ui.wait('message-draft');assert not ui.find('voice-hold')
    done('rapid-double-tap','真实发送按钮快速双击仅产生一条用户消息和一轮回复',userMessages=1)
    marker='HARMONY_REAL_EXTERNAL_'+uuid.uuid4().hex[:8].upper();call('/message',dict(id=session['id'],text=marker));wait(lambda:reply(marker));ui.text(marker,timeout=25);assert users(marker)==1;ui.screenshot('real-cross-client-live-append')
    done('cross-client-append','另一真实客户端追加消息与回复自动在当前原生会话显示，消息唯一',userMessages=1)
    marker_a='HARMONY_SLOW_STOP_A_'+uuid.uuid4().hex[:8].upper();ui.message(marker_a);ui.wait('message-stop')
    other=call('/create',dict(label='P1-stop-isolation-B-'+uuid.uuid4().hex[:6]));call('/subscribe',dict(id=other['id']));marker_b='HARMONY_SLOW_STOP_B_'+uuid.uuid4().hex[:8].upper();call('/message',dict(id=other['id'],text=marker_b))
    def frames_b():return next(r['streamFrames'] for r in call('/status')['sessions'] if r['id']==other['id'])
    wait(lambda:frames_b()>=3);before=frames_b();ui.click('message-stop');wait(lambda:not ui.find('message-stop'));after=wait(lambda:frames_b() if frames_b()>before else None)
    assert after<200,'B必须在A停止后仍实际生成，不能用已结束会话证明隔离'
    assert not any(kind(r)=='turn/end' for r in history(other['id']))
    call('/cancel',dict(id=other['id']));wait(lambda:any(kind(r)=='turn/end' for r in history(other['id'])))
    call('/subscribe',dict(id=session['id']));wait(lambda:any(kind(r)=='turn/end' for r in history()));assert users(marker_a)==1
    ui.screenshot('real-native-stop-isolated-A')
    marker='HARMONY_REAL_AFTER_STOP_'+uuid.uuid4().hex[:8].upper();ends=sum(kind(r)=='turn/end' for r in history());ui.message(marker);wait(lambda:reply(marker));wait(lambda:sum(kind(r)=='turn/end' for r in history())>ends);assert users(marker)==1
    wait(lambda:not ui.find('message-stop'));call('/cancel',dict(id=session['id']))
    marker='HARMONY_REAL_AFTER_ENDED_CANCEL_'+uuid.uuid4().hex[:8].upper();ui.message(marker);wait(lambda:reply(marker));assert users(marker)==1;wait(lambda:not ui.find('message-stop'))
    done('stop-current-session','A/B真实同时生成，原生停止A后B仍产生流帧；A收束且可继续聊天，已结束A的取消不破坏下一轮',stoppedSession=session['id'],unaffectedSession=other['id'],otherStreamBefore=before,otherStreamAfter=after,continuedUserMessages=1,afterEndedCancelUserMessages=1)
