"""Offline v1 validation. Never fetch schema references over the network."""
from __future__ import annotations
import json
import math
from pathlib import Path
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from check_contracts import semantics, MQTT

ROOT = Path(__file__).resolve().parents[2]

class Rejected(ValueError):
    """Contains only a public error category, never payload or credentials."""
    def __init__(self, code='invalid_params'):
        super().__init__(code)
        self.code = code

def pack(value):
    try:
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
    except (ValueError, UnicodeError, TypeError):
        raise Rejected() from None

def unpack(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Rejected()
            result[key] = value
        return result
    def number(text):
        value = float(text)
        if not math.isfinite(value): raise Rejected()
        return value
    def constant(_): raise Rejected()
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                           parse_constant=constant, parse_float=number)
        if not isinstance(value, dict): raise Rejected()
        return value
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected() from None

class Protocol:
    def __init__(self):
        resources = []
        for path in (ROOT / 'contracts').rglob('*.schema.json'):
            doc = json.loads(path.read_text(encoding='utf-8'))
            resources.append((doc['$id'], Resource.from_contents(doc)))
        registry = Registry().with_resources(resources)
        common = json.loads((ROOT / 'contracts/common/v1.schema.json').read_text(encoding='utf-8'))
        self.validators = {suffix: Draft202012Validator(
            {'$ref': common['$id'] + '#/$defs/' + name}, registry=registry)
            for suffix, name in MQTT.items()}

    def validate(self, topic, raw, qos=1, retained=False, identity=None, caps=None,
                 now=None, synced=None):
        if not isinstance(raw, bytes) or len(raw) > 1536: raise Rejected()
        data = unpack(raw)
        suffix = topic.rsplit('/', 1)[-1]
        if suffix not in self.validators: raise Rejected()
        if suffix == 'cmd' and data.get('cmd') not in {'sentry','profile','gps_mode','output','calibrate'}:
            raise Rejected('unsupported_command')
        try:
            if not self.validators[suffix].is_valid(data): raise Rejected()
            context = {'topic': topic, 'qos': qos, 'retain': retained}
            if identity: context.update(identity)
            if caps is not None: context['caps'] = caps
            if now is not None: context['now'] = now
            if synced is not None: context['time_synced'] = synced
            issues = semantics(data, MQTT[suffix], 'mqtt', context, raw)
        except (KeyError, TypeError, RecursionError, UnicodeError):
            raise Rejected() from None
        if issues:
            mapping = {'retained':'retained_command', 'expired':'expired',
                       'caps':'unsupported_caps', 'time_unsynced':'time_unsynced'}
            raise Rejected(mapping.get(issues[0].code, 'invalid_params'))
        return data
