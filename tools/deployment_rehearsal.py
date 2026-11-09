"""Explicit cold-start rehearsal in a NEW artifact tree and owned MySQL on 13306.
Never uses or stops the installed shared MySQL service. Evidence is private.
"""
import argparse,json,os,secrets,shutil,subprocess,sys,time
from pathlib import Path
from development_config import initialize,provision
from project import ROOT

def rehearse(directory,resume=False,scope='all'):
    from simulator.broker import port_open
    if any(port_open(p) for p in (13306,1883,8080,5173)):raise ValueError('Rehearsal ports occupied')
    directory=directory.resolve();directory.relative_to((ROOT/'artifacts').resolve())
    cold=directory/'新 开发者/SmartBox';previous=None
    if directory.exists():
        if not resume:raise ValueError('Fresh rehearsal directory required')
        previous=json.loads((directory/'summary.json').read_text(encoding='utf-8'))
        if previous.get('cold_root')!=str(cold) or previous.get('mysql_port')!=13306 or previous.get('shared_service_modified') is not False or not previous.get('ports_released'):
            raise ValueError('Unknown or unclean rehearsal; cannot resume')
        from simulator.config import confined
        confined(cold,directory);confined(directory/'mysql-data',directory)
        # Bind resume to the exact private server established by the first run.
        import configparser
        instance=configparser.ConfigParser();instance.read(directory/'mysql-data/auto.cnf')
        owned=json.loads((cold/'secrets/database-provision.local.json').read_text(encoding='utf-8'))
        if instance['auto']['server-uuid']!=owned['server_uuid']:raise ValueError('Rehearsal server ownership mismatch')
    else:
        if resume or scope!='all':raise ValueError('A fresh run requires full scope')
        directory.mkdir(parents=True);cold.mkdir(parents=True)
    tracked=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
    # Include reviewed new deployment files too, so the rehearsal can precede commit.
    extra=subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    for name in sorted(set(tracked+extra)):
        if not name:continue
        if name.startswith(('secrets/','artifacts/')) or name=='firmware/include/sb_local_config.h':continue
        src=ROOT/name;dst=cold/name
        if src.is_symlink():raise ValueError('Linked source rejected')
        dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
    initialize(cold,13306)
    exe=Path(shutil.which('mysqld') or '')
    if not exe.is_file():raise ValueError('Installed MySQL 8 mysqld required')
    data=directory/'mysql-data';password=secrets.token_urlsafe(32)
    flags=getattr(subprocess,'CREATE_NO_WINDOW',0)
    if not resume:
        with (directory/'mysql-initialize.log').open('wb') as output:
            subprocess.run([str(exe),'--no-defaults','--initialize-insecure','--datadir='+str(data)],stdout=output,stderr=subprocess.STDOUT,check=True,creationflags=flags)
    init=directory/'mysql-first-start.local.sql';init.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+password+"';\n")
    log=(directory/'mysql.log').open('wb');process=None
    summary={'mysql_port':13306,'shared_service_modified':False,'cold_root':str(cold),'checks':[]}
    if previous:
        (directory/('summary-before-resume-'+str(time.time_ns())+'.json')).write_text(json.dumps(previous,indent=2),encoding='utf-8')
        summary['previous_checks']=list(dict.fromkeys(previous.get('previous_checks',[])+previous['checks']));summary['resumed_scope']=scope
    def run(name,argv,cwd=cold):
        with (directory/(name+'.log')).open('wb') as output:
            result=subprocess.run(argv,cwd=cwd,stdout=output,stderr=subprocess.STDOUT,creationflags=flags)
        if result.returncode:raise ValueError(name+' failed; inspect private log')
        summary['checks'].append(name);print('PASS: '+name,flush=True)
    try:
        process=subprocess.Popen([str(exe),'--no-defaults','--datadir='+str(data),'--port=13306','--bind-address=127.0.0.1','--mysqlx=OFF','--init-file='+str(init)],stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
        deadline=time.monotonic()+60
        while not port_open(13306):
            if process.poll() is not None or time.monotonic()>deadline:raise ValueError('Owned MySQL startup failed')
            time.sleep(.2)
        # Listening can precede init-file execution. Keep the file until a real
        # authenticated query succeeds; deleting it on port-open races startup.
        client=shutil.which('mysql');auth=os.environ.copy();auth['MYSQL_PWD']=password
        while True:
            ready=subprocess.run([client,'--protocol=TCP','--host=127.0.0.1','--port=13306','--user=root','--batch','--skip-column-names'],input=b'SELECT 1;',env=auth,capture_output=True,timeout=5,creationflags=flags)
            if ready.returncode==0 and ready.stdout.strip()==b'1':break
            if time.monotonic()>deadline:raise ValueError('Owned MySQL authentication readiness failed')
            time.sleep(.2)
        init.unlink()
        provision(cold/'secrets/backend.local.json',password,cold)
        provision(cold/'secrets/backend.local.json',password,cold)
        summary['checks'].append('repeat-private-instance-provision' if resume else 'fresh-and-repeat-database-provision')
        # Existing host workaround is enabled only for this rehearsal, not issued universally.
        config=cold/'secrets/backend.local.json';value=json.loads(config.read_text(encoding='utf-8'))
        if os.name=='nt':
            for env in value.values():env['SMARTBOX_WINDOWS_TCP_SELECTOR']='true'
            config.write_text(json.dumps(value))
        py=sys.executable
        if scope=='all':
            run('backend-build',[py,'tools/backend.py','build'])
            run('bootstrap-first',[py,'tools/backend.py','bootstrap'])
        run('bootstrap-repeat',[py,'tools/backend.py','bootstrap'])
        if scope=='all':
            run('backend-integration',[py,'tools/backend.py','test','--profile','test','--allow-test-reset'])
            run('npm-ci',[shutil.which('npm.cmd' if os.name=='nt' else 'npm'),'ci','--no-audit','--no-fund'],cold/'frontend')
            run('frontend-build',[py,'tools/frontend.py','build'])
        # Reuse the existing matching browser cache only, never a personal browser profile.
        cache=ROOT/'artifacts/task006/browsers'
        if scope=='all':
            if cache.is_dir():shutil.copytree(cache,cold/'artifacts/task006/browsers',dirs_exist_ok=True)
            run('real-browser',[py,'tools/frontend.py','integration','--profile','test','--allow-test-reset','--run-dir',str(cold/'artifacts'/('cold-browser-'+str(time.time_ns()))),'--scenario','authorization'])
        run('development-supervisor',[py,'tools/deployment_smoke.py'])
        summary['passed']=True
    finally:
        if init.exists():init.unlink()
        if process and port_open(13306):
            # Windows mysqld may spawn a child. Ask this authenticated private
            # instance to shut down rather than terminating only its launcher.
            admin=shutil.which('mysqladmin')
            if admin:
                child=os.environ.copy();child['MYSQL_PWD']=password
                subprocess.run([admin,'--protocol=TCP','--host=127.0.0.1','--port=13306','--user=root','shutdown'],env=child,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30,creationflags=flags)
            deadline=time.monotonic()+20
            while port_open(13306) and time.monotonic()<deadline:time.sleep(.2)
        if process and process.poll() is None:
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                from frontend import stop_owned
                stop_owned(process)
        log.close();summary['ports_released']=not any(port_open(p) for p in (13306,1883,8080,5173))
        (directory/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    if not summary['ports_released']:raise ValueError('Rehearsal cleanup incomplete')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--resume',action='store_true');p.add_argument('--scope',choices=['all','supervisor'],default='all');a=p.parse_args()
    try:rehearse(a.directory,a.resume,a.scope)
    except Exception as e:print('Rehearsal failed: '+type(e).__name__+'; inspect private evidence.');raise SystemExit(1)
