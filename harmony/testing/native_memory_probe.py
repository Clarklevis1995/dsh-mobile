#!/usr/bin/env python3
# coding: utf-8
"""在临时工程构建只读Native内存探针，禁止覆盖生产源码或库。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]


def instrument_app(app):
    assert app.resolve() != (ROOT/'harmonyApp').resolve(),'禁止在生产工程注入探针'
    assert json.loads((app/'AppScope/app.json5').read_text())['app']['bundleName']=='com.clarklevis.whaleness.harmony.smoke','探针仅限独立测试包'
    base=app/'entry/src/main/ets'
    def patch(name,old,new):
        p=base/name;s=p.read_text();assert old in s,(name,old);p.write_text(s.replace(old,new,1))
    patch('core/GatewayClient.ets','  get connected(): boolean', '  get nativeMemoryProbe(): string { return this.engine.dispatch(\'{"op":"native-memory-probe"}\'); }\n  get connected(): boolean')
    patch('pages/Index.ets','this.perfText = PerfStats.report(this.messages.length);', "PerfStats.nativeMemory = this.runtime?.client.nativeMemoryProbe ?? ''; this.perfText = PerfStats.report(this.messages.length);")
    patch('core/PerfStats.ets','interface PerfSnapshot {', 'interface PerfSnapshot {\n  nativeMemory: string;')
    patch('core/PerfStats.ets','export class PerfStats {', "export class PerfStats {\n  static nativeMemory: string = '';")
    patch('core/PerfStats.ets','const snapshot: PerfSnapshot = {', 'const snapshot: PerfSnapshot = { nativeMemory: PerfStats.nativeMemory,')


def prepare():
    original=ROOT/'harmonyApp/entry/src/main/cpp/generated/arm64-v8a/libwhaleness.so'
    original_hash=hashlib.sha256(original.read_bytes()).hexdigest()
    folder=Path(tempfile.mkdtemp(prefix='harmony-native-memory-'));print('PROBE_ROOT',folder,flush=True)
    shutil.copytree(ROOT/'harmony',folder/'harmony',ignore=shutil.ignore_patterns('build','.gradle','testing'))
    (folder/'shared').symlink_to(ROOT/'shared',target_is_directory=True)
    app=folder/'harmonyApp';shutil.copytree(ROOT/'harmonyApp',app,ignore=shutil.ignore_patterns('build','.hvigor','oh_modules','.idea'))
    p=app/'AppScope/app.json5';d=json.loads(p.read_text());d['app']['bundleName']='com.clarklevis.whaleness.harmony.smoke';p.write_text(json.dumps(d,indent=2))
    native=folder/'harmony/shared/src/ohosArm64Main/kotlin/whaleness'
    shutil.copy2(ROOT/'harmony/testing/compatibility/NativeMemoryProbe.kt',native/'NativeMemoryProbe.kt')
    p=native/'Exports.kt';s=p.read_text();old=': String = engines[handle]?.dispatch(command)';new=': String = if (command == "{\\\"op\\\":\\\"native-memory-probe\\\"}") nativeMemoryProbe() else engines[handle]?.dispatch(command)';assert old in s;s=s.replace(old,new);p.write_text(s)
    deveco=Path(os.environ.get('DEVECO_STUDIO_PATH','/Applications/DevEco-Studio.app/Contents'))
    env=dict(os.environ,JAVA_HOME=str(deveco/'jbr/Contents/Home'),HARMONY_NATIVE_MODE='release')
    with (folder/'native-build.log').open('w') as log:
        subprocess.run([str(folder/'harmony/gradlew'),'-p',str(folder/'harmony'),':shared:publishNative','--console=plain','--no-daemon'],env=env,cwd=folder,stdout=log,stderr=log,check=True)
    assert hashlib.sha256(original.read_bytes()).hexdigest()==original_hash,'生产库发生意外变化'
    probe=app/'entry/src/main/cpp/generated/arm64-v8a/libwhaleness.so';assert probe.is_file() and hashlib.sha256(probe.read_bytes()).hexdigest()!=original_hash
    result=dict(root=str(folder),app=str(app),productionLibraryUnchanged=True,productionSha256=original_hash,probeSha256=hashlib.sha256(probe.read_bytes()).hexdigest(),compiledProbeSourceSha256=hashlib.sha256((native/'NativeMemoryProbe.kt').read_bytes()).hexdigest(),compiledExportsSha256=hashlib.sha256((native/'Exports.kt').read_bytes()).hexdigest(),sourceCheckpoint=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),scope='仅本轮临时Native库的已完成GC只读统计，无GC触发/调度参数修改，不进入候选')
    (folder/'probe-manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('NATIVE_MEMORY_PROBE_READY',app,flush=True)


if __name__=='__main__':
    argparse.ArgumentParser(description=__doc__).parse_args();prepare()
