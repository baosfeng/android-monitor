#!/usr/bin/env node
/**
 * 原型 ↔ 实现 锚点对照（原生端）。
 *
 * 背景：odops parity 只对比两个 HTML 页面，实现是 Android App 时用不了。
 * 本脚本统一两边的数据结构后逐锚点比：缺块 / 字号 / 圆角 / 背景色 / 文案 / 几何。
 *
 * 输入格式（两边同构，二选一）：
 *   { "s04": { "box": {"w":393,"h":738}, "items":[ {id,x,y,w,h,fs,color,bg,radius,pad[4],text,visible} ] } }
 *   或 { "screens": { ...同上 } }
 * 坐标约定：相对「屏幕内容区」左上角；原型侧单位 px，实现侧单位 dp（1:1）。
 *
 * 用法：
 *   node scripts/parity-native.mjs --proto <proto.json> --impl <impl.json> [--screen s04] [--tol 3] [--json]
 * 退出码：0 无 P0；1 有 P0（缺块 / 字号差 / 可见性相反）；2 用法错。
 */
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : fallback;
};
const PROTO = opt('proto');
const IMPL = opt('impl');
const ONLY = opt('screen');
const TOL = Number(opt('tol', '3'));
const JSON_OUT = args.includes('--json');
if (!PROTO || !IMPL) {
  console.error('用法：node scripts/parity-native.mjs --proto <proto.json> --impl <impl.json> [--screen s04] [--tol 3] [--json]');
  process.exit(2);
}

// 「已知口径差」白名单：产品口径刻意不同 / 字体度量不可消除的项，显式登记后才跳过，
// 不静默吞掉。文件格式见 scripts/parity-known-diffs.tsv。
const KNOWN_FILE = 'scripts/parity-known-diffs.tsv';
const known = new Map();
if (fs.existsSync(KNOWN_FILE)) {
  for (const line of fs.readFileSync(KNOWN_FILE, 'utf8').split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    const [screen, id, kind, reason] = line.split('\t');
    if (screen && id && kind) known.set(screen + '\t' + id + '\t' + kind, reason || '');
  }
}
const knownSkipped = [];

// 已登记「不实现」的锚点（scripts/anchor-retired.tsv）：作废 / 原型外壳 / 待实现。
// 这三类在实现侧本来就不该有 testTag，判 P0 会让整屏永远红；直接读同一份清单，
// 避免在 parity-known-diffs.tsv 里重复登记、两处漂移。命中时照旧打印来源与理由。
const RETIRED_FILE = 'scripts/anchor-retired.tsv';
const retired = new Map();
if (fs.existsSync(RETIRED_FILE)) {
  for (const line of fs.readFileSync(RETIRED_FILE, 'utf8').split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    const [id, , status, reason] = line.split('	');
    if (id && status) retired.set(id, status + '：' + (reason || ''));
  }
}

const load = (p) => {
  const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
  return raw.screens || raw;
};

// 「同屏多页 / 坐标原点」拼接表：一张原型页被实现拆成多页（设置 s09），或两端原点不同
// （原型 appbar 由外壳生成、实现自绘导航栏占 70dp）时，逐页按对齐锚点整体平移 dy 再合并。
// 不登记在这里的屏严格按原坐标比。格式见 scripts/parity-compose.tsv。
const COMPOSE_FILE = 'scripts/parity-compose.tsv';
const composeRows = new Map();
if (fs.existsSync(COMPOSE_FILE)) {
  for (const line of fs.readFileSync(COMPOSE_FILE, 'utf8').split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue;
    const [screen, file, ref, reason] = line.split('\t');
    if (!screen || !file) continue;
    if (!composeRows.has(screen)) composeRows.set(screen, []);
    composeRows.get(screen).push({ file, ref, reason: reason || '' });
  }
}
const composedLog = [];
// 原型靠 DOM 空格分隔标签与数值、实现靠布局：文案比较一律去掉全部空白
const norm = (s) => (s || '').replace(/\s+/g, '');
const px = (v) => (typeof v === 'number' ? v : parseFloat(v) || 0);

const proto = load(PROTO);
const impl = load(IMPL);

const findings = [];
const screens = Object.keys(proto).filter((s) => !ONLY || s === ONLY);
let checked = 0;

