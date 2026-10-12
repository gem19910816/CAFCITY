"""Copy original street scenes and roadside seating; never edit the source JAR."""
from pathlib import Path
import hashlib, json, re, zipfile
import os, sys

project=Path(__file__).resolve().parent.parent
source=Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get('CAF_CHAOSZ_JAR', ''))
if not source.is_file():
    raise SystemExit('Pass the source jar as the first argument or set CAF_CHAOSZ_JAR.')
target=project/'src/main/resources/caf_lc_customcity/chaos_roads'
target.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(source) as jar:
    style=json.loads(jar.read('data/lostcities/lostcities/citystyles/standard.json'))
    variants=style['streetblocks']['parts']
    hashes={}
    for names in variants.values():
        for name in names:
            assert re.fullmatch(r'street_[a-z]+\d+_0_0_0',name), name
            data=jar.read('data/lostcities/lostcities/parts/'+name+'.json')
            (target/(name+'.json')).write_bytes(data)
            hashes[name]=hashlib.sha256(data).hexdigest()
    decorations=['bus_stop1','bus_stop2','bus_stop3','building_front1','building_front2','building_front3']
    for name in decorations:
        data=jar.read('data/lostcities/lostcities/parts/'+name+'.json')
        (target/(name+'.json')).write_bytes(data)
        hashes[name]=hashlib.sha256(data).hexdigest()
manifest={'source':'ChaosZPack 1.4.1 TacZ by ChaosCraft',
          'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
          'variants':variants,'decorations':decorations,'original_part_sha256':hashes,
          'adaptation':'Existing vacant plots; compatible states; leaf height capped at two.'}
(target/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Imported',len(hashes),'original road scenes')
