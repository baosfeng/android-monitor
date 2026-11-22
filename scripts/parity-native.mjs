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

const load = (p) => {
  const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
  return raw.screens || raw;
};
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
  for (const pi of p.items || []) {
    if (!pi.visible) continue;
    const ii = byId.get(pi.id);
    if (!ii || !ii.visible) {
      findings.push({ screen: s, id: pi.id, level: 'P0', kind: '缺块', detail: ii ? '实现侧不可见' : '实现侧无此锚点' });
      continue;
    }
    checked++;
    const record = (kind, level, detail) => {
      const key = s + '\t' + pi.id + '\t' + kind;
      if (known.has(key)) { knownSkipped.push({ screen: s, id: pi.id, kind, reason: known.get(key) }); return; }
      findings.push({ screen: s, id: pi.id, level, kind, detail });
    };
    if (pi.fs != null && ii.fs != null && Math.abs(px(pi.fs) - px(ii.fs)) > 0.5) {
      record('字号', 'P0', 'proto ' + px(pi.fs) + ' / impl ' + px(ii.fs));
    }
    // 滚动容器（-content）的高度由视口决定，长页导出必然不一致，只比存在性
    const skipGeom = /-content$/.test(pi.id);
    // 面板容器（-pane-* / s04-list）：原型锚点挂在整个 pane（含明细列表）上，实现挂在
    // pane 的标题行上 —— 高度必然差一个列表的高度，只跳过 h，位置仍然要比
    const skipHeight = /-pane-/.test(pi.id) || pi.id === 's04-list';
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
for (const k of knownSkipped) console.log('- [已知] ' + k.screen + ' · ' + k.id + ' · ' + k.kind + '：' + k.reason);
process.exit(p0.length ? 1 : 0);