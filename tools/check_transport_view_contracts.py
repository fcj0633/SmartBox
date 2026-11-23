"""Read-only additive transport monitoring/guest contract validation, local references."""
from pathlib import Path
import json
import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import OpenAPIV31SpecValidator

ROOT=Path(__file__).resolve().parents[1]
EXPECTED={('get','/enterprise/riders'),('get','/enterprise/transports'),('get','/enterprise/transports/{id}'),('get','/enterprise/transports/{id}/monitoring'),('get','/rider/transports/{id}/monitoring'),('post','/rider/transports/{id}/access-code'),('post','/public/transport-access/verify'),('get','/public/transport-view')}
def validate_value(name,value,root=ROOT):
    schema=json.loads((root/'contracts/http/transport-view/responses.schema.json').read_text(encoding='utf-8'))
    if name not in schema['$defs']:raise ValueError('Unknown view definition')
    Draft202012Validator({**schema,'$ref':'#/$defs/'+name}).validate(value)

def check(root=ROOT):
    folder=root/'contracts/http/transport-view'
    api=yaml.safe_load((folder/'openapi.yaml').read_text(encoding='utf-8'))
    OpenAPIV31SpecValidator(api,base_uri=(folder/'openapi.yaml').resolve().as_uri()).validate()
    operations={(method,path) for path,methods in api['paths'].items() for method in methods}
    if operations!=EXPECTED:raise ValueError('Eight operation inventory mismatch')
    identifiers=[]
    for path,methods in api['paths'].items():
        for op in methods.values():
            identifiers.append(op['operationId'])
            expected=[] if path.endswith('/verify') else [{'transportViewToken':[]}] if path.endswith('/transport-view') else [{'bearerAuth':[]}]
            if op.get('security')!=expected:raise ValueError('Authentication boundary changed')
    if len(set(identifiers))!=8:raise ValueError('Duplicate operation ID')
    schema=json.loads((folder/'responses.schema.json').read_text(encoding='utf-8'));Draft202012Validator.check_schema(schema)
    fixtures=json.loads((folder/'fixtures.json').read_text(encoding='utf-8'))
    for case in fixtures:
        valid=Draft202012Validator({**schema,'$ref':'#/$defs/'+case['definition']}).is_valid(case['value'])
        if valid!=case['valid']:raise ValueError('Unexpected fixture result')
    return {'operations':8,'definitions':len(schema['$defs']),'fixtures':len(fixtures)}
if __name__=='__main__':print('PASS: '+json.dumps(check()))
