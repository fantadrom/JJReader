// Linux/desktop Chromium evidence only. Does not claim an Android WebView or Honor PASS.
// All virtual Readium requests are fulfilled from local synthetic fixtures/AAR assets.
const {chromium}=require('playwright-core');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const assets=process.argv[2];const chrome=process.argv[3] || process.env.CHROMIUM_PATH;
const out=process.argv[4];
if(!assets || !chrome || !out)throw Error('Usage: node verify_browser_runtime.cjs <extracted-AAR-assets> <Chromium-executable> <output-directory>');
const fixtures=path.join(root,'app/build/xhtml-regression');
const bridge=fs.readFileSync(path.join(root,'app/src/main/assets/locator-bridge.js'),'utf8');
const meta=JSON.parse(fs.readFileSync(path.join(fixtures,'manifest.json'),'utf8'));
const decorations=JSON.parse(fs.readFileSync(path.join(fixtures,'decorations.json'),'utf8'));
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:chrome,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 const context=await browser.newContext({viewport:{width:430,height:650}});
 // Only the Java callback boundary is stubbed. CSS/layout/selection/decorations execute in Chromium.
 await context.addInitScript(()=>{
  window.Android={getViewportWidth:()=>innerWidth,log:()=>{},logError:()=>{},onDecorationActivated:()=>{},
   onDragEnd:()=>false,onDragMove:()=>false,onDragStart:()=>false,onKey:()=>false,onSelectionEnd:()=>{},onSelectionStart:()=>{},onTap:()=>false};
 });
 const errors=[],unexpected=[];
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.name+': '+e.message));
 page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url());
  if(url.hostname==='readium_assets'){
   const file=path.join(assets,decodeURIComponent(url.pathname));
   if(!file.startsWith(path.resolve(assets)+path.sep) || !fs.existsSync(file))return route.abort();
   const type=file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'font/otf';
   return route.fulfill({contentType:type,body:fs.readFileSync(file)});
  }
  if(url.hostname==='readium_package'){
   const name=path.basename(url.pathname).replace('.xhtml','.injected.xhtml');
   if(name==='diagram.png')return route.fulfill({contentType:'image/png',body:fs.readFileSync(path.join(out,'diagram.png'))});
   if(fs.existsSync(path.join(fixtures,name)))return route.fulfill({contentType:'application/xhtml+xml',body:fs.readFileSync(path.join(fixtures,name))});
  }
  unexpected.push(url.hostname+url.pathname);return route.abort();
 });
 const ready=async()=>{await page.waitForFunction(()=>typeof window.readium==='object');await page.evaluate(bridge);};
 const restore=async(id,a,b)=>{
  assert.equal(await page.evaluate(({id,a,b})=>JJ.requestRestore(id,a,b),{id,a,b}),true);
  await page.waitForFunction(id=>JJ.restoreStatus()?.id===id,id);
  assert.equal(await page.evaluate(()=>JJ.restoreStatus().shown),true);
 };
 try{
  await page.goto('https://readium_package/EPUB/c1.xhtml');await ready();
  assert.equal(await page.evaluate(()=>document.getElementsByTagNameNS('*','parsererror').length),0);
  assert.equal(await page.evaluate(()=>Array.from(document.images).every(i=>i.complete && i.naturalWidth>0)),true);
  const before=await page.locator('p').first().evaluate(p=>parseFloat(getComputedStyle(p).fontSize));
  await page.evaluate(()=>readium.setCSSProperties({'--USER__fontSize':'145%'}));
  await page.waitForFunction(before=>parseFloat(getComputedStyle(document.querySelector('p')).fontSize)>before*1.25,before);
  const after=await page.locator('p').first().evaluate(p=>parseFloat(getComputedStyle(p).fontSize));
  assert.ok(after>before*1.25);
  const snapshot=await page.evaluate(()=>{
   const n=document.getElementById('p1').firstChild, offset=n.data.indexOf('маяк');
   const r=document.createRange();r.setStart(n,offset);r.setEnd(n,offset+4);
   const s=getSelection();s.removeAllRanges();s.addRange(r);
   JJ.beginSelection('menu-1');s.removeAllRanges();
   const original=readium.getCurrentSelection;readium.getCurrentSelection=()=>null;
   const snap=JJ.takeSelection('menu-1');readium.getCurrentSelection=original;
   return snap;
  });
  assert.equal(snapshot.anchor.quote,'маяк');assert.equal(snapshot.readiumSelection,false);
  assert.equal(await page.evaluate(()=>JJ.takeSelection('old-menu')),null);
  assert.equal(Array.from(meta.fixtures.find(x=>x.file==='c1.injected.xhtml').normalized).slice(snapshot.anchor.start,snapshot.anchor.end).join(''),'маяк');
  await restore('middle',1500,1504);
  const saved=await page.evaluate(()=>JJ.viewport());assert.ok(saved.first>1000);
  const docId=saved.documentId;
  await page.reload();await ready();assert.notEqual(await page.evaluate(()=>JJ.context().documentId),docId);
  // Android restores the saved font preference before its text anchor; model the same order.
  await page.evaluate(()=>readium.setCSSProperties({'--USER__fontSize':'145%'}));
  await restore('restart',saved.anchor.start,saved.anchor.end);
  const afterRestart=await page.evaluate(()=>JJ.viewport());
  assert.ok(Math.abs(afterRestart.first-saved.first)<100,JSON.stringify({saved:saved.first,restored:afterRestart.first,target:saved.anchor.start}));
  await page.setViewportSize({width:720,height:400});
  await restore('rotate',saved.anchor.start,saved.anchor.end);
  assert.ok(Math.abs((await page.evaluate(()=>JJ.visible().first))-saved.first)<150);
  await page.evaluate(templates=>readium.registerDecorationTemplates(templates),decorations.templates);
  const anchor=await page.evaluate(()=>JJ.anchor(1500,1570));
  const decoration={...decorations.decoration,locator:{href:'EPUB/c1.xhtml',type:'application/xhtml+xml',locations:anchor.locations,text:anchor.locatorText}};
  await page.evaluate(d=>readium.getDecorations('tts').add(d),decoration);
  await page.waitForFunction(()=>Array.from(document.querySelectorAll('[id^="r2-decoration-"] *')).some(e=>parseFloat(getComputedStyle(e).borderBottomWidth)>=3 && e.getBoundingClientRect().width>0));
  const style=await page.evaluate(()=>Array.from(document.querySelectorAll('[id^="r2-decoration-"] *')).filter(e=>parseFloat(getComputedStyle(e).borderBottomWidth)>=3).map(e=>({color:getComputedStyle(e).borderBottomColor,width:getComputedStyle(e).borderBottomWidth,rect:e.getBoundingClientRect().toJSON()})));
  assert.ok(style.some(x=>x.color==='rgb(34, 103, 208)'));
  await page.screenshot({path:path.join(out,'chromium-underline.png')});
  await page.evaluate(()=>readium.getDecorations('tts').clear());
  assert.equal(await page.locator('[id^="r2-decoration-"]').count(),0);
  // A book's inline script remains forbidden; evaluation here stands in for native app code.
  const previousErrors=errors.length;
  await page.evaluate(()=>{window.bookScriptRan=false;const s=document.createElement('script');s.textContent='window.bookScriptRan=true';document.head.appendChild(s)});
  assert.equal(await page.evaluate(()=>window.bookScriptRan),false);
  const expectedBlock=errors.splice(previousErrors);
  assert.ok(expectedBlock.some(x=>x.includes('Content Security Policy')));
  assert.deepEqual(unexpected,[]);assert.deepEqual(errors,[]);
  const result={status:'PASS',environment:'desktop Linux Chromium; Java callbacks stubbed; NOT Android/Honor',font:{before,after},selection:'exact cached DOM range with empty native API',restore:'reload + resized viewport',decoration:{visible:true,styles:style},csp:'Readium loaded; book inline script blocked; no outbound requests',cases:6};
  fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exit(1)});
