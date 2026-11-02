package edu.whut.cs.bi.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.user.UserException;
import com.ruoyi.common.utils.IpUtils;
import com.ruoyi.framework.shiro.service.SysLoginService;
import edu.whut.cs.bi.api.oauth.AuthorizationCodeStore;
import edu.whut.cs.bi.api.oauth.McpTokenService;
import edu.whut.cs.bi.api.oauth.OAuthClientRegistry;
import edu.whut.cs.bi.api.oauth.OAuthException;
import edu.whut.cs.bi.api.oauth.OAuthProperties;
import edu.whut.cs.bi.api.oauth.OAuthRedirects;
import edu.whut.cs.bi.api.oauth.OAuthSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 使用的 OAuth 2.1 授权服务器：元数据、动态注册、授权页、令牌、撤销。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class OAuthController {

    private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    private static final String TOKEN_TYPE_JWT = "urn:ietf:params:oauth:token-type:jwt";
    private static final Map<String, String> MANUAL_CLIENTS = new LinkedHashMap<>();

    static {
        MANUAL_CLIENTS.put("codearts", "码道 CodeArts");
        MANUAL_CLIENTS.put("cursor", "Cursor");
        MANUAL_CLIENTS.put("other", "其他 AI 客户端");
    }

    private final OAuthProperties properties;
    private final OAuthSigner signer;
    private final OAuthClientRegistry clientRegistry;
    private final McpTokenService tokenService;
    private final SysLoginService loginService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @CrossOrigin(origins = "*")
    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ObjectNode> metadata() {
        String issuer = issuer();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/oauth/authorize");
        body.put("token_endpoint", issuer + "/oauth/token");
        body.put("registration_endpoint", issuer + "/oauth/register");
        body.put("revocation_endpoint", issuer + "/oauth/revoke");
        body.putArray("response_types_supported").add("code");
        body.putArray("grant_types_supported").add(GRANT_AUTHORIZATION_CODE).add(GRANT_TOKEN_EXCHANGE);
        body.putArray("code_challenge_methods_supported").add("S256");
        body.putArray("token_endpoint_auth_methods_supported").add("none").add("client_secret_basic");
        body.putArray("revocation_endpoint_auth_methods_supported").add("none");
        ArrayNode scopesSupported = body.putArray("scopes_supported");
        properties.supportedScopes().forEach(scopesSupported::add);
        body.put("authorization_response_iss_parameter_supported", true);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5))).body(body);
    }

    /**
     * RFC 7591 动态注册：一律注册为公开客户端，client_id 自带签名，不落库。
     */
    @CrossOrigin(origins = "*")
    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ObjectNode> register(@RequestBody JsonNode request) {
        List<String> redirectUris = new ArrayList<>();
        request.path("redirect_uris").forEach(node -> redirectUris.add(node.asText()));
        OAuthClientRegistry.RegisteredClient client =
                clientRegistry.register(request.path("client_name").asText(null), redirectUris);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", client.getClientId());
        body.put("client_id_issued_at", System.currentTimeMillis() / 1000);
        body.put("client_name", client.getClientName());
        body.set("redirect_uris", objectMapper.valueToTree(client.getRedirectUris()));
        body.putArray("grant_types").add(GRANT_AUTHORIZATION_CODE);
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        body.put("scope", properties.getScope());
        log.info("MCP OAuth 客户端注册: name={}, redirectUris={}", client.getClientName(), client.getRedirectUris());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/oauth/authorize")
    public String authorize(@RequestParam Map<String, String> params, ModelMap model, HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        OAuthClientRegistry.RegisteredClient client;
        try {
            client = clientRegistry.resolve(params.get("client_id"));
        } catch (OAuthException e) {
            return errorPage(model, "客户端无效，请在 AI 客户端中删除该 MCP 配置后重新连接。");
        }
        String redirectUri = params.get("redirect_uri");
        if (redirectUri == null && client.getRedirectUris().size() == 1) {
            redirectUri = client.getRedirectUris().get(0);
        }
        if (redirectUri == null || !client.getRedirectUris().contains(redirectUri)) {
            return errorPage(model, "回调地址与客户端注册信息不一致。");
        }
        String state = params.get("state");
        if (!"code".equals(params.get("response_type"))) {
            return "redirect:" + errorRedirect(redirectUri, state, "unsupported_response_type", "只支持 response_type=code");
        }
        String codeChallenge = params.get("code_challenge");
        if (codeChallenge == null || codeChallenge.isBlank() || !"S256".equals(params.get("code_challenge_method"))) {
            return "redirect:" + errorRedirect(redirectUri, state, "invalid_request", "必须使用 PKCE（code_challenge_method=S256）");
        }
        String resource = canonicalResource(params.get("resource"));
        if (resource == null) {
            return "redirect:" + errorRedirect(redirectUri, state, "invalid_target", "resource 不是本系统的 MCP 地址");
        }
        String scope = params.get("scope");
        String grantedScope = properties.getScope();
        if (scope != null && !scope.isBlank()) {
            List<String> requested = List.of(scope.trim().split("\\s+"));
            if (!properties.supportedScopes().containsAll(requested)) {
                return "redirect:" + errorRedirect(redirectUri, state, "invalid_scope", "不支持的 scope：" + scope);
            }
            grantedScope = String.join(" ", requested);
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("c", client.getClientId());
        request.put("n", client.getClientName());
        request.put("r", redirectUri);
        request.put("s", state);
        request.put("cc", codeChallenge);
        request.put("res", resource);
        request.put("sc", grantedScope);
        request.put("t", System.currentTimeMillis());
        return authorizePage(model, signer.encode(request.toString()), client.getClientName(), redirectUri, null, null, grantedScope);
    }

    @PostMapping("/oauth/authorize")
    public String authorizeSubmit(@RequestParam("request") String signedRequest,
                                  @RequestParam(value = "username", required = false) String username,
                                  @RequestParam(value = "password", required = false) String password,
                                  @RequestParam(value = "action", defaultValue = "approve") String action,
                                  HttpServletRequest httpRequest, HttpServletResponse response, ModelMap model) {
        response.setHeader("X-Frame-Options", "DENY");
        JsonNode request = readSignedRequest(signedRequest);
        if (request == null) {
            return errorPage(model, "授权请求无效或已超过 10 分钟，请回到 AI 客户端重新发起连接。");
        }
        String redirectUri = request.path("r").asText();
        String state = request.path("s").isNull() ? null : request.path("s").asText(null);
        if ("deny".equals(action)) {
            return "redirect:" + errorRedirect(redirectUri, state, "access_denied", "用户拒绝授权");
        }

        SysUser user;
        try {
            user = loginService.login(username, password);
        } catch (UserException e) {
            return authorizePage(model, signedRequest, request.path("n").asText(), redirectUri, username, e.getMessage(),
                    request.path("sc").asText(properties.getScope()));
        } catch (RuntimeException e) {
            log.warn("MCP OAuth 登录异常: {}", e.getMessage());
            return authorizePage(model, signedRequest, request.path("n").asText(), redirectUri, username, "登录失败，请稍后重试",
                    request.path("sc").asText(properties.getScope()));
        }

        String code = tokenService.issueCode(new AuthorizationCodeStore.AuthorizationCode(
                request.path("c").asText(), request.path("n").asText(), redirectUri, request.path("cc").asText(),
                request.path("sc").asText(properties.getScope()), request.path("res").asText(), user.getUserId(), IpUtils.getIpAddr(httpRequest)));
        log.info("MCP OAuth 授权: user={}, client={}", user.getLoginName(), request.path("n").asText());
        return "redirect:" + OAuthRedirects.withQuery(redirectUri, "code", code, "iss", issuer(), "state", state);
    }

    /**
     * 手动连接页：给不会自动弹出 OAuth 登录的客户端（如当前版本的码道桌面端）生成访问令牌和配置片段。
     */
    @GetMapping("/oauth/connect")
    public String connect(ModelMap model, HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        return connectPage(model, null, MANUAL_CLIENTS.keySet().iterator().next(), null);
    }

    @PostMapping("/oauth/connect")
    public String connectSubmit(@RequestParam(value = "username", required = false) String username,
                                @RequestParam(value = "password", required = false) String password,
                                @RequestParam(value = "client", required = false) String client,
                                HttpServletRequest httpRequest, HttpServletResponse response, ModelMap model) {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Pragma", "no-cache");
        String clientKey = MANUAL_CLIENTS.containsKey(client) ? client : MANUAL_CLIENTS.keySet().iterator().next();
        List<String> resources = properties.getMcpResourceUris();
        if (resources.isEmpty()) {
            return errorPage(model, "服务端未配置 MCP 地址（oauth.mcp-resource-uris），请联系管理员。");
        }

        SysUser user;
        try {
            user = loginService.login(username, password);
        } catch (UserException e) {
            return connectPage(model, username, clientKey, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP 手动令牌登录异常: {}", e.getMessage());
            return connectPage(model, username, clientKey, "登录失败，请稍后重试");
        }

        String resource = stripTrailingSlash(resources.get(0));
        String clientName = MANUAL_CLIENTS.get(clientKey) + "（手动令牌）";
        McpTokenService.IssuedToken issued =
                tokenService.issueManualToken(user, clientName, resource, IpUtils.getIpAddr(httpRequest));
        log.info("MCP 手动令牌签发: user={}, client={}", user.getLoginName(), clientName);

        String mcpUrl = resource + "/mcp";
        String authorization = "Bearer " + issued.getAccessToken();
        model.put("token", issued.getAccessToken());
        model.put("mcpUrl", mcpUrl);
        model.put("client", clientKey);
        model.put("clientLabel", MANUAL_CLIENTS.get(clientKey));
        model.put("loginName", user.getLoginName());
        model.put("userName", user.getUserName());
        model.put("expiresAt", new SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new Date(System.currentTimeMillis() + issued.getExpiresIn() * 1000)));
        model.put("codeartsDesktopConfig", prettyJson(codeartsServer(mcpUrl, authorization, "bridge")));
        ObjectNode cli = objectMapper.createObjectNode();
        cli.set("mcp", codeartsServer(mcpUrl, authorization, "bridge"));
        model.put("codeartsCliConfig", prettyJson(cli));
        ObjectNode cursor = objectMapper.createObjectNode();
        ObjectNode cursorServer = cursor.putObject("mcpServers").putObject("building-inspector");
        cursorServer.put("url", mcpUrl);
        cursorServer.putObject("headers").put("Authorization", authorization);
        model.put("cursorConfig", prettyJson(cursor));
        return "oauth/connect-result";
    }

    @CrossOrigin(origins = "*")
    @PostMapping(value = "/oauth/token", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ObjectNode> token(@RequestParam Map<String, String> params,
                                            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String grantType = params.get("grant_type");
        if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
            clientRegistry.resolve(params.get("client_id"));
            McpTokenService.IssuedToken issued = tokenService.exchangeCode(params.get("code"), params.get("client_id"),
                    params.get("redirect_uri"), params.get("code_verifier"), canonicalResourceOrRaw(params.get("resource")));
            ObjectNode body = objectMapper.createObjectNode();
            body.put("access_token", issued.getAccessToken());
            body.put("token_type", "Bearer");
            body.put("expires_in", issued.getExpiresIn());
            body.put("scope", issued.getScope());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
        }
        if (GRANT_TOKEN_EXCHANGE.equals(grantType)) {
            requireMcpServerClient(authorization);
            if (!TOKEN_TYPE_ACCESS_TOKEN.equals(params.get("subject_token_type"))) {
                throw new OAuthException("invalid_request", "subject_token_type 必须是 " + TOKEN_TYPE_ACCESS_TOKEN);
            }
            McpTokenService.ExchangedToken exchanged =
                    tokenService.exchangeForBiJwt(params.get("subject_token"), params.get("resource"));
            ObjectNode body = objectMapper.createObjectNode();
            body.put("access_token", exchanged.getBiJwt());
            body.put("issued_token_type", TOKEN_TYPE_JWT);
            body.put("token_type", "N_A");
            body.put("expires_in", exchanged.getExpiresIn());
            body.put("scope", exchanged.getRecord().getScope());
            body.put("user_id", exchanged.getUser().getUserId());
            body.put("login_name", exchanged.getUser().getLoginName());
            body.put("user_name", exchanged.getUser().getUserName());
            body.put("client_name", exchanged.getRecord().getClientName());
            body.put("subject_expires_at", exchanged.getRecord().getExpiresAt().getTime() / 1000);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        throw new OAuthException("unsupported_grant_type", "不支持的 grant_type：" + grantType);
    }

    @CrossOrigin(origins = "*")
    @PostMapping("/oauth/revoke")
    @ResponseBody
    public ResponseEntity<Void> revoke(@RequestParam(value = "token", required = false) String token) {
        tokenService.revoke(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(OAuthException.class)
    @ResponseBody
    public ResponseEntity<ObjectNode> handleOAuthException(OAuthException e) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", e.getError());
        body.put("error_description", e.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON);
        if (e.getStatus() == 401) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"bi-oauth\"");
        }
        return builder.body(body);
    }

    private String authorizePage(ModelMap model, String signedRequest, String clientName, String redirectUri,
                                 String username, String error, String scope) {
        model.put("request", signedRequest);
        model.put("clientName", clientName);
        model.put("redirectHost", describeRedirect(redirectUri));
        model.put("tokenDays", properties.getAccessTokenTtl().toDays());
        model.put("username", username);
        model.put("error", error);
        List<String> scopes = scope == null || scope.isBlank()
                ? properties.supportedScopes() : List.of(scope.trim().split("\\s+"));
        model.put("canWrite", scopes.contains("bridge:write"));
        return "oauth/authorize";
    }

    private String connectPage(ModelMap model, String username, String client, String error) {
        model.put("clients", MANUAL_CLIENTS);
        model.put("client", client);
        model.put("tokenDays", properties.getAccessTokenTtl().toDays());
        model.put("username", username);
        model.put("error", error);
        return "oauth/connect";
    }

    private ObjectNode codeartsServer(String mcpUrl, String authorization, String name) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode server = root.putObject(name);
        server.put("type", "remote");
        server.put("url", mcpUrl);
        server.put("enabled", true);
        server.putObject("headers").put("Authorization", authorization);
        server.put("oauth", false);
        return root;
    }

    private String prettyJson(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String errorPage(ModelMap model, String message) {
        model.put("message", message);
        return "oauth/error";
    }

    private JsonNode readSignedRequest(String signedRequest) {
        String json = signer.decode(signedRequest);
        if (json == null) {
            return null;
        }
        try {
            JsonNode request = objectMapper.readTree(json);
            long age = System.currentTimeMillis() - request.path("t").asLong();
            return age >= 0 && age <= properties.getAuthorizeRequestTtl().toMillis() ? request : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 把客户端传来的 resource 归一到配置中的 MCP 地址；允许带路径（如 /sse）。未传时取第一个配置。
     */
    private String canonicalResource(String requested) {
        List<String> allowed = properties.getMcpResourceUris();
        if (allowed.isEmpty()) {
            return null;
        }
        if (requested == null || requested.isBlank()) {
            return allowed.get(0);
        }
        String normalized = stripTrailingSlash(requested.trim());
        for (String candidate : allowed) {
            String base = stripTrailingSlash(candidate);
            if (normalized.equals(base) || normalized.startsWith(base + "/")) {
                return base;
            }
        }
        return null;
    }

    private String canonicalResourceOrRaw(String requested) {
        String canonical = canonicalResource(requested);
        return canonical != null ? canonical : requested;
    }

    private void requireMcpServerClient(String authorization) {
        String expectedSecret = properties.getMcpServerClientSecret();
        if (expectedSecret == null || expectedSecret.isBlank() || authorization == null || !authorization.startsWith("Basic ")) {
            throw new OAuthException("invalid_client", "令牌交换需要 MCP Server 客户端认证", 401);
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new OAuthException("invalid_client", "客户端认证格式错误", 401);
        }
        String expected = properties.getMcpServerClientId() + ":" + expectedSecret;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), decoded.getBytes(StandardCharsets.UTF_8))) {
            throw new OAuthException("invalid_client", "MCP Server 客户端认证失败", 401);
        }
    }

    private String errorRedirect(String redirectUri, String state, String error, String description) {
        return OAuthRedirects.withQuery(redirectUri, "error", error, "error_description", description, "iss", issuer(), "state", state);
    }

    private static String describeRedirect(String redirectUri) {
        try {
            URI uri = URI.create(redirectUri);
            return uri.getHost() != null ? uri.getScheme() + "://" + uri.getAuthority() : uri.getScheme() + "://";
        } catch (IllegalArgumentException e) {
            return redirectUri;
        }
    }

    private String issuer() {
        return stripTrailingSlash(properties.getIssuer());
    }

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
