#!/usr/bin/env bash
# 覆盖率门禁：解析 JaCoCo XML 报告，按模块分别判定，任一模块不达标 exit 1。
#
# 报告来源（两条命令都由 CI 先跑）：
#   app    ./gradlew :app:coverageReport      -> app/build/reports/coverage/app-debug/report.xml
#   domain ./gradlew :domain:jacocoTestReport -> domain/build/reports/jacoco/test/jacocoTestReport.xml
# 两份报告都已排除生成代码（清单与理由见各自的 build.gradle.kts）。
#
# 阈值全部从环境变量读。默认值是**实测基线向下取整**，不是理想值：
# 改阈值前先看报告里的真实数字；反过来改排除清单把分母做小、让数字好看，就是造假。
#
# 用法：
#   bash scripts/check-coverage.sh
#   COVERAGE_APP_MIN_LINE=25 bash scripts/check-coverage.sh
#   COVERAGE_APP_REPORT=/path/to/a.xml COVERAGE_DOMAIN_REPORT=/path/to/d.xml bash scripts/check-coverage.sh
#
# 两个模块的可信度差别（决定了阈值为什么差这么多）：
#   domain：纯 Kotlin/JVM，无 Robolectric 沙箱，数字**完全可信**，实测行 49.38%，阈值 48%。
#   app   ：Robolectric 沙箱 ClassLoader 绕过 JaCoCo 在线 agent，被它覆盖的类
#           （FinanceDao / FinanceManager / PandaImporter 等）恒为 0%；只有纯 JVM 用例的覆盖能进来。
#           实测行 21.74%，阈值 21% —— 它防的是「纯 JVM 可测代码的覆盖率下滑」，不是整体质量分。
#
# 注意：脚本里所有变量引用都写成 ${var}。中文全角标点紧跟变量名时，
# bash 在某些 locale 下会把标点首字节当成变量名的一部分，导致「unbound variable」。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- app 模块（Robolectric 覆盖缺失，口径偏低）----
APP_REPORT="${COVERAGE_APP_REPORT:-${ROOT}/app/build/reports/coverage/app-debug/report.xml}"
APP_MIN_LINE="${COVERAGE_APP_MIN_LINE:-21}"
APP_MIN_BRANCH="${COVERAGE_APP_MIN_BRANCH:-21}"
# 核心业务包单独卡一道，防止「堆 UI 文件摊薄全局平均」式的偷懒。
# 该包不在报告里（被排除或改名）时跳过，不判失败。
APP_CORE_PACKAGE="${COVERAGE_APP_CORE_PACKAGE:-autobook}"
APP_MIN_CORE_LINE="${COVERAGE_APP_MIN_CORE_LINE:-40}"

# ---- domain 模块（纯 JVM，数字完全可信）----
DOMAIN_REPORT="${COVERAGE_DOMAIN_REPORT:-${ROOT}/domain/build/reports/jacoco/test/jacocoTestReport.xml}"
DOMAIN_MIN_LINE="${COVERAGE_DOMAIN_MIN_LINE:-48}"
DOMAIN_MIN_BRANCH="${COVERAGE_DOMAIN_MIN_BRANCH:-35}"

# report.xml 是单行 minified XML（Ant/AGP/Gradle 输出都不换行），必须用真正的 XML 解析器。
# python3 在 ubuntu-latest 与 macOS 都自带，不引第三方依赖。
PY="${PYTHON:-python3}"
if ! command -v "${PY}" > /dev/null 2>&1; then
    echo "FAIL: 找不到 python3（本脚本用它解析单行 minified XML）"
    exit 1
fi

FAILED=0
SUMMARY=()

# 百分比，保留两位
pct() {
    awk -v c="$1" -v m="$2" 'BEGIN {
        d = c + m
        if (d == 0) { printf "0.00"; exit }
        printf "%.2f", c * 100 / d
    }'
}

# judge <label> <covered> <missed> <min>
judge() {
    local label="$1" covered="$2" missed="$3" min="$4"
    local denom=$((covered + missed))
    if [ "${denom}" -eq 0 ]; then
        echo "  SKIP  ${label}: 无数据（covered=0 missed=0）"
        SUMMARY+=("SKIP  ${label}: 无数据")
        return 0
    fi
    local value pass
    value="$(pct "${covered}" "${missed}")"
    pass="$(awk -v v="${value}" -v m="${min}" 'BEGIN { print (v + 0 >= m + 0) ? "1" : "0" }')"
    if [ "${pass}" = "1" ]; then
        echo "  PASS  ${label}: ${value}% >= ${min}%  (${covered}/${denom})"
        SUMMARY+=("PASS  ${label}: ${value}% >= ${min}%")
    else
        echo "  FAIL  ${label}: ${value}% < ${min}%  (${covered}/${denom})"
        SUMMARY+=("FAIL  ${label}: ${value}% < ${min}%")
        FAILED=$((FAILED + 1))
    fi
}

# 解析一份报告：输出 subject<TAB>type<TAB>covered<TAB>missed
# subject 为空表示全量；否则是 com/bsfeng/... 形式的包名。
parse_report() {
    "${PY}" - "$1" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()


def counters(el):
    return {
        c.get('type'): (int(c.get('covered')), int(c.get('missed')))
        for c in el.findall('counter')
    }


for t in ('LINE', 'BRANCH'):
    covered, missed = counters(root).get(t, (0, 0))
    print('\t%s\t%d\t%d' % (t, covered, missed))

for pkg in root.findall('package'):
    p = counters(pkg)
    for t in ('LINE', 'BRANCH'):
        covered, missed = p.get(t, (0, 0))
        print('%s\t%s\t%d\t%d' % (pkg.get('name'), t, covered, missed))
PYEOF
}

