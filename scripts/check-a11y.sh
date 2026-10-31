#!/usr/bin/env bash
# 无障碍静态门禁：ERROR 阻断，WARN 只报不阻断。
#
# 口径见 docs/UI设计/无障碍基线.md 的四条红线。扫描范围固定 app/src/main/java
# （与 check-ui-rules.sh 一致；debug 源集待存量清理后再纳入）。
#
# ERROR（确定可判定，必须为 0，命中即 exit 1）：
#   1. IconButton 家族的调用块内出现 contentDescription = null
#      —— 图标按钮的图标没有名字时读屏只说「按钮」，等同于不可达。
#
# WARN（启发式，只报数量与前 20 条，不影响 exit code）：
#   WARN 1. clickable 所在函数没有 testTag：运行时审计与自动化定位都靠这个锚点。
#   WARN 2. clickable 所在修饰链写死 .size(<48.dp) / .height(<48.dp)：触达区不足。
#   WARN 3. switch/checkbox/toggle 类函数里有 clickable 却没有 toggleable/selectable：
#           状态只靠颜色/位移表达，读屏读不出开或关。
#   WARN 4. 可点元素既无可读名也无装饰标记：函数内有 clickable + contentDescription = null，
#           却没有可见 Text、没有非空 contentDescription、也没有 clearAndSetSemantics。
#
# 为什么「clickable 内有 Icon(contentDescription = null)」只做 WARN（原定 ERROR，已降级）：
#   函数级宽松判定在本仓库命中 20 处，全部是合规写法：19 处是「行有可见文本 + 右侧装饰 chevron」
#   （文本给了可读名、图标是纯装饰，基线文档允许豁免），1 处用 .semantics 给了标签。
#   按行判定分不清「唯一内容是无名图标」和「有文本的控件里摆了装饰图标」，做成 ERROR 会误报
#   并堵死构建，故降级为 WARN 4，并把判定收窄到「既无文本也无非空 contentDescription 也无
#   clearAndSetSemantics」的形态：当前命中 0 处，作为新增代码的回归拦截。
#
# 没有豁免清单：ERROR 项要求全仓 0 处，命中就是真缺口，改代码而不是加白名单。
#
# 已知局限：按行 + 花括号深度判定，字符串/注释里的花括号可能影响函数结束位置；
# 跨行书写的尺寸（.size( 换行 40.dp )）与写在 .clickable 之后的尺寸不在此判定范围内。
# 用法：bash scripts/check-a11y.sh（本地与 CI 同一条命令）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
WARN_SHOW=20
ERROR_TOTAL=1
WARN_TOTAL=4
WARN_HITS=0
FAILED=0
SUMMARY=()

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

# ERROR 1：IconButton 家族调用块（参数块 + 内容 lambda）内出现 contentDescription = null。
# 括号 + 花括号深度累计，跨行也覆盖；函数定义行（fun XxxIconButton）不算调用。
scan_icon_button_without_label() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" '
            function opens(s,   t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\(/, "", t) }
            function closes(s,  t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\)/, "", t) }
            function bopens(s,  t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\{/, "", t) }
            function bcloses(s, t) { t = s; gsub(/"[^"]*"/, "", t); return gsub(/\}/, "", t) }
            {
                line = $0
                if (!inblk) {
                    if (line ~ /^[[:space:]]*(\*|\/\/|\/\*)/) next
                    if (line ~ /fun[[:space:]]+[A-Za-z0-9_]*IconButton/) next
                    if (line !~ /[A-Za-z0-9_]*IconButton[[:space:]]*\(/) next
                    inblk = 1; start = NR; startline = line; buf = ""; depth = 0
                }
                buf = buf " " line
                depth += opens(line) + bopens(line) - closes(line) - bcloses(line)
                if (depth <= 0) {
                    if (buf ~ /contentDescription[[:space:]]*=[[:space:]]*null/) print rel ":" start ": " startline
                    inblk = 0
                }
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
        if ($0 ~ /testTag[[:space:]]*\(/) ftag[cur] = 1
        if ($0 ~ /(toggleable|selectable)[[:space:]]*\(/) ftoggle[cur] = 1
        if ($0 ~ /Text[[:space:]]*\(/) ftext[cur] = 1
        if ($0 ~ /clearAndSetSemantics/) fclear[cur] = 1
        if ($0 ~ /contentDescription[[:space:]]*=[[:space:]]*null/) fnull[cur] = 1
        if ($0 ~ /contentDescription[[:space:]]*=/ && $0 !~ /contentDescription[[:space:]]*=[[:space:]]*null/) flabel[cur] = 1
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

# WARN 2：clickable 同一条修饰链上写死 .size(<48.dp) / .height(<48.dp)。
# 修饰链 = 从 .clickable 行向上连续「以 . 开头」或「以 , / . 结尾」的行。
scan_clickable_small_touch() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" "$FUNC_AWK_PRELUDE"'
            function small(s,   v) {
                if (!match(s, /\.(size|height)\([[:space:]]*[0-9]+(\.[0-9]+)?[[:space:]]*\.dp/)) return ""
                v = substr(s, RSTART, RLENGTH); sub(/.*\([[:space:]]*/, "", v); sub(/[^0-9.].*/, "", v)
                if (v + 0 >= 48) return ""
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

# WARN 4：可点元素既无可读名也无装饰标记（ERROR 第 2 条的降级形态，收窄口径见文件头）。
scan_clickable_icon_without_label() {
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

echo "无障碍静态门禁：$SRC"
echo

run_error 1 "IconButton 内图标无 contentDescription" scan_icon_button_without_label
run_warn 1 "clickable 所在函数没有 testTag" scan_clickable_without_testtag
run_warn 2 "clickable 修饰链写死触达区 <48dp" scan_clickable_small_touch
run_warn 3 "switch/checkbox 无 toggleable/selectable" scan_toggle_without_semantics
run_warn 4 "可点图标无可读名兜底（ERROR 降级）" scan_clickable_icon_without_label

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
