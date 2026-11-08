#!/usr/bin/env node
// 锚点对账：锚点账本 + 组件级锚点登记 ↔ 代码 Modifier.testTag。
//
// 为什么需要它：锚点覆盖率是交付的必要条件——「该打标的地方打了标」证明不了
// 「点了会有该有的反应」，但连锚点都对不上时，任何行为验收都无从谈起。
// 原型（data-od-id）、锚点账本、代码三方靠同一套锚点字符串对账，不改号、不转下划线。
//
// 判据：
//   1. 账本锚点 = scripts/anchor-ledger.tsv 第 1 列（页面级）
//      + docs/交互设计/s12a-组件级锚点登记.md 表格第一列（组件级）；去重，字符集含 . 与 -
//   2. 代码落地 = 从某个真实 testTag 调用点能走到的字符串，三条证据都挂在调用点上：
//      a. 字面量：锚点就是 testTag(...) 的实参，或 Glance 的 testTag = "…" 属性值
//      b. 常量 / 辅助函数：实参引用的 val / fun，其声明体里有该锚点字符串（≤ 3 跳）
//      c. 参数透传：实参引用的是某函数参数，顺着该函数的调用点回追到调用方传的字面量（≤ 4 跳）
//      运行时拼接前缀（"s15-l1-" 这类以 - 结尾的常量）同样只能来自上述三条证据的文本，
//      不再接受「全仓任意处出现过 testTag( 」「任意文件任意一行出现过该字符串」两个宽口子。
//   3. 自增 = 代码有、账本无 —— 这是硬错误：锚点集合必须能逐字对账
//
// 为什么收窄（基建审计 I1）：旧实现的前缀判定只要求「全仓库某处出现过 testTag(」，
// 映射表兜底只要求「锚点字符串出现在任意文件任意一行」，两条都与锚点本身无关：
// 代码里根本不存在的幽灵锚点会被判「已落地」，且 exit 0。现在每条证据都必须从
// testTag 调用点出发、沿声明或调用链走得通，走不通就是缺失。
//
// 缺失的事实源（基建审计 I10）：缺失不再「只报不判」。scripts/anchor-retired.tsv 是受版本
// 控制的作废 / 未实现清单，每行写明来源页面、状态（作废 / 原型外壳 / 待实现）与理由；
// 脚本只容忍清单内的缺失，清单外的缺失 exit 1，清单里已落地的锚点同样 exit 1（清单要跟着缩）。
// 以前 CI 注释把 123 条缺失一锅端归给「作废页」，而其中 s05 / s16 约 40 条来自在用页面。
//
// 用法：
//   node scripts/check-anchors.mjs            # 打印四列账本；自增 / 清单外缺失非 0 时 exit 1
//   node scripts/check-anchors.mjs --strict   # 连清单内的容忍缺失也要求为 0（清理完再启用）
//   node scripts/check-anchors.mjs --json     # 机器可读输出
//   node scripts/check-anchors.mjs --self-test # 用 fixture 校验上面三条判定本身
//
// 已知局限：testTag 实参是复杂表达式（三元、集合下标算出来的值、超过深度上限的跨文件
// 参数链）时静态求值不到，这类锚点会落进「缺失」——实现侧应把锚点写成字面量，
// 或经由常量 / 单层参数透传。

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DOC_DIR = path.join(ROOT, 'docs/交互设计');
// 路径可用环境变量覆盖：--self-test 要拿 fixture 跑同一套判据（见文末 selfTest）。
const SRC_DIR = path.resolve(ROOT, process.env.ANCHORS_SRC || 'app/src/main/java');

// 锚点源：页面级在 tsv（第 1 列锚点、第 2 列来源页面），组件级在 s12a 的 md 表格。
const LEDGER_TSV = path.resolve(ROOT, process.env.ANCHORS_LEDGER || 'scripts/anchor-ledger.tsv');
const COMPONENT_DOC = path.resolve(ROOT, process.env.ANCHORS_COMPONENTS || 'docs/交互设计/s12a-组件级锚点登记.md');
// 作废 / 未实现的锚点清单：只容忍这里列出的缺失（见文件头的 I10 说明）。
const RETIRED_TSV = path.resolve(ROOT, process.env.ANCHORS_RETIRED || 'scripts/anchor-retired.tsv');
const ANCHOR_RE = /^[A-Za-z0-9][A-Za-z0-9.-]*$/;

