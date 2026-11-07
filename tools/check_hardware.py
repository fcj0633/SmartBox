"""Run real firmware cores against fault-injecting host ports on the existing Ubuntu GCC."""
from __future__ import annotations
import argparse
import base64
import json
from pathlib import Path
from firmware_support import connection_arguments,remote_python
ROOT=Path(__file__).resolve().parents[1]
REMOTE=r'''
import base64,json,pathlib,subprocess,tempfile
with tempfile.TemporaryDirectory(prefix='smartbox-hardware-') as td:
    root=pathlib.Path(td)
    for name,data in payload['files'].items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(data))
    for role in ['ENV','POSE']:
        target=root/role
        command=['gcc','-std=c99','-Wall','-Wextra','-Werror','-DSB_ROLE_'+role+'=1','-I'+str(root/'firmware/include'),'-I'+str(root/'sdk-json')]
        subprocess.run(command+[str(root/name) for name in payload['sources']]+['-lm','-o',str(target)],check=True)
        result=subprocess.run([str(target)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=15)
        print(result.stdout.decode(),end='',flush=True)
        if result.returncode:raise RuntimeError('Hardware component check failed '+role)
        port_target=root/(role+'-sdk-ports')
        port_command=command+['-I'+str(root/'sdk-ports'),str(root/'firmware/drivers/sb_hardware_sdk.c'),str(root/'firmware/services/sb_config.c'),str(root/'tests/firmware/hardware/test_sdk_ports.c'),'-o',str(port_target)]
        subprocess.run(port_command,check=True)
        for mode in ['0','1','2']:subprocess.run([str(port_target),mode],check=True)
        boot_target=root/(role+'-boot-sdk')
        subprocess.run(command+['-I'+str(root/'tests/firmware/hardware/boot_sdk_stubs'),str(root/'firmware/drivers/sb_boot_sdk.c'),str(root/'firmware/services/sb_boot.c'),str(root/'firmware/services/sb_config.c'),str(root/'tests/firmware/hardware/test_boot_sdk.c'),'-o',str(boot_target)],check=True)
        subprocess.run([str(boot_target)],check=True)
        wifi_target=root/(role+'-wifi-sdk')
        subprocess.run(command+['-I'+str(root/'sdk-ports'),str(root/'firmware/drivers/sb_wifi_sdk.c'),str(root/'tests/firmware/hardware/test_wifi_sdk.c'),'-o',str(wifi_target)],check=True)
        subprocess.run([str(wifi_target)],check=True)


'''
def main():
    p=argparse.ArgumentParser(description=__doc__);connection_arguments(p);p.add_argument('--sdk-root',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    sources=['firmware/'+x for x in ['drivers/sb_sensor_real.c','drivers/sb_output_real.c','services/sb_boot.c','services/sb_clock.c','services/sb_config.c','services/sb_event_buffer.c','domain/sb_pipeline.c','services/sb_wire.c','services/sb_sntp.c']]+['tests/firmware/hardware/test_hardware.c','sdk-json/cJSON.c']
    files={f.relative_to(ROOT).as_posix():base64.b64encode(f.read_bytes()).decode() for parent in ['firmware/include','tests/firmware/hardware'] for f in (ROOT/parent).rglob('*') if f.is_file() and f.name!='sb_local_config.h'}
    files.update({s:base64.b64encode((ROOT/s).read_bytes()).decode() for s in sources if not s.startswith('sdk-json/')})
    for name in ['cJSON.h','cJSON.c']:files['sdk-json/'+name]=base64.b64encode((a.sdk_root/'third_party/cJSON'/name).read_bytes()).decode()
    for name in ['wifiiot_gpio.h','wifiiot_gpio_ex.h','wifiiot_i2c.h','wifiiot_i2c_ex.h']:
        files['sdk-ports/'+name]=base64.b64encode((a.sdk_root/'base/iot_hardware/interfaces/kits/wifiiot_lite'/name).read_bytes()).decode()
    files['sdk-ports/cmsis_os2.h']=base64.b64encode((ROOT/'tests/firmware/cmsis_os2.h').read_bytes()).decode()
    files['firmware/drivers/sb_boot_sdk.c']=base64.b64encode((ROOT/'firmware/drivers/sb_boot_sdk.c').read_bytes()).decode()
    files['firmware/drivers/sb_hardware_sdk.c']=base64.b64encode((ROOT/'firmware/drivers/sb_hardware_sdk.c').read_bytes()).decode()
    files['sdk-ports/hi_wifi_api.h']=base64.b64encode((a.sdk_root/'vendor/hisi/hi3861/hi3861/include/hi_wifi_api.h').read_bytes()).decode()
    files['firmware/drivers/sb_wifi_sdk.c']=base64.b64encode((ROOT/'firmware/drivers/sb_wifi_sdk.c').read_bytes()).decode()
    code,output=remote_python(a,REMOTE,{'files':files,'sources':sources},check=False)
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(output,encoding='utf-8');print(output,end='')
    if code:return code
    from simulator.protocol import Protocol
    from jsonschema import Draft202012Validator
    from referencing import Registry,Resource
    protocol=Protocol();fixtures=0
    resources=[]
    for path in (ROOT/'contracts').rglob('*.schema.json'):
        schema=json.loads(path.read_text(encoding='utf-8'));resources.append((schema['$id'],Resource.from_contents(schema)))
    registry=Registry().with_resources(resources)
    effective=json.loads((ROOT/'contracts/effective/common.schema.json').read_text(encoding='utf-8'))
    names={'property':'Property','heartbeat':'Heartbeat','command_ack':'CommandAck'}
    for line in output.splitlines():
        if line.startswith('FIXTURE '):
            _,suffix,raw=line.split(' ',2);value=json.loads(raw)
            protocol.validate('box/BOX-0001/'+value['node_id']+'/'+suffix,raw.encode(),qos=0 if suffix=='property' else 1)
            Draft202012Validator({'$ref':effective['$id']+'#/$defs/'+names[suffix]},registry=registry).validate(value)
            fixtures+=1
    print('PASS frozen/effective schemas and static semantics for '+str(fixtures)+' emitted firmware fixtures')
    return 0
if __name__=='__main__':raise SystemExit(main())
