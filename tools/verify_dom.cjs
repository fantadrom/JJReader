// Node DOM tests, deliberately not presented as Android/WebView runtime checks.
// Pass the Readium 3.4.0 readium-reflowable.js path as the first argument, or set READIUM_SCRIPT.
const {JSDOM} = require('jsdom');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const engine = process.argv[2] || process.env.READIUM_SCRIPT;
if (!engine) throw Error('Supply the Readium 3.4.0 readium-reflowable.js path');
const bridge = fs.readFileSync(path.join(root, 'app/src/main/assets/locator-bridge.js'), 'utf8');
let checks = 0;
function page(body, test) {
  const dom = new JSDOM('<html><head></head><body>' + body + '</body></html>', {
    runScripts:'outside-only', url:'https://readium_package/c1.xhtml', pretendToBeVisual:true
  });
  const w = dom.window;
  let captured;
  w.Range.prototype.getBoundingClientRect = function() {
    captured = this.cloneRange();
    return {x:0,y:0,left:0,top:0,right:100,bottom:20,width:100,height:20};
  };
  Object.defineProperty(w.document, 'scrollingElement', {value:w.document.documentElement});
  w.scrollTo=()=>{}; w.scrollBy=()=>{};
  w.eval(fs.readFileSync(engine,'utf8')); w.eval(bridge);
  try { test(w, () => captured); checks++; } finally { w.close(); }
}
page('<p>Первый маяк, второй маяк.</p><p>маяк снова.</p>', (w, captured) => {
  const anchor = JSON.parse(JSON.stringify(w.JJ.anchor(20,24)));
  assert.equal(anchor.quote, 'маяк');
  // Actual bundled Readium JS must select the second occurrence in this same paragraph.
  w.readium.scrollToLocator({href:'c1.xhtml',type:'application/xhtml+xml',locations:anchor.locations,text:anchor.locatorText});
  assert.equal(captured().toString(),'маяк'); assert.equal(captured().startOffset,20);
  // Bridge selection maps the actual DOM range back to exactly those canonical offsets.
  const selection=w.getSelection(); selection.removeAllRanges(); selection.addRange(captured());
  assert.equal(w.JJ.selection().start,20); assert.equal(w.JJ.selection().end,24);
});
page('<p>маяк</p><p>маяк</p>', (w,captured) => {
  const a=w.JJ.anchor(6,10);
  w.readium.scrollToLocator({locations:a.locations,text:a.locatorText});
  assert.equal(captured().startContainer.parentElement,w.document.querySelectorAll('p')[1]);
});
page('<h1>Глава</h1><p> Раз <b>два</b>\u00a0 три </p><div><p>Конец</p></div><nav>служебное</nav><img alt="не считать"/>', w => {
  assert.equal(w.JJ.text,'Глава\n\nРаз два три\n\nКонец');
});
page('<p>А👩‍🚀е\u0308Я</p>', w => {
  assert.equal(w.JJ.anchor(2,3).quote,'👩‍🚀');
  assert.equal(w.JJ.anchor(5,6).quote,'е\u0308');
});
page('<p>Первая строка.</p><p>Вторая строка.</p>', (w,captured) => {
  const a=w.JJ.anchor(7,23);
  w.readium.scrollToLocator({locations:a.locations,text:a.locatorText});
  assert.equal(captured().toString(),a.locatorText.highlight);
  assert.equal(a.quote,'строка.\n\nВторая ');
});
console.log(JSON.stringify({status:'PASS',checks,environment:'Node + jsdom + real Readium 3.4.0 JS; not Android'}));
