"""Synthetic P1.1 content boundaries. No user EPUB is used."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED
root=Path(__file__).resolve().parents[1]
with ZipFile(root/'app/src/main/assets/p0-sample.epub') as z:image=z.read('EPUB/diagram.png')
long=''.join(f'<p>Абзац {i}. Проверяем последовательное чтение назад и содержательные границы ресурса.</p>' for i in range(35))
bodies=[
 '<h1>Начало длинного раздела</h1>'+long+'<p id="end">Последняя строка Я</p>',
 '<h1>Короткий раздел</h1><p id="end">Всего одна фраза Я</p>',
 '<img id="cover" src="diagram.png" style="width:100%;height:720px"/><h1>Заголовок после изображения</h1><p>Текст после обложки Я</p>',
 '<h1>Иллюстрация в конце</h1>'+long+'<p>Последний текст Я</p><img id="trailer" src="diagram.png" style="width:100%;height:900px"/>',
 '<h1>Таблица и подпись в конце</h1>'+long+'<table style="width:100%;border:12px solid black"><tr><td style="padding:30px">Текст таблицы</td></tr></table><p id="end">Подпись Я</p>',
 '<img id="only" src="diagram.png" style="width:100%;height:900px"/>',
 '',
 '<h1>Финальный раздел</h1><p>Последний символ книги Я</p>'
]
out=root/'app/src/test/resources/sections/p11-taps.epub';out.parent.mkdir(parents=True,exist_ok=True)
# Stable ZIP timestamps make regeneration byte-identical.
from zipfile import ZipInfo
with ZipFile(out,'w') as z:
 def put(name,value,stored=False):
  info=ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=ZIP_STORED if stored else ZIP_DEFLATED;z.writestr(info,value)
 put('mimetype','application/epub+zip',True)
 put('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
 manifest=''.join(f'<item id="s{i}" href="s{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(bodies)))
 spine=''.join(f'<itemref idref="s{i}"/>' for i in range(len(bodies)))
 put('content.opf',f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">p11-synthetic</dc:identifier><dc:title>Проверка касаний P1.1</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>{manifest}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="img" href="diagram.png" media-type="image/png"/></manifest><spine>{spine}</spine></package>')
 put('nav.xhtml','<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><ol>'+''.join(f'<li><a href="s{i}.xhtml">Раздел {i+1}</a></li>' for i in range(len(bodies)))+'</ol></nav></body></html>')
 put('diagram.png',image)
 for i,body in enumerate(bodies):put(f's{i}.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Синтетический раздел</title><style>body{padding-top:18px!important}h1{margin-top:14px}p{font-size:18px;line-height:26px}</style></head><body>'+body+'</body></html>')
debug=root/'app/src/debug/assets/p11-taps.epub';debug.parent.mkdir(parents=True,exist_ok=True);debug.write_bytes(out.read_bytes())
print(out)
