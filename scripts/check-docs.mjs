#!/usr/bin/env node
// 文档门禁：单文档行数 + 死链 + 索引可达（AGENTS.md「文档规则」第一次有脚本兜底）。
//
// 规则来源：AGENTS.md 的「单文档 ≤ 80 行（参考类 ≤ 120 行）」只写在文档里，没有任何校验——
// 实测 27 篇超 80 行、13 篇超 120 行，最长 249 行（基建审计 I12）。本脚本三件事：
//
//   1. 行数：>80 行必须在 LINE_BASELINE 里，且**只允许下降**（当前值 > 基线值即判红）。
//      基线是把存量锁住，不是永久豁免：改到 80 行以内就把条目删掉，删了脚本也会提醒。
//   2. 死链：相对链接（含图片）指向不存在的文件即判红。外链（http/https/mailto）不检查——
//      离线环境与网络抖动会让门禁变成随机红。
//   3. 索引可达：docs/ 下每篇文档都必须能从 docs/索引.md 沿相对链接走到（AGENTS.md 的
//      「禁止孤立文档」）。索引里指向不存在的文件由死链检查兜住。
//
// 扫描范围：git 跟踪的 *.md，排除 .open-design/（过程取证）与 .github/（模板）。用 git ls-files
// 而不是遍历目录：未跟踪的草稿不该被门禁管辖，删掉但没提交的文件也不该继续算。
//
// 用法：
//   node scripts/check-docs.mjs              # 判红即 exit 1
//   node scripts/check-docs.mjs --json       # 机器可读
//   node scripts/check-docs.mjs --self-test  # 用内联 fixture 校验三条判据本身

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LINE_LIMIT = 80;
// .open-design/ 是过程取证、.github/ 是模板：都不是项目文档。
const SKIP_PREFIXES = ['.open-design/', '.github/', 'node_modules/', 'build/'];
// 行数基线（ratchet）：key 是相对仓库根的路径，value 是**当前实测行数**，只允许下降。
// 每改一篇就把它改到 ≤80 行并从本表删除；新增超限文档直接判红（不进基线）。
// 其中 13 篇已超「参考类 ≤ 120 行」的硬上限：先把内容删减或拆成多篇，再谈出基线——
// 这一层脚本只负责「不许再涨」，不负责替人决定怎么拆。
const LINE_BASELINE = {
  'docs/记账/概述.md': 249,
  'docs/开屏跳过/业界调研与改进方案.md': 231,
  'docs/开屏跳过/兜底方案调研.md': 226,
  'docs/记账/自动记账调研.md': 213,
  'docs/UI设计/设计规范.md': 183,
  'docs/网络通信/局域网互传协议.md': 183,
  'docs/开屏跳过/规则实测记录.md': 158,
  'docs/网络通信/局域网互传-实现记录.md': 140,
  'docs/API接口定义_后端需实现.md': 135,
  'docs/UI设计/原型维护.md': 128,
  'docs/记账/分类体系实现计划.md': 128,
  'docs/记账/微信支付页识别调研.md': 127,
  'docs/开屏跳过/后续计划.md': 125,
  'docs/开屏跳过/设计.md': 120,
  'docs/开发指南/构建与测试.md': 119,
  'docs/事件采集/定位能力基线.md': 118,
  'docs/开发指南/代码快速定位.md': 114,
  'docs/开发指南/发布指南.md': 114,
  'docs/开屏跳过/小程序广告调研.md': 113,
  'docs/踩坑/进程被系统冻结导致无障碍全部失效.md': 112,
  'docs/踩坑/MIUI-force-stop导致无障碍服务被自动移除.md': 99,
  'docs/小组件/调研.md': 91,
  'docs/交互设计/s12a-组件级锚点登记.md': 89,
  'docs/开发指南/Android适配指南.md': 88,
  'docs/更新检测/概述.md': 88,
  'docs/踩坑/记账FAB被底部导航栏遮住导致无法新增交易.md': 84,
  'docs/额度监控/设计.md': 83,
};

const args = new Set(process.argv.slice(2));
const JSON_OUT = args.has('--json');

function listDocs() {
  const out = execFileSync('git', ['ls-files', '-z', '*.md'], { cwd: ROOT, encoding: 'utf8' });
  return out
    .split('\0')
    .filter(Boolean)
    .filter((f) => !SKIP_PREFIXES.some((p) => f.startsWith(p)));
}

function lineCount(text) {
  return text.split('\n').length - (text.endsWith('\n') ? 1 : 0);
}

const LINK_RE = /!?\[[^\]]*\]\(([^)\s]+)\)/g;

/** 抽出相对链接目标；外链、纯锚点、空目标一律跳过。 */
function relativeTargets(text) {
  const out = [];
  for (const m of text.matchAll(LINK_RE)) {
    const raw = m[1];
    if (/^[a-z][a-z0-9+.-]*:/i.test(raw)) continue;
    const target = raw.split('#')[0].trim();
    if (!target) continue;
    out.push(target);
  }
  return out;
}

/** 行数判定：超基线的（含新增超限）与被修好却没删基线的。 */
function judgeLines(docs) {
  const over = [];
  const stale = [];
  for (const { rel, lines } of docs) {
    const base = LINE_BASELINE[rel];
    if (lines > LINE_LIMIT) {
      if (base === undefined) over.push({ rel, lines, base: null });
      else if (lines > base) over.push({ rel, lines, base });
    } else if (base !== undefined) {
      stale.push({ rel, lines, base });
    }
  }
  return { over, stale };
}

