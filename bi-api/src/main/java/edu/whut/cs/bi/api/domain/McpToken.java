package edu.whut.cs.bi.api.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * MCP OAuth 访问令牌记录。数据库只保存令牌的 SHA-256 哈希。
 */
@Data
public class McpToken {

    private Long id;

    private String tokenHash;

    private Long userId;

    private String clientId;

    private String clientName;

    private String scope;

    private String resource;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expiresAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date revokedAt;

    private String createIp;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastUsedAt;
}
