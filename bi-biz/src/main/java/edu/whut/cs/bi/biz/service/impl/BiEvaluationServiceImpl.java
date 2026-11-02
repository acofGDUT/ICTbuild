package edu.whut.cs.bi.biz.service.impl;

import com.ruoyi.common.utils.ShiroUtils;
import edu.whut.cs.bi.biz.domain.BiEvaluation;
import edu.whut.cs.bi.biz.domain.BiObject;
import edu.whut.cs.bi.biz.domain.Condition;
import edu.whut.cs.bi.biz.domain.Task;
import edu.whut.cs.bi.biz.mapper.BiEvaluationMapper;
import edu.whut.cs.bi.biz.service.IBiEvaluationService;
import edu.whut.cs.bi.biz.service.IBiObjectService;
import edu.whut.cs.bi.biz.service.IConditionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 桥梁技术状况评定Service业务层处理
 */
@Service
public class BiEvaluationServiceImpl implements IBiEvaluationService {
    @Autowired
    private BiEvaluationMapper biEvaluationMapper;

    @Autowired
    private IBiObjectService biObjectService;

    @Autowired
    private IConditionService conditionService;

    @Autowired
    private TaskServiceImpl taskService;

    private static BigDecimal superWeight;
    private static BigDecimal subWeight;
    private static BigDecimal deckWeight;

    @Override
    public BiEvaluation selectBiEvaluationById(Long id) {
        return biEvaluationMapper.selectBiEvaluationById(id);
    }

    @Override
    public List<BiEvaluation> selectBiEvaluationList(BiEvaluation biEvaluation) {
        return biEvaluationMapper.selectBiEvaluationList(biEvaluation);
    }

    @Override
    public BiEvaluation selectBiEvaluationByTaskId(Long taskId) {
        BiEvaluation biEvaluation = new BiEvaluation();
        biEvaluation.setTaskId(taskId);
        List<BiEvaluation> evaluations = selectBiEvaluationList(biEvaluation);
        return evaluations.isEmpty() ? null : evaluations.get(0);
    }

