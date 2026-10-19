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

    document.addEventListener('keydown', function (e) {
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
