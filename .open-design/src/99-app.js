(function () {
  'use strict';

  var TABS = [
    { id: 'overview', screen: 's01' },
    { id: 'live', screen: 's02' },
    { id: 'ad', screen: 's03' },
    { id: 'finance', screen: 's04' },
    { id: 'settings', screen: 's09' }
  ];
  var TAB_OF_SCREEN = {};
  TABS.forEach(function (t) { TAB_OF_SCREEN[t.screen] = t.id; });
  var STATES = ['loading', 'empty', 'error', 'populated', 'edge'];

  var root = document.documentElement;
  var host = document.getElementById('screenHost');
  var device = document.getElementById('odDevice');
  var screenBox = document.getElementById('odScreen');
  var tabbar = document.getElementById('odTabbar');
  var appbar = document.getElementById('odAppbar');
  var appbarTitle = document.getElementById('odAppbarTitle');
  var toastHost = document.getElementById('odToastHost');
  var hapticPing = document.getElementById('odHaptic');
  var splashEl = document.getElementById('odSplash');

  var current = null;
  var stack = [];
  var lastHaptic = 0;
  var splashTimers = [];

  function screenList() {
    return host ? Array.prototype.slice.call(host.querySelectorAll(':scope > .screen')) : [];
  }
  function byId(id) {
    if (!id) return null;
    var list = screenList();
    for (var i = 0; i < list.length; i++) {
      if (list[i].getAttribute('data-screen') === id) return list[i];
    }
    return null;
  }
  function reduced() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  /* ── 主题 ─────────────────────────────────────────────────────── */
  function theme(id) {
    if (!id) return root.getAttribute('data-theme') || 'apple';
    root.setAttribute('data-theme', id);
    syncDev();
    haptic('light');
    return id;
  }

  /* ── 触觉反馈（视觉示意，同一操作 120ms 内不重复） ─────────────── */
  function haptic(type) {
    var now = Date.now();
    if (now - lastHaptic < 120) return;
    lastHaptic = now;
    if (!device) return;
    device.classList.remove('is-haptic-light', 'is-haptic-medium');
    if (hapticPing) hapticPing.classList.remove('is-on');
    void device.offsetWidth;
    device.classList.add(type === 'medium' ? 'is-haptic-medium' : 'is-haptic-light');
    if (hapticPing) hapticPing.classList.add('is-on');
    window.setTimeout(function () {
      device.classList.remove('is-haptic-light', 'is-haptic-medium');
      if (hapticPing) hapticPing.classList.remove('is-on');
    }, 300);
  }

  /* ── Toast ───────────────────────────────────────────────────── */
  function toast(text) {
    if (!toastHost) return;
    var el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text == null ? '' : String(text);
    toastHost.appendChild(el);
    window.setTimeout(function () { el.classList.add('is-out'); }, 1200);
    window.setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 1600);
  }

  /* ── 浮层开关 ─────────────────────────────────────────────────── */
  function open(sel) {
    var el = typeof sel === 'string' ? document.querySelector(sel) : sel;
    if (!el) return false;
    el.hidden = false;
    return true;
  }
  function close(sel) {
    var el = typeof sel === 'string' ? document.querySelector(sel) : sel;
    if (!el) return false;
    el.hidden = true;
    return true;
  }

  /* ── 两级分类选择器（结构冻结，s15 / s05 / s14a 共用，交互只此一份） ──
     <div class="cat-picker" data-kind="expense" data-recognized="居住·家政">
       .cat-l1[data-l1]  →  .cat-l2-panel[data-l1]  →  .cat-l2[data-l2][data-l1]
     点一级展开 / 收起对应面板；点二级选中该二级并把父一级置为展开；
     data-recognized="A·B" 自动展开 A、选中 B、给 B 加 .is-recognized 并滚到可见。
     选中结果写在 picker 的 data-value（二级）/ data-value-l1（一级）上，供提交方读。 */
  function pkList(scope) {
    var box = scope && scope.querySelectorAll ? scope : document;
    return Array.prototype.slice.call(box.querySelectorAll('.cat-picker'));
  }
  function pkL1s(pk) { return Array.prototype.slice.call(pk.querySelectorAll('.cat-l1')); }
  function pkPanels(pk) { return Array.prototype.slice.call(pk.querySelectorAll('.cat-l2-panel')); }
  function pkPanelOf(pk, l1) {
    var ps = pkPanels(pk);
    for (var i = 0; i < ps.length; i++) {
      if (ps[i].getAttribute('data-l1') === l1) return ps[i];
    }
    return null;
  }
  function pkL1Of(pk, l1) {
    var bs = pkL1s(pk);
    for (var i = 0; i < bs.length; i++) {
      if (bs[i].getAttribute('data-l1') === l1) return bs[i];
    }
    return null;
  }
  function pkMarkL1(pk, active) {
    pkL1s(pk).forEach(function (b) {
      b.setAttribute('aria-pressed', b === active ? 'true' : 'false');
    });
  }
  function pkPickL2(pk, item, silent) {
    var l1 = item.getAttribute('data-l1') || '';
    var l2 = item.getAttribute('data-l2') || '';
    Array.prototype.forEach.call(pk.querySelectorAll('.cat-l2'), function (b) {
      b.setAttribute('aria-pressed', b === item ? 'true' : 'false');
    });
    pkMarkL1(pk, pkL1Of(pk, l1));
    var panel = pkPanelOf(pk, l1);
    if (panel) panel.hidden = false;
    if (l1) pk.setAttribute('data-value-l1', l1);
    pk.setAttribute('data-value', l2);
    pkLegacySync(pk);
    if (!silent) haptic('light');
    return l2;
  }
  /* 选中一级（不改展开状态 —— 供回填使用；交互点击走 pkToggleL1 的展开 / 收起语义） */
  function pkSelectL1(pk, name, silent) {
    var btn = pkL1Of(pk, name);
    var panel = pkPanelOf(pk, name);
    if (!btn || !panel) return false;
    pkPanels(pk).forEach(function (p) { p.hidden = p !== panel; });
    pkMarkL1(pk, btn);
    pk.setAttribute('data-value-l1', name);
    pk.removeAttribute('data-value');
    pkLegacySync(pk);
    if (!silent) haptic('light');
    return true;
  }
  /* s04 的共用表单脚本（editTx / setS05 / 保存）仍在读 .s05-cat / .s14-cat；
     把新结构的当前选中项映射过去，s04 脚本迁到 .cat-picker 后这一段可整段删掉。 */
  function pkLegacySync(pk) {
    var screen = pk.closest ? pk.closest('.screen') : null;
    if (!screen) return;
    var sid = screen.getAttribute('data-screen');
    var cls = sid === 's05' ? 's05-cat' : (sid === 's14a' ? 's14-cat' : '');
    if (!cls) return;
    Array.prototype.forEach.call(screen.querySelectorAll('.cat-l1, .cat-l2'), function (b) {
      b.classList.remove('s05-cat', 's14-cat');
      b.removeAttribute('data-cat');
    });
    var pickers = screen.querySelectorAll('.cat-picker');
    for (var i = 0; i < pickers.length; i++) {
      if (pickers[i].hidden) continue;
      var on = pickers[i].querySelector('.cat-l2[aria-pressed="true"]') || pickers[i].querySelector('.cat-l1[aria-pressed="true"]');
      if (!on) continue;
      var name = on.getAttribute('data-l2') || on.getAttribute('data-l1') || '';
      on.classList.add(cls);
      on.setAttribute('data-cat', name);
      return;
    }
  }
  /* 收支切换：一屏内的两个 .cat-picker（expense / income）只显示与 kind 一致的那个 */
  function pkKindSync(screen, kind) {
    if (!screen) return;
    Array.prototype.forEach.call(screen.querySelectorAll('.cat-picker'), function (pk) {
      if (!pk.getAttribute('data-kind')) return;
      pk.hidden = pk.getAttribute('data-kind') !== kind;
      pkLegacySync(pk);
    });
  }
  /* s04 的共用脚本把「任何带 data-kind 的点击」都当成收支切换 —— .cat-picker 自身也带
     data-kind，所以点分类会把它写成「支出 / 无选中」。这里在它跑完后把分段控件与
     picker 可见性拉回正确状态（s05 的 #s05-kind-label 一并修）。 */
  function pkKindRestore(screen, kind) {
    if (!screen || !kind) return;
    Array.prototype.forEach.call(screen.querySelectorAll('button[data-kind]'), function (b) {
      b.setAttribute('aria-pressed', b.getAttribute('data-kind') === kind ? 'true' : 'false');
    });
    var label = screen.querySelector('#s05-kind-label');
    if (label) label.textContent = kind === 'income' ? '收入' : '支出';
    pkKindSync(screen, kind);
  }

  /* 按分类名回填（一级 / 二级名全局唯一，先找二级再找一级） */
  function pkApplyName(screen, name, silent) {
    if (!screen || !name) return false;
    var valueEl = screen.querySelector('[data-value-for="cat"]');
    if (valueEl) valueEl.textContent = name;
    var pickers = screen.querySelectorAll('.cat-picker');
    for (var i = 0; i < pickers.length; i++) {
      var pk = pickers[i];
      if (pk.hidden) continue;
      var l2 = pk.querySelector('.cat-l2[data-l2="' + name + '"]');
      if (l2) return !!pkPickL2(pk, l2, silent);
      if (pkSelectL1(pk, name, silent)) return true;
    }
    return false;
  }
  function pkToggleL1(pk, btn) {
    var name = btn.getAttribute('data-l1') || '';
    var wasOpen = btn.getAttribute('aria-pressed') === 'true';
    var prevL1 = pk.getAttribute('data-value-l1') || '';
    pkPanels(pk).forEach(function (p) { p.hidden = true; });
    if (wasOpen) {
      pkMarkL1(pk, null);
      pk.removeAttribute('data-value-l1');
      return null;
    }
    var panel = pkPanelOf(pk, name);
    if (!panel) return null;
    panel.hidden = false;
    pkMarkL1(pk, btn);
    pk.setAttribute('data-value-l1', name);
    /* 换了一级：原二级选中值失效，回落到一级；.is-recognized 是「系统识别」标记，保留 */
    Array.prototype.forEach.call(pk.querySelectorAll('.cat-l2'), function (b) {
      if (b.getAttribute('data-l1') !== name) b.setAttribute('aria-pressed', 'false');
    });
    if (prevL1 !== name) pk.removeAttribute('data-value');
    haptic('light');
    return name;
  }
  function pkApplyRecognized(pk) {
    var rec = pk.getAttribute('data-recognized') || '';
    if (!rec) return null;
    var parts = rec.split(/[·・]/);
    var l1 = (parts[0] || '').replace(/^\s+|\s+$/g, '');
    var l2 = (parts[1] || '').replace(/^\s+|\s+$/g, '');
    var btn = pkL1Of(pk, l1);
    var panel = pkPanelOf(pk, l1);
    if (!panel) return null;
    pkPanels(pk).forEach(function (p) { p.hidden = p !== panel; });
    pkMarkL1(pk, btn);
    pk.setAttribute('data-value-l1', l1);
    var target = l2 ? panel.querySelector('.cat-l2[data-l2="' + l2 + '"]') : null;
    if (target) {
      target.classList.add('is-recognized');
      Array.prototype.forEach.call(pk.querySelectorAll('.cat-l2'), function (b) {
        b.setAttribute('aria-pressed', b === target ? 'true' : 'false');
      });
      pk.setAttribute('data-value', l2);
    }
    return target;
  }
  function pkScroller(el) {
    var node = el && el.parentNode;
    while (node && node.nodeType === 1) {
      var cs = window.getComputedStyle ? window.getComputedStyle(node) : null;
      var oy = cs ? cs.overflowY : '';
      if ((oy === 'auto' || oy === 'scroll') && node.scrollHeight > node.clientHeight + 1) return node;
      node = node.parentNode;
    }
    return null;
  }
  /* 只滚自己的滚动容器（block:"nearest" 语义），不用 scrollIntoView —— 后者会连宿主页面一起拽动 */
  function pkScrollTo(target) {
    if (!target) return;
    var box = pkScroller(target);
    if (!box) return;
    var br = box.getBoundingClientRect();
    var tr = target.getBoundingClientRect();
    var top = box.scrollTop;
    if (tr.top < br.top) top -= (br.top - tr.top) + 8;
    else if (tr.bottom > br.bottom) top += (tr.bottom - br.bottom) + 8;
    top = Math.max(0, Math.min(box.scrollHeight - box.clientHeight, top));
    var behavior = reduced() ? 'auto' : 'smooth';
    try {
      if (typeof box.scrollTo === 'function') box.scrollTo({ top: top, left: 0, behavior: behavior });
      else box.scrollTop = top;
    } catch (err) {
      box.scrollTop = top;
    }
  }
  function initCategoryPickers(scope) {
    pkList(scope).forEach(function (pk) {
      if (pk.getAttribute('data-pk-ready') === '1') return;
      pk.setAttribute('data-pk-ready', '1');
      pk.addEventListener('click', function (e) {
        var node = e.target;
        if (!node || !node.closest) return;
        var l2 = node.closest('.cat-l2');
        if (l2) { pkPickL2(pk, l2); return; }
        var l1 = node.closest('.cat-l1');
        if (l1) pkToggleL1(pk, l1);
      });
      pkApplyRecognized(pk);
    });
  }
  function focusCategoryPickers(scope) {
    pkList(scope).forEach(function (pk) {
      var rec = pk.getAttribute('data-recognized') || '';
      if (!rec) return;
      var l2 = (rec.split(/[·・]/)[1] || '').replace(/^\s+|\s+$/g, '');
      var target = pk.querySelector('.cat-l2.is-recognized' + (l2 ? '[data-l2="' + l2 + '"]' : ''));
      pkScrollTo(target);
    });
  }

  /* s14a 回填：用新结构（.cat-picker）把分类写进摘要行；row 为 null 表示新增交易 */
  function s14aFill(screen, row) {
    if (!screen) return false;
    var kind = row ? (row.getAttribute('data-kind') || 'expense') : 'expense';
    pkKindSync(screen, kind);
    var name = row ? (row.getAttribute('data-cat') || '') : '餐饮';
    if (!name) { pkKindSync(screen, kind); return false; }
    return pkApplyName(screen, name, true);
  }

  /* ── 自动记账浮窗（s15） ─────────────────────────────────────────
     契约（docs/记账/自动记账.md）：只有「记一笔」和「忽略」能关闭浮窗；
     点遮罩只吞点击（轻震 + 每笔一次 Toast）；切到任何屏 / App 都不关闭
     —— 所以浮层带自己的可见状态（.ab-layer 的 hidden），与当前屏无关。 */
  var AB = { toasted: false, freshTimer: null };
  var AB_DEFAULT = {
    key: 'ab-housekeeping-128',
    merchant: '好慷在家',
    amount: 128,
    time: '10-20 12:18',
    channel: '支付宝',
    pay: '招商银行信用卡(1570)',
    cat: '居住',
    catL2: '家政',
    note: ''
  };
  function abLayer() { return document.getElementById('odAutoBook'); }
  function abPart(sel) { var layer = abLayer(); return layer ? layer.querySelector(sel) : null; }
  function abOpenNow() {
    var layer = abLayer();
    return !!(layer && !layer.hidden);
  }
  function mountAutoBook() {
    var layer = abLayer();
    var od = document.getElementById('odScreen');
    if (!layer || !od) return null;
    /* 移到设备屏上：覆盖状态栏 / 导航条，并且切屏后继续覆盖（屏内子节点做不到这两点） */
    if (layer.parentNode !== od) od.appendChild(layer);
    return layer;
  }
  function showAutoBook() {
    var layer = mountAutoBook();
    if (!layer) return false;
    layer.hidden = false;
    AB.toasted = false;
    initCategoryPickers(layer);
    /* 每笔重新识别：上一次用户收起的二级面板，再次打开时回到「识别项展开 + 选中」 */
    var pk = layer.querySelector('.cat-picker');
    if (pk) pkApplyRecognized(pk);
    focusCategoryPickers(layer);
    return true;
  }
  function hideAutoBook() {
    var layer = abLayer();
    if (!layer) return false;
    layer.hidden = true;
    AB.toasted = false;
    return true;
  }
  function syncAutoBook(el) {
    if (el && el.getAttribute('data-screen') === 's15') showAutoBook();
  }
  function openAutoBook() {
    mountAutoBook();
    if (current && current.getAttribute('data-screen') === 's15') return showAutoBook();
    if (go('s15')) return true;
    return showAutoBook();
  }
  function abShake() {
    var sheet = abPart('.ab-sheet');
    if (!sheet || reduced()) return;
    sheet.classList.remove('is-shake');
    void sheet.offsetWidth;
    sheet.classList.add('is-shake');
    window.setTimeout(function () { sheet.classList.remove('is-shake'); }, 150);
  }
  function abScrimTap() {
    haptic('light');
    abShake();
    if (!AB.toasted) {
      AB.toasted = true;
      toast('点遮罩不会关闭，请选『记一笔』或『忽略』');
    }
    return false;
  }
  function abSwitch() {
    haptic('light');
    if (current && current.getAttribute('data-screen') === 's04') {
      toast('已经在记账页 · 浮窗保持覆盖');
      return true;
    }
    go('s04', { replace: true });
    toast('已切到记账页 · 浮窗不关闭');
    return true;
  }
  function abConfirm() {
    haptic('medium');
    var pk = abPart('.cat-picker');
    var l1 = pk ? (pk.getAttribute('data-value-l1') || '') : '';
    var l2 = pk ? (pk.getAttribute('data-value') || '') : '';
    var input = abPart('.ab-note .note-input');
    var note = input ? String(input.value || '').replace(/^\s+|\s+$/g, '') : '';
    var tx = abTx({});
    if (l1) tx.cat = l1;
    tx.catL2 = l2;
    tx.note = note;
    hideAutoBook();
    toast('已自动记账');
    autoBookCommitted(tx);
    return true;
  }
  function abIgnore() {
    haptic('light');
    hideAutoBook();
    toast('已忽略，7 天内不再提示同笔');
    if (!current || current.getAttribute('data-screen') !== 's04') go('s04', { replace: true });
    return true;
  }
  function abTx(over) {
    var t = {};
    Object.keys(AB_DEFAULT).forEach(function (k) { t[k] = AB_DEFAULT[k]; });
    if (over) Object.keys(over).forEach(function (k) { if (over[k] != null) t[k] = over[k]; });
    return t;
  }
  function money(v) {
    var n = Number(v);
    if (isNaN(n)) n = 0;
    var s = n.toFixed(2).split('.');
    s[0] = s[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return s.join('.');
  }
  function bumpTxCounters(s04) {
    ['#s04-sub', '#s04-listmeta'].forEach(function (sel) {
      var el = s04.querySelector(sel);
      if (!el) return;
      el.textContent = String(el.textContent).replace(/(\d+)(\s*笔)/, function (m, n, tail) {
        return (Number(n) + 1) + tail;
      });
    });
  }
  /* 自动记账落库 → 记账列表即时刷新。同一笔（data-fresh-key）重复触发只更新不重复插入。 */
  function autoBookCommitted(tx) {
    var t = abTx(tx);
    hideAutoBook();
    var s04 = byId('s04');
    if (!s04) { console.warn('[App.autoBookCommitted] 未找到屏 s04'); return false; }
    if (current !== s04) go('s04');
    var list = s04.querySelector('[data-od-id="s04-list"]') || s04;
    var group = list.querySelector('.group');
    if (!group) {
      var anyTx = list.querySelector('.s04-tx');
      group = anyTx ? anyTx.closest('.group') : null;
    }
    if (!group) { console.warn('[App.autoBookCommitted] s04 缺少交易列表'); return false; }
    var key = t.key || ('ab-' + t.merchant);
    /* s04 列表用的是二级名（电影 / 基本工资 …）：能落到二级就只写二级名，只选了一级才写一级 */
    var catLabel = t.catL2 || t.cat;
    var sub = t.merchant + (t.note ? ' · ' + t.note : '') + ' · ' + (t.channel || '支付宝');
    var row = group.querySelector('[data-fresh-key="' + key + '"]');
    var isNew = !row;
    if (isNew) {
      row = document.createElement('button');
      row.type = 'button';
      row.className = 'list-row s04-tx';
      row.setAttribute('data-tx', '');
      row.setAttribute('data-kind', 'expense');
      row.setAttribute('data-fresh-key', key);
      row.innerHTML =
        '<span class="legend-dot s5"></span>' +
        '<span class="row-body"><span class="row-title"><span class="tx-t"></span></span>' +
        '<span class="row-sub"></span></span>' +
        '<span class="tx-auto">自动</span>' +
        '<span class="row-tail num s04-amt is-exp"></span>';
      group.insertBefore(row, group.firstChild);
    }
    row.setAttribute('data-cat', catLabel);
    row.setAttribute('data-amt', String(Number(t.amount) || 0));
    row.setAttribute('data-note', sub);
    var title = row.querySelector('.tx-t');
    if (title) title.textContent = catLabel;
    var subEl = row.querySelector('.row-sub');
    if (subEl) subEl.textContent = sub;
    var amtEl = row.querySelector('.row-tail');
    if (amtEl) amtEl.textContent = '-¥' + money(t.amount);
    row.classList.remove('is-fresh');
    void row.offsetWidth;
    row.classList.add('is-fresh');
    if (AB.freshTimer) window.clearTimeout(AB.freshTimer);
    AB.freshTimer = window.setTimeout(function () { row.classList.remove('is-fresh'); }, 1600);
    if (isNew) bumpTxCounters(s04);
    toast('自动记账已落库 · 列表已刷新');
    return true;
  }

  /* ── 状态演示 ─────────────────────────────────────────────────── */
  function state(id, st) {
    var el = null;
    if (id && id.nodeType === 1) el = id;
    else if (id) el = byId(id);
    else el = current;
    if (!el) return false;
    if (!st || st === 'populated') el.removeAttribute('data-od-state');
    else el.setAttribute('data-od-state', st);
    syncDev();
    return true;
  }
  function currentState() {
    return (current && current.getAttribute('data-od-state')) || 'populated';
  }

  /* ── 路由 ─────────────────────────────────────────────────────── */
  function syncChrome(el) {
    var id = el.getAttribute('data-screen');
    var tabId = TAB_OF_SCREEN[id];
    if (tabbar) {
      Array.prototype.forEach.call(tabbar.querySelectorAll('.tab'), function (b) {
        b.setAttribute('aria-selected', b.getAttribute('data-tab') === tabId ? 'true' : 'false');
      });
      tabbar.hidden = !tabId;
    }
    if (screenBox) screenBox.classList.toggle('is-push', !tabId);
    if (appbar) {
      appbar.hidden = !!tabId;
      if (!tabId && appbarTitle) appbarTitle.textContent = el.getAttribute('data-title') || '';
    }
    Charts.hydrate(el);
    syncDev();
  }

  function activate(next, prev) {
    if (!next) return;
    if (prev === next) { syncChrome(next); return; }
    var fast = reduced();
    if (prev) {
      prev.classList.remove('is-entering');
      prev.classList.add('is-leaving');
    }
    next.classList.add('is-entering');
    next.classList.add('is-active');
    void next.offsetWidth;
    next.classList.remove('is-entering');
    current = next;
    syncChrome(next);
    syncAutoBook(next);
    if (prev) {
      var old = prev;
      window.setTimeout(function () {
        old.classList.remove('is-active', 'is-leaving');
      }, fast ? 0 : 300);
    }
  }

  function go(id, opts) {
    var o = opts || {};
    var next = byId(id);
    if (!next) { console.warn('[App.go] 未找到屏', id); return false; }
    if (next === current && !o.force) return true;
    var prev = current;
    if (prev && !o.replace) stack.push(prev.getAttribute('data-screen'));
    activate(next, prev);
    return true;
  }

  function back() {
    if (!stack.length) { toast('已经在首页'); return false; }
    var id = stack.pop();
    var prev = byId(id);
    if (!prev) return false;
    activate(prev, current);
    return true;
  }

  function tab(key) {
    var found = null;
    for (var i = 0; i < TABS.length; i++) {
      if (TABS[i].id === key || TABS[i].screen === key) found = TABS[i];
    }
    if (!found) return false;
    stack = [];
    var next = byId(found.screen);
    if (!next) { console.warn('[App.tab] 未找到屏', found.screen); return false; }
    if (next === current) { activate(next, null); return true; }
    activate(next, current);
    return true;
  }

  /* ── 图表基元（零依赖内联 SVG） ────────────────────────────────── */
  var Charts = (function () {
    var uid = 0;

    function nums(v) {
      var arr = Array.isArray(v) ? v : String(v == null ? '' : v).split(/[\s,]+/);
      return arr.map(Number).filter(function (x) { return !isNaN(x); });
    }
    function esc(s) {
      return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
    function fx(x) { return String(Math.round(x * 100) / 100); }
    function maxOf(v) { return Math.max.apply(null, v.concat([1])); }
    function wrap(cls, w, h, inner, label) {
      return '<svg class="chart ' + cls + '" data-od-min-fs viewBox="0 0 ' + w + ' ' + h + '" role="img" aria-label="' + esc(label || '图表') + '">' + inner + '</svg>';
    }

    function area(d) {
      d = d || {};
      var v = nums(d.values);
      var w = d.w || 340, h = d.h || 148, padT = 24, padB = 24, padL = 30, padR = 12;
      if (!v.length) v = [0];
      var mx = maxOf(v), n = v.length;
      function X(i) { return padL + (n > 1 ? i * (w - padL - padR) / (n - 1) : (w - padL - padR) / 2); }
      function Y(val) { return (h - padB) - (val / mx) * (h - padB - padT); }
      var pts = v.map(function (val, i) { return fx(X(i)) + ',' + fx(Y(val)); });
      var line = 'M' + pts.join(' L');
      var fill = line + ' L' + fx(X(n - 1)) + ',' + fx(h - padB) + ' L' + fx(X(0)) + ',' + fx(h - padB) + ' Z';
      var gid = 'odg' + (++uid);
      var inner = '<defs><linearGradient id="' + gid + '" x1="0" y1="0" x2="0" y2="1">' +
        '<stop class="chart-stop-a" offset="0"/><stop class="chart-stop-b" offset="1"/></linearGradient></defs>';
      [0, 0.25, 0.5, 0.75, 1].forEach(function (t) {
        var y = fx((h - padB) - t * (h - padB - padT));
        inner += '<line class="chart-grid" x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '"/>';
        if (t === 0 || t === 0.5 || t === 1) {
          inner += '<text x="' + (padL - 6) + '" y="' + (parseFloat(y) + 3.5) + '" text-anchor="end">' + Math.round(t * mx) + '</text>';
        }
      });
      var lbls = d.labels || [];
      lbls.forEach(function (lb, i) {
        if (lbls.length > 7 && i % 2 !== 0) return;
        var f = lbls.length > 1 ? i / (lbls.length - 1) : 0.5;
        var lx = padL + f * (w - padL - padR);
        inner += '<text x="' + fx(lx) + '" y="' + (h - 7) + '" text-anchor="middle">' + esc(lb) + '</text>';
      });
      inner += '<path class="chart-area" d="' + fill + '" fill="url(#' + gid + ')"/>';
      inner += '<path class="chart-line seq" pathLength="1" d="' + line + '"/>';
      inner += '<circle class="chart-dot seq" cx="' + fx(X(n - 1)) + '" cy="' + fx(Y(v[n - 1])) + '" r="4"/>';
      if (d.peak !== false) {
        var pi = 0;
        for (var k = 1; k < n; k++) { if (v[k] > v[pi]) pi = k; }
        inner += '<text class="chart-value" x="' + fx(X(pi)) + '" y="' + fx(Y(v[pi]) - 9) +
          '" text-anchor="middle">峰值 ' + v[pi] + '</text>';
      }
      return wrap('chart-area-chart', w, h, inner, d.label || '24 小时曲线');
    }

    function ring(d) {
      d = d || {};
      var items = (d.items && d.items.length) ? d.items : nums(d.values).map(function (v, i) {
        return { label: '第 ' + (i + 1) + ' 项', value: v, cls: 's' + ((i % 8) + 1) };
      });
      var size = d.size || 128, sw = d.thickness || 15;
      var r = (size - sw) / 2 - 1, c = 2 * Math.PI * r, cx = size / 2;
      var total = items.reduce(function (a, b) { return a + Math.max(0, Number(b.value) || 0); }, 0) || 1;
      var off = 0;
      var inner = '<circle class="chart-track" cx="' + cx + '" cy="' + cx + '" r="' + fx(r) + '" stroke-width="' + sw + '"/>';
      items.forEach(function (it, i) {
        var val = Math.max(0, Number(it.value) || 0);
        var len = c * (val / total);
        var cls = it.cls || ('s' + ((i % 8) + 1));
        inner += '<circle class="chart-arc ' + cls + '" cx="' + cx + '" cy="' + cx + '" r="' + fx(r) + '" stroke-width="' + sw +
          '" stroke-dasharray="' + fx(len) + ' ' + fx(c - len) + '" stroke-dashoffset="' + fx(-off) +
          '" style="--arc-l:' + fx(len) + ';--arc-c:' + fx(c - len) + ';--arc-d:' + (i * 60) + 'ms"/>';
        off += len;
      });
      return '<svg class="chart chart-ring" data-od-min-fs viewBox="0 0 ' + size + ' ' + size + '" role="img" aria-label="' + esc(d.label || '环形图') + '">' +
        '<g transform="rotate(-90 ' + cx + ' ' + cx + ')">' + inner + '</g></svg>';
    }

    function pie(d) {
      d = d || {};
      var items = (d.items && d.items.length) ? d.items : nums(d.values).map(function (v, i) {
        return { label: '第 ' + (i + 1) + ' 项', value: v, cls: 's' + ((i % 8) + 1) };
      });
      var size = d.size || 128;
      var r = size / 2 - 1, cx = size / 2;
      var total = items.reduce(function (a, b) { return a + Math.max(0, Number(b.value) || 0); }, 0) || 1;
      var start = -Math.PI / 2, inner = '';
      items.forEach(function (it, i) {
        var val = Math.max(0, Number(it.value) || 0);
        var ang = val / total * Math.PI * 2, end = start + ang;
        var x1 = cx + r * Math.cos(start), y1 = cx + r * Math.sin(start);
        var x2 = cx + r * Math.cos(end), y2 = cx + r * Math.sin(end);
        var large = ang > Math.PI ? 1 : 0;
        var cls = it.cls || ('s' + ((i % 8) + 1));
        inner += '<path class="pie-slice ' + cls + '" d="M' + cx + ' ' + cx +
          ' L' + fx(x1) + ' ' + fx(y1) + ' A' + fx(r) + ' ' + fx(r) + ' 0 ' + large + ' 1 ' +
          fx(x2) + ' ' + fx(y2) + ' Z" style="--arc-d:' + (i * 60) + 'ms"/>';
        start = end;
      });
      return '<svg class="chart chart-pie" data-od-min-fs viewBox="0 0 ' + size + ' ' + size +
        '" role="img" aria-label="' + esc(d.label || '扇形图') + '">' + inner + '</svg>';
    }

    function bars(d) {
      d = d || {};
      var v = nums(d.values);
      var w = d.w || 340, h = d.h || 150, padT = 22, padB = 24, padL = 30, padR = 12, gap = 8;
      if (!v.length) v = [0];
      var mx = maxOf(v);
      var bw = Math.max(4, (w - padL - padR - gap * (v.length + 1)) / v.length);
      var inner = '';
      [0, 0.5, 1].forEach(function (t) {
        var y = fx((h - padB) - t * (h - padB - padT));
        inner += '<line class="chart-grid" x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '"/>';
        inner += '<text x="' + (padL - 6) + '" y="' + (parseFloat(y) + 3.5) + '" text-anchor="end">' + Math.round(t * mx) + '</text>';
      });
      v.forEach(function (val, i) {
        var bh = Math.max(2, (h - padB - padT) * (val / mx));
        var x = padL + gap * (i + 1) + bw * i;
        inner += '<rect class="chart-bar ' + (d.cls || 'seq') + '" x="' + fx(x) + '" y="' + fx(h - padB - bh) + '" width="' + fx(bw) +
          '" height="' + fx(bh) + '" rx="4" style="--bar-d:' + (i * 40) + 'ms"/>';
        if (d.vlabels !== false) {
          inner += '<text class="chart-value" x="' + fx(x + bw / 2) + '" y="' + fx(h - padB - bh - 6) +
            '" text-anchor="middle">' + val + '</text>';
        }
        var lb = (d.labels || [])[i];
        if (lb) inner += '<text x="' + fx(x + bw / 2) + '" y="' + (h - 7) + '" text-anchor="middle">' + esc(lb) + '</text>';
      });
      return wrap('chart-bars', w, h, inner, d.label || '柱状图');
    }

    function gauge(d) {
      d = d || {};
      var raw = d.value != null ? Number(d.value) : (nums(d.values)[0] || 0);
      var pct = Math.max(0, Math.min(1, isNaN(raw) ? 0 : raw));
      var size = d.size || 132, sw = d.thickness || 11;
      var r = (size - sw) / 2 - 1, cx = size / 2;
      return '<svg class="chart chart-gauge" data-od-min-fs viewBox="0 0 ' + size + ' ' + size + '" role="img" aria-label="' + esc(d.label || ('进度 ' + Math.round(pct * 100) + '%')) + '">' +
        '<circle class="chart-track" cx="' + cx + '" cy="' + cx + '" r="' + fx(r) + '" stroke-width="' + sw + '"/>' +
        '<circle class="chart-gauge-value" cx="' + cx + '" cy="' + cx + '" r="' + fx(r) + '" stroke-width="' + sw +
        '" pathLength="1" stroke-dasharray="' + fx(pct) + ' 1" transform="rotate(-90 ' + cx + ' ' + cx + ')" style="--g:' + fx(pct) + '"/>' +
        '</svg>';
    }

    function spark(d) {
      d = d || {};
      var v = nums(d.values);
      var w = d.w || 84, h = d.h || 28;
      if (!v.length) v = [0];
      var mx = maxOf(v), mn = Math.min.apply(null, v.concat([0])), span = (mx - mn) || 1;
      var pts = v.map(function (val, i) {
        var x = v.length > 1 ? i * (w - 4) / (v.length - 1) + 2 : w / 2;
        return fx(x) + ',' + fx(h - 3 - ((val - mn) / span) * (h - 6));
      });
      return wrap('chart-spark', w, h, '<path class="chart-line seq" pathLength="1" d="M' + pts.join(' L') + '"/>', d.label || '迷你走势');
    }

    function heat(d) {
      d = d || {};
      var v = nums(d.values);
      var w = d.w || 340, h = d.h || 44, gap = 2;
      if (!v.length) v = [0];
      var cw = (w - gap * (v.length - 1)) / v.length;
      var mx = maxOf(v);
      var inner = '';
      v.forEach(function (val, i) {
        var t = Math.max(0, Math.min(1, val / mx));
        var lv = t <= 0 ? 0 : Math.min(5, Math.max(1, Math.ceil(t * 5)));
        inner += '<rect class="heat-cell h' + lv + '" x="' + fx(i * (cw + gap)) + '" y="0" width="' + fx(cw) + '" height="' + h +
          '" rx="2" style="--heat-d:' + (i * 20) + 'ms"/>';
      });
      return wrap('chart-heat', w, h, inner, d.label || '24 小时活跃热力');
    }

    var BUILDERS = { area: area, ring: ring, pie: pie, bars: bars, gauge: gauge, spark: spark, heat: heat };

    function render(kind, d) {
      var fn = BUILDERS[kind];
      if (!fn) { console.warn('[App.chart] 未知图表类型', kind); return ''; }
      return fn(d || {});
    }

    function mount(target, kind, d) {
      var el = typeof target === 'string' ? document.querySelector(target) : target;
      if (!el) return false;
      el.innerHTML = render(kind, d);
      return true;
    }

    /* 自动渲染：<div data-chart="heat" data-chart-values="1,3,9"></div>
       data-chart-labels="00,04,08"  data-chart-value="0.62" */
    function hydrate(scope) {
      var box = scope || document;
      var list = box.querySelectorAll ? box.querySelectorAll('[data-chart]') : [];
      Array.prototype.forEach.call(list, function (el) {
        if (el.getAttribute('data-chart-done') === '1') return;
        try {
          var html = render(el.getAttribute('data-chart'), {
            values: el.getAttribute('data-chart-values') || '',
            labels: (el.getAttribute('data-chart-labels') || '').split(',').filter(function (s) { return s !== ''; }),
            value: el.getAttribute('data-chart-value'),
            label: el.getAttribute('data-chart-label') || ''
          });
          if (html) { el.innerHTML = html; el.setAttribute('data-chart-done', '1'); }
        } catch (err) {
          console.warn('[App.chart] 渲染失败', el, err);
        }
      });
    }

    return { area: area, ring: ring, bars: bars, gauge: gauge, spark: spark, heat: heat, render: render, mount: mount, hydrate: hydrate };
  })();

  /* ── 数字滚动 ─────────────────────────────────────────────────── */
  function count(target, to, opts) {
    var el = typeof target === 'string' ? document.querySelector(target) : target;
    if (!el) return;
    var o = opts || {};
    var end = Number(to) || 0;
    var dec = o.decimals || 0;
    var pre = o.prefix || '', suf = o.suffix || '';
    if (reduced()) { el.textContent = pre + end.toFixed(dec) + suf; return; }
    var from = parseFloat(String(el.textContent).replace(/[^\d.-]/g, '')) || 0;
    var dur = o.dur || 600;
    var t0 = performance.now();
    function step(t) {
      var p = Math.min(1, (t - t0) / dur);
      var e = 1 - Math.pow(1 - p, 3);
      el.textContent = pre + (from + (end - from) * e).toFixed(dec) + suf;
      if (p < 1) window.requestAnimationFrame(step);
    }
    window.requestAnimationFrame(step);
  }

  /* ── 开屏 ─────────────────────────────────────────────────────── */
  function splash(force) {
    if (!splashEl) return;
    splashTimers.forEach(function (t) { window.clearTimeout(t); });
    splashTimers = [];
    splashEl.hidden = false;
    splashEl.classList.remove('is-done');
    void splashEl.offsetWidth;
    var reduce = reduced();
    splashTimers.push(window.setTimeout(function () { splashEl.classList.add('is-done'); }, reduce ? 400 : 1600));
    splashTimers.push(window.setTimeout(function () { splashEl.hidden = true; }, reduce ? 700 : 1900));
    return force;
  }

  /* ── 交互委托 ─────────────────────────────────────────────────── */
  function bind() {
    if (screenBox) {
      screenBox.addEventListener('click', function (e) {
        var node = e.target;
        if (!node || !node.closest) return;
        /* 浮窗打开时先吃浮层上的点击：只有两个按钮能关闭，遮罩只吞点击 */
        if (abOpenNow()) {
          if (node.closest('[data-ab-confirm]')) { abConfirm(); return; }
          if (node.closest('[data-ab-ignore]')) { abIgnore(); return; }
          if (node.closest('[data-ab-switch]')) { abSwitch(); return; }
          if (node.closest('.ab-scrim')) { abScrimTap(); return; }
          if (!node.closest('.ab-sheet')) return;
        }
        /* 收支切换：一屏内的两个 .cat-picker（data-kind）只显示与 kind 一致的那个 */
        var kindBtn = node.closest('[data-kind]');
        if (kindBtn && kindBtn.tagName === 'BUTTON' && !kindBtn.hasAttribute('data-tx') && !kindBtn.closest('.cat-picker')) {
          var kindScreen = kindBtn.closest('.screen');
          if (kindScreen) pkKindSync(kindScreen, kindBtn.getAttribute('data-kind'));
          haptic('light');
        }
        /* 点分类（.cat-picker 自带 data-kind）会把旧脚本的收支分段打乱，跑完就拉回来 */
        var pickerHit = node.closest('.cat-picker');
        if (pickerHit && pickerHit.getAttribute('data-kind')) {
          var pkScreen = node.closest('.screen');
          var pkKind = pickerHit.getAttribute('data-kind');
          window.setTimeout(function () { pkKindRestore(pkScreen, pkKind); }, 0);
        }
        /* s14a 分类抽屉（新结构已无 [data-pick="cat"]）：点二级 → 写回分类行并收起抽屉；
           点一级 → 先把一级名写回，抽屉留着继续选二级（可只记一级）。 */
        if (node.closest('#s14a-cat-sheet')) {
          var pickL2 = node.closest('.cat-l2');
          var pickL1 = pickL2 ? null : node.closest('.cat-l1');
          if (pickL2 || pickL1) {
            var pickScreen = node.closest('.screen');
            var pickPk = node.closest('.cat-picker');
            var pickName = pickL2
              ? ((pickPk && pickPk.getAttribute('data-value')) || pickL2.getAttribute('data-l2') || '')
              : (pickL1.getAttribute('data-l1') || '');
            var pickVal = pickScreen ? pickScreen.querySelector('[data-value-for="cat"]') : null;
            if (pickVal) pickVal.textContent = pickName;
            haptic('light');
            if (pickL2) close('#s14a-cat-sheet');
            return;
          }
        }
        /* s14a 回填：编辑 / 新增交易时用新结构把分类名写进摘要行（s04 脚本只认旧 .s14-cat），
           等它先跑完再补，避免与它的 select 逻辑抢顺序 */
        var txHit = node.closest('[data-tx]');
        if ((txHit && txHit.hasAttribute('data-tx')) || node.closest('[data-newtx]')) {
          var txRow = txHit && txHit.hasAttribute('data-tx') ? txHit : null;
          window.setTimeout(function () {
            var s14a = byId('s14a');
            if (s14a) s14aFill(s14a, txRow);
          }, 0);
        }
        var tabBtn = node.closest('[data-tab]');
        if (tabBtn) { tab(tabBtn.getAttribute('data-tab')); haptic('light'); return; }
        var goto = node.closest('[data-goto]');
        if (goto) { go(goto.getAttribute('data-goto')); haptic('light'); return; }
        var backBtn = node.closest('[data-back]');
        if (backBtn) { back(); haptic('light'); return; }
        var closeBtn = node.closest('[data-close]');
        if (closeBtn) { close(closeBtn.getAttribute('data-close')); haptic('light'); return; }
        if (node.closest('.btn, .fab, .chip, .switch, .icon-btn, button')) haptic('light');
      });
      screenBox.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') back();
      });
    }

    var picker = document.getElementById('devThemePicker');
    if (picker) {
      picker.addEventListener('click', function (e) {
        var b = e.target.closest ? e.target.closest('[data-theme-id]') : null;
        if (b) theme(b.getAttribute('data-theme-id'));
      });
    }
    var states = document.getElementById('devStates');
    if (states) {
      states.addEventListener('click', function (e) {
        var b = e.target.closest ? e.target.closest('[data-state-id]') : null;
        if (!b || !current) return;
        state(current.getAttribute('data-screen'), b.getAttribute('data-state-id'));
        haptic('light');
      });
    }
    var toggle = document.getElementById('devToggle');
    var panel = document.getElementById('devPanel');
    if (toggle && panel) {
      toggle.setAttribute('aria-expanded', 'false');
      toggle.addEventListener('click', function () {
        var show = panel.hidden;
        panel.hidden = !show;
        toggle.setAttribute('aria-expanded', show ? 'true' : 'false');
      });
    }
    var replay = document.getElementById('devReplay');
    if (replay) replay.addEventListener('click', function () { splash(true); });
    var devToast = document.getElementById('devToast');
    if (devToast) devToast.addEventListener('click', function () { toast('已保存 · ' + new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })); });
    var devAutoBook = document.getElementById('devAutoBook');
    if (devAutoBook) devAutoBook.addEventListener('click', function () { openAutoBook(); });
    var devAutoCommit = document.getElementById('devAutoCommit');
    if (devAutoCommit) devAutoCommit.addEventListener('click', function () { autoBookCommitted(null); });
    var devAutoSwitch = document.getElementById('devAutoSwitch');
    if (devAutoSwitch) devAutoSwitch.addEventListener('click', function () {
      if (!abOpenNow()) openAutoBook();   /* 演示入口：没有浮窗就先开，再模拟「切到记账页」 */
      abSwitch();
    });

    document.addEventListener('keydown', function (e) {
      /* Esc 也关不掉浮窗：等价于点遮罩（轻震 + 每笔一次 Toast） */
      if (e.key === 'Escape' && abOpenNow()) { abScrimTap(); e.preventDefault(); return; }
      if (e.altKey && e.key >= '1' && e.key <= '5') {
        var t = TABS[Number(e.key) - 1];
        if (t) { tab(t.id); e.preventDefault(); }
      }
    });
  }

  function syncDev() {
    var themeName = document.getElementById('devTheme');
    if (themeName) themeName.textContent = root.getAttribute('data-theme') || 'apple';
    var screenName = document.getElementById('devScreen');
    if (screenName) screenName.textContent = current ? (current.getAttribute('data-screen') || '-') : '-';
    var picker = document.getElementById('devThemePicker');
    if (picker) {
      Array.prototype.forEach.call(picker.querySelectorAll('[data-theme-id]'), function (b) {
        b.setAttribute('aria-pressed', b.getAttribute('data-theme-id') === (root.getAttribute('data-theme') || 'apple') ? 'true' : 'false');
      });
    }
    var states = document.getElementById('devStates');
    if (states) {
      var cur = currentState();
      Array.prototype.forEach.call(states.querySelectorAll('[data-state-id]'), function (b) {
        b.setAttribute('aria-pressed', b.getAttribute('data-state-id') === cur ? 'true' : 'false');
      });
    }
  }

  function boot() {
    root.setAttribute('data-theme', root.getAttribute('data-theme') || 'apple');
    Charts.hydrate(document);
    var first = byId('s01') || screenList()[0];
    if (first) {
      first.classList.add('is-active');
      current = first;
      syncChrome(first);
    }
    bind();
    mountAutoBook();
    initCategoryPickers(document);
    splash(false);
  }

  window.App = {
    go: go,
    back: back,
    tab: tab,
    toast: toast,
    haptic: haptic,
    state: state,
    theme: theme,
    open: open,
    close: close,
    count: count,
    chart: Charts,
    splash: splash,
    initCategoryPickers: initCategoryPickers,
    openAutoBook: openAutoBook,
    closeAutoBook: hideAutoBook,
    autoBookCommitted: autoBookCommitted,
    current: function () { return current ? current.getAttribute('data-screen') : null; },
    screens: function () { return screenList().map(function (el) { return el.getAttribute('data-screen'); }); },
    stack: function () { return stack.slice(); },
    version: '1.0.0'
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
</script>
<script>
/* URL hash 直达某一屏：08-prototype.html#s14 —— 外壳本身没有屏导航入口，
   评审时用它在屏之间直接跳转，不用开控制台敲 App.go()。 */
(function () {
  function jump() {
    var id = (location.hash || '').replace('#', '');
    if (!id || !window.App || typeof App.screens !== 'function') return;
    if (App.screens().indexOf(id) < 0) return;
    if (App.current && App.current() === id) return;
    App.go(id);
  }
  if (document.readyState === 'complete') setTimeout(jump, 80);
  else window.addEventListener('load', function () { setTimeout(jump, 80); });
  window.addEventListener('hashchange', jump);
})();
</script>
<script>
/* 布局修正：tabbar / gesture-bar 必须位于 device-screen 的 flex 流底部，
   否则会被绝对定位的 device-screen 覆盖，渲染到屏幕顶部。 */
(function () {
  var nav = document.querySelector('.tabbar');
  var gesture = document.querySelector('.gesture-bar');
  var host = document.querySelector('#screenHost');
  if (!nav || !gesture || !host) return;
  var parent = host.parentElement;
  [nav, gesture].forEach(function (el) {
    el.style.position = 'relative';
    el.style.left = 'auto';
    el.style.right = 'auto';
    el.style.bottom = 'auto';
    parent.appendChild(el);
  });
})();
