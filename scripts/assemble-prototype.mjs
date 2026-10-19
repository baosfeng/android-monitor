#!/usr/bin/env node
/**
 * 原型组装器：把 .open-design/src/ 下的分片拼成单文件产物 08-prototype.html。
 *
 * 为什么要组装：交互原型靠同页 DOM 切换（App.go / 返回栈 / 过渡动画），
 * 拆成多个独立 HTML 会直接破坏交互；单文件又是 GitHub Pages、README 截图、
 * odops verify/spec 的唯一输入。所以源文件按屏拆开维护，产物仍是单文件。
 *
 * 用法：
 *   node scripts/assemble-prototype.mjs            # 生成产物
 *   node scripts/assemble-prototype.mjs --check    # 只校验产物是否与分片一致（CI 用）
 *   node scripts/assemble-prototype.mjs --out x.html
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, '.open-design/src');
const OUT = path.join(ROOT, '.open-design/08-prototype.html');

const args = process.argv.slice(2);
const check = args.includes('--check');
const outIdx = args.indexOf('--out');
const outPath = outIdx >= 0 ? path.resolve(args[outIdx + 1]) : OUT;

const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8').replace(/\s+$/, '');

/**
 * 把一屏的分片拆成样式段 / 结构段。
 * 只认「独占一行」的标记，注释里提到标记名不会误命中。
 */
function parseScreen(rel) {
  const lines = read(rel).split('\n');
  const at = (marker) => {
    const idx = lines.findIndex((l) => l.trim() === marker);
    if (idx < 0) throw new Error(`${rel}: 缺少独占一行的 ${marker} 标记`);
    return idx;
  };
  const iStyle = at('<!--@od:style-->');
  const iSection = at('<!--@od:section-->');
  const iEnd = at('<!--@od:end-->');
  if (!(iStyle < iSection && iSection < iEnd)) {
    throw new Error(`${rel}: 标记顺序应为 style → section → end`);
  }
  return {
    style: lines.slice(iStyle + 1, iSection).join('\n').trim(),
    section: lines.slice(iSection + 1, iEnd).join('\n').trim(),
  };
}

const screenFiles = fs
  .readdirSync(path.join(SRC, 'screens'))
  .filter((f) => f.endsWith('.html'))
  .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));

const styleChunks = [];
const sectionChunks = [];
for (const f of screenFiles) {
  const { style, section } = parseScreen(path.join('screens', f));
  if (style) styleChunks.push(style);
  if (section) sectionChunks.push(section);
}

const baseStyles = [read('styles/10-tokens.html'), read('styles/20-base.html')].join('\n\n');
const appJs = read('99-app.js');

const replacements = {
  '<!--@od:base-styles-->': baseStyles,
  '<!--@od:styles-->': styleChunks.join('\n\n'),
  '<!--@od:screens-->': sectionChunks.join('\n\n'),
  '<!--@od:splash-->': read('10-splash.html'),
  '<!--@od:devbar-->': read('90-devbar.html'),
  '<!--@od:scripts-->': '<script>\n' + appJs + '\n</script>',
};

let html = read('00-shell.html');
for (const [token, content] of Object.entries(replacements)) {
  if (!html.includes(token)) throw new Error(`00-shell.html 缺少占位 ${token}`);
  html = html.split(token).join(content);
}
if (html.includes('<!--@od:')) throw new Error('产物里仍有未替换的占位：' + html.match(/<!--@od:[^>]*-->/g).join(' '));

const banner = [
  '<!doctype html>',
  '<!--',
  '  本文件由 scripts/assemble-prototype.mjs 从 .open-design/src/ 自动生成，请勿直接编辑。',
  '  改哪一屏就改 src/screens/ 下对应分片，改完全局骨架改 src/00-shell.html，',
  '  然后执行 node scripts/assemble-prototype.mjs 重新生成。',
  '-->',
].join('\n');
html = html.replace('<!doctype html>', banner);

if (check) {
  const current = fs.existsSync(outPath) ? fs.readFileSync(outPath, 'utf8') : '';
  if (current !== html) {
    console.error('产物与分片不一致：请执行 node scripts/assemble-prototype.mjs 重新生成');
    process.exit(1);
  }
  console.log('产物与分片一致 ✓');
  process.exit(0);
}

fs.writeFileSync(outPath, html);
const kb = (Buffer.byteLength(html) / 1024).toFixed(1);
console.log(
  `已生成 ${path.relative(ROOT, outPath)}  ${kb} KB · ${html.split('\n').length} 行 · ` +
    `${screenFiles.length} 屏分片 · 样式 ${styleChunks.length} 段 · 结构 ${sectionChunks.length} 段`
);
