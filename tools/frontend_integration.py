"""Real browser acceptance with the existing isolated project test stack. No test HTTP API."""
from __future__ import annotations
import json
import os
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path
from frontend import ROOT, FRONTEND, CREATE_FLAGS, node_command, frontend_environment, stop_owned
from live_integration import LiveRun, java_executable, JAR
from simulator.broker import Broker, port_open
from simulator.config import prepare, confined
from simulator.scenarios import Run
from simulator.storage import atomic_json

def start_vite(directory):
    if port_open(5173): raise ValueError('Port 5173 occupied')
    log=(directory/'vite.log').open('wb')
    process=subprocess.Popen(node_command('dev')+['--host','127.0.0.1','--port','5173','--strictPort'], cwd=FRONTEND, env=frontend_environment(), stdout=log, stderr=subprocess.STDOUT, creationflags=CREATE_FLAGS)
    try:
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            if process.poll() is not None: raise AssertionError('Own Vite exited')
            try:
                with urllib.request.urlopen('http://127.0.0.1:5173', timeout=1): return process,log
            except OSError: time.sleep(.1)
        raise AssertionError('Own Vite startup timeout')
    except BaseException: stop_owned(process);log.close();raise

def isolated_browser():
    directory=ROOT/'artifacts/task006'
    directory.mkdir(parents=True,exist_ok=True)
    process,log=start_vite(directory)
    try:
        return subprocess.run(node_command('browser')+['test','--config','tests/playwright.config.ts'], cwd=FRONTEND,env=frontend_environment(),creationflags=CREATE_FLAGS).returncode
    finally:
        stop_owned(process);log.close()
        if port_open(5173): raise AssertionError('Own isolated Vite did not release port')

class BrowserStack(LiveRun):
    def __init__(self,directory,config):
        super().__init__(directory,config);self.suspended=None;self.silence_start=None;self.viewer_revoked=False
    def suspend_pose(self):
        if self.suspended is not None: raise AssertionError('Already suspended')
        child=self.run.nodes['POSE']['process']
        if os.name=='nt':
            import ctypes
            native=ctypes.WinDLL('ntdll');suspend=native.NtSuspendProcess;resume=native.NtResumeProcess
            suspend.argtypes=resume.argtypes=[ctypes.c_void_p];suspend.restype=resume.restype=ctypes.c_long
            if suspend(int(child._handle))!=0:raise AssertionError('Own process suspension failed')
            self.suspended=lambda:resume(int(child._handle))
        else:
            import signal
            child.send_signal(signal.SIGSTOP);self.suspended=lambda:child.send_signal(signal.SIGCONT)
        self.silence_start=time.monotonic()
    def resume_pose(self):
        if self.suspended:
            resume=self.suspended;self.suspended=None;resume()
    def sql(self,statement):
        from urllib.parse import urlsplit
        url=urlsplit(self.env['SMARTBOX_DB_URL'].removeprefix('jdbc:'))
        if url.path != '/smartbox_test' or url.hostname not in ('localhost','127.0.0.1') or not self.env['SMARTBOX_DB_USER'].startswith('smartbox_test_'):raise ValueError('Only project test database allowed')
        mysql=shutil.which('mysql')
        if not mysql:raise ValueError('Existing MySQL command client required')
        env=os.environ.copy();env['MYSQL_PWD']=self.env['SMARTBOX_DB_PASSWORD']
        result=subprocess.run([mysql,'--protocol=TCP','--host=127.0.0.1','--port=3306','--user='+self.env['SMARTBOX_DB_USER'],'--database=smartbox_test','--batch','--skip-column-names'], input=statement.encode(),env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,creationflags=CREATE_FLAGS)
        if result.returncode:raise AssertionError('Test-only SQL operation failed (details suppressed)')
        return result.stdout.decode().strip()
    def control(self,action):
        if action=='restore_viewer':self.restore_viewer();return {}
        if action=='suspend_arming_pose':
            self.run.wait(lambda:self.run.status('POSE').get('sentry')=='ARMING','POSE already received sentry before suspension',5)
            self.suspend_pose();return {}
        if action=='suspend_pose':self.suspend_pose();return {}
        if action=='resume_pose':
            elapsed=time.monotonic()-self.silence_start if self.silence_start else 0
            self.resume_pose();return {'observed_silence_s':round(elapsed,3)}
        if action=='broker_restart':
            self.run.broker.stop();self.run.pump(.5);self.run.broker.start()
            for peer in list(self.run.peers):self.run.remove_peer(peer)
            self.run.observer_start();return {}
        if action=='backend_stop':self.stop();return {}
        if action=='backend_start':self.start();return {}
        if action=='revoke_viewer':
            account=next(u for u in self.fixture['users'] if u['role']=='LOGISTICS_VIEWER' and u['box_ids']==['BOX-0001'])['username'].replace("'","''")
            self.viewer_revoked=True
            self.sql("DELETE FROM user_box_access WHERE box_id='BOX-0001' AND user_id=(SELECT id FROM users WHERE username='"+account+"');")
            return {}
        if action=='validate_delivery_frames':
            frames=json.loads((self.dir/'delivery-frames.json').read_text())
            if not any(m.get('type')=='snapshot' for m in frames):raise AssertionError('No actual delivery WS snapshot')
            for message in frames:
                self.validate(message,'WsPush')
                if message['type'] in ('node_status','command_status'):raise AssertionError('Restricted WS category')
                if message['type']=='snapshot' and any(k in message['data'] for k in ('pose','sentry','evidence','node_diagnostics')):raise AssertionError('Restricted WS field')
            for role in ('LOGISTICS_OPERATOR','LOGISTICS_VIEWER','DELIVERY_USER','COURIER'):
                token=self.login(role)
                self.request('/api/v1/boxes/BOX-0001/latest',token=token,definition='LatestResponse')
            self.check(True,'independent effective Schema validates real four-role HTTP and nonempty delivery WS messages')
            return {'frames':len(frames)}
        raise ValueError('Unknown browser control action')
    def restore_viewer(self):
        account=next(u for u in self.fixture['users'] if u['role']=='LOGISTICS_VIEWER' and u['box_ids']==['BOX-0001'])['username'].replace("'","''")
        self.sql("INSERT IGNORE INTO user_box_access(user_id,box_id,created_at) SELECT id,'BOX-0001',"+str(int(time.time()*1000))+" FROM users WHERE username='"+account+"' AND role_code='LOGISTICS_VIEWER';")
        self.viewer_revoked=False

