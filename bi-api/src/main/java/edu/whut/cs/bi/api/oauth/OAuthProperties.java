package edu.whut.cs.bi.api.oauth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP OAuth 授权服务器配置（oauth.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {

    /** 授权服务器对外地址，元数据中的 issuer 和各端点地址都基于它 */
    private String issuer;

    /** 允许签发令牌的 MCP 规范地址，第一个作为客户端未传 resource 时的默认值 */
    private List<String> mcpResourceUris = new ArrayList<>();

    /** MCP Server 做令牌交换时使用的机密客户端 */
    private String mcpServerClientId = "bi-mcp-server";

    private String mcpServerClientSecret;

    /** 用于签名 client_id 和授权页隐藏字段 */
    private String signingSecret;

    /**
     * 支持签发的 scope（空格分隔）。bridge:read 只读查询；bridge:write 允许创建报告、填写重点关注病害。
     * 客户端未请求 scope 时默认签发全部。
     */
    private String scope = "bridge:read bridge:write";

    /** 支持的 scope 列表 */
    public List<String> supportedScopes() {
        return List.of(scope.trim().split("\\s+"));
    }

    private Duration accessTokenTtl = Duration.ofDays(7);

    private Duration codeTtl = Duration.ofMinutes(5);

    /** 授权页从打开到提交的最长时间 */
    private Duration authorizeRequestTtl = Duration.ofMinutes(10);

    /** 令牌交换签发给 MCP Server 的 BI JWT 有效期 */
    private Duration downstreamJwtTtl = Duration.ofMinutes(10);
}
