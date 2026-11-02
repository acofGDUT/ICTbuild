package edu.whut.cs.bi.api.evaluation;

import lombok.Data;

/**
 * 单座桥评定计算请求。
 */
@Data
public class EvaluationCalculateRequest {

    /** 任务 id，必填 */
    private Long taskId;

    /** 桥梁 id，可选，用于校验任务归属 */
    private Long bridgeId;
}