"""Extract pinned Readium assets and generated synthetic EPUBs for desktop checks."""
import argparse
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--aar", type=Path, help="Readium navigator 3.4.0 AAR; defaults to Gradle cache")
args = parser.parse_args()
if args.aar:
    aar = args.aar
else:
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1/org.readium.kotlin-toolkit/readium-navigator/3.4.0"
    candidates = list(cache.glob("*/*.aar"))
    if len(candidates) != 1:
        parser.error("Pass --aar with the readium-navigator 3.4.0 AAR used by this build")
    aar = candidates[0]
destination = root / "work/aar-assets"
with ZipFile(aar) as archive:
    for name in archive.namelist():
        if name.startswith("assets/readium/") and not name.endswith("/"):
            target = destination / Path(name).relative_to("assets")
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError("Invalid asset path")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
fixtures = [root / "app/build/unified-regression", *(root / "app/build/unified-benchmark").glob("*")]
for fixture in fixtures:
    epub = fixture / "rendition.epub"
    if epub.is_file():
        with ZipFile(epub) as archive:
            for name in archive.namelist():
                target = fixture / "files" / name
                if not target.resolve().is_relative_to((fixture / "files").resolve()):
                    raise ValueError("Invalid synthetic fixture path")
                if not name.endswith("/"):
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
print(destination)
