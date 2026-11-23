"""Explicit, bounded EMQX TLS/ACL smoke. Never creates/stops a cloud deployment."""
from __future__ import annotations
import argparse,csv,json,secrets,socket,ssl,time,uuid
from pathlib import Path
from broker_profiles import ROOT,CLOUD_DEFAULT,load_cloud,configure_client
from simulator.config import UP,DOWN,confined

def prepare(path,host,deployment):
    path=confined(path,ROOT)
    if path.exists():load_cloud(path);print('Existing cloud configuration preserved.');return
    if not host or not deployment:raise ValueError('--host and --deployment-id required')
    base=path.parent/'cloud';base.mkdir(parents=True,exist_ok=True)
    ca=base/'emqxsl-ca.crt'
    if not ca.exists():ca.write_bytes((ROOT/'deploy/emqx/emqxsl-ca.crt').read_bytes())
    names={'ENV':'BOX-0001-ENV','POSE':'BOX-0001-POSE','backend':'smartbox_backend','control':'smartbox_test_control','observer':'smartbox_test_observer'}
    value={'format':1,'box_id':'BOX-0001','deployment_id':deployment,'broker':{'mode':'cloud','host':host,'port':8883,'tls':True,'ca_file':str(ca.resolve())},'state_dir':str(base/'state'),'accounts':{k:{'username':v,'password':secrets.token_urlsafe(32)} for k,v in names.items()}}
    # Exclusive create: a rerun must never rotate already imported credentials.
    with path.open('x',encoding='utf-8') as f:json.dump(value,f,indent=2)
    try:load_cloud(path)
    except Exception:path.unlink();raise
    with (base/'authentication.csv').open('x',newline='',encoding='utf-8') as f:
        w=csv.writer(f);w.writerow(('username','password'));w.writerows((a['username'],a['password']) for a in value['accounts'].values())
    with (base/'authorization.csv').open('x',newline='',encoding='utf-8') as f:
        w=csv.writer(f);w.writerow(('username','topic','action','access'))
        for role in ('ENV','POSE'):
            node=names[role];prefix='box/BOX-0001/'+node+'/'
            for suffix in UP:w.writerow((node,prefix+suffix,'pub','allow'))
            for suffix in DOWN:w.writerow((node,prefix+suffix,'sub','allow'))
            for who in ('backend','control','observer'):
                for suffix in UP:w.writerow((names[who],prefix+suffix,'sub','allow'))
                if who!='observer':
                    for suffix in DOWN:w.writerow((names[who],prefix+suffix,'pub','allow'))
    print('Private configuration/import CSVs created. Import in console, add deny-all fallback, then delete authentication.csv. No state reserved yet.')

def reserve_state(config):
    from hardware import known_boots
    from simulator.storage import FileLock,IdentityState,atomic_json
    state=confined(config['state_dir'],ROOT);state.mkdir(parents=True,exist_ok=True)
    base=ROOT/'secrets/hardware';base.mkdir(parents=True,exist_ok=True);ledger=base/'boot-reservations.json'
    with FileLock(base/'provision.lock'):
        record=json.loads(ledger.read_text()) if ledger.exists() else {'format':1,'reserved_through':{'ENV':0,'POSE':0}}
        if record.get('format')!=1 or set(record['reserved_through'])!={'ENV','POSE'}:raise ValueError('Invalid boot ledger')
        observed=known_boots()
        for role in ('ENV','POSE'):
            node='BOX-0001-'+role;p=state/(node+'.json')
            with FileLock(state/(node+'.lock')):
                old=IdentityState(state,node).value if p.exists() else {'node_id':node,'boot_id':0,'sentry_revision':0}
                prior=record['reserved_through'][role]
                if type(prior)is not int or prior<0:raise ValueError('Invalid boot ledger value')
                floor=max(prior,observed[role],old['boot_id'])
                if floor>4294967295-65536:raise ValueError('Boot space exhausted')
                record['reserved_through'][role]=floor+65536
                atomic_json(ledger,record) # reserve before exposing the new state
                atomic_json(p,{**old,'boot_id':floor,'boot_limit':floor+65536})
    print('Distinct cloud simulator state reserved; switch back to hardware only after fresh hardware provisioning/build.')

