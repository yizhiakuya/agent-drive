// 设置页浏览器 QA（shadcn/Combobox 版）：认证 → 设置页 → 获取模型 → combobox 弹层 → 选择回填 → 手输保留
import { chromium } from "playwright";

const BASE = process.env.QA_BASE || "http://127.0.0.1:8100";
const MOCK_URL = process.env.QA_MOCK_URL || "http://127.0.0.1:9001/v1";

(async () => {
  const browser = await chromium.launch({ executablePath: "/usr/bin/chromium-browser", headless: true, args: ["--no-sandbox"] });
  // serviceWorkers: "block" —— 必须：否则 PWA SW 缓存旧 chunk，QA 会测到过期构建
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, serviceWorkers: "block" });
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

  const cards = page.locator("div.grid button");
  if ((await cards.count()) < 3) fail("协议卡片数量异常: " + (await cards.count()));
  for (const label of ["OpenAI 兼容", "OpenAI Responses", "Anthropic"]) {
    if ((await page.getByRole("button", { name: new RegExp(label) }).count()) !== 1) fail("缺少协议卡片: " + label);
  }
  out.steps.push({ name: "protocol-cards", ok: true });

  await page.getByRole("button", { name: "获取可用模型" }).click();
  await page.waitForTimeout(2500);

  // combobox 弹层应自动展开，包含 3 个模型 option
  const options = page.getByRole("option");
  const optCount = await options.count();
  const optTexts = [];
  for (let i = 0; i < optCount; i++) optTexts.push(await options.nth(i).textContent());
  if (!optTexts.some((t) => (t || "").includes("qa-model-alpha"))) fail("combobox 弹层没有模型项: " + JSON.stringify(optTexts));
  out.steps.push({ name: "combobox-popup", visible: optCount > 0, texts: optTexts, ok: true });

  // 点选 qa-model-gamma → 输入回填
  await options.filter({ hasText: "qa-model-gamma" }).first().click();
  await page.waitForTimeout(400);
  const comboboxInput = page.locator('input[placeholder="选择或输入模型名"]');
  const v = await comboboxInput.inputValue();
  if (v !== "qa-model-gamma") fail("选择未回填输入框: " + v);
  out.steps.push({ name: "select-fills-input", value: v, ok: true });

  // 手输自定义名
  await comboboxInput.fill("my-custom-model");
  await page.waitForTimeout(300);
  out.steps.push({ name: "manual-input", value: await comboboxInput.inputValue(), ok: true });

  // 空模型校验
  await comboboxInput.fill("");
  await comboboxInput.press("Escape"); // 输入触发弹层自动打开，先关闭以免遮挡按钮
  await page.waitForTimeout(300);
  await page.getByRole("button", { name: "保存并测试连接" }).click();
  await page.waitForTimeout(400);
  if ((await page.getByText("请填写或选择模型").count()) === 0) fail("空模型校验未触发");
  out.steps.push({ name: "empty-model-validation", ok: true });

  await page.screenshot({ path: "/tmp/qa/shot-settings.png", fullPage: true });

  // 其余 tab 冒烟 + 截图
  for (const tab of ["对话", "文件", "任务"]) {
    await page.getByRole("button", { name: tab, exact: true }).click();
    await page.waitForTimeout(900);
    await page.screenshot({ path: "/tmp/qa/shot-" + tab + ".png", fullPage: true });
    out.steps.push({ name: "tab-" + tab, ok: true });
  }

  console.log(JSON.stringify(out, null, 1));
  await browser.close();
})().catch((e) => { console.error("QA FAIL:", e.message); process.exit(1); });
