# 🦋 Agent Drive — 以 AI 为中心的私人网盘

> 不是"网盘 + AI 功能"，而是 **Agent-first**：进入系统先配置好 Agent，之后一切操作（文件管理、系统设置、自动化规则）都交给 Agent 自己完成。

## ✨ 核心特性

- **Agent 自管理**：LLM 配置存在网盘里（`backend/system/agent-config.json`），Agent 可以通过对话修改自己的配置
- **三协议 LLM 支持**：OpenAI 兼容 (chat/completions) / OpenAI Responses / Anthropic (Claude)
- **Agentic Loop**：意图理解 → 规划 → 工具调用 → 观察 → 回复（含工具轨迹可视化）
- **流式输出**：SSE 逐块渲染，Agent 边思考边输出
- **技能包**：可插拔 Skills（周报生成器/文件整理器），read_skill 按需加载
- **意图路由**：闲聊走轻量路径（更快更省），任务走完整 Loop
- **重试与退避**：LLM/工具瞬态错误指数退避重试，永久错误立即失败
- **工具分级安全**：查询自动 / 低风险写自动 / 高风险写需确认
- **审计日志**：Agent 每一步操作都可回放
- **记忆系统**：用户偏好 + 多会话 + 跨会话摘要
- **全站认证**：网页密码登录（PBKDF2）+ App 扫码即授权（一次性配对码换设备令牌）+ 登录限速（见 docs/security.md）
- **设备管理**：web 设置页设备列表——型号/活跃时间/相册同步状态，移除即吊销令牌
- **安卓原生壳**：Capacitor（frontend/android）打包 web UI + 原生能力：扫码连接、相册自动同步（WorkManager 后台/去重秒传/断点续传/进度可视）、通知、全局下拉刷新

## 🚀 快速开始

### 1. 启动后端

```bash
cd backend
pip install -r requirements.txt
python -m uvicorn app.main:app --port 8000
```

### 2. 启动前端（Next.js 16）

```bash
cd frontend
npm install
# 生产: 构建静态导出(backend 单服务托管 out/)
npm run build
# 开发: 直连后端
NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1 npm run dev   # :3000
```

### 3. 首次使用

1. 打开 http://localhost:8000（backend 托管前端）→ **设置主人密码**（第一个设置者即主人）
2. 网页端配置 AI：选择协议类型 → 填 Base URL / API Key / 模型 → **测试连接** → **完成配置**
3. 手机装 App（设置 → 连接手机 App → 下载 APK）→ **扫码即授权**，免输入密码

App 与 PWA 均为客户端：AI 配置只在网页端进行（App 内不提供 AI 设置界面）。

之后所有操作都通过对话完成：
- "看看网盘里有什么文件"
- "帮我建一个叫 `项目` 的文件夹"
- "把 LLM 换成 DeepSeek"（Agent 自己改配置）
- "以后下载的文件自动归档"（生成规则）

## 🏗️ 项目结构（分层架构，见 docs/architecture.md）

```
agent-drive/
├── docs/                        # 架构/Agent定义/前端架构/安卓端/质量报告/审查记录
├── deploy/                      # nginx 部署配置（HTTPS 13311）+ systemd 单元（8000 仅回环）
├── backend/
│   ├── app/
│   │   ├── main.py              # 入口：create_app() + 托管前端静态(SPA)
│   │   ├── core/                # config/logging/errors/container/retry
│   │   ├── auth/                # 认证：密码/会话令牌/设备令牌/配对码（store.py）
│   │   ├── devices/             # 设备注册表（devices.json）
│   │   ├── api/v1/              # auth/chat/config/files/sessions/automation/devices
│   │   ├── agent/               # loop/prompt/router/skills/onboarding/
│   │   │   │                    #   scheduler(自动化)/confirm/context/
│   │   │   ├── tools/           # files/system/analytics/plan/memory
│   │   │   └── memory/          # preferences/sessions
│   │   ├── llm/                 # base/manager/embeddings/providers(3协议)
│   │   ├── storage/             # base/local（回收站 .trash）+ upload_index（秒传去重）
│   │   └── ingest/              # pipeline（PDF/OCR 摄入+向量索引）
│   ├── tests/                   # unit 12 套 + integration pytest
│   ├── scripts/                 # benchmark_real.py / mock_llm.py / backup.sh
│   └── pyproject.toml           # 依赖唯一真相源
├── frontend/                    # Next.js 16 + Tailwind + TS + zustand
│   └── src/
│       ├── app/                 # layout/page + globals.css(主题)
│       ├── components/          # chat/ files/ sessions/ settings/ onboarding/ auth(登录·扫码)· PullToRefresh
│       └── lib/                 # store/events/format + api(6模块) + native(插件桥)
├── frontend/android/           # Capacitor 原生壳（扫码连接 + 相册自动同步 + 后台任务）
├── Makefile                     # install/dev/test/bench/build
└── docker-compose.yml           # db(pgvector)+redis+backend+frontend
```

