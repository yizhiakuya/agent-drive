# 文档索引

本文档目录分为现行说明和历史快照两类。涉及当前实现、运维或开发时，优先阅读现行说明；历史质量报告和专题审查统一位于 [`archive/`](archive/README.md)，只用于追溯当时的审查证据，不代表当前状态。

## 现行说明

| 文档 | 用途 |
|------|------|
| [`README.md`](../README.md) | 项目概览、快速开始、部署入口 |
| [`product.md`](product.md) | 产品定位、用户流程、功能地图和当前边界 |
| [`product-design.md`](product-design.md) | 面向 Gemini 的产品与业务说明，不规定 UI/UX 方案 |
| [`architecture.md`](architecture.md) | Java API/Worker、前端、Android 和数据边界 |
| [`security.md`](security.md) | 认证、密钥、文件安全和生产暴露面 |
| [`android.md`](android.md) | Capacitor App、相册同步、构建和发布 |
| [`frontend-architecture.md`](frontend-architecture.md) | Next.js 前端分层、状态和请求生命周期 |
| [`frontend-design.md`](frontend-design.md) | UI 控件、主题、响应式和可访问性约定 |
| [`java-migration-architecture.md`](java-migration-architecture.md) | Java 后端现行边界及已完成迁移/切换记录 |
| [`microservices-architecture.md`](microservices-architecture.md) | 从模块化单体演进到微服务的边界、数据所有权和迁移顺序 |
| [`agent-definition.md`](agent-definition.md) | Agent 工具边界、循环、可靠性和安全契约 |
| [`quality-analysis.md`](quality-analysis.md) | 工程质量分析协议和当前基线 |

项目级维护约束在 [`AGENTS.md`](../AGENTS.md)。后端启动和接口示例见 [`backend/README.md`](../backend/README.md)，前端命令见 [`frontend/README.md`](../frontend/README.md)。

## 历史快照

旧质量报告和专题审查见 [`archive/README.md`](archive/README.md)。它们可能记录已经修复或已经淘汰的实现，只能作为变更背景、问题证据和决策依据，不能作为开发或部署手册。
