# Frontend Scripts

前端脚本统一使用 Node.js 的 JavaScript/TypeScript，放在本目录。脚本不得依赖后端 Java 类；需要验证后端接口时，应调用公开 HTTP 契约。

静态构建检查：

```bash
npm run build
npm run verify:build
```

脚本只负责前端构建、静态产物和前端测试辅助工作。跨前后端的部署、备份和 QA 编排继续放在仓库顶层 `scripts/`。
