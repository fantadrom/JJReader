// Trusted companion to JJ, installed through Readium's public evaluateJavascript API.
(function(){
 if(!window.JJ || JJ.enableSections)return;
 let enabled=false,tail=null,last=null,queued=null,touch=null,busy=false,visual=null,layoutSample=null,lastScroll=-Infinity;
 let controls=false,readerAction=null,gesture=0;
 const context=JJ.context;
 const selection=()=>{const s=getSelection();return !!(s && !s.isCollapsed)};
 const blocked=e=>e instanceof Element && !!e.closest('a,button,input,textarea,[id^="r2-decoration-"],[data-jj-technical]');
 const maxScroll=()=>Math.max(0,document.documentElement.scrollHeight-innerHeight);
 function contentEnd(){
   let result=null;
   const walk=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
   while(walk.nextNode()){
     const n=walk.currentNode,e=n.nodeType===1?n:n.parentElement;
     if(!e || e.closest('[aria-hidden="true"],[hidden],script,style,nav,[data-jj-technical],[id^="r2-decoration-"]'))continue;
     let r=null,media=false;
     if(n.nodeType===3 && /\S/.test(n.data)){const end=n.data.search(/\s*$/),start=Math.max(0,end-(n.data.charCodeAt(end-1)>=0xDC00&&n.data.charCodeAt(end-1)<=0xDFFF?2:1));r=document.createRange();r.setStart(n,start);r.setEnd(n,end)}
     else if(n.nodeType===1 && n.localName==='img'){r=n;media=true}
     if(r){const rect=r.getBoundingClientRect();if(rect.width>0 && rect.height>0)result={r,media}}
   }
   return result;
 }
 function size(){
   if(!enabled || innerHeight<=0)return;
   last=contentEnd();const rect=last?.r.getBoundingClientRect();
   let previous=parseFloat(tail.style.height||'0'),documentHeight=document.documentElement.scrollHeight;
   if(rect && documentHeight<=innerHeight && previous===0){tail.style.height=innerHeight+'px';previous=innerHeight;documentHeight=document.documentElement.scrollHeight}
   const target=rect?Math.max(0,rect.top+scrollY,rect.bottom+scrollY-innerHeight):0;
   const height=rect?Math.max(0,previous+target+innerHeight-documentHeight):0;
   if(Math.abs(parseFloat(tail.style.height||'0')-height)>0.5)tail.style.height=height+'px';
 }
 function schedule(){requestAnimationFrame(size)}
 function edgeState(){return {atStart:scrollY<=2,atEnd:maxScroll()-scrollY<=2}}
 function enableSections(){
   if(enabled)return context();enabled=true;
   tail=document.createElement('div');tail.setAttribute('data-jj-technical','tail');tail.setAttribute('aria-hidden','true');tail.style.cssText='display:block!important;clear:both!important;padding:0!important;margin:0!important;pointer-events:none!important;width:1px!important';document.body.append(tail);
   addEventListener('resize',schedule);addEventListener('load',schedule,true);
   if(document.fonts)document.fonts.ready.then(schedule);
   new ResizeObserver(schedule).observe(document.body);setInterval(()=>{layoutSample=`${innerWidth}:${innerHeight}:${scrollY}`},40);size();
   document.addEventListener('scroll',()=>{lastScroll=performance.now()},{passive:true});
   document.addEventListener('touchstart',event=>{
     queued=null;readerAction=null;touch=null;JJ.cancelUiContact?.();
     if(busy || event.touches.length!==1 || selection() || blocked(event.target))return;
     if(controls)JJ.beginUiContact?.();
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
       if(Math.abs(dy)>=12){JJ.commitUiContact?.();touch.bright=true;readerAction={...context(),kind:'brightness',gesture:touch.gesture,delta:-dy/innerHeight,end:false}}
     }
   },{passive:false,capture:true});
   document.addEventListener('touchcancel',()=>{if(touch?.bright)readerAction={...context(),kind:'brightness',gesture:touch.gesture,delta:-touch.dy/innerHeight,end:true};touch=null},{passive:true,capture:true});
   document.addEventListener('touchend',event=>{
     const start=touch;touch=null;
     if(!start || selection() || busy || event.changedTouches.length!==1)return;
     const elapsed=performance.now()-start.time,p=event.changedTouches[0],dy=p.clientY-start.y,dx=p.clientX-start.x;
     if(start.left && (start.bright || Math.abs(dy)>=12&&elapsed<650)){
       if(event.cancelable)event.preventDefault();JJ.commitUiContact?.();readerAction={...context(),kind:'brightness',gesture:start.gesture,delta:-dy/innerHeight,end:true};return;
     }
     if(controls && elapsed<350 && Math.abs(dy)<8 && Math.abs(dx)<8){
       if(event.cancelable)event.preventDefault();JJ.commitUiContact?.();readerAction={...context(),kind:start.x>=innerWidth/3&&start.x<innerWidth*2/3?'panels':'page',direction:start.x<innerWidth/3?-1:1};return;
     }
     JJ.cancelUiContact?.();
     if(start.left || !start.edgeEligible || elapsed>650 || Math.abs(dy)<48 || Math.abs(dy)<Math.abs(dx)*1.5)return;
     const direction=dy<0 && start.atEnd?'next':dy>0 && start.atStart?'previous':null;
     if(direction){busy=true;queued={...context(),direction}}
   },{passive:false,capture:true});return context();
 }
 function requestVisualRestore(id,end){size();if(end!==null)scrollTo(0,end?maxScroll():0);visual={id,end,documentId:context().documentId,sample:null,time:performance.now()};return true}
 function visualStatus(){
   if(!visual)return null;size();if(visual.end!==null)scrollTo(0,visual.end?maxScroll():0);
   const key=[innerHeight,innerWidth,scrollY,document.documentElement.scrollHeight].join(':');
   if(visual.sample!==key){visual.sample=key;visual.time=performance.now()}
   const c=context(),ready=c.fontReady&&c.documentReady&&c.resourcesReady&&innerHeight>0&&innerWidth>0&&performance.now()-visual.time>=64;
   return {...c,id:visual.id,phase:ready?'confirmed':'pending',layoutStable:ready,shown:ready,viewportWidth:innerWidth,viewportHeight:innerHeight,end:visual.end};
 }
 JJ.enableSections=enableSections;
 JJ.takeEdge=()=>{const result=queued;queued=null;if(!result)busy=false;return result};
 JJ.blockEdges=()=>{busy=true;queued=null;touch=null;readerAction=null;return {ok:true}};
 JJ.edgeComplete=()=>{busy=false;touch=null;queued=null;return {ok:true}};
 JJ.configureInteractions=value=>{controls=!!value;return {ok:true}};
 JJ.takeReaderAction=()=>{const result=readerAction;readerAction=null;if(result)return result;const edge=JJ.takeEdge();return edge?{...edge,kind:'edge',direction:edge.direction==='next'?1:-1}:null};
 JJ.pageStep=direction=>{
   const state=edgeState();if(direction>0&&state.atEnd || direction<0&&state.atStart)return {resource:direction};
   const style=getComputedStyle(document.querySelector('p')||document.body),line=parseFloat(style.lineHeight)||parseFloat(style.fontSize)*1.4;
   document.dispatchEvent(new Event('wheel'));scrollBy(0,direction*Math.max(1,innerHeight-line));return {resource:0};
 };
 JJ.contentState=()=>({...context(),...edgeState(),lastMedia:!!last?.media,scrollY,maxScroll:maxScroll()});
 JJ.requestVisualRestore=requestVisualRestore;JJ.visualStatus=visualStatus;
 // Blank space after an illustration keeps a source-local visual position, never a fake quote.
 const viewport=JJ.viewport;
 JJ.viewport=()=>{const v=viewport();if(enabled && v.first<0){const c=JJ.contentState();return {...v,visual:true,visualEnd:c.atEnd,last:v.count,layoutStable:!!layoutSample&&layoutSample===`${innerWidth}:${innerHeight}:${scrollY}`,layoutReady:v.layoutReady,shown:v.layoutReady};}return v};
})();