# 从 PARSED 里取某部门某类型的 "covered missed"
get_counts() {
    local parsed="$1" subject="$2" type="$3"
    printf '%s\n' "${parsed}" | awk -F'\t' -v s="${subject}" -v t="${type}" '$1 == s && $2 == t { print $3, $4; exit }'
}

# check_module <模块名> <标题> <报告路径> <生成命令> <min_line> <min_branch> [core_pkg min_core_line]
check_module() {
    local module="$1" title="$2" report="$3" cmd="$4" min_line="$5" min_branch="$6"
    local core_pkg="${7:-}" min_core="${8:-}"

    echo "================================================"
    echo "${title}"
    echo "  报告: ${report}"
    if [ -n "${core_pkg}" ]; then
        echo "  阈值: 行 >= ${min_line}%  分支 >= ${min_branch}%  (核心包 ${core_pkg} 行 >= ${min_core}%)"
    else
        echo "  阈值: 行 >= ${min_line}%  分支 >= ${min_branch}%"
    fi

    if [ ! -f "${report}" ]; then
        echo "  FAIL: 找不到报告"
        echo "  先跑: ${cmd}"
        SUMMARY+=("FAIL  ${module}: 缺报告")
        FAILED=$((FAILED + 1))
        echo
        return 0
    fi

    local parsed
    parsed="$(parse_report "${report}")"
    if [ -z "${parsed}" ]; then
        echo "  FAIL: 报告解析失败（没有任何 counter），格式可能变了"
        SUMMARY+=("FAIL  ${module}: 解析失败")
        FAILED=$((FAILED + 1))
        echo
        return 0
    fi

    local line_counts branch_counts
    line_counts="$(get_counts "${parsed}" "" LINE)"
    branch_counts="$(get_counts "${parsed}" "" BRANCH)"
    if [ -z "${line_counts}" ] || [ -z "${branch_counts}" ]; then
        echo "  FAIL: 报告里没解析到全量 LINE/BRANCH counter"
        SUMMARY+=("FAIL  ${module}: 缺全量 counter")
        FAILED=$((FAILED + 1))
        echo
        return 0
    fi

    echo
    echo "  -- 全量 --"
    # shellcheck disable=SC2086 # 有意分词成 covered missed 两个参数
    judge "${module} 行覆盖率" ${line_counts} "${min_line}"
    # shellcheck disable=SC2086
    judge "${module} 分支覆盖率" ${branch_counts} "${min_branch}"

    if [ -n "${core_pkg}" ]; then
        echo
        echo "  -- 核心包 ${core_pkg} --"
        local core_line
        core_line="$(get_counts "${parsed}" "${core_pkg}" LINE)"
        if [ -n "${core_line}" ]; then
            # shellcheck disable=SC2086
            judge "${module} 核心包行覆盖率" ${core_line} "${min_core}"
        else
            echo "  SKIP  核心包 ${core_pkg} 不在报告里（被排除或改名）"
            SUMMARY+=("SKIP  ${module} 核心包不在报告里")
        fi
    fi

    echo
    echo "  -- 分包明细（按行覆盖率降序）--"
    printf '%s\n' "${parsed}" \
        | awk -F'\t' '$1 != "" && $2 == "LINE" { printf "%s\t%d\t%d\n", $1, $3, $3 + $4 }' \
        | sort -t$'\t' -k3,3nr \
        | while IFS=$'\t' read -r pkg covered denom; do
            if [ "${denom}" -eq 0 ]; then
                continue
            fi
            branch_line="$(get_counts "${parsed}" "${pkg}" BRANCH)"
            bdenom=0
            bvalue="0.00"
            if [ -n "${branch_line}" ]; then
                set -- ${branch_line}
                bdenom=$(($1 + $2))
                if [ "${bdenom}" -gt 0 ]; then
                    bvalue="$(pct "$1" "$2")"
                fi
            fi
            line_value="$(pct "${covered}" "$((denom - covered))")"
            awk -v p="${pkg}" -v lv="${line_value}" -v lc="${covered}" -v ld="${denom}" \
                -v bv="${bvalue}" -v bd="${bdenom}" 'BEGIN {
                    short = p
                    sub(/^com\/bsfeng\/monitor\//, "", short)
                    if (short == "") short = "(root)"
                    printf "    %-30s 行 %6s%% (%d/%d)   分支 %6s%% (%d)\n", short, lv, lc, ld, bv, bd
                }'
          done
    echo
}

echo "覆盖率门禁（两个模块分别判定，都过才算过）"
echo

check_module "app" "app 模块（Robolectric 覆盖缺失，口径偏低）" \
    "${APP_REPORT}" "./gradlew :app:coverageReport" \
    "${APP_MIN_LINE}" "${APP_MIN_BRANCH}" \
    "com/bsfeng/monitor/${APP_CORE_PACKAGE}" "${APP_MIN_CORE_LINE}"

check_module "domain" "domain 模块（纯 JVM，数字完全可信）" \
    "${DOMAIN_REPORT}" "./gradlew :domain:jacocoTestReport" \
    "${DOMAIN_MIN_LINE}" "${DOMAIN_MIN_BRANCH}"

echo "================ 汇总 ================"
for row in "${SUMMARY[@]}"; do
    echo "  ${row}"
done
echo "--------------------------------------"
if [ "${FAILED}" -eq 0 ]; then
    echo "结果: PASS（两个模块覆盖率均达标）"
    exit 0
fi
echo "结果: FAIL（${FAILED} 项未达阈值；补测试，而不是调低阈值或改排除清单）"
exit 1
