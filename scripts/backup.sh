#!/usr/bin/env bash
# Agent Drive 数据备份：data/ + system/ 打包 → /root/backups 轮转保留 7 天
# tasks.sqlite3 使用 SQLite 在线备份 API 生成一致快照，避免直接打包 WAL 三件套。
# 用法: bash scripts/backup.sh [目标目录]    默认 /root/backups
# 定时: systemd timer (agent-drive-backup.timer, 每天 04:00)
set -euo pipefail

BACKUP_DIR="${1:-/root/backups}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
NAME="agent-drive-$TS.tar.gz"
STAGING_PARENT="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
STAGING_DIR="$(mktemp -d "$STAGING_PARENT/agent-drive-backup.XXXXXX")"

cleanup() {
  case "$STAGING_DIR" in
    "$STAGING_PARENT"/agent-drive-backup.*) rm -rf -- "$STAGING_DIR" ;;
  esac
}
trap cleanup EXIT

mkdir -p "$BACKUP_DIR"
mkdir -p "$STAGING_DIR/backend"

# 先复制 system/，但排除活动中的 SQLite 数据库及 WAL/SHM。
tar cf - \
  --exclude="backend/system/tasks.sqlite3" \
  --exclude="backend/system/tasks.sqlite3-*" \
  -C "$PROJECT_DIR" backend/system | tar xf - -C "$STAGING_DIR"

TASK_DB="$PROJECT_DIR/backend/system/tasks.sqlite3"
TASK_SNAPSHOT="$STAGING_DIR/backend/system/tasks.sqlite3"
if [[ -f "$TASK_DB" ]]; then
  python3 - "$TASK_DB" "$TASK_SNAPSHOT" <<'PY'
import sqlite3
import sys

source = sqlite3.connect(sys.argv[1])
target = sqlite3.connect(sys.argv[2])
try:
    source.backup(target)
    target.execute("PRAGMA journal_mode=DELETE")
    result = target.execute("PRAGMA integrity_check").fetchone()
    if result is None or result[0] != "ok":
        raise RuntimeError(f"task database snapshot failed integrity check: {result}")
finally:
    target.close()
    source.close()
PY
  chmod 600 "$TASK_SNAPSHOT"
fi

# 打包数据工作区 + 已快照的系统配置（排除可重建索引）。
tar czf "$BACKUP_DIR/$NAME" \
  --exclude="backend/data/.index" \
  --exclude="__pycache__" \
  --exclude="*.pyc" \
  -C "$PROJECT_DIR" backend/data \
  -C "$STAGING_DIR" backend/system

# 轮转：保留最近 7 份
ls -1t "$BACKUP_DIR"/agent-drive-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f

SIZE=$(du -h "$BACKUP_DIR/$NAME" | cut -f1)
echo "✅ 备份完成: $BACKUP_DIR/$NAME ($SIZE)"
echo "当前保留: $(ls -1 "$BACKUP_DIR"/agent-drive-*.tar.gz 2>/dev/null | wc -l) 份"

# ===== 云同步钩子（配置后取消注释）=====
# rclone sync "$BACKUP_DIR" remote:bucket/agent-drive-backups
# 或 aws s3 sync "$BACKUP_DIR" s3://your-bucket/agent-drive-backups
