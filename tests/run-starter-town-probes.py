"""Run the actual candidate planner with a fixed-terrain fixture, never launch Minecraft."""
from pathlib import Path
import argparse, json, subprocess, shutil, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

parser = argparse.ArgumentParser()
parser.add_argument('--config', required=True, type=Path)
parser.add_argument('--output', type=Path)
parser.add_argument('--artifact', required=True, type=Path, help='The candidate JAR under review')
parser.add_argument('--buildings-from', required=True, type=Path,
                    help='Read-only source of one real building per enabled stage, to satisfy the normal loader')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = args.output or root / 'tests/.work/starter-town'
output.mkdir(parents=True, exist_ok=True)
fixture = output / 'config'
fixture.mkdir(exist_ok=True)
shutil.copy2(args.config, fixture / '城市风格配置.json')
settings = json.loads(args.config.read_text('utf-8-sig'))
for name, stage in settings['阶段'].items():
    if not stage.get('启用'):
        continue
    source_dir = args.buildings_from / stage['城市风格'] / name
    building = next(p for p in source_dir.rglob('*.nbt') if '建筑文件' in p.parts)
    destination = fixture / building.relative_to(args.buildings_from)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(building, destination)
tmp = output / 'tmp'
tmp.mkdir(exist_ok=True)
local = dict(line.split('=', 1) for line in (root / 'local.properties').read_text('utf-8-sig').splitlines()
             if '=' in line and not line.lstrip().startswith('#'))
jdk = Path(local['java_home'])
build_args = [json.loads(line) for line in (root / 'build/javac.args').read_text('utf-8-sig').splitlines()]
dependencies = build_args[build_args.index('-cp') + 1]
classes = output / 'classes'
classes.mkdir(exist_ok=True)
cp = str(args.artifact.resolve()) + ';' + dependencies
sources = [root / 'tools/smoke/StarterTownPlanSmokeTest.java', root / 'tests/StarterTownRuntimeProbe.java']

def run(name, executable, values):
    arguments = output / (name + '.args')
    # javac reads UTF-8 argfiles; the Windows native java launcher uses the active ANSI codepage.
    encoding = 'utf-8' if executable.name == 'javac.exe' else 'mbcs'
    arguments.write_text('\n'.join(json.dumps(str(v).replace('\\', '/'), ensure_ascii=False) for v in values), encoding)
    result = subprocess.run([str(executable), '@' + str(arguments)], cwd=root, capture_output=True)
    (output / (name + '.log')).write_bytes(result.stdout + result.stderr)
    print(name, 'exit', result.returncode)
    if result.returncode:
        print((result.stdout + result.stderr).decode('utf-8', 'replace')[-5000:])
        raise SystemExit(result.returncode)

run('compile', jdk / 'bin/javac.exe', ['-encoding', 'UTF-8', '-proc:none', '-cp', cp, '-d', classes, *sources])
extra = next((Path(local['minecraft_libraries']) / 'net/minecraft/client').rglob('client-1.21.1-*-extra.jar'))
runtime_cp = str(classes) + ';' + cp + ';' + str(extra)
run('geometry', jdk / 'bin/java.exe', ['-Djava.io.tmpdir=' + str(tmp), '-cp', runtime_cp, 'StarterTownPlanSmokeTest'])
run('runtime', jdk / 'bin/java.exe', ['-Djava.io.tmpdir=' + str(tmp), '-cp', runtime_cp,
    'com.caf.lccustomcity.impl.StarterTownRuntimeProbe', fixture, output / 'runtime-report.json'])
print('Reports:', output)
