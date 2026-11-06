"""Read-only, offline validation of SmartBox v1 contracts and static fixtures.

This is not a broker, application authorization layer or device simulator.
Install tools/requirements-contracts.txt in an isolated virtual environment.
"""
from __future__ import annotations

import csv
from dataclasses import dataclass
import io
import json
import math
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urljoin, urlparse

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import OpenAPIV31SpecValidator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://smartbox.invalid/contracts/'
MQTT = {'property': 'Property', 'heartbeat': 'Heartbeat', 'event': 'EventBatch',
        'command_ack': 'CommandAck', 'availability': 'Availability', 'cmd': 'Command',
        'event_ack': 'EventAck', 'box_state': 'BoxState'}
COMMANDS = {'sentry', 'profile', 'gps_mode', 'output', 'calibrate'}
ROUTES = {('post', '/auth/login'): 'login', ('get', '/me'): 'me', ('get', '/boxes'): 'boxes',
          ('get', '/boxes/{id}/latest'): 'latest', ('get', '/boxes/{id}/events'): 'events',
          ('get', '/boxes/{id}/track'): 'track', ('get', '/boxes/{id}/nodes'): 'nodes',
          ('post', '/boxes/{id}/commands'): 'command',
          ('get', '/boxes/{id}/commands/{operation_id}'): 'operation',
          ('get', '/boxes/{id}/events/export'): 'export', ('post', '/ws-ticket'): 'ticket'}
WS_TYPES = {'snapshot', 'box_event', 'alarm', 'command_status', 'node_status'}
FORBIDDEN = {'pose', 'evidence', 'evidence_json', 'raw_events', 'node_diagnostics',
             'sentry', 'sentry_operation_details'}


@dataclass(frozen=True)
class Issue:
    code: str
    path: str
    message: str


@dataclass
class Result:
    errors: list[str]
    stats: dict[str, int]


def pointer(parts) -> str:
    return ''.join('/' + str(x).replace('~', '~0').replace('/', '~1') for x in parts)


def strict_json(raw: bytes):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    def constant(value):
        raise ValueError('Non-JSON numeric constant: ' + value)
    def finite_number(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('Numeric value is not finite')
        return number
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=constant, parse_float=finite_number)


def walk(value, path=()):
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, path + (key,))
    elif isinstance(value, list):
        for key, child in enumerate(value):
            yield from walk(child, path + (key,))


