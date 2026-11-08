#!/usr/bin/env bash
# 无障碍静态门禁：ERROR 阻断，WARN 只报不阻断。
#
# 口径见 docs/UI设计/无障碍基线.md 的四条红线。扫描范围固定 app/src/main/java
# （与 check-ui-rules.sh 一致；debug 源集待存量清理后再纳入）。
#
# ERROR（确定可判定，必须为 0，命中即 exit 1）：
#   1. 图标按钮调用块内拿不到可读名：
#      a) 调用既没有非空 contentDescription / description 命名参数，也没有顶层字符串实参
#         （AppIconButton(Icons.Add, "更多操作") 这种位置传名），也没有把名字交给内容 lambda
#         （FinanceIconButton(...) { label -> ... }）；
#      b) 或块内任一 Icon(...) 省略 contentDescription / 显式写 null——图标没有名字时
#         读屏只说「按钮」，等同于不可达。
#   2. 可点元素既无可读名也无装饰标记：函数内有 .clickable + contentDescription = null，
#      却没有可见 Text、没有非空 contentDescription、也没有 clearAndSetSemantics。
#      （原 WARN 4，按基建审计 I3 升 ERROR：当前全仓 0 处命中，成本为零。）
#
# 为什么 ERROR 1 不再按「函数名里有没有 IconButton」判定（基建审计 I3）：
#   全仓 6 个图标按钮里 5 个是自建封装，真正的 IconButton( 只有 1 处；旧实现只认函数名，
#   等于只守住 1/6 的调用面。现在按「调用块里到底传没传名字」判：名字从命名参数、位置实参
#   还是内容 lambda 来都算，拿不到就判红。块内 Icon( 必须带名字这一条与具体封装无关，
#   任何自绘图标按钮都适用。
#   族的构成：函数名以 IconButton 结尾的调用全部自动纳入（IconButton / AppIconButton /
#   HeadIconButton / FinanceIconButton / OverviewIconButton，以及将来同类命名的封装）；
#   名字不带 IconButton、但同样把可读名交给调用方的封装显式登记在 ICON_BUTTON_EXTRAS，
#   否则它的调用点不受检。
#
# WARN（启发式，只报数量与前 20 条，不影响 exit code）：
#   WARN 1. clickable 所在函数没有 testTag：运行时审计与自动化定位都靠这个锚点。
#   WARN 2. clickable 所在修饰链写死 .size(<44.dp) / .height(<44.dp)：触达区不足（44 = token tap-min）。
#   WARN 3. switch/checkbox/toggle 类函数里有 clickable 却没有 toggleable/selectable：
#           状态只靠颜色/位移表达，读屏读不出开或关。
#
# 没有豁免清单：ERROR 项要求全仓 0 处，命中就是真缺口，改代码而不是加白名单。
#
# 已知局限（按行 + 括号/花括号深度判定）：
#   - 行首注释跳过，行尾 // 之后出现的调用片段也跳过；字符串里的括号不计数；
#   - 尾随 lambda 必须与参数右括号在同一行（ktlint 风格即是），写成「右括号换行、
#     下一行才写 {」时块会在右括号处提前结束；
#   - 跨行书写的尺寸（.size( 换行 40.dp )）与写在 .clickable 之后的尺寸不在此判定范围内。
#
# 用法：
#   bash scripts/check-a11y.sh              # 本地与 CI 同一条命令
#   bash scripts/check-a11y.sh --self-test  # 只跑 fixture 自测（校验判据本身）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${A11Y_SRC:-$ROOT/app/src/main/java}"
FIXTURES="$ROOT/scripts/fixtures/a11y"
WARN_SHOW=20
ERROR_TOTAL=2
WARN_TOTAL=3
WARN_HITS=0
FAILED=0
SUMMARY=()

# 名字不带 IconButton、但把可读名交给调用方的封装。新增这类封装时加在这里，
# 否则它的调用点不会进 ERROR 1 的检查面（ERROR 2 仍能兜住「完全没名字」的形态）。
ICON_BUTTON_EXTRAS="DayArrow QuotaHeadIcon"

