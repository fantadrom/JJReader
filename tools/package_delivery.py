"""Package a verified P0 checkout. Signing keys are never included."""
import argparse, hashlib, json, shutil, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--pr-url',default=None)
parser.add_argument('--verification-log',type=Path)
a=parser.parse_args();root=Path(__file__).resolve().parents[1];out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
if subprocess.check_output(['git','status','--porcelain'],cwd=root).strip(): raise SystemExit('Refusing to package uncommitted sources')
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
branch=subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip()
def copy(source,name): shutil.copy2(root/source,out/name)
copy(Path('app/build/outputs/apk/debug/app-debug.apk'),'JJReader-0.1.0-p0-debug.apk')
copy(Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),'JJReader-P0-androidTest.apk')
copy(Path('app/src/main/assets/p0-sample.epub'),'JJReader-P0-sample.epub')
for source,name in [('README.md','README.md'),('docs/P0_REPORT.md','P0_REPORT.md'),('docs/HONOR_CHECKLIST.md','HONOR_CHECKLIST.md'),('docs/THIRD_PARTY.md','THIRD_PARTY.md')]: copy(Path(source),name)
shutil.copytree(root/'docs',out/'docs',dirs_exist_ok=True)
shutil.copytree(root/'app/src/main/assets/licenses',out/'licenses',dirs_exist_ok=True)
checks=out/'checks';checks.mkdir(exist_ok=True)
copy(Path('app/build/reports/lint-results-debug.html'),'checks/lint-results-debug.html')
copy(Path('app/build/reports/lint-results-debug.txt'),'checks/lint-results-debug.txt')
if a.verification_log: shutil.copy2(a.verification_log,checks/'gradle.log')
tests=[]
for directory in ['app/build/test-results','edge-protocol/build/test-results']:
 for path in (root/directory).rglob('TEST-*.xml'):
  data=ET.parse(path).getroot().attrib
  tests.append({k:data[k] for k in ['name','tests','failures','errors','skipped']})
  shutil.copy2(path,checks/path.name)
  assert data['failures']=='0' and data['errors']=='0'
with zipfile.ZipFile(out/'JJReader-P0-sources.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.comment=('JJReader P0 commit '+commit).encode()
 for item in subprocess.check_output(['git','ls-files','-z'],cwd=root).split(b'\0'):
  if item:
   relative=Path(item.decode());z.write(root/relative,Path('JJReader')/relative)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
artifacts={p.name:{'bytes':p.stat().st_size,'sha256':sha(p)} for p in out.iterdir() if p.is_file() and p.name not in ['DELIVERY.json','SHA256SUMS.txt']}
value={'versionName':'0.1.0-p0','versionCode':1,'applicationId':'com.fantadrom.jjreader','branch':branch,'commit':commit,'draft_pr':a.pr_url,'android_runtime':'NOT_RUN','honor':'NOT_RUN','edge_cloud':'BLOCKED: proxy CONNECT 403','unit_tests':tests,'artifacts':artifacts}
(out/'DELIVERY.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
(out/'SHA256SUMS.txt').write_text(''.join(f'{data["sha256"]}  {name}\n' for name,data in artifacts.items()))
print(json.dumps({'commit':commit,'files':len(artifacts),'apk':artifacts['JJReader-0.1.0-p0-debug.apk']},indent=2))