def semantics(data: dict, definition: str, channel: str, context: dict,
              wire: bytes | None = None) -> list[Issue]:
    """Pure checks against explicit context, never real identity or permission enforcement."""
    issues = []
    def fail(code, path, message):
        issues.append(Issue(code, path, message))
    if channel == 'mqtt':
        raw = wire if wire is not None else json.dumps(data, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
        if len(raw) > 1536:
            fail('payload_bytes', '/payload', 'UTF-8 payload exceeds 1536 bytes')
        topic = context.get('topic', '')
        if len(topic.encode()) > 128:
            fail('topic_bytes', '/topic', 'Topic exceeds 128 UTF-8 bytes')
        if topic and len(topic.encode()) <= 128:
            match = re.fullmatch(r'box/([A-Za-z0-9_-]{1,48})/([A-Za-z0-9_-]{1,48})/(' + '|'.join(MQTT) + ')', topic)
            if not match:
                fail('topic', '/topic', 'Unknown Topic structure')
            else:
                box, node, suffix = match.groups()
                if node != data.get('node_id'):
                    fail('identity', '/node_id', 'Topic node differs from payload')
                if 'box_id' in data and box != data['box_id']:
                    fail('identity', '/box_id', 'Topic box differs from payload')
                if MQTT[suffix] != definition:
                    fail('topic', '/topic', 'Message schema differs from Topic suffix')
        for key in ['node_id', 'box_id']:
            if key in context and key in data and data[key] != context[key]:
                fail('identity', '/' + key, 'Payload differs from authenticated fixture identity')
        if 'caps' in data and 'caps' in context and data['caps'] != context['caps']:
            fail('caps', '/caps', 'Declared capabilities differ from registered fixture identity')
        qos = context.get('qos', 0 if definition == 'Property' else 1)
        if qos != (0 if definition == 'Property' else 1):
            fail('qos', '/qos', 'QoS differs from v1 Topic table')
        if context.get('retain', False):
            fail('retained', '/retain', 'v1 messages are not retained')
        remaining = 2 + len(topic.encode()) + (2 if qos else 0) + len(raw)
        digits, value = 1, remaining
        while value >= 128:
            digits += 1
            value //= 128
        frame_bytes = 1 + digits + remaining
        if max(frame_bytes, context.get('frame_bytes', 0)) > 2048:
            fail('frame_bytes', '/frame', 'MQTT PUBLISH frame exceeds 2048 bytes')
    for path, value in walk(data):
        if path and path[-1] in {'request_id', 'operation_id'} and isinstance(value, str) and len(value.encode()) > 64:
            fail('id_bytes', pointer(path), 'Identifier exceeds 64 UTF-8 bytes')
        if isinstance(value, dict):
            if 'first_seq' in value and 'last_seq' in value and value['first_seq'] > value['last_seq']:
                fail('range_order', pointer(path), 'Reversed lost sequence range')
            if value.get('valid') is True:
                keys = ['temp_c', 'humi_pct', 'lux'] if 'temp_c' in value else ['lat', 'lon'] if 'lat' in value else ['acc_g', 'tilt_deg'] if 'acc_g' in value else []
                if any(value.get(k) is None for k in keys):
                    fail('validity', pointer(path), 'Valid sensor group contains unknown measurement')
    if definition in ['Command', 'CommandInput']:
        params, cmd = data['params'], data['cmd']
        if definition == 'Command':
            if data['expires_at'] <= data['issued_at']:
                fail('time_order', '/expires_at', 'Expiry must follow issue time')
            if context.get('now') is not None and context['now'] >= data['expires_at']:
                fail('expired', '/expires_at', 'Command expired at fixture reception time')
            if context.get('time_synced') is False:
                fail('time_unsynced', '/ts_src', 'Unsynced nodes must not execute remote commands')
            caps = context.get('caps')
            if caps is not None and (data['target_caps'] & caps) != data['target_caps']:
                fail('caps', '/target_caps', 'Not all requested capabilities are available')
            if cmd == 'output' and data['target_caps'] != {'light': 2, 'fan': 4, 'indicator': 16}[params['kind']]:
                fail('caps_kind', '/target_caps', 'Output kind requires its exact capability bit')
        if cmd == 'profile':
            for prefix, unit in [('temp', 'c'), ('humi', 'pct')]:
                lo, hi, gap = (params[f'{prefix}_min_{unit}'], params[f'{prefix}_max_{unit}'], params[f'{prefix}_hysteresis_{unit}'])
                if lo >= hi or lo + gap > hi - gap:
                    fail('profile_range', '/params', 'Thresholds do not form a valid recovery interval')
        role = context.get('role')
        if role is not None and not (role == 'LOGISTICS_OPERATOR' or (role == 'COURIER' and cmd == 'sentry')):
            fail('control_permission', '/cmd', 'Fixture role cannot issue this command')
    if definition == 'CommandAck':
        for name in ['request_id', 'operation_id']:
            if name in context and data[name] != context[name]:
                fail('request_match', '/' + name, 'Acknowledgement differs from original command')
    if definition == 'EventAck' and 'stored_event_seqs' in context:
        if set(data['event_seqs']) != set(context['stored_event_seqs']):
            fail('ack_set', '/event_seqs', 'Confirmation is not the exact committed set')
    if definition == 'EventBatch':
        seqs = [x['event_seq'] for x in data['events']]
        if len(seqs) != len(set(seqs)):
            fail('event_identity', '/events', 'Event identities repeat within batch')
        if 'caps' in context:
            for i, event in enumerate(data['events']):
                kind = event['type']
                needed = 1 if kind.startswith('ENV_') else 8 if kind in ['POSE_CHANGED', 'IMPACT'] else 32 if kind.startswith('GEOFENCE_') else 64 if kind == 'SENTRY_INTRUSION' else 0
                if kind == 'DOOR_EVIDENCE':
                    needed = 1 if event['src'] == 'LIGHT' else 8 if event['src'] == 'TILT' else 0
                if context['caps'] & needed != needed:
                    fail('event_caps', f'/events/{i}/type', 'Event is unsupported by registered node capabilities')
    if definition == 'Query':
        if 'from' in data and 'to' in data and not 0 < data['to'] - data['from'] <= 31 * 86400000:
            fail('query_range', '/to', 'Query interval must be positive and at most 31 days')
    if 'idempotency_key' in context and not 1 <= len(context['idempotency_key'].encode()) <= 64:
        fail('id_bytes', '/headers/Idempotency-Key', 'Idempotency key must be 1..64 UTF-8 bytes')
    if context.get('side') == 'response':
        for path, value in walk(data):
            if path and path[-1] in {'password', 'password_hash', 'device_secret', 'device_password', 'wifi_password'}:
                fail('secret', pointer(path), 'Response contains a forbidden credential field')
    if context.get('role') == 'DELIVERY_USER' and context.get('side', 'response') == 'response':
        if definition == 'WsPush' and data['type'] in ['node_status', 'command_status']:
            fail('projection', '/type', 'Delivery role cannot receive diagnostic/control pushes')
        for path, value in walk(data):
            if path and path[-1] in FORBIDDEN:
                fail('projection', pointer(path), 'Restricted field in delivery projection')
            if isinstance(value, dict) and 'type' in value and all(k in value for k in ['observed_at', 'received_at', 'historical']):
                if value['type'] not in ['DOOR_CONCLUSION', 'ALARM']:
                    fail('projection', pointer(path + ('type',)), 'Original node events cannot enter delivery feed')
    if 'box_ids' in context:
        allowed = set(context['box_ids'])
        for path, value in walk(data):
            if path and path[-1] == 'box_id' and value not in allowed:
                fail('box_scope', pointer(path), 'Fixture contains an unauthorized box')
        if definition == 'WsSubscribe':
            for i, box in enumerate(data['box_ids']):
                if box not in allowed:
                    fail('box_scope', '/box_ids/' + str(i), 'Entire subscription must be rejected')
    return issues


def read_csv(raw: bytes, context: dict) -> list[Issue]:
    rows = list(csv.DictReader(io.StringIO(raw.decode('utf-8'))))
    if not rows or not {'id', 'box_id', 'type'} <= set(rows[0]):
        return [Issue('csv', '', 'Missing CSV header or rows')]
    if context.get('role') != 'LOGISTICS_OPERATOR':
        return [Issue('export_permission', '', 'Only logistics operators export events')]
    issues = []
    for i, row in enumerate(rows):
        if 'box_ids' in context and row['box_id'] not in context['box_ids']:
            issues.append(Issue('box_scope', f'/{i}/box_id', 'Unauthorized CSV box'))
        for key, value in row.items():
            if value is None or not isinstance(value, str):
                issues.append(Issue('csv', f'/{i}/{key}', 'Malformed CSV row'))
            elif value.lstrip().startswith(('=', '+', '-', '@')):
                issues.append(Issue('csv_formula', f'/{i}/{key}', 'Unescaped CSV formula'))
    return issues


def validate(root: Path = ROOT) -> Result:
    root = root.resolve()
    errors, stats = [], {'schema_files': 0, 'definitions': 0, 'fixtures': 0, 'positive': 0, 'negative': 0, 'http_operations': 0}
    try:
        common = strict_json((root / 'contracts/common/v1.schema.json').read_bytes())
        manifest = strict_json((root / 'contracts/coverage.json').read_bytes())
        spec_path = root / 'contracts/http/openapi.yaml'
        spec = yaml.safe_load(spec_path.read_text(encoding='utf-8'))
        files = list((root / 'contracts').rglob('*.schema.json'))
        resources, schemas = [], {}
        for path in files:
            schema = strict_json(path.read_bytes())
            Draft202012Validator.check_schema(schema)
            schemas[path] = schema
            resource = Resource.from_contents(schema)
            resources.extend([(schema['$id'], resource), (path.as_uri(), resource)])
        registry = Registry().with_resources(resources)
        stats['schema_files'], stats['definitions'] = len(files), len(common['$defs'])
        # Fail before invoking OpenAPI libraries if any reference might require remote retrieval.
        for path, schema in [(p, s) for p, s in schemas.items()] + [(spec_path, spec)]:
            base = schema.get('$id', path.as_uri())
            for loc, value in walk(schema):
                if isinstance(value, dict) and '$ref' in value:
                    uri = urljoin(base, value['$ref'])
                    if path == spec_path and uri.startswith(spec_path.as_uri() + '#'):
                        target = spec
                        for key in uri.partition('#')[2].lstrip('/').split('/'):
                            target = target[key.replace('~1', '/').replace('~0', '~')]
                    elif path == spec_path:
                        parsed = urlparse(uri)
                        target_path = Path(unquote(parsed.path.lstrip('/'))) if sys.platform == 'win32' else Path(unquote(parsed.path))
                        if parsed.scheme != 'file' or not target_path.resolve().is_relative_to(root / 'contracts'):
                            raise ValueError('Non-local OpenAPI reference: ' + uri)
                        registry.resolver(base_uri=path.as_uri()).lookup(value['$ref'])
                    else:
                        registry.resolver(base_uri=base).lookup(value['$ref'])
        for issue in OpenAPIV31SpecValidator(spec, base_uri=spec_path.as_uri()).iter_errors():
            errors.append('OpenAPI: ' + str(issue))
        routes = {(m, p): o.get('operationId') for p, item in spec['paths'].items() for m, o in item.items() if m in ['get', 'post', 'put', 'patch', 'delete']}
        if routes != ROUTES:
            errors.append('HTTP route inventory differs from v1 baseline')
        if len(set(routes.values())) != len(routes):
            errors.append('Duplicate operationId')
        stats['http_operations'] = len(routes)
        listed = {(x['method'], x['path']): x['operation_id'] for x in manifest['http_operations']}
        if listed != ROUTES or len(manifest['http_operations']) != len(ROUTES):
            errors.append('Coverage HTTP inventory missing or duplicated')
        if manifest['mqtt'] != MQTT or set(manifest['commands']) != COMMANDS or set(manifest['ws_types']) != WS_TYPES:
            errors.append('Coverage message/command inventory mismatch')
        source_keys = {'mqtt:' + x for x in MQTT} | {'command:' + x for x in COMMANDS} | {'http:' + x for x in ROUTES.values()} | {'ws:' + x for x in WS_TYPES | {'subscribe', 'unsubscribe', 'handshake', 'ping_pong', 'error'}}
        if set(manifest.get('sources', {})) != source_keys:
            errors.append('Interface source inventory incomplete')
        for key, source in manifest.get('sources', {}).items():
            if not all(source.get(x) for x in ['source', 'definition', 'requirements', 'consumers']):
                errors.append('Interface source mapping incomplete: ' + key)
        if spec.get('security') != [{'bearerAuth': []}]:
            errors.append('REST default authentication missing')
        for entry in manifest['http_operations']:
            op = spec['paths'].get(entry['path'], {}).get(entry['method'], {})
            expected_status = '202' if entry['operation_id'] == 'command' else '200'
            if entry['operation_id'] == 'login':
                if op.get('security') != []:
                    errors.append('Login authentication definition differs')
            elif 'security' in op and op['security'] != [{'bearerAuth': []}]:
                errors.append('Operation bypasses authentication: ' + entry['operation_id'])
            if entry['request']:
                actual = op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {}).get('$ref')
                if actual != '#/components/schemas/' + entry['request']:
                    errors.append('HTTP request schema mapping mismatch: ' + entry['operation_id'])
            if entry['response']:
                actual = op.get('responses', {}).get(expected_status, {}).get('content', {}).get('application/json', {}).get('schema', {}).get('$ref')
                if actual != '#/components/schemas/' + entry['response']:
                    errors.append('HTTP response schema mapping mismatch: ' + entry['operation_id'])
                component = spec['components']['schemas'].get(entry['response'], {})
                if component != {'$ref': '../common/v1.schema.json#/$defs/' + entry['response']}:
                    errors.append('HTTP shared definition drift: ' + entry['operation_id'])
            elif 'text/csv' not in op.get('responses', {}).get('200', {}).get('content', {}):
                errors.append('CSV response definition missing')
        for name, definition in MQTT.items():
            schema = schemas.get(root / f'contracts/mqtt/schemas/{name}.schema.json', {})
            if schema.get('$ref') != '../../common/v1.schema.json#/$defs/' + definition:
                errors.append('Missing or wrong MQTT schema: ' + name)
        for name, definition in [('client', 'WsSubscribe'), ('server', 'WsPush'), ('error', 'WsError')]:
            schema = schemas.get(root / f'contracts/http/ws/{name}.schema.json', {})
            if schema.get('$ref') != '../../common/v1.schema.json#/$defs/' + definition:
                errors.append('Missing or wrong WS schema: ' + name)
        # Check coverage against original text too, so a documentation/API change is visible.
        http_text = (root / 'contracts/http/v1.md').read_text(encoding='utf-8')
        text_routes = {(m.lower(), p) for m, p in re.findall(r'^\| (GET|POST) \| ([^ ]+) \|', http_text, re.M)}
        if text_routes != set(ROUTES):
            errors.append('Text HTTP route inventory differs')
        mqtt_text = (root / 'contracts/mqtt/v1.md').read_text(encoding='utf-8')
        if set(re.findall(r'^\| [上下]行 \| (\w+) \|', mqtt_text, re.M)) != set(MQTT):
            errors.append('Text MQTT Topic inventory differs')
        ids, used, positive_defs, positive_commands, negative_commands, positive_http, positive_ws = set(), set(), set(), set(), set(), set(), set()
        common_validator = Draft202012Validator({'$ref': BASE + 'common/v1.schema.json'}, registry=registry)
        for case in manifest['fixtures']:
            stats['fixtures'] += 1
            name, definition, channel = case['id'], case['definition'], case['channel']
            if name in ids:
                errors.append('Duplicate fixture id: ' + name)
            ids.add(name)
            path = (root / case['file']).resolve()
            if not path.is_relative_to(root / 'contracts'):
                errors.append('Fixture outside contracts: ' + name)
                continue
            used.add(path)
            raw, expected, context = path.read_bytes(), case['expected'], case['context']
            issues, data = [], None
            if channel == 'csv':
                issues = read_csv(raw, context)
            else:
                parsed = False
                try:
                    data = strict_json(raw)
                    parsed = True
                except (ValueError, UnicodeError) as exc:
                    issues = [Issue('parse', '', str(exc))]
                if parsed:
                    if definition not in common['$defs']:
                        errors.append('Unknown fixture definition: ' + name)
                        continue
                    validator = common_validator.evolve(schema={'$ref': BASE + 'common/v1.schema.json#/$defs/' + definition})
                    issues = [Issue('schema', pointer(e.absolute_path), e.message) for e in validator.iter_errors(data)]
                    if not issues and context.get('role') == 'DELIVERY_USER' and context.get('side', 'response') == 'response':
                        projected_schema, projected_data, prefix = None, None, ()
                        if definition == 'LatestResponse':
                            projected_schema, projected_data, prefix = 'DeliverySnapshot', data.get('data'), ('data',)
                        elif definition == 'EventsResponse':
                            projected_schema, projected_data, prefix = 'DeliveryEventViewPage', data.get('data'), ('data',)
                        elif definition == 'WsPush' and data.get('type') in ['snapshot', 'box_event']:
                            projected_schema = 'DeliverySnapshot' if data['type'] == 'snapshot' else 'DeliveryEventView'
                            projected_data, prefix = data.get('data'), ('data',)
                        if projected_schema and not issues:
                            projected = common_validator.evolve(schema={'$ref': BASE + 'common/v1.schema.json#/$defs/' + projected_schema})
                            issues.extend(Issue('projection', pointer(prefix + tuple(e.absolute_path)), e.message) for e in projected.iter_errors(projected_data))
                    if not issues:
                        issues = semantics(data, definition, channel, context, raw if channel == 'mqtt' else None)
            if expected:
                stats['negative'] += 1
                if not any(e.code == expected['code'] and e.path == expected['path'] for e in issues):
                    errors.append(f'{name}: expected {expected}, got {issues}')
                if data and definition == 'Command':
                    negative_commands.add(data.get('cmd'))
            else:
                stats['positive'] += 1
                if issues:
                    errors.append(f'{name}: unexpected {issues}')
                positive_defs.add(definition)
                if data and definition == 'Command':
                    positive_commands.add(data['cmd'])
                if context.get('side') == 'response' and 'operation' in context:
                    positive_http.add(context['operation'])
                if definition == 'WsPush' and data:
                    positive_ws.add(data['type'])
        if not set(MQTT.values()) <= positive_defs:
            errors.append('MQTT message positive fixture coverage incomplete')
        if not COMMANDS <= positive_commands or not COMMANDS <= negative_commands:
            errors.append('Command positive/negative coverage incomplete')
        if positive_http != set(ROUTES.values()):
            errors.append('HTTP response fixture coverage incomplete')
        if positive_ws != WS_TYPES:
            errors.append('WS push fixture coverage incomplete')
        fixture_files = {p.resolve() for folder in ['mqtt/fixtures', 'http/fixtures'] for p in (root / 'contracts' / folder).rglob('*') if p.is_file()}
        if fixture_files != used:
            errors.append('Unlisted or missing fixture files')
        # Existing P0 examples remain independent input; do not silently replace them.
        examples = strict_json((root / 'contracts/mqtt/examples.json').read_bytes())
        for name, data in examples.items():
            definition = MQTT['property' if name == 'unsynced_property' else name]
            validator = common_validator.evolve(schema={'$ref': BASE + 'common/v1.schema.json#/$defs/' + definition})
            for e in validator.iter_errors(data):
                errors.append(f'Original example {name}{pointer(e.absolute_path)}: {e.message}')
    except Exception as exc:
        errors.append(f'{type(exc).__name__}: {exc}')
    return Result(errors, stats)


def main() -> int:
    result = validate()
    for error in result.errors:
        print('FAIL:', error)
    print('FAIL' if result.errors else 'PASS', json.dumps(result.stats, ensure_ascii=False))
    print('Scope: offline schema/fixture/static checks only; no real authorization, services or hardware validation.')
    return int(bool(result.errors))


if __name__ == '__main__':
    raise SystemExit(main())
