#!/usr/bin/env bash
# Agent Drive 数据备份：data/ + system/ 打包 → /root/backups 轮转保留 7 天
# 用法: bash scripts/backup.sh [目标目录]    默认 /root/backups
# 定时: systemd timer (agent-drive-backup.timer, 每天 04:00)
set -euo pipefail

BACKUP_DIR="${1:-/root/backups}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
NAME="agent-drive-$TS.tar.gz"

mkdir -p "$BACKUP_DIR"
cd "$PROJECT_DIR"

# 打包：数据工作区 + 系统配置（排除可重建的索引/缓存）
tar czf "$BACKUP_DIR/$NAME" \
  --exclude="backend/data/.index" \
  --exclude="__pycache__" \
  --exclude="*.pyc" \
  --exclude="frontend/node_modules" \
  --exclude="frontend/.next" \
  --exclude="frontend/out" \
  backend/data backend/system 2>/dev/null || true

# 轮转：保留最近 7 份
ls -1t "$BACKUP_DIR"/agent-drive-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f

SIZE=$(du -h "$BACKUP_DIR/$NAME" | cut -f1)
echo "✅ 备份完成: $BACKUP_DIR/$NAME ($SIZE)"
echo "当前保留: $(ls -1 "$BACKUP_DIR"/agent-drive-*.tar.gz 2>/dev/null | wc -l) 份"

# ===== 云同步钩子（配置后取消注释）=====
# rclone sync "$BACKUP_DIR" remote:bucket/agent-drive-backups
# 或 aws s3 sync "$BACKUP_DIR" s3://your-bucket/agent-drive-backups
