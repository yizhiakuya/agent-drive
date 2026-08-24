/**
 * 前端可供 Agent 发现的语义动作目录。
 *
 * <p>目录项只描述稳定的 operation、用途和参数 schema；真正的 React handler 由挂载
 * 该能力的组件注册。新增交互时扩展这里和对应组件的 handler 即可，不需要修改后端
 * 工具 schema，也不允许模型直接传入函数名或脚本。</p>
 */

export interface FrontendCapability {
  operation: string;
  summary: string;
  target_tab: string;
  parameters: Record<string, unknown>;
}

export interface FrontendActionPayload {
  operation: string;
  arguments: Record<string, unknown>;
  targetTab: string;
  summary?: string;
}

export interface PendingFrontendAction extends FrontendActionPayload {
  id: string;
}

export type FrontendActionHandler = (action: PendingFrontendAction) => void | Promise<void>;

const CAPABILITIES: readonly FrontendCapability[] = [
  {
    operation: "files.open",
    summary: "切换到文件页，加载并打开指定文件的详情和内容预览",
    target_tab: "files",
    parameters: {
      type: "object",
      required: ["path"],
      properties: {
        path: { type: "string", description: "owner 内相对 POSIX 文件路径，例如 docs/readme.md" },
      },
    },
  },
  {
    operation: "files.show_details",
    summary: "切换到文件页并显示指定文件的详情面板",
    target_tab: "files",
    parameters: {
      type: "object",
      required: ["path"],
      properties: {
        path: { type: "string", description: "owner 内相对 POSIX 文件路径" },
      },
    },
  },
  {
    operation: "files.open_folder",
    summary: "切换到文件页并打开指定目录；空 path 表示根目录",
    target_tab: "files",
    parameters: {
      type: "object",
      required: ["path"],
      properties: {
        path: { type: "string", description: "owner 内相对 POSIX 目录路径，根目录使用空字符串" },
      },
    },
  },
];

const handlers = new Map<string, FrontendActionHandler>();
let actionSequence = 0;

/**
 * 返回当前客户端的前端能力清单副本。
 *
 * @returns 可随聊天请求发送给 Agent 的 JSON 能力清单
 */
export function getFrontendCapabilities(): FrontendCapability[] {
  return CAPABILITIES.map((capability) => ({
    ...capability,
    parameters: { ...capability.parameters },
  }));
}

/**
 * 判断 operation 是否由当前前端注册表声明。
 *
 * @param operation 待检查的动作名
 * @returns operation 已登记时为 true
 */
export function isFrontendOperation(operation: unknown): operation is string {
  return typeof operation === "string"
    && CAPABILITIES.some((capability) => capability.operation === operation);
}

/**
 * 判断文件动作使用的路径是否为 owner 内相对 POSIX 路径。
 *
 * @param value 待校验路径
 * @param allowRoot 是否允许空字符串表示根目录
 * @returns 路径安全且符合前端动作约束时为 true
 */
export function isSafeFrontendPath(value: unknown, allowRoot = false): value is string {
  if (typeof value !== "string") return false;
  if (value.includes("\\") || value.includes("\0") || value.startsWith("/")) return false;
  if (!value && allowRoot) return true;
  if (!value) return false;
  return value.split("/").every((segment) => segment !== "" && segment !== "." && segment !== "..");
}

/**
 * 将 SSE 动作数据转换为前端待执行动作。
 *
 * @param data 后端 frontend_action 事件的 JSON 对象
 * @returns 通过客户端 allowlist 的动作，数据不符合契约时返回 null
 */
export function normalizeFrontendAction(data: Record<string, unknown>): FrontendActionPayload | null {
  if (!isFrontendOperation(data.operation)) return null;
  if (!isRecord(data.arguments)) return null;
  const capability = CAPABILITIES.find((candidate) => candidate.operation === data.operation);
  if (!capability) return null;
  if (!argumentsMatchCapability(data.arguments, capability)) return null;
  return {
    operation: data.operation,
    arguments: data.arguments,
    targetTab: capability.target_tab,
    ...(typeof data.summary === "string" ? { summary: data.summary } : {}),
  };
}

/** 在事件入队前再次按本地 registry schema 校验参数，不能只信任后端回显的 schema。 */
function argumentsMatchCapability(
  value: Record<string, unknown>,
  capability: FrontendCapability,
): boolean {
  const schema = capability.parameters;
  const properties = isRecord(schema.properties) ? schema.properties : {};
  const required = Array.isArray(schema.required) ? schema.required.filter((item): item is string => typeof item === "string") : [];
  if (required.some((name) => !(name in value))) return false;
  if (Object.keys(value).some((name) => !(name in properties))) return false;
  for (const [name, argument] of Object.entries(value)) {
    const property = properties[name];
    if (!isRecord(property)) return false;
    const type = property.type;
    if (typeof type === "string" && !matchesJsonType(argument, type)) return false;
  }
  if ("path" in value && !isSafeFrontendPath(
    value.path,
    capability.operation === "files.open_folder",
  )) return false;
  return true;
}

function matchesJsonType(value: unknown, type: string): boolean {
  switch (type) {
    case "string": return typeof value === "string";
    case "number": return typeof value === "number" && Number.isFinite(value);
    case "integer": return typeof value === "number" && Number.isInteger(value);
    case "boolean": return typeof value === "boolean";
    case "array": return Array.isArray(value);
    case "object": return isRecord(value);
    default: return false;
  }
}

/**
 * 给待执行动作生成客户端唯一 ID。
 *
 * @param action 已通过动作契约校验的动作
 * @returns 带队列 ID 的动作
 */
export function createPendingFrontendAction(action: FrontendActionPayload): PendingFrontendAction {
  actionSequence += 1;
  return { ...action, id: `${Date.now()}-${actionSequence}` };
}

/**
 * 注册一个 React 组件当前可执行的动作 handler。
 *
 * @param operation 已在能力目录声明的 operation
 * @param handler 负责更新组件状态或触发交互的函数
 * @returns 清理注册的函数
 */
export function registerFrontendActionHandler(operation: string, handler: FrontendActionHandler): () => void {
  if (!isFrontendOperation(operation)) {
    throw new Error(`Frontend operation is not registered: ${operation}`);
  }
  handlers.set(operation, handler);
  return () => {
    if (handlers.get(operation) === handler) handlers.delete(operation);
  };
}

/**
 * 把一个待执行动作分发给当前已挂载组件的 handler。
 *
 * @param action 待执行动作
 * @returns 找到并等待 handler 时为 true，否则为 false
 */
export async function dispatchFrontendAction(action: PendingFrontendAction): Promise<boolean> {
  const handler = handlers.get(action.operation);
  if (!handler) return false;
  await handler(action);
  return true;
}

/**
 * 判断一个未知值是否为普通 JSON 对象。
 *
 * @param value 待判断值
 * @returns 非数组对象时为 true
 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
