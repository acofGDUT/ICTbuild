package edu.whut.cs.bi.biz.service;

import edu.whut.cs.bi.biz.domain.BiEvaluation;

import java.util.List;

/**
 * 桥幅评定Service接口
 */
public interface IBiEvaluationService {
    /**
     * 查询桥幅评定
     *
     * @param id 桥幅评定ID
     * @return 桥幅评定
     */
    public BiEvaluation selectBiEvaluationById(Long id);

    /**
     * 查询桥幅评定列表
     *
     * @param biEvaluation 桥幅评定
     * @return 桥幅评定集合
     */
    public List<BiEvaluation> selectBiEvaluationList(BiEvaluation biEvaluation);

    /**
     * 查询任务的桥幅评定
     *
     * @param taskId 任务ID
     * @return 桥幅评定
     */
    public BiEvaluation selectBiEvaluationByTaskId(Long taskId);

    /**
     * 按任务批量查询评定。
     */
    public List<BiEvaluation> selectBiEvaluationByTaskIds(List<Long> taskIds);

    /**
     * 新增桥幅评定
     *
     * @param biEvaluation 桥幅评定
     * @return 结果
     */
    public int insertBiEvaluation(BiEvaluation biEvaluation);

    /**
     * 修改桥幅评定
     *
     * @param biEvaluation 桥幅评定
     * @return 结果
     */
    public int updateBiEvaluation(BiEvaluation biEvaluation);

    /**
     * 批量删除桥幅评定
     *
     * @param ids 需要删除的桥幅评定ID
     * @return 结果
     */
    public int deleteBiEvaluationByIds(String ids);

    /**
     * 删除桥幅评定信息
     *
     * @param id 桥幅评定ID
     * @return 结果
     */
    public int deleteBiEvaluationById(Long id);

    /**
     * 计算桥幅评定
     *
     * @param taskId 任务ID
     * @return 桥幅评定
     */
    public BiEvaluation calculateBiEvaluation(Long taskId, Long rootObjectId);
}