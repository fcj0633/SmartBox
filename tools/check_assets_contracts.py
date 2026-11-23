"""Offline TASK-011 additive contract checks; frozen eleven operations are untouched."""
from pathlib import Path
import json
import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import OpenAPIV31SpecValidator

ROOT=Path(__file__).resolve().parents[1]
EXPECTED={('get','/enterprise/boxes'),('post','/enterprise/boxes/{boxId}/assign'),('post','/enterprise/boxes/{boxId}/unassign'),('get','/rider/boxes')}
def validate_value(definition,value,root=ROOT):
    schema=json.loads((root/'contracts/http/assets/responses.schema.json').read_text(encoding='utf-8'))
    if definition not in schema['$defs']:raise ValueError('Unknown asset definition')
    Draft202012Validator({**schema,'$ref':'#/$defs/'+definition}).validate(value)
def check(root=ROOT):
    base=root/'contracts/http/assets';api=yaml.safe_load((base/'openapi.yaml').read_text(encoding='utf-8'))
    OpenAPIV31SpecValidator(api,base_uri=(base/'openapi.yaml').as_uri()).validate()
    operations={(method,path) for path,methods in api['paths'].items() for method in methods if method in ('get','post','put','delete','patch')}
    if operations!=EXPECTED:raise ValueError('Asset route coverage changed')
    ids=[op['operationId'] for methods in api['paths'].values() for op in methods.values()]
    if len(set(ids))!=4 or any(op.get('security')!=[{'bearerAuth':[]}] for methods in api['paths'].values() for op in methods.values()):raise ValueError('Asset operation identity/authentication mismatch')
    schema=json.loads((base/'responses.schema.json').read_text(encoding='utf-8'));Draft202012Validator.check_schema(schema)
    fixtures=json.loads((base/'fixtures.json').read_text(encoding='utf-8'))
    for fixture in fixtures:
        errors=list(Draft202012Validator({**schema,'$ref':'#/$defs/'+fixture['definition']}).iter_errors(fixture['value']))
        if bool(errors)==fixture['valid']:raise ValueError('Asset fixture failed: '+fixture['id'])
    return {'operations':4,'fixtures':len(fixtures),'definitions':len(schema['$defs'])}
if __name__=='__main__':print('PASS: '+json.dumps(check()))
