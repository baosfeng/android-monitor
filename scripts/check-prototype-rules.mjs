#!/usr/bin/env node
/**
 * 原型规则检查：原型分片里「实现侧写不出来」的写法。
 *
 * 为什么需要它：原型在浏览器里自由度无限（任意 px），实现侧受 token 门禁约束。两端自由度
 * 不对称，每轮实现都要把「原型里合法的写法」手工翻译成「实现里合法的写法」，翻译损耗就是
 * 一轮返工。本脚本把损耗量化，并对**新增**损耗判红（存量走 ratchet 基线）。
 *
 * 判据（只看 screens/ 与 styles/20-base.html；tokens 定义处与外壳不进实现）：
 *   1 裸色值        #hex / rgb() / hsl()
 *   2 裸字号        font-size: Npx           —— 不在 --text-* 档位 = 孤儿值
 *   3 裸间距        padding/margin/gap: Npx  —— 不在 --space-* 档位 = 孤儿值
 *   4 裸圆角        border-radius: Npx       —— 不在 --radius-* 档位 = 孤儿值
 *   5 几何字面量    width/height/min-height… —— 非违规，是几何契约导出对象（只记账）
 * 含 var() / calc() / % / em / rem 的值不判定。
 *
 * 用法：
 *   node scripts/check-prototype-rules.mjs            # 新增孤儿值即 exit 1；存量基线只报
 *   node scripts/check-prototype-rules.mjs --warn     # 只报不判红（排查用）
 *   node scripts/check-prototype-rules.mjs --update   # 接受当前孤儿值为基线（修完后也要跑）
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, '.open-design/src');
const BASELINE = path.join(ROOT, 'scripts/prototype-rules-baseline.json');
const WARN = process.argv.includes('--warn');
const UPDATE = process.argv.includes('--update');
const read = (p) => fs.readFileSync(p, 'utf8');
const walk = (dir, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.html')) out.push(p);
  }
  return out;
};

function tokenValues() {
  const text = read(path.join(SRC, 'styles/10-tokens.html'));
  const collect = (prefix) => {
    const set = new Set();
    const re = new RegExp('--' + prefix + '[a-z0-9-]*: *([0-9.]+)px', 'g');
    for (const m of text.matchAll(re)) set.add(Number(m[1]));
    return set;
  };
  return { fontSize: collect('text-'), space: collect('space-'), radius: collect('radius-') };
}

const TOK = tokenValues();
const FILES = [path.join(SRC, 'styles/20-base.html'), ...walk(path.join(SRC, 'screens'))];
const declRe = /([a-z-]+): *([^;{}"']+)/gi;
const pxRe = /(-?[0-9]+(?:\.[0-9]+)?)px/g;
const colorRe = /#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/g;
const SPACE_PROPS = /^(padding|margin|gap|row-gap|column-gap)(-|$)/;
const GEOM_PROPS = /^(width|height|min-width|min-height|max-width|max-height|top|left|right|bottom|inset)(-|$)/;

const rules = { color: [], fontSize: [], space: [], radius: [], geom: [] };
const orphans = new Map();

function record(rule, file, line, decl, value) {
  rules[rule].push({ file, line, decl, value });
  if (rule !== 'fontSize' && rule !== 'space' && rule !== 'radius') return;
  if (TOK[rule].has(value)) return;
  const key = rule + ':' + value;
  if (!orphans.has(key)) orphans.set(key, { key, rule, value, hits: [] });
  orphans.get(key).hits.push({ file, line, decl });
}

for (const file of FILES) {
  const rel = path.relative(ROOT, file);
  read(file).split('\n').forEach((raw, i) => {
    const line = i + 1;
    for (const m of raw.matchAll(colorRe)) record('color', rel, line, m[0], m[0]);
    for (const d of raw.matchAll(declRe)) {
      const prop = d[1].toLowerCase();
      const val = d[2].trim();
      if (val.includes('var(') || val.includes('calc(')) continue;
      if (!/px/.test(val)) continue;
      for (const m of val.matchAll(pxRe)) {
        const n = Number(m[1]);
        if (prop === 'font-size') record('fontSize', rel, line, prop + ': ' + val, n);
        else if (SPACE_PROPS.test(prop)) record('space', rel, line, prop + ': ' + val, n);
        else if (prop === 'border-radius') record('radius', rel, line, prop + ': ' + val, n);
        else if (GEOM_PROPS.test(prop)) record('geom', rel, line, prop + ': ' + val, n);
      }
    }
  });
}

const current = [...orphans.values()].sort((a, b) => b.hits.length - a.hits.length || a.rule.localeCompare(b.rule) || a.value - b.value);
const total = rules.color.length + rules.fontSize.length + rules.space.length + rules.radius.length;

let baseline = [];
try { baseline = JSON.parse(read(BASELINE)); } catch (e) { baseline = []; }
const baseSet = new Set(baseline);
const newOnes = current.filter((o) => !baseSet.has(o.key));
const fixed = baseline.filter((k) => !orphans.has(k));

if (UPDATE) {
  fs.writeFileSync(BASELINE, JSON.stringify(current.map((o) => o.key).sort(), null, 2) + '\n');
  console.log('已写入基线：' + current.length + ' 个孤儿值 → ' + path.relative(ROOT, BASELINE));
}

console.log('# 原型规则检查（screens/ + styles/20-base.html）\n');
console.log('| 规则 | 命中 | 说明 |');
console.log('|---|---|---|');
console.log('| 裸色值 | ' + rules.color.length + ' | 颜色必须走 var(--*) |');
console.log('| 裸字号 | ' + rules.fontSize.length + ' | 不在 --text-* 档位 = 孤儿值 |');
console.log('| 裸间距 | ' + rules.space.length + ' | 不在 --space-* 档位 = 孤儿值 |');
console.log('| 裸圆角 | ' + rules.radius.length + ' | 不在 --radius-* 档位 = 孤儿值 |');
console.log('| 几何字面量 | ' + rules.geom.length + ' | 非违规，是契约导出对象 |');
console.log('\n## 孤儿值（实现侧没有对应 token）\n');
if (!current.length) console.log('无。');
for (const o of current.slice(0, 40)) {
  const s = o.hits[0];
  const mark = baseSet.has(o.key) ? '基线内' : '**新增**';
  console.log('- [' + mark + '] ' + o.rule + ' ' + o.value + 'px × ' + o.hits.length + ' ｜ 例 ' + s.file + ':' + s.line + '  ' + s.decl);
}
if (current.length > 40) console.log('- …另有 ' + (current.length - 40) + ' 种孤儿值');
console.log('\n汇总：孤儿值 ' + current.length + ' 种 / ' + current.reduce((n, o) => n + o.hits.length, 0) + ' 处；新增 ' + newOnes.length + ' 种；可缩基线 ' + fixed.length + ' 种');

if (fixed.length) {
  console.log('\n以下基线项已修复，跑 --update 缩掉：' + fixed.join(', '));
}
if (!UPDATE && !WARN && newOnes.length) {
  console.log('\n判定：FAIL（新增孤儿值 ' + newOnes.length + ' 种）。处理方式二选一：补 token（10-tokens.html + 重跑生成器）或改回原型档位；确认接受后跑 --update。');
  process.exit(1);
}
console.log('\n判定：' + (WARN ? 'PASS（--warn 只报）' : 'PASS'));
