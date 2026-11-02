package edu.whut.cs.bi.biz.mapper;

import edu.whut.cs.bi.biz.domain.BiEvaluation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Author:wanzheng
 * @Date:2025/4/17 14:31
 * @Description:
 **/
public interface BiEvaluationMapper {
    /**
     * 查询桥梁技术状况评定
     *
     * @param id ID
     * @return 桥梁技术状况评定
     */
    public BiEvaluation selectBiEvaluationById(Long id);

    /**
     * 按任务批量查询评定。一个任务可能有多条，调用方取最新一条。
     */
    public List<BiEvaluation> selectBiEvaluationByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 查询桥梁技术状况评定列表
     *
     * @param biEvaluation 桥梁技术状况评定
     * @return 桥梁技术状况评定集合
     */
    public List<BiEvaluation> selectBiEvaluationList(BiEvaluation biEvaluation);

    /**
     * 新增桥梁技术状况评定
     *
     * @param biEvaluation 桥梁技术状况评定
     * @return 结果
     */
    public int insertBiEvaluation(BiEvaluation biEvaluation);

    /**
     * 修改桥梁技术状况评定
     *
     * @param biEvaluation 桥梁技术状况评定
     * @return 结果
     */
    public int updateBiEvaluation(BiEvaluation biEvaluation);

    /**
     * 删除桥梁技术状况评定
     *
     * @param id ID
     * @return 结果
     */
    public int deleteBiEvaluationById(Long id);

    /**
     * 批量删除桥梁技术状况评定
     *
     * @param ids 需要删除的数据ID
     * @return 结果
     */
    public int deleteBiEvaluationByIds(String[] ids);
}