count_lines() {
    if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | grep -c .; fi
}

TMP_ERR="$(mktemp)"
trap 'rm -f "$TMP_ERR"' EXIT

# 扫描器 stderr 非空即视为门禁自身故障：awk 写错时输出为空，当成 0 处命中就是静默放行。
scanner_error() {
    local err
    err=$(grep -v '^$' "$TMP_ERR" 2>/dev/null)
    : >"$TMP_ERR"
    if [ -n "$err" ]; then
        echo "   门禁自身故障，扫描器报错：$(printf '%s\n' "$err" | head -n 3)"
        return 1
    fi
    return 0
}

# ERROR / WARN 两个 runner：前者期望 0 且影响 exit code，后者只累计。
run_error() {
    local index="$1" title="$2" scanner="$3"
    local out hits
    out="$("$scanner" 2>"$TMP_ERR")"
    hits=$(count_lines "$out")
    echo "== ERROR ${index}/${ERROR_TOTAL} · ${title}（期望 0 处）=="
    if ! scanner_error; then
        SUMMARY+=("FAIL  ERROR ${index} · ${title}：门禁自身故障")
        FAILED=1
        echo
        return
    fi
    if [ "$hits" -eq 0 ]; then
        echo "   PASS：0 处命中"
        SUMMARY+=("PASS  ERROR ${index} · ${title}：0 处命中")
    else
        echo "   FAIL：${hits} 处命中（改法见 docs/UI设计/无障碍基线.md）"
        printf '%s\n' "$out" | sed 's/^/   /'
        SUMMARY+=("FAIL  ERROR ${index} · ${title}：${hits} 处命中")
        FAILED=1
    fi
    echo
}

run_warn() {
    local index="$1" title="$2" scanner="$3"
    local out hits
    out="$("$scanner" 2>"$TMP_ERR")"
    hits=$(count_lines "$out")
    WARN_HITS=$((WARN_HITS + hits))
    echo "== WARN ${index}/${WARN_TOTAL} · ${title}（启发式，不阻断）=="
    if ! scanner_error; then
        SUMMARY+=("FAIL  WARN ${index} · ${title}：门禁自身故障")
        FAILED=1
        echo
        return
    fi
    if [ "$hits" -eq 0 ]; then
        echo "   0 处"
    else
        echo "   ${hits} 处命中（仅列前 ${WARN_SHOW} 条）"
        printf '%s\n' "$out" | head -n "$WARN_SHOW" | sed 's/^/   /'
    fi
    SUMMARY+=("WARN  WARN ${index} · ${title}：${hits} 处")
    echo
}

# ── 扫描器 ──────────────────────────────────────────────────────────

