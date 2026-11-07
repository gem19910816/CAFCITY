"""Run native radio state/SPI probes after build.ps1; does not launch Minecraft."""
from pathlib import Path
import json,subprocess
ROOT=Path(__file__).resolve().parents[1]
props=dict(line.split('=',1) for line in (ROOT/'local.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
jdk=Path(props['java_home'])/'bin'
args=[line.strip().strip('"') for line in (ROOT/'build/javac.args').read_text(encoding='utf-8-sig').splitlines()]
build=int((ROOT/'build_number.properties').read_text(encoding='utf-8-sig').strip().split('=')[1])
jar=ROOT/f'build/libs/caf_lc_customcity-1.21.1-1.0.{build}.jar'
cp=str(jar)+';'+args[args.index('-cp')+1]
out=ROOT/'build/radio-probes';out.mkdir(parents=True,exist_ok=True)
sources=[str(ROOT/'tests'/name) for name in ['RadioStateProbe.java','RadioSpiProbe.java']]
compiled=subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(out),*sources],capture_output=True)
(out/'compile.log').write_bytes(compiled.stdout+compiled.stderr)
assert compiled.returncode==0,'Probe compile failed; see build/radio-probes/compile.log'
results=[]
for name in ['RadioStateProbe','RadioSpiProbe']:
    result=subprocess.run([str(jdk/'java.exe'),'-cp',str(out)+';'+cp,name],capture_output=True)
    (out/f'{name}.log').write_bytes(result.stdout+result.stderr)
    results.append({'name':name,'exit':result.returncode,'stdout':result.stdout.decode('utf-8',errors='replace')})
(out/'report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(results,ensure_ascii=False))
assert all(result['exit']==0 for result in results)
