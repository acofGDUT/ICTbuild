package edu.whut.cs.bi.api.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 在已编码的回调地址后追加查询参数。不再用 UriComponentsBuilder.encode()，
 * 否则 workbuddy://...%3A... 会被编成 %253A，客户端收不到回调。
 */
public final class OAuthRedirects {

    private OAuthRedirects() {
    }

    public static String withQuery(String redirectUri, String... namesAndValues) {
        StringBuilder target = new StringBuilder(redirectUri);
        char separator = redirectUri.indexOf('?') >= 0 ? '&' : '?';
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            String value = namesAndValues[i + 1];
            if (value == null) {
                continue;
            }
            target.append(separator);
            separator = '&';
            target.append(encode(namesAndValues[i])).append('=').append(encode(value));
        }
        return target.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