# ERROR 1：图标按钮族的调用块里拿不到可读名。
# 块 = 调用的参数列表 + 紧随其后的内容 lambda（尾随 lambda 与右括号同行）。
# 括号/花括号深度累计，跨行也覆盖；声明行 fun XxxIconButton( 不算调用。
scan_icon_button_without_label() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" -v extras="$ICON_BUTTON_EXTRAS" '
            function nostr(s,   t) { t = s; gsub(/"[^"]*"/, "\"\"", t); return t }
            function popen(s,   t) { t = nostr(s); return gsub(/\(/, "", t) }
            function pclose(s,  t) { t = nostr(s); return gsub(/\)/, "", t) }
            function bopen(s,   t) { t = nostr(s); return gsub(/\{/, "", t) }
            function bclose(s,  t) { t = nostr(s); return gsub(/\}/, "", t) }
            function iscomment(s) { return s ~ /^[[:space:]]*(\*|\/\/|\/\*)/ }

            # 从 openIdx（指向左括号）取配对片段；SCAN_END 置为配对右括号下标。
            function bal(text, openIdx,   i, c, depth, instr, n) {
                depth = 0; instr = 0; n = length(text)
                for (i = openIdx; i <= n; i++) {
                    c = substr(text, i, 1)
                    if (instr) {
                        if (c == "\\") { i++; continue }
                        if (c == "\"") instr = 0
                        continue
                    }
                    if (c == "\"") { instr = 1; continue }
                    if (c == "(") { depth++; continue }
                    if (c == ")") {
                        depth--
                        if (depth == 0) { SCAN_END = i; return substr(text, openIdx + 1, i - openIdx - 1) }
                    }
                }
                SCAN_END = n
                return substr(text, openIdx + 1)
            }

            # 命名参数里有非空可读名（contentDescription / description）？
            function hasNamedLabel(arg,   rest, v, i, n, c) {
                if (!match(arg, /(contentDescription|description)[[:space:]]*=/)) return 0
                rest = substr(arg, RSTART + RLENGTH)
                sub(/^[[:space:]]+/, "", rest)
                n = length(rest); v = ""
                for (i = 1; i <= n; i++) {
                    c = substr(rest, i, 1)
                    if (c == "," || c == "\n") break
                    v = v c
                }
                gsub(/[[:space:]]+$/, "", v)
                return (v != "" && v != "null")
            }

            # 单个顶层实参是不是「位置传名」：字符串字面量，或裸变量名。
            # 排除命名实参（a = ...）与图标常量（Icons.Filled.Add 含点号）。
            function isLabelArg(a,   t) {
                t = a
                gsub(/^[[:space:]]+/, "", t); gsub(/[[:space:]]+$/, "", t)
                if (t == "") return 0
                if (index(t, "=") > 0) return 0
                if (t ~ /^"[^"]*"$/) return 1
                if (t ~ /^[A-Za-z_][A-Za-z0-9_]*$/) return 1
                return 0
            }

            # 参数列表按顶层逗号切分后，有没有位置传名的实参
            # （AppIconButton(Icons.Add, "更多操作", ...) 这种形态）。
            function hasPositionalLabel(arg,   i, n, c, depth, instr, cur) {
                n = length(arg); depth = 0; instr = 0; cur = ""
                for (i = 1; i <= n; i++) {
                    c = substr(arg, i, 1)
                    if (instr) {
                        cur = cur c
                        if (c == "\\") { i++; cur = cur substr(arg, i, 1); continue }
                        if (c == "\"") instr = 0
                        continue
                    }
                    if (c == "\"") { instr = 1; cur = cur c; continue }
                    if (c == "(" || c == "[" || c == "{") depth++
                    else if (c == ")" || c == "]" || c == "}") depth--
                    if (c == "," && depth == 0) {
                        if (isLabelArg(cur)) return 1
                        cur = ""
                        continue
                    }
                    cur = cur c
                }
                return isLabelArg(cur)
            }

            # 块内每个 Icon(...) 都必须带非空 contentDescription；返回缺名个数。
            function countUnlabeledIcons(body,   i, n, j, prev, arg, hits) {
                n = length(body); hits = 0
                for (i = 1; i + 3 <= n; i++) {
                    if (substr(body, i, 4) != "Icon") continue
                    prev = (i > 1) ? substr(body, i - 1, 1) : " "
                    if (prev ~ /[A-Za-z0-9_]/) continue
                    j = i + 4
                    while (j <= n && substr(body, j, 1) ~ /[[:space:]]/) j++
                    if (substr(body, j, 1) != "(") continue
                    arg = bal(body, j)
                    if (!hasNamedLabel(arg)) hits++
                    i = SCAN_END
                }
                return hits
            }

            # 块结束：括号与花括号都配平，且行尾是 ) 或 }（尾随 lambda 结束）。
            function blockDone(line) {
                if (pdepth > 0 || bdepth > 0) return 0
                return (line ~ /[)}][[:space:]]*$/)
            }

            # 判定：块内 Icon 缺名 → 红；块内没有 Icon 可查（名字交给调用方的封装）→ 必须显式传名。
            # Material IconButton( 本身把标签写在 lambda 里的 Icon 上，故不要求实参传名。
            # 内容 lambda 透传（{ label -> Icon(contentDescription = label) }）之所以不够：
            # label 的取值就是实参 contentDescription，调用方不传时它是 null，静态看不到。
            function report(   p, pidx, argText, labeled, nicon, mat) {
                p = nmpos + length(nm)
                pidx = p - 1 + index(substr(startline, p), "(")
                argText = bal(body, pidx)
                labeled = hasNamedLabel(argText) || hasPositionalLabel(argText)
                nicon = countUnlabeledIcons(body)
                mat = (nm == "IconButton")
                if (nicon > 0)
                    print rel ":" startNR ": " startline "  ← 块内 " nicon " 个 Icon 缺 contentDescription"
                else if (!mat && !labeled)
                    print rel ":" startNR ": " startline "  ← 调用未给可读名（无 contentDescription/description 实参）"
            }

            BEGIN {
                fam = "[A-Za-z0-9_]*IconButton"
                n = split(extras, e, " ")
                for (i = 1; i <= n; i++) if (e[i] != "") fam = fam "|" e[i]
                inblk = 0
            }

            {
                line = $0
                if (inblk) {
                    body = body "\n" line
                    pdepth += popen(line) - pclose(line)
                    bdepth += bopen(line) - bclose(line)
                    if (blockDone(line)) { report(); inblk = 0 }
                    next
                }
                if (iscomment(line)) next
                if (!match(line, fam "[[:space:]]*\\(")) next
                nm = substr(line, RSTART, RLENGTH)
                sub(/[[:space:]]*\($/, "", nm)
                nmpos = RSTART
                # 行尾 // 之后的内容不当代码
                if (nmpos > 2 && index(substr(line, 1, nmpos - 1), "//") > 0) next
                # 声明行 fun XxxIconButton(... 不算调用
                if (match(line, /(^|[^A-Za-z0-9_])fun[[:space:]]/)) {
                    if (RSTART < nmpos) next
                }
                inblk = 1; startNR = NR; startline = line; body = line
                # 首行也要算净增量：IconButton(onClick = {}) { 这一行同时含左括号、右括号与花括号
                pdepth = popen(line) - pclose(line); bdepth = bopen(line) - bclose(line)
                if (blockDone(line)) { report(); inblk = 0 }
            }
        ' "$f"
    done
}

