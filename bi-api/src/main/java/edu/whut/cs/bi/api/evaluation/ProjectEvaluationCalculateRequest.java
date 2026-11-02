package edu.whut.cs.bi.api.evaluation;

import lombok.Data;

import java.util.List;

/**
 * 项目批量评定计算请求。
 */
@Data
public class ProjectEvaluationCalculateRequest {

    /** 项目 id，必填 */
    private Long projectId;

    /** 只计算这些任务，可选 */
    private List<Long> taskIds;

    /** 只计算这些桥梁，可选 */
    private List<Long> bridgeIds;

    /** true 时跳过未提交检测数据的桥，默认 true */
    private Boolean onlySubmitted = Boolean.TRUE;
}