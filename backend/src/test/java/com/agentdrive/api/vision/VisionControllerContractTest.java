package com.agentdrive.api.vision;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.files.FileContentPort;
import com.agentdrive.vision.VisionDescriptionPort;
import com.agentdrive.vision.VisionModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 验证图片结构化识别接口的请求校验和 owner 认证边界。 */
class VisionControllerContractTest {
    /** 非法相对路径必须在进入图片读取服务前转换为 HTTP 400。 */
    @Test
    void rejectsTraversalAsBadRequest() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner);

        client.post().uri("/api/v1/vision/describe")
                .bodyValue(Map.of("files", List.of("../secret.jpg")))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** 空列表不能触发无意义的视觉模型请求。 */
    @Test
    void rejectsEmptyFileListAsBadRequest() {
        UUID owner = UUID.randomUUID();
        WebTestClient client = client(owner);

        client.post().uri("/api/v1/vision/describe")
                .bodyValue(Map.of("files", List.of()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** 创建带认证替身的视觉控制器测试客户端。 */
    private WebTestClient client(UUID owner) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new VisionController(
                        visionService(), new WebRequestPrincipalResolver(authenticator)))
                .build()
                .mutate().defaultCookie("agentdrive_session", "session-token").build();
    }

    /**
     * 创建不会被非法请求触发的视觉服务替身。
     * 代理只用于满足存储服务依赖，路径校验异常应在服务调用之前返回。
     */
    private VisionDescriptionPort visionService() {
        FileContentPort files = (FileContentPort) Proxy.newProxyInstance(
                FileContentPort.class.getClassLoader(),
                new Class<?>[]{FileContentPort.class},
                (proxy, method, args) -> null);
        return new com.agentdrive.vision.VisionDescriptionService(
                userId -> Optional.empty(), files, new VisionModelClient(new ObjectMapper()));
    }
}
