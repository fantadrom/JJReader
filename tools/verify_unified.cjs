// Desktop-only real Chromium + pinned Readium assets. No device/service claims.
const {chromium}=require('playwright-core');const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const [assets,chrome,out]=process.argv.slice(2);if(!out)throw Error('Usage: verify_unified.cjs <AAR assets> <Chromium> <output>');
const fixture=path.resolve(__dirname,'../app/build/unified-regression');const model=JSON.parse(fs.readFileSync(path.join(fixture,'model.json')));const map=JSON.parse(fs.readFileSync(path.join(fixture,'map.json')));
const bridge=fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/locator-bridge.js'),'utf8');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({executablePath:chrome,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});const context=await browser.newContext({viewport:{width:1000,height:650}});
await context.addInitScript(()=>{window.Android={getViewportWidth:()=>innerWidth,log:()=>{},logError:()=>{},onDecorationActivated:()=>{},onDragEnd:()=>false,onDragMove:()=>false,onDragStart:()=>false,onKey:()=>false,onSelectionEnd:()=>{},onSelectionStart:()=>{},onTap:()=>false}});
const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.route('**/*',async r=>{const u=new URL(r.request().url());let file;
 if(u.hostname==='readium_assets')file=path.join(assets,u.pathname);else if(u.hostname==='readium_package')file=u.pathname.endsWith('unified.xhtml')?path.join(fixture,'unified.injected.xhtml'):path.join(fixture,'files',decodeURIComponent(u.pathname));
 if(!file || !fs.existsSync(file))throw Error('Unexpected request '+u.pathname);
 await r.fulfill({contentType:file.endsWith('.xhtml')?'application/xhtml+xml':file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':file.endsWith('.png')?'image/png':'application/octet-stream',body:fs.readFileSync(file)});
});
try{await page.goto('https://readium_package/unified.xhtml');await page.waitForFunction(()=>typeof readium==='object');await page.evaluate(bridge);
 const texts=await page.evaluate(()=>JJ.sectionTexts());assert.deepEqual(texts,model.chapters.map(c=>c.text));
 const restore=async(ch,start,end)=>{assert.equal(await page.evaluate(({ch,start,end})=>JJ.forSection(ch).requestRestore('proof',start,end),{ch,start,end}),true);await page.waitForFunction(ch=>['confirmed','failed'].includes(JJ.forSection(ch).restoreStatus()?.phase),ch);const s=await page.evaluate(ch=>JJ.forSection(ch).restoreStatus(),ch);assert.equal(s.phase,'confirmed',JSON.stringify(s));return s};
 if(model.chapters.length===4) {
  assert.equal(await page.locator('#jj-0-same').count(),1);assert.equal(await page.locator('#jj-1-same').count(),1);
  assert.equal(await page.locator('#jj-source-0 .repeat').evaluate(e=>getComputedStyle(e).color),'rgb(180, 0, 0)');
  assert.equal(await page.locator('#jj-source-1 .repeat').evaluate(e=>getComputedStyle(e).color),'rgb(0, 0, 180)');
  assert.equal(await page.evaluate(()=>Array.from(document.images).every(i=>i.complete&&i.naturalWidth>0)),true);
  assert.equal(await page.locator('#jj-source-0 a').first().getAttribute('href'),'#jj-1-same');
 }
 const anchors=[];
 for(let ch=0;ch<texts.length;ch++){if(!texts[ch].length)continue;const a=await page.evaluate(ch=>JJ.forSection(ch).anchor(5,20),ch);assert.equal(a.quote,Array.from(texts[ch]).slice(a.start,a.end).join(''));await restore(ch,a.start,a.end);anchors.push({ch,start:a.start,end:a.end});}
 await page.evaluate(()=>{document.dispatchEvent(new Event('wheel'));const e=document.getElementById('jj-source-1');scrollBy(0,e.getBoundingClientRect().top-200)});await page.waitForTimeout(60);
 const seam=await page.evaluate(()=>JJ.viewport());assert.equal(seam.sourceChapter,0);assert.ok(seam.lastSourceChapter>=1,JSON.stringify(seam));
 const snap=await page.evaluate(()=>{const e=document.querySelector('#jj-source-1 p'),n=e.firstChild;const r=document.createRange();r.setStart(n,0);r.setEnd(n,8);const s=getSelection();s.removeAllRanges();s.addRange(r);JJ.beginSelection('one');return JJ.takeSelection('one')});
 assert.equal(snap.sourceChapter,1);assert.equal(snap.anchor.quote,Array.from(texts[1]).slice(snap.anchor.start,snap.anchor.end).join(''));
 await restore(snap.sourceChapter,snap.anchor.start,snap.anchor.end);
 if(texts.length===4){
  const repeated=await page.evaluate(()=>{const p=document.querySelector('#jj-source-0 p:nth-of-type(2)'),r=document.createRange();r.selectNodeContents(p);const s=getSelection();s.removeAllRanges();s.addRange(r);JJ.beginSelection('repeat');return JJ.takeSelection('repeat')});
  assert.equal(repeated.sourceChapter,0);assert.ok(repeated.anchor.start>Array.from(texts[0].slice(0,texts[0].indexOf(repeated.anchor.quote))).length);
  assert.equal(repeated.anchor.quote,Array.from(texts[0]).slice(repeated.anchor.start,repeated.anchor.end).join(''));
  const emoji=Array.from(texts[0]).indexOf('👩');
  const unicode=await page.evaluate(cp=>JJ.forSection(0).anchor(cp,cp+1),emoji);assert.equal(unicode.quote,'👩‍🚀');
  const combined=Array.from(texts[0]).findIndex((c,i,a)=>c==='е'&&a[i+1]==='̈');
  assert.equal(await page.evaluate(cp=>JJ.forSection(0).anchor(cp,cp+1).quote,combined),'ё');
 }
 const cross=await page.evaluate(()=>{const a=document.querySelector('#jj-source-0 p').firstChild,b=document.querySelector('#jj-source-1 p').firstChild,r=document.createRange();r.setStart(a,0);r.setEnd(b,2);const s=getSelection();s.removeAllRanges();s.addRange(r);JJ.beginSelection('cross');return JJ.takeSelection('cross')});assert.equal(cross.crossSection,true);
 const shifts=[];for(let n=0;n<8;n++){await page.setViewportSize({width:n%2?430:1000,height:n%2?650:32});await restore(1,snap.anchor.start,snap.anchor.end);await page.evaluate(()=>JJ.viewport());await page.waitForTimeout(50);const v=await page.evaluate(()=>JJ.viewport());assert.equal(v.anchor.start,snap.anchor.start);assert.equal(v.sourceChapter,1);shifts.push(v.anchor.start)}
 await page.setViewportSize({width:1000,height:650});await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));await page.waitForTimeout(60);const end=await page.evaluate(()=>JJ.viewport());assert.equal(end.lastSourceChapter,texts.length-1);assert.equal(end.last,Array.from(texts.at(-1)).length);
 const serial=await page.evaluate(()=>JJ.context().navigationSerial);
 await page.locator('#jj-source-0 a').first().click();await page.waitForTimeout(60);
 assert.equal(await page.evaluate(()=>location.hash),'#jj-1-same');
 const afterLink=await page.evaluate(()=>scrollY);
 assert.equal(await page.evaluate(serial=>JJ.forSection(0).go(0,1,serial),serial),false);
 assert.equal(await page.evaluate(()=>scrollY),afterLink);
 assert.deepEqual(errors,[]);const result={status:'PASS',environment:'desktop Chromium; real Readium injection; Android NOT_RUN',sourceSections:texts.length,anchors,seam:{firstSource:seam.sourceChapter,lastSource:seam.lastSourceChapter,first:seam.first,last:seam.last},selection:{source:snap.sourceChapter,start:snap.anchor.start,end:snap.anchor.end},crossRejected:cross.crossSection,shifts,end:{section:end.lastSourceChapter,offset:end.last},generation:map.generation};fs.writeFileSync(path.join(out,'unified-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
