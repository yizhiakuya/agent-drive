# Backend Scripts

后端脚本统一使用 Java 21，放在本目录并以 Java source-file 模式运行。脚本不得依赖 Python 或前端 Node.js；需要访问应用内部服务时，优先使用标准 JDK API。

健康检查：

```bash
java scripts/HealthCheck.java http://127.0.0.1:8000
```

从仓库根目录快速查看服务器上的 Agent Drive 会话：

```bash
java backend/scripts/SessionView.java 83565e8b-238d-406e-8a64-3d122c107c4b
java backend/scripts/SessionView.java 83565e8b-238d-406e-8a64-3d122c107c4b --full
```

默认只显示最近 12 条消息和工具 replay 的 action/operation/path，并截断内容；`--full` 显示全部消息和更长的 replay 输出。两种模式都会脱敏常见 token。查看器只读 PostgreSQL，找不到会话返回退出码 3。

后端生产代码仍放在 `src/main/java`；本目录只放一次性运维、诊断和发布辅助脚本。每个类、构造器和方法都使用简洁的标准 Javadoc。
