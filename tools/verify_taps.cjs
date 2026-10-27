const {chromium}=require('playwright-core'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const [assets,chrome,out]=process.argv.slice(2);if(!out)throw Error('verify_taps.cjs <Readium AAR assets> <Chromium> <output>');fs.mkdirSync(out,{recursive:true});
const root=path.resolve(__dirname,'../app/build/section-regression/tap'),summary=JSON.parse(fs.readFileSync(path.join(root,'summary.json')));
const js=['locator-bridge.js','section-navigation.js'].map(n=>fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets',n),'utf8')).join('\n');
(async()=>{const browser=await chromium.launch({executablePath:chrome,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});try{
 const context=await browser.newContext({viewport:{width:430,height:700},hasTouch:true});
 await context.addInitScript(()=>{window.Android={getViewportWidth:()=>innerWidth,log:()=>{},logError:()=>{},onDecorationActivated:()=>{},onDragEnd:()=>false,onDragMove:()=>false,onDragStart:()=>false,onKey:()=>false,onSelectionEnd:()=>{},onSelectionStart:()=>{},onTap:()=>false}});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async r=>{const u=new URL(r.request().url());let file;if(u.hostname==='readium_assets')file=path.join(assets,u.pathname);else if(u.hostname==='readium_package'){const i=summary.sections.findIndex(s=>'/'+s.href===u.pathname);file=i>=0?path.join(root,`section-${i}.xhtml`):path.join(root,'source',decodeURIComponent(u.pathname))}if(!file||!fs.existsSync(file))throw Error('Unexpected fixture URL');await r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.xhtml')?'application/xhtml+xml':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'application/octet-stream'})});
 let index=0,transitions=0;
 async function open(i){index=i;await page.goto('https://readium_package/'+summary.sections[i].href);await page.waitForFunction(()=>typeof readium==='object');await page.evaluate(js);await page.evaluate(()=>{JJ.enableSections();JJ.configureInteractions(true)});await page.waitForTimeout(150);assert.equal(await page.evaluate(()=>JJ.text),summary.sections[i].text)}
 const state=()=>page.evaluate(()=>JJ.contentState());
 async function restore(kind,a=null,b=null){const visual=summary.sections[index].length===0;
  await page.evaluate(({kind,a,b,visual})=>{const p=kind?{schema:1,kind}:null;if(visual)JJ.requestVisualRestore('entry',kind!=='RESOURCE_START',p);else JJ.requestRestore('entry',a??(kind==='RESOURCE_START'?0:JJ.text.length-1),b??(kind==='RESOURCE_START'?1:JJ.text.length),p)},{kind,a,b,visual});
  await page.waitForFunction(visual?()=>JJ.visualStatus()?.phase==='confirmed':()=>JJ.restoreStatus()?.phase==='confirmed');await page.waitForTimeout(80);
  return page.evaluate(()=>JJ.viewport());
 }
 async function eventTap(x){await page.evaluate(x=>{const t=document.body,p=new Touch({identifier:1,target:t,clientX:innerWidth*x,clientY:innerHeight*.6});for(const type of ['touchstart','touchend'])t.dispatchEvent(new TouchEvent(type,{bubbles:true,cancelable:true,touches:type==='touchstart'?[p]:[],changedTouches:[p]}))},x)}
 async function step(dir){const result=await page.evaluate(dir=>JJ.pageStep(dir),dir);await page.evaluate(()=>JJ.edgeComplete());return result}
 async function tap(dir){await eventTap(dir<0?.16:.86);const a=await page.evaluate(()=>JJ.takeReaderAction());assert.equal(a?.kind,'page');const r=await step(dir);if(r.resource&&index+r.resource>=0&&index+r.resource<summary.sections.length){await open(index+r.resource);await restore(r.placement);transitions++}return r}
 async function freeBelow(free){await page.evaluate(free=>{const s=JJ.contentState();scrollTo(0,s.contentBottom+free-innerHeight)},free);await page.waitForTimeout(35);return state()}
 const matrix=[];
 for(const [width,height] of [[430,700],[430,400],[1000,260],[1000,170]])for(const large of [false,true]){
  await page.setViewportSize({width,height});await open(0);
  if(large){await page.evaluate(()=>{for(const p of document.querySelectorAll('p')){p.style.setProperty('font-size','30px','important');p.style.setProperty('line-height','normal','important')}});await page.waitForTimeout(100)}
  const bodyFont=await page.locator('p').first().evaluate(p=>parseFloat(getComputedStyle(p).fontSize));if(large)assert.equal(bodyFont,30,'large body font applied');
  const L=(await state()).lineHeight;if(large)assert.equal(L,bodyFont*1.4,'normal line-height from body copy font');
  for(const free of [-3,0,L-3]){await freeBelow(free);assert.equal((await step(1)).resource,0,`partial/too little space H${height} free${free}`)}
  for(const free of [L,L+.25]){await freeBelow(free);assert.equal((await step(1)).resource,1,'threshold')}
  await page.evaluate(()=>{const s=JJ.contentState();scrollTo(0,s.contentBottom+20)});assert.equal((await step(1)).resource,1,'already past text');
  await restore('END_PAGE');const end=await state(),gap=height-(end.contentBottom-end.scrollY);assert.ok(Math.abs(gap-Math.min(4*L,height/2))<=2,JSON.stringify({gap,L,height}));
  let previous=end.scrollY;const back=await step(-1),now=(await state()).scrollY;assert.equal(back.resource,0);assert.ok(Math.abs(previous-now-(height-L))<=2,'one page up with overlap');
  await restore('RESOURCE_START');const start=await state();assert.equal(start.scrollY,0);assert.ok(start.contentTop>1,'natural top margin preserved');assert.equal((await step(-1)).resource,-1);
  await page.evaluate(()=>scrollTo(0,JJ.contentState().contentTop));assert.equal((await step(-1)).resource,-1,'aligned glyph, scrollY > 0');
  await page.evaluate(()=>scrollTo(0,JJ.contentState().contentTop+4));assert.equal((await step(-1)).resource,0,'clipped first glyph');
  matrix.push({width,height,large,bodyFont,lineHeight:L,endGap:gap});
 }
 await page.setViewportSize({width:430,height:700});await open(0);await freeBelow((await state()).lineHeight+2);const before=transitions;assert.equal((await tap(1)).resource,1);assert.equal(index,1);assert.equal(transitions-before,1);assert.equal((await state()).scrollY,0);
 const short=await restore('END_PAGE');assert.equal((await state()).scrollY,0,'short content retains start');assert.equal(short.entryPlacement.kind,'END_PAGE');
 await restore('RESOURCE_START');assert.equal((await tap(-1)).resource,-1);assert.equal(index,0);assert.ok((await state()).freeBelow>40);
 // The very first/last resources are safe no-ops in the native target range check.
 await restore('RESOURCE_START');await tap(-1);assert.equal(index,0);await open(7);await restore('END_PAGE');await tap(1);assert.equal(index,7);
 // The cover, trailing image, table border and caption are content, not decorative tail.
 await open(2);await restore('RESOURCE_START');assert.equal((await step(-1)).resource,-1);await page.evaluate(()=>scrollTo(0,document.querySelector('h1').getBoundingClientRect().top+scrollY));assert.equal((await step(-1)).resource,0,'cover before heading not skipped');
 await open(3);await page.evaluate(()=>{const r=JJ.geometry(JJ.text.length-1,JJ.text.length).firstRect;scrollBy(0,r.bottom+JJ.contentState().lineHeight-innerHeight)});assert.equal((await step(1)).resource,0,'trailing illustration unseen');await restore('END_PAGE');{const s=await state();assert.ok(Math.abs(s.freeBelow-Math.min(4*s.lineHeight,s.viewportHeight/2))<=2,JSON.stringify(s))}assert.ok((await page.locator('#trailer').boundingBox()).y<0,'large image lower part');
 await open(4);await restore('END_PAGE');const table=await state();assert.ok(Math.abs(table.freeBelow-104)<=2);assert.ok(table.contentBottom>=await page.locator('table').evaluate(e=>e.getBoundingClientRect().bottom+scrollY));
 // No DOM walks on taps, scrolling or forty-millisecond polling; reflow invalidates metrics.
 const scans=(await state()).metricScans;await page.evaluate(()=>{for(let i=0;i<200;i++){scrollTo(0,i);JJ.contentState()}});assert.equal((await state()).metricScans,scans);
 await page.evaluate(()=>{document.querySelector('#end').style.marginTop='70px'});await page.waitForTimeout(100);assert.ok((await state()).metricScans>scans);
 // Six reflows/restarts preserve semantic entry, not a fixed pixel offset or first visible line.
 const reflows=[];
 for(const kind of ['RESOURCE_START','END_PAGE']){
  await open(0);let snap=await restore(kind);
  for(let i=0;i<6;i++){
   await eventTap(.5);assert.equal((await page.evaluate(()=>JJ.takeReaderAction())).kind,'panels');snap=await page.evaluate(()=>JJ.viewport());assert.equal(snap.entryPlacement.kind,kind);
   await page.setViewportSize(i%2?{width:430,height:700}:{width:1000,height:260});await page.waitForTimeout(90);await restore(snap.entryPlacement.kind,snap.anchor.start,snap.anchor.end);
   const s=await state();assert.ok(kind==='RESOURCE_START'?s.scrollY===0:Math.abs(s.freeBelow-Math.min(4*s.lineHeight,s.viewportHeight/2))<=2);reflows.push({kind,y:s.scrollY,gap:s.freeBelow});
  }
  await open(0);snap=await restore(kind,snap.anchor.start,snap.anchor.end);assert.equal(snap.entryPlacement.kind,kind,'recreated document');
 }
 for(const i of [5,6])for(const kind of ['RESOURCE_START','END_PAGE']){await open(i);const v=await restore(kind);assert.equal(v.visual,true);assert.equal(v.entryPlacement.kind,kind);await eventTap(.5);await page.evaluate(()=>JJ.takeReaderAction());assert.equal((await page.evaluate(()=>JJ.viewport())).entryPlacement.kind,kind)}
 // Late reads do not replay placement; manual scrolling releases its persistence marker.
 await open(0);await restore('END_PAGE');await page.evaluate(()=>{document.dispatchEvent(new Event('wheel'));scrollBy(0,-100)});const y=(await state()).scrollY;await page.evaluate(()=>JJ.restoreStatus());assert.equal((await state()).scrollY,y);assert.equal((await page.evaluate(()=>JJ.viewport())).entryPlacement,undefined);
 // Arbitrary source anchor restoration keeps its exact code-point through repeated changes.
 for(let i=0;i<6;i++){await page.setViewportSize(i%2?{width:430,height:700}:{width:1000,height:260});const v=await restore(null,233,234);assert.equal(v.anchor.start,233);assert.ok(v.entryPlacement==null)}
 // Fast contacts queue at most one action until native acknowledgement.
 await page.evaluate(()=>JJ.edgeComplete());await eventTap(.86);await eventTap(.86);assert.equal((await page.evaluate(()=>JJ.takeReaderAction())).kind,'page');assert.equal(await page.evaluate(()=>JJ.takeReaderAction()),null);
 assert.deepEqual(errors,[]);
 const result={status:'PASS',runtime:'desktop Chromium '+browser.version()+'; production JS + real Readium injection; simulated DOM touches, Android NOT_RUN',matrix,reflows,transitions,checks:['forward threshold/partial/subpixel','start natural/aligned/clipped','end page gap/short/overlap','first-last safe bounds','cover/trailer/table/caption','image-only/empty semantic ACK','six reflows and new document','CP233 arbitrary anchor unchanged','late read/manual release','cached metrics/no walks on 200 polls','fast taps one action']};
 fs.writeFileSync(path.join(out,'taps.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
