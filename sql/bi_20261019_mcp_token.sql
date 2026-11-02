-- MCP OAuth 访问令牌（只保存令牌哈希）

CREATE TABLE IF NOT EXISTS `bi_mcp_token` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `token_hash` char(64) NOT NULL COMMENT '访问令牌 SHA-256 哈希',
  `user_id` bigint NOT NULL COMMENT '授权用户ID',
  `client_id` varchar(1024) NOT NULL COMMENT 'OAuth 客户端ID（签名编码）',
  `client_name` varchar(128) DEFAULT NULL COMMENT '客户端名称',
  `scope` varchar(128) NOT NULL COMMENT '授权范围',
  `resource` varchar(255) NOT NULL COMMENT '令牌绑定的 MCP 地址',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `revoked_at` datetime DEFAULT NULL COMMENT '撤销时间',
  `create_ip` varchar(128) DEFAULT NULL COMMENT '授权时IP',
  `create_time` datetime NOT NULL COMMENT '签发时间',
  `last_used_at` datetime DEFAULT NULL COMMENT '最近使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bi_mcp_token_hash` (`token_hash`),
  KEY `idx_bi_mcp_token_user` (`user_id`, `revoked_at`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='MCP OAuth 访问令牌';
