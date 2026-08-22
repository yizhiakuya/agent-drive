package com.agentdrive.api.vision;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.index.IndexPaths;
import com.agentdrive.vision.VisionDescriptionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 提供图片批量结构化识别接口。
 *
 * <p>该接口只返回描述，不写入索引；需要把描述纳入语义检索时调用索引业务接口。</p>
 */
@RestController
@Profile({"java-files", "java-auth", "java-chat"})
@RequestMapping("/api/v1/vision")
public final class VisionController {
    private final VisionDescriptionService vision;
    private final WebRequestPrincipalResolver principalResolver;

    /**
     * 创建图片识别控制器。
     * @param vision 图片读取和视觉模型调用服务。
     * @param principalResolver 解析请求 owner 的认证组件。
     */
    public VisionController(VisionDescriptionService vision, WebRequestPrincipalResolver principalResolver) {
        this.vision = vision;
        this.principalResolver = principalResolver;
    }

    /**
     * 响应 {@code POST /api/v1/vision/describe}，批量返回图片结构化描述。
     * @param request body.files 为 owner 相对图片路径列表，最多 16 项。
     * @param exchange 用于确定图片 owner 的请求上下文。
     * @return 每个图片的 description JSON 对象和逐项错误。
     */
    @PostMapping("/describe")
    public Mono<Map<String, Object>> describe(@RequestBody(required = false) DescribeRequest request,
                                               ServerWebExchange exchange) {
        List<String> paths = normalize(request == null ? null : request.files());
        return principalResolver.resolve(exchange).flatMap(principal -> Mono.fromCallable(
                () -> vision.describeFiles(principal.userId(), paths)).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 规范化图片路径列表并限制同步响应规模。
     * @param raw 请求中的相对路径列表。
     * @return 去重后的路径列表。
     * @throws ResponseStatusException 列表为空、过大或包含非法路径时返回 400。
     */
    private List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > 16) {
            throw new ResponseStatusException(BAD_REQUEST, "files must contain 1 to 16 paths");
        }
        try {
            return IndexPaths.normalize(raw);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(BAD_REQUEST, error.getMessage());
        }
    }

    /**
     * 图片识别请求体。
     * @param files owner 根目录下的相对图片路径列表。
     */
    public record DescribeRequest(@JsonProperty("files") List<String> files) {
    }
}