def integrate(config,directory,scenario='all'):
    directory=confined(directory,ROOT/'artifacts')
    if directory.exists(): raise ValueError('Use a new private evidence directory')
    if any(port_open(p) for p in (1883,8080,5173)):raise ValueError('Required port occupied; no process stopped')
    if not JAR.is_file():raise ValueError('Build backend first')
    directory.mkdir(parents=True)
    stack=BrowserStack(directory,config)
    vite=None;vite_log=None;browser=None;browser_log=None;passed=False;failure=None
    try:
        env=stack.env.copy();env.update({'SMARTBOX_TEST_ALLOW_RESET':'true','SPRING_MAIN_WEB_APPLICATION_TYPE':'none','SMARTBOX_MQTT_ENABLED':'false'})
        with (directory/'test-reset.log').open('wb') as output:
            result=subprocess.run([java_executable(env),'-jar',str(JAR),'--smartbox.integration.reset=true','--spring.main.web-application-type=none'],cwd=ROOT/'backend',env=env,stdout=output,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS,timeout=40)
        if result.returncode:raise AssertionError('Project test reset failed')
        # The existing reset intentionally preserves identity tables. Restore only this scenario's revoked grant.
        stack.restore_viewer()
        cfg=directory/'mqtt.local.json';mqtt=prepare(cfg)
        stack.run=Run(directory,cfg);stack.run.broker=Broker(mqtt,directory/'broker');stack.run.broker.start();stack.run.observer_start()
        stack.env.update({'SMARTBOX_MQTT_ENABLED':'true','SMARTBOX_MQTT_USER':mqtt['accounts']['backend']['username'],'SMARTBOX_MQTT_PASSWORD':mqtt['accounts']['backend']['password']})
        stack.start();stack.run.start_node('ENV');stack.run.start_node('POSE')
        token=stack.login('LOGISTICS_OPERATOR')
        stack.wait(lambda:stack.request('/api/v1/boxes/BOX-0001/latest',token=token)['data']['online_mask']==3,'dual nodes before browser')
        stack.check(True,'real backend and dual independent node processes ready')
        vite,vite_log=start_vite(directory)
        # Passwords remain only in this ignored input; no trace/HAR or credential screenshots.
        atomic_json(directory/'browser-input.local.json',{'users':stack.fixture['users'],'directory':str(directory)})
        browser_env=frontend_environment();browser_env['SMARTBOX_BROWSER_INPUT']=str(directory/'browser-input.local.json')
        browser_log=(directory/'browser.log').open('wb')
        selection=['--grep','targeted:'] if scenario=='authorization' else []
        browser=subprocess.Popen(node_command('browser')+['test','--config','tests/live.config.ts']+selection,cwd=FRONTEND,env=browser_env,stdout=browser_log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS)
        started=time.monotonic();handled=set();next_progress=0
        while browser.poll() is None:
            if time.monotonic()-started>700:raise AssertionError('Own browser acceptance exceeded bounded duration')
            stack.run.pump(.03)
            if time.monotonic()>next_progress:
                print('Browser acceptance in progress: '+str(round(time.monotonic()-started))+'s',flush=True);next_progress=time.monotonic()+20
            for request in directory.glob('control-*.request.json'):
                if request.name in handled:continue
                data=json.loads(request.read_text());handled.add(request.name)
                output=stack.control(data['action'])
                atomic_json(directory/(request.name.replace('.request.json','.response.json')),{'ok':True,**output})
        if browser.returncode:raise AssertionError('Real browser checks failed; inspect private browser.log')
        result=json.loads((directory/'browser-summary.json').read_text())
        stack.check(result.get('passed') is True,'real browser '+scenario+' checks passed')
        stack.results.extend(result['checks'])
        passed=True
    except Exception as error:
        failure=type(error).__name__+(': '+str(error)[:180] if isinstance(error,AssertionError) else '')
        print('FAIL: '+failure,flush=True)
    finally:
        try:
            stack.resume_pose();stop_owned(browser)
            if browser_log:browser_log.close()
            stop_owned(vite)
            if vite_log:vite_log.close()
            if stack.viewer_revoked:stack.restore_viewer()
            stack.cleanup()
            if port_open(5173):raise AssertionError('Own Vite port not released')
            stack.check(True,'own browser and Vite cleaned; ports 1883 8080 5173 released')
        except Exception as error:passed=False;failure='Cleanup: '+type(error).__name__
        atomic_json(directory/'summary.json',{'passed':passed,'error':failure,'checks':stack.results,'scenario':scenario,'test_database':'smartbox_test'})
    return 0 if passed else 1