// 证据追踪深度：常量 / 辅助函数链与参数透传链分别计，防止在大型 UI 组件里扩散成全仓搜索。
const MAX_DECL_HOPS = 3;
const MAX_CALL_HOPS = 4;

const args = new Set(process.argv.slice(2));
const STRICT = args.has('--strict');
const JSON_OUT = args.has('--json');

if (args.has('--self-test')) {
  process.exit(selfTest());
}

function readLines(file) {
  if (!fs.existsSync(file)) {
    console.error('缺锚点源文件：' + path.relative(ROOT, file));
    process.exit(2);
  }
  return fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.trim() && !l.trim().startsWith('#'));
}

/** 锚点清单，去重，值 = 来源页面。先登记者为准，同一锚点只归属一处。 */
function readLedgerAnchors() {
  const anchors = new Map();
  // 字符集含 "."：组件级锚点用 组件名.用途（chip.option / button.primary），见 s12a 组件级锚点登记
  const add = (anchor, page) => {
    if (anchor && ANCHOR_RE.test(anchor) && !anchors.has(anchor)) anchors.set(anchor, page);
  };
  // 页面级：tsv 的「锚点 <TAB> 来源页面」；首行表头是中文列名，被字符集过滤掉
  for (const raw of readLines(LEDGER_TSV)) {
    const [anchor, page] = raw.split('\t').map((s) => s.trim());
    add(anchor, page || 'anchor-ledger.tsv');
  }
  // 组件级：md 表格第一列，页名固定
  for (const raw of readLines(COMPONENT_DOC)) {
    const line = raw.trim();
    if (!line.startsWith('|')) continue;
    const cols = line.split('|').map((s) => s.trim());
    if (cols[1] === '锚点' || /^[-:]+$/.test(cols[1] || '')) continue;
    add(cols[1], 's12a-组件级锚点登记');
  }
  return anchors;
}

/**
 * 作废 / 未实现锚点清单：锚点 → { page, status, reason }。
 *
 * 只容忍这里列出的缺失；每行必须带状态与理由，否则「一锅粥」清单会重新变成
 * 「缺失没人管」的挡箭牌（基建审计 I10）。
 */
function readRetired() {
  const retired = new Map();
  if (!fs.existsSync(RETIRED_TSV)) {
    console.error('缺作废锚点清单：' + path.relative(ROOT, RETIRED_TSV));
    process.exit(2);
  }
  for (const raw of readLines(RETIRED_TSV)) {
    const [anchor, page, status, reason] = raw.split('\t').map((s) => (s || '').trim());
    // 首行是中文表头，被字符集过滤掉
    if (!anchor || !ANCHOR_RE.test(anchor)) continue;
    if (!status || !reason) {
      console.error('作废锚点清单缺状态或理由：' + anchor);
      process.exit(2);
    }
    retired.set(anchor, { page: page || '(无)', status, reason });
  }
  return retired;
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (entry.name.endsWith('.kt')) out.push(p);
  }
  return out;
}

const isComment = (line) => /^\s*(\*|\/\/|\/\*)/.test(line);

/** 去掉行内 // 注释（保留字符串里的 //），注释里的示例锚点不能当证据。 */
function stripLineComments(line) {
  let out = '';
  let inStr = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inStr) {
      out += c;
      if (c === '\\') { out += line[++i] ?? ''; continue; }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; out += c; continue; }
    if (c === '/' && line[i + 1] === '/') break;
    out += c;
  }
  return out;
}

/** 字符串字面量内容（不含引号）。 */
function literals(text) {
  const out = [];
  for (const m of text.matchAll(/"((?:[^"\\]|\\.)*)"/g)) out.push(m[1]);
  return out;
}

