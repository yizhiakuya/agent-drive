# AGENTS.md — Agent Drive 项目维护手册

> 给本仓库的编码 Agent 与维护者：这是一份"项目级 skill"。修改代码前先读本文件；
> 改动完成后按「修改检查单」自查，保持文档一致。

## 0. 铁律：文档与 skill 实时同步

**任何代码/行为/流程变更，必须与代码同一次提交内更新：**

1. 相关文档：README、docs/architecture、docs/android、docs/security（按改动范围选择，原则是"文档与实现永不脱节"）
2. 本文件（AGENTS.md）：新增的坑位、变更的流程、新的约定——即时记录，而不是事后补
3. 历史快照类文档（quality-report/review-*）除外，它们按日期存档不随实现更新

违反此条 = 提交不合格。禁止"先改代码，文档下次再说"。

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
# 后端（依赖：pip install -e ".[dev]" —— pyproject 含 build-system，dev 组带 pytest/ruff/mypy）
cd backend && ruff check app/
python -m pytest tests/unit -q && python -m pytest tests/integration -q
# 遗留脚本式单测（pytest 不收集，CI 逐一直跑；bash 写法，Windows 用 foreach 等价）
for t in test_agent test_critic test_reliability test_retry test_compress test_write_tools test_memory test_bugfixes; do python tests/unit/$t.py || exit 1; done

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
5. **数据备份**：deploy/agent-drive-backup.{service,timer} 已在服务器安装启用（每日 04:00，/root/backups 轮转 7 份）；改备份策略改 deploy/ + scripts/backup.sh 后需同步到服务器

## 4. 关键约定与坑位（改动前必读）

- **Capacitor 插件注册**必须在 `super.onCreate()` 之前（否则 JS 拿不到原生实现，表现是静默退回默认地址）
- **BridgeActivity.onResume 是 final**：回前台心跳走前端 `visibilitychange` 事件 → 插件 `heartbeat()`
- **keystore.properties 必须无 BOM**：PowerShell 写 Properties 用 `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`；反斜杠双写、冒号转义
- **上传接口约定**：`path` 是查询参数；`md5`（秒传去重）与 `noclobber`（同名自动序号）是表单字段；multipart 文件 part 名为 `file`
- **MediaStore DATE_ADDED 是秒级**：`lastSyncAt` 只推进到「整秒全部成功」的秒；同秒有失败/未取完挂 `pendingSecond+pendingMaxId`（_ID 水位）续传。勿改回严格 `> 检查点` + 单张推进的旧实现——同秒失败张、单秒超 200 张会永久丢失（1.0.22 修复）
- **去重索引**：`system/upload-index.json`（md5→路径）；内容变更（改名/移动/删除/覆盖）自动失效（`storage.attach_index` 反向注入），lookup 时文件不在则自愈兜底
- **同步检查点**：整秒完成后才写 `lastSyncAt`（每轮一次）；失败不阻塞整批，Worker 按 lastError 退避重试
- **noclobber 原子独占**：`save_bytes(..., exclusive=True)` 用 os.link 原子不覆盖 + 端点重试改名；web 覆盖上传走原子 replace
- **resolve 拒绝符号链接**：组件级检查（业务从不产生 symlink），下载/预览/上传共用
- **设备令牌加密存储**：EncryptedSharedPreferences(AES256-GCM/SIV，MasterKey 在 Keystore) + `allowBackup=false`；旧明文自动迁移清空；勿改回明文
- **显式编码**：backend/app 全部 read_text/write_text/open 显式 `encoding="utf-8"`；用户可编辑记忆文件用 `preferences._read_tolerant`（utf-8→gbk→latin-1）容错读；`write_text` 固定 `newline="\n"`（Windows 不转 CRLF）
- **CI（GitHub Actions）**：backend = ruff + mypy(非阻断) + integration + unit(pytest) + 8 个遗留脚本直跑；frontend = vitest + build。新增测试一律 pytest 风格（自动被收集）
- **上传大小上限**：`max_upload_mb=300`（后端 413；公网闸门仍是 nginx 200m）——直连 8000 的滥用兜底
- **健康检查**：`/api/v1/health` 公开豁免（探活用，不泄露业务信息）
- **审计日志轮转**：1MB 轮转保留 5 份历史（logging.MAX_BACKUPS），勿改回只留 1 份
- **限速内存态**：仅适用单 worker 部署（现部署即单进程）；check_rate 已做过期 key 清理（>1000 触发全量清扫）
- **API Key 掩码只显前缀**（绝不回显尾部）；agent-config.json 写入 chmod 0600
- **版本号**：每次发版 `frontend/android/app/build.gradle` 的 versionCode/versionName 同步 +1
- **PowerShell 转义坑**：ssh 内嵌 curl 的 JSON 用 stdin 管道（`... | ssh megumin "curl --data-binary @-"`），不要 `\"` 转义
- **事件总线**：`agent-drive:refresh`（下拉刷新）、`agent-drive:files-changed`、`agent-drive:toast`、`agent-drive:unauthorized`（401 全局拦截）

## 5. 安全红线（勿破坏）

- 除 `/api/v1/health` 与 `auth/status|setup|login|logout|pair-exchange` 外，**全部 /api/v1 走 get_owner 鉴权**（Cookie / Bearer session|device / 媒体 ?token= 三通道）
- 密码 PBKDF2 只存哈希；设备令牌/配对码服务端只存 SHA-256；配对码一次性 5 分钟
- `system/auth.json` 删除 = 重置认证；8000 端口必须只绑 127.0.0.1（见 deploy/agent-drive.service）
- 密钥不进 git：*.keystore、keystore.properties、keystore 密码（仓库外 D:\ds\agent-drive-keystore\）
- 移除设备 = 吊销令牌；重扫配对 = 吊销旧令牌换新

## 6. 修改检查单

- [ ] 后端改动 → `ruff check app/` + 对应 unit/integration 测试；前端改动 → `npm run build` + `npm test`；原生改动 → APK 构建验证
- [ ] 全量门禁：backend unit + integration + vitest 全绿再提交
- [ ] 版本号 +1（涉及 App 行为/资源变更时）
- [ ] 同步文档 + 本 skill：README / docs/* 相应小节 / AGENTS.md（铁律 §0，同一次提交内完成）
- [ ] 提交并推送 → 服务器 git pull（+ 需要时 restart）→ 前端 tar 部署 / APK 发布

## 7. 环境事实

- 本机（Windows）：JDK 21（Temurin）、Android SDK `C:\Android\Sdk`（build-tools 35 + platform 35）、Gradle `C:\Android\gradle-8.14.3`
- 服务器：`ssh megumin`，仓库 `/root/projects/agent-drive`，服务 `agent-drive.service`（uvicorn 127.0.0.1:8000），nginx 13311 单入口
- keystore：服务器 `/root/agent-drive-android/agentdrive.keystore`（密码在本地 `D:\ds\agent-drive-keystore\password.txt`）