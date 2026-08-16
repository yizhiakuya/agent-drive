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
| `docs/quality-analysis.md` | 工程质量分析 Agent 协议（只读分析→先汇报→确认后修改） |

## 2. 常用命令

```bash
# 后端（依赖：pip install -e ".[dev]" —— pyproject 含 build-system，dev 组带 pytest/ruff/mypy）
cd backend && ruff check app/
python -m pytest tests/unit -q && python -m pytest tests/integration -q
# 遗留脚本式单测（CI 逐一直跑；其中 test_retry/test_bugfixes 兼具 pytest 可收集写法、属双轨；bash 写法，Windows 用 foreach 等价）
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
   后端/任务代码有改动重启 `agent-drive.service agent-drive-worker.service`（纯前端改动无需重启）。首次安装或 unit 变更还要复制 `deploy/agent-drive*.service` 到 `/etc/systemd/system/` 后 `systemctl daemon-reload`；`/etc/agent-drive/proxy.env` 从 `deploy/proxy.env.example` 创建、chmod 0600，并确认只含 HTTP(S) 代理
3. **前端产物**：`tar -cf out.tar -C out .` → scp → 服务器 out.new 解包 → 原子替换 out/。
   ⚠️ 不要用 PowerShell 通配符 `out\*` scp——会漏掉点开头的目录（.well-known/assetlinks.json）；tar 全量打包是安全做法
4. **发布 APK**：拷贝 app-release.apk → `out/app/agent-drive.apk` → 随前端一起部署。
   下载地址恒定：https://home.rainaki.top:13311/app/agent-drive.apk；
   ⚠️ APK 不要放进 public/（会被 cap sync 嵌套打包进 App 自身资源）；
   ⚠️ 在服务器原地 `npm run build` 会清空 out/ 并丢掉手工拷入的 out/app/——重建后必须从 `/root/agent-drive-rollbacks/` 恢复 agent-drive.apk，并 `chmod -R a+rX out/`（build 产物默认 0600）
5. **数据备份**：deploy/agent-drive-backup.{service,timer} 已在服务器安装启用（每日 04:00，/root/backups 轮转 7 份）；`tasks.sqlite3` 必须经 Python sqlite3 backup API 生成一致快照，禁止直接打包活动中的 WAL 三件套

## 4. 关键约定与坑位（改动前必读）

- **Capacitor 插件注册**必须在 `super.onCreate()` 之前（否则 JS 拿不到原生实现，表现是静默退回默认地址）
- **BridgeActivity 生命周期约束**：`onResume` 是 final，回前台心跳走前端 `visibilitychange` → 插件 `heartbeat()`；`onDestroy` 覆写必须保持 public。MediaStore observer 用 Activity 字段持有、只注册一次，并在销毁时注销且清 debounce callback
- **keystore.properties 必须无 BOM**：PowerShell 写 Properties 用 `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`；反斜杠双写、冒号转义
- **上传接口约定**：`path` 是查询参数；`md5`（服务端必须复算验证）与 `noclobber`（同名自动序号）是表单字段；multipart 文件 part 名为 `file`。请求体按块写 0600 temp，禁止重新读回内存拼接
- **免传预检**：Android 先 `GET /files/dedupe?md5=...`；只允许 `verified=true` 且文件 revision 仍匹配的服务端实算条目命中。预检 GET 无副作用；真正上传始终复算 MD5，勿重新信任客户端 hash。发布成功后的去重索引登记是优化项：失败只记 warning、不得把已上传文件伪报为失败（否则客户端重试会经 noclobber 落成重复照片）
- **MediaStore DATE_ADDED 是秒级**：`lastSyncAt` 只推进到「整秒全部成功」的秒；同秒有失败/未取完挂 `pendingSecond+pendingMaxId`（_ID 连续水位）续传。首次失败后水位冻结，之后成功项靠秒传重试；第 201 行仅作截断哨兵、不上传；完整检查点一次 commit。勿改回严格 `> 检查点` + 单张推进
- **去重索引**：`system/upload-index.json`（md5→路径+revision）；sidecar `0600` flock 事务每次 reload→modify→fsync→replace，锁序固定 storage→index。内容变更（含目录树改名/移动/删除/覆盖）递归失效；verified lookup 要求 revision 且会自愈清理。勿在持有 index 锁时反向抢 storage 锁
- **持久任务库**：`system/tasks.sqlite3`（SQLite WAL + 0600）是唯一任务状态源；生产 API 必须 `AGENT_DRIVE_TASK_WORKER_ENABLED=false`，由独立 `agent-drive-worker.service` 执行；开发环境才允许 API 内嵌 Worker
- **任务状态机**：只经 `JobStore` 做 queued/running/retry_wait/cancelling/terminal 迁移；领取使用租约+心跳，租约过期必须遵守 max_attempts；停机 release 遇到 cancel_requested 必须落为 cancelled，勿留下不可领取的 queued 任务
- **Python 3.10 Worker 兼容**：`asyncio.wait_for` 的空闲超时必须捕获 `asyncio.TimeoutError`（同时兼容内置 `TimeoutError`），否则生产 Worker 每轮空闲轮询都会误报异常
- **任务去重与进度**：活跃 dedupe_key 由 SQLite 部分唯一索引保证；相同进度不重复写事件；无游标 SSE 从事件尾部订阅，勿回放全库。终态历史每日保留至少最近 2000 条并清理 30 天前旧记录；子任务仍保留时不得先删父任务
- **索引任务链**：文件写/移动/删除先同步失效旧全文与向量，再由 `storage.attach_change_listener` 入队 `index.file`；全量重建是 `index.rebuild` 父任务 + index lane 子任务，禁止在上传请求或 Agent 工具内串行跑 OCR/embedding
- **任务中心口径**：列表/状态计数只算顶层任务，子任务汇总进父任务；`vector_stats` 是全盘扫描，任务总览必须保留 15 秒缓存，文件变更或 embedding 指纹变化时在本进程失效
- **向量有效性**：全文元数据记录 source_revision + extractor_version；向量元数据记录 source_revision + embedding fingerprint + chunk_version。文档用 `retrieval.passage`、查询用 `retrieval.query`；`.npy` 与 metadata 分别原子发布，读取必须同时校验，旧格式/模型切换自动视为失效
- **同步检查点**：整秒完成后才推进 `lastSyncAt`；失败不阻塞整批但不得让更晚秒越过最早失败秒，Worker 按 lastError 退避重试；MediaStore query/Cursor/字段读取和 checkpoint commit 异常也必须保留当前/已有 pending，不能把部分成功当成已提交。每行先读 DATE_ADDED 并以此真实秒 `begin`，再读 _ID 等其余字段——字段异常必须落在该真实秒的 pending 上，不得把已完成的上一组误标失败。`lastSyncAt/pending*` 必须同一次加密 prefs commit。周期/快速/手动任务可能使用不同 unique work 名，`SyncEngine.sync()` 必须保持进程内串行
- **noclobber 原子独占**：`save_bytes(..., exclusive=True)` 用 os.link 原子不覆盖 + 端点重试改名；web 覆盖上传走原子 replace。发布父目录使用 dirfd/O_NOFOLLOW；link/replace 是可见性提交点，之后的目录 fsync/临时链接清理失败不得把已发布文件伪报为失败。覆盖移动拒绝文件↔目录混型（IsADirectory/NotADirectory），非空目录整体覆盖报 FileExists（409）。非 Linux 的 no-replace fallback 仅在 mutation lock 内安全（对不遵守 flock 的外部写入者仍有窗口；生产为 Linux renameat2）。勿退回“先 exists 再普通写”的 TOCTOU
- **设备注册表写入**：`system/devices.json` 与 auth/upload-index 同原子规范——0600 临时文件 + flush/fsync + 原子 replace，失败清理 .tmp；勿退回无 fsync 的 write_text+replace
- **原子文本、目录复制与回收站**：write/append 都是 temp+fsync+replace；append 的读-改-写由 RLock/flock 包围。目录复制先在隐藏 staging 完整构建，Linux 用 `renameat2` no-replace/exchange 一次发布；fallback 在挪旧目标前 durable 写 `.copy.*.txn.json`，启动恢复未提交旧目录或清理已提交 backup。无 marker 的 `.copy-old.*` 无法证明可删，必须保守保留。回收站每次删除有唯一 `trash_id`，恢复传 trash_id（兼容旧 path），孤儿 metadata 不展示并可清理
- **resolve 拒绝符号链接与内部路径**：组件级检查（业务从不产生 symlink），下载/预览/上传共用；`.index/.trash/.storage.lock` 与 `.upload/.copy` staging 的公共访问必须拒绝，不只是列表隐藏。内部流程使用显式 `allow_internal`，列表/摄入/任务变更必须跳过
- **设备令牌加密存储**：现行数据只写独立 `agent_drive_secure` EncryptedSharedPreferences（AES256-GCM/SIV，MasterKey 在 Keystore）+ `allowBackup=false`。升级识别旧 `agent_drive` 明文和 1.0.27 同文件密文：同键以 1.0.27 持续写入的密文为现行值，明文只作更早来源/清理残留；独立新密文若与 legacy 现行值冲突则保留双方并失败关闭。新密文 commit 成功或逐键确认相等后才清理旧业务数据，AndroidX keyset 永不 clear，清理失败下次幂等重试；初始化/迁移/commit 失败必须弹窗或 reject/Log+retry，绝不能吞异常或降级明文
- **显式编码**：backend/app 全部 read_text/write_text/open 显式 `encoding="utf-8"`；用户可编辑记忆文件用 `preferences._read_tolerant`（utf-8→gbk→latin-1）容错读；`write_text` 固定 `newline="\n"`（Windows 不转 CRLF）
- **CI（GitHub Actions）**：backend = ruff + mypy（阻断，保持 0 错误）+ integration + unit(pytest) + 8 个遗留脚本直跑；frontend = eslint + vitest + build；android = gradle testDebugUnitTest（JVM 单测）。CI Python 与生产对齐 3.10（勿升 3.11：3.10 特有的 asyncio.TimeoutError 兼容问题 CI 才测得出来）；gradlew 可执行位已入库，本地可直接 ./gradlew。新增测试一律 pytest 风格（自动被收集）；ESLint 配置忽略 android/ 构建产物，不得恢复全目录裸扫
- **Vitest ESM 配置**：使用 `vitest.config.mts` + `import.meta.url` 解析别名；勿改回含 ESM 语法的 `.ts`/CommonJS 加载方式（未来 Vite native config loader 不支持）
- **上传大小上限**：`max_upload_mb=300`（后端 413；公网闸门仍是 nginx 200m）——直连 8000 的滥用兜底
- **健康检查**：`/api/v1/health` 公开豁免（探活用，不泄露业务信息）
- **审计日志轮转**：1MB 轮转保留 5 份历史（logging.MAX_BACKUPS），勿改回只留 1 份
- **限速内存态**：仅适用单 API 进程部署（独立任务 Worker 不承载 HTTP）；check_rate 已做过期 key 清理（>1000 触发全量清扫）
- **API Key 掩码只显前缀**（绝不回显尾部）；agent-config.json 写入 chmod 0600
- **认证配置失败关闭**：已有 `system/auth.json` 损坏、编码错误、非 JSON 对象、嵌套设备/配对/撤销字段畸形或不可读时必须抛 `AuthStoreLoadError` 拒绝启动并保留原文件；勿静默当作未初始化。正常保存用 0600 安全临时文件 + fsync + 原子 replace；同一路径的 AuthStore 必须共享进程锁，POSIX 再叠加 sidecar flock，每次事务 reload-before-mutate，勿退回实例私有锁
- **同步断网中止**：SyncEngine 连接失败/401/403/5xx 抛 AbortBatchException 整批中止（勿改回 200 张串行超时）；永久 4xx（400/413/415/416/422）按“跳过”推进连续水位、不设 lastError 不触发重试，其余 4xx 视为可能瞬时并冻结水位下轮重试；中止时当前秒组同样挂 pending 续传。响应实体必须先 drain 再 disconnect，保持连接可复用
- **同步配置与观察者**：PhotoSync.configure 的 enabled/wifiOnly/interval/folder 必须一次加密 prefs commit；多个调用放入专用单线程执行器，从写入到 WorkManager Operation 入库结果全程串行，绝不能在桥接/UI 线程 `Future.get()`。调度失败保留已提交的期望状态、明确 reject，由下次启动 `ensureScheduled` 幂等收敛；禁止伪回滚未知 WorkManager 副作用。挂起的权限回调在 Activity 销毁或新请求到来时必须 reject/替换，不能留旧 PluginCall 解析。ContentObserver 保持 1 秒防抖，字段持有且 Activity 销毁时注销/清 callback，避免重建泄漏和重复快速同步
- **通知权限非致命**：相册权限判定只看 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE（通知被拒不算失败）
- **错误语义化**：files.py 用 `_friendly()` 映射 404/409/403，裸 OSError（磁盘/IO 故障）映射 500 重试（透传 HTTPException），勿改回 `except Exception → 400`；Android 把永久 4xx 当可跳过照片，服务端瞬时故障绝不能落进 4xx
- **/api 404 保持 JSON**：main.py SPA fallback 对 `api/` 前缀返回 JSON 404，前端不再拿到 HTML
- **SPA 静态文件边界**：main.py `_resolve_dist_path` 拒绝 `..` 路径段，并要求 resolve 后仍在 `frontend/out` 内；越界返回 JSON 404，勿改回 `_DIST / full_path` 直接读取
- **extract 不进请求路径**：上传只持久化并入队；`index.file` handler 用 `asyncio.to_thread(ingest.extract)` 跑 PDF/OCR，勿改回上传端点同步摄入
- **生产代理边界**：API/Worker 都读 `/etc/agent-drive/proxy.env` 的 HTTP(S) proxy；systemd 用 `UnsetEnvironment=ALL_PROXY all_proxy` 阻止 SOCKS 继承。Jina 在服务器直连会超时，勿移除
- **systemd unit 语法**：`StartLimitIntervalSec/StartLimitBurst` 放 `[Unit]`；systemd 不支持指令值后的行尾注释（会把注释当值并忽略安全项）。unit 变更部署前必须跑 `systemd-analyze verify`，Agent Drive unit 不得出现 warning
- **前端 GET 去重与身份隔离**：cache key 必须含 API base + credential generation + cache generation + path；凭据代次与缓存代次分离，旧 in-flight 不得写入新身份/新缓存或派发迟到 401。每个非 GET 在请求开始和结束（成功、HTTP 错误、网络异常、Abort）都独立失效，交错写不能跳过结束清理
- **原生登出语义**：先清 EncryptedSharedPreferences 再清进程令牌；安全存储清理失败必须停留并报错。离线/5xx 本地退出后只提示“服务端吊销状态未知”，401/403 视为凭据已不可用，勿误报旧令牌仍有效
- **chat SSE 解析**：chatStream 必须处理 LF/CRLF/CR、换行跨 chunk、UTF-8 码点跨 chunk、多行 data 和无终止空行的尾事件；401 与普通 API/上传共用 `EV.unauthorized`，错误保留后端 detail
- **chat 流式节流**：ChatPanel 每 80ms 批量刷一帧（streamTimerRef），流结束冲刷最后一帧；勿改回逐 token setState
- **前端复用单元**：文件预览六分支统一用 `FilePreview`（variant=panel/page），时间格式化统一用 `lib/format.fmtTime`（勿再内联 toLocaleString），原生重扫统一用 `lib/native/useRescan`（勿各自维护 busy/msg）；新增同类重复先查这些单元
- **移动端预览面板**：FilePage 预览/回收站移动端为全屏覆盖层（`fixed inset-0 z-40 lg:static`），勿改回 `hidden lg:flex`
- **移动端文件工具栏**：`<640px` 保持 3×2、44px 高触控网格；`<360px` 顶栏只视觉隐藏 Agent Drive 文字（保留无障碍文本），320/407px 必须无横向滚动
- **Next viewport**：Next.js 16 在 `layout.tsx` 用独立 `export const viewport: Viewport`；勿放回 `metadata.viewport`（构建会警告并可能被忽略）
- **版本号**：每次发版 `frontend/android/app/build.gradle` 的 versionCode/versionName 同步 +1
- **PowerShell 转义坑**：ssh 内嵌 curl 的 JSON 用 stdin 管道（`... | ssh megumin "curl --data-binary @-"`），不要 `\"` 转义
- **工程质量分析 agent**：任何质量/可维护性分析按 `docs/quality-analysis.md` 协议执行——架构与技术栈属基线（记录在快照，不频繁重审），每次只做增量检查（风格一致性/耦合重复/门禁/测试缺口/复杂度热点/死代码/文档同步）；分析阶段只读，完成后先出报告等用户确认，只有用户明示“直接修”才动手。禁止“边分析边改”
- **事件总线**：`agent-drive:refresh`（下拉刷新）、`agent-drive:files-changed`、`agent-drive:tasks-changed`、`agent-drive:toast`、`agent-drive:unauthorized`（401 全局拦截）

