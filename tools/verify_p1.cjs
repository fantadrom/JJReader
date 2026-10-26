const {chromium}=require('playwright-core'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const [assets,chrome,out]=process.argv.slice(2);if(!out)throw Error('verify_p1.cjs <AAR assets> <Chromium> <output>');fs.mkdirSync(out,{recursive:true});
const root=path.resolve(__dirname,'../app/build/section-regression/small'),summary=JSON.parse(fs.readFileSync(path.join(root,'summary.json')));
const js=['locator-bridge.js','section-navigation.js'].map(n=>fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets',n),'utf8')).join('\n');
(async()=>{const browser=await chromium.launch({executablePath:chrome,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});try{
 const context=await browser.newContext({viewport:{width:430,height:500},hasTouch:true});await context.addInitScript(()=>{window.Android={getViewportWidth:()=>innerWidth,log:()=>{},logError:()=>{},onDecorationActivated:()=>{},onDragEnd:()=>false,onDragMove:()=>false,onDragStart:()=>false,onKey:()=>false,onSelectionEnd:()=>{},onSelectionStart:()=>{},onTap:()=>false}});
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async r=>{const u=new URL(r.request().url());let file;if(u.hostname==='readium_assets')file=path.join(assets,u.pathname);else if(u.hostname==='readium_package'){const index=summary.sections.findIndex(s=>'/'+s.href===u.pathname);file=index>=0?path.join(root,'section-'+index+'.xhtml'):path.join(root,'source',decodeURIComponent(u.pathname))}if(!file||!fs.existsSync(file))throw Error('Unexpected synthetic resource');await r.fulfill({body:fs.readFileSync(file),contentType:file.endsWith('.xhtml')?'application/xhtml+xml':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'application/octet-stream'})});
 async function install(index){await page.waitForFunction(()=>typeof readium==='object');await page.evaluate(js);await page.evaluate(()=>JJ.enableSections());await page.waitForTimeout(160);assert.equal(await page.evaluate(()=>JJ.text),summary.sections[index].text)}
 async function open(i){await page.goto('https://readium_package/'+summary.sections[i].href);await install(i)}
 async function finger(start,end,options={}){await page.evaluate(({start,end,options})=>{const target=options.link?document.querySelector('a'):document.body;function touch(y){return new Touch({identifier:1,target,clientX:100,clientY:y})}const a=touch(start),b=touch(end);target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,touches:options.multi?[a,a]:[a],changedTouches:[a]}));if(options.reach)scrollTo(0,document.documentElement.scrollHeight);target.dispatchEvent(new TouchEvent(options.cancel?'touchcancel':'touchend',{bubbles:true,touches:[],changedTouches:[b]}));},{start,end,options});return page.evaluate(()=>JJ.takeEdge())}
 await open(0);assert.equal(await finger(300,100,{reach:true}),null);assert.equal(await page.evaluate(()=>JJ.takeEdge()),null);await page.waitForTimeout(170);
 const end=await page.evaluate(()=>({state:JJ.contentState(),rect:JJ.geometry(JJ.text.length-1,JJ.text.length).firstRect,text:JJ.text}));assert.ok(end.rect.top>=-2&&end.rect.top<3,JSON.stringify(end.rect));assert.ok(end.state.atEnd);
 assert.equal((await finger(300,180)).direction,'next');assert.equal(await finger(300,180),null);await page.evaluate(()=>JJ.edgeComplete());
 assert.equal(await finger(300,180,{multi:true}),null);assert.equal(await finger(300,180,{cancel:true}),null);assert.equal(await finger(300,180,{link:true}),null);
 await page.evaluate(()=>{const n=document.querySelector('p').firstChild,r=document.createRange();r.setStart(n,0);r.setEnd(n,5);const s=getSelection();s.removeAllRanges();s.addRange(r)});assert.equal(await finger(300,180),null);await page.evaluate(()=>getSelection().removeAllRanges());
 const drift=[];for(let i=0;i<6;i++){await page.setViewportSize({width:i%2?430:1000,height:i%2?500:160});await page.waitForTimeout(130);await page.evaluate(()=>{JJ.requestRestore('end',JJ.text.length-1,JJ.text.length)});await page.waitForFunction(()=>JJ.restoreStatus()?.phase==='confirmed');const v=await page.evaluate(()=>JJ.viewport());drift.push(v.anchor.start);assert.equal(v.last,Array.from(summary.sections[0].text).length)}assert.equal(new Set(drift).size,1);
 await page.evaluate(()=>{JJ.blockEdges()});assert.equal(await page.evaluate(()=>JJ.takeEdge()),null);await page.evaluate(()=>JJ.edgeComplete());
 await open(1);assert.equal((await finger(160,300)).direction,'previous');await page.evaluate(()=>JJ.edgeComplete());
 await page.locator('a').click();await install(0);assert.equal(new URL(page.url()).hash,'#call');await page.locator('#call a').click();await install(1);assert.equal(new URL(page.url()).hash,'#note');
 await open(2);assert.ok(await page.locator('img').evaluate(i=>i.complete&&i.naturalWidth>0));await page.evaluate(()=>JJ.requestVisualRestore('image',true));await page.waitForFunction(()=>JJ.visualStatus()?.phase==='confirmed');assert.equal(await page.evaluate(()=>JJ.text),'');
 await open(3);await page.evaluate(()=>JJ.requestVisualRestore('empty',false));await page.waitForFunction(()=>JJ.visualStatus()?.phase==='confirmed');assert.equal(await page.evaluate(()=>JJ.text),'');

 await open(0);await page.evaluate(()=>JJ.configureInteractions(true));
 async function control(x,start,end,{select=false,long=false}={}){
   return page.evaluate(async({x,start,end,select,long})=>{
     if(select){const n=document.querySelector('p').firstChild,r=document.createRange();r.setStart(n,0);r.setEnd(n,5);getSelection().removeAllRanges();getSelection().addRange(r)}
     const target=document.body,p=y=>new Touch({identifier:99,target,clientX:x,clientY:y});
     target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[p(start)],changedTouches:[p(start)]}));
     if(long)await new Promise(r=>setTimeout(r,700));
     let prevented=false;
     if(start!==end&&!long)prevented=!target.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[p(end)],changedTouches:[p(end)]}));
     target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[p(end)]}));
     const action=JJ.takeReaderAction(),edge=JJ.takeEdge();getSelection().removeAllRanges();return {action,edge,prevented,scrollY};
   },{x,start,end,select,long});
 }
 await page.evaluate(()=>scrollTo(0,document.documentElement.scrollHeight));await page.waitForTimeout(180);const originalY=await page.evaluate(()=>scrollY);
 const brightness=await control(10,300,100);assert.equal(brightness.action.kind,'brightness');assert.equal(brightness.action.end,true);assert.ok(brightness.prevented);assert.equal(brightness.edge,null);assert.equal(brightness.scrollY,originalY);
 assert.equal((await control(10,300,100,{select:true})).action,null);assert.equal((await control(10,300,100,{long:true})).action,null);
 assert.equal((await control(210,150,150)).action.kind,'panels');assert.equal((await control(400,150,150)).action.kind,'page');
 await page.evaluate(()=>scrollTo(0,0));const step=await page.evaluate(()=>{const line=parseFloat(getComputedStyle(document.querySelector('p')).lineHeight);return {result:JJ.pageStep(1),expected:innerHeight-line,y:scrollY}});assert.equal(step.result.resource,0);assert.ok(Math.abs(step.y-step.expected)<3);
 const panelPositions=[];await page.evaluate(()=>JJ.requestRestore('panel-start',233,234));await page.waitForFunction(()=>JJ.restoreStatus()?.phase==='confirmed');
 for(const h of [380,500,160,380,500,160]){const x=await page.evaluate(()=>innerWidth/2);assert.equal((await control(x,80,80)).action.kind,'panels');const current=(await page.evaluate(()=>JJ.viewport())).anchor.start;assert.equal(current,233);await page.setViewportSize({width:h===160?1000:430,height:h});await page.evaluate(current=>JJ.requestRestore('panel',current,current+1),current);await page.waitForFunction(()=>JJ.restoreStatus()?.phase==='confirmed');panelPositions.push((await page.evaluate(()=>JJ.viewport())).anchor.start)}
 assert.equal(new Set(panelPositions).size,1);
 await page.setViewportSize({width:430,height:500});await page.locator('#call a').tap();await install(1);assert.equal(new URL(page.url()).hash,'#note');await page.evaluate(()=>JJ.configureInteractions(true));await page.locator('a').tap();await install(0);assert.equal(new URL(page.url()).hash,'#call');
 assert.deepEqual(errors,[]);const result={status:'PASS',runtime:'desktop Chromium '+browser.version()+'; real Readium assets/injection, simulated DOM touches; Android gestures NOT_RUN',thresholdCssPx:48,p1Checks:['brightness reserves left strip/never edge or scroll','selection and long press priority','center/side tap zones','page overlap','six panel-height reflows','touch links and explicit return'],panelPositions,checks:['reach-only/no-inertia','new-intent/one-edge','selection/link/multiple/cancel','last-line-at-top','six-reflows/no-drift','short/previous','footnote/explicit-return','image-only/empty visual ACK','canonical-text unchanged'],drift};fs.writeFileSync(path.join(out,'p1.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
