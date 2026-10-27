// Trusted companion to JJ, installed through Readium's public evaluateJavascript API.
(function(){
 if(!window.JJ || JJ.enableSections)return;
 let enabled=false,tail=null,last=null,queued=null,touch=null,busy=false,visual=null,layoutSample=null,lastScroll=-Infinity;
 let controls=false,readerAction=null,gesture=0,visualUiPin=null;
 const context=JJ.context;
 const selection=()=>{const s=getSelection();return !!(s && !s.isCollapsed)};
 const blocked=e=>e instanceof Element && !!e.closest('a,button,input,textarea,[id^="r2-decoration-"],[data-jj-technical]');
 const maxScroll=()=>Math.max(0,document.documentElement.scrollHeight-innerHeight);
 // Node references are scanned only on content mutation; document-space metrics only on reflow.
 let candidates=null,metrics=null,scans=0,visualPin=null;
 const epsilon=1;
 const excluded='[aria-hidden="true"],[hidden],script,style,nav,[data-jj-technical],[id^="r2-decoration-"]';
 function scanContent(){
   candidates=[];scans++;
   const walk=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
   while(walk.nextNode()){
     const n=walk.currentNode,e=n.nodeType===1?n:n.parentElement;
     if(!e || e.closest(excluded))continue;
     if(n.nodeType===3 && /\S/.test(n.data)){
       const start=n.data.search(/\S/),end=n.data.search(/\s*$/);
       const first=document.createRange(),last=document.createRange();
       first.setStart(n,start);first.setEnd(n,start+(n.data.codePointAt(start)>0xFFFF?2:1));
       last.setStart(n,Math.max(start,end-(n.data.charCodeAt(end-1)>=0xDC00&&n.data.charCodeAt(end-1)<=0xDFFF?2:1)));last.setEnd(n,end);
       candidates.push({first,last,element:e,media:false});
     }else if(n.nodeType===1 && ['img','table','hr'].includes(n.localName))candidates.push({first:n,last:n,element:n,media:true});
   }
 }
 function measure(){
   if(metrics)return metrics;
   if(!candidates)scanContent();
   let first=null,end=null,ordinary=null;
   for(const item of candidates){
     const style=getComputedStyle(item.element);if(style.display==='none'||style.visibility==='hidden')continue;
     const a=item.first.getBoundingClientRect(),b=item.last.getBoundingClientRect();
     if(a.width>0&&a.height>0&&(!first||a.top+scrollY<first.top))first={top:a.top+scrollY,bottom:a.bottom+scrollY};
     if(b.width>0&&b.height>0&&(!end||b.bottom+scrollY>=end.bottom))end={top:b.top+scrollY,bottom:b.bottom+scrollY,media:item.media};
     if(!ordinary&&!item.media){const block=item.element.closest('p,li,blockquote,td,figcaption,pre');if(block&&!block.closest('h1,h2,h3,h4,h5,h6')&&b.width>0&&b.height>0)ordinary=block}
   }
   // Prefer body copy, never the heading. CSS normal has no numeric used line-height.
   const style=getComputedStyle(ordinary||document.body),font=parseFloat(style.fontSize),line=parseFloat(style.lineHeight);
   const lineHeight=Number.isFinite(line)&&line>0?line:Number.isFinite(font)&&font>0?font*1.4:22.4;
   last=end;return metrics={first,end,lineHeight};
 }
 function size(){
   if(!enabled || innerHeight<=0)return;
   const m=measure(),rect=m.end;
   let previous=parseFloat(tail.style.height||'0'),documentHeight=document.documentElement.scrollHeight;
   if(rect && documentHeight<=innerHeight && previous===0){tail.style.height=innerHeight+'px';previous=innerHeight;documentHeight=document.documentElement.scrollHeight}
   const target=rect?Math.max(0,rect.top,rect.bottom+Math.min(4*m.lineHeight,innerHeight*.5)-innerHeight):0;
   const height=rect?Math.max(0,previous+target+innerHeight-documentHeight):0;
   if(Math.abs(parseFloat(tail.style.height||'0')-height)>0.5)tail.style.height=height+'px';
 }
 let scheduled=false;
 function schedule(rescan=false){metrics=null;if(rescan)candidates=null;if(scheduled)return;scheduled=true;requestAnimationFrame(()=>{scheduled=false;size()})}
 function tapState(){
   const m=measure(),top=m.first?.top??0,bottom=m.end?.bottom??0,free=innerHeight-(bottom-scrollY);
   return {contentTop:top,contentBottom:bottom,lineHeight:m.lineHeight,freeBelow:free,viewportWidth:innerWidth,viewportHeight:innerHeight,
     contentStartVisible:!m.first||top-scrollY>=-epsilon&&top-scrollY<innerHeight,
     contentEndVisible:!m.end||bottom-scrollY<=innerHeight+epsilon,
     tapAtStart:!m.first||top-scrollY>=-epsilon&&top-scrollY<innerHeight,
     tapAtEnd:!m.end||bottom-scrollY<=epsilon||bottom-scrollY<=innerHeight+epsilon&&free+epsilon>=m.lineHeight};
 }
 function boundaryGeometry(kind,move=false){
   size();const m=measure(),gap=Math.min(4*m.lineHeight,innerHeight*.5);
   const target=kind==='RESOURCE_START'?0:Math.max(0,Math.min(maxScroll(),(m.end?.bottom??0)+gap-innerHeight));
   if(move)scrollTo(0,target);
   return {...tapState(),scrollY,placement:kind,entryGap:gap,targetScrollY:target,
     shown:innerHeight>0&&innerWidth>0&&Math.abs(scrollY-target)<=epsilon};
 }
 function edgeState(){return {atStart:scrollY<=2,atEnd:maxScroll()-scrollY<=2}}
 function enableSections(){
   if(enabled)return context();enabled=true;
   tail=document.createElement('div');tail.setAttribute('data-jj-technical','tail');tail.setAttribute('aria-hidden','true');tail.style.cssText='display:block!important;clear:both!important;padding:0!important;margin:0!important;pointer-events:none!important;width:1px!important';document.body.append(tail);
   addEventListener('resize',()=>schedule());addEventListener('load',()=>schedule(),true);
   if(document.fonts)document.fonts.ready.then(()=>schedule());
   new ResizeObserver(()=>schedule()).observe(document.body);
   new MutationObserver(rows=>{if(rows.some(r=>{const e=r.target.nodeType===1?r.target:r.target.parentElement;return e&&!e.closest('[data-jj-technical],[id^="r2-decoration-"]')}))schedule(true)}).observe(document.documentElement,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-hidden','src']});setInterval(()=>{layoutSample=`${innerWidth}:${innerHeight}:${scrollY}`},40);size();
   document.addEventListener('scroll',()=>{lastScroll=performance.now()},{passive:true});
   document.addEventListener('touchstart',event=>{
     if(busy)return;
     queued=null;readerAction=null;touch=null;visualUiPin=null;JJ.cancelUiContact?.();
     if(event.touches.length!==1 || selection() || blocked(event.target))return;
     if(controls){JJ.beginUiContact?.();if(visualPin&&Math.abs(visualPin.scrollY-scrollY)<1&&boundaryGeometry(visualPin.placement.kind).shown)visualUiPin={...visualPin}}
     const p=event.touches[0];touch={x:p.clientX,y:p.clientY,time:performance.now(),gesture:String(++gesture),left:controls&&p.clientX<innerWidth*.1,bright:false,dy:0,edgeEligible:performance.now()-lastScroll>=120,...edgeState()};
   },{passive:true,capture:true});
   document.addEventListener('touchmove',event=>{
     if(!touch)return;
     if(event.touches.length!==1 || selection()){touch=null;return}
     if(touch.left){
       if(performance.now()-touch.time>500&&!touch.bright){touch=null;return}
       const p=event.touches[0],dy=p.clientY-touch.y,dx=p.clientX-touch.x;touch.dy=dy;
       if(Math.abs(dx)>12 && Math.abs(dx)>Math.abs(dy)*1.5){touch=null;return}
       // Reserve the left strip from the first movement; long press itself is untouched.
       if(event.cancelable)event.preventDefault();
       if(Math.abs(dy)>=12){commitUi();touch.bright=true;readerAction={...context(),kind:'brightness',gesture:touch.gesture,delta:-dy/innerHeight,end:false}}
     }
   },{passive:false,capture:true});
   document.addEventListener('touchcancel',()=>{if(touch?.bright)readerAction={...context(),kind:'brightness',gesture:touch.gesture,delta:-touch.dy/innerHeight,end:true};touch=null},{passive:true,capture:true});
   document.addEventListener('touchend',event=>{
     const start=touch;touch=null;
     if(!start || selection() || busy || event.changedTouches.length!==1)return;
     const elapsed=performance.now()-start.time,p=event.changedTouches[0],dy=p.clientY-start.y,dx=p.clientX-start.x;
     if(start.left && (start.bright || Math.abs(dy)>=12&&elapsed<650)){
       if(event.cancelable)event.preventDefault();commitUi();readerAction={...context(),kind:'brightness',gesture:start.gesture,delta:-dy/innerHeight,end:true};return;
     }
     if(controls && elapsed<350 && Math.abs(dy)<8 && Math.abs(dx)<8){
       if(event.cancelable)event.preventDefault();commitUi();const kind=start.x>=innerWidth/3&&start.x<innerWidth*2/3?'panels':'page';if(kind==='page')busy=true;readerAction={...context(),kind,direction:start.x<innerWidth/3?-1:1};return;
     }
     JJ.cancelUiContact?.();
     if(start.left || !start.edgeEligible || elapsed>650 || Math.abs(dy)<48 || Math.abs(dy)<Math.abs(dx)*1.5)return;
     const direction=dy<0 && start.atEnd?'next':dy>0 && start.atStart?'previous':null;
     if(direction){busy=true;queued={...context(),direction}}
   },{passive:false,capture:true});return context();
 }
 function commitUi(){JJ.commitUiContact?.();if(visualUiPin&&Math.abs(visualUiPin.scrollY-scrollY)<1&&boundaryGeometry(visualUiPin.placement.kind).shown)visualPin={...visualUiPin,interaction:JJ.interactionState()};visualUiPin=null}
 function endTop(){const r=measure().end;return r?Math.min(maxScroll(),Math.max(0,r.top,r.bottom-innerHeight)):0}
 function requestVisualRestore(id,end,placement=null){
   size();visualPin=null;
   if(placement)boundaryGeometry(placement.kind,true);else if(end!==null)scrollTo(0,end?endTop():0);
   visual={id,end,placement,interaction:JJ.interactionState(),documentId:context().documentId,sample:null,time:performance.now()};return true;
 }
 function visualStatus(){
   if(!visual)return null;
   const c=context();
   if(visual.interaction!==JJ.interactionState())return {...c,id:visual.id,phase:'failed',reason:'RESTORE_INTERRUPTED'};
   // A late status read must never move a confirmed viewport.
   if(!visual.confirmed){if(visual.placement)boundaryGeometry(visual.placement.kind,true);else if(visual.end!==null)scrollTo(0,visual.end?endTop():0)}
   const key=[innerHeight,innerWidth,scrollY,document.documentElement.scrollHeight].join(':');
   if(visual.sample!==key){visual.sample=key;visual.time=performance.now()}
   const ready=c.fontReady&&c.documentReady&&c.resourcesReady&&innerHeight>0&&innerWidth>0&&performance.now()-visual.time>=64;
   if(ready&&!visual.confirmed){visual.confirmed=true;if(visual.placement)visualPin={placement:visual.placement,interaction:JJ.interactionState(),scrollY}}
   return {...c,...(visual.placement?boundaryGeometry(visual.placement.kind):{}),id:visual.id,phase:ready?'confirmed':'pending',layoutStable:ready,shown:ready,viewportWidth:innerWidth,viewportHeight:innerHeight,end:visual.end};
 }
 JJ.enableSections=enableSections;
 JJ.takeEdge=()=>{const result=queued;queued=null;if(!result)busy=false;return result};
 JJ.blockEdges=()=>{busy=true;queued=null;touch=null;readerAction=null;return {ok:true}};
 JJ.edgeComplete=()=>{busy=false;touch=null;queued=null;return {ok:true}};
 JJ.configureInteractions=value=>{controls=!!value;return {ok:true}};
 JJ.takeReaderAction=()=>{const result=readerAction;readerAction=null;if(result)return result;const edge=JJ.takeEdge();return edge?{...edge,kind:'edge',direction:edge.direction==='next'?1:-1}:null};
 JJ.pageStep=direction=>{
   const state=tapState();
   if(direction>0&&state.tapAtEnd || direction<0&&state.tapAtStart)return {...context(),...state,resource:direction,placement:direction>0?'RESOURCE_START':'END_PAGE'};
   visualPin=null;JJ.cancelPlacement();document.dispatchEvent(new Event('wheel'));scrollBy(0,direction*Math.max(1,innerHeight-state.lineHeight));return {...context(),...state,resource:0,placement:'ANCHOR'};
 };
 JJ.contentState=()=>({...context(),...edgeState(),...tapState(),lastMedia:!!last?.media,scrollY,maxScroll:maxScroll(),metricScans:scans});
 JJ.boundaryGeometry=boundaryGeometry;
 JJ.requestVisualRestore=requestVisualRestore;JJ.visualStatus=visualStatus;
 // Blank space after an illustration keeps a source-local visual position, never a fake quote.
 const viewport=JJ.viewport;
 JJ.viewport=()=>{
   const v=viewport();
   const pin=visualPin&&visualPin.interaction===JJ.interactionState()&&Math.abs(visualPin.scrollY-scrollY)<1&&boundaryGeometry(visualPin.placement.kind).shown?visualPin:null;
   if(!pin)visualPin=null;
   if(enabled && v.first<0){const c=JJ.contentState();return {...v,visual:true,entryPlacement:v.entryPlacement||pin?.placement,visualEnd:c.atEnd||Math.abs(scrollY-endTop())<=epsilon,last:v.count,layoutStable:!!layoutSample&&layoutSample===`${innerWidth}:${innerHeight}:${scrollY}`,layoutReady:v.layoutReady,shown:v.layoutReady};}
   return v;
 };
})();
