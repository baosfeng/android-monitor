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
// 用法：
//   node scripts/check-anchors.mjs            # 打印四列账本，自增非 0 时 exit 1
//   node scripts/check-anchors.mjs --strict   # 缺失也非 0 时 exit 1（清理完后再启用）
//   node scripts/check-anchors.mjs --json     # 机器可读输出
//
// 已知局限：testTag 实参是复杂表达式（三元、集合下标算出来的值、超过深度上限的跨文件
// 参数链）时静态求值不到，这类锚点会落进「缺失」——实现侧应把锚点写成字面量，
// 或经由常量 / 单层参数透传。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DOC_DIR = path.join(ROOT, 'docs/交互设计');
const SRC_DIR = path.join(ROOT, 'app/src/main/java');

// 锚点源：页面级在 tsv（第 1 列锚点、第 2 列来源页面），组件级在 s12a 的 md 表格。
const LEDGER_TSV = path.join(ROOT, 'scripts/anchor-ledger.tsv');
const COMPONENT_DOC = path.join(DOC_DIR, 's12a-组件级锚点登记.md');
const ANCHOR_RE = /^[A-Za-z0-9][A-Za-z0-9.-]*$/;

// 证据追踪深度：常量 / 辅助函数链与参数透传链分别计，防止在大型 UI 组件里扩散成全仓搜索。
const MAX_DECL_HOPS = 3;
const MAX_CALL_HOPS = 4;

const args = new Set(process.argv.slice(2));
const STRICT = args.has('--strict');
const JSON_OUT = args.has('--json');

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
    pages: [...pages.entries()].map(([p, s]) => ({ page: p, ...s })),
  }, null, 2));
} else {
  console.log('=== 锚点对账 ===');
  console.log('账本锚点 ' + ledger.size + ' | 代码落地 ' + landed.size + ' | 缺失 ' + missing.length + ' | 自增 ' + extra.length);
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
}

if (extra.length > 0) {
  console.error('\n锚点自增 ' + extra.length + ' 个：锚点集合必须与锚点账本逐字对账，不允许代码自造锚点。');
  process.exit(1);
}
if (STRICT && missing.length > 0) {
  console.error('\n锚点缺失 ' + missing.length + ' 个（--strict 模式）。');
  process.exit(1);
}
