"""TASK-006 frontend commands. Local secrets never become frontend environment variables."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / 'frontend'
CREATE_FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

def node_command(kind):
    node = shutil.which('node')
    if not node: raise ValueError('Node required')
    relative = {'dev':'vite/bin/vite.js','unit':'vitest/vitest.mjs','browser':'@playwright/test/cli.js'}[kind]
    entry = FRONTEND/'node_modules'/relative
    if not entry.is_file(): raise ValueError('Install locked frontend dependencies first')
    return [node, str(entry)]

def frontend_environment():
    env = os.environ.copy()
    # Public VITE variables must not accidentally inherit local secrets from a prior shell.
    for key in list(env):
        if key.startswith(('SMARTBOX_', 'VITE_')) or key in ('MYSQL_PWD','SPRING_DATASOURCE_PASSWORD'):
            env.pop(key)
    env['PLAYWRIGHT_BROWSERS_PATH'] = str(ROOT/'artifacts/task006/browsers')
    return env

def stop_owned(process):
    if process is None or process.poll() is not None: return
    if os.name == 'nt':
        subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=CREATE_FLAGS, timeout=15)
    else: process.terminate()
    try: process.wait(10)
    except subprocess.TimeoutExpired: process.kill(); process.wait(5)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install','dev','check','build','browser','integration'])
    parser.add_argument('--config', type=Path, default=ROOT/'secrets/backend.local.json')
    parser.add_argument('--profile', choices=['test'])
    parser.add_argument('--allow-test-reset', action='store_true')
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--scenario', choices=['all','authorization'], default='all')
    args = parser.parse_args()
    try:
        if args.action == 'integration':
            if args.profile != 'test' or not args.allow_test_reset or not args.run_dir: raise ValueError('Explicit test profile, reset consent and new evidence directory required')
            from frontend_integration import integrate
            return integrate(args.config, args.run_dir, args.scenario)
        env = frontend_environment()
        if args.action == 'dev':
            from simulator.broker import port_open
            if port_open(5173): raise ValueError('Port 5173 occupied; no process stopped')
            process = subprocess.Popen(node_command('dev')+['--host','127.0.0.1','--port','5173','--strictPort'], cwd=FRONTEND, env=env, creationflags=CREATE_FLAGS)
            try: return process.wait()
            finally: stop_owned(process)
        npm = shutil.which('npm.cmd' if os.name=='nt' else 'npm')
        if not npm: raise ValueError('npm required')
        if args.action == 'install':
            result = subprocess.run([npm,'ci','--no-audit','--no-fund'], cwd=FRONTEND, env=env)
            if result.returncode: return result.returncode
            return subprocess.run(node_command('browser')+['install','chromium'], cwd=FRONTEND, env=env).returncode
        if args.action == 'browser':
            from frontend_integration import isolated_browser
            return isolated_browser()
        scripts = ['typecheck','test'] if args.action=='check' else ['build']
        for script in scripts:
            result = subprocess.run([npm,'run',script], cwd=FRONTEND, env=env)
            if result.returncode: return result.returncode
        return 0
    except (ValueError,OSError) as error:
        print('Frontend command rejected: '+type(error).__name__+'; verify tools, ports and local configuration (no secrets printed).', file=sys.stderr)
        return 1

if __name__ == '__main__': raise SystemExit(main())
