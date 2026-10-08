const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const source = readFileSync(resolve(__dirname, '../../main/resources/META-INF/resources/theme-preference.js'), 'utf8');

function browser({saved, cookie = '', blocked = false, dark = false} = {}) {
    let theme = 'compact';
    const listeners = [];
    const media = {matches: dark, addEventListener: (_, listener) => listeners.push(listener)};
    const document = {
        cookie,
        documentElement: {getAttribute: () => theme, setAttribute: (_, value) => { theme = value; }}
    };
    const window = {
        matchMedia: () => media,
        localStorage: {
            getItem: () => { if (blocked) throw new Error('Storage blocked'); return saved; },
            setItem: (_, value) => { if (blocked) throw new Error('Storage blocked'); saved = value; }
        }
    };
    const context = vm.createContext({window, document});
    vm.runInContext(source, context);
    return {window, document, listeners, theme: () => theme,
        reloadModule: () => vm.runInContext(source, context),
        changeSystem: value => { media.matches = value; listeners.forEach(listener => listener()); }};
}

test('restores the saved theme while preserving unrelated theme tokens', () => {
    const page = browser({saved: 'dark'});
    assert.equal(page.window.m3Theme.preference, 'dark');
    assert.equal(page.theme(), 'compact dark');
    page.window.m3Theme.save('light');
    assert.equal(page.theme(), 'compact light');
    assert.match(page.document.cookie, /^m3-theme=light;/);
});

test('follows system changes only while the system preference is selected', () => {
    const page = browser();
    page.changeSystem(true);
    assert.equal(page.theme(), 'compact dark');
    page.window.m3Theme.save('light');
    page.changeSystem(false);
    page.changeSystem(true);
    assert.equal(page.theme(), 'compact light');
    page.window.m3Theme.save('system');
    assert.equal(page.theme(), 'compact dark');
});

test('uses cookies and still applies changes when browser storage is blocked', () => {
    const page = browser({blocked: true, cookie: 'other=value; m3-theme=dark'});
    assert.equal(page.theme(), 'compact dark');
    assert.doesNotThrow(() => page.window.m3Theme.save('light'));
    assert.equal(page.theme(), 'compact light');
    assert.match(page.document.cookie, /^m3-theme=light;/);
});

test('rejects invalid saved values and registers the media listener only once', () => {
    const page = browser({saved: 'invalid', cookie: 'm3-theme=dark'});
    assert.equal(page.theme(), 'compact dark');
    page.reloadModule();
    page.window.m3Theme.save('system');
    page.window.m3Theme.save('dark');
    page.window.m3Theme.save('system');
    assert.equal(page.listeners.length, 1);
    page.changeSystem(false);
    assert.equal(page.theme(), 'compact light');
});
