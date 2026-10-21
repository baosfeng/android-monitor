#!/usr/bin/env bash
# 渲染期（GPU）规则门禁：命中即 exit 1。
#
# 四项检查，各由一个 grep / awk 判定、期望值为 0、命中时打印「文件:行:内容」：
#   1. 页面自画底衬渐变：*Screen.kt 出现 Brush.verticalGradient(listOf(t.backdropA
#      （底衬统一走 foundation/Backdrop.kt，避免每屏各建一份全屏渐变）
#   2. 绘制 lambda 内分配：onDrawBehind / onDrawWithContent 体内出现 Path() 或 Brush. 构造
#      （对象进 drawWithCache 的 build 块，绘制期只读）
#   3. 动画值走布局：Modifier.offset(...) 引用 animate*AsState / animateFloat 得到的值
#      （改走 foundation/GpuMotion.kt 的绘制期位移，避免每帧 measure/layout）
#   4. 转场裸 spec：AnimatedVisibility(...) 参数块内出现不带 animationSpec 的
#      slideInHorizontally() / slideOutHorizontally() / slideInVertically() / slideOutVertically() /
#      fadeIn() / fadeOut()（这类裸调用取 Compose 默认 spec：不读设计 token、不响应减弱动效）
#      （统一改用 foundation/Transitions.kt 的 slideInFromRight / sheetEnterFromBottom / dialogEnter /
#        expandEnter 等基元，或 AnimatedScreenOverlay 一步式载体）
#
# 已知局限：按行判定，字符串 / 注释里的花括号可能影响块结束位置；
# 跨行书写的 offset(x = 变量) 不在此判定范围内 —— 这两类写法在本仓库不出现。
# 用法：bash scripts/check-ui-rules.sh（本地与 CI 同一条命令）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
TOTAL_CHECKS=4
FAILED=0
SUMMARY=()

run_check() {
    local index="$1" title="$2" expected="$3"
    shift 3
    local out
    out="$("$@")"
    local hits=0
    if [ -n "$out" ]; then
        hits=$(printf '%s\n' "$out" | grep -c .)
    fi
    echo "== 检查 ${index}/${TOTAL_CHECKS} · ${title}（期望 ${expected} 处）=="
    if [ "$hits" -eq 0 ]; then
        echo "   PASS：0 处命中"
        SUMMARY+=("PASS  检查 ${index} · ${title}：0 处命中")
    else
        echo "   FAIL：${hits} 处命中"
        printf '%s\n' "$out" | sed 's/^/   /'
        SUMMARY+=("FAIL  检查 ${index} · ${title}：${hits} 处命中（期望 ${expected}）")
        FAILED=1
    fi
    echo
}

# 检查 1：页面（*Screen.kt）不得自画底衬渐变；foundation/Backdrop.kt 是唯一实现处。
scan_page_backdrop() {
    find "$SRC" -type f -name '*Screen.kt' | sort | while IFS= read -r f; do
        grep -nE 'Brush\.verticalGradient\(listOf\(t\.backdropA' "$f" \
            | awk -v rel="${f#"$ROOT"/}" '{ print rel ":" $0 }'
    done
}

# 检查 2：onDrawBehind / onDrawWithContent 体内不得 new Path() / Brush.；按花括号深度跟踪块范围。
scan_draw_alloc() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" '
            function braces(s,   t) { t = s; return gsub(/\{/, "", t) }
            function closebraces(s,   t) { t = s; return gsub(/\}/, "", t) }
            {
                opened = braces($0)
                closed = closebraces($0)
                if ($0 ~ /(onDrawBehind|onDrawWithContent)[[:space:]]*\{/) {
                    inblock = 1
                    entry = depth
                }
                if (inblock && $0 !~ /^[[:space:]]*\/\// && ($0 ~ /Path\(\)/ || $0 ~ /Brush\.[A-Za-z_][A-Za-z0-9_]*\(/)) {
                    print rel ":" NR ": " $0
                }
                depth += opened - closed
                if (inblock && depth <= entry) inblock = 0
            }
        ' "$f"
    done
}

# 检查 3：offset(...) 引用动画状态（animateDpAsState / animateFloatAsState / transition.animateFloat…）。
scan_animated_offset() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        names=$(grep -oE 'val [A-Za-z_][A-Za-z0-9_]* (by|=) ([A-Za-z_][A-Za-z0-9_]*\.)?animate[A-Za-z]+(AsState)?\(' "$f" \
            | awk '{ print $2 }' | sort -u | paste -sd'|' -)
        [ -z "$names" ] && continue
        grep -nE "\.offset\([^)]*($names)([^A-Za-z0-9_]|$)" "$f" \
            | awk -v rel="${f#"$ROOT"/}" '{ print rel ":" $0 }'
    done
}

# 检查 4：AnimatedVisibility 的 enter/exit 里不得出现不带 animationSpec 的裸转场调用。
# 判定方式：按括号深度取出每个 `AnimatedVisibility(` 的参数块（不是它的 content lambda），
# 块内匹配「函数名 + 空括号」，即 slideInHorizontally() / fadeIn() 这类取 Compose 默认 spec 的写法；
# 带参数的写法（tween(...) / fadeIn(tween(...))）不命中。同一文件可多次出现，逐个报。
scan_bare_transition_spec() {
    find "$SRC" -type f -name '*.kt' | sort | while IFS= read -r f; do
        awk -v rel="${f#"$ROOT"/}" '
            function opens(s,   t) { t = s; gsub(/\{[^{}]*\}/, "", t); return gsub(/\(/, "", t) }
            function closes(s,   t) { t = s; gsub(/\{[^{}]*\}/, "", t); return gsub(/\)/, "", t) }
            function scan(buf,   name) {
                if (match(buf, /(slideInHorizontally|slideOutHorizontally|slideInVertically|slideOutVertically|fadeIn|fadeOut)[[:space:]]*\([[:space:]]*\)/)) {
                    name = substr(buf, RSTART, RLENGTH)
                    gsub(/[[:space:]]+/, "", name)
                    return name
                }
                return ""
            }
            {
                if (!inblock && $0 ~ /AnimatedVisibility[[:space:]]*\(/) {
                    inblock = 1; start = NR; buf = ""; depth = 0
                }
                if (inblock) {
                    buf = buf " " $0
                    depth += opens($0) - closes($0)
                    if (depth <= 0) {
                        hit = scan(buf)
                        if (hit != "") print rel ":" start ": " hit
                        inblock = 0
                    }
                }
            }
        ' "$f"
    done
}

echo "渲染期规则门禁：$SRC"
echo

run_check 1 "页面自画底衬渐变" 0 scan_page_backdrop
run_check 2 "绘制 lambda 内分配 Path/Brush" 0 scan_draw_alloc
run_check 3 "动画值走 Modifier.offset（布局属性）" 0 scan_animated_offset
run_check 4 "AnimatedVisibility 转场走统一基元（无裸 spec）" 0 scan_bare_transition_spec

echo "================ 汇总 ================"
for row in "${SUMMARY[@]}"; do
    echo "  $row"
done
echo "--------------------------------------"
if [ "$FAILED" -eq 0 ]; then
    echo "结果：PASS（${TOTAL_CHECKS} 项检查，合计 0 处命中）"
    exit 0
fi
echo "结果：FAIL（命中项见上方 文件:行，修复后重跑本脚本）"
exit 1
