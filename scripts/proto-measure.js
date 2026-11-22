(async () => {
  // 逐屏测量原型锚点：几何 + 关键计算样式。由 scripts/export-proto-spec.sh 注入。
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const screens = [...document.querySelectorAll('.screen[data-screen]')].map((e) => e.dataset.screen);
  const out = {};
  for (const s of screens) {
    try { App.go(s); } catch (e) { out[s] = { error: String(e) }; continue; }
    await sleep(420);
    const scr = document.querySelector('.screen.is-active');
    if (!scr) { out[s] = { error: 'no-active' }; continue; }
    const sr = scr.getBoundingClientRect();
    const items = [...scr.querySelectorAll('[data-od-id]')].map((e) => {
      const r = e.getBoundingClientRect();
      const cs = getComputedStyle(e);
      const px = (v) => parseFloat(v) || 0;
      return {
        id: e.getAttribute('data-od-id'),
        x: +(r.x - sr.x).toFixed(1),
        y: +(r.y - sr.y).toFixed(1),
        w: +r.width.toFixed(1),
        h: +r.height.toFixed(1),
        fs: px(cs.fontSize),
        fw: cs.fontWeight,
        color: cs.color,
        bg: cs.backgroundColor,
        radius: cs.borderRadius,
        pad: [px(cs.paddingTop), px(cs.paddingRight), px(cs.paddingBottom), px(cs.paddingLeft)],
        text: (e.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120),
        tag: e.tagName.toLowerCase(),
        visible: cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0,
      };
    });
    out[s] = { box: { w: +sr.width.toFixed(1), h: +sr.height.toFixed(1) }, items };
  }
  return out;
})()