#!/usr/bin/env node
/**
 * 按 Tab 拆分原型：.open-design/src/ 分片 → .open-design/tabs/tab-*.html
 *
 * 与 assemble-prototype.mjs 的分工：
 *   · assemble → 08-prototype.html（单文件，Pages / README 截图 / odops verify 的唯一输入，保持不变）
 *   · 本脚本   → 每个 Tab 一个自包含 html（可单独双击打开、单独点击）
 *
 * 每个产物：CSS 内联、无外链、只含本 Tab 的屏；同文件内的返回栈 / 弹窗 / Toast / 图表 / devbar 主题照旧；
 * 跨 Tab 的跳转（底部导航、data-goto 指向别的 Tab 的屏）改成真链接或真跳转 tab-*.html#sN。
 *
 * 用法：
 *   node scripts/split-prototype-tabs.mjs           # 生成 .open-design/tabs/tab-*.html
 *   node scripts/split-prototype-tabs.mjs --check   # 只校验产物与分片是否同步（CI 用）
 *   node scripts/split-prototype-tabs.mjs --out <目录> [--no-safe-area]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, '.open-design/src');
const args = process.argv.slice(2);
const check = args.includes('--check');
const safeArea = !args.includes('--no-safe-area');
const outIdx = args.indexOf('--out');
const OUT_DIR = outIdx >= 0 ? path.resolve(args[outIdx + 1]) : path.join(ROOT, '.open-design/tabs');

/* 安全区档位：与 08-prototype.html 的 36 / 52 / 22 不同，拆分页按 44 / 56 / 56 / 24。
   只覆盖 4 个骨架 token，不动任何屏的结构与排版（--no-safe-area 可关掉这层覆盖）。 */
const SAFE_AREA = { statusbar: 44, appbar: 56, tab: 56, gesture: 24 };

/**
 * 归属表：一个 Tab 一个文件。
 * 判据 = 99-app.js 的 TABS（六个 Tab 的首页）+ 全分片 data-goto / App.go 的跳转闭包：
 *   · s16 每日行程      ← s01「今日行程」卡（data-goto="s16"）
 *   · s22…s27 验证器族   ← s09「管理账户 / 备份与导入」（s01 顶栏「验证器」也指向 s22，主入口在设置）
 *   · s17 页面记录粒度   ← s09（data-goto="s17"）
 *   · s14a 记一笔 / s15 自动记账浮窗 ← s04 的 FAB 与自动记账链路，均在记账 Tab 内
 */
const TABS = [
  { id: 'overview', label: '概览', screen: 's01', file: 'tab-overview.html', splash: true,
    screens: ['s01', 's16'] },
  { id: 'live', label: '实时', screen: 's02', file: 'tab-live.html', screens: ['s02'] },
  { id: 'ad', label: '广告', screen: 's03', file: 'tab-ad.html', screens: ['s03'] },
  { id: 'finance', label: '记账', screen: 's04', file: 'tab-finance.html',
    screens: ['s04', 's05', 's06', 's07', 's08', 's14a', 's15'] },
  { id: 'quota', label: '额度', screen: 's18', file: 'tab-quota.html',
    screens: ['s18', 's19'] },
  { id: 'settings', label: '设置', screen: 's09', file: 'tab-settings.html',
    screens: ['s09', 's10', 's11', 's12', 's13', 's17', 's22', 's23', 's24', 's25', 's26', 's27'] },
];

const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8').replace(/\s+$/, '');
const fail = (msg) => { throw new Error(msg); };

/** 分片 → { file, style, section, screens[] }；标记必须独占一行（与 assemble 同口径）。 */
function parseShard(file) {
  const lines = read(path.join('screens', file)).split('\n');
  const at = (m) => {
    const i = lines.findIndex((l) => l.trim() === m);
    if (i < 0) fail(file + ': 缺少独占一行的 ' + m + ' 标记');
    return i;
  };
  const iStyle = at('<!--@od:style-->'), iSection = at('<!--@od:section-->'), iEnd = at('<!--@od:end-->');
  if (!(iStyle < iSection && iSection < iEnd)) fail(file + ': 标记顺序应为 style → section → end');
  const style = lines.slice(iStyle + 1, iSection).join('\n').trim();
  const section = lines.slice(iSection + 1, iEnd).join('\n').trim();
  const screens = [...section.matchAll(/<section[^>]*\bdata-screen="([^"]+)"/g)].map((m) => m[1]);
  return { file, style, section, screens };
}

