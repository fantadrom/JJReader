// Same synthetic book, viewport/font/Chromium for both modes. Native gestures/Fragment NOT simulated as PASS.
const {chromium}=require('playwright-core'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const [assets,chrome,out]=process.argv.slice(2);if(!out)throw Error('benchmark_sections.cjs <AAR assets> <Chromium> <output>');fs.mkdirSync(out,{recursive:true});
const root=path.resolve(__dirname,'../app/build/section-regression/3m'),summary=JSON.parse(fs.readFileSync(path.join(root,'summary.json')));
function sizes(base){return fs.readdirSync(base,{withFileTypes:true}).flatMap(e=>{const file=path.join(base,e.name);return e.isDirectory()?sizes(file):e.name.endsWith('.xhtml')&&e.name!=='nav.xhtml'?[fs.statSync(file).size]:[]})}const maxXhtmlBytes=Math.max(...sizes(path.join(root,'source')));
const js=['locator-bridge.js','section-navigation.js'].map(n=>fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets',n),'utf8')).join('\n');
(async()=>{const browser=await chromium.launch({executablePath:chrome,headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-features=BackForwardCache']});const rows=[];
try{for(const mode of ['sections','legacy-control','unified']){
 const runs=[];
 for(let run=0;run<3;run++){
  const context=await browser.newContext({viewport:{width:430,height:500}});await context.addInitScript(()=>{window.Android={getViewportWidth:()=>innerWidth,log:()=>{},logError:()=>{},onDecorationActivated:()=>{},onDragEnd:()=>false,onDragMove:()=>false,onDragStart:()=>false,onKey:()=>false,onSelectionEnd:()=>{},onSelectionStart:()=>{},onTap:()=>false}});
  const failures=[];const page=await context.newPage();await page.route('**/*',async r=>{const u=new URL(r.request().url());let file;if(u.hostname==='readium_assets')file=path.join(assets,u.pathname);else if(u.hostname==='readium_package'){const index=summary.sections.findIndex(s=>'/'+s.href===u.pathname);file=u.pathname==='/unified.xhtml'?path.join(root,'unified.xhtml'):index>=0?path.join(root,'section-'+index+'.xhtml'):path.join(root,mode==='unified'?'derived':'source',decodeURIComponent(u.pathname))}if(!file||!fs.existsSync(file))throw Error('Unexpected fixture resource');await r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.xhtml')?'application/xhtml+xml':file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':'application/octet-stream'})});
  async function load(index){const start=Date.now();await page.goto('https://readium_package/'+(mode==='unified'?'unified.xhtml':summary.sections[index].href),{timeout:0});await page.waitForFunction(()=>typeof readium==='object'&&document.fonts.status==='loaded',{},{timeout:0});await page.evaluate(js);if(mode==='sections')await page.evaluate(()=>JJ.enableSections());return Date.now()-start}
  async function ack(index,start=0){const began=Date.now();await page.evaluate(({mode,index,start})=>(mode==='unified'?JJ.forSection(index):JJ).requestRestore('bench',start,start+1),{mode,index,start});await page.waitForFunction(({mode,index})=>['confirmed','failed'].includes((mode==='unified'?JJ.forSection(index):JJ).restoreStatus()?.phase),{mode,index},{timeout:0});const state=await page.evaluate(({mode,index})=>(mode==='unified'?JJ.forSection(index):JJ).restoreStatus(),{mode,index});if(state.phase!=='confirmed'){failures.push({mode,index,reason:state.reason,durationMs:Date.now()-began});return null}return Date.now()-began}
  console.log(mode+" run "+run+" opening");const firstOpenLoad=await load(0),firstOpenAck=await ack(0);const warmLoad=await load(0),warmAck=await ack(0);
  const buttons=[],edges=[];
  for(let i=1;i<=10;i++){
    const loadMs=mode!=='unified'?await load(i):0,ackMs=await ack(i);buttons.push({loadMs,ackMs,totalMs:ackMs===null?null:loadMs+ackMs});
  }
  if(mode!=='unified')await load(0);else await ack(0);
  for(let i=1;i<=10;i++){
    console.log(mode+" run "+run+" edge "+i);
    const edgeStart=Date.now();
    if(mode==='sections'){
      await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));await page.waitForTimeout(160);
      await page.evaluate(()=>{const target=document.body;const p=y=>new Touch({identifier:1,target,clientX:100,clientY:y});target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,touches:[p(300)],changedTouches:[p(300)]}));target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,touches:[],changedTouches:[p(180)]}))});assert.equal((await page.evaluate(()=>JJ.takeEdge()))?.direction,'next');
      const detectionMs=Date.now()-edgeStart-160,transitionLoad=await load(i),transitionAck=await ack(i);edges.push({detectionMs,loadMs:transitionLoad,ackMs:transitionAck,totalMs:transitionAck===null?null:detectionMs+transitionLoad+transitionAck});
    }else if(mode==='legacy-control'){const loadMs=await load(i),ackMs=await ack(i);edges.push({loadMs,ackMs,totalMs:ackMs===null?null:loadMs+ackMs,comparison:'explicit next document; legacy has no edge gesture'})}
    else{const ms=await ack(i);edges.push({ackMs:ms,totalMs:ms,comparison:'explicit next-source anchor; unified has no edge gesture'})}
  }
  if(mode!=='unified')await load(10);else await ack(10);
  const fonts=[];for(const percent of ['115%','130%','100%']){const start=Date.now();await page.evaluate(percent=>readium.setCSSProperties({'--USER__fontSize':percent}),percent);const ok=await ack(10);fonts.push(ok===null?null:Date.now()-start)}
  const client=await context.newCDPSession(page);await client.send('HeapProfiler.collectGarbage');await client.send('Performance.enable');const metrics=await client.send('Performance.getMetrics');const jsHeap=metrics.metrics.find(m=>m.name==='JSHeapUsedSize')?.value;
  runs.push({failures,status:failures.length?"PARTIAL":"PASS",firstOpenLoad,firstOpenAck,firstOpenMs:firstOpenAck===null?null:firstOpenLoad+firstOpenAck,warmLoad,warmAck,warmOpenMs:warmAck===null?null:warmLoad+warmAck,buttons,edges,fontMs:fonts,jsHeapUsedBytes:jsHeap});await context.close();
 }
 rows.push({mode,runs});fs.writeFileSync(path.join(out,'comparison.json'),JSON.stringify({runtime:'Linux Chromium '+browser.version(),method:'3 fresh browser contexts/mode; 10 button + 10 edge operations/run; same book/font100%/430x500; OS cache not flushed; new-context cold vs second document open; no native Fragment/prefetch, model-loading or 40ms Android polling overhead; BackForwardCache disabled, JS heap measured after explicit GC; DOM touches simulated',totalCP:summary.total,sections:summary.sectionCount,maxChapterCP:summary.maxChapterCP,maxXhtmlBytes,unifiedPrepareMs:summary.prepareMs,rows},null,2));console.log(mode+' '+(runs.some(r=>r.failures.length)?'PARTIAL':'PASS')+' '+runs.map(r=>r.firstOpenMs));
}}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
