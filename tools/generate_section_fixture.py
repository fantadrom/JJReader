"""Small synthetic P0.8 EPUB, including explicit footnote return, image-only and empty resources."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
from html import escape
root=Path(__file__).resolve().parents[1]
out=root/'app/src/test/resources/sections/p08-sections.epub';out.parent.mkdir(parents=True,exist_ok=True)
with ZipFile(root/'app/src/main/assets/p0-sample.epub') as z: image=z.read('EPUB/diagram.png')
bodies=[
 '<h1 id="same">Начало</h1>'+''.join(f'<p>Абзац {i}. Синтетический текст для вертикального чтения, пометок и проверки края.</p>' for i in range(25))+'<p id="call"><a href="s1.xhtml#note">Сноска в следующем разделе</a></p><p id="end">Последняя строка первого раздела.</p>',
 '<h1 id="same">Короткий раздел</h1><aside id="note"><p>Текст сноски. Вторая фраза сноски.</p><a href="s0.xhtml#call">Вернуться к вызову</a></aside><p>Последняя фраза раздела.</p>',
 '<img id="picture" src="diagram.png" alt="Синтетическая иллюстрация" style="width:100%;height:auto"/>',
 '',
 '<h1 id="same">Конец книги</h1><p>Финальная фраза. Последний символ Я</p>'
]
with ZipFile(out,'w',ZIP_DEFLATED) as z:
 z.writestr('mimetype','application/epub+zip',compress_type=ZIP_STORED)
 z.writestr('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
 manifest=''.join(f'<item id="s{i}" href="s{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(bodies)))
 spine=''.join(f'<itemref idref="s{i}"/>' for i in range(len(bodies)))
 z.writestr('content.opf',f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">p08-synthetic</dc:identifier><dc:title>Проверка разделов P0.8</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>{manifest}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="img" href="diagram.png" media-type="image/png"/></manifest><spine>{spine}</spine></package>')
 z.writestr('nav.xhtml','<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><ol>'+''.join(f'<li><a href="s{i}.xhtml">Раздел {i+1}</a></li>' for i in range(len(bodies)))+'</ol></nav></body></html>')
 z.writestr('diagram.png',image)
 for i,body in enumerate(bodies):z.writestr(f's{i}.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Синтетический раздел</title></head><body>'+body+'</body></html>')
print(out)
