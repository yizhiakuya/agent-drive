import { access, readFile } from "node:fs/promises";
import path from "node:path";

/**
 * 验证 Next.js 静态导出包含部署所需的关键文件和缓存版本标记。
 *
 * @returns {Promise<void>} 验证成功时完成，失败时抛出错误
 */
async function main() {
  const outputDirectory = path.resolve(process.argv[2] ?? "out");
  const requiredFiles = [
    "index.html",
    ".well-known/assetlinks.json",
    "sw.js",
  ];

  for (const relativePath of requiredFiles) {
    await assertFile(path.join(outputDirectory, relativePath));
  }

  const serviceWorker = await readFile(path.join(outputDirectory, "sw.js"), "utf8");
  if (!serviceWorker.includes("agent-drive-v")) {
    throw new Error(`service worker cache marker missing: ${outputDirectory}`);
  }
  console.log(`static build verified: ${outputDirectory}`);
}

/**
 * 确认指定路径存在且可读取。
 *
 * @param {string} filePath 待检查文件的绝对路径
 * @returns {Promise<void>} 文件可读取时完成
 */
async function assertFile(filePath) {
  try {
    await access(filePath);
  } catch (error) {
    throw new Error(`required build file missing: ${filePath}`, { cause: error });
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
