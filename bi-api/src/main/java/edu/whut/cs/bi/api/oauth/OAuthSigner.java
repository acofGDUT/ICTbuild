package edu.whut.cs.bi.api.oauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 把一段文本编码为“base64url(文本).签名”，用于无状态的 client_id 和授权页隐藏字段。
 */
public class OAuthSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;

    public OAuthSigner(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("oauth.signing-secret 至少需要 32 个字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String payload) {
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /**
     * 验签并返回原文；签名不匹配或格式错误时返回 null。
     */
    public String decode(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return null;
        }
        String body = value.substring(0, dot);
        String signature = value.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        try {
            return new String(DECODER.decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * PKCE S256：BASE64URL(SHA256(ASCII(code_verifier))) == code_challenge（RFC 7636 4.6）。
     */
    public static boolean verifyPkceS256(String codeVerifier, String codeChallenge) {
        if (codeVerifier == null || codeChallenge == null
                || codeVerifier.length() < 43 || codeVerifier.length() > 128
                || !codeVerifier.matches("[A-Za-z0-9\\-._~]+")) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String expected = ENCODER.encodeToString(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
