"""Export current Community source only; never includes Git history or private sibling code."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
TOP_FILES = ['pom.xml', 'mvnw', 'mvnw.cmd', 'README.md', 'LICENSE.md', 'Dockerfile',
             'compose.yaml', '.gitignore', '.dockerignore']
EXCLUDED = {'target', 'node_modules', 'generated', '__pycache__', '.git', '.m3'}

def sources():
    for name in TOP_FILES:
        yield ROOT / name
    for folder in ['src', 'docs', 'scripts', '.mvn', '.run', 'browser-tests']:
        for path in (ROOT / folder).rglob('*'):
            if path.is_file() and not (set(path.relative_to(ROOT).parts) & EXCLUDED) and path.name != 'index.html':
                yield path

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    files = sorted(set(sources()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.output, 'x', compression=ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, Path('m3') / path.relative_to(ROOT))
    print(f'Exported {len(files)} Community source files to {args.output}')
