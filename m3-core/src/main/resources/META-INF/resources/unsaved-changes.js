(() => {
    if (window.m3Unsaved) return;
    const forms = new Map();
    const cleanDisconnected = () => {
        for (const [owner, form] of forms) {
            if (!owner.isConnected) {
                form.roots.forEach(controller => controller.abort());
                forms.delete(owner);
            }
        }
    };
    new MutationObserver(cleanDisconnected).observe(document.body, {childList: true, subtree: true});
    window.addEventListener('beforeunload', event => {
        cleanDisconnected();
        if ([...forms.values()].some(form => form.dirty)) {
            event.preventDefault(); event.returnValue = '';
        }
    });
    window.m3Unsaved = {
        register(root, owner) {
            cleanDisconnected();
            let form = forms.get(owner);
            if (!form) { form = {dirty: false, roots: new Map()}; forms.set(owner, form); }
            if (form.roots.has(root)) return;
            const controller = new AbortController();
            form.roots.set(root, controller);
            const changed = event => { if (event.isTrusted) form.dirty = true; };
            root.addEventListener('input', changed, {capture: true, signal: controller.signal});
            root.addEventListener('change', changed, {capture: true, signal: controller.signal});
        },
        setDirty(owner, dirty) { const form = forms.get(owner); if (form) form.dirty = dirty; },
        unregister(owner) {
            const form = forms.get(owner);
            if (form) form.roots.forEach(controller => controller.abort());
            forms.delete(owner);
        }
    };
})();
