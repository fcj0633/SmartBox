"""Generate the CR-004 effective contract without rewriting the frozen baseline."""
from pathlib import Path
import copy
import json
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
BASE = 'https://smartbox.local/contracts/'

def fixtures():
    legacy={'lat':30,'lon':120,'valid':True,'src':'MOCK'}
    observation={'sample_seq':1,'ts':None,'ts_src':'UNSYNCED','uptime_ms':100,'time_uncertainty_ms':None}
    current={**legacy,'observation':observation}
    invalid=copy.deepcopy(current);invalid['observation']['ts']=10
    zero=copy.deepcopy(current);zero['observation']['sample_seq']=0
    sample={'value':None,'valid':False,'observed_at':None,'received_at':None,'stale':True,'src':None,'time_quality':'UNKNOWN','freshness':'UNKNOWN'}
    missing=copy.deepcopy(sample);del missing['freshness']
    cases=[('alarm-unknown','Alarm',{'assessment':'UNKNOWN','active':None,'level':None},True),
           ('alarm-unknown-false','Alarm',{'assessment':'UNKNOWN','active':False,'level':None},False),
           ('alarm-evaluated-clear','Alarm',{'assessment':'EVALUATED','active':False,'level':None},True),
           ('alarm-evaluated-null','Alarm',{'assessment':'EVALUATED','active':None,'level':None},False),
           ('legacy-location','Loc',legacy,True),('location-observation','Loc',current,True),
           ('location-unsynced-utc','Loc',invalid,False),('location-sequence-zero','Loc',zero,False),
           ('unknown-sample','SampleLoc',sample,True),('missing-freshness','SampleLoc',missing,False)]
    return [{'id':name,'definition':definition,'valid':valid,'value':value} for name,definition,value,valid in cases]

def definitions(root=ROOT):
    document = json.loads((root/'contracts/common/v1.schema.json').read_text(encoding='utf-8'))
    document['$id'] = BASE+'effective/common.schema.json'
    defs = document['$defs']
    defs['Alarm']['properties']['assessment'] = {'enum':['UNKNOWN','EVALUATED']}
    defs['Alarm']['properties']['active'] = {'type':['boolean','null']}
    defs['Alarm']['required'].append('assessment')
    defs['Alarm']['allOf'] = [
        {'if':{'properties':{'assessment':{'const':'UNKNOWN'}}},
         'then':{'properties':{'active':{'type':'null'},'level':{'type':'null'}}},
         'else':{'properties':{'active':{'type':'boolean'}}}}]
    defs['Loc']['properties']['observation'] = {
        'allOf':[{'$ref':'#/$defs/Time'}, {'type':'object','properties':{
            'sample_seq':{'type':'integer','minimum':1,'maximum':4294967295}},'required':['sample_seq']}]}
    for name in ('SampleEnv','SamplePose','SampleLoc'):
        defs[name]['properties']['freshness'] = {'enum':['FRESH','STALE','UNKNOWN']}
        defs[name]['required'].append('freshness')
    return document

def outputs(root=ROOT):
    common = definitions(root)
    spec = yaml.safe_load((root/'contracts/http/openapi.yaml').read_text(encoding='utf-8'))
    for value in spec['components']['schemas'].values():
        if '$ref' in value:
            value['$ref'] = value['$ref'].replace('../common/v1.schema.json','common.schema.json')
    files = {'common.schema.json':json.dumps(common,ensure_ascii=False,indent=2)+'\n',
             'openapi.yaml':yaml.safe_dump(spec,allow_unicode=True,sort_keys=False),
             'fixtures.json':json.dumps(fixtures(),ensure_ascii=False,indent=2)+'\n'}
    for name,definition in [('ws-client','WsClient'),('ws-server','WsServer')]:
        # Reuse original root, but point its shared reference at the effective graph.
        source = root/'contracts/http/ws'/('client.schema.json' if name=='ws-client' else 'server.schema.json')
        value = json.loads(source.read_text(encoding='utf-8'))
        value['$id'] = BASE+'effective/'+name+'.schema.json'
        value['$ref'] = 'common.schema.json'+value['$ref'].split('v1.schema.json',1)[1]
        files[name+'.schema.json'] = json.dumps(value,ensure_ascii=False,indent=2)+'\n'
    return files

def check(root=ROOT):
    errors = []
    for name,content in outputs(root).items():
        path = root/'contracts/effective'/name
        if not path.is_file() or path.read_text(encoding='utf-8') != content:
            errors.append('Effective contract drift: '+name)
    return errors

def validate(root=ROOT):
    from jsonschema import Draft202012Validator
    from openapi_spec_validator import OpenAPIV31SpecValidator
    errors=check(root)
    if errors:return errors
    document=definitions(root)
    Draft202012Validator.check_schema(document)
    cases=json.loads((root/'contracts/effective/fixtures.json').read_text(encoding='utf-8'))
    for case in cases:
        name,value,expected=case['definition'],case['value'],case['valid']
        schema={'$schema':document['$schema'],'$defs':document['$defs'],'$ref':'#/$defs/'+name}
        if Draft202012Validator(schema).is_valid(value)!=expected:errors.append('Effective fixture failed: '+name)
    path=root/'contracts/effective/openapi.yaml'
    spec=yaml.safe_load(path.read_text(encoding='utf-8'))
    for issue in OpenAPIV31SpecValidator(spec,base_uri=path.resolve().as_uri()).iter_errors():errors.append('Effective OpenAPI: '+str(issue))
    return errors

if __name__=='__main__':
    if '--write' in sys.argv:
        directory = ROOT/'contracts/effective';directory.mkdir(exist_ok=True)
        for name,content in outputs().items():(directory/name).write_text(content,encoding='utf-8',newline='\n')
    else:
        errors=validate()
        print('\n'.join(errors) if errors else 'PASS: effective contract equals frozen baseline plus CR-004 changes')
        sys.exit(bool(errors))
