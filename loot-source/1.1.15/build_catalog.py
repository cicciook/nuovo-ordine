import json,re,zipfile,pathlib
P=pathlib.Path(__file__).parent
base=zipfile.ZipFile('loot-input/lootr-more-tactical-loot-1.1.10.jar')
tacz=zipfile.ZipFile('loot-input/tacz-1.20.1-1.1.8-hotfix.jar')
sbw=zipfile.ZipFile('loot-input/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all(1).jar')
si=zipfile.ZipFile('loot-input/survival_instinct-1.0.2-forge-1.20.1.jar')
def parse(b):return json.loads(re.sub(r'//[^\n]*|/\*.*?\*/','',b.decode(),flags=re.S))
def e(item,w=4,lo=1,hi=1,tag=None):
 d={'type':'minecraft:item','name':item,'weight':w}
 fs=[]
 if tag:fs.append({'function':'minecraft:set_nbt','tag':tag})
 if lo!=1 or hi!=1:fs.append({'function':'minecraft:set_count','count':{'min':lo,'max':hi}})
 if fs:d['functions']=fs
 return d
excluded_t={'rpg7','minigun','m95','m107','ai_awp'}
excluded_s={'rpg','minigun','awm','m_98b','ntw_20','sentinel'}
weapons=[]; ammo_ids=set()
for n in tacz.namelist():
 if '/index/guns/' not in n or not n.endswith('.json'):continue
 name=n.split('/')[-1][:-5]
 if name in excluded_t:continue
 idx=parse(tacz.read(n)); raw=tacz.read('assets/tacz/custom/tacz_default_gun/data/tacz/data/guns/'+idx['data'].split(':')[1]+'.json').decode()
 mode=re.findall(r'"(semi|auto|burst)"',re.search(r'"fire_mode"\s*:\s*\[([^]]+)\]',raw).group(1))[0].upper()
 ammo_ids.add(re.search(r'"ammo"\s*:\s*"([^"]+)"',raw).group(1))
 weight={'pistol':8,'smg':6,'shotgun':5,'rifle':4,'mg':2,'sniper':2,'rpg':1}.get(idx['type'],2)
 weapons.append(e('tacz:modern_kinetic_gun',weight,tag='{GunId:"tacz:'+name+'",GunFireMode:"'+mode+'"}'))
registered=set(re.findall(r'// String ([a-z0-9_]+)\s*$',(P/'SBWItems.javap').read_text(),re.M))
for n in sbw.namelist():
 if not n.startswith('data/superbwarfare/sbw/guns/') or not n.endswith('.json'):continue
 name=n.split('/')[-1][:-5]
 if name in excluded_s or name=='repair_tool':continue
 assert name in registered,name
 d=json.loads(sbw.read(n)); weight={'Handgun':8,'Smg':6,'Shotgun':5,'Rifle':4,'MachineGun':2,'Sniper':2}.get(d.get('GunType'),1)
 weapons.append(e('superbwarfare:'+name,weight))
for name in ['hand_grenade','rgo_grenade','m18_smoke_grenade','claymore_mine','c4_bomb']:
 assert name in registered
 weapons.append(e('superbwarfare:'+name,1))
path='data/lootr_more_loot/tacz_loot_injectors/balanced_structure_loot.json'
data=json.loads(base.read(path))
weapon_pools=[p for p in data['pools'] if any(x['name']=='tacz:modern_kinetic_gun' for x in p['entries'])]
assert len(weapon_pools)==1
weapon_pools[0]['entries']=weapons
# All actual finished combat armor, including chestplates and the highest Exo Heavy set.
lang=json.loads(si.read('assets/survival_instinct/lang/en_us.json'))
armor={'low':[],'high':[]}
for key in lang:
 if not key.startswith('item.survival_instinct.'):continue
 name=key.split('.')[-1]
 if not name.endswith(('_helmet','_chestplate','_leggings','_boots')):continue
 if any(v in name for v in ['juggernaut','exo','military','reaper','guillie']):armor['high'].append(e('survival_instinct:'+name))
 if any(v in name for v in ['recluit','police','rockie','hunter','stop_sign','riot','swat','military']):armor['low'].append(e('survival_instinct:'+name))
for tier,chance in [('low',.24),('high',.12)]:
 data['pools'].append({'rolls':1,'conditions':[{'condition':'minecraft:random_chance','chance':chance}],'entries':armor[tier]})
# Preserve existing ammunition and add missing non-explosive rounds for the restored guns.
ammopool=next(p for p in data['pools'] if any(x['name']=='tacz:ammo' for x in p['entries']))
known={f.get('tag') for x in ammopool['entries'] for f in x.get('functions',[])}
extra=[]
for a in sorted(ammo_ids-{'tacz:40mm'}):
 tag='{AmmoId:"'+a+'"}'
 if tag not in known:extra.append(e('tacz:ammo',3,20,32,tag))
for a in ['sniper_ammo','heavy_ammo','taser_electrode']:
 assert a in registered;extra.append(e('superbwarfare:'+a,2,20,32))
ammopool['entries']+=extra
(P/'loot.json').write_text(json.dumps(data,indent=2)+'\n')
# Entry arrays for the existing runtime. All standard rounds now provide at least 20 even with only one empty slot.
def tsv(entries):
 rows=[]
 for x in entries:
  tag='';lo=hi=1
  for f in x.get('functions',[]):
   if f['function'].endswith('set_nbt'):tag=f['tag']
   if f['function'].endswith('set_count'):lo=f['count']['min'];hi=f['count']['max']
  rows.append('\t'.join(map(str,[x['name'],x['weight'],lo,hi,tag])))
 return '\n'.join(rows)+'\n'
(P/'WEAPONS.tsv').write_text(tsv(weapons))
for x in ammopool['entries']:
 for f in x.get('functions',[]):
  if f['function'].endswith('set_count'):f['count']['min']=max(20,f['count']['min']);f['count']['max']=max(24,f['count']['max'])
(P/'AMMO.tsv').write_text(tsv(ammopool['entries']))
# Supplemental launcher rounds join the weapon category, so they do not replace the regular >=20 ammo roll.
launcher=[e('tacz:ammo',1,1,3,'{AmmoId:"tacz:40mm"}')]+[e('superbwarfare:'+x,1,1,2) for x in ['grenade_40mm','javelin_missile','medium_anti_air_missile']]
weapon_pools[0]['entries']+=launcher
(P/'WEAPONS.tsv').write_text(tsv(weapon_pools[0]['entries']))
(P/'loot.json').write_text(json.dumps(data,indent=2)+'\n')
report={'weapons':len(weapons)-len(launcher),'weapon_ids':[x.get('functions',[{}])[0].get('tag',x['name']) for x in weapons if x not in launcher], 'excluded_tacz':sorted(excluded_t),'excluded_superbwarfare':sorted(excluded_s),'armor_low':len(armor['low']),'armor_high':len(armor['high'])}
(P/'catalog-report.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='weapon_ids'}))
