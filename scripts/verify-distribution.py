"""Inspect a packaged application, including nested library JARs, for edition boundaries."""
import argparse
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

def verify(path, edition):
    classes = set()
    libraries = set()
    with ZipFile(path) as app:
        names = app.namelist()
        classes.update(name for name in names if name.endswith('.class'))
        for name in names:
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                libraries.add(name.rsplit('/', 1)[-1])
                with ZipFile(BytesIO(app.read(name))) as library:
                    classes.update(entry for entry in library.namelist() if entry.endswith('.class'))
    paid = any('com/sysadminanywhere/m3/scale/' in name or name.endswith('/ScaleLicenseService.class')
               or name.endswith('/ScaleLicenseApi.class') or name.endswith('/ScaleLicenseView.class') for name in classes)
    community = any(name.endswith('/CommunityExecutionPolicy.class') for name in classes)
    if edition == 'community':
        assert community and not paid, 'Community artifact contains paid code or lacks its fixed policy'
        assert not any(name.endswith('/CommunityLicenseView.class') for name in classes), 'Community contains the obsolete upgrade page'
        assert not any('/i18n/extra_ui_' in name for name in names), 'Community contains private UI translations'
    else:
        assert paid and not community, 'Full artifact lacks Scale or contains conflicting Community policy'
    assert not any(name.startswith(('m3-core-', 'm3-extension-api-', 'm3-community-', 'm3-scale-')) for name in libraries), 'Unexpected M3 module dependency'
    assert any(name.endswith('/Application.class') and 'com/sysadminanywhere/m3/' in name for name in classes), 'Missing shared application entry point'
    print(f'{edition}: verified {len(classes)} classes and {len(libraries)} libraries')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('edition', choices=['community', 'full'])
    args = parser.parse_args()
    verify(args.jar, args.edition)