/** 标识符集合：先抹掉字符串字面量，字面量里的词不是符号。 */
function identifiers(text) {
  const out = new Set();
  const bare = text.replace(/"(?:[^"\\]|\\.)*"/g, '""');
  for (const m of bare.matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)) out.add(m[0]);
  return out;
}

/** 从 start（指向 '(' / '{' / '['）取出配对整段内容，返回 [内容, 结束下标]。 */
function balanced(text, start) {
  const open = text[start];
  const close = open === '(' ? ')' : open === '{' ? '}' : ']';
  let depth = 0;
  let inStr = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === '\\') { i++; continue; }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; continue; }
    if (c === open) depth++;
    else if (c === close) {
      depth--;
      if (depth === 0) return [text.slice(start + 1, i), i];
    }
  }
  return [text.slice(start + 1), text.length - 1];
}

/** 顶层逗号切分（忽略字符串与各类括号内的逗号）。 */
function splitTopLevel(text) {
  const out = [];
  let depth = 0;
  let inStr = false;
  let cur = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      cur += c;
      if (c === '\\') { cur += text[++i] ?? ''; continue; }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; cur += c; continue; }
    if (c === '(' || c === '{' || c === '[') depth++;
    else if (c === ')' || c === '}' || c === ']') depth--;
    if (c === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur);
  return out.map((s) => s.trim()).filter(Boolean);
}

