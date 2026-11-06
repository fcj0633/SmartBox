"""SmartBox project-only MQTT commands. Never accept passwords as arguments."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from simulator.config import ROOT, DEFAULT, load, prepare, confined
from simulator.broker import Broker, port_open

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','broker','node','unit','integration'])
    parser.add_argument('--config',type=Path)
    parser.add_argument('--run-dir',type=Path)
    parser.add_argument('--role',choices=['ENV','POSE'],default='ENV')
    parser.add_argument('--scenario',choices=['normal','unsynced','invalid'],default='normal')
    parser.add_argument('--events',type=int,default=1)
    parser.add_argument('--seed',type=int,default=1)
    parser.add_argument('--stop-file',type=Path)
    args=parser.parse_args()
    try:
        if args.action=='unit':
            return subprocess.run([sys.executable,'-m','unittest','discover','-s','tests/simulator','-p','test_*.py','-v'],cwd=ROOT).returncode
        run_dir=confined(args.run_dir or ROOT/'artifacts/mqtt/TASK-004'/str(time.time_ns()),ROOT)
        if args.action=='integration':
            from simulator.scenarios import integration
            return integration(run_dir,args.config)
        config_path=args.config or DEFAULT
        if args.action=='prepare':
            prepare(config_path);print('PASS: local MQTT configuration prepared/verified; credentials not displayed.');return 0
        config=load(config_path)
        if args.action=='broker':
            with Broker(config,run_dir) as broker:
                try:
                    while broker.process.poll() is None:time.sleep(.2)
                except KeyboardInterrupt:pass
            if port_open():raise ValueError('Own broker did not release port')
        else:
            if not 0<=args.events<=1000:raise ValueError('Event fixture count outside bounds')
            from simulator.runtime import Node
            stop=confined(args.stop_file,ROOT) if args.stop_file else None
            Node(config,args.role,run_dir,args.scenario,args.events,args.seed).run(stop)
        return 0
    except (ValueError,OSError,KeyError,AssertionError) as error:
        # No traceback/config/raw payload from operational failures enters shared output.
        print('MQTT command failed: '+type(error).__name__+'; inspect private evidence/configuration.',file=sys.stderr)
        return 1
if __name__=='__main__':sys.exit(main())
