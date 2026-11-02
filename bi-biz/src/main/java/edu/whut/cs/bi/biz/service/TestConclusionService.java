package edu.whut.cs.bi.biz.service;

import edu.whut.cs.bi.biz.domain.BiEvaluation;
import edu.whut.cs.bi.biz.domain.Task;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.util.List;
import java.util.Map;

/**
 * @Author:wanzheng
 * @Date:2025/9/16 22:13
 * @Description:
 **/
public interface TestConclusionService {

    String NO_SUCH_COMPONENT_TEXT = "经检查，该桥无此构件。";

    /**
     * 处理第十章检测结论占位符
     *
     * @param document        Word文档
     * @param targetParagraph 目标段落（占位符段落）
     * @param bridgeName      桥梁名称
     */
    void handleTestConclusion(XWPFDocument document, XWPFParagraph targetParagraph,
                              List<Task> tasks, String bridgeName, Map<Long, BiEvaluation> biEvaluationMap, Integer minSystemLevel);

    /**
     * 处理第十章检测结论桥梁详情占位符
     *
     * @param document        Word文档
     * @param targetParagraph 目标段落（占位符段落）
     * @param tasks            任务信息
     */
    void handleTestConclusionBridge(XWPFDocument document, XWPFParagraph targetParagraph,
                                    List<Task> tasks);

    /**
     * 缓存病害汇总数据（供外部调用，在第三章处理时缓存）
     */
    void cacheDiseaseSummary(Long nodeId, String summary);

    /**
     * 清空当前线程的病害汇总缓存
     */
    void clearDiseaseSummaryCache();

    /**
     * 释放当前线程的病害汇总缓存。报告生成结束时必须调用，避免线程池复用串数据。
     */
    void removeDiseaseSummaryCache();

    /**
     * 2025 11.14
     * 单桥模板 的 新要求 ， 主要病害使用 缓存的外观检测的病害小结拼接。
     */
    Map<Long, String> getSummaryCache();

}