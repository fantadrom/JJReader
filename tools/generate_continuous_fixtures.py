"""Synthetic EPUBs only; no literary/user text. Python 3 standard library."""
import argparse,base64,html,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=Path('work/unified-fixtures'));a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
pixel=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ9kAAAAASUVORK5CYII=')
def write(name,resources,extra=None):
 names=list(resources);manifest=''.join(f'<item id="c{i}" href="{n}" media-type="application/xhtml+xml"/>' for i,n in enumerate(names))
 manifest+=''.join(f'<item id="r{i}" href="{n}" media-type="'+('text/css' if n.endswith('.css') else 'image/png')+'"/>' for i,n in enumerate(extra or {}))
 spine=''.join(f'<itemref idref="c{i}"/>' for i in range(len(names)))
 nav='<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>'+''.join(f'<li><a href="{n}#same">Раздел {i+1}</a></li>' for i,n in enumerate(names))+'</ol></nav></body></html>'
 with zipfile.ZipFile(a.output/name,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('mimetype','application/epub+zip',compress_type=zipfile.ZIP_STORED)
  z.writestr('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
  z.writestr('content.opf',f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">synthetic-{name}</dc:identifier><dc:title>Синтетическая проверка {name}</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>{manifest}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>{spine}</spine></package>')
  z.writestr('nav.xhtml',nav)
  for n,text in resources.items():z.writestr(n,text)
  for n,text in (extra or {}).items():z.writestr(n,text)
def document(body,css=''):
 return f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ru"><head><title>Синтетический раздел</title>{css}</head><body>{body}</body></html>'
write('small.epub',{
 'a/one.xhtml':document('<h1 id="same">Первый</h1><p class="repeat">Одинаковая цитата. Ёж\u00a0и е\u0308лка 👩‍🚀.</p><p>Одинаковая <b>цитата</b>.</p><p><a href="../b/two.xhtml#same">Второй раздел</a> <a epub:type="noteref" href="#note">Сноска</a></p><aside id="note"><p>Локальная сноска.</p></aside><img src="../res/pixel.png"/>','<link rel="stylesheet" href="../css/a.css"/>'),
 'b/two.xhtml':document('<h1 id="same">Второй</h1><p class="repeat">Одинаковая цитата. 😀 Кириллица.</p><p>Длинный абзац. '+('Повторяемый текст рядом с границей. '*25)+'</p><a href="../a/one.xhtml#same">Назад</a>','<link rel="stylesheet" href="../css/b.css"/>'),
 'b/empty.xhtml':document(''),
 'c/three.xhtml':document('<h1 id="same">Третий</h1><p>Конец книги. Одинаковая цитата.</p>')},
 {'css/a.css':'.repeat{color:rgb(180,0,0)} body{margin:90px} #same{font-style:italic} @media screen{p{line-height:1.6}}', 'css/b.css':'.repeat{color:rgb(0,0,180)} p{page-break-before:always} body{margin:200px}', 'res/pixel.png':pixel})
for size,count,label in [(1_000_000,80,'1m'),(3_000_000,200,'3m')]:
 resources={};paragraph='Синтетическая строка для измерений. Ёж, е\u0308лка и 😀. Одинаковая цитата повторяется здесь. '
 for i in range(count):
  budget=size//count;paras=[];length=0
  while length<budget:
   value=f'Абзац {len(paras)+1}. '+paragraph*3;paras.append('<p>'+html.escape(value)+'</p>');length+=len(value)+2
  resources[f'text/c{i:03}.xhtml']=document(f'<h1 id="same">Раздел {i+1}</h1>'+''.join(paras))
 write(label+'.epub',resources)
print(json.dumps({'output':str(a.output),'fixtures':['small.epub','1m.epub','3m.epub']},ensure_ascii=False))
