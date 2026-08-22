package com.agentdrive.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天完成和流式聊天的请求模型。
 *
 * <p>客户端可提交当前消息、历史消息、确认参数、会话 ID、思考等级和权限模式；构造器会把
 * 可空列表规范化为不可变空列表，并把缺省思考等级设为 {@code auto}、权限模式设为
 * {@code auto}。认证用户 ID
 * 使用 {@code JsonIgnore}，只由服务端在请求准备阶段注入，模型不能从请求体提供；
 * {@code frontend_capabilities} 只是当前浏览器的 UI 动作清单，不授予后端 API 权限；
 * {@code inline_images} 只承载本轮支持视觉模型的剪贴板 Base64，不落盘、不进入历史正文。
 * {@code file_context} 只接受 owner 根下的相对路径，正文由服务端文件服务读取，客户端
 * 不能直接把任意文件内容伪造为模型上下文。
 */
public record ChatRequest(
        @NotBlank @Size(max = MAX_MESSAGE_CHARS) String message,
        @Size(max = MAX_HISTORY_ITEMS) List<Map<String, Object>> history,
        @Size(max = MAX_CONFIRMATION_ITEMS) List<Map<String, Object>> confirmations,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("thinking_level")
        @Pattern(regexp = "auto|low|medium|high") String thinkingLevel,
        @com.fasterxml.jackson.annotation.JsonIgnore UUID authenticatedUserId,
        @com.fasterxml.jackson.annotation.JsonIgnore String requestId,
        @JsonProperty("frontend_capabilities")
        @Size(max = MAX_FRONTEND_CAPABILITIES) List<Map<String, Object>> frontendCapabilities,
        @JsonProperty("model") @Size(max = 256) String model,
        @JsonProperty("file_context") @Size(max = MAX_FILE_CONTEXT_ITEMS) List<String> fileContext,
        @JsonProperty("permission_mode")
        @Pattern(regexp = "ask|auto|full") String permissionMode,
        @JsonProperty("inline_images") @Size(max = MAX_INLINE_IMAGES) List<InlineImage> inlineImages
) {
    /** 单条用户消息允许的最大 UTF-8 字节数/字符数上限。 */
    public static final int MAX_MESSAGE_CHARS = 64 * 1024;
    /** 客户端最多携带的历史消息条数。 */
    public static final int MAX_HISTORY_ITEMS = 100;
    /** 单轮最多携带的待确认工具参数条数。 */
    public static final int MAX_CONFIRMATION_ITEMS = 20;
    /** 当前浏览器能力清单的最大条数。 */
    public static final int MAX_FRONTEND_CAPABILITIES = 100;
    /** 一轮最多附加的 owner 文件/目录路径数。 */
    public static final int MAX_FILE_CONTEXT_ITEMS = 16;
    /** 单个附加路径的最大字符数。 */
    public static final int MAX_FILE_CONTEXT_PATH_CHARS = 1024;
    /** 请求 JSON 递归估算的最大 UTF-8 大小；包含内联图片时允许 16 MiB 的受控上限。 */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    /** 单轮最多发送的剪贴板图片数量。 */
    public static final int MAX_INLINE_IMAGES = 4;
    /** 单张内联图片允许的 Base64 字符数上限，约对应 4 MiB 原始字节。 */
    public static final int MAX_INLINE_IMAGE_BASE64_CHARS = 6 * 1024 * 1024;
    /** 单轮所有内联图片允许的 Base64 字符数上限。 */
    public static final int MAX_INLINE_IMAGE_TOTAL_BASE64_CHARS = 12 * 1024 * 1024;

    /**
     * 创建未注入认证 owner 的客户端请求。
     *
     * @param message 本轮用户消息，必须非空。
     * @param history 客户端历史消息；为空时转为空不可变列表。
     * @param confirmations 本轮确认/重放参数；为空时转为空不可变列表。
     * @param sessionId 可选会话 ID。
     * @param thinkingLevel 思考等级，支持 auto、low、medium、high，空值归一化为 auto。
     * @param permissionMode 权限模式，支持 ask、auto、full，空值归一化为 auto。
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel) {
        this(message, history, confirmations, sessionId, thinkingLevel, null, null, null, null, null, "auto", List.of());
    }

    /**
     * 创建带服务端 owner 和请求 ID 的兼容请求副本。
     *
     * @param message 本轮消息
     * @param history 客户端历史
     * @param confirmations 本轮确认参数
     * @param sessionId 会话 ID
     * @param thinkingLevel 思考等级
     * @param authenticatedUserId 服务端认证 owner
     * @param requestId 聊天流关联 ID
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId) {
        this(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, requestId, null, null, null, "auto", List.of());
    }

    /**
     * 保留旧的八参数内部构造器，并使用默认模型。
     *
     * @param message 本轮消息
     * @param history 客户端历史
     * @param confirmations 本轮确认参数
     * @param sessionId 会话 ID
     * @param thinkingLevel 思考等级
     * @param authenticatedUserId 服务端认证 owner
     * @param requestId 服务端请求关联 ID
     * @param frontendCapabilities 当前浏览器注册的前端动作能力清单
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId,
                       List<Map<String, Object>> frontendCapabilities) {
        this(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, requestId, frontendCapabilities, null, null, "auto", List.of());
    }

    /**
     * 保留加入文件上下文前的九参数内部构造器，供现有 runtime/test 调用方兼容。
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId,
                       List<Map<String, Object>> frontendCapabilities,
                       String model) {
        this(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, requestId, frontendCapabilities, model, null, "auto", List.of());
    }

    /**
     * 保留旧的十参数记录构造器，默认使用仅高风险请求批准模式。
     *
     * @param message 本轮消息
     * @param history 客户端历史
     * @param confirmations 本轮确认参数
     * @param sessionId 会话 ID
     * @param thinkingLevel 思考等级
     * @param authenticatedUserId 服务端认证 owner
     * @param requestId 请求关联 ID
     * @param frontendCapabilities 当前浏览器动作能力
     * @param model 本轮模型
     * @param fileContext owner 文件上下文路径
     */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId,
                       List<Map<String, Object>> frontendCapabilities,
                       String model,
                       List<String> fileContext) {
        this(message, history, confirmations, sessionId, thinkingLevel, authenticatedUserId, requestId,
                frontendCapabilities, model, fileContext, "auto", List.of());
    }

    /** 保留加入内联图片前的十一参数内部构造器，默认不附加图片。 */
    public ChatRequest(String message,
                       List<Map<String, Object>> history,
                       List<Map<String, Object>> confirmations,
                       String sessionId,
                       String thinkingLevel,
                       UUID authenticatedUserId,
                       String requestId,
                       List<Map<String, Object>> frontendCapabilities,
                       String model,
                       List<String> fileContext,
                       String permissionMode) {
        this(message, history, confirmations, sessionId, thinkingLevel, authenticatedUserId, requestId,
                frontendCapabilities, model, fileContext, permissionMode, List.of());
    }

    /**
     * 规范化记录组件并创建服务端内部请求。
     *
     * @param message 本轮用户消息。
     * @param history 已复制为不可变列表的历史消息。
     * @param confirmations 已复制为不可变列表的确认参数。
     * @param sessionId 会话 ID，可为空。
     * @param thinkingLevel 已校验的思考等级；空值转为 auto。
     * @param authenticatedUserId 服务端解析出的 owner UUID，不接受 JSON 注入。
     * @param requestId 服务端生成或复用的请求关联 ID，不接受 JSON 注入。
     * @param frontendCapabilities 当前浏览器注册的前端动作能力清单。
     * @param model 本轮聊天要使用的模型 ID；为空时沿用 owner 的默认模型。
     * @param permissionMode 本轮批准策略；为空时使用仅高风险请求批准。
     * @param inlineImages 本轮剪贴板图片的 Base64 内联内容；为空时不发送图片。
     */
    public ChatRequest {
        history = history == null ? List.of() : List.copyOf(history);
        confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
        thinkingLevel = thinkingLevel == null || thinkingLevel.isBlank() ? "auto" : thinkingLevel;
        permissionMode = permissionMode == null || permissionMode.isBlank()
                ? "auto"
                : permissionMode.trim().toLowerCase(Locale.ROOT);
        inlineImages = inlineImages == null ? List.of() : inlineImages.stream()
                .filter(java.util.Objects::nonNull)
                .map(InlineImage::normalized)
                .toList();
        frontendCapabilities = frontendCapabilities == null ? List.of() : frontendCapabilities.stream()
                .filter(java.util.Objects::nonNull)
                .map(java.util.Map::copyOf)
                .toList();
        model = model == null ? "" : model.trim();
        fileContext = fileContext == null ? List.of() : fileContext.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    /**
     * 校验聊天请求的递归内容总量。
     *
     * <p>Bean Validation 会在 HTTP 请求进入 Controller 前调用该属性；除了顶层列表数量
     * 外，还限制 map/list 嵌套内容的 UTF-8 大小，避免把单个历史项或确认参数扩成超大 JSON。
     * {@code @JsonIgnore} 确保该内部校验属性不会出现在 API 响应或模型上下文中。</p>
     *
     * @return 所有字段均在请求体预算内时为 true。
     */
    @AssertTrue(message = "chat request body exceeds the 16 MiB limit")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean payloadWithinLimits() {
        if (message == null || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_CHARS) {
            return false;
        }
        return encodedSize(message) + encodedSize(history) + encodedSize(confirmations)
                + encodedSize(frontendCapabilities) + encodedSize(model) + encodedSize(fileContext)
                + encodedSize(inlineImages)
                + encodedSize(permissionMode) <= MAX_BODY_BYTES;
    }

    /** 校验剪贴板图片的 MIME、Base64 形状和单轮大小，阻止任意 data URL/超大正文进入模型。 */
    @AssertTrue(message = "inline_images contains an invalid image payload")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean inlineImagesValid() {
        if (inlineImages == null || inlineImages.size() > MAX_INLINE_IMAGES) return false;
        int total = 0;
        for (InlineImage image : inlineImages) {
            if (image == null || !image.mediaType().startsWith("image/")
                    || image.data().isBlank() || image.data().length() > MAX_INLINE_IMAGE_BASE64_CHARS
                    || !image.data().matches("[A-Za-z0-9+/]*={0,2}")) return false;
            total += image.data().length();
            if (total > MAX_INLINE_IMAGE_TOTAL_BASE64_CHARS) return false;
        }
        return true;
    }

    /**
     * 校验客户端附加的文件路径仍在 owner 根下，避免把绝对路径、反斜杠或穿越段交给
     * 文件服务。Bean Validation 会把失败转换成稳定的 400，而不是在上下文装配阶段抛出
     * 存储层异常。
     *
     * @return 所有附加路径符合 owner-relative POSIX 语义时为 true。
     */
    @AssertTrue(message = "file_context contains an invalid owner-relative path")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean fileContextPathsValid() {
        return fileContext != null && fileContext.size() <= MAX_FILE_CONTEXT_ITEMS
                && fileContext.stream().allMatch(ChatRequest::validFileContextPath);
    }

    private static boolean validFileContextPath(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_FILE_CONTEXT_PATH_CHARS
                || value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /**
     * 递归估算 JSON 值大小并在达到上限后提前停止。
     *
     * @param value 待估算的 Jackson 值。
     * @return 不超过 {@link #MAX_BODY_BYTES} 的估算字节数；超出时返回上限加一。
     */
    private static int encodedSize(Object value) {
        return encodedSize(value, 0);
    }

    /**
     * 执行带早停的递归大小估算。
     *
     * @param value 当前值。
     * @param used 已累计字节数。
     * @return 累计大小或超限哨兵值。
     */
    private static int encodedSize(Object value, int used) {
        if (used > MAX_BODY_BYTES) return MAX_BODY_BYTES + 1;
        if (value == null) return used + 4;
        if (value instanceof String text) {
            long next = (long) used + text.getBytes(StandardCharsets.UTF_8).length + 2;
            return next > MAX_BODY_BYTES ? MAX_BODY_BYTES + 1 : (int) next;
        }
        if (value instanceof Map<?, ?> map) {
            int total = used + 2;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total = encodedSize(String.valueOf(entry.getKey()), total);
                total = encodedSize(entry.getValue(), total);
                if (total > MAX_BODY_BYTES) return MAX_BODY_BYTES + 1;
            }
            return total;
        }
        if (value instanceof Iterable<?> iterable) {
            int total = used + 2;
            for (Object item : iterable) {
                total = encodedSize(item, total);
                if (total > MAX_BODY_BYTES) return MAX_BODY_BYTES + 1;
            }
            return total;
        }
        long next = (long) used + String.valueOf(value).getBytes(StandardCharsets.UTF_8).length + 1;
        return next > MAX_BODY_BYTES ? MAX_BODY_BYTES + 1 : (int) next;
    }

    /**
     * 创建只替换会话 ID 的请求副本。
     *
     * @param normalizedSessionId 由会话服务确认归属后的会话 ID。
     * @return 保留其他字段和认证 owner 的新请求记录。
     */
    public ChatRequest withSessionId(String normalizedSessionId) {
        return new ChatRequest(message, history, confirmations, normalizedSessionId, thinkingLevel,
                authenticatedUserId, requestId, frontendCapabilities, model, fileContext, permissionMode, inlineImages);
    }

    /**
     * 创建只注入认证用户 ID 的请求副本。
     *
     * @param userId 由认证解析器得到的 owner UUID。
     * @return 保留客户端字段和会话 ID、附带服务端 owner 的新请求记录。
     */
    public ChatRequest withAuthenticatedUserId(UUID userId) {
        return new ChatRequest(message, history, confirmations, sessionId, thinkingLevel,
                userId, requestId, frontendCapabilities, model, fileContext, permissionMode, inlineImages);
    }

    /**
     * 绑定当前 HTTP 聊天流的服务端关联 ID。
     *
     * <p>该字段被 {@code JsonIgnore} 标记，只在 Controller 到 runtime 的内部调用链中
     * 传播，模型和客户端请求体都看不到它。</p>
     *
     * @param correlationId 当前请求使用的关联 ID。
     * @return 保留所有聊天字段并附带关联 ID 的请求副本。
     */
    public ChatRequest withRequestId(String correlationId) {
        return new ChatRequest(message, history, confirmations, sessionId, thinkingLevel,
                authenticatedUserId, correlationId, frontendCapabilities, model, fileContext, permissionMode, inlineImages);
    }

    /** 一张只在当前聊天请求内传递的剪贴板图片。 */
    public record InlineImage(
            String name,
            @JsonProperty("media_type") String mediaType,
            String data
    ) {
        /** 规范化名称、MIME 和 Base64 字段，不保留 data URL 前缀。 */
        public InlineImage normalized() {
            String normalizedName = name == null || name.isBlank() ? "pasted-image" : name.trim();
            String normalizedType = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
            String normalizedData = data == null ? "" : data.trim();
            if (normalizedData.startsWith("data:") && normalizedData.contains(",")) {
                normalizedData = normalizedData.substring(normalizedData.indexOf(',') + 1);
            }
            return new InlineImage(normalizedName, normalizedType, normalizedData);
        }
    }
}
