// JJReader P0 bridge. DOM indices complement Readium's text-quote locator.
// This runs only in the current sanitized Readium resource; it exposes no Java interface.
(function () {
  if (window.JJ) return;
  const blocks = new Set('p div section article h1 h2 h3 h4 h5 h6 li blockquote tr td figcaption pre br'.split(' '));
  const excluded = new Set('script style nav noscript iframe object svg math template'.split(' '));
  const chars = [], points = [];
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
  function selection() {
    const s = window.getSelection();
    if (!s || !s.rangeCount || s.isCollapsed) return null;
    const r = s.getRangeAt(0);
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
  function go(a, b) {
    const x = range(a, b); if (!x) return false;
    const rect = x.r.getBoundingClientRect(); window.scrollBy(0, rect.top - 40); return true;
  }
  window.JJ = {text: chars.join(''), selection, visible, anchor, go};
})();
