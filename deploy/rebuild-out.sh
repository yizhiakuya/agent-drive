#!/usr/bin/env bash
# 服务器前端产物原地重建（等价于 §3 tar 部署流的服务器侧简化版）。
# 用法：bash deploy/rebuild-out.sh
# 日常功能迭代不构建 APK：out/app/agent-drive.apk 仅从旧产物恢复（保持恒定下载地址）。
# APK 构建（assembleRelease）只在测试 App 业务或发版时做。
set -euo pipefail
cd "$(dirname "$0")/../frontend"

# 1. 快照旧 out 到 rollbacks（含手工拷入的 app/agent-drive.apk）
SNAP="/root/agent-drive-rollbacks/out-$(date +%Y%m%d%H%M%S)"
if [[ -d out ]]; then
  mkdir -p /root/agent-drive-rollbacks
  cp -a out "$SNAP"
  echo "snapshot: $SNAP"
fi

# 2. 重建（next build 会清空 out/）
npm run build

# 3. 恢复 APK（构建产物不含 app/）
APK_SRC=""
for d in /root/agent-drive-rollbacks/out-*/app/agent-drive.apk; do
  [[ -f "$d" ]] && APK_SRC="$d" && break
done
if [[ -n "$APK_SRC" ]]; then
  mkdir -p out/app
  cp -a "$APK_SRC" out/app/agent-drive.apk
  echo "restored out/app/agent-drive.apk <- $APK_SRC"
else
  echo "WARN: 找不到可恢复的 APK（out/app/agent-drive.apk 缺失，下载地址将 404，直到下次发版拷贝）"
fi

# 4. 权限（build 产物默认 0600）
chmod -R a+rX out
echo "OK: out/ 重建完成"
