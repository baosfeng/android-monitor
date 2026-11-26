#!/usr/bin/env node
/**
 * 每文件实测取证（给 README 用）：等过渡结束后取 innerText 字符数 / 有色元素数 / 屏列表 / 底栏链接。
 * 产出 evidence/measure.json
 *
 * 用法：node .open-design/tabs/measure-tabs.mjs
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SESSION = 'tabsplit-measure';
const EVID = path.join(HERE, 'evidence');
fs.mkdirSync(EVID, { recursive: true });
const FILES = ['tab-overview.html', 'tab-live.html', 'tab-ad.html', 'tab-finance.html', 'tab-quota.html', 'tab-settings.html'];

function ab(args, input) {
  try {
    return execFileSync('agent-browser', ['--session', SESSION].concat(args),
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], input: input || undefined, timeout: 120000 });
  } catch (e) { return String(e.stdout || '') + String(e.stderr || ''); }
}
function evRaw(js) {
  const out = ab(['eval', '--stdin'], js).trim().split('\n').filter(Boolean).pop() || '';
  try { return JSON.parse(out); } catch (e) { return out; }
}
function ev(js) {
  const v = evRaw(js);
  if (typeof v !== 'string') return { parseError: String(v).slice(0, 200) };
  try { return JSON.parse(v); } catch (e) { return { parseError: v.slice(0, 200) }; }
}
const wait = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

const READY = [
  '(function(){',
  '  var act = document.querySelector("#screenHost > .screen.is-active");',
  '  if (!act) return "no-active";',
  '  var op = parseFloat(getComputedStyle(act).opacity || "0");',
  '  if (op < 0.99) return "animating";',
  '  return "ready";',
  '})()',
].join('\n');

const MEASURE = [
  '(function(){',
  '  var act = document.querySelector("#screenHost > .screen.is-active");',
  '  function nums(s){var m=String(s).match(/rgba?\\(([^)]+)\\)/); if(!m) return null;',
  '    var p=m[1].split(","); return {r:Number(p[0]),g:Number(p[1]),b:Number(p[2]),a:p.length>3?Number(p[3]):1};}',
  '  function chroma(c){return Math.max(c.r,c.g,c.b)-Math.min(c.r,c.g,c.b);}',
  '  var els = document.querySelectorAll("body *"), colored = 0;',
  '  for (var i=0;i<els.length;i++){',
  '    var cs=getComputedStyle(els[i]);',
  '    if (cs.display==="none"||cs.visibility==="hidden") continue;',
  '    var bg=nums(cs.backgroundColor);',
  '    if (bg && bg.a>0.05 && chroma(bg)>8) { colored++; continue; }',
  '    var hasText=false;',
  '    for (var c=0;c<els[i].childNodes.length;c++){',
  '      var n=els[i].childNodes[c];',
  '      if (n.nodeType===3 && String(n.textContent).trim()) { hasText=true; break; }',
  '    }',
  '    if (hasText){var fg=nums(cs.color); if (fg && chroma(fg)>8) colored++;}',
  '  }',
  '  var selfBtn = document.querySelector("#odTabbar button.tab");',
  '  return JSON.stringify({',
  '    file: location.pathname.split("/").pop(),',
  '    active: act ? act.getAttribute("data-screen") : null,',
  '    screens: window.App ? App.screens() : [],',
  '    textLen: act ? String(act.innerText || "").replace(/\\s+/g," ").trim().length : 0,',
  '    colored: colored,',
  '    tabHref: Array.prototype.slice.call(document.querySelectorAll("#odTabbar a.tab")).map(function(a){return a.getAttribute("data-tab")+"="+a.getAttribute("href");}),',
  '    selfTab: selfBtn ? selfBtn.getAttribute("data-tab") : null',
  '  });',
  '})()',
].join('\n');

const rows = [];
for (const f of FILES) {
  ab(['set', 'viewport', '393', '852']);
  ab(['open', 'file://' + path.join(HERE, f)]);
  wait(1400);
  let ready = evRaw(READY);
  for (let i = 0; i < 25 && ready !== 'ready'; i++) { wait(250); ready = evRaw(READY); }
  const m = ev(MEASURE);
  rows.push({ file: f, ready: ready, ...m });
  console.log(f, JSON.stringify({ ready: ready, active: m.active, textLen: m.textLen, colored: m.colored }));
}
fs.writeFileSync(path.join(EVID, 'measure.json'), JSON.stringify(rows, null, 2));