const shardFiles = fs.readdirSync(path.join(SRC, 'screens')).filter((f) => f.endsWith('.html'))
  .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
const shards = shardFiles.map(parseShard);
const allScreens = new Set(shards.flatMap((s) => s.screens));

/* 样式依赖闭包：某屏用到的类若只在别的 Tab 的分片里定义，把那段样式一并带上（否则静默丢样式） */
const baseCss = read('styles/20-base.html');
const baseClasses = new Set([...baseCss.matchAll(/\.([A-Za-z_][\w-]*)/g)].map((m) => m[1]));
const classesOf = (text) => {
  const set = new Set();
  for (const m of text.matchAll(/class=['"]([^'"]*)['"]/g)) {
    m[1].split(/\s+/).filter(Boolean).forEach((c) => set.add(c));
  }
  return set;
};
const definedIn = new Map();
for (const s of shards) {
  for (const m of s.style.matchAll(/\.([A-Za-z_][\w-]*)/g)) {
    if (!definedIn.has(m[1])) definedIn.set(m[1], new Set());
    definedIn.get(m[1]).add(s.file);
  }
}

/* 归属表必须覆盖全部分片里的屏：新增屏若忘了归 Tab，产物会静默少一屏 —— 直接报错 */
const assigned = new Set(TABS.flatMap((t) => t.screens));
const unassigned = [...allScreens].filter((s) => !assigned.has(s)).sort();
if (unassigned.length) fail('这些屏没有归到任何 Tab（补 TABS[].screens）：' + unassigned.join(' '));
const duplicated = TABS.flatMap((t) => t.screens).filter((s, i, arr) => arr.indexOf(s) !== i);
if (duplicated.length) fail('同一个屏被归到多个 Tab：' + duplicated.join(' '));

/* 99-app.js 定点改写：每处断言只命中一次，分片一改就报错，而不是静默产出坏页 */
function patch(text, needle, replacement, label) {
  const n = text.split(needle).length - 1;
  if (n !== 1) fail('99-app.js 改写点「' + label + '」命中 ' + n + ' 次（应为 1）：' + needle.slice(0, 50));
  return text.split(needle).join(replacement);
}

function buildAppJs(tab) {
  let js = read('99-app.js');
  const local = TABS.filter((t) => t.id === tab.id)
    .map((t) => "    { id: '" + t.id + "', screen: '" + t.screen + "' }").join(',\n');
  js = js.replace(/ {2}var TABS = \[\n[\s\S]*?\n {2}\];/, () => '  var TABS = [\n' + local + '\n  ];');
  if (js.indexOf("id: '" + tab.id + "'") < 0) fail(tab.file + ': TABS 注入失败');

  js = patch(js, '    if (!found) return false;', [
    '    if (!found) {',
    '      /* 跨文件：别的 Tab 的首页 → 真跳转 tab-*.html（拆分页新增） */',
    '      var tabMap = window.__OD_TAB_FILE || {};',
    '      var screenTab = window.__OD_TAB_OF_SCREEN || {};',
    '      var toFile = tabMap[key] || tabMap[screenTab[key]];',
    '      if (toFile) { window.location.href = toFile; return false; }',
    '      return false;',
    '    }',
  ].join('\n'), 'tab() 跨文件兜底');

  js = patch(js,
    "    if (!next) { console.warn('[App.go] 未找到屏', id); return false; }",
    [
      '    if (!next) {',
      '      /* 跨文件：目标屏不在本文件 → 真跳转 tab-*.html#<屏 id>（拆分页新增） */',
      '      var fileMap = window.__OD_FILE_OF_SCREEN || {};',
      "      if (fileMap[id]) { window.location.href = fileMap[id] + '#' + id; return false; }",
      "      console.warn('[App.go] 未找到屏', id);",
      '      return false;',
      '    }',
    ].join('\n'), 'go() 跨文件兜底');

  js = patch(js, "    var first = byId('s01') || screenList()[0];",
    "    var first = byId(window.__OD_HOME || 's01') || screenList()[0];", 'boot 首屏');
  js = patch(js, '    splash(false);',
    '    if (window.__OD_SPLASH_ON_LOAD !== false) splash(false);', 'boot 开屏');
  js = patch(js, "    root.setAttribute('data-theme', id);",
    "    root.setAttribute('data-theme', id);\n    try { window.localStorage.setItem('od-theme', id); } catch (err) {}",
    '主题持久化');
  js = patch(js, '        var t = TABS[Number(e.key) - 1];',
    '        var t = (window.__OD_TAB_ORDER || TABS)[Number(e.key) - 1];', 'Alt+数字跨文件');
  return js;
}

