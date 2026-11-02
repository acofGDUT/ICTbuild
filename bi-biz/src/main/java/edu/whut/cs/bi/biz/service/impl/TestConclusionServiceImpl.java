package edu.whut.cs.bi.biz.service.impl;

import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.mapper.BiObjectMapper;
import edu.whut.cs.bi.biz.service.IBiEvaluationService;
import edu.whut.cs.bi.biz.service.IDiseaseService;
import edu.whut.cs.bi.biz.service.IReportService;
import edu.whut.cs.bi.biz.service.TestConclusionService;
import edu.whut.cs.bi.biz.utils.ReportGenerateTools;
import edu.whut.cs.bi.biz.utils.ReportTemplateValueUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STLineSpacingRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author:wanzheng
 * @Date:2025/9/16 22:14
 * @Description:
 **/
@Slf4j
@Service
public class TestConclusionServiceImpl implements TestConclusionService {

    @Autowired
    private IBiEvaluationService biEvaluationService;

    @Autowired
    private BiObjectMapper biObjectMapper;

    /**
     * 按生成线程隔离。报告走 reportTaskExecutor 并发时，
     * 全局 HashMap 会把不同桥的外观小结串进主要病害/检测结论。
     */
    private static final ThreadLocal<Map<Long, String>> DISEASE_SUMMARY_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    private static Map<Long, String> currentCache() {
        return DISEASE_SUMMARY_CACHE.get();
    }

    @Override
    public void handleTestConclusion(XWPFDocument document, XWPFParagraph targetParagraph,
                                     List<Task> tasks, String bridgeName,Map<Long, BiEvaluation> biEvaluationMap,Integer minSystemLevel) {
        try {
            log.info("开始处理第十章检测结论");

            if (biEvaluationMap == null) {
                log.warn("未找到任务的评定结果");
                // 清空占位符
                clearParagraphRuns(targetParagraph);
                XWPFRun run = targetParagraph.createRun();
                run.setText("未找到评定结果");
                return;
            }
            StringBuilder content = new StringBuilder();
            content.append("依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，");

            // 生成检测结论文本
            for(Task task:tasks){
                BiEvaluation evaluation = biEvaluationMap.get(task.getId());
                if (evaluation == null) {
                    log.warn("未找到任务的评定结果: taskId={}", task.getId());
                    continue;
                }
                // 拼接多个任务的结论
                content.append(task.getBuilding().getName())
                        .append("评定为")
                        .append(evaluation.getSystemLevel())
                        .append("类，");
            }
            content.append("全桥技术状况评定为")
                    .append(minSystemLevel)
                    .append("类。");

            // 清空占位符段落并填充内容
            clearParagraphRuns(targetParagraph);

            // 设置段落格式
            setupParagraphFormat(targetParagraph);

            // 添加结论文本
            XWPFRun run = targetParagraph.createRun();
            run.setText(content.toString());
            applyBodyRun(run, false);

            log.info("第十章检测结论处理完成");

        } catch (Exception e) {
            log.error("处理第十章检测结论失败", e);
            throw e;
        }
    }

    @Override
    public void handleTestConclusionBridge(XWPFDocument document, XWPFParagraph targetParagraph,
                                           List<Task> tasks) {
        try {
            log.info("开始处理第十章检测结论桥梁详情");

            // 获取桥梁根节点
            for (Task task : tasks) {
                Building building = task.getBuilding();
                if (building == null || building.getRootObjectId() == null) {
                    log.warn("未找到桥梁信息: taskId={}", task.getId());
                    return;
                }

                BiObject rootNode = biObjectMapper.selectBiObjectById(building.getRootObjectId());
                if (rootNode == null) {
                    log.warn("未找到桥梁根节点: rootObjectId={}", building.getRootObjectId());
                    return;
                }

                // 获取所有节点
                List<BiObject> allNodes = biObjectMapper.selectChildrenById(building.getRootObjectId());

                // 获取插入位置的游标
                XmlCursor cursor = targetParagraph.getCTP().newCursor();
                // 生成桥梁结构树（只到第二层，第二层开始添加内容）
                writeBridgeStructureTree(document, rootNode, allNodes, cursor, 1, building.getName());
            }
            // 清空占位符段落
            clearParagraphRuns(targetParagraph);

            log.info("第十章检测结论桥梁详情处理完成");

        } catch (Exception e) {
            log.error("处理第十章检测结论桥梁详情失败:}", e);
            throw e;
        }
    }

