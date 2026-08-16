// 设置页浏览器 QA（Playwright + 临时后端 + mock 模型服务）。
// 前置：backend 以 AGENT_DRIVE_BACKEND_DIR=/tmp/ad-qa 跑在 8100；mock 模型服务在 9001。
// 用法：node qa-settings.mjs
const { chromium } = require("playwright");

const BASE = process.env.QA_BASE || "http://127.0.0.1:8100";
const MOCK_URL = process.env.QA_MOCK_URL || "http://127.0.0.1:9001/v1";

(async () => {
  const browser = await chromium.launch({ executablePath: "/usr/bin/chromium-browser", headless: true, args: ["--no-sandbox"] });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const out = { steps: [] };
  const fail = (msg) => { throw new Error(msg); };

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.evaluate(async ({ MOCK_URL }) => {
    const j = (o) => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(o) });
    await fetch("/api/v1/auth/setup", j({ password: "qa-pass-123" })).catch(() => {});
    await fetch("/api/v1/auth/login", j({ password: "qa-pass-123" }));
    await fetch("/api/v1/config", j({ type: "openai_compat", base_url: MOCK_URL, api_key: "sk-qa", model: "qa-model-alpha" }));
  }, { MOCK_URL });
  await page.reload({ waitUntil: "networkidle" });
  await page.waitForTimeout(1000);

  await page.getByRole("button", { name: "设置" }).click();
  await page.waitForTimeout(800);
  if ((await page.getByText("🧠 LLM 模型").count()) !== 1) fail("设置页未打开");
  out.steps.push({ name: "settings-opened", ok: true });

  const cards = page.locator("div.grid.grid-cols-3 button");
  if ((await cards.count()) !== 3) fail("协议卡片不是 3 个");
  out.steps.push({ name: "protocol-cards", ok: true });

  await page.getByRole("button", { name: "获取可用模型" }).click();
  await page.waitForTimeout(2500);

  const selects = page.locator("select");
  let modelOptions = [];
  let modelSelectIdx = -1;
  for (let i = 0; i < (await selects.count()); i++) {
    const opts = await selects.nth(i).locator("option").allTextContents();
    if (opts.some((t) => t.includes("qa-model-"))) { modelSelectIdx = i; modelOptions = opts; }
  }
  if (modelSelectIdx < 0) fail("获取模型后没有出现下拉框");
  if (!modelOptions.some((t) => t.includes("qa-model-alpha"))) fail("下拉框缺少模型项");
  out.steps.push({ name: "model-dropdown", options: modelOptions.length, ok: true });

  const modelInput = page.locator('input[placeholder*="如 deepseek"]');
  await selects.nth(modelSelectIdx).selectOption("qa-model-gamma");
  await page.waitForTimeout(300);
  if ((await modelInput.inputValue()) !== "qa-model-gamma") fail("下拉选择未回填输入框");
  out.steps.push({ name: "select-syncs-input", ok: true });

  await modelInput.fill("");
  await page.getByRole("button", { name: "保存并测试连接" }).click();
  await page.waitForTimeout(400);
  if ((await page.getByText("请填写或选择模型").count()) === 0) fail("空模型校验未触发");
  out.steps.push({ name: "empty-model-validation", ok: true });

  await page.screenshot({ path: "/tmp/qa/shot-settings-final.png", fullPage: true });
  console.log(JSON.stringify(out, null, 1));
  await browser.close();
})().catch((e) => { console.error("QA FAIL:", e.message); process.exit(1); });
