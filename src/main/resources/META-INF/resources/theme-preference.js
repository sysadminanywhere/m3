(() => {
    if (window.m3Theme) return;

    const choices = new Set(['light', 'dark', 'system']);
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const normalize = value => choices.has(value) ? value : 'system';
    const read = () => {
        let saved;
        try { saved = window.localStorage.getItem('m3-theme'); } catch { /* Cookies remain available. */ }
        const cookie = document.cookie.split('; ').find(value => value.startsWith('m3-theme='));
        return normalize(choices.has(saved) ? saved : cookie?.slice('m3-theme='.length));
    };
    let preference = read();
    const render = () => {
        const tokens = new Set((document.documentElement.getAttribute('theme') || '').split(/\s+/).filter(Boolean));
        tokens.delete('light');
        tokens.delete('dark');
        tokens.add(preference === 'dark' || (preference === 'system' && media.matches) ? 'dark' : 'light');
        document.documentElement.setAttribute('theme', [...tokens].join(' '));
    };
    const apply = value => { preference = normalize(value); render(); };
    media.addEventListener('change', () => { if (preference === 'system') render(); });
    window.m3Theme = {
        get preference() { return preference; },
        apply,
        save(value) {
            apply(value);
            try { window.localStorage.setItem('m3-theme', preference); } catch { /* Fall back to the cookie. */ }
            document.cookie = `m3-theme=${preference}; Path=/; Max-Age=31536000; SameSite=Lax`;
        }
    };
    render();
})();