    /**
     * 写入桥梁结构树（参考第三章的writeBiObjectTreeToWord方法）
     */
    private void writeBridgeStructureTree(XWPFDocument document, BiObject node, List<BiObject> allNodes,
                                          XmlCursor cursor, int level,
                                          String bridgeName) {
        try {
            if (level > 2) {
                return; // 只处理到第三层
            }

            // 第一层：桥梁名称（检测结论的下一级，例如 3.11.1.1）
            if (level == 1) {
                XWPFParagraph bridgePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();

                bridgePara.setStyle("5");
                bridgePara.setAlignment(ParagraphAlignment.LEFT);

                XWPFRun bridgeRun = bridgePara.createRun();
                bridgeRun.setText(bridgeName);
                applyHeitiSmallFour(bridgeRun);

                // 递归处理子节点（第二层）
                List<BiObject> secondLevelNodes = ReportTemplateValueUtils.sortedReportChildren(allNodes, node.getId());

                for (BiObject secondNode : secondLevelNodes) {
                    if (("附属设施").equals(secondNode.getName())) {
                        continue;
                    }
                    writeBridgeStructureTree(document, secondNode, allNodes, cursor, level + 1, bridgeName);
                }
            }
            // 第二层：结构类型（上部结构、下部结构、桥面系，例如 3.11.1.1.1）
            else if (level == 2) {
                XWPFParagraph structurePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();

                structurePara.setStyle("6");
                structurePara.setAlignment(ParagraphAlignment.LEFT);

                XWPFRun structureRun = structurePara.createRun();
                structureRun.setText(node.getName());
                applyHeitiSmallFour(structureRun);

                // 处理第三层节点
                List<BiObject> thirdLevelNodes = ReportTemplateValueUtils.sortedReportChildren(allNodes, node.getId());

                int index = 1;
                for (BiObject thirdNode : thirdLevelNodes) {
                    writeThirdLevelContent(document, thirdNode, cursor, index);
                    index++;
                }
            }

        } catch (Exception e) {
            log.error("写入桥梁结构树失败: level={}, nodeName={}", level, node.getName(), e);
            throw e;
        }
    }

    /**
     * 写入第三层内容（构件级别）。每条单独成段，用首行缩进，不再用 Tab。
     */
    private void writeThirdLevelContent(XWPFDocument document, BiObject node,
                                        XmlCursor cursor, int index) {
        try {
            XWPFParagraph titlePara = insertTitleParagraph(document, cursor);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("（" + index + "）" + node.getName());
            applyHeitiSmallFour(titleRun);

            String diseaseSummary = getOrCreateDiseaseSummary(node.getId());
            if (NO_SUCH_COMPONENT_TEXT.equals(diseaseSummary == null ? null : diseaseSummary.trim())) {
                XWPFParagraph noComponentPara = insertBodyParagraph(document, cursor);
                XWPFRun noComponentRun = noComponentPara.createRun();
                noComponentRun.setText(NO_SUCH_COMPONENT_TEXT);
                applyBodyRun(noComponentRun, false);
            } else if (diseaseSummary != null && !diseaseSummary.trim().isEmpty()) {
                XWPFParagraph introPara = insertBodyParagraph(document, cursor);
                XWPFRun introRun = introPara.createRun();
                introRun.setText("经检查，" + node.getName() + "主要病害为：");
                applyBodyRun(introRun, false);

                String[] lines = diseaseSummary.split("\\r?\\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    XWPFParagraph diseasePara = insertBodyParagraph(document, cursor);
                    XWPFRun diseaseRun = diseasePara.createRun();
                    diseaseRun.setText(line.trim());
                    applyBodyRun(diseaseRun, false);
                }
            } else {
                XWPFParagraph noDiseasePara = insertBodyParagraph(document, cursor);
                XWPFRun noDiseaseRun = noDiseasePara.createRun();
                noDiseaseRun.setText("经检查，" + node.getName() + "未见明显病害。");
                applyBodyRun(noDiseaseRun, false);
            }
        } catch (Exception e) {
            log.error("写入第三层内容失败: nodeName={}", node.getName(), e);
            throw e;
        }
    }