const VALS_RE = /^\s*(?:(?:private|internal|public|protected|override|const)\s+)*val\s+([A-Za-z_][A-Za-z0-9_]*)\b/;
const FUNS_RE = /^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>]+\.\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(/;

/** 解析一份 .kt：testTag 调用点、val 表、fun 表（参数名 + 体文本 + 偏移范围）。 */
function parseFile(file, rel) {
  const rawLines = fs.readFileSync(file, 'utf8').split('\n');
  const lines = rawLines.map((l) => (isComment(l) ? '' : stripLineComments(l)));
  const joined = lines.join('\n');

  const lineStarts = [0];
  for (let i = 0; i < joined.length; i++) if (joined[i] === '\n') lineStarts.push(i + 1);

  const vals = new Map();
  const funs = new Map();
  const sites = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim()) continue;
    const base = lineStarts[i];

    const v = line.match(VALS_RE);
    if (v) {
      const eq = line.indexOf('=', line.indexOf(v[1], v.index) + v[1].length);
      if (eq >= 0) {
        let body = line.slice(eq + 1);
        let depth = countDepth(body);
        let j = i;
        // 取值跨行（mapOf(…) / listOf(…)）：按括号配平补足，最多看 40 行
        while (depth > 0 && j + 1 < lines.length && j - i < 40) {
          j++;
          body += '\n' + lines[j];
          depth += countDepth(lines[j]);
        }
        if (!vals.has(v[1])) vals.set(v[1], { rel, body });
      }
    }

    const f = line.match(FUNS_RE);
    if (!f) continue;
    const openIdx = base + f.index + f[0].length - 1;
    if (joined[openIdx] !== '(') continue;
    const [paramText, closeIdx] = balanced(joined, openIdx);
    const params = splitTopLevel(paramText)
      .map((p) => {
        const m = p.match(/^(?:@[A-Za-z0-9_.]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*(?:vararg\s+|noinline\s+|crossinline\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:/);
        return m ? m[1] : '';
      })
      .filter(Boolean);

    let body = '';
    let spanEnd = closeIdx;
    let k = closeIdx + 1;
    while (k < joined.length && /\s/.test(joined[k])) k++;
    // 返回类型注解：`fun f(x: T): R = ...` / `: R { ... }` —— 先跳过 `: R` 再找体起点，
    // 否则表达式体函数会被当成无体函数，常量 / 锚点表就断在这里。
    if (joined[k] === ':') {
      const rt = joined.slice(k).match(/^:\s*[A-Za-z0-9_.<>?(), ]+?(?=\s*(?:=|\{|where\b))/);
      if (rt) k += rt[0].length;
      while (k < joined.length && /\s/.test(joined[k])) k++;
    }
    if (joined[k] === '{') {
      const [b, e] = balanced(joined, k);
      body = b;
      spanEnd = e;
    } else if (joined[k] === '=') {
      // 表达式体：取到下一行同为声明 / 注解 / 收尾大括号之前，最多 8 行
      const startLine = lineIndexOf(lineStarts, k);
      let end = startLine;
      const firstBody = joined.slice(k + 1, nextLineBreak(joined, k));
      body = firstBody;
      for (let n = startLine + 1; n < lines.length && n - startLine <= 8; n++) {
        const nxt = lines[n];
        if (!nxt.trim()) { end = n; continue; }
        if (/^\s*(?:@|private\s|internal\s|public\s|override\s|fun\s|val\s|class\s|object\s|interface\s|\})/.test(nxt)) break;
        body += '\n' + nxt;
        end = n;
      }
      spanEnd = end < lines.length - 1 ? lineStarts[end + 1] : joined.length;
    }
    if (!funs.has(f[1])) funs.set(f[1], []);
    funs.get(f[1]).push({ rel, name: f[1], params, body, span: [base, spanEnd] });
  }

  // testTag 调用点：Compose 的 testTag(...) 与 Glance 的 testTag = "…" 属性
  for (const m of joined.matchAll(/(?<![A-Za-z0-9_])testTag\s*\(/g)) {
    const openIdx = m.index + m[0].length - 1;
    const [argText] = balanced(joined, openIdx);
    if (!argText.trim()) continue;
    const lineNo = lineIndexOf(lineStarts, m.index);
    sites.push({ arg: argText, line: rawLines[lineNo] ?? '', fun: funAt(funs, m.index) });
  }
  // Glance（RemoteViews）只有 `semantics { testTag = "…" }` 属性形式，没有 testTag(...) 函数。
  // 只认 semantics 块内的那种；Compose 函数命名参数 `testTag = "…"`（TotpScaffold 等）与旧实现
  // 口径一致地不计入调用点（它们既不产生落地证据，也不产生自增）。
  for (const m of joined.matchAll(/(?<![A-Za-z0-9_])testTag\s*=\s*("(?:[^"\\]|\\.)*")/g)) {
    const lineNo = lineIndexOf(lineStarts, m.index);
    if (!/semantics/.test(rawLines[lineNo] ?? '')) continue;
    sites.push({ arg: m[1], line: rawLines[lineNo] ?? '', fun: funAt(funs, m.index) });
  }

  return { rel, sites, vals, funs };
}

/** 同文件内最内层的 fun 名（span 起点最大者）：参数透传要从「这段 testTag 写在哪个函数里」出发。 */
function funAt(funs, offset) {
  let best = null;
  for (const [, list] of funs) {
    for (const f of list) {
      if (offset >= f.span[0] && offset <= f.span[1]) {
        if (!best || f.span[0] > best.start) best = { name: f.name, start: f.span[0] };
      }
    }
  }
  return best ? best.name : null;
}

function countDepth(s) {
  const opens = (s.match(/[({[]/g) || []).length;
  const closes = (s.match(/[)}\]]/g) || []).length;
  return opens - closes;
}

function nextLineBreak(text, from) {
  const i = text.indexOf('\n', from);
  return i < 0 ? text.length : i;
}

function lineIndexOf(lineStarts, offset) {
  let lo = 0;
  let hi = lineStarts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (lineStarts[mid] <= offset) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

/** 全仓索引 + 懒加载的调用点索引。 */
function buildIndex() {
  const files = walk(SRC_DIR);
  const parsed = files.map((f) => parseFile(f, path.relative(ROOT, f)));
  const vals = new Map();
  const funs = new Map();
  for (const p of parsed) {
    for (const [name, v] of p.vals) if (!vals.has(name)) vals.set(name, v);
    for (const [name, list] of p.funs) {
      if (!funs.has(name)) funs.set(name, []);
      funs.get(name).push(...list);
    }
  }
  // 每份文件的「去注释文本」缓存，供调用点扫描复用
  const texts = new Map();
  for (const f of files) {
    const rel = path.relative(ROOT, f);
    const lines = fs.readFileSync(f, 'utf8').split('\n').map((l) => (isComment(l) ? '' : stripLineComments(l)));
    texts.set(rel, { lines, joined: lines.join('\n') });
  }
  const callCache = new Map();
  const callSites = (name) => {
    if (callCache.has(name)) return callCache.get(name);
    const out = [];
    // 允许方法调用 `Modifier.segmentTag(...)`：点号只是调用链，不是排除理由；
    // 声明行 `fun Modifier.segmentTag(` 由下面的 before 检查剔除。
    const re = new RegExp('(?<![A-Za-z0-9_])' + name + '\\s*\\(', 'g');
    for (const [rel, t] of texts) {
      for (const m of t.joined.matchAll(re)) {
        const before = t.joined.slice(Math.max(0, m.index - 60), m.index);
        if (/\bfun\s+(?:[A-Za-z0-9_.<>]+\.\s*)?$/.test(before)) continue; // 声明行本身
        const openIdx = m.index + m[0].length - 1;
        const [argText] = balanced(t.joined, openIdx);
        out.push({ rel, fun: enclosingFun(funs, rel, m.index), args: splitTopLevel(argText) });
      }
    }
    callCache.set(name, out);
    return out;
  };
  return { rel: null, parsed, vals, funs, callSites };
}

/** 调用点所属函数名（同文件、偏移落在某个 fun 的 span 内）。 */
function enclosingFun(funs, rel, offset) {
  let best = null;
  for (const [name, list] of funs) {
    for (const f of list) {
      if (f.rel !== rel) continue;
      if (offset >= f.span[0] && offset <= f.span[1]) {
        if (!best || f.span[0] > best.start) best = { name, start: f.span[0] };
      }
    }
  }
  return best ? best.name : null;
}

function isParamOf(index, funName, ident) {
  const list = index.funs.get(funName);
  if (!list) return false;
  return list.some((f) => f.params.includes(ident));
}

/** 在某个调用点里取绑定到参数 paramName 的实参文本（命名参数优先，其次按位置）。 */
function bindArg(index, site, funName, paramName) {
  const named = site.args.find((a) => {
    const m = a.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)/);
    return m && m[1] === paramName;
  });
  if (named) return named.replace(/^[A-Za-z_][A-Za-z0-9_]*\s*=\s*/, '');
  // 位置实参：Kotlin 允许命名与位置混写，按「函数形参顺序 → 命名优先，其余按序取」对齐
  const positional = [];
  const namedArgs = new Map();
  for (const a of site.args) {
    const m = a.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)/);
    if (m) namedArgs.set(m[1], a.slice(m[0].length));
    else positional.push(a);
  }
  for (const f of index.funs.get(funName) || []) {
    if (!f.params.includes(paramName)) continue;
    let cursor = 0;
    for (const param of f.params) {
      const value = namedArgs.has(param)
        ? namedArgs.get(param)
        : cursor < positional.length
          ? positional[cursor++]
          : null;
      if (param === paramName) return value;
    }
  }
  return null;
}

