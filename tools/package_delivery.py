"""Package a verified JJReader checkout. Signing keys are never included."""
import argparse, hashlib, json, shutil, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--pr-url',default=None)
parser.add_argument('--verification-log',type=Path)
parser.add_argument('--p1-checks',type=Path,help='P1 browser and DOM check directory')
parser.add_argument('--tap-checks',type=Path,help='P1.1 boundary, compatibility and DOM checks')
parser.add_argument('--section-checks',type=Path,help='P0.8 browser/performance evidence directory')
parser.add_argument('--runtime-checks',type=Path,help='Directory with P0.7 synthetic desktop check results')
a=parser.parse_args();root=Path(__file__).resolve().parents[1];out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
if subprocess.check_output(['git','status','--porcelain'],cwd=root).strip(): raise SystemExit('Refusing to package uncommitted sources')
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
branch=subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip()
def copy(source,name): shutil.copy2(root/source,out/name)
metadata=json.loads((root/'app/build/outputs/apk/debug/output-metadata.json').read_text())
version=metadata['elements'][0]['versionName'];version_code=metadata['elements'][0]['versionCode']
apk_name=f'JJReader-{version}-debug.apk'
copy(Path('app/build/outputs/apk/debug/app-debug.apk'),apk_name)
copy(Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),f'JJReader-{version}-androidTest.apk')
copy(Path('app/src/main/assets/p0-sample.epub'),'JJReader-P0-sample.epub')
for source,name in [('README.md','README.md'),('docs/P0_REPORT.md','P0_REPORT.md'),('docs/HONOR_CHECKLIST.md','HONOR_CHECKLIST.md'),('docs/THIRD_PARTY.md','THIRD_PARTY.md')]: copy(Path(source),name)
copy(Path('docs/P0_1_XHTML_REPORT.md'),'P0_1_XHTML_REPORT.md')
copy(Path('docs/P0_2_RUNTIME_REPORT.md'),'P0_2_RUNTIME_REPORT.md')
copy(Path('docs/P0_3_LAYOUT_TTS_REPORT.md'),'P0_3_LAYOUT_TTS_REPORT.md')
copy(Path('docs/P0_4_POSITION_VOICE_REPORT.md'),'P0_4_POSITION_VOICE_REPORT.md')
copy(Path('docs/P0_5_RESOURCE_STATE_REPORT.md'),'P0_5_RESOURCE_STATE_REPORT.md')
copy(Path('docs/P0_6_RESTORE_ACK_REPORT.md'),'P0_6_RESTORE_ACK_REPORT.md')
copy(Path('docs/P0_7_CONTINUOUS_READING_REPORT.md'),'P0_7_CONTINUOUS_READING_REPORT.md')
copy(Path('docs/P0_7_1_ANDROID_XML_REPORT.md'),'P0_7_1_ANDROID_XML_REPORT.md')
copy(Path('docs/P0_8_SECTION_NAVIGATION_REPORT.md'),'P0_8_SECTION_NAVIGATION_REPORT.md')
copy(Path('docs/P1_READER_SHELF_REPORT.md'),'P1_READER_SHELF_REPORT.md')
copy(Path('docs/P1_1_TAP_BOUNDARIES_REPORT.md'),'P1_1_TAP_BOUNDARIES_REPORT.md')
copy(Path('docs/JJReader_TZ.md'),'JJReader_Codex_TZ.md')
copy(Path('app/src/test/resources/sections/p08-sections.epub'),'JJReader-P0.8-sections.epub')
copy(Path('app/src/test/resources/sections/p11-taps.epub'),'JJReader-P1.1-taps.epub')
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
shutil.copytree(root/'app/build/xhtml-regression',checks/'xhtml-regression',dirs_exist_ok=True)
shutil.copytree(root/'app/build/unified-regression',checks/'unified-regression',dirs_exist_ok=True,ignore=shutil.ignore_patterns('files'))
performance=checks/'performance';performance.mkdir(exist_ok=True)
shutil.copy2(root/'app/build/unified-benchmark/results.json',performance/'jvm-benchmark.json')
for label in ['small','1m','3m']:
 copy(Path(f'work/unified-fixtures/{label}.epub'),f'JJReader-synthetic-{label}.epub')
