#!/usr/bin/env node
// 交互契约锚点对账：契约表格第一列 ↔ 代码 Modifier.testTag。
//
// 为什么需要它：锚点覆盖率是交付的必要条件——「该打标的地方打了标」证明不了
// 「点了会有该有的反应」，但连锚点都对不上时，任何行为验收都无从谈起。
// 契约、原型、代码三方靠同一套锚点字符串对账，不改号、不转下划线。
//
// 判据：
//   1. 契约锚点 = docs/交互设计/s*.md 表格第一列（去重；同一锚点可有多行触发；字符集含 . 与 -）
//   2. 代码锚点 = testTag("X") 字面量 + 映射表/常量里的锚点字符串（排除 token 表）
//   3. 运行时拼接 = 代码里以 - 结尾的前缀（如 s15-l1-），能覆盖以它开头的契约锚点
//   4. 自增 = 代码有、契约无 —— 这是硬错误：锚点集合必须能逐字对账
//
// 用法：
//   node scripts/check-anchors.mjs            # 打印四列账本，自增非 0 时 exit 1
//   node scripts/check-anchors.mjs --strict   # 缺失也非 0 时 exit 1（清理完后再启用）
//   node scripts/check-anchors.mjs --json     # 机器可读输出
//
// 已知局限：testTag 参数是复杂表达式（如三元、函数调用返回）时无法静态求值，
// 这类锚点会落进「缺失」——实现侧应把锚点写成字符串字面量或映射表常量。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONTRACT_DIR = path.join(ROOT, 'docs/交互设计');
const SRC_DIR = path.join(ROOT, 'app/src/main/java');

const args = new Set(process.argv.slice(2));
const STRICT = args.has('--strict');
const JSON_OUT = args.has('--json');

/** 契约表格第一列的锚点，去重。 */
function readContractAnchors() {
  const anchors = new Map();
  const files = fs.readdirSync(CONTRACT_DIR).filter((f) => /^s\d/.test(f) && f.endsWith('.md'));
  for (const file of files) {
    const text = fs.readFileSync(path.join(CONTRACT_DIR, file), 'utf8');
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (!line.startsWith('|')) continue;
      const anchor = line.split('|').map((s) => s.trim())[1];
      if (!anchor || anchor === '锚点' || /^[-:]+$/.test(anchor)) continue;
      // 字符集含 "."：组件级锚点用 组件名.用途（chip.option / button.primary），见 s12a 组件级锚点登记
      if (!/^[A-Za-z0-9][A-Za-z0-9.-]*$/.test(anchor)) continue;
      if (!anchors.has(anchor)) anchors.set(anchor, new Set());
      anchors.get(anchor).add(file);
    }
  }
  return anchors;
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (entry.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

/** 代码侧：testTag 字面量、映射表常量、运行时前缀。 */
function readCodeAnchors() {
  const literal = new Set();
  const mapped = new Map();
  const prefixes = new Set();
  const isTokenFile = (f) => /ui\/theme\/(DesignTokenSpecs|GeneratedTokens)/.test(f);
  const files = walk(SRC_DIR);
  for (const file of files) {
    const lines = fs.readFileSync(file, 'utf8').split('\n');
    for (const raw of lines) {
      const t = raw.trim();
      if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue;
      for (const m of raw.matchAll(/testTag\(\s*"([^"]+)"\s*\)/g)) literal.add(m[1]);
      // 映射表 / 常量里的锚点（如 THEME_ANCHOR 的 to "s13-theme-apple"），排除 token 表
      if (!isTokenFile(file)) {
        for (const m of raw.matchAll(/"([A-Za-z0-9][A-Za-z0-9-]*)"/g)) {
          const s = m[1];
          if (!literal.has(s) && s.endsWith('-')) prefixes.add(s);
        }
      }
    }
  }
  return { literal, mapped, prefixes, files };
}

/** 前缀覆盖判定：契约锚点以某个代码前缀开头，且该前缀确实用于 testTag 拼接。 */
function coveredByPrefix(anchor, prefixes, fileText) {
  for (const p of prefixes) {
    if (anchor.startsWith(p) && fileText.includes('testTag(')) return p;
  }
  return null;
}

const contract = readContractAnchors();
const { literal, prefixes } = readCodeAnchors();
const allCode = walk(SRC_DIR).map((f) => fs.readFileSync(f, 'utf8')).join('\n');

const landed = new Set();
const varGen = new Set();
for (const anchor of contract.keys()) {
  if (literal.has(anchor)) { landed.add(anchor); continue; }
  const p = coveredByPrefix(anchor, prefixes, allCode);
  if (p) { landed.add(anchor); varGen.add(anchor); continue; }
  // 映射表：锚点字符串出现在非 token 文件里
  const inMapped = walk(SRC_DIR).some((f) => {
    if (/ui\/theme\/(DesignTokenSpecs|GeneratedTokens)/.test(f)) return false;
    return fs.readFileSync(f, 'utf8').split('\n').some((line) => {
      const t = line.trim();
      if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return false;
      return line.includes('"' + anchor + '"');
    });
  });
  if (inMapped) landed.add(anchor);
}

const missing = [...contract.keys()].filter((a) => !landed.has(a));
const extra = [...literal].filter((a) => !contract.has(a));

const pageOf = (a) => {
  const s = contract.get(a);
  return s ? [...s][0] : '(无)';
};
const pages = new Map();
for (const a of contract.keys()) {
  const p = pageOf(a);
  if (!pages.has(p)) pages.set(p, { total: 0, landed: 0, missing: [], varGen: [] });
  const st = pages.get(p);
  st.total++;
  if (landed.has(a)) { st.landed++; if (varGen.has(a)) st.varGen.push(a); }
  else st.missing.push(a);
}

if (JSON_OUT) {
  console.log(JSON.stringify({
    contract: contract.size,
    landed: landed.size,
    missing: missing.length,
    extra: extra.length,
    extraList: extra,
    pages: [...pages.entries()].map(([p, s]) => ({ page: p, ...s })),
  }, null, 2));
} else {
  console.log('=== 锚点对账 ===');
  console.log(`契约锚点 ${contract.size} | 代码落地 ${landed.size} | 缺失 ${missing.length} | 自增 ${extra.length}`);
  if (varGen.size) console.log(`（其中 ${varGen.size} 个由运行时前缀生成）`);
  console.log('');
  const rows = [...pages.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [p, s] of rows) {
    const name = p.replace(/-交互契约\.md$/, '');
    const flag = s.missing.length ? '❌' : '✅';
    console.log(`${flag} ${name.padEnd(26)} 契约 ${String(s.total).padStart(3)} | 落地 ${String(s.landed).padStart(3)} | 缺 ${String(s.missing.length).padStart(3)}${s.varGen.length ? ` (变量 ${s.varGen.length})` : ''}`);
  }
  if (missing.length) {
    console.log('');
    console.log('=== 缺失明细 ===');
    for (const [p, s] of rows) {
      if (!s.missing.length) continue;
      console.log(`--- ${p} (${s.missing.length}) ---`);
      console.log(s.missing.join(', '));
    }
  }
  if (extra.length) {
    console.log('');
    console.log('=== 自增（硬错误：代码有、契约无）===');
    console.log(extra.join(', '));
  }
}

if (extra.length > 0) {
  console.error(`\n锚点自增 ${extra.length} 个：锚点集合必须与契约逐字对账，不允许代码自造锚点。`);
  process.exit(1);
}
if (STRICT && missing.length > 0) {
  console.error(`\n锚点缺失 ${missing.length} 个（--strict 模式）。`);
  process.exit(1);
}
