# AGENTS.md — Agent Drive 项目维护手册

> 给本仓库的编码 Agent 与维护者：这是一份"项目级 skill"。修改代码前先读本文件；
> 改动完成后按「修改检查单」自查，保持文档一致。

## 1. 项目定位与文档地图

Agent-first 私人网盘：FastAPI 后端 + Next.js 16 前端（静态导出）+ Capacitor 7 安卓原生壳。

| 文档 | 内容 |
|------|------|
| `README.md` | 总览/快速开始/项目结构 |
| `docs/architecture.md` | 分层架构、模块职责、扩展点 |
| `docs/security.md` | 认证模型（密码/会话/设备令牌/扫码配对）、暴露面、运维 |
| `docs/android.md` | 安卓原生壳方案、构建发布、同步机制 |
| `docs/frontend-architecture.md` | 历史存档 + 现状速览（勿当现行文档） |
| `docs/agent-definition.md` | Agent 设计规范 |
| `docs/quality-report*.md` / `review-*.md` | 历史快照，只读 |

## 2. 常用命令

```bash
# 后端测试（先装依赖：pip install fastapi pydantic pydantic-settings httpx openai anthropic tiktoken uvicorn python-multipart pytest pytest-asyncio pymupdf pytesseract numpy）
cd backend && python -m pytest tests/unit -q && python -m pytest tests/integration -q
# 注意：pip install -e 不可用（项目无打包配置），直接装依赖

# 前端
cd frontend && npm run build    # TS 类型检查 + 静态导出 out/
npm test                        # vitest

# 安卓 APK（本机环境：JAVA_HOME=Temurin 21，ANDROID_HOME=C:\Android\Sdk，Gradle 8.14.3）
cd frontend/android && gradlew.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（keystore.properties 就位则自动签名）
```

## 3. 部署与发布流程

1. **推送**：`git push origin main`（remote = github.com/yizhiakuya/agent-drive）
2. **服务器同步**：`ssh megumin "cd /root/projects/agent-drive && git pull"`；
   后端有改动再 `systemctl restart agent-drive.service`（纯前端改动无需重启）
3. **前端产物**：`tar -cf out.tar -C out .` → scp → 服务器 out.new 解包 → 原子替换 out/。
   ⚠️ 不要用 PowerShell 通配符 `out\*` scp——会漏掉点开头的目录（.well-known/assetlinks.json）；tar 全量打包是安全做法
4. **发布 APK**：拷贝 app-release.apk → `out/app/agent-drive.apk` → 随前端一起部署。
   下载地址恒定：https://home.rainaki.top:13311/app/agent-drive.apk；
   ⚠️ APK 不要放进 public/（会被 cap sync 嵌套打包进 App 自身资源）

## 4. 关键约定与坑位（改动前必读）

- **Capacitor 插件注册**必须在 `super.onCreate()` 之前（否则 JS 拿不到原生实现，表现是静默退回默认地址）
- **BridgeActivity.onResume 是 final**：回前台心跳走前端 `visibilitychange` 事件 → 插件 `heartbeat()`
- **keystore.properties 必须无 BOM**：PowerShell 写 Properties 用 `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`；反斜杠双写、冒号转义
- **上传接口约定**：`path` 是查询参数；`md5`（秒传去重）与 `noclobber`（同名自动序号）是表单字段；multipart 文件 part 名为 `file`
- **MediaStore DATE_ADDED 是秒级**：轮次截断必须落在"完整的一秒"边界，否则同秒照片漏传（SyncEngine 现有实现勿简化）
- **去重索引**：`system/upload-index.json`（md5→路径）；文件删除后索引自愈；覆盖上传会更新索引
- **同步检查点**：逐张成功后立即写 `lastSyncAt`；失败不阻塞整批，Worker 按 lastError 退避重试
- **版本号**：每次发版 `frontend/android/app/build.gradle` 的 versionCode/versionName 同步 +1
- **PowerShell 转义坑**：ssh 内嵌 curl 的 JSON 用 stdin 管道（`... | ssh megumin "curl --data-binary @-"`），不要 `\"` 转义
- **事件总线**：`agent-drive:refresh`（下拉刷新）、`agent-drive:files-changed`、`agent-drive:toast`、`agent-drive:unauthorized`（401 全局拦截）

## 5. 安全红线（勿破坏）

- 除 `auth/status|setup|login|logout|pair-exchange` 外，**全部 /api/v1 走 get_owner 鉴权**（Cookie / Bearer session|device / 媒体 ?token= 三通道）
- 密码 PBKDF2 只存哈希；设备令牌/配对码服务端只存 SHA-256；配对码一次性 5 分钟
- `system/auth.json` 删除 = 重置认证；8000 端口必须只绑 127.0.0.1（见 deploy/agent-drive.service）
- 密钥不进 git：*.keystore、keystore.properties、keystore 密码（仓库外 D:\ds\agent-drive-keystore\）
- 移除设备 = 吊销令牌；重扫配对 = 吊销旧令牌换新

## 6. 修改检查单

- [ ] 后端改动 → 对应 unit/integration 测试；前端改动 → `npm run build` + `npm test`；原生改动 → APK 构建验证
- [ ] 全量门禁：backend unit + integration + vitest 全绿再提交
- [ ] 版本号 +1（涉及 App 行为/资源变更时）
- [ ] 同步文档：README / docs/architecture / docs/android / docs/security 相应小节
- [ ] 提交并推送 → 服务器 git pull（+ 需要时 restart）→ 前端 tar 部署 / APK 发布

## 7. 环境事实

- 本机（Windows）：JDK 21（Temurin）、Android SDK `C:\Android\Sdk`（build-tools 35 + platform 35）、Gradle `C:\Android\gradle-8.14.3`
- 服务器：`ssh megumin`，仓库 `/root/projects/agent-drive`，服务 `agent-drive.service`（uvicorn 127.0.0.1:8000），nginx 13311 单入口
- keystore：服务器 `/root/agent-drive-android/agentdrive.keystore`（密码在本地 `D:\ds\agent-drive-keystore\password.txt`）