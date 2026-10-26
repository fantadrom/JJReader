// JJReader P0 bridge. DOM indices complement Readium's text-quote locator.
// This runs only in the current sanitized Readium resource; it exposes no Java interface.
(function () {
  if (window.JJ) return;
  const wrappers=document.documentElement.hasAttribute('data-jj-generation')?Array.from(document.querySelectorAll('body > section[data-jj-source]')):[];
  const documentId = (globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random();
  let interaction=0,navigationSerial=0;
  document.addEventListener('click',event=>{if(event.target instanceof Element && event.target.closest('a[href]')){navigationSerial++;interaction++;}},true);
  for(const event of ['pointerdown','touchstart','wheel'])document.addEventListener(event,()=>{interaction++;},{passive:true});
  function makeIndex(root,sourceChapter) {
  const blocks = new Set('p div section article h1 h2 h3 h4 h5 h6 li blockquote tr td figcaption pre br'.split(' '));
  const excluded = new Set('script style nav noscript iframe object svg math template'.split(' '));
  const chars = [], points = [];
  let selectionSession = null, selectionCache = null, restoreResult = null;
  let pinned=null,viewportSample=null,uiPin=null;
  function sep() {
    while (chars[chars.length - 1] === ' ') { chars.pop(); points.pop(); }
    if (chars.length && chars[chars.length - 1] !== '\n') { chars.push('\n', '\n'); points.push(null, null); }
  }
  function visit(n) {
    if (n.nodeType === 1) {
      if (excluded.has(n.localName) || n.hasAttribute('hidden') || n.getAttribute('aria-hidden') === 'true' || (n.id || '').startsWith('r2-decoration-')) return;
      if (blocks.has(n.localName)) sep();
      Array.from(n.childNodes).forEach(visit);
      if (blocks.has(n.localName)) sep();
    } else if (n.nodeType === 3) {
      let offset = 0;
      for (const c of n.data) {
        if (/^[\t\n\f\r \u00a0]$/.test(c)) {
          if (chars.length && ![' ', '\n'].includes(chars[chars.length - 1])) { chars.push(' '); points.push({n, s: offset, e: offset + c.length}); }
        } else { chars.push(c); points.push({n, s: offset, e: offset + c.length}); }
        offset += c.length;
      }
    }
  }
  visit(root);
  while ([' ', '\n'].includes(chars[chars.length - 1])) { chars.pop(); points.pop(); }
  function selector(e) {
    const parts = [];
    while (e && e.nodeType === 1) {
      let i = 1, p = e.previousElementSibling;
      while (p) { if (p.localName === e.localName) i++; p = p.previousElementSibling; }
      parts.unshift(e.localName + ':nth-of-type(' + i + ')');
      e = e.parentElement;
    }
    return parts.join(' > ');
  }
  function point(p, end) {
    return {cssSelector: selector(p.n.parentElement), textNodeIndex: Array.from(p.n.parentNode.childNodes).filter(x => x.nodeType === 3).indexOf(p.n), charOffset: end ? p.e : p.s};
  }
  function range(a, b) {
    while (a < b && !points[a]) a++;
    while (b > a && !points[b - 1]) b--;
    if (a >= b) return null;
    const r = document.createRange();
    r.setStart(points[a].n, points[a].s); r.setEnd(points[b - 1].n, points[b - 1].e);
    return {r, a, b};
  }
  let graphemes=null;
  function anchor(a, b) {
    if(typeof Intl.Segmenter==='function') {
      if(!graphemes){graphemes=[0];let offset=0;for(const item of new Intl.Segmenter('ru',{granularity:'grapheme'}).segment(chars.join(''))){offset+=Array.from(item.segment).length;graphemes.push(offset)}}
      function boundary(value,upper){let lo=0,hi=graphemes.length;while(lo<hi){const m=(lo+hi)>>1;if(graphemes[m]<value)lo=m+1;else hi=m}return graphemes[lo]===value?value:graphemes[upper?lo:Math.max(0,lo-1)]}
      a=boundary(a,false);b=boundary(b,true);
    }
    const x = range(a, b);
    if (!x) return null;
    const scope = x.r.commonAncestorContainer.nodeType === 1 ? x.r.commonAncestorContainer : x.r.commonAncestorContainer.parentElement;
    const before = document.createRange(); before.selectNodeContents(scope); before.setEnd(x.r.startContainer, x.r.startOffset);
    const after = document.createRange(); after.selectNodeContents(scope); after.setStart(x.r.endContainer, x.r.endOffset);
    return {start: x.a, end: x.b, quote: chars.slice(x.a, x.b).join(''),
      locatorText: {highlight: x.r.toString(), before: before.toString(), after: after.toString()},
      locations: {cssSelector: selector(scope), domRange: {start: point(points[x.a], false), end: point(points[x.b - 1], true)}}};
  }
  function fromRange(r) {
    let first = -1, last = -1;
    points.forEach((p, i) => {
      if (!p) return;
      // isPointInRange uses native DOM boundary positions, never quote searching.
      if (r.comparePoint(p.n,p.e)>=0 && r.comparePoint(p.n,p.s)<=0 &&
          !(r.startContainer===p.n && r.startOffset>=p.e) && !(r.endContainer===p.n && r.endOffset<=p.s)) {
        if (first < 0) first = i; last = i + 1;
      }
    });
    return first >= 0 ? anchor(first, last) : null;
  }
  function selection() {
    const s = window.getSelection();
    return s && s.rangeCount && !s.isCollapsed ? fromRange(s.getRangeAt(0)) : null;
  }
  if(sourceChapter===null)document.addEventListener('selectionchange', () => {
    const current = selection();
    if (current) selectionCache = current;
  });
  function context() {
    const p = document.querySelector('p') || document.body;
    return {documentId, url:document.URL, sourceChapter,navigationSerial,generation:document.documentElement.getAttribute('data-jj-generation'), count:chars.length, nativeReady:typeof window.readium === 'object',
      fontReady:!document.fonts || document.fonts.status==='loaded',documentReady:document.readyState!=='loading',resourcesReady:Array.from(document.images).every(i=>i.complete),fontSize:parseFloat(getComputedStyle(p).fontSize), fontScale:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--USER__fontSize')) / 100};
  }
  function beginSelection(session) { selectionSession=session; selectionCache=selection() || selectionCache; return context(); }
  function takeSelection(session) {
    if (selectionSession !== session) return null;
    const current=selection() || selectionCache;
    if (!current) return null;
    const native=window.readium && readium.getCurrentSelection ? readium.getCurrentSelection() : null;
    return Object.assign(context(), {anchor:current, session, readiumSelection:!!native});
  }
  function resolveDom(dom) {
    function resolve(p) {
      const e=document.querySelector(p.cssSelector);
      const n=e && Array.from(e.childNodes).filter(n=>n.nodeType===3)[p.textNodeIndex];
      if (!n || p.charOffset < 0 || p.charOffset > n.length) return null;
      return {n, offset:p.charOffset || 0};
    }
    try {
      const a=resolve(dom.start), b=resolve(dom.end || dom.start); if (!a || !b) return null;
      const r=document.createRange();r.setStart(a.n,a.offset);r.setEnd(b.n,b.offset);
      if (r.collapsed) r.setEnd(a.n,Math.min(a.n.length,a.offset+Array.from(a.n.data.slice(a.offset))[0].length));
      return fromRange(r);
    } catch (_) { return null; }
  }
  const textNodes=new Map();
  points.forEach((p,i)=>{if(p){if(!textNodes.has(p.n))textNodes.set(p.n,[]);textNodes.get(p.n).push(i)}});
  function visible() {
    let first=-1,last=-1;
    for(const [n,indices] of textNodes) {
      const r=document.createRange();r.selectNodeContents(n);
      if(!Array.from(r.getClientRects()).some(x=>x.bottom>0 && x.top<innerHeight && x.right>0 && x.left<innerWidth))continue;
      const rect=i=>{const p=points[indices[i]];r.setStart(n,p.s);r.setEnd(n,p.e);return r.getBoundingClientRect()};
      let lo=0,hi=indices.length;
      while(lo<hi){const mid=(lo+hi)>>1;if(rect(mid).bottom<=0)lo=mid+1;else hi=mid}
      let start=lo;lo=0;hi=indices.length;
      while(lo<hi){const mid=(lo+hi)>>1;if(rect(mid).top<innerHeight)lo=mid+1;else hi=mid}
      let end=lo;
      const seen=i=>{const x=rect(i);return x.width>0 && x.bottom>0 && x.top<innerHeight && x.right>0 && x.left<innerWidth};
      while(start<end && !seen(start))start++;while(end>start && !seen(end-1))end--;
      if(start<end){if(first<0)first=indices[start];last=indices[end-1]+1;}
    }
    return {first,last,count:chars.length};
  }
  function rectValue(rect) {
    return rect ? {left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom,width:rect.width,height:rect.height} : null;
  }
  function geometry(a,b) {
    const x=range(a,b);let r=null, firstOffset=null;
    // A collapsed line-break space may have zero width. Find the first readable glyph,
    // including the next glyph when the stored single code point itself is such a space.
    if(x)for(let i=x.a;i<Math.min(chars.length,Math.max(x.b,x.a+64));i++) {
      const first=range(i,i+1);
      r=first && Array.from(first.r.getClientRects()).find(r=>r.width>0 && r.height>0);
      if(r){firstOffset=i;break;}
    }
    const fontReady=!document.fonts || document.fonts.status==='loaded';
    const layoutReady=innerWidth>0 && innerHeight>0 && document.readyState!=='loading' && fontReady;
    return {viewportWidth:innerWidth,viewportHeight:innerHeight,scrollY,layoutReady,fontReady,
      firstOffset,documentReady:document.readyState!=='loading',firstRect:rectValue(r),rangeRect:rectValue(x && x.r.getBoundingClientRect()),
      shown:!!(layoutReady && r && r.bottom>0 && r.top<innerHeight && r.right>0 && r.left<innerWidth)};
  }
  function scrollTarget(a,b) {
    const g=geometry(a,b);
    if (!g.layoutReady || !g.firstRect) return false;
    // Align the first readable symbol, including viewports shorter than one text line.
    // No fixed top inset: repeatedly restoring a first-visible point must not walk backwards.
    if(Math.abs(g.firstRect.top)>1)window.scrollBy(0,g.firstRect.top);
    return true;
  }
  function go(a,b,expectedNavigation=null) {
    if(expectedNavigation!==null && expectedNavigation!==navigationSerial)return false;
    pinned=null;restoreResult=null;return scrollTarget(a,b);
  }
  // A chrome/brightness contact must not turn a confirmed mid-line point into line start.
  function beginUiContact(){uiPin=pinned && Math.abs(scrollY-pinned.scrollY)<1 && geometry(pinned.a,pinned.b).shown?{...pinned}:null;return {ok:!!uiPin}}
  function commitUiContact(){if(uiPin && Math.abs(scrollY-uiPin.scrollY)<1 && geometry(uiPin.a,uiPin.b).shown)pinned={...uiPin,interaction};uiPin=null;return {ok:true}}
  function cancelUiContact(){uiPin=null;return {ok:true}}
  function viewport() {
    const v=visible(), now=performance.now();
    const key=[innerWidth,innerHeight,scrollY,document.documentElement.scrollHeight].join(':');
    if (!viewportSample || viewportSample.key!==key) viewportSample={key,since:now};
    let target=v.first>=0 ? anchor(v.first,v.first+1) : null;
    if(pinned && pinned.interaction===interaction && Math.abs(scrollY-pinned.scrollY)<1 && geometry(pinned.a,pinned.b).shown)
      target=anchor(pinned.a,pinned.b);
    else pinned=null;
    const g=geometry(target ? target.start : 0,target ? target.end : 1);
    return Object.assign(context(),v,g,{anchor:target,interaction,restorePinned:!!pinned,layoutStable:g.layoutReady && now-viewportSample.since>=32});
  }
  function requestRestore(id,a,b) {
    pinned=null;
    if (!range(a,b)) { restoreResult=null;return false; }
    restoreResult={id,a,b,phase:'pending',began:performance.now(),interaction,sample:null,stable:0};
    scrollTarget(a,b); // Start immediately when layout permits; ACK still requires stable polling.
    return true; // Accepted request, not a visibility acknowledgement.
  }
  function cancelRestore(id) { if(restoreResult && restoreResult.id===id)restoreResult=null; }
  function restoreStatus() {
    const q=restoreResult;if(!q)return null;
    const now=performance.now();let g=geometry(q.a,q.b);
    if(q.phase==='pending' || q.phase==='layout_wait') {
      if(q.interaction!==interaction) {q.phase='failed';q.reason='RESTORE_INTERRUPTED';}
      else if(now-q.began>=4000) {
        q.phase='failed';q.reason=!g.layoutReady?'LAYOUT_TIMEOUT':!g.shown?'ANCHOR_NOT_VISIBLE':'LAYOUT_UNSTABLE';
      } else if(!g.layoutReady || !g.firstRect) {q.phase='layout_wait';q.stable=0;q.sample=null;}
      else {
        scrollTarget(q.a,q.b);g=geometry(q.a,q.b);
        const key=[g.viewportWidth,g.viewportHeight,g.scrollY,document.documentElement.scrollHeight,
          ...Object.values(g.firstRect || {}).map(x=>Math.round(x*2)/2)].join(':');
        if(!q.sample || q.sample.key!==key) {q.sample={key,time:now};q.stable=0;}
        else if(now-q.sample.time>=32) {q.sample.time=now;q.stable++;}
        q.phase='pending';
        if(g.shown && q.stable>=2) {
          q.phase='confirmed';pinned={a:q.a,b:q.b,interaction,scrollY};
        }
      }
    }
    return Object.assign(context(),g,{id:q.id,phase:q.phase,reason:q.reason || null,start:q.a,end:q.b,
      layoutStable:q.stable>=2,elapsedMs:now-q.began});
  }
  function anchorForId(id) {
    const element=document.getElementById(id);if(!element || !root.contains(element))return null;
    const r=document.createRange();r.selectNodeContents(element);const found=fromRange(r);
    if(found)return anchor(found.start,Math.min(found.start+1,found.end));
    const next=points.findIndex(p=>p && (element.compareDocumentPosition(p.n)&Node.DOCUMENT_POSITION_FOLLOWING));
    return next>=0?anchor(next,next+1):null;
  }
  function resolveSourceDom(dom) {
    const copy=JSON.parse(JSON.stringify(dom));
    for(const key of ['start','end'])if(copy[key]) {
      const selector=copy[key].cssSelector;
      const suffix=selector.replace(/^html[^>]*>\s*body[^>]*(?:>\s*)?/,'');
      copy[key].cssSelector='#'+root.firstElementChild.id+(suffix?' > '+suffix:'');
    }
    return resolveDom(copy);
  }
  return {text: chars.join(''),selection,visible,anchor,go,context,viewport,beginSelection,takeSelection,resolveDom,resolveSourceDom,
    requestRestore,restoreStatus,cancelRestore,geometry,fromRange,anchorForId,beginUiContact,commitUiContact,cancelUiContact};
  }
  if(!wrappers.length) {window.JJ=makeIndex(document.body,null);return;}
  const indexes=new Map();let active=0,session=null,cached=null,rootPin=null;
  function forSection(index) {
    if(!Number.isInteger(index) || !wrappers[index])throw Error('SOURCE_SECTION');
    active=index;
    if(indexes.has(index)){const value=indexes.get(index);indexes.delete(index);indexes.set(index,value);return value;}
    const value=makeIndex(wrappers[index],index);
    const request=value.requestRestore,go=value.go;
    value.requestRestore=(...args)=>{rootPin=index;return request(...args)};
    value.go=(...args)=>{rootPin=null;return go(...args)};
    indexes.set(index,value);
    if(indexes.size>8)indexes.delete(Array.from(indexes.keys()).find(i=>i!==rootPin));
    return value;
  }
  function sectionFor(node,offset,end) {
    if(node.nodeType!==3){node=node.childNodes[Math.min(node.childNodes.length-1,Math.max(0,offset-(end?1:0)))] || node;}
    const element=node.nodeType===1?node:node.parentElement;
    return element && element.closest('[data-jj-source]') ? Number(element.closest('[data-jj-source]').getAttribute('data-jj-source')) : -1;
  }
  function selection() {
    const s=getSelection();if(!s || !s.rangeCount || s.isCollapsed)return null;
    const r=s.getRangeAt(0),a=sectionFor(r.startContainer,r.startOffset,false),b=sectionFor(r.endContainer,r.endOffset,true);
    if(a<0 || b<0 || a!==b)return {crossSection:true};
    const anchor=forSection(a).fromRange(r);return anchor?{sourceChapter:a,anchor}:null;
  }
  document.addEventListener('selectionchange',()=>{const s=selection();if(s)cached=s;});
  function viewport() {
    const candidates=wrappers.map((w,i)=>({i,rect:w.getBoundingClientRect()})).filter(x=>x.rect.bottom>0 && x.rect.top<innerHeight);
    let first=null,last=null,pin=null;
    for(const candidate of candidates){const v=forSection(candidate.i).viewport();if(v.first>=0){if(!first)first=v;last=v;if(candidate.i===rootPin && v.restorePinned)pin=v;}}
    if(!first)return Object.assign(forSection(active).context(),{anchor:null,first:-1,last:-1,sourceChapter:active,lastSourceChapter:active,layoutReady:false,layoutStable:false,shown:false,viewportWidth:innerWidth,viewportHeight:innerHeight});
    if(!pin)rootPin=null;
    active=(pin || first).sourceChapter;
    return Object.assign({},pin || first,{first:first.first,firstSourceChapter:first.sourceChapter,last:last.last,lastSourceChapter:last.sourceChapter});
  }
  window.JJ={forSection,viewport,context:()=>forSection(active).context(),anchor:(a,b)=>forSection(active).anchor(a,b),
    go:(a,b)=>forSection(active).go(a,b),requestRestore:(...args)=>forSection(active).requestRestore(...args),restoreStatus:()=>forSection(active).restoreStatus(),
    beginSelection:id=>{session=id;cached=selection() || cached;return forSection(active).context()},
    takeSelection:id=>{if(id!==session)return null;const s=selection() || cached;if(!s)return null;return Object.assign(forSection(s.sourceChapter ?? active).context(),s,{session:id,readiumSelection:!!(window.readium && readium.getCurrentSelection && readium.getCurrentSelection())})},
    sectionTexts:()=>wrappers.map((_,i)=>forSection(i).text),selection,resolveDom:dom=>forSection(active).resolveSourceDom(dom)};
})();
