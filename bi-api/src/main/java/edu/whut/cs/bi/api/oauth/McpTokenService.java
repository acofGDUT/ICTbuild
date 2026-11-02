package edu.whut.cs.bi.api.oauth;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.UserStatus;
import com.ruoyi.framework.jwt.utils.JwtUtils;
import com.ruoyi.system.service.ISysUserService;
import edu.whut.cs.bi.api.domain.McpToken;
import edu.whut.cs.bi.api.mapper.McpTokenMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * MCP 访问令牌的签发、兑换、交换与撤销。
 */
@Service
@RequiredArgsConstructor
public class McpTokenService {

    public static final String TOKEN_PREFIX = "bimcp_";
    public static final String MANUAL_CLIENT_ID = "manual";

    private final McpTokenMapper mcpTokenMapper;
    private final ISysUserService userService;
    private final AuthorizationCodeStore codeStore;
    private final OAuthProperties properties;

    public String issueCode(AuthorizationCodeStore.AuthorizationCode code) {
        return codeStore.issue(code);
    }

    /**
     * authorization_code 授权：校验授权码、回调地址、PKCE 与 resource 后签发访问令牌。
     */
    public IssuedToken exchangeCode(String code, String clientId, String redirectUri, String codeVerifier, String resource) {
        AuthorizationCodeStore.AuthorizationCode data = codeStore.consume(code);
        if (data == null) {
            throw new OAuthException("invalid_grant", "授权码无效、已使用或已过期");
        }
        if (!data.clientId.equals(clientId)) {
            throw new OAuthException("invalid_grant", "授权码不属于该客户端");
        }
        if (!data.redirectUri.equals(redirectUri)) {
            throw new OAuthException("invalid_grant", "redirect_uri 与授权请求不一致");
        }
        if (!OAuthSigner.verifyPkceS256(codeVerifier, data.codeChallenge)) {
            throw new OAuthException("invalid_grant", "code_verifier 校验失败");
        }
        if (resource != null && !resource.isBlank() && !resource.equals(data.resource)) {
            throw new OAuthException("invalid_target", "resource 与授权请求不一致");
        }
        SysUser user = requireActiveUser(data.userId);
        return createToken(user.getUserId(), data.clientId, data.clientName, data.scope, data.resource, data.clientIp);
    }

    /**
     * 手动令牌：给不会自动发起 OAuth 的客户端，用户在网页登录后复制到客户端的 Authorization 请求头。
     */
    public IssuedToken issueManualToken(SysUser user, String clientName, String resource, String clientIp) {
        requireActiveUser(user.getUserId());
        return createToken(user.getUserId(), MANUAL_CLIENT_ID, clientName, properties.getScope(), resource, clientIp);
    }

    private IssuedToken createToken(Long userId, String clientId, String clientName, String scope, String resource,
                                    String clientIp) {
        String token = TOKEN_PREFIX + OAuthSigner.randomToken();
        McpToken record = new McpToken();
        record.setTokenHash(OAuthSigner.sha256Hex(token));
        record.setUserId(userId);
        record.setClientId(clientId);
        record.setClientName(clientName);
        record.setScope(scope);
        record.setResource(resource);
        record.setExpiresAt(new Date(System.currentTimeMillis() + properties.getAccessTokenTtl().toMillis()));
        record.setCreateIp(clientIp);
        mcpTokenMapper.insertMcpToken(record);
        return new IssuedToken(token, properties.getAccessTokenTtl().getSeconds(), scope);
    }

    /**
     * 令牌交换：校验 MCP 访问令牌，签发短期 BI JWT 供 MCP Server 调用 /toolCallingApi。
     */
    public ExchangedToken exchangeForBiJwt(String subjectToken, String resource) {
        McpToken record = findActiveToken(subjectToken);
        if (resource != null && !resource.isBlank() && !resource.equals(record.getResource())) {
            throw new OAuthException("invalid_target", "令牌不属于该 MCP 地址");
        }
        SysUser user = requireActiveUser(record.getUserId());
        long ttlMillis = Math.min(properties.getDownstreamJwtTtl().toMillis(),
                record.getExpiresAt().getTime() - System.currentTimeMillis());
        String jwt = JwtUtils.createToken(user.getLoginName(), user.getPassword(), ttlMillis);
        mcpTokenMapper.updateLastUsedAt(record.getId());
        return new ExchangedToken(jwt, ttlMillis / 1000, user, record);
    }

    /**
     * RFC 7009：令牌不存在或已撤销也视为成功。
     */
    public void revoke(String token) {
        if (token != null && token.startsWith(TOKEN_PREFIX)) {
            mcpTokenMapper.revokeMcpTokenByHash(OAuthSigner.sha256Hex(token));
        }
    }

    public List<McpToken> listActiveTokens(Long userId) {
        return mcpTokenMapper.selectActiveMcpTokensByUserId(userId);
    }

    public boolean revokeById(Long id, Long userId) {
        return mcpTokenMapper.revokeMcpTokenByIdAndUserId(id, userId) > 0;
    }

    private McpToken findActiveToken(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new OAuthException("invalid_grant", "访问令牌无效");
        }
        McpToken record = mcpTokenMapper.selectMcpTokenByHash(OAuthSigner.sha256Hex(token));
        if (record == null || record.getRevokedAt() != null) {
            throw new OAuthException("invalid_grant", "访问令牌无效或已撤销");
        }
        if (record.getExpiresAt().getTime() <= System.currentTimeMillis()) {
            throw new OAuthException("invalid_grant", "访问令牌已过期");
        }
        return record;
    }

    private SysUser requireActiveUser(Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null || UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            throw new OAuthException("invalid_grant", "用户不存在");
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            throw new OAuthException("invalid_grant", "用户已停用");
        }
        return user;
    }

    @Getter
    @RequiredArgsConstructor
    public static class IssuedToken {
        private final String accessToken;
        private final long expiresIn;
        private final String scope;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ExchangedToken {
        private final String biJwt;
        private final long expiresIn;
        private final SysUser user;
        private final McpToken record;
    }
}
