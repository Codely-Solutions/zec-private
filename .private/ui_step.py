import json,sys,time
from pathlib import Path
active=Path(r"C:\Users\User\.codex\workflow-guards\state\active.json")
d=json.loads(active.read_text());assert d['owner']=='zec-private-tor-retry'
r=Path(d['root']);state=r/'ui-state.json';previous=json.loads(state.read_text());cmd=json.loads(sys.stdin.read());cmd['sequence']=(previous.get('sequence') or 0)+1
p=r/'ui-command.next';p.write_text(json.dumps(cmd));p.replace(r/'ui-command.json')
if cmd['action']=='finish':
 print('Finish requested; wait for guard cleanup.');raise SystemExit(0)
needs_snapshot=cmd['action']!='snapshot'
deadline=time.monotonic()+45
while time.monotonic()<deadline:
 if not active.exists():raise RuntimeError('UI job ended')
 try: current=json.loads(state.read_text())
 except (FileNotFoundError,json.JSONDecodeError):time.sleep(.2);continue
 if current.get('sequence')==cmd['sequence']:
  if needs_snapshot:
   cmd={'sequence':cmd['sequence']+1,'action':'snapshot'};needs_snapshot=False
   p=r/'ui-command.next';p.write_text(json.dumps(cmd));p.replace(r/'ui-command.json');deadline=time.monotonic()+45
   continue
  print('sequence',current['sequence'],'error',current.get('error'),'hierarchyUnavailable',current.get('hierarchyUnavailable',False))
  for n in current.get('nodes',[]):print(json.dumps({k:n[k] for k in ['text','id','bounds','clickable','enabled']}))
  break
 time.sleep(.2)
else:raise RuntimeError('UI action response deadline exceeded')