def smoke(config,directory):
    import paho.mqtt.client as mqtt
    directory=confined(directory,ROOT/'artifacts');directory.mkdir(parents=True,exist_ok=False)
    checks=[];clients=[];messages={};subscriptions={};connected={}
    nonce='SMARTBOX_CLOUD_PROBE_'+uuid.uuid4().hex
    def pump(seconds=.4):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            for _,c in clients:c.loop(timeout=.01)
    def check(ok,label):
        checks.append({'check':label,'passed':bool(ok)})
        if not ok:raise AssertionError(label)
    try:
        b=config['broker']
        for role,account in config['accounts'].items():
            c=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='sb-probe-'+role+'-'+uuid.uuid4().hex[:8],protocol=mqtt.MQTTv311,clean_session=True)
            configure_client(c,config);c.connect_timeout=10;c.username_pw_set(account['username'],account['password'])
            messages[role]=[];subscriptions[role]=[]
            c.on_connect=lambda client,data,flags,reason,props,r=role:connected.update({r:not reason.is_failure})
            c.on_message=lambda client,data,msg,r=role:messages[r].append((msg.topic,msg.payload))
            c.on_subscribe=lambda client,data,mid,reasons,props,r=role:subscriptions[r].append((mid,[not x.is_failure for x in reasons]))
            c.connect(b['host'],b['port'],60);clients.append((role,c))
        deadline=time.monotonic()+15
        while len(connected)<5 and time.monotonic()<deadline:pump(.1)
        check(len(connected)==5 and all(connected.values()),'five independent TLS/authenticated connections')
        peers=dict(clients)
        for role in ('ENV','POSE'):
            node=config['accounts'][role]['username'];prefix='box/BOX-0001/'+node+'/'
            for suffix in UP:
                topic=prefix+suffix
                for who in ('backend','control','observer'):peers[who].subscribe(topic,1)
                pump(.15);token=(nonce+role+suffix).encode();peers[role].publish(topic,token,qos=1);pump(.3)
                for who in ('backend','control','observer'):check((topic,token) in messages[who],role+' '+suffix+' -> '+who)
            for suffix in DOWN:
                topic=prefix+suffix;peers[role].subscribe(topic,1);pump(.15)
                for who in ('backend','control'):
                    token=(nonce+role+suffix+who).encode();peers[who].publish(topic,token,1);pump(.25)
                    check((topic,token) in messages[role],who+' '+suffix+' -> '+role)
            # Both target's subscription and allowed sender are live controls.
            other='POSE' if role=='ENV' else 'ENV'
            topic='box/BOX-0001/'+config['accounts'][other]['username']+'/cmd'
            peers[other].subscribe(topic,1);pump(.15)
            token=(nonce+role+'cross').encode();peers[role].publish(topic,token,1);pump(.5)
            check((topic,token) not in messages[other],role+' cross-node publish denied at receiver')
            mid=peers[role].subscribe('box/BOX-0001/'+config['accounts'][other]['username']+'/property',1)[1]
            pump(.4);result=[v for m,v in subscriptions[role] if m==mid]
            check(bool(result) and not any(result[-1]),role+' cross-node subscribe denied')
            topic=prefix+'cmd';token=(nonce+role+'wrong-direction').encode()
            peers[role].publish(topic,token,1);pump(.4)
            check((topic,token) not in messages[role],role+' own downlink publish denied')
            mid=peers[role].subscribe(prefix+'property',1)[1];pump(.3)
            result=[v for m,v in subscriptions[role] if m==mid]
            check(bool(result) and not any(result[-1]),role+' own uplink subscribe denied')
        for who in ('backend','control'):
            topic='box/BOX-0001/BOX-0001-ENV/property';token=(nonce+who+'wrong-direction').encode()
            peers[who].publish(topic,token,1);pump(.4)
            check((topic,token) not in messages['observer'],who+' uplink publish denied at receiver')
        mid=peers['observer'].subscribe('box/BOX-0001/BOX-0001-ENV/cmd',1)[1];pump(.3)
        result=[v for m,v in subscriptions['observer'] if m==mid]
        check(bool(result) and not any(result[-1]),'observer downlink subscribe denied')
        topic='box/BOX-0001/BOX-0001-ENV/cmd';token=(nonce+'observer').encode()
        peers['observer'].publish(topic,token,1);pump(.5)
        check((topic,token) not in messages['ENV'],'observer publish denied at receiver')
        # Bad credentials must fail authentication, not just socket establishment.
        bad=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='sb-probe-bad-'+uuid.uuid4().hex[:8],protocol=mqtt.MQTTv311)
        configure_client(bad,config);bad.username_pw_set(config['accounts']['observer']['username'],uuid.uuid4().hex)
        bad_result=[];bad.on_connect=lambda c,u,f,r,p:bad_result.append(r.is_failure)
        bad.connect(b['host'],b['port'],60);clients.append(('bad',bad));pump(1)
        check(bool(bad_result) and bad_result[0],'wrong password rejected')
    finally:
        for _,c in clients:
            try:c.disconnect();c.loop(timeout=.05)
            except OSError:pass
        (directory/'summary.json').write_text(json.dumps({'checks':checks,'all_passed':bool(checks) and all(x['passed'] for x in checks),'client_cleanup_requested':True,'test_payload':'non-business marker; run before backend/real nodes','host':config['broker']['host']},indent=2),encoding='utf-8')
    print('PASS: cloud TLS/ACL checks:',len(checks))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','reserve-state','check','smoke'])
    p.add_argument('--config',type=Path,default=CLOUD_DEFAULT);p.add_argument('--report-dir',type=Path)
    p.add_argument('--host');p.add_argument('--deployment-id')
    p.add_argument('--allow-probes',action='store_true',help='Confirm backend and real nodes are not connected; sends non-business test markers')
    a=p.parse_args()
    try:
        if a.action=='prepare':prepare(a.config,a.host,a.deployment_id);return 0
        config=load_cloud(a.config)
        if a.action=='reserve-state':reserve_state(config);return 0
        if a.action=='check':
            from broker_profiles import tls_context
            b=config['broker']
            with socket.create_connection((b['host'],b['port']),timeout=10) as raw:
                with tls_context(b['ca_file']).wrap_socket(raw,server_hostname=b['host']) as conn:print('PASS: DNS/TLS hostname and CA verified;',conn.version())
            for label,context,name in [('untrusted CA',ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT),b['host']),('wrong hostname',tls_context(b['ca_file']),'invalid.smartbox.test')]:
                try:
                    with socket.create_connection((b['host'],b['port']),timeout=10) as raw:
                        with context.wrap_socket(raw,server_hostname=name):pass
                except ssl.SSLError:print('PASS: '+label+' rejected without plaintext fallback')
                else:raise AssertionError(label+' unexpectedly accepted')
        else:
            if not a.allow_probes or not a.report_dir:raise ValueError('Smoke requires --allow-probes and new --report-dir; stop project cloud clients first')
            smoke(config,a.report_dir)
        return 0
    except (ValueError,OSError,KeyError,AssertionError) as error:
        category='TLS' if isinstance(error,ssl.SSLError) else 'DNS' if isinstance(error,socket.gaierror) else type(error).__name__
        print('Cloud check failed:',category,'(credentials suppressed)');return 1
if __name__=='__main__':raise SystemExit(main())
