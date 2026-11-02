package edu.whut.cs.bi.api.oauth;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权码只保存在进程内存中，按哈希索引，兑换一次即删除。
 */
public class AuthorizationCodeStore {

    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AuthorizationCodeStore(Duration ttl) {
        this.ttl = ttl;
    }

    public String issue(AuthorizationCode code) {
        removeExpired();
        String value = OAuthSigner.randomToken();
        code.expiresAtMillis = System.currentTimeMillis() + ttl.toMillis();
        codes.put(OAuthSigner.sha256Hex(value), code);
        return value;
    }

    /**
     * 取出并删除授权码；不存在、已使用或已过期时返回 null。
     */
    public AuthorizationCode consume(String value) {
        if (value == null) {
            return null;
        }
        AuthorizationCode code = codes.remove(OAuthSigner.sha256Hex(value));
        if (code == null || code.expiresAtMillis < System.currentTimeMillis()) {
            return null;
        }
        return code;
    }

    private void removeExpired() {
        long now = System.currentTimeMillis();
        codes.values().removeIf(code -> code.expiresAtMillis < now);
    }

    public static final class AuthorizationCode {
        final String clientId;
        final String clientName;
        final String redirectUri;
        final String codeChallenge;
        final String scope;
        final String resource;
        final Long userId;
        final String clientIp;
        long expiresAtMillis;

        public AuthorizationCode(String clientId, String clientName, String redirectUri, String codeChallenge,
                                 String scope, String resource, Long userId, String clientIp) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.scope = scope;
            this.resource = resource;
            this.userId = userId;
            this.clientIp = clientIp;
        }
    }
}
