"""Two logical TOC chapters across three resources, including a boundary inside r1.xhtml."""
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_STORED,ZIP_DEFLATED
root=Path(__file__).resolve().parents[1]
a=''.join(f'<p>Альфа, абзац {i}. Повторное слово и ещё одно слово. Русский текст е́ж 👩‍💻 для контекста.</p>' for i in range(12))
b=''.join(f'<p>Продолжение Альфы {i}. Текст до логической границы главы.</p>' for i in range(8))
c=''.join(f'<p>Бета, абзац {i}. Свой контекст и повторное слово.</p>' for i in range(8))
bodies=['<h1 id="alpha">Логическая Альфа</h1>'+a+'<p>Конец первого документа, но не главы: граница</p>',b+'<h1 id="beta">Логическая Бета</h1>'+c,'<h2>Продолжение Беты</h2>'+c+'<p>Последняя строка Я</p>']
out=root/'app/src/test/resources/notes/p2-logical.epub';out.parent.mkdir(parents=True,exist_ok=True)
with ZipFile(out,'w') as z:
 def put(name,value,stored=False):
  i=ZipInfo(name,(2026,1,1,0,0,0));i.compress_type=ZIP_STORED if stored else ZIP_DEFLATED;z.writestr(i,value)
 put('mimetype','application/epub+zip',True)
 put('META-INF/container.xml','<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
 items=''.join(f'<item id="r{i}" href="r{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(3));spine=''.join(f'<itemref idref="r{i}"/>' for i in range(3))
 put('content.opf',f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">p2-logical-synthetic</dc:identifier><dc:title>Логические главы P2</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest>{items}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>{spine}</spine></package>')
 put('nav.xhtml','<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><ol><li><a href="r0.xhtml">Часть</a><ol><li><a href="r0.xhtml#alpha">Логическая Альфа</a></li><li><a href="r1.xhtml#beta">Логическая Бета</a></li></ol></li></ol></nav></body></html>')
 for i,body in enumerate(bodies):put(f'r{i}.xhtml','<html xmlns="http://www.w3.org/1999/xhtml"><head><title>P2</title></head><body>'+body+'</body></html>')
debug=root/'app/src/debug/assets/p2-logical.epub';debug.parent.mkdir(parents=True,exist_ok=True);debug.write_bytes(out.read_bytes());print(out)