/**
 * lambda 内用到 testTag 时（`anchor?.let { testTag(it) }`），实参 `it` 指的就是 let 的接收者。
 * 把 lambda 参数名换回接收者表达式再继续追踪，避免「包一层 let 就断链」。
 */
function lambdaReceiver(body, param) {
  const out = [];
  for (const m of body.matchAll(/([A-Za-z_][A-Za-z0-9_.]*(?:\([^()]*\))?)\s*\??\.\s*(?:let|run|also|apply)\s*\{([^{}]*)/g)) {
    const receiver = m[1];
    const inner = m[2];
    // `X.let { it … }`（无显式参数）或 `X.let { name -> … name … }`
    const named = inner.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*->/);
    const useName = named ? named[1] : 'it';
    if (useName !== param) continue;
    if (!new RegExp('\\b' + useName + '\\b').test(inner)) continue;
    out.push(receiver);
  }
  return out;
}

/** 从一段文本出发，收集「与 testTag 有关联」的字面量与运行时前缀。 */
function expand(index, text, ctx, declHops, callHops, seen, acc) {
  for (const lit of literals(text)) {
    acc.literals.add(lit);
    if (lit.endsWith('-')) acc.prefixes.add(lit);
  }
  for (const id of identifiers(text)) {
    const key = (ctx.fun || '') + '|' + id;
    if (seen.has(key)) continue;
    seen.add(key);

    // 只对「值引用」形态追踪同名声明：大写开头的常量 / 辅助函数，或带括号的调用 f(...)。
    // 裸的小写标识符多半是局部变量与属性（index / record / type），按名字匹配到无关声明
    // 就会把不相干的字符串拉进来 —— 那正是旧实现「任意文件任意一行」宽口子的缩小版。
    const asCall = new RegExp('(?<![A-Za-z0-9_])' + id + '\\s*\\(').test(text);
    const isConstant = /^[A-Z]/.test(id);
    if (declHops < MAX_DECL_HOPS && (asCall || isConstant)) {
      const v = index.vals.get(id);
      if (v) expand(index, v.body, { rel: v.rel, fun: null }, declHops + 1, callHops, seen, acc);
      const list = index.funs.get(id);
      if (list) {
        for (const f of list) expand(index, f.body, { rel: f.rel, fun: id }, declHops + 1, callHops, seen, acc);
      }
    }

    // lambda 内写成 testTag(it) / testTag { it }：把 it 换回 `X?.let { … }` 的接收者再追踪
    if (ctx.fun && declHops < MAX_DECL_HOPS) {
      for (const f of index.funs.get(ctx.fun) || []) {
        if (f.rel !== ctx.rel) continue;
        for (const receiver of lambdaReceiver(f.body, id)) {
          expand(index, receiver, { rel: f.rel, fun: ctx.fun }, declHops + 1, callHops, seen, acc);
        }
      }
    }

    if (ctx.fun && callHops < MAX_CALL_HOPS && isParamOf(index, ctx.fun, id)) {
      for (const site of index.callSites(ctx.fun)) {
        const argText = bindArg(index, site, ctx.fun, id);
        if (argText == null || !argText.trim()) continue;
        expand(index, argText, { rel: site.rel, fun: site.fun }, declHops, callHops + 1, seen, acc);
      }
    }
  }
}

