export type IndexPolicy = "manual" | "auto" | "images";

export const INDEX_POLICY_EVENT = "agent-drive:index-policy-changed";
const STORAGE_KEY = "agent-drive-index-policy-v1";
const DEFAULT_POLICY: IndexPolicy = "manual";

function valid(value: unknown): value is IndexPolicy {
  return value === "manual" || value === "auto" || value === "images";
}

/** 读取当前浏览器 owner 的智能摄入策略；默认手动，避免隐式产生模型费用。 */
export function getIndexPolicy(storage?: Storage): IndexPolicy {
  if (typeof window === "undefined") return DEFAULT_POLICY;
  try {
    const source = storage ?? window.localStorage;
    const value = source.getItem(STORAGE_KEY);
    return valid(value) ? value : DEFAULT_POLICY;
  } catch {
    return DEFAULT_POLICY;
  }
}

/** 保存智能摄入策略，并通知文件页立即使用新策略。 */
export function setIndexPolicy(policy: IndexPolicy, storage?: Storage) {
  if (!valid(policy)) return;
  try {
    (storage ?? window.localStorage).setItem(STORAGE_KEY, policy);
  } catch {
    // 浏览器禁用本地存储时仍让当前页面内策略生效。
  }
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(INDEX_POLICY_EVENT, { detail: policy }));
  }
}
