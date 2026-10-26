// JJReader P0 bridge. DOM indices complement Readium's text-quote locator.
// This runs only in the current sanitized Readium resource; it exposes no Java interface.
(function () {
  if (window.JJ) return;
  const blocks = new Set('p div section article h1 h2 h3 h4 h5 h6 li blockquote tr td figcaption pre br'.split(' '));
  const excluded = new Set('script style nav noscript iframe object svg math template'.split(' '));
  const chars = [], points = [];
  const documentId = (globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random();
  let selectionSession = null, selectionCache = null, restoreResult = null;
  let interaction = 0, pinned = null, viewportSample = null;
  for (const event of ['pointerdown','touchstart','wheel']) document.addEventListener(event,()=>{interaction++;pinned=null;},{passive:true});
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
  visit(document.body);
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
  function anchor(a, b) {
    if (typeof Intl.Segmenter === 'function') {
      let offset = 0;
      for (const item of new Intl.Segmenter('ru', {granularity: 'grapheme'}).segment(chars.join(''))) {
        const end = offset + Array.from(item.segment).length;
        if (offset < a && a < end) a = offset;
        if (offset < b && b < end) b = end;
        offset = end;
      }
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
      if (r.isPointInRange(p.n, p.s) && r.comparePoint(p.n, p.e) <= 0) {
        if (first < 0) first = i; last = i + 1;
      }
    });
    return first >= 0 ? anchor(first, last) : null;
  }
  function selection() {
    const s = window.getSelection();
    return s && s.rangeCount && !s.isCollapsed ? fromRange(s.getRangeAt(0)) : null;
  }
  document.addEventListener('selectionchange', () => {
    const current = selection();
    if (current) selectionCache = current;
  });
  function context() {
    const p = document.querySelector('p') || document.body;
    return {documentId, url:document.URL, count:chars.length, nativeReady:typeof window.readium === 'object',
      fontSize:parseFloat(getComputedStyle(p).fontSize), fontScale:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--USER__fontSize')) / 100};
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
  function visible() {
    let first = -1, last = -1;
    const nodes = new Map();
    points.forEach((p, i) => { if (p) { if (!nodes.has(p.n)) nodes.set(p.n, []); nodes.get(p.n).push(i); } });
    nodes.forEach((indices, n) => {
      const r = document.createRange(); r.selectNodeContents(n);
      if (!Array.from(r.getClientRects()).some(x => x.bottom > 0 && x.top < innerHeight && x.right > 0 && x.left < innerWidth)) return;
      for (const i of indices) {
        const p = points[i]; r.setStart(n, p.s); r.setEnd(n, p.e);
        if (Array.from(r.getClientRects()).some(x => x.width > 0 && x.bottom > 0 && x.top < innerHeight && x.right > 0 && x.left < innerWidth)) {
          if (first < 0) first = i; last = i + 1;
        }
      }
    });
    return {first, last, count: chars.length};
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
  function go(a,b) { pinned=null;restoreResult=null;return scrollTarget(a,b); }
  function viewport() {
    const v=visible(), now=performance.now();
    const key=[innerWidth,innerHeight,scrollY,document.documentElement.scrollHeight].join(':');
    if (!viewportSample || viewportSample.key!==key) viewportSample={key,since:now};
    let target=v.first>=0 ? anchor(v.first,v.first+1) : null;
    if(pinned && pinned.interaction===interaction && Math.abs(scrollY-pinned.scrollY)<1 && geometry(pinned.a,pinned.b).shown)
      target=anchor(pinned.a,pinned.b);
    else pinned=null;
    const g=geometry(target ? target.start : 0,target ? target.end : 1);
    return Object.assign(context(),v,g,{anchor:target,interaction,layoutStable:g.layoutReady && now-viewportSample.since>=32});
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
  window.JJ = {text: chars.join(''), selection, visible, anchor, go, context, viewport, beginSelection, takeSelection, resolveDom,
    requestRestore, restoreStatus, cancelRestore, geometry};
})();
