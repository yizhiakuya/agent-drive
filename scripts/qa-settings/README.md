# Settings 浏览器 QA

前端设置页交互改动后必跑（AGENTS.md 修改检查单）：

```bash
# 1) mock 模型服务（/models + /chat/completions）
python3 scripts/qa-settings/mock_models.py &

# 2) 临时后端（独立数据目录，不碰生产）
mkdir -p /tmp/ad-qa/system /tmp/ad-qa/data
cd backend && AGENT_DRIVE_APP_ENV=test AGENT_DRIVE_BACKEND_DIR=/tmp/ad-qa \
  AGENT_DRIVE_TASK_WORKER_ENABLED=false python3 -m uvicorn app.main:app --host 127.0.0.1 --port 8100 &

# 3) playwright（一次性：npm i playwright@1.57.0，系统 chromium）
cd /tmp/qa && node /root/projects/agent-drive/scripts/qa-settings/qa-settings.mjs

# 4) 截图 /tmp/qa/shot-settings-final.png（可经 vision 模型复查 CSS）
```

覆盖点：认证 → 设置页打开 → 协议卡片 3 个 → 获取可用模型出现下拉框 →
下拉选择回填输入框 → 空模型提交校验提示。
