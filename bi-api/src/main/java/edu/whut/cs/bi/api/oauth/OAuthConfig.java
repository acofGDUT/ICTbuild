package edu.whut.cs.bi.api.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OAuthConfig {

    @Bean
    public OAuthSigner oauthSigner(OAuthProperties properties) {
        return new OAuthSigner(properties.getSigningSecret());
    }

    @Bean
    public OAuthClientRegistry oauthClientRegistry(OAuthSigner oauthSigner) {
        return new OAuthClientRegistry(oauthSigner);
    }

    @Bean
    public AuthorizationCodeStore authorizationCodeStore(OAuthProperties properties) {
        return new AuthorizationCodeStore(properties.getCodeTtl());
    }
}