for (const s of screens) {
  const p = proto[s];
  const i = impl[s];
  if (!i) {
    findings.push({ screen: s, id: '-', level: 'P0', kind: '缺屏', detail: '实现侧没有该屏' });
    continue;
  }
  const byId = new Map((i.items || []).map((it) => [it.id, it]));
  // 拼接表命中：逐页按对齐锚点平移 dy 后合并（同名锚点先到先得）
  const compose = composeRows.get(s);
  if (compose) {
    byId.clear();
    for (const row of compose) {
      const file = path.resolve(path.dirname(IMPL), row.file);
      if (!fs.existsSync(file)) {
        composedLog.push({ screen: s, file: row.file, ref: row.ref, dy: null, reason: '实现文件不存在，跳过' });
        continue;
      }
      const part = load(file);
      const page = part[s] || part[Object.keys(part)[0]];
      if (!page) { composedLog.push({ screen: s, file: row.file, ref: row.ref, dy: null, reason: '实现文件里没有该屏' }); continue; }
      const pi = row.ref === '-' ? null : (p.items || []).find((it) => it.id === row.ref);
      const ii = row.ref === '-' ? null : (page.items || []).find((it) => it.id === row.ref);
      const dy = pi && ii ? px(pi.y) - px(ii.y) : 0;
      if (row.ref !== '-' && !(pi && ii)) {
        composedLog.push({ screen: s, file: row.file, ref: row.ref, dy: null, reason: '对齐锚点两端缺一，按原坐标拼' });
      }
      for (const it of page.items || []) {
        if (!byId.has(it.id)) byId.set(it.id, { ...it, y: px(it.y) + dy });
      }
      composedLog.push({ screen: s, file: row.file, ref: row.ref, dy, reason: row.reason, merged: byId.size });
    }
  }
  for (const pi of p.items || []) {
    if (!pi.visible) continue;
    const ii = byId.get(pi.id);
    const record = (kind, level, detail) => {
      const key = s + '\t' + pi.id + '\t' + kind;
      if (known.has(key)) { knownSkipped.push({ screen: s, id: pi.id, kind, reason: known.get(key) }); return; }
      findings.push({ screen: s, id: pi.id, level, kind, detail });
    };
    if (!ii || !ii.visible) {
      // 缺块先看「不实现」清单，再走已知口径差白名单；两者都没登记才是真 P0。
      const why = retired.get(pi.id);
      if (why) {
        knownSkipped.push({ screen: s, id: pi.id, kind: '缺块', reason: why });
      } else {
        record('缺块', 'P0', ii ? '实现侧不可见' : '实现侧无此锚点');
      }
      continue;
    }
    checked++;
    if (pi.fs != null && ii.fs != null && Math.abs(px(pi.fs) - px(ii.fs)) > 0.5) {
      record('字号', 'P0', 'proto ' + px(pi.fs) + ' / impl ' + px(ii.fs));
    }
    // 滚动容器（-content / -body / -timeline）的高度由视口决定，长页导出必然不一致，只比存在性
    const skipGeom = /-(content|body|timeline)$/.test(pi.id);
    // 面板容器（-pane-* / s04-list）：原型锚点挂在整个 pane（含明细列表）上，实现挂在
    // pane 的标题行上 —— 高度必然差一个列表的高度。
    // 列表容器同理（-records / -rules / -list）：原型挂的是「整块列表滚动容器」，
    // 实现挂的是小节头或列表本体，高度差等于列表长度差，不是排版缺陷。
    // 以上两类只跳过 h，位置仍然要比。
    // 小时组卡（sNN-g09 这类）：原型是一整块「组头 + 行 + 卡底」，实现里组头是 LazyColumn 的
    // stickyHeader、行是同级 item，整组不是单个节点 —— 高度无从对齐，只比组内锚点。
    const skipHeight =
      /-pane-/.test(pi.id) ||
      /-(records|rules|list|summary|legend)$/.test(pi.id) ||
      /^s\d\d[ab]?-g\d\d$/.test(pi.id) ||
      pi.id === 's04-list';
    const d = {
      x: Math.abs(px(pi.x) - px(ii.x)),
      y: Math.abs(px(pi.y) - px(ii.y)),
      w: Math.abs(px(pi.w) - px(ii.w)),
      h: Math.abs(px(pi.h) - px(ii.h)),
    };
    // 长页滚动后 y 是视口坐标，两端口径不同：y 差超过一屏时只比 x/w/h
    const scrolled = d.y > 200;
    const bad = skipGeom ? [] : Object.entries(d).filter(([k, v]) =>
      v > TOL && !(scrolled && k === 'y') && !(skipHeight && k === 'h'));
    if (bad.length) {
      record('几何', 'P1', bad.map(([k, v]) => k + ' 差 ' + v.toFixed(1)).join(', ') + '（容差 ' + TOL + '）');
    }
    const pr = pi.radius == null ? null : px(pi.radius);
    const ir = ii.radius == null ? null : px(ii.radius);
    if (pr != null && ir != null && Math.abs(pr - ir) > 1 && !(pr >= 900 && ir >= 900)) {
      record('圆角', 'P1', 'proto ' + pr + ' / impl ' + ir);
    }
    const pc = norm(pi.text);
    const ic = norm(ii.text);
    if (pc && ic && !pc.includes(ic) && !ic.includes(pc)) {
      record('文案', 'P1', 'proto「' + pc.slice(0, 30) + '」/ impl「' + ic.slice(0, 30) + '」');
    }
  }
}

const p0 = findings.filter((f) => f.level === 'P0');
const p1 = findings.filter((f) => f.level === 'P1');
const byKind = findings.reduce((m, f) => ((m[f.kind] = (m[f.kind] || 0) + 1), m), {});

if (JSON_OUT) {
  console.log(JSON.stringify({ checked, p0: p0.length, p1: p1.length, byKind, findings }, null, 2));
} else {
  console.log('# 原型 ↔ 实现 锚点对照（' + screens.length + ' 屏，容差 ' + TOL + '）\n');
  console.log('检查锚点 ' + checked + ' 个 | P0 ' + p0.length + ' | P1 ' + p1.length);
  console.log('按类型：' + (Object.entries(byKind).map(([k, v]) => k + ' ' + v).join(' · ') || '全一致') +
    (knownSkipped.length ? ' ｜ 已知口径差跳过 ' + knownSkipped.length + ' 条（见台账）' : ''));
  for (const f of findings.slice(0, 40)) {
    console.log('- [' + f.level + '] ' + f.screen + ' · ' + f.id + ' · ' + f.kind + '：' + f.detail);
  }
  if (findings.length > 40) console.log('- …另有 ' + (findings.length - 40) + ' 条');
}
for (const a of composedLog) {
  console.log('- [拼接] ' + a.screen + ' · ' + a.file + ' · ref=' + a.ref + ' · dy=' +
    (a.dy == null ? '—' : a.dy.toFixed(1)) + (a.merged != null ? ' · 合并后 ' + a.merged + ' 个锚点' : '') +
    '：' + a.reason);
}
for (const k of knownSkipped) console.log('- [已知] ' + k.screen + ' · ' + k.id + ' · ' + k.kind + '：' + k.reason);
process.exit(p0.length ? 1 : 0);