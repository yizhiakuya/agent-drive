#!/usr/bin/env bash
# Agent Drive 日志查询助手（服务器上执行，不依赖 jq）。
#
# 用法：
#   logs.sh api [选项]        API 进程日志（journalctl -u agent-drive.service）
#   logs.sh worker [选项]     Worker 进程日志（journalctl -u agent-drive-worker.service）
#   logs.sh audit [选项]      审计日志尾部（backend/system/audit.log，可用 AGENT_DRIVE_AUDIT_LOG 覆盖）
#
# 选项：
#   -n N           行数（默认 100）
#   -f, --follow   实时跟踪（等价 journalctl -f）
#   -l, --level X  只显示该级别（INFO/WARNING/ERROR/DEBUG，JSON 日志有效）
#   -m, --msg 关键词  只显示 msg/整行含关键词的记录
#
# 示例：
#   logs.sh worker ERROR --msg task        # worker 最近的 ERROR 任务日志
#   logs.sh api -f                          # 实时跟踪 API
#   logs.sh audit -n 30                     # 审计日志最近 30 条
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
AUDIT=$(printenv AGENT_DRIVE_AUDIT_LOG || true)
if [ -z "$AUDIT" ]; then
  AUDIT="$REPO/backend/system/audit.log"
fi
TARGET="api"
N=100
FOLLOW=0
LEVEL=""
MSG=""

while [ $# -gt 0 ]; do
  case "$1" in
    api|worker|audit) TARGET="$1" ;;
    -f|--follow) FOLLOW=1 ;;
    -n) N="$2"; shift ;;
    -l|--level) LEVEL="$2"; shift ;;
    -m|--msg) MSG="$2"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（logs.sh -h 查看用法）" >&2; exit 2 ;;
  esac
  shift
done

# 内置 JSONL 过滤/美化器（python3，无 jq 依赖）：
#   argv1=level argv2=msg；读 stdin 每行尝试 json.loads，非 JSON 行在无过滤时原样透传
PY_FILTER=$(cat <<'PYEOF'
import json, sys, datetime
level = sys.argv[1] if len(sys.argv) > 1 else ""
msg = sys.argv[2] if len(sys.argv) > 2 else ""
for line in sys.stdin:
    line = line.rstrip("\n")
    try:
        rec = json.loads(line)
    except Exception:
        if not level and not msg:
            print(line)
        continue
    if level and rec.get("level") != level:
        continue
    if msg and msg not in json.dumps(rec, ensure_ascii=False):
        continue
    ts = rec.get("ts")
    when = datetime.datetime.fromtimestamp(ts).strftime("%m-%d %H:%M:%S") if isinstance(ts, (int, float)) else ""
    head = "%s %-7s %-24s | rid=%s %s" % (
        when, rec.get("level") or "AUDIT", rec.get("logger", "audit"),
        rec.get("rid", "-"), rec.get("msg") or rec.get("event") or "",
    )
    print(head)
    data = rec.get("data")
    if data is not None:
        print("    data=" + json.dumps(data, ensure_ascii=False))
    result = rec.get("result")
    if result:
        print("    result=" + str(result))
    exc = rec.get("exc")
    if exc:
        print("    " + exc.replace("\n", "\n    "))
PYEOF
)

if [ "$TARGET" = "audit" ]; then
  if [ ! -f "$AUDIT" ]; then
    echo "无审计日志: $AUDIT" >&2
    exit 1
  fi
  echo "# audit: $AUDIT（最近 $N 条）"
  tail -n "$N" "$AUDIT" | python3 -c "$PY_FILTER" "$LEVEL" "$MSG"
  exit 0
fi

UNIT="agent-drive.service"
if [ "$TARGET" = "worker" ]; then
  UNIT="agent-drive-worker.service"
fi
echo "# journalctl -u $UNIT -n $N"
if [ "$FOLLOW" -eq 1 ]; then
  journalctl -u "$UNIT" -o cat --no-pager -n "$N" -f | python3 -c "$PY_FILTER" "$LEVEL" "$MSG"
else
  journalctl -u "$UNIT" -o cat --no-pager -n "$N" | python3 -c "$PY_FILTER" "$LEVEL" "$MSG"
fi