    @Override
    public List<BiEvaluation> selectBiEvaluationByTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ArrayList<>();
        }
        return biEvaluationMapper.selectBiEvaluationByTaskIds(taskIds);
    }

    @Override
    public int insertBiEvaluation(BiEvaluation biEvaluation) {
        return biEvaluationMapper.insertBiEvaluation(biEvaluation);
    }

    @Override
    public int updateBiEvaluation(BiEvaluation biEvaluation) {
        return biEvaluationMapper.updateBiEvaluation(biEvaluation);
    }

    @Override
    public int deleteBiEvaluationByIds(String ids) {
        return biEvaluationMapper.deleteBiEvaluationByIds(ids.split(","));
    }

    @Override
    public int deleteBiEvaluationById(Long id) {
        return biEvaluationMapper.deleteBiEvaluationById(id);
    }

    @Override
    @Transactional
    /**
     * 计算桥幅得分
     */
    public BiEvaluation calculateBiEvaluation(Long taskId, Long rootObjectId) {
        // 检查参数
        if (taskId == null || rootObjectId == null) {
            throw new RuntimeException("计算失败：任务ID或根对象ID为空");
        }

        Task task = taskService.selectTaskById(taskId);
        if (task == null) {
            throw new RuntimeException("计算失败：未找到任务信息");
        }

        Long projectId = task.getProjectId();
        if (projectId == null) {
            throw new RuntimeException("计算失败：任务未关联项目");
        }

        // 1. 获取或创建评定记录
        BiEvaluation biEvaluation = selectBiEvaluationByTaskId(taskId);
        if (biEvaluation == null) {
            biEvaluation = new BiEvaluation();
            biEvaluation.setTaskId(taskId);
            biEvaluation.setCreateBy(ShiroUtils.getLoginName());
            biEvaluation.setCreateTime(new Date());
            insertBiEvaluation(biEvaluation);
        }

        // 2. 获取所有第二层的节点
        List<BiObject> secondLevelObject = biObjectService.selectDirectChildrenByParentId(rootObjectId);
        if (secondLevelObject == null || secondLevelObject.isEmpty()) {
            throw new RuntimeException("计算失败：未找到桥梁结构的子节点");
        }

        // 重置权重变量，避免使用上次计算的值
        superWeight = null;
        subWeight = null;
        deckWeight = null;

        for (int i = 0; i < secondLevelObject.size(); i++) {
            BiObject object = secondLevelObject.get(i);
            if (object == null) {
                throw new RuntimeException("计算失败：子节点对象为空");
            }

            if (object.getWeight() != null) {
                calculatePartScore(object, biEvaluation, projectId);
            } else {
                throw new RuntimeException("计算失败：" + object.getName() + "的权重为空");
            }
        }

        // 3. 计算桥梁总体技术状况得分
        calculateOverallScore(biEvaluation);

        // 4. 更新评定记录
        biEvaluation.setUpdateBy(ShiroUtils.getLoginName());
        biEvaluation.setUpdateTime(new Date());
        updateBiEvaluation(biEvaluation);

        return biEvaluation;
    }

    /**
     * 计算部分（上部结构、下部结构、桥面系统）得分
     */
    private void calculatePartScore(BiObject part, BiEvaluation biEvaluation, Long projectId) {
        // 检查参数
        if (part == null) {
            throw new RuntimeException("计算失败：部件对象为空");
        }

        if (biEvaluation == null || biEvaluation.getId() == null) {
            throw new RuntimeException("计算失败：评定记录为空或ID为空");
        }

        if (projectId == null) {
            throw new RuntimeException("计算失败：项目ID为空");
        }

        // 获取该部分下所有孩子节点
        List<BiObject> leafNodes = biObjectService.selectDirectChildrenByParentId(part.getId());
        if (leafNodes == null || leafNodes.isEmpty()) {
            throw new RuntimeException("计算失败：" + part.getName() + "下没有子节点");
        }

        // 计算加权平均分
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedScore = BigDecimal.ZERO;
        Integer worstLevel = 1;
        boolean hasValidCondition = false;

        for (BiObject leaf : leafNodes) {
            if (leaf == null) {
                continue;
            }

            // 计算部件得分
            Condition condition = conditionService.calculateCondition(leaf, biEvaluation.getId(), projectId);
            if (condition != null) {
                hasValidCondition = true;

                BigDecimal weight = leaf.getWeight() != null ? leaf.getWeight() : BigDecimal.ZERO;
                totalWeight = totalWeight.add(weight);

                if (condition.getScore() == null) {
                    throw new RuntimeException("计算失败：" + leaf.getName() + "的评分为空");
                }

                weightedScore = weightedScore.add(condition.getScore().multiply(weight));

                // 更新最差等级
                if (condition.getLevel() > worstLevel) {
                    worstLevel = condition.getLevel();
                }
            }
        }

        if (!hasValidCondition) {
            throw new RuntimeException("计算失败：" + part.getName() + "下没有有效的评定数据");
        }

        // 计算最终得分，并以报告显示的一位小数作为正式得分
        BigDecimal finalScore = totalWeight.compareTo(BigDecimal.ZERO) > 0 ?
                weightedScore.divide(totalWeight, 2, RoundingMode.HALF_UP) : new BigDecimal("100");
        finalScore = finalScore.setScale(1, RoundingMode.HALF_UP);
        int level = calculateLevel(finalScore);

        // 设置得分和等级
        if (part.getName() == null) {
            throw new RuntimeException("计算失败：部件名称为空");
        }

        switch (part.getName()) {
            case "上部结构":
                biEvaluation.setSuperstructureScore(finalScore);
                biEvaluation.setSuperstructureLevel(level);
                superWeight = part.getWeight();
                break;
            case "下部结构":
                biEvaluation.setSubstructureScore(finalScore);
                biEvaluation.setSubstructureLevel(level);
                subWeight = part.getWeight();
                break;
            case "桥面系":
                biEvaluation.setDeckSystemScore(finalScore);
                biEvaluation.setDeckSystemLevel(level);
                deckWeight = part.getWeight();
                break;
        }
    }

    /**
     * 计算桥梁总体技术状况得分
     */
    private void calculateOverallScore(BiEvaluation biEvaluation) {
        if (biEvaluation == null) {
            throw new RuntimeException("计算失败：评定记录为空");
        }

        // 1. 计算系统总分
        BigDecimal superScore = biEvaluation.getSuperstructureScore();
        BigDecimal subScore = biEvaluation.getSubstructureScore();
        BigDecimal deckScore = biEvaluation.getDeckSystemScore();

        if (superScore == null || subScore == null || deckScore == null) {
            throw new RuntimeException("计算失败：上部结构、下部结构或桥面系统的得分为空");
        }

        if (superWeight == null || subWeight == null || deckWeight == null) {
            throw new RuntimeException("计算失败：数据异常，权重数据为空");
        }

        // 检查权重总和是否为1
        BigDecimal totalWeight = superWeight.add(subWeight).add(deckWeight);
        if (totalWeight.compareTo(BigDecimal.ONE) != 0) {
            throw new RuntimeException("计算失败：权重之和不等于1，当前总和为" + totalWeight);
        }

        BigDecimal systemScore = superScore.multiply(superWeight)
                .add(subScore.multiply(subWeight))
                .add(deckScore.multiply(deckWeight))
                .setScale(1, RoundingMode.HALF_UP);

        biEvaluation.setSystemScore(systemScore);

        // 2. 确定最差部位等级
        Integer superLevel = biEvaluation.getSuperstructureLevel();
        Integer subLevel = biEvaluation.getSubstructureLevel();
        Integer deckLevel = biEvaluation.getDeckSystemLevel();

        if (superLevel == null || subLevel == null || deckLevel == null) {
            throw new RuntimeException("计算失败：上部结构、下部结构或桥面系统的等级为空");
        }

        int worstLevel = Math.max(Math.max(superLevel, subLevel), deckLevel);
        biEvaluation.setWorstPartLevel(worstLevel);

        // 3. 设置系统等级（取决于系统得分）
        biEvaluation.setSystemLevel(calculateLevel(systemScore));
        //当上部结构和下部结构技术状况等级为3类、桥面系技术状况等级为4类，且桥梁总体技术状况评分为40<D<60时，桥梁总体技术状况等级应评定为3类。
        if (biEvaluation.getSuperstructureLevel() == 3 && biEvaluation.getSubstructureLevel() == 3 && biEvaluation.getDeckSystemLevel() == 4 && biEvaluation.getSystemLevel() == 4) {
            biEvaluation.setSystemLevel(3);
        }
    }

    /**
     * 根据得分计算技术状况等级
     */
    private Integer calculateLevel(BigDecimal score) {
        if (score.compareTo(new BigDecimal("95")) >= 0) {
            return 1;
        } else if (score.compareTo(new BigDecimal("80")) >= 0) {
            return 2;
        } else if (score.compareTo(new BigDecimal("60")) >= 0) {
            return 3;
        } else if (score.compareTo(new BigDecimal("40")) >= 0) {
            return 4;
        } else {
            return 5;
        }
    }
}