/* devbar 里指向别的 Tab 的按钮要摘掉，否则出现「点不动的空按钮」 */
const DEV_REQUIRES = {
  'dev-autobook': ['s15'], 'dev-autocommit': ['s04'], 'dev-autoswitch': ['s15', 's04'],
};
function buildDevbar(local) {
  let html = read('90-devbar.html');
  for (const [id, need] of Object.entries(DEV_REQUIRES)) {
    if (need.every((s) => local.has(s))) continue;
    html = html.split('\n').filter((l) => l.indexOf('data-od-id="' + id + '"') < 0).join('\n');
  }
  /* 摘掉按钮后变空的 dev-actions 组一并删掉，避免面板里出现空行 */
  return html.replace(/\n {4}<div class="dev-group dev-actions">\s*<\/div>/g, '');
}

/* 底部导航：本 Tab = button（原地切屏，保留选中态语义）；别的 Tab = 真 <a href="tab-*.html"> */
function navItems() {
  const nav = read('00-shell.html').match(/<nav class="tabbar"[\s\S]*?<\/nav>/);
  if (!nav) fail('00-shell.html 未找到 <nav class="tabbar">');
  const items = [];
  const re = /<button class="tab"[^>]*data-tab="([^"]+)"[^>]*>([\s\S]*?)<\/button>/g;
  let m;
  while ((m = re.exec(nav[0]))) items.push({ id: m[1], inner: m[2].trim() });
  if (items.length !== TABS.length) fail('tabbar 项数 ' + items.length + ' 与归属表 ' + TABS.length + ' 不一致');
  return items;
}
function buildTabbar(tab) {
  const items = navItems();
  const rows = TABS.map((t) => {
    const item = items.filter((i) => i.id === t.id)[0];
    if (!item) fail('tabbar 缺少 ' + t.id);
    const attrs = ' role="tab" data-tab="' + t.id + '" data-od-id="tab-' + t.id + '"';
    return t.id === tab.id
      ? '          <button class="tab" type="button"' + attrs + ' aria-selected="true">' + item.inner + '</button>'
      : '          <a class="tab" href="' + t.file + '"' + attrs + ' aria-selected="false">' + item.inner + '</a>';
  });
  return '<nav class="tabbar" id="odTabbar" role="tablist" aria-label="主导航" data-od-min-fs data-od-id="tabbar">\n'
    + rows.join('\n') + '\n        </nav>';
}

/* 拆分页专属样式：安全区档位 + 链接态导航。不动任何屏的结构或内部排版。 */
function splitCss() {
  const head = safeArea
    ? ':root{--statusbar-h:' + SAFE_AREA.statusbar + 'px;--appbar-h:' + SAFE_AREA.appbar
      + 'px;--tab-h:' + SAFE_AREA.tab + 'px;--gesture-h:' + SAFE_AREA.gesture + 'px}'
    : '/* --no-safe-area：沿用 08-prototype.html 的 36 / 52 / 22 */';
  return [
    '<style data-for="tabs-split">',
    head,
    /* 底部 Tab 固定 56：外壳里 tabbar 高度原本由 .tab min-height 撑出（52 + 4 内边距），token 只用于换算 */
    '.tabbar{height:var(--tab-h)}',
    '.tabbar a.tab{text-decoration:none}',
    '.tabbar a.tab:focus-visible{outline:0;box-shadow:var(--focus-ring)}',
    '/* 独立页在手机宽度下满屏显示：去掉桌面演示用的机身外框与留白（393 下画布不再横向溢出），',
    '   桌面宽度仍保留机身框。只动原型外壳，不动任何屏内部的结构与排版。 */',
    '@media (max-width: 460px) {',
    '  /* 画布跟随视口：更窄的窗口（如 390）不再横向溢出 3px；393×852 时取值不变 */',
    '  :root{--screen-w:min(393px,100vw);--screen-h:min(852px,100vh)}',
    '  body{padding:0}',
    '  .device-frame{padding:0;border-radius:0;background:none;box-shadow:none}',
    '  .dev-key{display:none}',
    '  .device{border-radius:0}',
    '  /* 原型控制条让位：默认贴底居中偏右，会压住右侧两个 Tab 与记账 FAB（都点不动）。',
    '     挪到「Tab 栏上方、靠左」——右边留给 FAB，底部留给 Tab 栏。 */',
    '  .devbar{left:12px;right:auto;bottom:calc(var(--tab-h) + var(--gesture-h) + 12px);align-items:flex-start}',
    '  .devbar-panel{width:min(344px,calc(100vw - 24px))}',
    '  /* 窄屏收起成 44×44 圆钮，尽量少盖内容（文字标签只在宽屏显示） */',
    '  .devbar-toggle{width:44px;height:44px;min-height:44px;padding:0;justify-content:center}',
    '  .devbar-toggle span{display:none}',
    '}',
    '</style>',
  ].join('\n');
}

