const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(resolve(__dirname, '../../main/resources/META-INF/resources/unsaved-changes.js'), 'utf8');

function browser() {
    let unload, mutation;
    const window = {addEventListener: (_, listener) => { unload = listener; }};
    const context = vm.createContext({window, document: {body: {}}, AbortController,
        MutationObserver: class { constructor(callback) { mutation = callback; } observe() {} }});
    vm.runInContext(source, context);
    const owner = {isConnected: true};
    const handlers = {};
    const root = {addEventListener: (type, handler, options) => { handlers[type] = {handler, options}; }};
    window.m3Unsaved.register(root, owner);
    return {window, owner, root, handlers, mutation: () => mutation(),
        reloadModule: () => vm.runInContext(source, context),
        unload: () => { let prevented = false; const event = {preventDefault: () => {prevented = true;}}; unload(event); return prevented; }};
}
test('warns immediately for real input, clears after save or revert, ignores synthetic initialization', () => {
    const page = browser();
    page.handlers.input.handler({isTrusted: false}); assert.equal(page.unload(), false);
    page.handlers.input.handler({isTrusted: true}); assert.equal(page.unload(), true);
    page.window.m3Unsaved.setDirty(page.owner, false); assert.equal(page.unload(), false);
    page.handlers.change.handler({isTrusted: true}); assert.equal(page.unload(), true);
});
test('cleans detached owners and aborts their listeners', () => {
    const page = browser(); page.handlers.input.handler({isTrusted: true});
    page.owner.isConnected = false; page.mutation();
    assert.equal(page.unload(), false); assert.equal(page.handlers.input.options.signal.aborted, true);
});
test('duplicate module loads and registration retain the existing dirty state', () => {
    const page = browser(); page.handlers.change.handler({isTrusted: true});
    page.reloadModule(); page.window.m3Unsaved.register(page.root, page.owner);
    assert.equal(page.unload(), true);
    page.window.m3Unsaved.unregister(page.owner); assert.equal(page.unload(), false);
});