// ── 主流程 ────────────────────────────────────────────────────────────────

const ledger = readLedgerAnchors();
const retired = readRetired();
const index = buildIndex();

// 从每个 testTag 调用点出发收集证据；只有「从调用点走得到」的字面量 / 前缀才算落地证据。
const evidence = new Set();
const prefixes = new Set();
const directLiterals = new Set();
for (const file of index.parsed) {
  for (const site of file.sites) {
    const acc = { literals: new Set(), prefixes: new Set() };
    expand(index, site.arg, { rel: file.rel, fun: site.fun }, 0, 0, new Set(), acc);
    for (const lit of acc.literals) {
      if (ledger.has(lit)) evidence.add(lit);
      if (lit.endsWith('-')) prefixes.add(lit);
    }
    // 自增判定只看「testTag 的实参就是这一个字面量」的形态（与旧口径一致，避免把组件里的
    // 普通文案当成自造锚点）；拼接表达式不算。
    const single = site.arg.trim().match(/^"((?:[^"\\]|\\.)*)"$/);
    if (single) directLiterals.add(single[1]);
  }
}

const landed = new Set();
const varGen = new Set();
for (const anchor of ledger.keys()) {
  if (evidence.has(anchor)) { landed.add(anchor); continue; }
  const p = [...prefixes].find((pre) => anchor.startsWith(pre));
  if (p) { landed.add(anchor); varGen.add(anchor); }
}

const missing = [...ledger.keys()].filter((a) => !landed.has(a));
const extra = [...directLiterals].filter((a) => !ledger.has(a));