function buildPrelude(tab) {
  const tabFile = {}, tabOfScreen = {}, fileOfScreen = {};
  for (const t of TABS) {
    tabFile[t.id] = t.file;
    for (const s of t.screens) { tabOfScreen[s] = t.id; fileOfScreen[s] = t.file; }
  }
  const order = TABS.map((t) => "{ id: '" + t.id + "', screen: '" + t.screen + "', file: '" + t.file + "' }");
  return [
    '<script>',
    '/* 拆分页运行时：首屏 / 开屏开关 / 跨文件跳转表 / 主题延续（由 scripts/split-prototype-tabs.mjs 生成） */',
    'window.__OD_HOME = ' + JSON.stringify(tab.screen) + ';',
    /* 开屏只在「直接打开这个 Tab」时播：从别的 tab-*.html 跳进来（referrer 是 tab 页）或带 #屏号 直达时不播，
       否则每次点「概览」都要等 1.9s 开屏。 */
    'window.__OD_SPLASH_ON_LOAD = ' + (tab.splash
      ? 'splashWanted()' : 'false') + ';',
    'function splashWanted() {',
    '  try {',
    "    var ref = document.referrer || '';",
    "    var fromTab = ref.slice(-5) === '.html' && ref.lastIndexOf('/tab-') > 0;",
    '    if (fromTab) return false;',
    '    return !location.hash;',
    '  } catch (err) { return true; }',
    '}',
    'window.__OD_TAB_FILE = ' + JSON.stringify(tabFile) + ';',
    'window.__OD_TAB_OF_SCREEN = ' + JSON.stringify(tabOfScreen) + ';',
    'window.__OD_FILE_OF_SCREEN = ' + JSON.stringify(fileOfScreen) + ';',
    'window.__OD_TAB_ORDER = [' + order.join(', ') + '];',
    '(function () {',
    '  try {',
    "    var t = window.localStorage.getItem('od-theme');",
    "    if (t) document.documentElement.setAttribute('data-theme', t);",
    '  } catch (err) {}',
    '})();',
    '</script>',
  ].join('\n');
}

function buildShell(tab, prelude) {
  let html = read('00-shell.html');
  html = html.replace('<title>行为记录 · 交互原型</title>',
    '<title>行为记录 · 交互原型 · ' + tab.label + '</title>');
  html = html.replace(/<nav class="tabbar"[\s\S]*?<\/nav>/, buildTabbar(tab));
  html = html.replace('<!--@od:devbar-->', buildDevbar(new Set(tab.screens)) + '\n\n' + prelude);
  return html;
}

