package edu.whut.cs.bi.api.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import edu.whut.cs.bi.api.domain.McpToken;
import edu.whut.cs.bi.api.oauth.McpTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个人中心 → AI 授权管理：只能查看和撤销本人的 MCP 授权。
 */
@Controller
@RequestMapping("/system/mcpToken")
@RequiredArgsConstructor
public class McpTokenController extends BaseController {

    private final McpTokenService tokenService;

    @GetMapping("/list")
    @ResponseBody
    public AjaxResult list() {
        List<Map<String, Object>> rows = tokenService.listActiveTokens(getUserId()).stream()
                .map(McpTokenController::toRow)
                .collect(Collectors.toList());
        return AjaxResult.success(rows);
    }

    @Log(title = "AI 授权管理", businessType = BusinessType.FORCE)
    @PostMapping("/revoke/{id}")
    @ResponseBody
    public AjaxResult revoke(@PathVariable("id") Long id) {
        return tokenService.revokeById(id, getUserId()) ? success() : error("授权不存在或已撤销");
    }

    private static Map<String, Object> toRow(McpToken token) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", token.getId());
        row.put("clientName", token.getClientName());
        row.put("scope", token.getScope());
        row.put("createTime", token.getCreateTime());
        row.put("expiresAt", token.getExpiresAt());
        row.put("lastUsedAt", token.getLastUsedAt());
        row.put("createIp", token.getCreateIp());
        return row;
    }
}
