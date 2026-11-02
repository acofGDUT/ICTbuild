package edu.whut.cs.bi.api.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 动态注册的公开客户端不落库：注册信息签名后直接作为 client_id，使用时验签解出回调地址。
 */
public class OAuthClientRegistry {

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_REDIRECT_URIS = 5;
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "data", "file", "vbscript", "about", "blob");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final OAuthSigner signer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OAuthClientRegistry(OAuthSigner signer) {
        this.signer = signer;
    }

    public RegisteredClient register(String clientName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uris 不能为空");
        }
        if (redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uris 最多 " + MAX_REDIRECT_URIS + " 个");
        }
        for (String uri : redirectUris) {
            if (!isAllowedRedirectUri(uri)) {
                throw new OAuthException("invalid_redirect_uri", "不允许的回调地址：" + uri);
            }
        }
        String name = clientName == null || clientName.isBlank() ? "未命名客户端" : clientName.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("n", name);
        payload.set("r", objectMapper.valueToTree(redirectUris));
        payload.put("t", System.currentTimeMillis() / 1000);
        try {
            String clientId = signer.encode(objectMapper.writeValueAsString(payload));
            return new RegisteredClient(clientId, name, List.copyOf(redirectUris));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 验签并解出客户端信息；client_id 无效时抛出 invalid_client。
     */
    public RegisteredClient resolve(String clientId) {
        String json = signer.decode(clientId);
        if (json == null) {
            throw new OAuthException("invalid_client", "client_id 无效", 401);
        }
        try {
            JsonNode payload = objectMapper.readTree(json);
            List<String> uris = new ArrayList<>();
            payload.path("r").forEach(node -> uris.add(node.asText()));
            return new RegisteredClient(clientId, payload.path("n").asText(), Collections.unmodifiableList(uris));
        } catch (JsonProcessingException e) {
            throw new OAuthException("invalid_client", "client_id 无效", 401);
        }
    }

    /**
     * 允许 http 回环地址（端口不限）、https，以及桌面客户端常用的自定义 scheme。
     */
    public static boolean isAllowedRedirectUri(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getFragment() != null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_SCHEMES.contains(scheme)) {
            return false;
        }
        if ("http".equals(scheme)) {
            return uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
        }
        if ("https".equals(scheme)) {
            return uri.getHost() != null;
        }
        return true;
    }

    public static final class RegisteredClient {
        private final String clientId;
        private final String clientName;
        private final List<String> redirectUris;

        public RegisteredClient(String clientId, String clientName, List<String> redirectUris) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.redirectUris = redirectUris;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientName() {
            return clientName;
        }

        public List<String> getRedirectUris() {
            return redirectUris;
        }
    }
}