function buildPage(tab) {
  const local = new Set(tab.screens);
  const unknown = tab.screens.filter((s) => !allScreens.has(s));
  if (unknown.length) fail(tab.file + ': 归属表里的屏不存在于分片：' + unknown.join(' '));

  const own = shards.filter((s) => s.screens.length && s.screens.every((id) => local.has(id)));
  const covered = new Set(own.flatMap((s) => s.screens));
  const missing = tab.screens.filter((s) => !covered.has(s));
  if (missing.length) fail(tab.file + ': 这些屏的分片还含别的屏（不支持半片拆分）：' + missing.join(' '));

  const extra = new Set();
  for (const s of own) {
    for (const cls of classesOf(s.section)) {
      if (baseClasses.has(cls)) continue;
      const defs = definedIn.get(cls);
      if (!defs) continue;
      if ([...defs].some((f) => own.some((o) => o.file === f))) continue;
      defs.forEach((f) => extra.add(f));
    }
  }
  const styleShards = [...own, ...shards.filter((s) => extra.has(s.file))];

  let splashHtml = read('10-splash.html');
  if (!tab.splash) {
    splashHtml = splashHtml.replace('<div class="splash" id="odSplash" data-od-id="splash">',
      '<div class="splash" id="odSplash" hidden data-od-id="splash">');
    if (splashHtml.indexOf('id="odSplash" hidden') < 0) fail('10-splash.html 结构已变更，开屏隐藏改写失败');
  }

  const replacements = {
    '<!--@od:base-styles-->': [read('styles/10-tokens.html'), baseCss].join('\n\n'),
    '<!--@od:styles-->': styleShards.filter((s) => s.style).map((s) => s.style).join('\n\n')
      + '\n\n' + splitCss(),
    '<!--@od:screens-->': own.filter((s) => s.section).map((s) => s.section).join('\n\n'),
    '<!--@od:splash-->': splashHtml,
    '<!--@od:scripts-->': '<script>\n' + buildAppJs(tab) + '\n</script>',
  };
  let html = buildShell(tab, buildPrelude(tab));
  for (const [token, body] of Object.entries(replacements)) {
    if (html.indexOf(token) < 0) fail('00-shell.html 缺少占位 ' + token);
    html = html.split(token).join(body);
  }
  if (html.indexOf('<!--@od:') >= 0) fail(tab.file + ': 仍有未替换的占位');

  const banner = [
    '<!doctype html>',
    '<!--',
    '  ' + tab.file + ' · 由 scripts/split-prototype-tabs.mjs 从 .open-design/src/ 自动生成，请勿直接编辑。',
    '  Tab：' + tab.label + '（' + tab.id + '） · 本文件屏：' + tab.screens.join(' / '),
    '  源分片：' + own.map((s) => 'screens/' + s.file).join('、'),
    extra.size ? '  跨 Tab 样式依赖：' + [...extra].join('、') : '',
    '  改界面改 src/screens/ 下对应分片，改完全局骨架跑 node scripts/split-prototype-tabs.mjs 重新生成。',
    '-->',
  ].filter(Boolean).join('\n');
  return { html: html.replace('<!doctype html>', banner), own, extra: [...extra] };
}

const built = TABS.map((tab) => {
  const page = buildPage(tab);
  const target = path.join(OUT_DIR, tab.file);
  const prev = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : '';
  return { tab, target, html: page.html, prev, own: page.own, extra: page.extra };
});

if (check) {
  const drift = built.filter((r) => r.html !== r.prev).map((r) => r.tab.file);
  if (drift.length) {
    console.error('拆分产物与分片不一致：' + drift.join(' '));
    console.error('请执行 node scripts/split-prototype-tabs.mjs 重新生成');
    process.exit(1);
  }
  console.log('拆分产物与分片一致 ✓（' + built.length + ' 个 Tab 文件）');
  process.exit(0);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
let totalBytes = 0, totalLines = 0;
for (const r of built) {
  fs.writeFileSync(r.target, r.html);
  const bytes = Buffer.byteLength(r.html), lines = r.html.split('\n').length;
  totalBytes += bytes; totalLines += lines;
  console.log(
    r.tab.file.padEnd(20) + ' ' + (bytes / 1024).toFixed(1).padStart(6) + ' KB · '
    + String(lines).padStart(5) + ' 行 · 屏 ' + r.tab.screens.join(',')
    + (r.extra.length ? ' · 跨 Tab 样式 ' + r.extra.join(',') : ''));
}
console.log('共 ' + built.length + ' 个文件 · ' + (totalBytes / 1024).toFixed(1) + ' KB · '
  + totalLines + ' 行 · 输出 ' + path.relative(ROOT, OUT_DIR) + '/');