# 公共前置：同一文件读两遍。第一遍收集行内容 L[] 与函数作用域（fun 行起、花括号回落），
# 并算出每个函数是否含 testTag / toggleable；第二遍按当前行落在哪个函数内做判定。
FUNC_AWK_PRELUDE='
    function bopen(s,   t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\{/, "", t) }
    function bclose(s,  t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\}/, "", t) }
    function iscomment(s) { return s ~ /^[[:space:]]*(\*|\/\/|\/\*)/ }
    FNR == NR {
        L[FNR] = $0; tot = FNR
        if (iscomment($0)) next
        isfun = ($0 ~ /^[[:space:]]*[A-Za-z]*[[:space:]]*fun[[:space:]]+[A-Za-z0-9_]+/)
        # 表达式体函数（fun f() = ...）没有花括号：遇到下一处 fun 声明即视为上一个函数结束
        if (inf && !fopen[cur] && isfun) { fend[cur] = FNR - 1; inf = 0 }
        if (!inf) {
            if (!isfun) next
            cur++; name = $0
            sub(/.*fun[[:space:]]+/, "", name); sub(/[^A-Za-z0-9_].*/, "", name)
            fname[cur] = name; fstart[cur] = FNR; fdep[cur] = 0; fopen[cur] = 0
            ftag[cur] = 0; ftoggle[cur] = 0; inf = 1
            ftext[cur] = 0; flabel[cur] = 0; fnull[cur] = 0; fclear[cur] = 0
        }
        # Glance（RemoteViews）没有 testTag(...) 函数，只有 semantics { testTag = "…" } 属性形式，
        # 两种写法都算「有标识」（glance-testing 的 hasTestTag 读的也是它）。
        if ($0 ~ /testTag[[:space:]]*(\(|=)/) ftag[cur] = 1
        if ($0 ~ /(toggleable|selectable)[[:space:]]*\(/) ftoggle[cur] = 1
        if ($0 ~ /Text[[:space:]]*\(/) ftext[cur] = 1
        if ($0 ~ /clearAndSetSemantics/) fclear[cur] = 1
        if ($0 ~ /contentDescription[[:space:]]*=[[:space:]]*null/) fnull[cur] = 1
        if ($0 ~ /contentDescription[[:space:]]=/ && $0 !~ /contentDescription[[:space:]]*=[[:space:]]*null/) flabel[cur] = 1
        d = bopen($0); c = bclose($0)
        if (d > c) fopen[cur] = 1
        fdep[cur] += d - c
        if (fopen[cur] && fdep[cur] <= 0) { fend[cur] = FNR; inf = 0 }
        next
    }
    !prepped {
        prepped = 1; fi = 1
        for (i = 1; i <= cur; i++) if (!(i in fend)) fend[i] = (i < cur ? fstart[i + 1] - 1 : tot)
    }
';

# 把当前行归到所属函数：区间回填，infun=1 表示落在某个函数体内。
FUNC_AWK_ENTER='
    {
        while (fi <= cur && FNR > fend[fi]) fi++
        infun = (fi <= cur && FNR >= fstart[fi] && FNR <= fend[fi])
    }
';

# WARN 1：clickable 所在函数内没有 testTag 调用。
scan_clickable_without_testtag() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" "$FUNC_AWK_PRELUDE"'
            '"$FUNC_AWK_ENTER"'
            {
                if (!infun || iscomment($0)) next
                if ($0 !~ /\.clickable[[:space:]]*(\(|\{)/) next
                if (ftag[fi]) next
                print rel ":" FNR ": " $0
            }
        ' "$f" "$f"
    done
}

# WARN 2：clickable 同一条修饰链上写死 .size(<44.dp) / .height(<44.dp)。
# 修饰链 = 从 .clickable 行向上连续「以 . 开头」或「以 , / . 结尾」的行。
scan_clickable_small_touch() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" "$FUNC_AWK_PRELUDE"'
            function small(s,   v) {
                if (!match(s, /\.(size|height)\([[:space:]]*[0-9]+(\.[0-9]+)?[[:space:]]*\.dp/)) return ""
                v = substr(s, RSTART, RLENGTH); sub(/.*\([[:space:]]*/, "", v); sub(/[^0-9.].*/, "", v)
                if (v + 0 >= 44) return ""
                return substr(s, RSTART, RLENGTH)
            }
            '"$FUNC_AWK_ENTER"'
            {
                if (!infun || iscomment($0)) next
                if ($0 !~ /\.clickable[[:space:]]*(\(|\{)/) next
                k = FNR - 1; found = ""
                while (k >= fstart[fi] && k >= 1) {
                    hit = small(L[k]); if (hit != "") found = found " " hit
                    if (L[k] ~ /^[[:space:]]*\./ || L[k] ~ /[.,][[:space:]]*$/) { k--; continue }
                    break
                }
                if (found != "") print rel ":" FNR ": " $0 "  ← 修饰链" found
            }
        ' "$f" "$f"
    done
}

# WARN 3：switch/checkbox/toggle 类函数里有 clickable，却没有 toggleable/selectable。
scan_toggle_without_semantics() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" "$FUNC_AWK_PRELUDE"'
            '"$FUNC_AWK_ENTER"'
            {
                if (!infun || iscomment($0)) next
                if ($0 !~ /\.clickable[[:space:]]*(\(|\{)/) next
                if (fname[fi] !~ /[Ss]witch|[Cc]heck|[Tt]oggle/) next
                if (ftoggle[fi]) next
                print rel ":" FNR ": " $0 "  ← 函数 " fname[fi]
            }
        ' "$f" "$f"
    done
}

# ERROR 2：可点元素既无可读名也无装饰标记（原 WARN 4，按 I3 升档）。
scan_clickable_without_readable_name() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" "$FUNC_AWK_PRELUDE"'
            '"$FUNC_AWK_ENTER"'
            {
                if (!infun || iscomment($0)) next
                if ($0 !~ /\.clickable[[:space:]]*(\(|\{)/) next
                if (!fnull[fi] || fclear[fi] || ftext[fi] || flabel[fi]) next
                print rel ":" FNR ": " $0 "  ← 函数 " fname[fi] " 无可读名兜底"
            }
        ' "$f" "$f"
    done
}

# ── fixture 自测：判据本身的回归（--self-test）─────────────────────────
# 每个用例是一个独立目录、只含一个 .kt；期望命中数是「旧实现会放过 / 改后必须拦住」的证据。
# 新增判据时先在这里加用例，再改扫描器。
self_test() {
    if [ ! -d "$FIXTURES" ]; then
        echo "缺 fixture 目录：$FIXTURES" >&2
        return 1
    fi
    local failed=0 cases dir fn expect desc out hits
    cases="$(mktemp)"
    cat >"$cases" <<'CASES'
error1-iconbutton-unlabeled|scan_icon_button_without_label|1|Icon 省略 contentDescription（真实 IconButton，旧实现判 PASS）
error1-dayarrow-unlabeled|scan_icon_button_without_label|1|自建封装名不带 IconButton 且无命名实参（旧实现判 PASS）
error1-finance-lambda-unlabeled|scan_icon_button_without_label|1|FinanceIconButton 无命名实参，标签只在 lambda 里引用了 null 默认值（旧实现判 PASS）
error1-appiconbutton-null-label|scan_icon_button_without_label|1|自建 AppIconButton 传 contentDescription = null（旧实现已能判红，防回归）
error1-labeled-good|scan_icon_button_without_label|0|三种合规传名形态各一处，不得误报
error2-clickable-no-name|scan_clickable_without_readable_name|1|可点元素只有无名图标、无文本无标签（原 WARN 4）
error2-labeled-good|scan_clickable_without_readable_name|0|clickable + 可见 Text，不得误报
CASES
    echo "无障碍门禁自测（fixture：${FIXTURES}）"
    echo
    while IFS='|' read -r dir fn expect desc; do
        [ -z "$dir" ] && continue
        if [ ! -d "$FIXTURES/$dir" ]; then
            echo "  FAIL ${dir}：fixture 目录不存在"
            failed=1
            continue
        fi
        SRC="$FIXTURES/$dir"
        out="$("$fn" 2>/dev/null)"
        hits=$(count_lines "$out")
        if [ "$hits" -eq "$expect" ]; then
            echo "  PASS ${dir}：${hits} 处（期望 ${expect}）· ${desc}"
        else
            echo "  FAIL ${dir}：${hits} 处（期望 ${expect}）· ${desc}"
            printf '%s\n' "$out" | sed 's/^/         /'
            failed=1
        fi
    done <"$cases"
    rm -f "$cases"
    echo
    if [ "$failed" -eq 0 ]; then
        echo "自测结果：PASS（判据按预期命中 / 不误报）"
        return 0
    fi
    echo "自测结果：FAIL（判据与 fixture 期望不符，先修扫描器）"
    return 1
}

if [ "${1:-}" = "--self-test" ]; then
    self_test
    exit $?
fi

echo "无障碍静态门禁：$SRC"
echo

run_error 1 "图标按钮调用拿不到可读名（命名参数 / 位置实参 / lambda 透传；块内 Icon 必须有名）" scan_icon_button_without_label
run_error 2 "可点元素无可读名兜底（原 WARN 4 升档）" scan_clickable_without_readable_name
run_warn 1 "clickable 所在函数没有 testTag" scan_clickable_without_testtag
run_warn 2 "clickable 修饰链写死触达区 <44dp" scan_clickable_small_touch
run_warn 3 "switch/checkbox 无 toggleable/selectable" scan_toggle_without_semantics

echo "================ 汇总 ================"
for row in "${SUMMARY[@]}"; do
    echo "  $row"
done
echo "--------------------------------------"
if [ "$FAILED" -eq 0 ]; then
    echo "结果：PASS（ERROR 0 处 / WARN ${WARN_HITS} 处）"
    echo "ERROR ${ERROR_TOTAL} 项检查全部为 0；WARN 只报不阻断，按红线逐步收敛。"
    exit 0
fi
echo "结果：FAIL（ERROR 有命中，见上方 文件:行；改法见 docs/UI设计/无障碍基线.md）"
echo "ERROR ${ERROR_TOTAL} 项检查有命中 / WARN ${WARN_HITS} 处"
exit 1