if a.runtime_checks:
 shutil.copy2(a.runtime_checks/'performance/browser-benchmark.json',performance/'browser-benchmark.json')
 shutil.copytree(a.runtime_checks/'unified-browser-final',checks/'unified-browser',dirs_exist_ok=True)
 for name in ['dom.log','legacy-browser.log','benchmark-jvm.log']:
  shutil.copy2(a.runtime_checks/name,checks/name)
if a.section_checks:
 shutil.copytree(a.section_checks/'browser',checks/'sections',dirs_exist_ok=True)
 shutil.copy2(a.section_checks/'performance/comparison.json',checks/'sections-performance.json')
 shutil.copy2(a.section_checks/'dom.log',checks/'sections-dom.log')
if a.p1_checks:
 shutil.copytree(a.p1_checks/'browser',checks/'p1-browser',dirs_exist_ok=True)
 shutil.copy2(a.p1_checks/'dom.log',checks/'p1-dom.log')
if a.tap_checks:
 shutil.copytree(a.tap_checks,checks/'tap-boundaries',dirs_exist_ok=True)
with zipfile.ZipFile(out/f'JJReader-{version}-sources.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.comment=('JJReader '+version+' commit '+commit).encode()
 for item in subprocess.check_output(['git','ls-files','-z'],cwd=root).split(b'\0'):
  if item:
   relative=Path(item.decode())
   if relative.suffix in {'.keystore','.jks'}: raise SystemExit('Signing keys cannot be included')
   z.write(root/relative,Path('JJReader')/relative)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
artifacts={p.name:{'bytes':p.stat().st_size,'sha256':sha(p)} for p in out.iterdir() if p.is_file() and p.name not in ['DELIVERY.json','SHA256SUMS.txt']}
value={'versionName':version,'versionCode':version_code,'applicationId':metadata['applicationId'],'branch':branch,'commit':commit,'draft_pr':a.pr_url,'android_runtime':'NOT_RUN','honor_current_build':'NOT_RUN','p0_acceptance':'Accepted by user; P0.8 merged into master at 67acede','p1_current':{'base':'67acede','shelf':'SAF tree, original UUID copies, progress/metadata, confirmed clear/delete','reader':'sections, panels/themes/fonts/window brightness/taps/slider/return','notesModel':'schema1, existing data retained','tts':'five phrases, unchanged provider/protocol; per-book cache ownership','android_runtime':'NOT_RUN','future_stages':'P2-P5 not started'},'honor_0.1.8_user_results':{'open_unified':'PASS','toc_notes_font_position_rotate_landscape':'PASS','tts_boundary_manual_reset':'PASS','large_unified_section_buttons':'slow, cause not measured','old_copies_images_footnote_return':'not confirmed'},'section_navigation_current':{'defaultMode':'sections','modeSchema':1,'gestures':'production bridge DOM tests PASS; Android NOT_RUN','library':'streaming original copies, SHA256 original bytes, explicit duplicate selection','performance':'Historical P0.8; see docs/P0_8_SECTION_NAVIGATION_REPORT.md; not rerun for P1'},'honor_0.1.7_user_results':{'unified_open':'FAIL for prior book and two EPUB; parser exception not recorded'},'opening_current':'Android XmlPullParser validator; opening-attempt schema1; source and unified modes; P1 Honor NOT_RUN','performance_origin':'Historical P0.7/d40b70e measurements; not rerun in P0.7.1','honor_0.1.1_user_results':{'epub_open_scroll_transitions':'PASS','progress':'PASS by observation, end 100%','copy_independence_cancel':'PASS','position_home_lock_return':'PASS','position_recreate_rotate':'FAIL','font_notes':'FAIL','tts_highlight':'FAIL','both_voices_pause_resume':'INITIAL_SUCCESS','tts_repeat':'UNSTABLE, silence or NETWORK','audio_stop_home_lock':'NOT_SEPARATELY_CONFIRMED'},'honor_0.1.2_user_results':{'font_notes':'PASS','approximate_restore_recreate_rotate':'PASS, accuracy accepted','svetlana_highlight':'PASS','home_lock_stop_manual_resume':'PASS','dmitry':'two network syntheses completed, HTTP101 close1000; third IO_SocketException, cause unknown'},'honor_0.1.3_user_results':{'landscape_font':'PASS','svetlana_highlight_autoscroll':'PASS','pause_home_lock_manual_resume':'PASS','cross_resource_note_then_rotate':'FAIL, restored previous resource','dmitry':'three network HTTP101 handshakes, zero recognized audio; SocketException, cause unknown'},'honor_0.1.4_user_results':{'position_x1_rotate_x2_rotate':'FAIL, still x1','landscape_new_resource':'FAIL, narrow','font_notes_lifecycle':'PASS','svetlana_audio_highlight_controls':'PASS','dmitry_uncached':'HTTP101 full/send accepted, turn.start+response, zero binary/audio, SocketException; unresolved','svetlana_uncached':'28224 audio bytes, 40 nonempty audio frames / 41 binary, synth success and playback completed'},'honor_0.1.5_user_results':{'position':'FAIL; restore accepted then REJECT before ACK/COMMIT, ready=false blocks capture, old chapter3 persisted','resource_width_font_notes':'PASS','current_dmitry':'HTTP101,27216 audio bytes,38 portions,synth+playback success; normal reading later worked, one phrase retried','current_svetlana':'HTTP101,28224 audio bytes,40 portions,synth+playback success','readest_both':'HTTP200,0 audio,ProtocolException; no WebSocket upgrade in comparison'},'honor_0.1.6_user_results':{'correct_build':'PASS on Honor per P0.7 spec; previous failing trace was an older build','position_x1_rotate_x2_rotate':'PASS','repeated_rotations_no_drift':'PASS','landscape_resource_width':'PASS'},'position_current':'source-local adapter and unified ACK tests PASS on desktop; P1 Honor NOT_RUN','tts_current':'current route unchanged; both voices confirmed by user on code6, P1 Microsoft NOT_RUN','edge_voice_catalog':'BLOCKED in Codex, proxy CONNECT 403','edge_cloud':'NOT_RUN; local mock WebSocket is not Microsoft','continuous_current':{'mode':'sections default; unified diagnostic comparison','converter':'unified-v1','sourceModel':1,'readerTraceSchema':3,'correctness':'PASS JVM/XML/desktop Chromium; native instrumentation compiled NOT_RUN','performance':'requires Honor; desktop reflow and memory costs documented','source_migration':'none; source EPUB and anchors remain source-local'},'unit_tests':tests,'artifacts':artifacts}
value['honor_0.2.0_user_results']={'checklist_1_4_6_8':'PASS reported by user','checklist_5':'FAIL: extra taps on content boundaries, heading pulled to top'}
value['p11_current']={'base':'7095994d66cc603f9aca4f77897cc33d7d2214f3','entry':'RESOURCE_START / END_PAGE / END_TOP, optional semantic schema1, no stored pixels','safety':'source text model, notes, UUIDs, signing identity retained','honor':'NOT_RUN','android_instrumentation':'compiled, NOT_RUN','merge':'NOT_PERFORMED'}
(out/'DELIVERY.json').write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
(out/'SHA256SUMS.txt').write_text(''.join(f'{sha(path)}  {path.relative_to(out).as_posix()}\n' for path in sorted(out.rglob('*')) if path.is_file() and path.name!='SHA256SUMS.txt'))
print(json.dumps({'commit':commit,'files':len(artifacts),'apk':artifacts[apk_name]},indent=2))