## 🧪 开发命令

```bash
make install        # 安装依赖
make dev-backend    # 后端 :8000 (热重载)
make dev-frontend   # 前端 next dev :3333
make test           # 全部测试（pytest + 脚本套件）
make bench          # 真实 LLM 可靠性基准
```

## 📐 Agent 定义规范（v1.0）

详见 [`docs/agent-definition.md`](docs/agent-definition.md)：
好 Agent = 大脑 + 手脚 + 记忆 + 主见 + 护栏；可靠性四维度（一致性/鲁棒性/可预测性/安全性）；
六大工程原则（系统提示=API文档 / 拆分上下文 / 精心工具 / Actor-Critic 反馈循环 / LLM错误分析 / 先调试系统）。

## 🧪 测试与基准

```bash
cd backend
python3 test_agent.py              # Agent 核心（工具循环/审计/安全）
python3 test_critic.py             # Critic 反馈循环（幂等/验证/结构化错误）
python3 test_reliability.py        # 可靠性四维度（一致性/鲁棒性/可预测性/安全性）
python3 benchmark_real.py --repeat 3   # 真实 LLM 可靠性基准回归 → benchmark_report.md
python3 mock_llm.py &              # Mock LLM (端口 9999)
# 然后通过 API 或前端完成 Onboarding + 对话测试
```

## 🧠 记忆与多会话（v4）

- **多会话**：左侧会话列表，可新建/切换/删除；消息持久化到 `system/sessions/*.jsonl`
- **跨会话记忆**：对话超过 12 条自动生成摘要；系统提示注入最近 5 个会话摘要，新会话 Agent 记得旧事
- **错误分析（meta-agent）**：`analyze_failures` 工具读取审计日志 → 规则分类（8 类错误）→ LLM 深度诊断根因
- **日期感知**：系统提示注入当前日期，正确理解"明年/上周"等相对时间

## 🛡️ 安全护栏（v3）

- **red 级操作确认**：删除等高风险操作返回 `pending_confirmation`，前端弹确认框，用户确认后才执行
- **Critic 验证**：写操作执行后由程序验证结果（创建后存在？重命名后源消失？）
- **结构化错误**：工具失败返回 `{ok:false, error}`，Agent 可读懂并优雅降级
- **上下文管理**：历史按 token 预算截断（非按条数），工具输出限长防膨胀
- **步数上限**：单轮最多 10 步，防失控循环

## 🗺️ 路线图

- **M1** ✅：三协议 LLM + Onboarding + Agent 对话 + 基础文件管理
- **M2** ✅：摄入管线（PDF/OCR）+ 语义搜索（Jina 云向量）+ 全文检索 + 文档问答
- **M3** ✅：规则自动执行（每天 03:30）+ 主动汇报 + 回收站
- **M4 候选**：音视频转写 / 文件关系图谱 / 知识图谱问答 / 推送通知
- **安全与多端** ✅：全站认证（密码/扫码配对/设备令牌）+ 安卓原生壳 + 相册自动同步（去重/断点/进度）+ 设备列表 + APK 下载
- **安卓客户端** ✅：Capacitor 原生壳（frontend/android）——web UI 原样打包 + 原生插件桥：扫码连接服务器、相册自动同步（WorkManager 后台任务）、通知（见 docs/android.md）

## ⚠️ 安全说明

- **全站鉴权**：首次访问设主人密码（PBKDF2），web 走 HttpOnly Cookie，App 走设备令牌（可单设备吊销）；登录限速防爆破。设计见 docs/security.md
- 路径穿越防护已内置
- 工具按 green/yellow/red 分级，红色操作需 HMAC 签名确认（nonce 防重放）
- 删除进回收站（30 天可恢复），彻底删除需 red 确认
- 文件内容注入防护（多模式正则 + 内容一律视为数据）
- 审计日志轮转 + API Key 掩码 + 敏感信息不入库
