"""Deterministic resource preparation; original branding is never overwritten. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED
import io, hashlib
ROOT = Path(__file__).resolve().parents[1]
res = ROOT / 'app/src/main/res'
source = Image.open(ROOT / 'design/branding/launcher_icon_source.png').convert('RGB')
for density, size, foreground in [('mdpi',48,108),('hdpi',72,162),('xhdpi',96,216),('xxhdpi',144,324),('xxxhdpi',192,432)]:
    folder = res / f'mipmap-{density}'; folder.mkdir(exist_ok=True)
    legacy = Image.new('RGB',(size,size),'#0b0f14')
    side = round(size*.88); legacy.paste(source.resize((side,side),Image.Resampling.LANCZOS),((size-side)//2,)*2)
    for name in ['ic_launcher','ic_launcher_round']:
        (folder / f'{name}.webp').unlink(missing_ok=True)
        legacy.save(folder / f'{name}.png')
    folder = res / f'drawable-{density}'; folder.mkdir(exist_ok=True)
    canvas = Image.new('RGBA',(foreground,foreground),(0,0,0,0))
    side = round(foreground*.56)
    canvas.paste(source.resize((side,side),Image.Resampling.LANCZOS),((foreground-side)//2,)*2)
    canvas.save(folder / 'jj_launcher_foreground.png')
for name in ['ic_launcher','ic_launcher_round']:
    (res/'mipmap-anydpi'/f'{name}.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/jj_icon_background" />
    <foreground android:drawable="@drawable/jj_launcher_foreground" />
</adaptive-icon>
''')
for name in ['ic_launcher_foreground.xml','ic_launcher_background.xml']:
    (res/'drawable'/name).unlink(missing_ok=True)
# A neutral diagram used only as an EPUB illustration, no book artwork.
picture=Image.new('RGB',(480,160),'#e1e8ed'); draw=ImageDraw.Draw(picture)
for i,color in enumerate(['#356aa0','#d6a235','#577d5b']): draw.rectangle((40+i*140,30,145+i*140,130),fill=color)
image_bytes=io.BytesIO();picture.save(image_bytes,format='PNG')
chapters=[
('Утро у станции',[
'Контрольное первое вхождение: маяк. Эта пометка должна оставаться здесь после увеличения шрифта.',
'Утро было тихим. Поезд остановился у станции. Светлана открыла блокнот. Дмитрий проверил часы. Дальше начинается длинный абзац для выделения через несколько строк.',
' '.join(['На столе лежала карта; синий маяк отмечал поворот дороги. Через окно был виден сад, и ветер шевелил листья.']*20),
'Короткий абзац.',
'Контрольное второе вхождение: маяк. Оно находится в том же ресурсе, но пометка первого слова не должна вести сюда.',
'И. И. Иванов приехал в г. Бишкек. На ул. Садовой он встретил друга. — Подожди… — сказал он. — Сейчас вернусь!',
'Юникод: ёж, е\u0308лка, 🧭, 👩\u200d🚀, неразрывный\u00a0пробел. Символы должны сохраняться.',
'КОНЕЦ ПЕРВОГО РЕСУРСА. Следующий начинается словами НАЧАЛО ВТОРОГО РЕСУРСА.']),
('Дорога через сад',[
'НАЧАЛО ВТОРОГО РЕСУРСА. Проверьте переход без потери последних слов предыдущего раздела.',
'Контрольное третье вхождение: маяк. Это уже другой ресурс.',
'Они прошли через сад. Дорожка поднялась на холм. На вершине стоял дом. Окно было открыто. Музыка звучала негромко.',
' '.join(['Длинный абзац во втором разделе помогает проверить прокрутку, выделение за экран и положение после поворота.']*25),
'КОНЕЦ ВТОРОГО РЕСУРСА.']),
('Возвращение',[
'НАЧАЛО ТРЕТЬЕГО РЕСУРСА. Последний маяк стоял у реки.',
'Небо потемнело. Они вернулись на станцию. Поезд ждал у платформы. Блокнот остался в кармане. Завершена проверка пяти фраз.',
'Последний читаемый символ книги — Я'])]
from html import escape
manifest=''.join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1,4))
nav=''.join(f'<li><a href="c{i}.xhtml">{escape(title)}</a></li>' for i,(title,_) in enumerate(chapters,1))
files={
'META-INF/container.xml':'<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>',
'EPUB/package.opf':f'''<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:6bc556bb-6120-4a51-8ff4-49710fe11000</dc:identifier><dc:title>JJReader — синтетическая проверка P0</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-10-25T00:00:00Z</meta></metadata><manifest>{manifest}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="picture" href="diagram.png" media-type="image/png"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine></package>''',
'EPUB/nav.xhtml':f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Оглавление</title></head><body><nav epub:type="toc"><h1>Оглавление</h1><ol>{nav}</ol></nav></body></html>',
'EPUB/diagram.png':image_bytes.getvalue()}
for i,(title,paragraphs) in enumerate(chapters,1):
    paras=''.join(f'<p id="p{j}">{escape(p)}</p>' for j,p in enumerate(paragraphs,1))
    if i==1: paras=paras.replace('<p id="p4">','<figure><img src="diagram.png" alt="Альтернативный текст не входит в процент"/></figure><p id="p4">')
    files[f'EPUB/c{i}.xhtml']=f'<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>{escape(title)}</title></head><body><h1>{escape(title)}</h1>{paras}</body></html>'
path=ROOT/'app/src/main/assets/p0-sample.epub'
with ZipFile(path,'w') as z:
    for name,value in [('mimetype','application/epub+zip'),*files.items()]:
        info=ZipInfo(name,(2026,10,25,0,0,0));info.compress_type=ZIP_STORED if name=='mimetype' else ZIP_DEFLATED
        z.writestr(info,value.encode() if isinstance(value,str) else value)
print('EPUB SHA256',hashlib.sha256(path.read_bytes()).hexdigest())