function main() {
  const rels = listDocs();
  const docs = rels.map((rel) => {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    return { rel, text, lines: lineCount(text) };
  });

  const { over, stale } = judgeLines(docs);
  const deadLinks = [];
  for (const doc of docs) {
    for (const target of relativeTargets(doc.text)) {
      const abs = path.resolve(ROOT, path.dirname(doc.rel), target);
      if (!fs.existsSync(abs)) deadLinks.push({ rel: doc.rel, target });
    }
  }

  // 索引可达：从 docs/索引.md 出发沿相对链接做 BFS，docs/ 下的文档必须全部被走到。
  const docsSet = new Set(docs.filter((d) => d.rel.startsWith('docs/')).map((d) => d.rel));
  const byRel = new Map(docs.map((d) => [d.rel, d]));
  const reached = new Set();
  const queue = ['docs/索引.md'];
  while (queue.length) {
    const cur = queue.pop();
    if (reached.has(cur) || !byRel.has(cur)) continue;
    reached.add(cur);
    for (const target of relativeTargets(byRel.get(cur).text)) {
      const rel = path.relative(ROOT, path.resolve(ROOT, path.dirname(cur), target));
      if (docsSet.has(rel) && !reached.has(rel)) queue.push(rel);
    }
  }
  const orphans = [...docsSet].filter((rel) => !reached.has(rel)).sort();

  const failed = over.length + stale.length + deadLinks.length + orphans.length;

  if (JSON_OUT) {
    console.log(JSON.stringify({ docs: docs.length, over, stale, deadLinks, orphans }, null, 2));
    process.exit(failed === 0 ? 0 : 1);
  }

  console.log('=== 文档门禁 ===');
  console.log('文档 ' + docs.length + ' 篇 | 超 ' + LINE_LIMIT + ' 行 ' + docs.filter((d) => d.lines > LINE_LIMIT).length +
    ' 篇（基线内） | 死链 ' + deadLinks.length + ' | 孤立文档 ' + orphans.length);
  console.log('');

  if (over.length) {
    console.log('❌ 行数超基线（只允许下降，超了先删内容或拆分）：');
    over.forEach(({ rel, lines, base }) => {
      console.log('   ' + rel + '：' + lines + ' 行' + (base === null ? '（新超限，不进基线）' : '（基线 ' + base + ' 行）'));
    });
    console.log('');
  }
  if (stale.length) {
    console.log('❌ 基线已过期（这些文档已 ≤' + LINE_LIMIT + ' 行，请从 LINE_BASELINE 删掉）：');
    stale.forEach(({ rel, lines, base }) => console.log('   ' + rel + '：' + lines + ' 行（基线 ' + base + '）'));
    console.log('');
  }
  if (deadLinks.length) {
    console.log('❌ 死链（目标文件不存在）：');
    deadLinks.forEach(({ rel, target }) => console.log('   ' + rel + ' -> ' + target));
    console.log('');
  }
  if (orphans.length) {
    console.log('❌ 孤立文档（从 docs/索引.md 走不到）：');
    orphans.forEach((rel) => console.log('   ' + rel));
    console.log('');
  }
  if (failed === 0) {
    console.log('结果：PASS（行数在基线内、无死链、无孤立文档）');
    process.exit(0);
  }
  console.log('结果：FAIL（' + failed + ' 处，见上）');
  process.exit(1);
}

// ── 自测：判据本身（--self-test）────────────────────────────────────────
// 纯函数级 fixture：不碰真实文档，改坏判定逻辑时自测先红。
function selfTest() {
  const cases = [];
  const doc = (rel, lines) => ({ rel, lines });

  // 行数：新超限 / 超基线 / 等于基线 / 修好后基线未删
  let r = judgeLines([doc('a.md', 100)]);
  cases.push(['新超限文档不进基线则判红', r.over.length === 1 && r.over[0].base === null]);
  r = judgeLines([doc('docs/记账/概述.md', 250)]);
  cases.push(['超基线 1 行即判红', r.over.length === 1 && r.over[0].base === 249]);
  r = judgeLines([doc('docs/记账/概述.md', 240)]);
  cases.push(['在基线下降不判红', r.over.length === 0]);
  r = judgeLines([doc('docs/额度监控/设计.md', 80)]);
  cases.push(['修到 80 行内但基线未删则判红', r.stale.length === 1]);

  // 死链：外链与纯锚点必须放过
  const links = relativeTargets('[a](x.md) [b](https://e.com/y) [c](#top) [d](../z.png#L1)');
  cases.push(['相对链接抽取正确', links.length === 2 && links[0] === 'x.md' && links[1] === '../z.png']);

  // 行数口径：末尾无换行不能多算一行
  cases.push(['行数口径（末尾无换行）', lineCount('a\nb') === 2 && lineCount('a\nb\n') === 2]);

  let failed = 0;
  console.log('文档门禁自测');
  console.log('');
  for (const [name, ok] of cases) {
    if (ok) console.log('  PASS ' + name);
    else { console.log('  FAIL ' + name); failed = 1; }
  }
  console.log('');
  if (failed === 0) {
    console.log('自测结果：PASS（行数 ratchet / 死链抽取 / 行数口径 三条判据成立）');
    return 0;
  }
  console.log('自测结果：FAIL（判据与 fixture 期望不符）');
  return 1;
}

if (args.has('--self-test')) {
  process.exit(selfTest());
}

main();