    private XWPFParagraph insertBodyParagraph(XWPFDocument document, XmlCursor cursor) {
        XWPFParagraph paragraph = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        setupParagraphFormat(paragraph);
        return paragraph;
    }

    private XWPFParagraph insertTitleParagraph(XWPFDocument document, XmlCursor cursor) {
        XWPFParagraph paragraph = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        return paragraph;
    }

    private void applyBodyRun(XWPFRun run, boolean bold) {
        ReportGenerateTools.setMixedFontFamily(run, 24);
        run.setBold(bold);
    }

    private void applyHeitiSmallFour(XWPFRun run) {
        run.setBold(false);
        run.setColor("000000");
        run.setFontFamily("黑体");
        CTRPr rPr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        while (rPr.sizeOfRFontsArray() > 0) {
            rPr.removeRFonts(0);
        }
        while (rPr.sizeOfSzArray() > 0) {
            rPr.removeSz(0);
        }
        while (rPr.sizeOfSzCsArray() > 0) {
            rPr.removeSzCs(0);
        }
        CTFonts fonts = rPr.addNewRFonts();
        fonts.setAscii("黑体");
        fonts.setHAnsi("黑体");
        fonts.setEastAsia("黑体");
        fonts.setCs("黑体");
        rPr.addNewSz().setVal(BigInteger.valueOf(24));
        rPr.addNewSzCs().setVal(BigInteger.valueOf(24));
        while (rPr.sizeOfBArray() > 0) {
            rPr.removeB(0);
        }
        while (rPr.sizeOfBCsArray() > 0) {
            rPr.removeBCs(0);
        }
    }

    /**
     * 获取病害汇总（优先使用第三章缓存的结果）
     */
    private String getOrCreateDiseaseSummary(Long nodeId) {
        try {
            // 先从缓存查找（第三章处理时已经缓存了结果）
            String cached = currentCache().get(nodeId);
            if (cached != null) {
                log.info("使用第三章缓存的病害汇总: nodeId={}", nodeId);
                return cached;
            }

            // 如果第三章没有处理这个节点（例如没有病害），返回空白或简单描述
            log.warn("第三章未缓存节点{}的病害汇总，可能该节点无病害", nodeId);
            return "";

        } catch (Exception e) {
            log.error("获取病害汇总失败: nodeId={}", nodeId, e);
            return "病害信息获取失败";
        }
    }

    /**
     * 清空段落中的所有Run
     */
    private void clearParagraphRuns(XWPFParagraph paragraph) {
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }
    }

    /**
     * 正文：两端对齐、首行缩进 2 字符、1.5 倍行距。
     */
    private void setupParagraphFormat(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null && style.trim().matches("[1-9]")) {
            paragraph.setStyle(null);
        }
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        if (ppr.isSetOutlineLvl()) {
            ppr.unsetOutlineLvl();
        }
        CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(STJc.BOTH);
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        ind.setFirstLine(BigInteger.valueOf(480));
        ind.setLeft(BigInteger.valueOf(0));
    }

    /**
     * 缓存病害汇总数据（供外部调用，在第三章处理时缓存）
     */
    public void cacheDiseaseSummary(Long nodeId, String summary) {
        if (nodeId == null) {
            return;
        }
        currentCache().put(nodeId, summary);
        log.info("缓存节点{}的病害汇总: {}", nodeId, summary);
    }

    /**
     * 清空当前线程的病害汇总缓存
     */
    public void clearDiseaseSummaryCache() {
        currentCache().clear();
        log.info("清空病害汇总缓存");
    }

    @Override
    public void removeDiseaseSummaryCache() {
        DISEASE_SUMMARY_CACHE.remove();
    }

    @Override
    public Map<Long, String> getSummaryCache() {
        return new HashMap<>(currentCache());
    }
}
