package edu.whut.cs.bi.api.mapper;

import edu.whut.cs.bi.api.domain.McpToken;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP OAuth 访问令牌Mapper。
 */
public interface McpTokenMapper {

    int insertMcpToken(McpToken token);

    McpToken selectMcpTokenByHash(String tokenHash);

    List<McpToken> selectActiveMcpTokensByUserId(Long userId);

    int revokeMcpTokenByHash(String tokenHash);

    int revokeMcpTokenByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    int updateLastUsedAt(Long id);
}
