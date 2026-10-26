"""Run after SectionRenderingTest; extracts only synthetic fixtures into ignored build output."""
from pathlib import Path
from zipfile import ZipFile
root=Path(__file__).resolve().parents[1]
for folder in (root/'app/build/section-regression').glob('*'):
 for kind in ['source','derived']:
  epub=folder/(kind+'.epub')
  if epub.exists():
   with ZipFile(epub) as z:
    for name in z.namelist():
     target=folder/kind/name
     if not target.resolve().is_relative_to((folder/kind).resolve()):raise ValueError('Invalid fixture path')
     if not name.endswith('/'):
      target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
