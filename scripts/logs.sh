#!/usr/bin/env bash
# Agent Drive 日志查询助手（服务器上执行，不依赖 jq）。
#
# 用法：
#   logs.sh api [选项]        API 进程日志（journalctl -u agent-drive-java.service）
#   logs.sh worker [选项]     Worker 进程日志（journalctl -u agent-drive-java-worker.service）
#   audit 日志不再由本地文件提供；按 request_id 查询 API/Worker journal 即可。
#
# 选项：
#   -n N           行数（默认 100）
#   -f, --follow   实时跟踪（等价 journalctl -f）
#   -l, --level X  只显示包含该级别的行（INFO/WARN/ERROR/DEBUG）
#   -m, --msg 关键词  只显示整行含关键词的记录
#
# 示例：
#   logs.sh worker ERROR --msg task        # worker 最近的 ERROR 任务日志
#   logs.sh api -f                          # 实时跟踪 API
#   logs.sh api -m request_id=...           # 按请求 ID 筛选 API 日志
set -u

TARGET="api"
N=100
FOLLOW=0
LEVEL=""
MSG=""

while [ $# -gt 0 ]; do
  case "$1" in
    api|worker) TARGET="$1" ;;
    -f|--follow) FOLLOW=1 ;;
    -n) N="$2"; shift ;;
    -l|--level) LEVEL="$2"; shift ;;
    -m|--msg) MSG="$2"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（logs.sh -h 查看用法）" >&2; exit 2 ;;
  esac
  shift
done

UNIT="agent-drive-java.service"
if [ "$TARGET" = "worker" ]; then
  UNIT="agent-drive-java-worker.service"
fi
echo "# journalctl -u $UNIT -n $N"
filter_lines() {
  while IFS= read -r line; do
    if [ -n "$LEVEL" ] && [[ "$line" != *"$LEVEL"* ]]; then
      continue
    fi
    if [ -n "$MSG" ] && [[ "$line" != *"$MSG"* ]]; then
      continue
    fi
    printf '%s\n' "$line"
  done
}

if [ "$FOLLOW" -eq 1 ]; then
  journalctl -u "$UNIT" -o cat --no-pager -n "$N" -f | filter_lines
else
  journalctl -u "$UNIT" -o cat --no-pager -n "$N" | filter_lines
fi