// 缺失分两类：清单内的（设计已作废 / 待实现，容忍）与清单外的（真缺口，判红）。
// 反方向：清单里写着「作废」但代码里已落地的锚点，说明清单该缩了，也判红——
// 否则这清单会越滚越大，重新变成「缺失无人负责」的挡箭牌。
const unretired = missing.filter((a) => !retired.has(a));
const staleRetired = [...retired.keys()].filter((a) => !missing.includes(a));
const pendingCount = missing.filter((a) => retired.get(a)?.status === '待实现').length;

const pageOf = (a) => ledger.get(a) || '(无)';
const pages = new Map();
for (const a of ledger.keys()) {
  const p = pageOf(a);
  if (!pages.has(p)) pages.set(p, { total: 0, landed: 0, missing: [], varGen: [] });
  const st = pages.get(p);
  st.total++;
  if (landed.has(a)) { st.landed++; if (varGen.has(a)) st.varGen.push(a); }
  else st.missing.push(a);
}

if (JSON_OUT) {
  // 键名沿用 contract：账本即锚点的唯一事实来源
  console.log(JSON.stringify({
    contract: ledger.size,
    landed: landed.size,
    missing: missing.length,
    extra: extra.length,
    extraList: extra,
    retiredTolerated: missing.length - unretired.length,
    pending: pendingCount,
    unretired,
    staleRetired,
    pages: [...pages.entries()].map(([p, s]) => ({ page: p, ...s })),
  }, null, 2));
} else {
  console.log('=== 锚点对账 ===');
  console.log('账本锚点 ' + ledger.size + ' | 代码落地 ' + landed.size + ' | 缺失 ' + missing.length +
    '（清单内 ' + (missing.length - unretired.length) + '，其中待实现 ' + pendingCount + '） | 自增 ' + extra.length);
  if (varGen.size) console.log('（其中 ' + varGen.size + ' 个由运行时前缀生成）');
  console.log('');
  const rows = [...pages.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  for (const [p, s] of rows) {
    const flag = s.missing.length ? '❌' : '✅';
    console.log(flag + ' ' + p.padEnd(26) + ' 账本 ' + String(s.total).padStart(3) +
      ' | 落地 ' + String(s.landed).padStart(3) + ' | 缺 ' + String(s.missing.length).padStart(3) +
      (s.varGen.length ? ' (变量 ' + s.varGen.length + ')' : ''));
  }
  if (missing.length) {
    console.log('');
    console.log('=== 缺失明细 ===');
    for (const [p, s] of rows) {
      if (!s.missing.length) continue;
      console.log('--- ' + p + ' (' + s.missing.length + ') ---');
      console.log(s.missing.join(', '));
    }
  }
  if (extra.length) {
    console.log('');
    console.log('=== 自增（硬错误：代码有、账本无）===');
    console.log(extra.join(', '));
  }
  if (unretired.length) {
    console.log('');
    console.log('=== 清单外缺失（硬错误：不在 ' + path.relative(ROOT, RETIRED_TSV) + ' 里）===');
    console.log(unretired.join(', '));
  }
  if (staleRetired.length) {
    console.log('');
    console.log('=== 清单已失效（硬错误：这些锚点已落地或不在账本，删掉对应行）===');
    console.log(staleRetired.join(', '));
  }
}

if (extra.length > 0) {
  console.error('\n锚点自增 ' + extra.length + ' 个：锚点集合必须与锚点账本逐字对账，不允许代码自造锚点。');
  process.exit(1);
}
if (staleRetired.length > 0) {
  console.error('\n作废清单有 ' + staleRetired.length + ' 条已失效：删掉 ' +
    path.relative(ROOT, RETIRED_TSV) + ' 里对应行（清单只容忍仍然缺失的锚点）。');
  process.exit(1);
}
if (unretired.length > 0) {
  console.error('\n有 ' + unretired.length + ' 条缺失不在作废清单里：要么实现 testTag，' +
    '要么在 ' + path.relative(ROOT, RETIRED_TSV) + ' 里登记状态与理由。');
  process.exit(1);
}
if (STRICT && missing.length > 0) {
  console.error('\n锚点缺失 ' + missing.length + ' 个（--strict 模式，清单内也不容忍）。');
  process.exit(1);
}

// ── fixture 自测：三条判定本身的回归（--self-test）────────────────────────
// 每个用例在临时目录里造一份最小 fixture（账本 / 组件级 md / 源码 / 作废清单），
// 再用 ANCHORS_* 环境变量把同一个脚本指过去跑一遍，断言退出码与 JSON 字段。
// 三种输入：清单外缺失判红、全部清单内判绿、清单已失效判红。
function selfTest() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'anchors-selftest-'));
  const build = (name, ledgerAnchors, codeAnchors, retiredRows) => {
    const dir = path.join(tmp, name);
    fs.mkdirSync(path.join(dir, 'src'), { recursive: true });
    fs.writeFileSync(
      path.join(dir, 'ledger.tsv'),
      ['锚点\t来源页面', ...ledgerAnchors.map((a) => a + '\t页面A')].join('\n') + '\n',
    );
    fs.writeFileSync(path.join(dir, 'components.md'), '| 锚点 | 说明 |\n|---|---|\n');
    fs.writeFileSync(
      path.join(dir, 'retired.tsv'),
      ['锚点\t来源页面\t状态\t理由', ...retiredRows].join('\n') + '\n',
    );
    fs.writeFileSync(
      path.join(dir, 'src', 'Sample.kt'),
      codeAnchors.map((a) => 'val t = Modifier.testTag("' + a + '")').join('\n') + '\n',
    );
    return dir;
  };
  const run = (dir) => {
    const env = { ...process.env };
    env.ANCHORS_LEDGER = path.join(dir, 'ledger.tsv');
    env.ANCHORS_COMPONENTS = path.join(dir, 'components.md');
    env.ANCHORS_RETIRED = path.join(dir, 'retired.tsv');
    env.ANCHORS_SRC = path.join(dir, 'src');
    try {
      const out = execFileSync(process.execPath, [fileURLToPath(import.meta.url), '--json'], {
        env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
      });
      return { code: 0, json: JSON.parse(out) };
    } catch (e) {
      return { code: e.status ?? 1, json: e.stdout ? JSON.parse(e.stdout) : null };
    }
  };

  const cases = [
    {
      name: 'unretired-missing',
      dir: build('unretired', ['a1', 'a2'], ['a1'], []),
      expect: 1,
      why: '清单外缺失（账本 a2 未实现且没登记）→ 判红',
      check: (j) => j && j.unretired.length === 1 && j.unretired[0] === 'a2',
    },
    {
      name: 'all-retired',
      dir: build('all-retired', ['a1', 'a2'], ['a1'], ['a2\t页面A\t作废\t示例理由']),
      expect: 0,
      why: '缺失全部在作废清单内 → 放行',
      check: (j) => j && j.missing === 1 && j.retiredTolerated === 1,
    },
    {
      name: 'stale-retired',
      dir: build('stale', ['a1', 'a2'], ['a1', 'a2'], ['a2\t页面A\t作废\t示例理由']),
      expect: 1,
      why: '清单里写「作废」但代码已落地 → 判红（清单必须跟着缩）',
      check: (j) => j && j.staleRetired.length === 1 && j.staleRetired[0] === 'a2',
    },
  ];

  let failed = 0;
  console.log('锚点门禁自测（fixture：' + tmp + '）');
  console.log('');
  for (const c of cases) {
    const r = run(c.dir);
    const ok = r.code === c.expect && c.check(r.json);
    if (ok) {
      console.log('  PASS ' + c.name + '：退出码 ' + r.code + '（期望 ' + c.expect + '）· ' + c.why);
    } else {
      console.log('  FAIL ' + c.name + '：退出码 ' + r.code + '（期望 ' + c.expect + '）· ' + c.why);
      failed = 1;
    }
  }
  fs.rmSync(tmp, { recursive: true, force: true });
  console.log('');
  if (failed === 0) {
    console.log('自测结果：PASS（清单内放行 / 清单外判红 / 失效清单判红 三条都成立）');
    return 0;
  }
  console.log('自测结果：FAIL（判据与 fixture 期望不符）');
  return 1;
}
