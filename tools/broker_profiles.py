"""Broker connection settings shared by launchers; no cloud lifecycle operations."""
from __future__ import annotations
import json
import ipaddress
import re
import ssl
from pathlib import Path
from simulator.config import ROOT, confined, load as load_local, ROLES

CLOUD_DEFAULT = ROOT / 'secrets/mqtt.cloud.local.json'

def load_cloud(path=CLOUD_DEFAULT, root=ROOT):
    path = confined(path, root)
    if not path.is_file():
        raise ValueError('Cloud configuration missing; follow EMQX deployment guide or explicitly select local')
    value = json.loads(path.read_text(encoding='utf-8'))
    broker = value['broker']
    if value.get('format') != 1 or value.get('box_id') != 'BOX-0001' or broker.get('mode') != 'cloud':
        raise ValueError('Invalid cloud profile')
    host = broker['host']
    if not isinstance(host,str) or len(host)>253 or not re.fullmatch(r'(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?',host) or '.' not in host or '..' in host:
        raise ValueError('Cloud DNS hostname required')
    try:ipaddress.ip_address(host)
    except ValueError:pass
    else:raise ValueError('Use deployment DNS name, not an IP address')
    if broker.get('port') != 8883 or broker.get('tls') is not True:
        raise ValueError('Cloud requires TLS on port 8883')
    ca = Path(broker['ca_file'])
    if not ca.is_absolute(): ca = path.parent / ca
    ca = confined(ca, root)
    tls_context(ca)  # Reject unreadable/invalid trust anchors before starting children.
    broker['ca_file'] = str(ca)
    confined(value['state_dir'],root)
    expected={'ENV':'BOX-0001-ENV','POSE':'BOX-0001-POSE','backend':'smartbox_backend','control':'smartbox_test_control','observer':'smartbox_test_observer'}
    if set(value['accounts']) != set(expected):raise ValueError('Five cloud accounts required')
    for role, username in expected.items():
        account=value['accounts'][role]
        if account['username']!=username or not isinstance(account['password'],str) or not account['password']:
            raise ValueError('Invalid cloud account')
    return value

def tls_context(ca):
    ctx=ssl.create_default_context(cafile=str(ca))
    ctx.minimum_version=ssl.TLSVersion.TLSv1_2
    return ctx

def load_profile(profile='cloud',path=None,hardware=False):
    if profile=='cloud':return load_cloud(path or CLOUD_DEFAULT)
    if profile!='local':raise ValueError('Unknown broker profile')
    return load_local(path or ROOT/('secrets/hardware/mqtt.local.json' if hardware else 'secrets/mqtt.local.json'),allow_hardware=hardware)

def configure_client(client, config):
    if config['broker'].get('mode')=='cloud':client.tls_set_context(tls_context(config['broker']['ca_file']))

def backend_environment(config):
    b=config['broker'];a=config['accounts']['backend'];cloud=b.get('mode')=='cloud'
    return {'SMARTBOX_MQTT_ENABLED':'true','SMARTBOX_MQTT_PROFILE':'cloud' if cloud else 'local',
            'SMARTBOX_MQTT_URL':('ssl' if cloud else 'tcp')+'://'+b['host']+':'+str(b['port']),
            'SMARTBOX_MQTT_CA_FILE':b['ca_file'] if cloud else '',
            'SMARTBOX_MQTT_USER':a['username'],'SMARTBOX_MQTT_PASSWORD':a['password']}
