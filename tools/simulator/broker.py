"""Start/stop only a broker process created by this object."""
from __future__ import annotations
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from .config import CREATE_FLAGS
from .storage import FileLock, atomic_json

def port_open(port=1883):
    with socket.socket() as sock:
        sock.settimeout(.2)
        return sock.connect_ex(('127.0.0.1',port))==0

class Broker:
    def __init__(self,config,run_dir):
        self.config=config;self.run_dir=Path(run_dir);self.process=None;self.log=None;self.lock=None
    def start(self):
        if port_open():raise ValueError('Port 1883 already in use; no process stopped')
        b=self.config['broker']
        version=subprocess.run([b['exe'],'-h'],capture_output=True,timeout=5,creationflags=CREATE_FLAGS)
        if b'mosquitto version 2.0.14' not in version.stdout:raise ValueError('Broker version needs frame-limit compatibility review')
        self.run_dir.mkdir(parents=True,exist_ok=True)
        self.lock=FileLock(Path(b['config']).with_suffix('.run.lock'));self.lock.__enter__()
        try:
            self.log=(self.run_dir/'broker.log').open('ab')
            working=Path(b['password_file']).parent if b.get('relative_files') else None
            config_arg=os.path.relpath(b['config'],working) if working else b['config']
            self.process=subprocess.Popen([b['exe'],'-c',config_arg],cwd=working,stdout=self.log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS)
            deadline=time.monotonic()+8
            while not port_open():
                if self.process.poll() is not None:raise ValueError('Own broker exited; inspect private log')
                if time.monotonic()>deadline:raise ValueError('Own broker startup timed out')
                time.sleep(.05)
            atomic_json(self.run_dir/'broker-process.json',{'pid':self.process.pid,'exe':b['exe'],'config':b['config']})
            return self
        except BaseException:
            self.stop();raise
    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
                try:self.process.wait(timeout=8)
                except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)
            self.process=None
        if self.log:self.log.close();self.log=None
        if self.lock:self.lock.__exit__(None,None,None);self.lock=None
    def __enter__(self):return self.start()
    def __exit__(self,*_):self.stop()