## 5. 安全红线（勿破坏）

- 除 `/api/v1/health` 与 `auth/status|setup|login|logout|pair-exchange` 外，**全部 /api/v1 走 get_owner 鉴权**；Cookie/Bearer 可全站，设备 `?token=` 只允许 raw/download GET，禁止扩到列表/状态/写接口
- 密码 PBKDF2 只存哈希；设备令牌/配对码服务端只存 SHA-256；配对码一次性 5 分钟；logout 必须服务端吊销所携 session/device credential，不能只删 Cookie
- `system/auth.json` 删除 = 显式重置认证；文件存在但损坏必须失败关闭，禁止隐式重置；8000 端口必须只绑 127.0.0.1（见 deploy/agent-drive.service）
- 密钥不进 git：*.keystore、keystore.properties、keystore 密码（仓库外 D:\ds\agent-drive-keystore\）
- 移除设备 = 吊销令牌；重扫配对 = 吊销旧令牌换新

## 6. 修改检查单

- [ ] 后端改动 → `ruff check app/` + 对应 unit/integration 测试；前端改动 → `npm run build` + `npm test`；原生改动 → APK 构建验证
- [ ] 全量门禁：backend unit + integration + vitest 全绿再提交
- [ ] 版本号 +1（涉及 App 行为/资源变更时）
- [ ] 同步文档 + 本 skill：README / docs/* 相应小节 / AGENTS.md（铁律 §0，同一次提交内完成）
- [ ] 提交并推送 → 服务器 git pull（后端同时 restart API+Worker）→ 前端 tar 部署 / APK 发布

## 7. 环境事实

- 本机（Windows）：JDK 21（Temurin）、Android SDK `C:\Android\Sdk`（build-tools 35 + platform 35）、Gradle `C:\Android\gradle-8.14.3`
- 服务器：`ssh megumin`，仓库 `/root/projects/agent-drive`，服务 `agent-drive.service`（uvicorn 127.0.0.1:8000）+ `agent-drive-worker.service`（无监听端口），nginx 13311 单入口；HTTP 代理 127.0.0.1:7890
- keystore：服务器 `/root/agent-drive-android/agentdrive.keystore`（密码在本地 `D:\ds\agent-drive-keystore\password.txt`）
