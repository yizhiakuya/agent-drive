import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      // fetch-on-mount（异步 load() 完成后 setState）是本项目标准习语；
      // 该新规则对 async 回调误报“同步 setState”，且项目已有 15s GET
      // 缓存 + inflight 去重防级联请求。真正的同步 setState 已人工规避。
      "react-hooks/set-state-in-effect": "off",
      // 纯静态导出：预览/二维码/缩略图均为用户自有内容，经后端 FileResponse
      // 原图直出，next/image 优化器在 output:export 下不可用。
      "@next/next/no-img-element": "off",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Capacitor 工程：android/** 是构建产物与 cap sync 拷贝的打包 JS（非源码），
    // 不应被 ESLint 扫描（Java 侧由 Gradle 编译把关）。
    "android/**",
    // 用户提供的 JSX 原型只作设计参考，不进入生产 bundle 或运行时。
    "prototypes/**",
  ]),
]);

export default eslintConfig;
