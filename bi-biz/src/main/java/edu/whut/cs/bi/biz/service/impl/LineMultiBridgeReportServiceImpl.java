package edu.whut.cs.bi.biz.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.whut.cs.bi.biz.config.MinioConfig;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.domain.dto.CauseQuery;
import edu.whut.cs.bi.biz.domain.enums.ReportTemplateTypes;
import edu.whut.cs.bi.biz.domain.temp.ComponentDiseaseType;
import edu.whut.cs.bi.biz.domain.vo.DiseaseComparisonData;
import edu.whut.cs.bi.biz.mapper.BiObjectMapper;
import edu.whut.cs.bi.biz.mapper.DiseaseDetailMapper;
import edu.whut.cs.bi.biz.mapper.DiseaseMapper;
import edu.whut.cs.bi.biz.mapper.ReportMapper;
import edu.whut.cs.bi.biz.service.*;
import edu.whut.cs.bi.biz.utils.CarbonizationAssessmentTableBuilder;
import edu.whut.cs.bi.biz.utils.FocusDiseaseSelection;
import edu.whut.cs.bi.biz.utils.DiseaseComparisonTableUtils;
import edu.whut.cs.bi.biz.utils.RebarCorrosionAssessmentTableBuilder;
import edu.whut.cs.bi.biz.utils.ReportGenerateTools;
import edu.whut.cs.bi.biz.utils.ReportTemplateValueUtils;
import edu.whut.cs.bi.biz.utils.WordFieldUtils;
import edu.whut.cs.bi.biz.utils.WordReportPageNumberUtils;
import edu.whut.cs.bi.biz.utils.WordSectionLayoutUtils;
import edu.whut.cs.bi.biz.utils.WordImportSupport;
import edu.whut.cs.bi.biz.utils.WordImportMerger;
import edu.whut.cs.bi.biz.utils.LineReportWordMatcher;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlip;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static edu.whut.cs.bi.biz.service.impl.ReportServiceImpl.compareCodes;

/**
 * 定期检查多桥报告Service实现。
 *
 * <p>桥梁章节的生成逻辑参照一级单桥报告实现并独立复制而来，之后两边各自演进、互不影响；
 * 定期检查记录表、桥梁卡片、评定表、检测结论等全系统通用服务仍然共用。</p>
 *
 * @author wanzheng
 */
@Slf4j
@Service
public class LineMultiBridgeReportServiceImpl implements ILineMultiBridgeReportService {
    /**
     * 多桥模板的桥梁章节沿用一级梁桥的占位符体系，向底层通用服务传递该类型。
     */
    private static final ReportTemplateTypes BRIDGE_CHAPTER_TEMPLATE_TYPE = ReportTemplateTypes.LEVEL_1_BEAM_BRIDGE;
    private static final String KEY_CONCERN_DISEASES_PLACEHOLDER = "${keyConcernDiseases}";
    private static final String KEY_CONCERN_SELECTION_KEY = "keyConcernSelection";
    private static final String CARBONIZATION_ASSESSMENT_PLACEHOLDER = "${carbonizationAssessment}";
    private static final String APPENDIX_BRIDGE_CARD_TASK_IDS_KEY = "appendixBridgeCardTaskIds";
    private static final String APPENDIX_EVALUATION_HISTORY_KEY = "appendixEvaluationHistory";
    private static final String APPEARANCE_IMAGE_SELECTION_KEY = "appearanceImageSelection";
    private static final String REPORT_HEADER_KEY = "line-report-header";
    private static final List<String> KEY_CONCERN_STRUCTURE_ORDER =
            List.of("上部结构", "下部结构", "桥面系", "附属设施");

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private ILineMultiBridgeReportDataService lineReportDataService;

    @Autowired
    private IFileMapService fileMapService;

    @Autowired
    private IProjectService projectService;

    @Autowired
    private IBuildingService buildingService;

    @Autowired
    private BiObjectMapper biObjectMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    @Autowired
    private IComponentService componentService;

    @Resource
    private IDiseaseService diseaseService;

    @Autowired
    private DiseaseMapper diseaseMapper;

    @Autowired
    private DiseaseDetailMapper diseaseDetailMapper;

    @Autowired
    private ProgrammaticDiseaseSummaryService programmaticDiseaseSummaryService;

    @Autowired
    private IBiEvaluationService biEvaluationService;

    @Autowired
    private IPropertyService propertyService;

    @Autowired
    private EvaluationTableService evaluationTableService;

    @Autowired
    private ComparisonAnalysisService comparisonAnalysisService;

    @Autowired
    private TestConclusionService testConclusionService;

    @Autowired
    private DiseaseComparisonService diseaseComparisonService;

    @Autowired
    private IBridgeCardService bridgeCardService;

    @Autowired
    private RegularInspectionService regularInspectionService;

    @Autowired
    private ITaskSheetService taskSheetService;

    @Resource
    private IBiTemplateObjectService biTemplateObjectService;

    @Override
    public boolean isMultiBridgeTemplate(ReportTemplate template) {
        return template != null && ReportTemplateTypes.isMultiBridge(template.getName());
    }

    @Override
    public List<Task> orderTasksBySelection(List<Long> taskIds, List<Task> tasks) {
        if (taskIds == null || tasks == null) {
            return tasks;
        }
        Map<Long, Task> taskById = tasks.stream()
                .collect(Collectors.toMap(Task::getId, task -> task, (left, right) -> left));
        return taskIds.stream()
                .map(taskById::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public String validateTasks(Report report, List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "多桥定期检查报告至少需要选择一座桥";
        }
        for (Task task : tasks) {
            Building building = task.getBuilding();
            if (building == null) {
                return "任务关联的建筑不存在：任务ID " + task.getId();
            }
            String bridgeName = building.getName() == null ? ("任务ID " + task.getId()) : building.getName();
            if (task.getProjectId() != null && report != null && report.getProjectId() != null
                    && !report.getProjectId().equals(task.getProjectId())) {
                return "所选桥梁不属于当前报告项目：" + bridgeName;
            }
            if (building.getRootPropertyId() == null) {
                return "桥梁【" + bridgeName + "】的桥梁信息卡片不存在,请通过excel导入";
            }
            Property query = new Property();
            query.setId(building.getRootPropertyId());
            List<Property> properties = propertyService.selectPropertyList(query);
            if (properties == null || properties.isEmpty()) {
                return "桥梁【" + bridgeName + "】的桥梁信息卡片不存在,请通过excel导入";
            }
            BiEvaluation evaluation = biEvaluationService.selectBiEvaluationByTaskId(task.getId());
            if (evaluation == null || evaluation.getSystemLevel() == null) {
                return "桥梁【" + bridgeName + "】未进行评定，请评定后再生成报告";
            }
        }
        return null;
    }

    /**
     * 生成多桥定期检查报告。
     *
     * <p>模板只加载一次。模板中的桥梁章节由 start/end 锚点标记，先在独立 Word 文档
     * 副本中按任务生成，再将生成后的章节按选择顺序插回主文档，从而避免多个桥梁共用
     * 同一份填报数据或相互覆盖占位符。</p>
     */
    @Override
    public String generateReportDocument(Report report, List<Task> tasks, ReportTemplate template, String operator) {
        InputStream templateStream = null;
        FileOutputStream out = null;
        File outputFile = null;
        XWPFDocument document = null;

        try {
            if (report == null || template == null || template.getMinioId() == null || tasks == null || tasks.isEmpty()) {
                log.error("多桥报告缺少报告、模板或任务");
                throw new IllegalArgumentException("多桥报告缺少报告、模板或任务");
            }

            FileMap fileMap = fileMapService.selectFileMapById(template.getMinioId());
            if (fileMap == null || fileMap.getNewName() == null) {
                log.error("未找到多桥模板文件");
                throw new IllegalStateException("未找到多桥模板文件");
            }

            String fileName = fileMap.getNewName();
            templateStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(fileName.substring(0, 2) + "/" + fileName)
                    .build());
            byte[] templateBytes = readAllBytes(templateStream);

            Project project = report.getProjectId() == null ? null : projectService.selectProjectById(report.getProjectId());
            List<LineReportData> allReportData = lineReportDataService.selectByReportId(report.getId());

            document = new XWPFDocument(new ByteArrayInputStream(templateBytes));
            WordSectionLayoutUtils.Layouts headerLayouts = WordSectionLayoutUtils.snapshot(document);
            WordFieldUtils.resetCounters();
            prepareTaskBuildings(tasks, project);
            List<LineBridgeGroup> groups = lineReportDataService.resolveEffectiveGroups(report.getId(), tasks);
            Map<Long, Task> taskById = tasks.stream()
                    .filter(task -> task != null && task.getId() != null)
                    .collect(Collectors.toMap(Task::getId, task -> task, (left, right) -> left));
            applyLineLevelData(document, allReportData, report, project, tasks.get(0));
            generateBridgeSummary(document, groups, taskById);

            XWPFParagraph masterStart = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-start}");
            XWPFParagraph masterEnd = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-end}");
            if (masterStart == null || masterEnd == null) {
                throw new IllegalStateException("多桥模板缺少桥梁章节 start/end 锚点");
            }

            // 清掉模板中的样例桥章节，只保留锚点和前后公共内容。
            removeBodyElementsBetween(document, masterStart, masterEnd);

            Map<String, BiEvaluation> importedPreviousEvaluations = loadImportedPreviousEvaluations(allReportData);
            for (LineBridgeGroup group : groups) {
                List<Task> groupTasks = resolveGroupTasks(group, taskById);
                if (groupTasks.isEmpty()) {
                    log.warn("跳过没有子桥的大桥: {}", group == null ? null : group.getName());
                    continue;
                }

                XWPFDocument bridgeDocument = new XWPFDocument(new ByteArrayInputStream(templateBytes));
                try {
                    WordFieldUtils.resetCounters();
                    String groupId = group.getId();
                    List<LineReportData> groupData = allReportData.stream()
                            .filter(data -> groupId != null && groupId.equals(data.getGroupId()))
                            .collect(Collectors.toList());

                    applyGroupIdentity(bridgeDocument, group, groupTasks.get(0).getBuilding(), project);
                    processGroupChapter(bridgeDocument, group, groupTasks, project, groupData, templateBytes,
                            importedPreviousEvaluations);

                    WordFieldUtils.updateAllFields(bridgeDocument);
                    bridgeDocument.getSettings().setUpdateFields();
                    insertBodyElementCopies(document, masterEnd, bridgeDocument,
                            copyBridgeBodyElements(bridgeDocument));
                } finally {
                    bridgeDocument.close();
                }
            }

            // 锚点只用于服务端定位，不能出现在最终报告中。
            removeParagraph(document, masterStart);
            // 删除 masterStart 后位置索引会变化，按占位符重新定位更安全。
            removeParagraphByPlaceholder(document, "${line.bridge-block-end}");
            replaceText(document, "${line.bridge-block-start}", "");
            replaceText(document, "${line.bridge-block-end}", "");
            updateTocPreview(document, groups);
            WordFieldUtils.updateAllFields(document);
            document.getSettings().setUpdateFields();
            applyDocumentHeadingStyles(document);
            WordSectionLayoutUtils.rebindHeadersByOrientation(document, headerLayouts);
            WordSectionLayoutUtils.continueBodyPageNumbers(document);
            applyReportHeader(document, allReportData, report);
            WordReportPageNumberUtils.useLastBodyPageForFooterTotal(document);

            outputFile = File.createTempFile("line_multi_bridge_report_" + report.getId(), ".docx");
            out = new FileOutputStream(outputFile);
            document.write(out);
            out.close();

            // Insert source XML last so existing global font/heading passes cannot erase its formatting.
            mergeImportedWordContent(outputFile, allReportData, groups);

            FileMap reportFileMap = fileMapService.handleFileUploadFromFile(
                    outputFile,
                    report.getName() + "_" + System.currentTimeMillis() + ".docx",
                    operator);
            report.setMinioId(Long.valueOf(reportFileMap.getId()));
            report.setStatus(1);
            reportMapper.updateReport(report);
            return reportFileMap.getId().toString();
        } catch (Exception e) {
            log.error("生成多桥报告失败", e);
            throw new IllegalStateException("生成多桥报告失败：" + e.getMessage(), e);
        } finally {
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
            try {
                if (templateStream != null) {
                    templateStream.close();
                }
                if (out != null) {
                    out.close();
                }
                if (document != null) {
                    document.close();
                }
                if (outputFile != null && outputFile.exists() && !outputFile.delete()) {
                    log.warn("多桥报告临时文件删除失败: {}", outputFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("清理多桥报告资源失败", e);
            }
        }
    }

    private void mergeImportedWordContent(File output, List<LineReportData> rows, List<LineBridgeGroup> groups) throws Exception {
        Set<String> groupIds = groups.stream().map(LineBridgeGroup::getId).collect(Collectors.toSet());
        Map<Long, java.nio.file.Path> sources = new HashMap<>();
        List<WordImportMerger.Item> imports = new ArrayList<>();
        try {
            for (LineReportData row : rows) {
                if (!WordImportSupport.isImported(row) || (row.getGroupId() != null && !groupIds.contains(row.getGroupId()))) continue;
                WordImportSupport.Reference ref = WordImportSupport.reference(row);
                java.nio.file.Path source = sources.get(ref.getFileId());
                if (source == null) {
                    FileMap stored = fileMapService.selectFileMapById(ref.getFileId());
                    if (stored == null) throw new IllegalStateException("Word 来源文件已丢失：" + ref.getSourceName());
                    source = java.nio.file.Files.createTempFile("word-import-source-", ".docx");
                    sources.put(ref.getFileId(), source);
                    String name = stored.getNewName();
                    try (InputStream input = minioClient.getObject(GetObjectArgs.builder().bucket(minioConfig.getBucketName())
                            .object(name.substring(0, 2) + "/" + name).build())) {
                        java.nio.file.Files.copy(input, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                boolean dropElevationPhotos = "overallOverview".equals(row.getKey())
                        && hasUploadedOverviewPhoto(rows, row.getGroupId());
                imports.add(new WordImportMerger.Item(WordImportSupport.marker(row), source, ref.getStart(), ref.getEnd(),
                        dropElevationPhotos));
            }
            WordImportMerger.merge(output.toPath(), imports);
        } finally {
            for (java.nio.file.Path source : sources.values()) java.nio.file.Files.deleteIfExists(source);
        }
    }

    /**
     * 处理一座大桥的章节内容，只操作传入的章节文档，不负责下载或上传。
     */
    private void processGroupChapter(XWPFDocument document, LineBridgeGroup group, List<Task> groupTasks,
                                     Project project, List<LineReportData> groupData, byte[] templateBytes,
                                     Map<String, BiEvaluation> importedPreviousEvaluations) {
        if (document == null || groupTasks == null || groupTasks.isEmpty()) {
            return;
        }
        Task firstTask = groupTasks.get(0);
        try {
            if (project == null && firstTask.getProjectId() != null) {
                project = projectService.selectProjectById(firstTask.getProjectId());
            }
            if (project == null) {
                log.warn("大桥章节未找到项目: groupId={}", group == null ? null : group.getId());
                return;
            }

            List<ReportData> chapterData = groupData == null ? Collections.emptyList() : new ArrayList<>(groupData);
            Map<String, ReportData> dataMap = new HashMap<>();
            for (ReportData data : chapterData) {
                if (data != null && data.getKey() != null) {
                    dataMap.put(data.getKey(), data);
                }
            }

            Calendar calendar = Calendar.getInstance();
            ReportGenerateTools.replaceText(document, "${year}", String.valueOf(calendar.get(Calendar.YEAR)));
            ReportGenerateTools.replaceText(document, "${month}", String.valueOf(calendar.get(Calendar.MONTH) + 1));
            ReportGenerateTools.replaceText(document, "${day}", String.valueOf(calendar.get(Calendar.DAY_OF_MONTH)));
            String groupName = groupDisplayName(group, firstTask);
            ReportGenerateTools.replaceText(document, "${buildingName}", groupName);
            if (project.getName() != null) {
                ReportGenerateTools.replaceText(document, "${project-name}", project.getName());
            }
            if (project.getDept() != null && project.getDept().getDeptName() != null) {
                ReportGenerateTools.replaceText(document, "${client-unit}", project.getDept().getDeptName());
            }

            testConclusionService.clearDiseaseSummaryCache();
            WordSectionLayoutUtils.Layouts headerLayouts = WordSectionLayoutUtils.snapshot(document);

            boolean importedOverview = WordImportSupport.isImported(dataMap.get("overallOverview"));
            boolean replaceImportedPhotos = importedOverview && hasUploadedOverviewPhoto(dataMap);
            if (importedOverview) WordImportSupport.prepare(document, dataMap.get("overallOverview"), replaceImportedPhotos);
            else replaceOverallOverviewInBridgeBlock(document, valueOf(dataMap, "overallOverview"));
            removeTechnicalStandardInBridgeBlock(document);
            if (!isEnabledFlag(valueOf(dataMap, "includePierTiltChapter"))) {
                removeHeadingSection(document, "空间变位检测结果");
                removeHeadingSection(document, "墩台倾斜度");
            }
            if (!importedOverview || replaceImportedPhotos) insertGroupOverviewImages(document, dataMap);

            XWPFParagraph appendixHeading = findBridgeBlockHeading(document, "附表");
            XWPFParagraph blockEnd = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-end}");
            if (appendixHeading != null && blockEnd != null) {
                removeBodyElementsBetween(document, appendixHeading, blockEnd);
            }

            insertHeadingBeforePlaceholder(document, "${testConclusion}", "检测结论", 4);
            insertHeadingBeforePlaceholder(document, "${technicalAdvice}", "技术建议", 4);
            processGroupAutoGeneratedContent(document, groupName, project, groupTasks, dataMap,
                    importedPreviousEvaluations);
            processGroupUserData(document, chapterData, groupTasks, dataMap, project);
            fillAppendixForGroup(document, groupTasks, project, templateBytes, groupData);
            WordSectionLayoutUtils.rebindHeadersByOrientation(document, headerLayouts);
            WordFieldUtils.updateAllFields(document);
        } catch (Exception e) {
            log.error("处理大桥章节失败: groupId={}", group == null ? null : group.getId(), e);
        }
    }

    private void prepareTaskBuildings(List<Task> tasks, Project project) {
        if (tasks == null) {
            return;
        }
        for (Task task : tasks) {
            if (task == null) {
                continue;
            }
            task.setProject(project);
            if (task.getBuilding() == null && task.getBuildingId() != null) {
                task.setBuilding(buildingService.selectBuildingById(task.getBuildingId()));
            }
            hydrateBuildingIdentityFromProperties(task.getBuilding());
        }
    }

    /**
     * 批量任务查询只装载 bi_building 的持久化字段，路线编号、路线名称、桥位桩号等
     * 报告字段实际保存在桥梁属性卡中。生成模板前先把这些值补到 Building，避免随后
     * 用空值清掉占位符。
     */
    private void hydrateBuildingIdentityFromProperties(Building building) {
        if (building == null || building.getRootPropertyId() == null) {
            return;
        }
        Property query = new Property();
        query.setId(building.getRootPropertyId());
        List<Property> properties = propertyService.selectPropertyList(query);
        if (properties == null || properties.isEmpty()) {
            return;
        }
        ReportTemplateValueUtils.addAliasProperties(properties);
        building.setBuildingCode(firstNonBlank(building.getBuildingCode(),
                propertyValue(properties, "桥梁编号"), propertyValue(properties, "桥梁代码")));
        building.setRouteCode(firstNonBlank(building.getRouteCode(), propertyValue(properties, "路线编号")));
        building.setRouteName(firstNonBlank(building.getRouteName(), propertyValue(properties, "路线名称")));
        building.setBridgePileNumber(firstNonBlank(building.getBridgePileNumber(),
                propertyValue(properties, "桥位桩号"), propertyValue(properties, "桥梁中心桩号")));
        building.setBridgeLength(firstNonBlank(building.getBridgeLength(),
                propertyValue(properties, "桥梁全长(m)"), propertyValue(properties, "桥梁全长")));
    }

    private String propertyValue(List<Property> properties, String name) {
        if (properties == null || name == null) {
            return null;
        }
        return properties.stream()
                .filter(Objects::nonNull)
                .filter(property -> name.equals(property.getName()))
                .map(Property::getValue)
                .filter(value -> !isBlank(value))
                .findFirst()
                .orElse(null);
    }

    private List<Task> resolveGroupTasks(LineBridgeGroup group, Map<Long, Task> taskById) {
        if (group == null || group.getTaskIds() == null || taskById == null) {
            return Collections.emptyList();
        }
        return group.getTaskIds().stream()
                .map(taskById::get)
                .filter(task -> task != null && task.getBuilding() != null)
                .collect(Collectors.toList());
    }

    private String groupDisplayName(LineBridgeGroup group, Task fallbackTask) {
        if (group != null && group.getName() != null && !group.getName().trim().isEmpty()) {
            return group.getName().trim();
        }
        if (fallbackTask != null && fallbackTask.getBuilding() != null) {
            return safeText(fallbackTask.getBuilding().getName());
        }
        return "桥梁";
    }

    /**
     * 3.1 整体概况改为填报正文，不再用桥梁卡片自动拼句。
     */
    private void replaceOverallOverviewInBridgeBlock(XWPFDocument document, String overview) {
        XWPFParagraph heading = findBridgeBlockHeading(document, "整体概况");
        if (heading == null) {
            replacePlaceholderWithBodyFormat(document, "${overallOverview}", overview);
            return;
        }
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        int headingIndex = paragraphs.indexOf(heading);
        if (headingIndex < 0 || headingIndex + 1 >= paragraphs.size()) {
            replacePlaceholderWithBodyFormat(document, "${overallOverview}", overview);
            return;
        }
        XWPFParagraph nextParagraph = paragraphs.get(headingIndex + 1);
        String nextText = safeParagraphText(nextParagraph);
        if (nextText.contains("${桥梁名称}") || nextText.contains("${overallOverview}")
                || nextText.contains("位于") || isBlank(nextText)) {
            writeFilledBodyIntoParagraph(document, nextParagraph, overview);
            replaceText(document, "${overallOverview}", "");
            return;
        }
        replacePlaceholderWithBodyFormat(document, "${overallOverview}", overview);
    }

    /**
     * 技术标准整节去掉，不放进 3.1，也不挪到第一章。
     */
    private void removeTechnicalStandardInBridgeBlock(XWPFDocument document) {
        XWPFParagraph heading = findBridgeBlockHeading(document, "技术标准");
        if (heading != null) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            int headingIndex = paragraphs.indexOf(heading);
            XWPFParagraph valueParagraph = headingIndex >= 0 && headingIndex + 1 < paragraphs.size()
                    ? paragraphs.get(headingIndex + 1) : null;
            if (valueParagraph != null && safeParagraphText(valueParagraph).contains("${standard}")) {
                removeParagraph(document, valueParagraph);
            }
            removeParagraph(document, heading);
        }
        replaceText(document, "${standard}", "");
    }

    /**
     * 删掉桥梁章节里某个标题及其下级内容，直到同级或更高级标题。
     */
    private void removeHeadingSection(XWPFDocument document, String headingText) {
        XWPFParagraph heading = findBridgeBlockSectionHeading(document, headingText);
        if (heading == null) {
            return;
        }
        int start = document.getPosOfParagraph(heading);
        if (start < 0) {
            return;
        }
        int level = resolveHeadingLevel(heading);
        List<IBodyElement> elements = document.getBodyElements();
        int end = elements.size();
        for (int i = start + 1; i < elements.size(); i++) {
            IBodyElement element = elements.get(i);
            if (!(element instanceof XWPFParagraph paragraph)) {
                continue;
            }
            String text = safeParagraphText(paragraph);
            if (text.contains("${line.bridge-block-end}")) {
                end = i;
                break;
            }
            int nextLevel = resolveHeadingLevel(paragraph);
            if (nextLevel > 0 && (level <= 0 || nextLevel <= level) && !text.isEmpty()) {
                end = i;
                break;
            }
        }
        for (int i = end - 1; i >= start; i--) {
            document.removeBodyElement(i);
        }
    }

    private boolean isEnabledFlag(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "是".equals(normalized);
    }

    private XWPFParagraph findBridgeBlockSectionHeading(XWPFDocument document, String headingText) {
        boolean inBridgeBlock = false;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = safeParagraphText(paragraph);
            if (text.contains("${line.bridge-block-start}")) {
                inBridgeBlock = true;
                continue;
            }
            if (text.contains("${line.bridge-block-end}")) {
                break;
            }
            if (inBridgeBlock && resolveHeadingLevel(paragraph) > 0 && matchesSectionTitle(text, headingText)) {
                return paragraph;
            }
        }
        return null;
    }

    private boolean matchesSectionTitle(String text, String headingText) {
        if (text.equals(headingText)) {
            return true;
        }
        if (!text.startsWith(headingText)) {
            return false;
        }
        String rest = text.substring(headingText.length()).trim();
        return rest.isEmpty() || rest.startsWith("（") || rest.startsWith("(");
    }

    private XWPFParagraph findBridgeBlockHeading(XWPFDocument document, String headingText) {
        boolean inBridgeBlock = false;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = safeParagraphText(paragraph);
            if (text.contains("${line.bridge-block-start}")) {
                inBridgeBlock = true;
                continue;
            }
            if (text.contains("${line.bridge-block-end}")) {
                break;
            }
            if (inBridgeBlock && headingText.equals(text)) {
                return paragraph;
            }
        }
        return null;
    }

    private String valueOf(Map<String, ReportData> dataMap, String key) {
        ReportData data = dataMap == null ? null : dataMap.get(key);
        return data == null ? null : data.getValue();
    }

    private String safeParagraphText(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return "";
        }
        String text = paragraph.getText();
        return text == null ? "" : text.trim();
    }

    /**
     * 填充线路级内容：封面、项目信息以及第一章由用户填报的线路级数据。
     */
    private void applyLineLevelData(XWPFDocument document, List<LineReportData> reportDataList,
                                    Report report, Project project, Task firstTask) {
        Calendar calendar = Calendar.getInstance();
        replaceText(document, "${year}", String.valueOf(calendar.get(Calendar.YEAR)));
        replaceText(document, "${month}", String.valueOf(calendar.get(Calendar.MONTH) + 1));
        replaceText(document, "${day}", String.valueOf(calendar.get(Calendar.DAY_OF_MONTH)));
        replaceText(document, "${line-report-title}", report == null ? "" : safeText(report.getName()));
        if (project != null) {
            replaceText(document, "${project-name}", safeText(project.getName()));
            if (project.getDept() != null) {
                replaceText(document, "${client-unit}", safeText(project.getDept().getDeptName()));
                replaceText(document, "${line-client-unit}", safeText(project.getDept().getDeptName()));
            }
        }
        if (firstTask != null && firstTask.getBuilding() != null) {
            Building building = firstTask.getBuilding();
            replaceText(document, "${路线名称}", safeText(building.getRouteName()));
            replaceText(document, "${路线编号}", safeText(building.getRouteCode()));
        }
        if (reportDataList == null) {
            return;
        }
        String routeOverview = null;
        String projectOverview = null;
        boolean importedRoute = false;
        for (LineReportData data : reportDataList) {
            if (data.getTaskId() != null || !isBlank(data.getGroupId()) || data.getKey() == null) {
                continue;
            }
            if (LineBridgeGroup.DATA_KEY.equals(data.getKey())) {
                continue;
            }
            if (data.getType() != null && data.getType() == 1) {
                continue;
            }
            String key = data.getKey();
            if (REPORT_HEADER_KEY.equals(key)) {
                // 页眉最后统一替换，不套用填报正文的字体和段落格式。
                continue;
            }
            if (WordImportSupport.isImported(data)) {
                WordImportSupport.prepare(document, data);
                if ("line-route-overview".equals(key)) importedRoute = true;
                continue;
            }
            if ("line-route-overview".equals(key)) {
                routeOverview = data.getValue();
                continue;
            }
            if ("line-project-overview".equals(key)) {
                projectOverview = data.getValue();
                continue;
            }
            String placeholder = key.startsWith("${") ? key : "${" + key + "}";
            replacePlaceholderWithBodyFormat(document, placeholder, data.getValue());
        }
        String overview = firstNonBlank(routeOverview, projectOverview);
        if (!importedRoute && !isBlank(overview)) {
            replacePlaceholderWithBodyFormat(document, "${line-route-overview}", overview);
            replacePlaceholderWithBodyFormat(document, "${line-project-overview}", overview);
        }
    }

    /** 线路级页眉在分节与桥梁章节生成完成后替换，保留各页眉的模板格式。 */
    private void applyReportHeader(XWPFDocument document, List<LineReportData> reportDataList, Report report) {
        String headerText = null;
        if (reportDataList != null) {
            for (LineReportData data : reportDataList) {
                if (data != null && data.getTaskId() == null && isBlank(data.getGroupId())
                        && REPORT_HEADER_KEY.equals(data.getKey())
                        && (data.getType() == null || data.getType() == 0)) {
                    headerText = data.getValue();
                    break;
                }
            }
        }
        String value = firstNonBlank(headerText, report == null ? null : report.getName());
        replaceReportHeaderText(document, "${" + REPORT_HEADER_KEY + "}", safeText(value));
    }

    /** 仅替换页眉文本节点，跨 run 占位符、表格和文本框均可处理，字体/图片/段落属性不变。 */
    private void replaceReportHeaderText(XWPFDocument document, String placeholder, String value) {
        String namespace = "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' ";
        for (XWPFHeader header : document.getHeaderList()) {
            List<XmlObject> paragraphs = new ArrayList<>();
            for (XWPFParagraph paragraph : header.getParagraphs()) {
                paragraphs.add(paragraph.getCTP());
            }
            Collections.addAll(paragraphs, header._getHdrFtr().selectPath(namespace + ".//w:p"));
            for (XmlObject paragraph : paragraphs) {
                XmlObject[] nodes = paragraph.selectPath(namespace + ".//w:t");
                List<String> texts = new ArrayList<>();
                int[] offsets = new int[nodes.length + 1];
                StringBuilder original = new StringBuilder();
                for (int i = 0; i < nodes.length; i++) {
                    try (XmlCursor cursor = nodes[i].newCursor()) {
                        String text = safeText(cursor.getTextValue());
                        texts.add(text);
                        original.append(text);
                        offsets[i + 1] = original.length();
                    }
                }
                boolean replaced = false;
                // 从后往前替换，避免同一段落多次出现占位符时字符位置偏移。
                for (int start = original.lastIndexOf(placeholder); start >= 0;
                     start = original.lastIndexOf(placeholder, start - 1)) {
                    int end = start + placeholder.length();
                    boolean inserted = false;
                    for (int i = 0; i < nodes.length; i++) {
                        if (offsets[i + 1] <= start || offsets[i] >= end) {
                            continue;
                        }
                        String text = texts.get(i);
                        int from = Math.max(0, start - offsets[i]);
                        int to = Math.min(offsets[i + 1] - offsets[i], end - offsets[i]);
                        texts.set(i, text.substring(0, from) + (inserted ? "" : value) + text.substring(to));
                        inserted = true;
                    }
                    replaced = true;
                }
                if (replaced) {
                    for (int i = 0; i < nodes.length; i++) {
                        try (XmlCursor cursor = nodes[i].newCursor()) {
                            cursor.setTextValue(texts.get(i));
                        }
                    }
                }
            }
        }
    }

    /**
     * 填充一座大桥的身份信息，桥梁章节副本内的同名占位符都指向这一座大桥。
     */
    private void applyGroupIdentity(XWPFDocument document, LineBridgeGroup group, Building firstBuilding, Project project) {
        String groupName = groupDisplayName(group, null);
        if (isBlank(groupName) && firstBuilding != null) {
            groupName = safeText(firstBuilding.getName());
        }
        replaceText(document, "${桥梁名称}", safeText(groupName));
        replaceText(document, "${building-name}", safeText(groupName));
        replaceText(document, "${buildingName}", safeText(groupName));
        if (firstBuilding != null) {
            replaceText(document, "${桥梁编号}", safeText(firstBuilding.getBuildingCode()));
            replaceText(document, "${路线编号}", safeText(firstBuilding.getRouteCode()));
            replaceText(document, "${路线名称}", safeText(firstBuilding.getRouteName()));
            replaceText(document, "${桥位桩号}", safeText(firstBuilding.getBridgePileNumber()));
        }
        if (project != null) {
            replaceText(document, "${project-name}", safeText(project.getName()));
            if (project.getDept() != null) {
                replaceText(document, "${client-unit}", safeText(project.getDept().getDeptName()));
            }
        }
    }

    /**
     * 填充单座桥梁的身份信息，桥梁章节副本内的同名占位符都指向这一座桥。
     */
    private void applyBridgeIdentity(XWPFDocument document, Building building, Project project) {
        replaceTextIfNotBlank(document, "${桥梁名称}", building.getName());
        replaceTextIfNotBlank(document, "${桥梁编号}", building.getBuildingCode());
        replaceTextIfNotBlank(document, "${桥梁代码}", building.getBuildingCode());
        replaceTextIfNotBlank(document, "${路线编号}", building.getRouteCode());
        replaceTextIfNotBlank(document, "${路线名称}", building.getRouteName());
        replaceTextIfNotBlank(document, "${桥位桩号}", building.getBridgePileNumber());
        replaceTextIfNotBlank(document, "${桥梁中心桩号}", building.getBridgePileNumber());
        replaceTextIfNotBlank(document, "${building-name}", building.getName());
        replaceTextIfNotBlank(document, "${buildingName}", building.getName());
        if (project != null) {
            replaceTextIfNotBlank(document, "${project-name}", project.getName());
            if (project.getDept() != null) {
                replaceTextIfNotBlank(document, "${client-unit}", project.getDept().getDeptName());
            }
        }
    }

    private void replaceTextIfNotBlank(XWPFDocument document, String placeholder, String value) {
        if (!isBlank(value)) {
            replaceText(document, placeholder, value);
        }
    }

    /**
     * 生成第二章：按大桥出标题（2.2 的下一级，样式 4），其下按子桥出部件划分及构件数量表。
     */
    private void generateBridgeSummary(XWPFDocument document, List<LineBridgeGroup> groups, Map<Long, Task> taskById) {
        XWPFParagraph placeholder = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-summary}");
        if (placeholder == null) {
            log.warn("多桥模板未找到桥梁汇总占位符");
            return;
        }
        XmlCursor cursor = placeholder.getCTP().newCursor();
        AtomicInteger chapter2TableCounter = new AtomicInteger(1);

        for (LineBridgeGroup group : groups) {
            List<Task> groupTasks = resolveGroupTasks(group, taskById);
            if (groupTasks.isEmpty()) {
                continue;
            }
            XWPFParagraph groupTitle = document.insertNewParagraph(cursor);
            groupTitle.setStyle("4");
            groupTitle.createRun().setText(groupDisplayName(group, groupTasks.get(0)));
            applyHeadingFormat(groupTitle, 4);
            cursor.toNextToken();

            XWPFParagraph componentIntro = document.insertNewParagraph(cursor);
            componentIntro.createRun().setText("各桥梁部件划分及构件数量如下表所示：");
            applyBodyFormat(componentIntro, true);
            cursor.toNextToken();

            for (Task task : groupTasks) {
                Building building = task.getBuilding();
                if (building == null || building.getRootObjectId() == null) {
                    continue;
                }
                BiObject rootObject = biObjectMapper.selectBiObjectById(building.getRootObjectId());
                if (rootObject == null) {
                    log.warn("多桥第二章未找到桥梁结构树: taskId={}, rootObjectId={}",
                            task.getId(), building.getRootObjectId());
                    continue;
                }
                List<BiObject> allObjects = biObjectMapper.selectChildrenById(rootObject.getId());
                for (BiObject tableRoot : resolveComponentTableRoots(rootObject, allObjects)) {
                    String tableName = Objects.equals(tableRoot.getId(), rootObject.getId())
                            ? safeText(building.getName()) : safeText(tableRoot.getName());
                    WordFieldUtils.createTableCaptionWithCounter(
                            document, tableName + "桥梁部件划分及构件数量表",
                            cursor, 2, chapter2TableCounter, 21, 360, false, 0);
                    generateComponentTable(document, cursor, collectComponentStructure(tableRoot, allObjects));
                }
            }
        }
        clearParagraph(placeholder);
    }

    /**
     * 更新母版中原有目录的桥梁名称缓存。
     *
     * <p>POI 不会计算 TOC 域，因此先把母版中 3 个样例桥名替换成前 3 座大桥名；
     * 真正的页码和超过 3 座大桥的目录项在 Word 打开时由 updateFields 自动刷新。</p>
     */
    private void updateTocPreview(XWPFDocument document, List<LineBridgeGroup> groups) {
        String[] placeholders = {"${桥梁名称}", "${line.bridge.2.name}", "${line.bridge.3.name}"};
        for (int i = 0; i < placeholders.length; i++) {
            XWPFParagraph paragraph = ReportGenerateTools.findParagraphByPlaceholder(document, placeholders[i]);
            if (paragraph == null) {
                continue;
            }
            if (groups != null && i < groups.size() && groups.get(i) != null
                    && !isBlank(groups.get(i).getName())) {
                ReportGenerateTools.replaceTextInParagraphs(
                        Collections.singletonList(paragraph),
                        placeholders[i],
                        safeText(groups.get(i).getName()),
                        "目录预览");
            } else {
                clearParagraph(paragraph);
            }
        }
    }

    /**
     * 删除两个锚点段落之间的全部内容，只保留锚点本身。
     */
    private void removeBodyElementsBetween(XWPFDocument document, XWPFParagraph start, XWPFParagraph end) {
        int startIndex = document.getPosOfParagraph(start);
        int endIndex = document.getPosOfParagraph(end);
        if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
            throw new IllegalStateException("多桥模板锚点顺序错误");
        }
        while (endIndex - startIndex > 1) {
            document.removeBodyElement(startIndex + 1);
            endIndex--;
        }
    }

    /**
     * 取出桥梁副本中两个锚点之间生成好的章节内容。
     */
    private List<IBodyElement> copyBridgeBodyElements(XWPFDocument document) {
        XWPFParagraph start = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-start}");
        XWPFParagraph end = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-end}");
        if (start == null || end == null) {
            throw new IllegalStateException("桥梁副本缺少章节锚点");
        }
        int startIndex = document.getPosOfParagraph(start);
        int endIndex = document.getPosOfParagraph(end);
        List<IBodyElement> elements = new ArrayList<>();
        for (int i = startIndex + 1; i < endIndex; i++) {
            elements.add(document.getBodyElements().get(i));
        }
        return elements;
    }

    /**
     * 将桥梁副本文档中的章节插入主文档。
     *
     * <p>图片在 docx 中不是段落 XML 的一部分：段落内的 {@code a:blip} 通过 rId 引用
     * 文档关系中的媒体文件。仅复制 CTP/CTTbl 会保留原副本的 rId，导致主文档找不到图片。
     * 因此在写入主文档前，需要把每个图片关系重新绑定到主文档。</p>
     */
    private void insertBodyElementCopies(XWPFDocument target, XWPFParagraph before,
                                         XWPFDocument sourceDocument, List<IBodyElement> elements) throws Exception {
        XmlCursor cursor = before.getCTP().newCursor();
        Map<String, String> pictureRelationIds = new HashMap<>();
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph source = (XWPFParagraph) element;
                CTP copiedParagraph = (CTP) source.getCTP().copy();
                rebindPictureRelationships(sourceDocument, target, copiedParagraph, pictureRelationIds);
                XWPFParagraph inserted = target.insertNewParagraph(cursor);
                inserted.getCTP().set(copiedParagraph);
            } else if (element instanceof XWPFTable) {
                XWPFTable source = (XWPFTable) element;
                CTTbl copiedTable = (CTTbl) source.getCTTbl().copy();
                rebindPictureRelationships(sourceDocument, target, copiedTable, pictureRelationIds);
                XWPFTable inserted = target.insertNewTbl(cursor);
                inserted.getCTTbl().set(copiedTable);
            }
            cursor.toNextToken();
        }
    }

    /**
     * 将已复制 XML 中的图片 rId 改为主文档的图片关系 ID。
     */
    private void rebindPictureRelationships(XWPFDocument sourceDocument, XWPFDocument targetDocument,
                                            XmlObject copiedElement,
                                            Map<String, String> pictureRelationIds) throws Exception {
        XmlObject[] blipNodes = copiedElement.selectPath(
                "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' .//a:blip");
        for (XmlObject blipNode : blipNodes) {
            CTBlip blip = (CTBlip) blipNode;
            String sourceRelationId = blip.getEmbed();
            if (sourceRelationId == null || sourceRelationId.isBlank()) {
                continue;
            }

            String targetRelationId = pictureRelationIds.get(sourceRelationId);
            if (targetRelationId == null) {
                XWPFPictureData pictureData = sourceDocument.getPictureDataByID(sourceRelationId);
                if (pictureData == null) {
                    log.warn("桥梁章节图片关系不存在，保留原引用: rId={}", sourceRelationId);
                    continue;
                }
                targetRelationId = targetDocument.addPictureData(
                        pictureData.getData(), pictureData.getPictureType());
                pictureRelationIds.put(sourceRelationId, targetRelationId);
            }
            blip.setEmbed(targetRelationId);
        }
    }

    private void removeParagraph(XWPFDocument document, XWPFParagraph paragraph) {
        int position = document.getPosOfParagraph(paragraph);
        if (position >= 0) {
            document.removeBodyElement(position);
        }
    }

    private void removeParagraphByPlaceholder(XWPFDocument document, String placeholder) {
        XWPFParagraph paragraph = ReportGenerateTools.findParagraphByPlaceholder(document, placeholder);
        if (paragraph != null) {
            removeParagraph(document, paragraph);
        }
    }

    /**
     * 在占位符段落前插入小节标题。模板里已有同名标题时不再重复插入。
     */
    private void insertHeadingBeforePlaceholder(XWPFDocument document, String placeholder, String title, int headingLevel) {
        XWPFParagraph target = ReportGenerateTools.findParagraphByPlaceholder(document, placeholder);
        if (target == null) {
            return;
        }
        int position = document.getPosOfParagraph(target);
        if (position > 0) {
            IBodyElement previous = document.getBodyElements().get(position - 1);
            if (previous instanceof XWPFParagraph) {
                String previousText = ((XWPFParagraph) previous).getText();
                if (previousText != null && previousText.replace(".", "").replace(" ", "").contains(title)) {
                    applyHeadingFormat((XWPFParagraph) previous, headingLevel);
                    return;
                }
            }
        }
        XmlCursor cursor = target.getCTP().newCursor();
        XWPFParagraph heading = document.insertNewParagraph(cursor);
        heading.setStyle(String.valueOf(headingLevel));
        heading.createRun().setText(title);
        applyHeadingFormat(heading, headingLevel);
    }

    /**
     * 替换占位符。除正文、表格、页眉页脚外，还覆盖文本框等普通段落 API 遍历不到的文本节点。
     */
    private void replaceText(XWPFDocument document, String oldText, String newText) {
        String replacement = newText == null ? "" : newText;
        ReportGenerateTools.replaceText(document, oldText, replacement);
        replaceTextInXmlTextNodes(document.getDocument(), oldText, replacement);
    }

    private void replaceTextInXmlTextNodes(XmlObject root, String oldText, String newText) {
        if (root == null || oldText == null || oldText.isEmpty()) {
            return;
        }
        try {
            XmlObject[] textNodes = root.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:t");
            for (XmlObject textNode : textNodes) {
                XmlCursor cursor = textNode.newCursor();
                String text = cursor.getTextValue();
                if (text != null && text.contains(oldText)) {
                    cursor.setTextValue(text.replace(oldText, newText));
                }
            }
        } catch (Exception e) {
            log.warn("替换XML文本节点占位符失败: {}", e.getMessage());
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private void clearParagraph(XWPFParagraph paragraph) {
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }
    }

    // ==================== 以下桥梁章节逻辑参照一级单桥报告实现，独立维护 ====================

    /**
     * 处理大桥章节中由系统自动生成的内容
     */
    private void processGroupAutoGeneratedContent(XWPFDocument document, String groupName,
                                                  Project project, List<Task> groupTasks,
                                                  Map<String, ReportData> dataMap,
                                                  Map<String, BiEvaluation> importedPreviousEvaluations) {
        AtomicInteger chapter3TableCounter = new AtomicInteger(1);
        try {
            processAppearanceCheck(document, groupTasks, project.getId(), chapter3TableCounter,
                    parseAppearanceImageSelection(dataMap));
            log.info("外观检测结果生成完成");
        } catch (Exception e) {
            log.error("处理外观检测结果出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, "${appearanceInspectionResults}", "【外观检测结果生成失败，请联系管理员】");
        }

        try {
            handleDiseaseComparison(document, groupTasks, project, chapter3TableCounter);
            log.info("与上一次检查病害变化情况分析生成完成");
        } catch (Exception e) {
            log.error("处理与上一次检查病害变化情况分析出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, "${diseaseComparison}", "【病害变化情况分析生成失败，请联系管理员】");
        }

        try {
            handleEvaluationResults(document, "${evaluationResults}", groupName, groupTasks);
            log.info("技术状况评定生成完成");
        } catch (Exception e) {
            log.error("处理技术状况评定出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, "${evaluationResults}", "【技术状况评定生成失败，请联系管理员】");
        }

        try {
            handleComparisonAnalysis(document, "${comparativeAnalysisOfEvaluationResults}", groupTasks,
                    importedPreviousEvaluations);
            log.info("近年评定结果对比生成完成");
        } catch (Exception e) {
            log.error("处理近年评定结果对比出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, "${comparativeAnalysisOfEvaluationResults}", "【近年评定结果对比生成失败，请联系管理员】");
        }

        try {
            handleCarbonizationAssessment(document, groupTasks, chapter3TableCounter);
            log.info("碳化及锈蚀电位评定表生成完成");
        } catch (Exception e) {
            log.error("处理碳化及锈蚀电位评定表出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, CARBONIZATION_ASSESSMENT_PLACEHOLDER, "【碳化及锈蚀电位评定表生成失败，请联系管理员】");
        }

        try {
            generateKeyConcernDiseases(document, groupName, groupTasks, project, chapter3TableCounter, dataMap);
            log.info("重点关注病害汇总生成完成");
        } catch (Exception e) {
            log.error("处理重点关注病害汇总出错: error={}", e.getMessage(), e);
            ReportGenerateTools.replaceText(document, KEY_CONCERN_DISEASES_PLACEHOLDER, "【重点关注病害生成失败，请联系管理员】");
        }

        try {
            handleTestConclusion(document, "${testConclusion}", groupTasks, groupName);
            handleTestConclusionBridge(document, "${testConclusionBridge}", groupTasks);
        } catch (Exception e) {
            log.error("处理检测结论出错: error={}", e.getMessage());
            ReportGenerateTools.replaceText(document, "${testConclusion}", "检测结论数据获取失败");
            ReportGenerateTools.replaceText(document, "${testConclusionBridge}", "检测结论详情数据获取失败");
        }
    }

    /**
     * 处理第三章外观检测结果。
     *
     * <p>模板里 3.2「外观检测结果」是二级（样式 2），其下一级是样式 4（与 3.1.1 整体概况同级）。
     * 每座子桥占这一级（3.2.1、3.2.2）；原先的上部/下部/桥面系及构件整体再降一级。</p>
     */
    private void processAppearanceCheck(XWPFDocument document, List<Task> groupTasks, Long projectId,
                                        AtomicInteger chapter3TableCounter,
                                        Map<Long, Set<Long>> appearanceImageSelection) throws Exception {
        XWPFParagraph placeholderParagraph = null;
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            if (paragraph.getText().contains("${appearanceInspectionResults}")) {
                placeholderParagraph = paragraph;
                break;
            }
        }
        if (placeholderParagraph == null) {
            return;
        }

        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();
        AtomicInteger chapter3ImageCounter = new AtomicInteger(1);
        if (chapter3TableCounter == null) {
            chapter3TableCounter = new AtomicInteger(1);
        }

        for (Task task : groupTasks) {
            Building building = task.getBuilding();
            if (building == null || building.getRootObjectId() == null) {
                continue;
            }
            BiObject rootBiObject = biObjectMapper.selectBiObjectById(building.getRootObjectId());
            if (rootBiObject == null) {
                continue;
            }
            List<BiObject> allObjects = biObjectMapper.selectChildrenById(building.getRootObjectId());

            XWPFParagraph subBridgeTitle = document.insertNewParagraph(cursor);
            subBridgeTitle.setStyle("4");
            subBridgeTitle.createRun().setText(safeText(building.getName()));
            applyHeadingFormat(subBridgeTitle, 4);
            cursor.toNextToken();

            Disease queryParam = new Disease();
            queryParam.setBuildingId(building.getId());
            queryParam.setProjectId(projectId);
            List<Disease> subBridgeDiseases = diseaseMapper.selectDiseaseList(queryParam);
            hydrateDiseaseDetails(subBridgeDiseases);

            Set<Long> imageDiseaseIds = appearanceImageSelection == null
                    ? null
                    : appearanceImageSelection.getOrDefault(task.getId(), Set.of());
            List<BiObject> appearanceRoots = resolveComponentTableRoots(rootBiObject, allObjects);
            for (BiObject appearanceRoot : appearanceRoots) {
                Map<Long, List<Disease>> bridgeDiseaseMap = new LinkedHashMap<>();
                collectDiseases(appearanceRoot, appearanceRoot, allObjects, subBridgeDiseases, bridgeDiseaseMap);
                boolean skipRootHeading = appearanceRoots.size() == 1
                        && Objects.equals(appearanceRoot.getId(), rootBiObject.getId());
                if (skipRootHeading) {
                    List<BiObject> structures = ReportTemplateValueUtils.sortedReportChildren(allObjects, appearanceRoot.getId()).stream()
                            .filter(child -> COMPONENT_STRUCTURE_NAMES.contains(child.getName()))
                            .collect(Collectors.toList());
                    if (structures.isEmpty()) {
                        structures = ReportTemplateValueUtils.sortedReportChildren(allObjects, appearanceRoot.getId());
                    }
                    // 子桥标题已写出。从上部结构按 level=2 往下写，部件仍是 level=3，
                    // 检测结果表只在 level==3 导出；若从上部结构当 level=1 写，病害会落到空心板等下一级，全部变成「未见明显病害」。
                    for (BiObject structure : structures) {
                        writeBiObjectTreeToWord(document, structure, allObjects, bridgeDiseaseMap,
                                "3.2", 2, chapter3ImageCounter, chapter3TableCounter, cursor, 3,
                                safeText(building.getName()), imageDiseaseIds);
                    }
                } else {
                    writeBiObjectTreeToWord(document, appearanceRoot, allObjects, bridgeDiseaseMap,
                            "3.2", 1, chapter3ImageCounter, chapter3TableCounter, cursor, 4,
                            safeText(building.getName()), imageDiseaseIds);
                }
            }
        }

        // getParagraphs() 的下标不包含表格，不能直接传给 removeBodyElement()。
        // 外观检测中一旦生成表格，两种下标就会错位，曾导致误删“桥台”等已生成标题，
        // 只留下其正文紧跟在上一构件正文后面。
        removeParagraph(document, placeholderParagraph);
    }

    /**
     * 报告查询默认只返回病害主表；程序汇总需要一次性装载所有结构化定量明细。
     */
    private void hydrateDiseaseDetails(List<Disease> diseases) {
        if (diseases == null || diseases.isEmpty()) {
            return;
        }
        List<Long> diseaseIds = diseases.stream()
                .filter(Objects::nonNull)
                .map(Disease::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (diseaseIds.isEmpty()) {
            return;
        }

        Map<Long, List<DiseaseDetail>> detailsByDiseaseId = diseaseDetailMapper
                .selectDiseaseDetailsByDiseaseIds(diseaseIds)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        DiseaseDetail::getDiseaseId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        diseases.forEach(disease -> {
            if (disease != null) {
                disease.setDiseaseDetails(detailsByDiseaseId.getOrDefault(disease.getId(), List.of()));
            }
        });
    }

    /**
     * 3.4 与上一次检查病害变化：按子桥顺序各出一张横向「病害汇总统计表」。
     */
    private void handleDiseaseComparison(XWPFDocument document, List<Task> groupTasks, Project project,
                                         AtomicInteger chapter3TableCounter) {
        XWPFParagraph placeholder = ReportGenerateTools.findParagraphByPlaceholder(document, "${diseaseComparison}");
        boolean removePlaceholder = placeholder != null;
        if (placeholder == null) {
            placeholder = findDiseaseComparisonHeading(document);
        }
        if (placeholder == null) {
            log.warn("未找到占位符 ${diseaseComparison}，跳过病害变化情况分析");
            return;
        }
        removeFollowingSampleComparisonTable(document, placeholder);
        if (chapter3TableCounter == null) {
            chapter3TableCounter = new AtomicInteger(1);
        }

        XmlCursor cursor = placeholder.getCTP().newCursor();
        if (!removePlaceholder) {
            cursor.toEndToken();
            cursor.toNextToken();
        }
        XWPFParagraph intro = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        applyBodyFormat(intro, true);

        List<String> bookmarks = new ArrayList<>();
        Long projectId = project == null ? null : project.getId();
        for (Task task : groupTasks) {
            if (task == null || task.getBuilding() == null || task.getBuilding().getRootObjectId() == null) {
                continue;
            }
            BiObject subBridge = biObjectMapper.selectBiObjectById(task.getBuilding().getRootObjectId());
            if (subBridge == null) {
                continue;
            }
            List<DiseaseComparisonData> comparisonData = diseaseComparisonService.generateComparisonData(
                    subBridge, projectId, task.getBuilding().getId());
            if (comparisonData == null || comparisonData.isEmpty()) {
                log.info("子桥无病害对比数据: {}", task.getBuilding().getName());
                continue;
            }
            String bookmark = DiseaseComparisonTableUtils.createDiseaseComparisonTable(
                    document, comparisonData, cursor, chapter3TableCounter, 3, 1,
                    safeText(task.getBuilding().getName()));
            if (bookmark != null) {
                bookmarks.add(bookmark);
            }
        }

        fillDiseaseComparisonIntro(intro, bookmarks);
        if (removePlaceholder) {
            clearParagraph(placeholder);
            int placeholderPos = document.getPosOfParagraph(placeholder);
            if (placeholderPos >= 0) {
                document.removeBodyElement(placeholderPos);
            }
        }
    }

    private XWPFParagraph findDiseaseComparisonHeading(XWPFDocument document) {
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text == null) {
                continue;
            }
            String compact = text.replace(" ", "");
            if (compact.contains("与上一次检查病害变化情况分析") && !compact.matches(".*分析\\d+$")) {
                return paragraph;
            }
        }
        return null;
    }

    private void fillDiseaseComparisonIntro(XWPFParagraph intro, List<String> bookmarks) {
        if (intro == null) {
            return;
        }
        if (bookmarks == null || bookmarks.isEmpty()) {
            intro.createRun().setText("对桥梁近2年的病害进行比对分析，本次检查无可对比病害数据。");
            applyBodyFormat(intro, true);
            return;
        }
        if (bookmarks.size() == 1) {
            WordFieldUtils.createChapterTableReference(intro, bookmarks.get(0),
                    "对桥梁近2年的病害进行比对分析，结果如下表", "所示。");
        } else {
            WordFieldUtils.createChapterTableReference(intro, bookmarks.get(0),
                    "对桥梁近2年的病害进行比对分析，结果如下表", "");
            XWPFRun rangeRun = intro.createRun();
            rangeRun.setText(" ~ ");
            WordFieldUtils.createChapterTableReference(intro, bookmarks.get(bookmarks.size() - 1),
                    "", "所示。");
        }
    }

    private void removeFollowingSampleComparisonTable(XWPFDocument document, XWPFParagraph anchor) {
        if (document == null || anchor == null) {
            return;
        }
        int pos = document.getPosOfParagraph(anchor);
        if (pos < 0) {
            return;
        }
        List<IBodyElement> elements = document.getBodyElements();
        for (int i = pos + 1; i < elements.size() && i <= pos + 8; i++) {
            IBodyElement element = elements.get(i);
            if (element instanceof XWPFTable) {
                String text = ((XWPFTable) element).getText();
                if (text != null && text.contains("桥梁名称") && text.contains("发展情况")) {
                    document.removeBodyElement(i);
                    return;
                }
            }
            if (element instanceof XWPFParagraph) {
                String text = ((XWPFParagraph) element).getText();
                if (text != null && (text.contains("评定结果") || text.contains("${evaluationResults}"))) {
                    return;
                }
            }
        }
    }

    /**
     * 收集病害数据，参考ReportControllertest.java中的collectDiseases方法
     */
    private void collectDiseases(BiObject node, BiObject appearanceRoot, List<BiObject> allNodes,
                                 List<Disease> properties, Map<Long, List<Disease>> map) {
        // 找到当前节点所属的 level3 祖先（如自身就是 level3 则返回自身）
        BiObject level3 = findLevel3Ancestor(node, appearanceRoot, allNodes);

        // 把当前节点的病害挂到 level3 名下
        List<Disease> self = properties.stream()
                .filter(d -> d.getBiObjectId() != null && node.getId().equals(d.getBiObjectId()))
                .collect(Collectors.toList());
        map.computeIfAbsent(level3.getId(), k -> new ArrayList<>()).addAll(self);

        // 继续向下收集
        List<BiObject> children = allNodes.stream()
                .filter(o -> node.getId().equals(o.getParentId()))
                .collect(Collectors.toList());
        for (BiObject child : children) {
            collectDiseases(child, appearanceRoot, allNodes, properties, map);
        }
    }

    /**
     * 已维修、未找到的病害不写入检测结果表，也不导出对应照片。
     */
    private static boolean isExcludedFromInspectionResultTable(Disease disease) {
        if (disease == null || disease.getDevelopmentTrend() == null) {
            return false;
        }
        String trend = disease.getDevelopmentTrend().trim();
        return "已维修".equals(trend) || "未找到".equals(trend);
    }

    private static boolean isCrackDisease(Disease disease) {
        if (disease == null) {
            return false;
        }
        String typeName = disease.getDiseaseType() != null && disease.getDiseaseType().getName() != null
                ? disease.getDiseaseType().getName() : disease.getType();
        return typeName != null && typeName.contains("裂缝");
    }

    private static Long longValue(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 不参与评定（participateAssess=0）的病害仍进外观检测表，评定类别列写「/」。 */
    private static boolean isNotParticipatingInAssessment(Disease disease) {
        return disease != null && "0".equals(disease.getParticipateAssess());
    }

    /**
     * 递归写入树结构和病害信息
     *
     * @param document   Word文档
     * @param node       当前节点
     * @param allNodes   所有节点
     * @param diseaseMap 病害映射
     * @param prefix     章节前缀
     * @param level      当前层级
     * @param cursor     XML游标，指定内容插入位置，如果为null则追加到文档末尾
     * @throws Exception 异常
     */

    private void writeBiObjectTreeToWord(XWPFDocument document, BiObject node, List<BiObject> allNodes,
                                         Map<Long, List<Disease>> diseaseMap, String prefix, int level,
                                         AtomicInteger chapterImageCounter, AtomicInteger chapter3TableCounter,
                                         XmlCursor cursor, int baseHeadingLevel, String bridgeName,
                                         Set<Long> imageDiseaseIds) throws Exception {
        if (level > 3) {
            return; // 不再写标题，也不再递归写标题
        }
        if (node.getName().equals("其他") || node.getName().equals("附属设施")) {
            return;
        }

        // 写目录标题，根据是否有游标决定在哪里创建段落
        XWPFParagraph p;
        if (cursor != null) {
            p = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            p = document.createParagraph();
        }

        CTPPr ppr = p.getCTP().getPPr();
        if (ppr == null) ppr = p.getCTP().addNewPPr();

        // 模板中 styleId=6 是五级标题。分项构件与上级结构同属标题树，
        // 由模板自动编号，不能改成带手工“（1）”序号的正文段落。
        int actualHeadingLevel = baseHeadingLevel + level;
        String headingStyle = String.valueOf(Math.min(actualHeadingLevel, 9));
        p.setStyle(headingStyle);
        p.setAlignment(ParagraphAlignment.LEFT);
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        ind.setFirstLine(BigInteger.valueOf(0));
        ind.setLeft(BigInteger.valueOf(0));
        XWPFRun run = p.createRun();
        run.setText(node.getName());
        applyHeadingFormat(p, actualHeadingLevel);

        // 写病害信息；已维修、未找到不进检测结果表，对应照片也不导出
        List<Disease> nodeDiseases = diseaseMap.getOrDefault(node.getId(), List.of()).stream()
                .filter(d -> !isExcludedFromInspectionResultTable(d))
                .collect(Collectors.toList());

        // 查询所有组件
        List<Long> componentIds = nodeDiseases.stream()
                .map(Disease::getComponentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<Component> components = componentService.selectComponentsByIds(componentIds);

        Map<Long, Component> componentMap = components.stream()
                .collect(Collectors.toMap(Component::getId, c -> c));
        // 给病害 按照构件编号 排序
        nodeDiseases.sort((d1, d2) -> {

            // -----------------------
            // 按 Component.code 排
            // -----------------------
            Component c1 = componentMap.get(d1.getComponentId());
            Component c2 = componentMap.get(d2.getComponentId());

            String code1 = (c1 != null ? c1.getCode() : "");
            String code2 = (c2 != null ? c2.getCode() : "");

            return compareCodes(code1, code2);
        });

        // 提前收集所有病害的图片书签信息
        Map<Long, List<String>> diseaseImageRefs = new HashMap<>(); // key: 病害ID, value: 图片书签列表

        for (Disease d : nodeDiseases) {
            List<String> imageBookmarks = new ArrayList<>();
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(d.getId());
            boolean crack = isCrackDisease(d);
            int emitted = 0;
            if (images != null) {
                for (Map<String, Object> img : images) {
                    if (!Boolean.TRUE.equals(img.get("isImage"))) {
                        continue;
                    }
                    Long attachmentId = longValue(img.get("id"));
                    boolean selected = imageDiseaseIds == null
                            ? emitted == 0
                            : attachmentId != null && imageDiseaseIds.contains(attachmentId);
                    if (!selected || (crack && emitted >= 1)) {
                        continue;
                    }
                    emitted++;
                    String componentName = "";
                    Component component = componentService.selectComponentById(d.getComponentId());
                    if (component != null) {
                        componentName = component.getName();
                    }
                    String type = d.getType() == null ? "" : d.getType().substring(d.getType().lastIndexOf("#") + 1);
                    String imageDesc = componentName + d.getPosition() + type;
                    String bookmark = "Figure_Chapter3_" + UUID.randomUUID() + "_" + chapterImageCounter.getAndIncrement();
                    imageBookmarks.add(bookmark + "|" + imageDesc + "|" + attachmentId);
                }
            }
            diseaseImageRefs.put(d.getId(), imageBookmarks);
        }

        // 如果存在病害信息，则生成介绍段落和表格
        if (!nodeDiseases.isEmpty() && level == 3) {
            // 现在域本身包含完整格式，不需要额外的序号映射
            // 创建介绍段落，使用游标
            XWPFParagraph introPara;
            if (cursor != null) {
                introPara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                introPara = document.createParagraph();
            }

            XWPFRun introRun = introPara.createRun();
            introRun.setText("经检查，" + node.getName() + "主要病害为：");
            applyBodyFormat(introPara, true);

            // Part 2: 生成病害小结

            log.info("开始生成程序病害小结: node={}, diseases={}", node.getName(), nodeDiseases.size());
            String diseaseString = programmaticDiseaseSummaryService.summarize(nodeDiseases, node.getName());
            log.info("程序病害小结生成完成: node={}, text={}", node.getName(), diseaseString);

            testConclusionService.cacheDiseaseSummary(node.getId(), diseaseString);

            log.info("生成结束");

            String[] lines = diseaseString.split("\\r?\\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                XWPFParagraph diseasePara;
                if (cursor != null) {
                    diseasePara = document.insertNewParagraph(cursor);
                    cursor.toNextToken();
                } else {
                    diseasePara = document.createParagraph();
                }
                XWPFRun runItem = diseasePara.createRun();
                runItem.setText(line.trim());
                applyBodyFormat(diseasePara, true);
            }

            // Part 3: 表格引用部分
            XWPFParagraph tableRefPara;
            if (cursor != null) {
                tableRefPara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                tableRefPara = document.createParagraph();
            }

            String tableTitle = (isBlank(bridgeName) ? "" : bridgeName) + node.getName() + "结构检测结果表";
            String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(
                    document, tableTitle, cursor, 3, chapter3TableCounter, 21, 360, false, 0);

            WordFieldUtils.createChapterTableReference(tableRefPara, tableBookmark, "具体检测结果见下表", "：");
            applyBodyFormat(tableRefPara, true);

            // 创建表格
            XWPFTable table;
            if (cursor != null) {
                table = document.insertNewTbl(cursor);
                cursor.toNextToken();
                // 初始化表格结构
                for (int i = 0; i < 8; i++) {
                    if (i == 0) {
                        table.getRow(0).getCell(i);
                    } else {
                        table.getRow(0).addNewTableCell();
                    }
                }
            } else {
                table = document.createTable(1, 8);
            }

            // 设置表格边框
            CTTblPr tblPr = table.getCTTbl().getTblPr();
            if (tblPr == null) {
                tblPr = table.getCTTbl().addNewTblPr();
            }
            CTTblBorders borders = tblPr.addNewTblBorders();
            borders.addNewBottom().setVal(STBorder.SINGLE);
            borders.addNewLeft().setVal(STBorder.SINGLE);
            borders.addNewRight().setVal(STBorder.SINGLE);
            borders.addNewTop().setVal(STBorder.SINGLE);
            borders.addNewInsideH().setVal(STBorder.SINGLE);
            borders.addNewInsideV().setVal(STBorder.SINGLE);

            CTJcTable jc = tblPr.isSetJc() ? tblPr.getJc() : tblPr.addNewJc();
            jc.setVal(STJcTable.CENTER);

            // 总宽 17.5 cm（取整为 9921 twips）
            int totalWidth = 9921;
            CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
            tblWidth.setW(BigInteger.valueOf(totalWidth));
            tblWidth.setType(STTblWidth.DXA);

            // 设置表头
            XWPFTableRow headerRow = table.getRow(0);

            // 表头文本数组
            String[] headers = {"序号", "缺损位置", "缺损类型", "数量", "病害描述", "评定类别 (1~5)", "发展趋势", "照片"};

            // 按现有比例分配列宽，累计取整确保列宽之和等于表格总宽
            Double[] columnWidthRatios = {0.08, 0.12, 0.12, 0.08, 0.32, 0.10, 0.08, 0.10};
            int[] columnWidths = new int[columnWidthRatios.length];
            double cumulativeRatio = 0;
            int allocatedWidth = 0;
            for (int i = 0; i < columnWidthRatios.length; i++) {
                cumulativeRatio += columnWidthRatios[i];
                int cumulativeWidth = i == columnWidthRatios.length - 1
                        ? totalWidth : (int) Math.round(totalWidth * cumulativeRatio);
                columnWidths[i] = cumulativeWidth - allocatedWidth;
                allocatedWidth = cumulativeWidth;
            }

            CTTblLayoutType tblLayout = tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout();
            tblLayout.setType(STTblLayoutType.FIXED);

            // 设置每列
            for (int i = 0; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.getCell(i);

                // 清除内容（更安全的清除方式）
                for (int j = cell.getParagraphs().size() - 1; j >= 0; j--) {
                    cell.removeParagraph(j);
                }

                // 设置文本样式
                XWPFParagraph paragraph = cell.addParagraph();
                ReportGenerateTools.setSingleLineSpacing(paragraph);  // 设置单倍行距
                paragraph.setAlignment(ParagraphAlignment.CENTER);

                XWPFRun run1 = paragraph.createRun();
                run1.setText(headers[i]);
                run1.setBold(true);

                // 设置中英文字体和字号
                ReportGenerateTools.setMixedFontFamily(run1, 21);  // 10.5pt

                // 设置列宽
                CTTc cttc = cell.getCTTc();
                CTTcPr tcPr = cttc.isSetTcPr() ? cttc.getTcPr() : cttc.addNewTcPr();

                if (tcPr.isSetTcW()) {
                    tcPr.unsetTcW(); // 关键：去掉默认宽度
                }

                // 计算每列的实际宽度
                int columnWidth = columnWidths[i];
                CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                tcW.setW(BigInteger.valueOf(columnWidth));
                tcW.setType(STTblWidth.DXA);

                // 防止内容换行（可选）
                tcPr.addNewNoWrap();
                // 垂直居中 12.16 修改
                if (!tcPr.isSetVAlign()) {
                    tcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
                }
            }

            // 设置标题行在跨页时重复显示
            ReportGenerateTools.setTableHeaderRepeat(headerRow);

            // 填充数据行
            int seqNum = 1;
            for (Disease d : nodeDiseases) {
                XWPFTableRow dataRow = table.createRow();

                // 为数据行的每个单元格设置相同的宽度
                for (int i = 0; i < headers.length; i++) {
                    XWPFTableCell cell = dataRow.getCell(i);

                    // 设置单元格宽度与表头一致
                    CTTc cttc = cell.getCTTc();
                    CTTcPr tcPr = cttc.isSetTcPr() ? cttc.getTcPr() : cttc.addNewTcPr();
                    int columnWidth = columnWidths[i];
                    CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                    tcW.setW(BigInteger.valueOf(columnWidth));
                    tcW.setType(STTblWidth.DXA);

                    // 设置单元格内容居中
                    XWPFParagraph cellP = cell.getParagraphs().get(0);
                    ReportGenerateTools.setSingleLineSpacing(cellP);  // 设置单倍行距
                    cellP.setAlignment(ParagraphAlignment.CENTER);

                    // 垂直居中 12.16修改。
                    CTTcPr curTcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr()
                            : cell.getCTTc().addNewTcPr();
                    if (!curTcPr.isSetVAlign()) {
                        curTcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
                    }

                    // 设置文本内容
                    XWPFRun cellR = cellP.createRun();

                    // 设置中英文字体和字号
                    ReportGenerateTools.setMixedFontFamily(cellR, 21);  // 10.5pt
                    Component component = componentService.selectComponentById(d.getComponentId());
                    switch (i) {
                        case 0:
                            cellR.setText(String.valueOf(seqNum++));
                            break;
                        case 1:
                            cellR.setText(component != null ? component.getName() : "/");
                            break;
                        case 2:
                            // 12.25 修改 ,额外处理裂缝类型
                            cellR.setText(ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) != null ? ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) : "/");
                            break;
                        case 3:
                            cellR.setText(d.getQuantity() > 0 ? String.valueOf(d.getQuantity()) : "/");
                            break;
                        case 4:
                            cellR.setText(ReportGenerateTools.formatAppearanceDiseaseDescription(d.getDescription()));
                            break;
                        case 5:
                            cellR.setText(!isNotParticipatingInAssessment(d) && d.getLevel() > 0
                                    ? String.valueOf(d.getLevel()) : "/");
                            break;
                        case 6:
                            cellR.setText(d.getDevelopmentTrend());
                            break;
                        case 7:
                            // 获取该病害对应的所有图片书签信息
                            List<String> refs = diseaseImageRefs.getOrDefault(d.getId(), new ArrayList<>());
                            // 从书签信息中提取书签名，创建图片引用域
                            if (!refs.isEmpty()) {
                                // 清除现有内容
                                cellP.removeRun(cellP.getRuns().size() - 1);

                                for (int j = 0; j < refs.size(); j++) {
                                    String[] parts = refs.get(j).split("\\|");
                                    String bookmarkName = parts[0];

                                    if (j > 0) {
                                        XWPFRun commaRun = cellP.createRun();
                                        commaRun.setText(",");
                                        ReportGenerateTools.setMixedFontFamily(commaRun, 21);  // 10.5pt
                                    }

                                    // 创建章节格式的图片引用域，添加"图"字前缀
                                    WordFieldUtils.createChapterFigureReference(cellP, bookmarkName, "图", "");
                                }
                            } else {
                                cellR.setText("/");
                            }
                            break;
                    }
                }
            }

            // 在表格下方插入病害图片
            if (!nodeDiseases.isEmpty()) {
                insertDiseaseImagesWithStreaming(document, nodeDiseases, diseaseImageRefs, cursor);
            }
        } else if (level == 3) {
            // 部件划分表里构件数量为「/」的，外观检测写无此构件，而不是未见明显病害
            boolean missingComponent = isMissingComponent(node, allNodes);
            String noDiseaseText = missingComponent
                    ? TestConclusionService.NO_SUCH_COMPONENT_TEXT
                    : "经检查，" + node.getName() + "未见明显病害。";
            XWPFParagraph noDiseasePara;
            if (cursor != null) {
                noDiseasePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                noDiseasePara = document.createParagraph();
            }

            CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
            spacing.setLine(BigInteger.valueOf(360));

            XWPFRun noDiseaseRun = noDiseasePara.createRun();
            noDiseaseRun.setText(noDiseaseText);
            applyBodyFormat(noDiseasePara, true);
            if (missingComponent) {
                testConclusionService.cacheDiseaseSummary(node.getId(), noDiseaseText);
            }

            if (!missingComponent) {
                try {
                    insertBiObjectStatusImages(document, node, chapterImageCounter, cursor);
                } catch (Exception e) {
                    log.error("插入BiObject现状照片失败", e);
                }
            }
        }

        // 按结构树 orderNum 出标题，与评定部件顺序一致：上部结构→下部结构→桥面系，
        // 以及承重构件、一般构件、支座、桥墩、桥台等，不能按名称拼音排。
        List<BiObject> children = ReportTemplateValueUtils.sortedReportChildren(allNodes, node.getId());

        int idx = 1;
        for (BiObject child : children) {
            writeBiObjectTreeToWord(document, child, allNodes, diseaseMap, prefix + "." + idx, level + 1, chapterImageCounter, chapter3TableCounter, cursor, baseHeadingLevel, bridgeName, imageDiseaseIds);
            idx++;
        }
    }

    /**
     * 找到第三层祖先节点（相对外观检测出表根，例如主桥/引桥）。
     */
    private BiObject findLevel3Ancestor(BiObject node, BiObject appearanceRoot, List<BiObject> allNodes) {
        BiObject cur = node;
        int lv = getRelativeLevel(cur, appearanceRoot, allNodes);
        while (lv > 3 && cur.getParentId() != null
                && (appearanceRoot == null || !cur.getId().equals(appearanceRoot.getId()))) {
            BiObject finalCur = cur;
            BiObject parent = allNodes.stream()
                    .filter(o -> o.getId().equals(finalCur.getParentId()))
                    .findFirst()
                    .orElse(null);
            if (parent == null && appearanceRoot != null && appearanceRoot.getId().equals(cur.getParentId())) {
                parent = appearanceRoot;
            }
            if (parent == null) {
                break;
            }
            cur = parent;
            lv--;
        }
        return cur;
    }

    /**
     * 相对出表根的层级：根=1，上部结构=2，部件=3。
     */
    private int getRelativeLevel(BiObject node, BiObject ancestor, List<BiObject> allNodes) {
        if (node == null) {
            return 0;
        }
        if (ancestor != null && node.getId().equals(ancestor.getId())) {
            return 1;
        }
        Map<Long, BiObject> byId = allNodes.stream()
                .filter(object -> object.getId() != null)
                .collect(Collectors.toMap(BiObject::getId, object -> object, (left, right) -> left));
        if (ancestor != null && ancestor.getId() != null) {
            byId.put(ancestor.getId(), ancestor);
        }
        int level = 1;
        BiObject current = node;
        while (current != null && (ancestor == null || !current.getId().equals(ancestor.getId()))) {
            Long parentId = current.getParentId();
            if (parentId == null) {
                break;
            }
            BiObject parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            current = parent;
            level++;
            if (level > 20) {
                break;
            }
        }
        return level;
    }

    /**
     * 获取节点层级
     */
    private int getLevel(BiObject node, List<BiObject> allNodes) {
        int level = 2;
        BiObject p = node;
        while (p.getParentId() != null) {
            BiObject finalP = p;
            p = allNodes.stream()
                    .filter(o -> o.getId().equals(finalP.getParentId()))
                    .findFirst()
                    .orElse(null);
            if (p != null) level++;
            else break;
        }
        return level;
    }


    /**
     * 处理评定结果：大桥总述 + 每座子桥一段 + 按子桥顺序出表。
     */
    private void handleEvaluationResults(XWPFDocument document, String key, String groupName, List<Task> groupTasks) {
        try {
            log.info("开始处理评定结果, key: {}", key);
            StringBuilder content = new StringBuilder();
            content.append("依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，")
                    .append(groupName)
                    .append("的技术状况评定结果如下：\n");

            Map<Long, BiEvaluation> evaluationMap = new LinkedHashMap<>();
            Integer minSystemLevel = null;
            for (Task task : groupTasks) {
                BiEvaluation evaluation = biEvaluationService.selectBiEvaluationByTaskId(task.getId());
                Building building = task.getBuilding();
                String bridgeName = building != null && building.getName() != null ? building.getName() : "桥梁";
                if (evaluation == null) {
                    content.append("未找到").append(bridgeName).append("的评定结果。\n");
                    continue;
                }
                evaluationMap.put(task.getId(), evaluation);
                if (evaluation.getSystemLevel() != null
                        && (minSystemLevel == null || evaluation.getSystemLevel() > minSystemLevel)) {
                    minSystemLevel = evaluation.getSystemLevel();
                }
                content.append(bridgeName)
                        .append("上部结构技术状况评分为")
                        .append(formatScore(evaluation.getSuperstructureScore()))
                        .append("分，等级为")
                        .append(evaluation.getSuperstructureLevel())
                        .append("类；下部结构技术状况评分为")
                        .append(formatScore(evaluation.getSubstructureScore()))
                        .append("分，等级为")
                        .append(evaluation.getSubstructureLevel())
                        .append("类；桥面系技术状况评分为")
                        .append(formatScore(evaluation.getDeckSystemScore()))
                        .append("分，等级为")
                        .append(evaluation.getDeckSystemLevel())
                        .append("类；全桥技术状况评分为")
                        .append(formatScore(evaluation.getSystemScore()))
                        .append("分，评定为")
                        .append(evaluation.getSystemLevel())
                        .append("类桥梁。\n");
            }
            if (minSystemLevel != null) {
                content.append("全桥技术状况评定按照评定单元最低分进行评定，因此评定为")
                        .append(minSystemLevel)
                        .append("类。\n");
            }
            content.append("技术状况评定记录和具体评分见下表所示。");

            XWPFParagraph paragraph = insertEvaluationContent(document, key, content.toString());
            // generateEvaluationTableAfterParagraph 总是紧挨这段文字插入，后写的表会排到前面。
            // 因此按子桥顺序从后往前插，最终表格顺序才和文字、外观检测一致。
            for (int i = groupTasks.size() - 1; i >= 0; i--) {
                Task task = groupTasks.get(i);
                BiEvaluation evaluation = evaluationMap.get(task.getId());
                if (evaluation == null) {
                    continue;
                }
                Building building = task.getBuilding();
                String bridgeName = building != null && building.getName() != null ? building.getName() : "桥梁";
                evaluationTableService.generateEvaluationTableAfterParagraph(
                        document, paragraph, building, evaluation, bridgeName, "13", true);
            }
            log.info("评定结果和表格处理完成");
        } catch (Exception e) {
            log.error("处理评定结果失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }


    /**
     * 插入评定文字内容到文档中
     *
     * @param document 文档对象
     * @param key      占位符
     */
    private XWPFParagraph insertEvaluationContent(XWPFDocument document, String key, String content) {
        try {
            // 查找占位符位置
            XWPFParagraph targetParagraph = null;
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            for (int i = 0; i < paragraphs.size(); i++) {
                XWPFParagraph paragraph = paragraphs.get(i);
                String text = paragraph.getText();
                if (text != null && text.contains(key)) {
                    targetParagraph = paragraph;
                    break;
                }
            }

            if (targetParagraph == null) {
                log.warn("未找到占位符: {}", key);
                return null;
            }

            XWPFParagraph lastParagraph = writeFilledBodyIntoParagraph(document, targetParagraph, content);
            log.info("评定内容插入成功: {}", key);
            return lastParagraph;

        } catch (Exception e) {
            log.error("插入评定内容失败: key={}", key, e);
            throw e;
        }
    }


    /**
     * 格式化分数，保留一位小数
     *
     * @param score 分数
     * @return 格式化后的分数字符串
     */
    private String formatScore(java.math.BigDecimal score) {
        if (score == null) {
            return "0.0";
        }
        return String.format("%.1f", score.doubleValue());
    }


    /**
     * 处理大桥章节中用户填写的数据（照片已按大桥上传处理）
     */
    private void processGroupUserData(XWPFDocument document, List<ReportData> reportDataList,
                                      List<Task> groupTasks, Map<String, ReportData> dataMap,
                                      Project project) {
        Task firstTask = groupTasks == null || groupTasks.isEmpty() ? null : groupTasks.get(0);
        Building firstBuilding = firstTask == null ? null : firstTask.getBuilding();
        for (ReportData data : reportDataList) {
            String key = data.getKey();
            String value = data.getValue();
            Integer type = data.getType();
            try {
                if (key != null && (key.contains("leftFront") || key.contains("rightFront") ||
                        key.contains("leftSide") || key.contains("rightSide"))) {
                    continue;
                }
                if ("overallOverview".equals(key) || LineBridgeGroup.DATA_KEY.equals(key)
                        || "includePierTiltChapter".equals(key)) {
                    continue;
                }
                if (key != null && (key.contains(KEY_CONCERN_SELECTION_KEY) || key.contains("focusDiseases")
                        || key.contains(APPEARANCE_IMAGE_SELECTION_KEY))) {
                    continue;
                }
                if (WordImportSupport.isImported(data)) {
                    WordImportSupport.prepare(document, data);
                    continue;
                }
                if (isSurroundingEnvironmentKey(key)) {
                    continue;
                }

                if (type == 0) {
                    String placeholder = (key != null && key.startsWith("${")) ? key : "${" + key + "}";
                    replacePlaceholderWithBodyFormat(document, placeholder, value);
                }
            } catch (Exception e) {
                log.error("处理字段出错: key={}, type={}, error={}", key, type, e.getMessage(), e);
            }
        }

        try {
            generateCauseAnalysisFromSelection(document, dataMap, groupTasks, project);
        } catch (Exception e) {
            log.error("处理大桥成因分析出错: error={}", e.getMessage(), e);
            writeFocusOnDiseases(document, "【病害数据处理失败，请联系管理员】");
        }

        try {
            ReportData environmentData = findSurroundingEnvironmentData(dataMap);
            generateBridgeSiteEnvironment(document, groupTasks,
                    environmentData == null ? null : environmentData.getValue());
        } catch (Exception e) {
            log.error("生成桥址周边环境调查内容失败: buildingId={}",
                    firstBuilding == null ? null : firstBuilding.getId(), e);
        }
    }

    /**
     * 成因分析按填报页独立勾选生成；未保存过选择时默认 3 类及以上。
     */
    private void generateCauseAnalysisFromSelection(XWPFDocument document, Map<String, ReportData> dataMap,
                                                    List<Task> groupTasks, Project project) {
        ReportData data = findReportDataByKeyPart(dataMap, "focusDiseases");
        String value = data == null ? null : data.getValue();
        try {
            Map<Long, Set<Long>> recordIds = FocusDiseaseSelection.parseRecordIds(value);
            if (recordIds != null) {
                generateSelectedFocusDiseases(document, recordIds, groupTasks);
                return;
            }
        } catch (Exception e) {
            log.error("解析成因分析选择失败: value={}, error={}", value, e.getMessage(), e);
            writeFocusOnDiseases(document, "【病害数据处理失败，请联系管理员】");
            return;
        }
        List<ComponentDiseaseType> combinations = parseChooseDiseaseJson(value);
        if (!combinations.isEmpty()) {
            generateGroupFocusOnDiseases(document, combinations, groupTasks, project);
            return;
        }
        generateSelectedFocusDiseases(document, null, groupTasks);
    }

    private ReportData findReportDataByKeyPart(Map<String, ReportData> dataMap, String keyPart) {
        if (dataMap == null || keyPart == null || keyPart.isEmpty()) {
            return null;
        }
        ReportData exact = dataMap.get("${" + keyPart + "}");
        if (exact != null) {
            return exact;
        }
        exact = dataMap.get(keyPart);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ReportData> entry : dataMap.entrySet()) {
            if (entry.getKey() != null && entry.getKey().contains(keyPart)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * “桥址周边环境”在历史数据中由两部分组成：
     * <ul>
     *     <li>桥梁信息卡属性树中的“环境条件”，用于报告概述；</li>
     *     <li>本次任务下与桥下空间、跨江/河流、电线、周边建筑等有关的现场记录，
     *     其照片作为 disease 附件保存。</li>
     * </ul>
     */
    private void generateBridgeSiteEnvironment(XWPFDocument document, List<Task> groupTasks,
                                               String manualSummary) throws Exception {
        if (document == null || groupTasks == null || groupTasks.isEmpty()) {
            return;
        }

        XWPFParagraph anchor = findSurroundingEnvironmentAnchor(document);
        if (anchor == null) {
            log.warn("多桥模板未找到“周边环境调查”锚点，跳过桥址周边环境导出");
            return;
        }

        List<Disease> environmentRecords = new ArrayList<>();
        for (Task task : groupTasks) {
            if (task.getBuilding() != null) {
                environmentRecords.addAll(loadBridgeSiteEnvironmentRecords(task.getBuilding(), task));
            }
        }
        Building firstBuilding = groupTasks.get(0).getBuilding();
        String propertySummary = firstBuilding == null ? null : loadEnvironmentCondition(firstBuilding);
        String summary = firstNonBlank(manualSummary, propertySummary, buildEnvironmentSummary(environmentRecords));

        if (isBlank(summary) && environmentRecords.isEmpty()) {
            clearParagraph(anchor);
            return;
        }

        XmlCursor cursor = anchor.getCTP().newCursor();
        if (!isBlank(summary)) {
            insertFilledBodyParagraphs(document, cursor, normalizeEnvironmentSummary(summary));
        }

        if (!environmentRecords.isEmpty()) {
            Map<Long, List<String>> imageReferences = collectEnvironmentImageReferences(environmentRecords);
            WordFieldUtils.createTableCaption(document, "周边环境检查结果一览表", cursor, 3);
            XWPFTable table = document.insertNewTbl(cursor);
            cursor.toNextToken();
            populateBridgeSiteEnvironmentTable(table, environmentRecords, imageReferences);

            insertDiseaseImagesWithStreaming(document, environmentRecords, imageReferences, cursor);
        }

        clearParagraph(anchor);
    }

    private XWPFParagraph findSurroundingEnvironmentAnchor(XWPFDocument document) {
        XWPFParagraph placeholder = ReportGenerateTools.findParagraphByPlaceholder(document, "${surroundingEnvironment}");
        if (placeholder != null) {
            return placeholder;
        }

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            String text = paragraphs.get(i).getText();
            if (text != null && text.replaceAll("\\s+", "").contains("周边环境调查")) {
                // 当前资源模板在标题后已有一个空白段落；把它作为插入锚点，避免把内容插到标题前。
                if (i + 1 < paragraphs.size()) {
                    XWPFParagraph nextParagraph = paragraphs.get(i + 1);
                    String nextText = nextParagraph.getText();
                    if (isBlank(nextText) || nextText.contains("${surroundingEnvironment}")) {
                        return nextParagraph;
                    }
                }
                log.warn("“周边环境调查”标题后未预留空白段落，跳过自动插入以保护后续模板内容");
                return null;
            }
        }
        return null;
    }

    private List<Disease> loadBridgeSiteEnvironmentRecords(Building building, Task task) {
        Disease query = new Disease();
        query.setBuildingId(building.getId());
        query.setTaskId(task.getId());
        List<Disease> diseases = diseaseMapper.selectDiseaseList(query);
        if (diseases == null || diseases.isEmpty()) {
            return Collections.emptyList();
        }
        return diseases.stream()
                .filter(this::isBridgeSiteEnvironmentRecord)
                .sorted(Comparator.comparing(Disease::getPosition, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Disease::getId, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());
    }

    private boolean isBridgeSiteEnvironmentRecord(Disease disease) {
        if (disease == null) {
            return false;
        }
        String text = String.join(" ", safeText(disease.getPosition()), safeText(disease.getType()),
                safeText(disease.getDescription()), safeText(disease.getBiObjectName()),
                disease.getDiseaseType() == null ? "" : safeText(disease.getDiseaseType().getName()));
        return text.contains("桥下") || text.contains("跨江") || text.contains("河流") || text.contains("水沟")
                || text.contains("电线") || text.contains("电缆") || text.contains("建筑") || text.contains("房屋")
                || text.contains("周边") || text.contains("环境") || text.contains("违建") || text.contains("空间占用");
    }

    private String loadEnvironmentCondition(Building building) {
        if (building.getRootPropertyId() == null) {
            return null;
        }
        try {
            return findPropertyValue(propertyService.selectPropertyTree(building.getRootPropertyId()), "环境条件");
        } catch (Exception e) {
            log.warn("读取桥梁环境条件属性失败: buildingId={}", building.getId(), e);
            return null;
        }
    }

    private String findPropertyValue(Property property, String propertyName) {
        if (property == null) {
            return null;
        }
        if (propertyName.equals(property.getName()) && !isBlank(property.getValue())) {
            return property.getValue();
        }
        if (property.getChildren() != null) {
            for (Property child : property.getChildren()) {
                String value = findPropertyValue(child, propertyName);
                if (!isBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String buildEnvironmentSummary(List<Disease> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        String descriptions = records.stream()
                .map(this::environmentDescription)
                .filter(value -> !isBlank(value))
                .distinct()
                .collect(Collectors.joining("；"));
        return isBlank(descriptions) ? null : "经现场检查，" + descriptions + "。";
    }

    private String normalizeEnvironmentSummary(String summary) {
        String normalized = summary.trim();
        return normalized.endsWith("。") || normalized.endsWith("；") ? normalized : normalized + "。";
    }

    private void populateBridgeSiteEnvironmentTable(XWPFTable table, List<Disease> records,
                                                    Map<Long, List<String>> imageReferences) {
        int rowCount = records.size() + 1;
        XWPFTableRow firstRow = table.getRow(0);
        while (firstRow.getTableCells().size() < 5) {
            firstRow.createCell();
        }
        for (int i = 1; i < rowCount; i++) {
            XWPFTableRow row = table.createRow();
            while (row.getTableCells().size() < 5) {
                row.createCell();
            }
        }
        applyTableGrid(table, 5);
        applyBridgeSiteEnvironmentTableLayout(table);

        String[] headers = {"序号", "桥梁部位", "周边环境情况描述", "照片编号", "备注"};
        for (int i = 0; i < headers.length; i++) {
            setTableCell(table.getRow(0), i, headers[i], true);
        }
        ReportGenerateTools.setTableHeaderRepeat(table, 1);

        for (int i = 0; i < records.size(); i++) {
            Disease record = records.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            setTableCell(row, 0, String.valueOf(i + 1));
            setTableCell(row, 1, environmentLocation(record));
            setTableCell(row, 2, environmentDescription(record), false, ParagraphAlignment.LEFT);
            setEnvironmentPhotoReferenceCell(row, 3, imageReferences.get(record.getId()));
            setTableCell(row, 4, "/");
        }
    }

    private void applyBridgeSiteEnvironmentTableLayout(XWPFTable table) {
        // 总宽 17.5 cm（9921 twips），按原列宽比例分配并保证合计一致。
        final int[] widths = {656, 1731, 4715, 1611, 1208};
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tableWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tableWidth.setW(BigInteger.valueOf(Arrays.stream(widths).sum()));
        tableWidth.setType(STTblWidth.DXA);
        CTTblLayoutType tblLayout = tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout();
        tblLayout.setType(STTblLayoutType.FIXED);

        CTTblGrid grid = table.getCTTbl().getTblGrid();
        if (grid == null) {
            grid = table.getCTTbl().addNewTblGrid();
        }
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
        for (XWPFTableRow row : table.getRows()) {
            for (int col = 0; col < widths.length; col++) {
                XWPFTableCell cell = row.getCell(col);
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidth.setW(BigInteger.valueOf(widths[col]));
                cellWidth.setType(STTblWidth.DXA);
            }
        }
    }

    private Map<Long, List<String>> collectEnvironmentImageReferences(List<Disease> records) {
        Map<Long, List<String>> references = new HashMap<>();
        for (Disease record : records) {
            List<String> itemReferences = new ArrayList<>();
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(record.getId());
            int imageIndex = 1;
            for (Map<String, Object> image : images) {
                if (Boolean.TRUE.equals(image.get("isImage"))) {
                    String bookmark = "Figure_Environment_" + record.getId() + "_" + imageIndex++;
                    itemReferences.add(bookmark + "|" + buildEnvironmentImageCaption(record));
                }
            }
            references.put(record.getId(), itemReferences);
        }
        return references;
    }

    private void setEnvironmentPhotoReferenceCell(XWPFTableRow row, int cellIndex, List<String> references) {
        if (references == null || references.isEmpty()) {
            setTableCell(row, cellIndex, "/");
            return;
        }
        setTableCell(row, cellIndex, "");
        XWPFParagraph paragraph = row.getCell(cellIndex).getParagraphs().get(0);
        for (int i = 0; i < references.size(); i++) {
            if (i > 0) {
                paragraph.createRun().setText("、");
            }
            String bookmark = references.get(i).split("\\|", 2)[0];
            WordFieldUtils.createChapterFigureReference(paragraph, bookmark, "图 ", "");
        }
    }

    private String environmentLocation(Disease record) {
        return firstNonBlank(record.getPosition(), record.getBiObjectName(), "桥址周边");
    }

    private String environmentDescription(Disease record) {
        return firstNonBlank(record.getDescription(), record.getType(),
                record.getDiseaseType() == null ? null : record.getDiseaseType().getName(), "现场检查记录");
    }

    private String buildEnvironmentImageCaption(Disease record) {
        return environmentLocation(record) + environmentDescription(record);
    }

    private ReportData findSurroundingEnvironmentData(Map<String, ReportData> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return null;
        }
        for (ReportData data : dataMap.values()) {
            if (data != null && isSurroundingEnvironmentKey(data.getKey())) {
                return data;
            }
        }
        return null;
    }

    private boolean isSurroundingEnvironmentKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("${", "").replace("}", "").trim();
        return "surroundingEnvironment".equals(normalized);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }


    /**
     * 3.9（1）碳化状况、（2）锈蚀电位状况。共用模板占位符 ${carbonizationAssessment}。
     */
    private void handleCarbonizationAssessment(XWPFDocument document, List<Task> groupTasks,
                                               AtomicInteger chapter3TableCounter) {
        XWPFParagraph target = ReportGenerateTools.findParagraphByPlaceholder(
                document, CARBONIZATION_ASSESSMENT_PLACEHOLDER);
        if (target == null) {
            log.warn("未找到占位符 {}，跳过碳化及锈蚀电位评定表", CARBONIZATION_ASSESSMENT_PLACEHOLDER);
            return;
        }
        if (chapter3TableCounter == null) {
            chapter3TableCounter = new AtomicInteger(1);
        }
        XmlCursor cursor = target.getCTP().newCursor();
        try {
            writeCarbonizationSection(document, cursor, collectCarbonizationRows(groupTasks), chapter3TableCounter);
            writeCorrosionSection(document, cursor, collectCorrosionRows(groupTasks), chapter3TableCounter);
        } finally {
            cursor.dispose();
        }
        removeParagraph(document, target);
    }

    private void writeCarbonizationSection(XWPFDocument document, XmlCursor cursor,
                                           List<CarbonizationAssessmentTableBuilder.Row> rows,
                                           AtomicInteger chapter3TableCounter) {
        XWPFParagraph title = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        title.createRun().setText("（1）碳化状况检测评定结果");
        applyBodyFormat(title, true);

        XWPFParagraph intro = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        if (rows.isEmpty()) {
            intro.createRun().setText("本次检测未进行混凝土碳化状况检测。");
            applyBodyFormat(intro, true);
            return;
        }
        String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(
                document, "混凝土构件碳化状况评定表", cursor, 3, chapter3TableCounter, 21, 360, false, 0);
        WordFieldUtils.createChapterTableReference(
                intro, tableBookmark, buildCarbonizationIntroPrefix(rows), "所示。");
        applyBodyFormat(intro, true);
        XWPFTable table = document.insertNewTbl(cursor);
        cursor.toNextToken();
        fillCarbonizationAssessmentTable(table, rows);
        String conclusion = CarbonizationAssessmentTableBuilder.conclusion(rows);
        if (!conclusion.isEmpty()) {
            XWPFParagraph paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
            paragraph.createRun().setText(conclusion);
            applyBodyFormat(paragraph, true);
        }
    }

    private void writeCorrosionSection(XWPFDocument document, XmlCursor cursor,
                                       List<RebarCorrosionAssessmentTableBuilder.Row> rows,
                                       AtomicInteger chapter3TableCounter) {
        XWPFParagraph title = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        title.createRun().setText("（2）锈蚀电位状况检测评定结果");
        applyBodyFormat(title, true);

        XWPFParagraph intro = document.insertNewParagraph(cursor);
        cursor.toNextToken();
        if (rows.isEmpty()) {
            intro.createRun().setText("本次检测未进行钢筋锈蚀电位检测。");
            applyBodyFormat(intro, true);
            return;
        }
        String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(
                document, "钢筋锈蚀电位评定表", cursor, 3, chapter3TableCounter, 21, 360, false, 0);
        WordFieldUtils.createChapterTableReference(
                intro, tableBookmark, buildCorrosionIntroPrefix(rows), "所示。");
        applyBodyFormat(intro, true);
        XWPFTable table = document.insertNewTbl(cursor);
        cursor.toNextToken();
        fillCorrosionAssessmentTable(table, rows);
    }

    private List<CarbonizationAssessmentTableBuilder.Row> collectCarbonizationRows(List<Task> groupTasks) {
        List<CarbonizationAssessmentTableBuilder.Row> rows = new ArrayList<>();
        if (groupTasks == null) {
            return rows;
        }
        for (Task task : groupTasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            String subBridgeName = task.getBuilding() == null ? "" : safeText(task.getBuilding().getName());
            try {
                String carbonJson = taskSheetService.getSheetJsonContent(
                        task.getId(), ITaskSheetService.SHEET_TYPE_CARBON_DEPTH);
                String coverJson = taskSheetService.getSheetJsonContent(
                        task.getId(), ITaskSheetService.SHEET_TYPE_REBAR_COVER);
                rows.addAll(CarbonizationAssessmentTableBuilder.build(subBridgeName, carbonJson, coverJson));
            } catch (Exception e) {
                log.warn("读取碳化/保护层检测记录失败: taskId={}, error={}", task.getId(), e.getMessage());
            }
        }
        return rows;
    }

    private List<RebarCorrosionAssessmentTableBuilder.Row> collectCorrosionRows(List<Task> groupTasks) {
        List<RebarCorrosionAssessmentTableBuilder.Row> rows = new ArrayList<>();
        if (groupTasks == null) {
            return rows;
        }
        for (Task task : groupTasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            String subBridgeName = task.getBuilding() == null ? "" : safeText(task.getBuilding().getName());
            try {
                String corrosionJson = taskSheetService.getSheetJsonContent(
                        task.getId(), ITaskSheetService.SHEET_TYPE_REBAR_CORROSION);
                rows.addAll(RebarCorrosionAssessmentTableBuilder.build(subBridgeName, corrosionJson));
            } catch (Exception e) {
                log.warn("读取锈蚀电位检测记录失败: taskId={}, error={}", task.getId(), e.getMessage());
            }
        }
        return rows;
    }

    private String buildCarbonizationIntroPrefix(List<CarbonizationAssessmentTableBuilder.Row> rows) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (CarbonizationAssessmentTableBuilder.Row row : rows) {
            String bridge = isBlank(row.bridgeName) ? "该桥" : row.bridgeName.trim();
            counts.merge(bridge, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getKey() + "选择" + entry.getValue() + "个构件");
        }
        return "本次检测对" + String.join("、", parts) + "，测试其混凝土碳化状况，测试结果见下表";
    }

    private String buildCorrosionIntroPrefix(List<RebarCorrosionAssessmentTableBuilder.Row> rows) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (RebarCorrosionAssessmentTableBuilder.Row row : rows) {
            String bridge = isBlank(row.bridgeName) ? "该桥" : row.bridgeName.trim();
            counts.merge(bridge, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(entry.getKey() + "选择" + entry.getValue() + "个测区");
        }
        return "本次检测对" + String.join("、", parts) + "，测试其锈蚀电位状况，测试结果见下表";
    }

    private void fillCarbonizationAssessmentTable(XWPFTable table,
                                                  List<CarbonizationAssessmentTableBuilder.Row> rows) {
        int columnCount = 7;
        XWPFTableRow header = table.getRow(0);
        while (header.getTableCells().size() < columnCount) {
            header.createCell();
        }
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = table.createRow();
            while (row.getTableCells().size() < columnCount) {
                row.createCell();
            }
        }
        applyTableGrid(table, columnCount);

        String[] headers = {
                "序号", "桥梁名称", "构件名称",
                "平均碳化深度(mm)①", "实测保护层厚度平均值(mm)②",
                "比值Kc=①/②", "评定标度"
        };
        for (int i = 0; i < headers.length; i++) {
            setTableCell(header, i, headers[i], true);
        }
        ReportGenerateTools.setTableHeaderRepeat(table, 1);

        // 17.5 cm = 9921 twips; preserve the original column proportions.
        int[] widths = {728, 1665, 2289, 1457, 1873, 1145, 764};
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(9921));
        tblWidth.setType(STTblWidth.DXA);

        int mergeStart = 1;
        for (int i = 0; i < rows.size(); i++) {
            CarbonizationAssessmentTableBuilder.Row item = rows.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            setTableCell(row, 0, String.valueOf(i + 1));
            setTableCell(row, 1, item.bridgeName);
            setTableCell(row, 2, item.componentName);
            setTableCell(row, 3, item.carbonAverageText);
            setTableCell(row, 4, item.coverAverageText);
            setTableCell(row, 5, item.kcText);
            setTableCell(row, 6, item.scaleText);
            boolean last = i == rows.size() - 1;
            boolean nextDifferent = !last && !Objects.equals(item.bridgeName, rows.get(i + 1).bridgeName);
            if (last || nextDifferent) {
                mergeVerticalCells(table, mergeStart, i + 1, 1);
                mergeStart = i + 2;
            }
        }

        for (XWPFTableRow row : table.getRows()) {
            for (int col = 0; col < widths.length && col < row.getTableCells().size(); col++) {
                XWPFTableCell cell = row.getCell(col);
                if (cell == null) {
                    continue;
                }
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidth.setW(BigInteger.valueOf(widths[col]));
                cellWidth.setType(STTblWidth.DXA);
            }
        }
    }

    private void fillCorrosionAssessmentTable(XWPFTable table,
                                              List<RebarCorrosionAssessmentTableBuilder.Row> rows) {
        int valueCols = RebarCorrosionAssessmentTableBuilder.VALUE_COLUMNS;
        int valueRows = RebarCorrosionAssessmentTableBuilder.VALUE_ROWS;
        int columnCount = 8 + valueCols;
        int headerRows = 1;
        int dataRows = rows.size() * valueRows;
        ensureTableSize(table, headerRows + dataRows, columnCount);
        applyTableGrid(table, columnCount);

        String[] fixedHeaders = {"序号", "桥梁名称", "测区位置"};
        for (int i = 0; i < fixedHeaders.length; i++) {
            setTableCell(table.getRow(0), i, fixedHeaders[i], true);
        }
        setTableCell(table.getRow(0), 3, "测试值", true);
        mergeHorizontalCells(table, 0, 3, 3 + valueCols - 1);
        String[] tailHeaders = {"最小值", "测试温度", "修正值", "修正后最小值", "评定标度"};
        for (int i = 0; i < tailHeaders.length; i++) {
            setTableCell(table.getRow(0), 3 + valueCols + i, tailHeaders[i], true);
        }
        ReportGenerateTools.setTableHeaderRepeat(table, 1);

        // 总宽仍为 17.5 cm；给 5 个测试值和最小值更多空间，文字列可自行换行。
        int[] widths = {520, 1145, 1250, 780, 780, 780, 780, 780, 780, 600, 520, 600, 606};
        applyColumnWidths(table, widths, 9921);

        int bridgeMergeStart = headerRows;
        for (int i = 0; i < rows.size(); i++) {
            RebarCorrosionAssessmentTableBuilder.Row item = rows.get(i);
            int start = headerRows + i * valueRows;
            int end = start + valueRows - 1;
            for (int r = 0; r < valueRows; r++) {
                XWPFTableRow row = table.getRow(start + r);
                if (r == 0) {
                    setTableCell(row, 0, String.valueOf(i + 1));
                    setTableCell(row, 1, item.bridgeName);
                    setTableCell(row, 2, item.location);
                    setTableCell(row, 3 + valueCols, item.minText);
                    keepNumericCellOnOneLine(row.getCell(3 + valueCols));
                    setTableCell(row, 4 + valueCols, item.temperatureText);
                    keepNumericCellOnOneLine(row.getCell(4 + valueCols));
                    setTableCell(row, 5 + valueCols, item.correctionText);
                    keepNumericCellOnOneLine(row.getCell(5 + valueCols));
                    setTableCell(row, 6 + valueCols, item.correctedMinText);
                    keepNumericCellOnOneLine(row.getCell(6 + valueCols));
                    setTableCell(row, 7 + valueCols, item.scaleText);
                    keepNumericCellOnOneLine(row.getCell(7 + valueCols));
                }
                for (int c = 0; c < valueCols; c++) {
                    int valueIndex = r * valueCols + c;
                    String value = valueIndex < item.values.size() ? item.values.get(valueIndex) : "";
                    setTableCell(row, 3 + c, value);
                    keepNumericCellOnOneLine(row.getCell(3 + c));
                }
            }
            mergeVerticalCells(table, start, end, 0);
            mergeVerticalCells(table, start, end, 2);
            for (int col = 3 + valueCols; col < columnCount; col++) {
                mergeVerticalCells(table, start, end, col);
            }
            boolean last = i == rows.size() - 1;
            boolean nextDifferent = !last && !Objects.equals(item.bridgeName, rows.get(i + 1).bridgeName);
            if (last || nextDifferent) {
                mergeVerticalCells(table, bridgeMergeStart, end, 1);
                bridgeMergeStart = end + 1;
            }
        }
    }

    private void ensureTableSize(XWPFTable table, int rowCount, int columnCount) {
        XWPFTableRow first = table.getRow(0);
        while (first.getTableCells().size() < columnCount) {
            first.createCell();
        }
        while (table.getNumberOfRows() < rowCount) {
            XWPFTableRow row = table.createRow();
            while (row.getTableCells().size() < columnCount) {
                row.createCell();
            }
        }
        for (XWPFTableRow row : table.getRows()) {
            while (row.getTableCells().size() < columnCount) {
                row.createCell();
            }
        }
    }

    private void applyColumnWidths(XWPFTable table, int[] widths, int totalWidth) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(totalWidth));
        tblWidth.setType(STTblWidth.DXA);
        CTTblLayoutType layout = tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid();
        if (grid == null) {
            grid = table.getCTTbl().addNewTblGrid();
        }
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
        for (XWPFTableRow row : table.getRows()) {
            for (int col = 0; col < widths.length && col < row.getTableCells().size(); col++) {
                XWPFTableCell cell = row.getCell(col);
                if (cell == null) {
                    continue;
                }
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidth.setW(BigInteger.valueOf(widths[col]));
                cellWidth.setType(STTblWidth.DXA);
            }
        }
    }

    private void keepNumericCellOnOneLine(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        if (!tcPr.isSetNoWrap()) {
            tcPr.addNewNoWrap();
        }
        CTTcMar margins = tcPr.isSetTcMar() ? tcPr.getTcMar() : tcPr.addNewTcMar();
        CTTblWidth left = margins.isSetLeft() ? margins.getLeft() : margins.addNewLeft();
        left.setW(BigInteger.valueOf(40));
        left.setType(STTblWidth.DXA);
        CTTblWidth right = margins.isSetRight() ? margins.getRight() : margins.addNewRight();
        right.setW(BigInteger.valueOf(40));
        right.setType(STTblWidth.DXA);
    }

    /**
     * 3.10.1 重点关注病害：按填报页勾选生成汇总表；未保存过选择时默认 3 类及以上。
     * 模板占位符：${keyConcernDiseases}
     */
    private void generateKeyConcernDiseases(XWPFDocument document, String groupName,
                                            List<Task> groupTasks, Project project,
                                            AtomicInteger chapter3TableCounter,
                                            Map<String, ReportData> dataMap) {
        XWPFParagraph placeholder = ReportGenerateTools.findParagraphByPlaceholder(
                document, KEY_CONCERN_DISEASES_PLACEHOLDER);
        if (placeholder == null) {
            log.warn("未找到占位符 {}，跳过重点关注病害汇总", KEY_CONCERN_DISEASES_PLACEHOLDER);
            return;
        }
        if (chapter3TableCounter == null) {
            chapter3TableCounter = new AtomicInteger(1);
        }

        Map<Long, Set<Long>> selections = parseDiseaseSelection(dataMap, KEY_CONCERN_SELECTION_KEY);
        List<Disease> diseases = collectKeyConcernDiseases(groupTasks, project, selections);
        Map<Long, Component> componentMap = loadComponents(diseases);
        Map<Long, BiObject> objectById = loadObjectsForTasks(groupTasks);
        diseases.sort((left, right) -> compareKeyConcernDiseases(left, right, componentMap, objectById));

        XmlCursor cursor = placeholder.getCTP().newCursor();
        XWPFParagraph intro = document.insertNewParagraph(cursor);
        cursor.toNextToken();

        if (diseases.isEmpty()) {
            XWPFRun run = intro.createRun();
            run.setText(selections != null ? "无重点关注病害。" : "未见3类及以上病害。");
            applyBodyFormat(intro, true);
            removeParagraph(document, placeholder);
            return;
        }

        String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(
                document, "重点关注病害汇总表", cursor, 3, chapter3TableCounter, 21, 360, false, 0);
        WordFieldUtils.createChapterTableReference(
                intro, tableBookmark, buildKeyConcernIntroPrefix(groupName, diseases, componentMap), "所示。");
        applyBodyFormat(intro, true);

        XWPFTable table = document.insertNewTbl(cursor);
        cursor.toNextToken();
        fillKeyConcernDiseaseTable(table, diseases, componentMap, objectById);
        removeParagraph(document, placeholder);
    }

    private List<Disease> collectKeyConcernDiseases(List<Task> groupTasks, Project project,
                                                    Map<Long, Set<Long>> selections) {
        if (selections != null) {
            return collectSelectedDiseases(groupTasks, selections);
        }
        return collectLevel3OrAboveDiseases(groupTasks, project);
    }

    private List<Disease> collectSelectedDiseases(List<Task> groupTasks, Map<Long, Set<Long>> selections) {
        List<Disease> diseases = new ArrayList<>();
        if (groupTasks == null || selections == null) {
            return diseases;
        }
        for (Task task : groupTasks) {
            if (task == null || task.getBuilding() == null
                    || selections.getOrDefault(task.getId(), Set.of()).isEmpty()) {
                continue;
            }
            diseases.addAll(FocusDiseaseSelection.selectedRecords(
                    selections, task.getId(), queryTaskDiseases(task)));
        }
        return diseases;
    }

    private List<Disease> collectLevel3OrAboveDiseases(List<Task> groupTasks, Project project) {
        List<Disease> diseases = new ArrayList<>();
        if (groupTasks == null) {
            return diseases;
        }
        Long projectId = project == null ? null : project.getId();
        for (Task task : groupTasks) {
            if (task == null || task.getBuilding() == null) {
                continue;
            }
            Disease query = new Disease();
            query.setBuildingId(task.getBuilding().getId());
            query.setProjectId(projectId);
            List<Disease> taskDiseases = diseaseMapper.selectDiseaseList(query);
            if (taskDiseases == null) {
                continue;
            }
            for (Disease disease : taskDiseases) {
                if (isLevel3OrAbove(disease)) {
                    diseases.add(disease);
                }
            }
        }
        return diseases;
    }

    private List<Disease> queryTaskDiseases(Task task) {
        Disease query = new Disease();
        query.setTaskId(task.getId());
        query.setBuildingId(task.getBuilding().getId());
        List<Disease> records = diseaseMapper.selectDiseaseList(query);
        return records == null ? Collections.emptyList() : records;
    }

    private static boolean isLevel3OrAbove(Disease disease) {
        return disease != null && disease.getLevel() >= 3 && !isExcludedFromInspectionResultTable(disease);
    }

    /** 未保存返回 null，表示外观检测仍生成全部病害照片；保存后只生成勾选的病害照片。 */
    private Map<Long, Set<Long>> parseAppearanceImageSelection(Map<String, ReportData> dataMap) {
        ReportData data = findReportDataByKeyPart(dataMap, APPEARANCE_IMAGE_SELECTION_KEY);
        if (data == null || data.getValue() == null || data.getValue().isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(data.getValue());
            if (root == null || !root.isObject() || root.path("version").asInt() != 3 || !root.path("tasks").isObject()) {
                return null;
            }
            Map<Long, Set<Long>> result = new LinkedHashMap<>();
            var tasks = root.get("tasks").fields();
            while (tasks.hasNext()) {
                var task = tasks.next();
                long taskId = Long.parseLong(task.getKey());
                Set<Long> ids = new LinkedHashSet<>();
                if (task.getValue().isArray()) {
                    for (JsonNode id : task.getValue()) {
                        ids.add(Long.parseLong(id.asText()));
                    }
                }
                result.put(taskId, ids);
            }
            return result;
        } catch (Exception e) {
            log.warn("解析外观检测图片选择失败，按默认规则生成照片: {}", e.getMessage());
            return null;
        }
    }

    private Map<Long, Set<Long>> parseDiseaseSelection(Map<String, ReportData> dataMap, String keyPart) {
        ReportData data = findReportDataByKeyPart(dataMap, keyPart);
        if (data == null) {
            return null;
        }
        try {
            return FocusDiseaseSelection.parseRecordIds(data.getValue());
        } catch (Exception e) {
            log.warn("解析病害选择失败，回退为3类及以上: keyPart={}, error={}", keyPart, e.getMessage());
            return null;
        }
    }

    private Map<Long, Component> loadComponents(List<Disease> diseases) {
        List<Long> componentIds = diseases.stream()
                .map(Disease::getComponentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (componentIds.isEmpty()) {
            return Map.of();
        }
        List<Component> components = componentService.selectComponentsByIds(componentIds);
        if (components == null || components.isEmpty()) {
            return Map.of();
        }
        return components.stream()
                .filter(Objects::nonNull)
                .filter(component -> component.getId() != null)
                .collect(Collectors.toMap(Component::getId, component -> component, (left, right) -> left));
    }

    private Map<Long, BiObject> loadObjectsForTasks(List<Task> groupTasks) {
        Map<Long, BiObject> objectById = new HashMap<>();
        for (Task task : groupTasks) {
            if (task == null || task.getBuilding() == null || task.getBuilding().getRootObjectId() == null) {
                continue;
            }
            BiObject root = biObjectMapper.selectBiObjectById(task.getBuilding().getRootObjectId());
            if (root != null && root.getId() != null) {
                objectById.put(root.getId(), root);
            }
            List<BiObject> children = biObjectMapper.selectChildrenById(task.getBuilding().getRootObjectId());
            if (children == null) {
                continue;
            }
            for (BiObject object : children) {
                if (object != null && object.getId() != null) {
                    objectById.put(object.getId(), object);
                }
            }
        }
        return objectById;
    }

    private int compareKeyConcernDiseases(Disease left, Disease right,
                                          Map<Long, Component> componentMap,
                                          Map<Long, BiObject> objectById) {
        int structure = Integer.compare(
                keyConcernStructureOrder(resolveKeyConcernStructure(left, objectById)),
                keyConcernStructureOrder(resolveKeyConcernStructure(right, objectById)));
        if (structure != 0) {
            return structure;
        }
        Component leftComponent = left.getComponentId() == null ? null : componentMap.get(left.getComponentId());
        Component rightComponent = right.getComponentId() == null ? null : componentMap.get(right.getComponentId());
        String leftCode = leftComponent == null ? "" : safeText(leftComponent.getCode());
        String rightCode = rightComponent == null ? "" : safeText(rightComponent.getCode());
        int code = compareCodes(leftCode, rightCode);
        if (code != 0) {
            return code;
        }
        return Long.compare(
                left.getId() == null ? 0L : left.getId(),
                right.getId() == null ? 0L : right.getId());
    }

    private int keyConcernStructureOrder(String structure) {
        int index = KEY_CONCERN_STRUCTURE_ORDER.indexOf(structure);
        return index < 0 ? KEY_CONCERN_STRUCTURE_ORDER.size() : index;
    }

    private String buildKeyConcernIntroPrefix(String groupName, List<Disease> diseases,
                                              Map<Long, Component> componentMap) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        for (Disease disease : diseases) {
            String location = keyConcernIntroLocation(disease, componentMap);
            String type = keyConcernTypeLabel(disease);
            if (isBlank(location) && isBlank(type)) {
                continue;
            }
            items.add(safeText(location) + safeText(type));
        }
        String joined = String.join("、", items);
        String bridge = isBlank(groupName) ? "该桥" : groupName.trim();
        if (items.size() > 1) {
            return bridge + "存在" + joined + "等病害，需引起重点关注，病害汇总表如表";
        }
        return bridge + "存在" + joined + "，需引起重点关注，病害汇总表如表";
    }

    private void fillKeyConcernDiseaseTable(XWPFTable table, List<Disease> diseases,
                                            Map<Long, Component> componentMap,
                                            Map<Long, BiObject> objectById) {
        int columnCount = 7;
        XWPFTableRow header = table.getRow(0);
        while (header.getTableCells().size() < columnCount) {
            header.createCell();
        }
        for (int i = 1; i < diseases.size() + 1; i++) {
            XWPFTableRow row = table.createRow();
            while (row.getTableCells().size() < columnCount) {
                row.createCell();
            }
        }
        applyTableGrid(table, columnCount);

        String[] headers = {"序号", "部位", "缺损位置", "缺损类型", "数量", "病害描述", "备注"};
        for (int i = 0; i < headers.length; i++) {
            setTableCell(header, i, headers[i], true);
        }
        ReportGenerateTools.setTableHeaderRepeat(table, 1);

        int[] widths = {700, 1200, 1400, 1200, 700, 3000, 1334};
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(9534));
        tblWidth.setType(STTblWidth.DXA);

        for (int i = 0; i < diseases.size(); i++) {
            Disease disease = diseases.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            setTableCell(row, 0, String.valueOf(i + 1));
            setTableCell(row, 1, resolveKeyConcernStructure(disease, objectById));
            setTableCell(row, 2, keyConcernDefectPosition(disease, componentMap));
            setTableCell(row, 3, keyConcernTypeLabel(disease));
            setTableCell(row, 4, disease.getQuantity() > 0 ? String.valueOf(disease.getQuantity()) : "/");
            setTableCell(row, 5, ReportGenerateTools.formatAppearanceDiseaseDescription(disease.getDescription()));
            setTableCell(row, 6, disease.getLevel() + "类病害");
        }

        for (XWPFTableRow row : table.getRows()) {
            for (int col = 0; col < widths.length; col++) {
                XWPFTableCell cell = row.getCell(col);
                if (cell == null) {
                    continue;
                }
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidth.setW(BigInteger.valueOf(widths[col]));
                cellWidth.setType(STTblWidth.DXA);
            }
        }
    }

    private String resolveKeyConcernStructure(Disease disease, Map<Long, BiObject> objectById) {
        BiObject current = disease == null ? null : objectById.get(disease.getBiObjectId());
        while (current != null) {
            if (KEY_CONCERN_STRUCTURE_ORDER.contains(current.getName())) {
                return current.getName();
            }
            current = current.getParentId() == null ? null : objectById.get(current.getParentId());
        }
        return "/";
    }

    private String keyConcernDefectPosition(Disease disease, Map<Long, Component> componentMap) {
        Component component = disease.getComponentId() == null ? null : componentMap.get(disease.getComponentId());
        if (component != null && !isBlank(component.getName())) {
            return component.getName();
        }
        if (!isBlank(disease.getPosition())) {
            return disease.getPosition();
        }
        return isBlank(disease.getBiObjectName()) ? "/" : disease.getBiObjectName();
    }

    private String keyConcernIntroLocation(Disease disease, Map<Long, Component> componentMap) {
        if (!isBlank(disease.getBiObjectName())) {
            return disease.getBiObjectName().trim();
        }
        String position = keyConcernDefectPosition(disease, componentMap);
        int hash = position.lastIndexOf('#');
        if (hash >= 0 && hash < position.length() - 1) {
            return position.substring(hash + 1).trim();
        }
        return "/".equals(position) ? "" : position;
    }

    private String keyConcernTypeLabel(Disease disease) {
        if (disease.getDiseaseType() != null && !isBlank(disease.getDiseaseType().getName())) {
            return ReportGenerateTools.reportDiseaseTypeNameIfCrack(disease);
        }
        return resolveDiseaseTypeLabel(disease);
    }

    /** 新版按具体病害 ID 生成；selections 为 null 时默认 3 类及以上。 */
    private void generateSelectedFocusDiseases(XWPFDocument document, Map<Long, Set<Long>> selections,
                                                List<Task> groupTasks) {
        boolean autoLevel3 = selections == null;
        if (groupTasks == null || groupTasks.isEmpty()) {
            writeFocusOnDiseases(document, autoLevel3 ? "未见3类及以上病害。" : "无重点关注病害");
            return;
        }
        StringBuilder content = new StringBuilder();
        int index = 1;
        for (Task task : groupTasks) {
            if (task == null || task.getBuilding() == null) continue;
            if (!autoLevel3 && selections.getOrDefault(task.getId(), Set.of()).isEmpty()) continue;
            List<Disease> records = queryTaskDiseases(task);
            List<Disease> selected = autoLevel3
                    ? records.stream().filter(LineMultiBridgeReportServiceImpl::isLevel3OrAbove).collect(Collectors.toList())
                    : FocusDiseaseSelection.selectedRecords(selections, task.getId(), records);
            if (selected.isEmpty()) continue;
            if (groupTasks.size() > 1) content.append(safeText(task.getBuilding().getName())).append("\n");
            Map<Long, Component> components = new HashMap<>();
            Map<Long, BiObject> objects = new HashMap<>();
            for (Disease disease : selected) {
                BiObject object = disease.getBiObjectId() == null ? null
                        : objects.computeIfAbsent(disease.getBiObjectId(), biObjectMapper::selectBiObjectById);
                Component component = disease.getComponentId() == null ? null
                        : components.computeIfAbsent(disease.getComponentId(), componentService::selectComponentById);
                String name = object == null ? safeText(disease.getBiObjectName()) : safeText(object.getName());
                content.append(index++).append(")").append(name);
                if (component != null && !isBlank(component.getCode())) {
                    content.append("（").append(component.getCode()).append("）");
                }
                if (!isBlank(disease.getPosition())) content.append(" ").append(disease.getPosition());
                content.append(" ").append(resolveDiseaseTypeLabel(disease)).append("\n");
                if (!isBlank(disease.getDescription())) {
                    content.append(ReportGenerateTools.formatAppearanceDiseaseDescription(disease.getDescription())).append("\n");
                }
                disease.setBuildingId(task.getBuilding().getId());
                String cause = disease.getCause();
                if (isBlank(cause)) {
                    try {
                        cause = getDiseaseCause(disease);
                    } catch (IllegalStateException e) {
                        log.warn("重点病害成因资料不完整，diseaseId={}: {}", disease.getId(), e.getMessage());
                    }
                }
                content.append("成因分析：\n").append(isBlank(cause) ? "需补充成因分析" : cause).append("\n");
            }
        }
        String text = content.toString().trim();
        String emptyText = autoLevel3 ? "未见3类及以上病害。" : "无重点关注病害";
        writeFocusOnDiseases(document, text.isEmpty() ? emptyText : text);
    }

    /** 兼容旧数据：按子桥分别写入勾选的构件 + 病害类型。 */
    private void generateGroupFocusOnDiseases(XWPFDocument document, List<ComponentDiseaseType> combinations,
                                              List<Task> groupTasks, Project project) {
        try {
            if (groupTasks == null || groupTasks.isEmpty()) {
                writeFocusOnDiseases(document, "无重点关注病害");
                return;
            }
            StringBuilder content = new StringBuilder();
            Integer year = project == null ? null : project.getYear();
            boolean hasTaskId = combinations.stream().anyMatch(item -> item.getTaskId() != null);
            boolean showBridgeName = groupTasks.size() > 1;
            int index = 1;

            for (Task task : groupTasks) {
                if (task == null || task.getBuilding() == null) {
                    continue;
                }
                List<ComponentDiseaseType> taskCombs = combinations.stream()
                        .filter(item -> !hasTaskId || task.getId().equals(item.getTaskId()))
                        .collect(Collectors.toList());
                if (taskCombs.isEmpty()) {
                    continue;
                }
                index += appendFocusDiseaseItems(content, taskCombs, task, year, index, showBridgeName);
            }

            String text = content.toString().trim();
            writeFocusOnDiseases(document, text.isEmpty() ? "无重点关注病害" : text);
        } catch (Exception e) {
            log.error("生成大桥重点关注病害内容失败", e);
            writeFocusOnDiseases(document, "【病害分析生成失败】");
        }
    }

    /**
     * 重点病害成因分析：宋体小四、两端对齐、首行缩进。
     */
    private void writeFocusOnDiseases(XWPFDocument document, String text) {
        try {
            insertEvaluationContent(document, "${focusOnDiseases}", text);
        } catch (Exception e) {
            log.warn("按段落写入重点病害成因分析失败，回退为文本替换", e);
            ReportGenerateTools.replaceText(document, "${focusOnDiseases}", text);
        }
    }

    /**
     * @return 实际写入的条目数
     */
    private int appendFocusDiseaseItems(StringBuilder content, List<ComponentDiseaseType> taskCombs,
                                        Task task, Integer year, int startIndex, boolean showBridgeName) {
        List<Long> componentIds = taskCombs.stream()
                .map(ComponentDiseaseType::getComponentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (componentIds.isEmpty()) {
            return 0;
        }
        List<Disease> diseases = diseaseMapper.selectDiseaseComponentData(
                componentIds, task.getBuilding().getId(), year);
        if (diseases == null || diseases.isEmpty()) {
            return 0;
        }

        Map<String, List<Disease>> grouped = diseases.stream()
                .filter(disease -> disease.getBiObjectId() != null && disease.getDiseaseTypeId() != null)
                .collect(Collectors.groupingBy(
                        disease -> disease.getBiObjectId() + "_" + disease.getDiseaseTypeId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        StringBuilder section = new StringBuilder();
        int index = startIndex;
        int written = 0;
        Set<String> writtenKeys = new HashSet<>();
        for (ComponentDiseaseType combination : taskCombs) {
            String key = combination.getComponentId() + "_" + combination.getDiseaseTypeId();
            if (!writtenKeys.add(key)) {
                continue;
            }
            List<Disease> matched = grouped.get(key);
            if (matched == null || matched.isEmpty()) {
                continue;
            }
            Disease disease = matched.get(0);
            BiObject component = biObjectMapper.selectBiObjectById(combination.getComponentId());
            if (component == null) {
                continue;
            }
            section.append(index).append(")");
            section.append(component.getName());
            section.append(resolveDiseaseTypeLabel(disease));
            section.append("\n成因分析：\n");
            // 批量查询返回的 Disease 不保证回填 buildingId；成因分析需要通过它获取桥梁根对象。
            disease.setBuildingId(task.getBuilding().getId());
            section.append(getDiseaseCause(disease));
            section.append("\n");
            index++;
            written++;
        }
        if (written == 0) {
            return 0;
        }
        if (showBridgeName && task.getBuilding().getName() != null) {
            content.append(task.getBuilding().getName()).append("\n");
        }
        content.append(section);
        return written;
    }

    private String resolveDiseaseTypeLabel(Disease disease) {
        String type = disease.getType();
        if (type != null) {
            int hash = type.lastIndexOf('#');
            return hash >= 0 ? type.substring(hash + 1) : type;
        }
        if (disease.getDiseaseType() != null && disease.getDiseaseType().getName() != null) {
            return disease.getDiseaseType().getName();
        }
        return "";
    }

    /**
     * 对于单个病害 生成 成因分析
     */
    private String getDiseaseCause(Disease disease) {
        CauseQuery causeQuery = new CauseQuery();
        Building building = buildingService.selectBuildingById(disease.getBuildingId());
        BiObject biObject = biObjectMapper.selectBiObjectById(disease.getBiObjectId());
        if (building == null) {
            throw new IllegalStateException("重点病害未找到所属桥梁，buildingId=" + disease.getBuildingId());
        }
        if (building.getRootObjectId() == null) {
            throw new IllegalStateException("重点病害所属桥梁缺少根对象，buildingId=" + building.getId());
        }
        if (biObject == null) {
            throw new IllegalStateException("重点病害未找到所属构件，biObjectId=" + disease.getBiObjectId());
        }
        BiObject biObject_building = biObjectMapper.selectBiObjectById(building.getRootObjectId());
        if (biObject_building == null) {
            throw new IllegalStateException("重点病害未找到桥梁根对象，rootObjectId=" + building.getRootObjectId());
        }
        BiTemplateObject biTemplateObject = biTemplateObjectService.selectBiTemplateObjectById(biObject_building.getTemplateObjectId());
        if (biTemplateObject == null) {
            throw new IllegalStateException("重点病害未找到桥梁模板对象，templateObjectId="
                    + biObject_building.getTemplateObjectId());
        }
        causeQuery.setTemplate(biTemplateObject.getName());
        causeQuery.setObject(biObject.getName());
        causeQuery.setParentObject(biObject.getParentName());
        causeQuery.setDescription(disease.getDescription());
        causeQuery.setPosition(disease.getPosition());
        causeQuery.setType(disease.getType());
        return diseaseService.getCauseAnalysis(causeQuery);
    }

    private boolean hasUploadedOverviewPhoto(Map<String, ReportData> dataMap) {
        return resolveUploadedImageName(dataMap, "leftFront") != null
                || resolveUploadedImageName(dataMap, "rightFront") != null
                || resolveUploadedImageName(dataMap, "leftSide") != null
                || resolveUploadedImageName(dataMap, "rightSide") != null;
    }

    private boolean hasUploadedOverviewPhoto(List<LineReportData> rows, String groupId) {
        if (rows == null || groupId == null) {
            return false;
        }
        return rows.stream().anyMatch(row -> groupId.equals(row.getGroupId())
                && row.getKey() != null
                && Set.of("leftFront", "rightFront", "leftSide", "rightSide").contains(row.getKey())
                && !isBlank(row.getValue()));
    }

    /**
     * 3.1 四张照片来自填报页上传，不再自动取桥梁卡片 newfront/newside。
     * 整体概况若来自 Word 导入，只要本组上传了照片，就用这四张盖掉导入内容里的正立面照。
     */
    private void insertGroupOverviewImages(XWPFDocument document, Map<String, ReportData> dataMap) throws Exception {
        String leftFront = resolveUploadedImageName(dataMap, "leftFront");
        String rightFront = resolveUploadedImageName(dataMap, "rightFront");
        String leftSide = resolveUploadedImageName(dataMap, "leftSide");
        String rightSide = resolveUploadedImageName(dataMap, "rightSide");

        replaceOverviewImage(document, "%{leftFront}", leftFront);
        replaceOverviewImage(document, "%{rightFront}", rightFront);
        replaceOverviewImage(document, "%{leftSide}", leftSide);
        replaceOverviewImage(document, "%{rightSide}", rightSide);
        replaceOverviewImage(document, "${桥梁正面照}", leftFront);
        replaceOverviewImage(document, "${桥梁正面照1}", rightFront != null ? rightFront : leftFront);
        replaceOverviewImage(document, "${桥梁立面照}", leftSide);
        replaceOverviewImage(document, "${桥梁立面照1}", rightSide != null ? rightSide : leftSide);
    }

    private void replaceOverviewImage(XWPFDocument document, String placeholder, String imageFileName) throws Exception {
        if (!isBlank(imageFileName)) {
            ReportGenerateTools.replaceImageInDocument(document, placeholder, imageFileName, null, false);
        } else {
            ReportGenerateTools.replaceText(document, placeholder, "");
        }
    }

    private String resolveUploadedImageName(Map<String, ReportData> dataMap, String key) {
        ReportData data = dataMap == null ? null : dataMap.get(key);
        if (data == null || isBlank(data.getValue())) {
            return null;
        }
        String firstId = data.getValue().split(",")[0].trim();
        if (firstId.isEmpty()) {
            return null;
        }
        try {
            FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(firstId));
            return fileMap == null ? null : fileMap.getNewName();
        } catch (NumberFormatException e) {
            log.warn("概况照片ID无效: key={}, value={}", key, firstId);
            return null;
        }
    }


    /**
     * 一座大桥一张近年评定对比表，行是该大桥下全部子桥。
     */
    private void handleComparisonAnalysis(XWPFDocument document, String key, List<Task> groupTasks,
                                          Map<String, BiEvaluation> importedPreviousEvaluations) {
        try {
            log.info("开始处理比较分析, key: {}, 子桥数量: {}", key, groupTasks == null ? 0 : groupTasks.size());
            XWPFParagraph targetParagraph = null;
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && text.contains(key)) {
                    targetParagraph = paragraph;
                    break;
                }
            }
            if (targetParagraph == null) {
                log.warn("未找到占位符: {}", key);
                return;
            }
            comparisonAnalysisService.generateMultiBridgeComparisonAnalysisTable(
                    document, targetParagraph, groupTasks, importedPreviousEvaluations);
            log.info("比较分析处理完成");
        } catch (Exception e) {
            log.error("处理比较分析失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    private Map<String, BiEvaluation> loadImportedPreviousEvaluations(List<LineReportData> allReportData) {
        Map<String, BiEvaluation> result = new LinkedHashMap<>();
        if (allReportData == null) {
            return result;
        }
        LineReportData row = allReportData.stream()
                .filter(data -> data != null && data.getTaskId() == null && isBlank(data.getGroupId())
                        && LineReportWordMatcher.PREVIOUS_EVALUATION_KEY.equals(data.getKey())
                        && data.getValue() != null && !data.getValue().isBlank())
                .findFirst().orElse(null);
        if (row == null) {
            return result;
        }
        try {
            List<LineReportWordMatcher.EvaluationTable> tables = new ObjectMapper().readValue(
                    row.getValue(), new TypeReference<List<LineReportWordMatcher.EvaluationTable>>() { });
            if (tables == null) {
                return result;
            }
            for (LineReportWordMatcher.EvaluationTable table : tables) {
                if (table == null || table.getBridgeName() == null) {
                    continue;
                }
                String lookupName = table.getMatchedName() != null && !table.getMatchedName().isBlank()
                        ? table.getMatchedName() : table.getBridgeName();
                String key = LineReportWordMatcher.normalize(lookupName);
                if (key.isEmpty() || result.containsKey(key)) {
                    continue;
                }
                BiEvaluation evaluation = new BiEvaluation();
                evaluation.setSuperstructureScore(table.getSuperstructureScore());
                evaluation.setSubstructureScore(table.getSubstructureScore());
                evaluation.setDeckSystemScore(table.getDeckSystemScore());
                evaluation.setSystemScore(table.getSystemScore());
                evaluation.setSystemLevel(table.getSystemLevel());
                result.put(key, evaluation);
            }
        } catch (Exception e) {
            log.warn("解析导入的上年评定表失败: {}", e.getMessage());
        }
        return result;
    }


    /**
     * 解析重点病害 JSON。
     * 新格式：{taskId: [{componentId: [diseaseTypeIds]}]}
     * 旧格式：[{componentId: [diseaseTypeIds]}]
     */
    private List<ComponentDiseaseType> parseChooseDiseaseJson(String jsonValue) {
        List<ComponentDiseaseType> combinations = new ArrayList<>();
        if (jsonValue == null || jsonValue.trim().isEmpty()) {
            return combinations;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(jsonValue);
            if (root == null || root.isNull()) {
                return combinations;
            }
            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    Long taskId;
                    try {
                        taskId = Long.parseLong(field.getKey());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    addCombinationsFromArray(field.getValue(), taskId, combinations);
                }
            } else if (root.isArray()) {
                addCombinationsFromArray(root, null, combinations);
            }
            log.info("解析重点病害JSON成功，共{}个构件病害类型组合", combinations.size());
        } catch (Exception e) {
            log.error("解析重点病害JSON失败: {}", jsonValue, e);
        }
        return combinations;
    }

    private void addCombinationsFromArray(JsonNode arrayNode, Long taskId, List<ComponentDiseaseType> combinations) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (JsonNode item : arrayNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Long componentId;
                try {
                    componentId = Long.parseLong(field.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                JsonNode typeIds = field.getValue();
                if (typeIds == null || !typeIds.isArray()) {
                    continue;
                }
                for (JsonNode typeIdNode : typeIds) {
                    if (typeIdNode == null || !typeIdNode.canConvertToLong()) {
                        continue;
                    }
                    combinations.add(new ComponentDiseaseType(taskId, componentId, typeIdNode.asLong()));
                }
            }
        }
    }


    /**
     * 插入一个空行：单倍行距，段前段后为 0。用于检测结果表与图片之间的换行。
     */
    private void insertSingleLineBreak(XWPFDocument document, XmlCursor cursor) {
        XWPFParagraph paragraph;
        if (cursor != null) {
            paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            paragraph = document.createParagraph();
        }
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        if (ppr.isSetInd()) {
            ppr.unsetInd();
        }
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(240));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        spacing.setBefore(BigInteger.ZERO);
        spacing.setAfter(BigInteger.ZERO);
        if (spacing.isSetBeforeLines()) {
            spacing.unsetBeforeLines();
        }
        if (spacing.isSetAfterLines()) {
            spacing.unsetAfterLines();
        }
    }

    /**
     * 使用流式处理方式插入病害图片
     *
     * @param document         Word文档
     * @param diseases         病害列表
     * @param diseaseImageRefs 已收集的病害图片序号映射
     * @param cursor           XML游标，指定内容插入位置，如果为null则追加到文档末尾
     * @throws Exception 异常
     */
    private void insertDiseaseImagesWithStreaming(XWPFDocument document, List<Disease> diseases,
                                                  Map<Long, List<String>> diseaseImageRefs, XmlCursor cursor) throws Exception {
        // 收集所有图片信息
        List<Pair<String, String>> allImages = new ArrayList<>(); // <图片文件名, 标题>

        // 逐个处理每个病害的图片
        for (Disease d : diseases) {
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(d.getId());
            if (images == null || images.isEmpty()) {
                continue;
            }

            // 获取该病害的图片书签信息
            List<String> imageRefs = diseaseImageRefs.getOrDefault(d.getId(), new ArrayList<>());
            Map<Long, String> refByAttachment = new HashMap<>();
            for (String ref : imageRefs) {
                String[] parts = ref.split("\\|", 3);
                if (parts.length >= 3) {
                    refByAttachment.put(longValue(parts[2]), ref);
                }
            }

            for (Map<String, Object> img : images) {
                if (Boolean.TRUE.equals(img.get("isImage"))) {
                    String ref = refByAttachment.get(longValue(img.get("id")));
                    if (ref == null) {
                        continue;
                    }
                    String url = (String) img.get("url");
                    String newName = url.substring(url.lastIndexOf("/") + 1);
                    String[] parts = ref.split("\\|", 3);
                    String bookmarkName = parts[0];
                    String imageDesc = parts.length > 1 ? parts[1] : "";

                    // 将图片信息添加到列表，包含书签名
                    allImages.add(Pair.of(newName, bookmarkName + "|" + imageDesc));
                }
            }
        }

        if (allImages.isEmpty()) {
            return;
        }

        // 检测结果表与图片之间空一行：单倍行距，段前段后为 0
        insertSingleLineBreak(document, cursor);

        // 计算需要的行数：每行放2张图片，需要图片行+标题行
        int imagesPerRow = 2;
        int totalImageRows = (int) Math.ceil((double) allImages.size() / imagesPerRow);
        int totalRows = totalImageRows * 2; // 每组图片需要两行：图片行+标题行

        // 创建表格
        XWPFTable table;
        if (cursor != null) {
            table = document.insertNewTbl(cursor);
            cursor.toNextToken();

            // 初始化第一行：确保有2列
            XWPFTableRow row0 = table.getRow(0);
            row0.getCell(0);
            row0.addNewTableCell(); // 添加第二列

            // 创建其余行
            for (int i = 1; i < totalRows; i++) {
                XWPFTableRow newRow = table.createRow();

                // 确保新行有且仅有2列
                int currentCells = newRow.getTableCells().size();
                if (currentCells < 2) {
                    for (int j = currentCells; j < 2; j++) {
                        newRow.addNewTableCell();
                    }
                } else if (currentCells > 2) {
                    for (int j = currentCells - 1; j >= 2; j--) {
                        newRow.removeCell(j);
                    }
                }
            }
        } else {
            table = document.createTable(totalRows, 2);
        }

        // 设置表格样式
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }

        // 设置表格宽度为页面宽度的100%，避免右侧留白
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(9534)); // 使用100%宽度
        tblWidth.setType(STTblWidth.DXA);

        // 设置表格边框样式
        CTTblBorders borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();

        // 设置外边框
        CTBorder topBorder = borders.addNewTop();
        topBorder.setVal(STBorder.NONE);

        CTBorder bottomBorder = borders.addNewBottom();
        bottomBorder.setVal(STBorder.NONE);

        CTBorder leftBorder = borders.addNewLeft();
        leftBorder.setVal(STBorder.NONE);

        CTBorder rightBorder = borders.addNewRight();
        rightBorder.setVal(STBorder.NONE);

        // 设置内部边框
        CTBorder insideH = borders.addNewInsideH();
        insideH.setVal(STBorder.NONE);

        CTBorder insideV = borders.addNewInsideV();
        insideV.setVal(STBorder.NONE);

        CTJcTable jc = tblPr.isSetJc() ? tblPr.getJc() : tblPr.addNewJc();
        jc.setVal(STJcTable.CENTER);

        // 设置列宽相等（每列占50%宽度）
        int cellWidth = 4767;

        // 设置所有单元格的样式
        for (int row = 0; row < totalRows; row++) {
            for (int col = 0; col < 2; col++) {
                XWPFTableCell cell = table.getRow(row).getCell(col);
                CTTc ctTc = cell.getCTTc();
                CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();

                // 设置单元格宽度
                CTTblWidth cellWidthObj = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidthObj.setW(BigInteger.valueOf(cellWidth));
                cellWidthObj.setType(STTblWidth.DXA);

                // 设置单元格垂直居中
                CTVerticalJc vJc = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
                vJc.setVal(STVerticalJc.CENTER);

                // 清除默认段落
                if (cell.getParagraphs().size() > 0) {
                    for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
                        cell.removeParagraph(i);
                    }
                }

                // 添加新段落，居中对齐
                XWPFParagraph para = cell.addParagraph();
                ReportGenerateTools.setSingleLineSpacing(para);  // 设置单倍行距
                para.setAlignment(ParagraphAlignment.CENTER);
            }
        }

        // 填充图片和标题
        int imageIndex = 0;
        for (int groupIndex = 0; groupIndex < totalImageRows; groupIndex++) {
            int imageRowIndex = groupIndex * 2;     // 图片行索引
            int titleRowIndex = groupIndex * 2 + 1; // 标题行索引

            // 在当前图片行填充最多2张图片
            for (int col = 0; col < 2 && imageIndex < allImages.size(); col++) {
                Pair<String, String> imageInfo = allImages.get(imageIndex);
                String fileName = imageInfo.getLeft();
                String titleInfo = imageInfo.getRight();

                // 解析书签名和描述
                String[] parts = titleInfo.split("\\|");
                String bookmarkName = parts[0];
                String imageDesc = parts.length > 1 ? parts[1] : "";

                // 获取图片单元格和标题单元格
                XWPFTableCell imageCell = table.getRow(imageRowIndex).getCell(col);
                XWPFTableCell titleCell = table.getRow(titleRowIndex).getCell(col);

                // 在图片单元格中添加图片
                XWPFParagraph imagePara = imageCell.getParagraphs().get(0);
                XWPFRun imageRun = imagePara.createRun();

                log.info("开始插入图片：{}", fileName);
                try (InputStream imageStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(fileName.substring(0, 2) + "/" + fileName)
                                .build())) {

                    // 统一图片大小
//                    int imgWidth = 7 * 360000;
                    // 11.11 修改 ， 所有图片 除了附表 和 封面 ，统一 8 cm x 6 cm
                    int imgWidth = 8 * 360000;
                    int imgHeight = 6 * 360000;

                    imageRun.addPicture(
                            imageStream,
                            XWPFDocument.PICTURE_TYPE_JPEG,
                            "disease.jpg",
                            imgWidth,
                            imgHeight
                    );
                    log.info("插入图片结束：{}", fileName);
                } catch (Exception e) {
                    log.error("插入病害图片失败", e);
                    continue;
                }

                // 在标题单元格中添加图片标题域
                XWPFParagraph titlePara = titleCell.getParagraphs().get(0);
                titlePara.setAlignment(ParagraphAlignment.CENTER);

                // 清除现有内容
                while (titlePara.getRuns().size() > 0) {
                    titlePara.removeRun(0);
                }

                // 在现有段落中创建图片标题域，使用第3章编号和指定的书签名
                WordFieldUtils.createFigureCaptionInParagraph(titlePara, imageDesc, 3, bookmarkName, 240, 0);

                imageIndex++;
            }

            // 末行只有一张时，合并成通栏并居中，避免照片贴在左半边。
            if (imageIndex == allImages.size() && (imageIndex - 1) % 2 == 0) {
                spanPhotoRowFullWidth(table, imageRowIndex);
                spanPhotoRowFullWidth(table, titleRowIndex);
            }
        }
        // 照片表格后直接接下一分项标题；检测结果表后的空行已在图片前插入。
    }

    /** 把照片行两列合成一格，段落居中后单张照片位于行中间。 */
    private void spanPhotoRowFullWidth(XWPFTable table, int rowIndex) {
        XWPFTableRow row = table.getRow(rowIndex);
        if (row == null || row.getTableCells().size() < 2) {
            return;
        }
        XWPFTableCell cell = row.getCell(0);
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        if (tcPr.isSetGridSpan()) {
            tcPr.getGridSpan().setVal(BigInteger.valueOf(2));
        } else {
            tcPr.addNewGridSpan().setVal(BigInteger.valueOf(2));
        }
        CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        cellWidth.setW(BigInteger.valueOf(9534));
        cellWidth.setType(STTblWidth.DXA);
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
        }
        row.removeCell(1);
    }


    /**
     * 插入BiObject的现状照片（type=8）
     *
     * @param document            Word文档
     * @param biObject            BiObject对象
     * @param chapterImageCounter 图片计数器，用于生成图片编号
     * @param cursor              XML游标，指定内容插入位置，如果为null则追加到文档末尾
     * @throws Exception 异常
     */
    private void insertBiObjectStatusImages(XWPFDocument document, BiObject biObject,
                                            AtomicInteger chapterImageCounter, XmlCursor cursor) throws Exception {
        // 收集所有现状照片信息
        List<Pair<String, String>> allImages = new ArrayList<>(); // <图片文件名, 标题>

        // 获取BiObject的现状照片（type=8）
        try {
            List<FileMap> photoList = fileMapService.selectBiObjectPhotoList(biObject.getId());
            if (photoList == null || photoList.isEmpty()) {
                return;
            }

            // 为每张照片生成书签名和标题
            for (FileMap fileMap : photoList) {
                String newName = fileMap.getNewName();
                // 生成书签名
                String bookmarkName = "fig_status_" + chapterImageCounter.getAndIncrement();
                // 获取照片备注作为描述，如果没有则使用默认描述
                String imageDesc = fileMap.getAttachmentRemark() != null && !fileMap.getAttachmentRemark().isEmpty()
                        ? ReportGenerateTools.finishStatusCaption(fileMap.getAttachmentRemark())
                        : biObject.getName() + "现状";

                // 将图片信息添加到列表，包含书签名和描述
                allImages.add(Pair.of(newName, bookmarkName + "|" + imageDesc));
            }
        } catch (Exception e) {
            log.error("获取BiObject现状照片失败", e);
            return;
        }

        if (allImages.isEmpty()) {
            return;
        }

        // 检测结果表与图片之间空一行：单倍行距，段前段后为 0
        insertSingleLineBreak(document, cursor);

        // 计算需要的行数：每行放2张图片，需要图片行+标题行
        int imagesPerRow = 2;
        int totalImageRows = (int) Math.ceil((double) allImages.size() / imagesPerRow);
        int totalRows = totalImageRows * 2; // 每组图片需要两行：图片行+标题行

        // 创建表格
        XWPFTable table;
        if (cursor != null) {
            table = document.insertNewTbl(cursor);
            cursor.toNextToken();

            // 初始化第一行：确保有2列
            XWPFTableRow row0 = table.getRow(0);
            row0.getCell(0);
            row0.addNewTableCell(); // 添加第二列

            // 创建其余行
            for (int i = 1; i < totalRows; i++) {
                XWPFTableRow newRow = table.createRow();

                // 确保新行有且仅有2列
                int currentCells = newRow.getTableCells().size();
                if (currentCells < 2) {
                    for (int j = currentCells; j < 2; j++) {
                        newRow.addNewTableCell();
                    }
                } else if (currentCells > 2) {
                    for (int j = currentCells - 1; j >= 2; j--) {
                        newRow.removeCell(j);
                    }
                }
            }
        } else {
            table = document.createTable(totalRows, 2);
        }

        // 设置表格样式
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }

        // 设置表格宽度为页面宽度的100%，避免右侧留白
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(9534)); // 使用100%宽度
        tblWidth.setType(STTblWidth.DXA);

        // 设置表格边框样式
        CTTblBorders borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();

        // 设置外边框
        CTBorder topBorder = borders.addNewTop();
        topBorder.setVal(STBorder.NONE);

        CTBorder bottomBorder = borders.addNewBottom();
        bottomBorder.setVal(STBorder.NONE);

        CTBorder leftBorder = borders.addNewLeft();
        leftBorder.setVal(STBorder.NONE);

        CTBorder rightBorder = borders.addNewRight();
        rightBorder.setVal(STBorder.NONE);

        // 设置内部边框
        CTBorder insideH = borders.addNewInsideH();
        insideH.setVal(STBorder.NONE);

        CTBorder insideV = borders.addNewInsideV();
        insideV.setVal(STBorder.NONE);

        CTJcTable jc = tblPr.isSetJc() ? tblPr.getJc() : tblPr.addNewJc();
        jc.setVal(STJcTable.CENTER);

        // 设置列宽相等（每列占50%宽度）
        int cellWidth = 4767;

        // 设置所有单元格的样式
        for (int row = 0; row < totalRows; row++) {
            for (int col = 0; col < 2; col++) {
                XWPFTableCell cell = table.getRow(row).getCell(col);
                CTTc ctTc = cell.getCTTc();
                CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();

                // 设置单元格宽度
                CTTblWidth cellWidthObj = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidthObj.setW(BigInteger.valueOf(cellWidth));
                cellWidthObj.setType(STTblWidth.DXA);

                // 设置单元格垂直居中
                CTVerticalJc vJc = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
                vJc.setVal(STVerticalJc.CENTER);

                // 清除默认段落
                if (cell.getParagraphs().size() > 0) {
                    for (int i = cell.getParagraphs().size() - 1; i >= 0; i--) {
                        cell.removeParagraph(i);
                    }
                }

                // 添加新段落，居中对齐
                XWPFParagraph para = cell.addParagraph();
                ReportGenerateTools.setSingleLineSpacing(para);  // 设置单倍行距
                para.setAlignment(ParagraphAlignment.CENTER);
            }
        }

        // 填充图片和标题
        int imageIndex = 0;
        for (int groupIndex = 0; groupIndex < totalImageRows; groupIndex++) {
            int imageRowIndex = groupIndex * 2;     // 图片行索引
            int titleRowIndex = groupIndex * 2 + 1; // 标题行索引

            // 在当前图片行填充最多2张图片
            for (int col = 0; col < 2 && imageIndex < allImages.size(); col++) {
                Pair<String, String> imageInfo = allImages.get(imageIndex);
                String fileName = imageInfo.getLeft();
                String titleInfo = imageInfo.getRight();

                // 解析书签名和描述
                String[] parts = titleInfo.split("\\|");
                String bookmarkName = parts[0];
                String imageDesc = parts.length > 1 ? parts[1] : "";

                // 获取图片单元格和标题单元格
                XWPFTableCell imageCell = table.getRow(imageRowIndex).getCell(col);
                XWPFTableCell titleCell = table.getRow(titleRowIndex).getCell(col);

                // 在图片单元格中添加图片
                XWPFParagraph imagePara = imageCell.getParagraphs().get(0);
                XWPFRun imageRun = imagePara.createRun();

                log.info("开始插入现状照片：{}", fileName);
                try (InputStream imageStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(fileName.substring(0, 2) + "/" + fileName)
                                .build())) {

                    // 统一图片大小
                    int imgWidth = 8 * 360000;
                    int imgHeight = 6 * 360000;

                    imageRun.addPicture(
                            imageStream,
                            XWPFDocument.PICTURE_TYPE_JPEG,
                            "status.jpg",
                            imgWidth,
                            imgHeight
                    );
                    log.info("插入现状照片结束：{}", fileName);
                } catch (Exception e) {
                    log.error("插入现状照片失败", e);
                    continue;
                }

                // 在标题单元格中添加图片标题域
                XWPFParagraph titlePara = titleCell.getParagraphs().get(0);
                titlePara.setAlignment(ParagraphAlignment.CENTER);

                // 清除现有内容
                while (titlePara.getRuns().size() > 0) {
                    titlePara.removeRun(0);
                }

                // 在现有段落中创建图片标题域，使用第3章编号和指定的书签名
                WordFieldUtils.createFigureCaptionInParagraph(titlePara, imageDesc, 3, bookmarkName, 240, 0);

                imageIndex++;
            }

            // 现状照末行只有一张时，与病害照一样合并通栏居中。
            if (imageIndex == allImages.size() && (imageIndex - 1) % 2 == 0) {
                spanPhotoRowFullWidth(table, imageRowIndex);
                spanPhotoRowFullWidth(table, titleRowIndex);
            }
        }

        // 在表格后添加空段落
        XWPFParagraph spacerAfter;
        if (cursor != null) {
            spacerAfter = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            spacerAfter = document.createParagraph();
        }
        spacerAfter.setSpacingAfter(300);
        spacerAfter.setSpacingBefore(300);
    }

    /**
     * 处理检测结论
     */
    private void handleTestConclusion(XWPFDocument document, String key, List<Task> groupTasks, String groupName) {
        try {
            log.info("开始处理检测结论, key: {}, 子桥数量: {}", key, groupTasks.size());
            XWPFParagraph targetParagraph = ReportGenerateTools.findParagraphByPlaceholder(document, key);
            if (targetParagraph == null) {
                log.warn("未找到检测结论占位符: {}", key);
                return;
            }
            Map<Long, BiEvaluation> biEvaluationMap = new HashMap<>();
            Integer minSystemLevel = null;
            for (Task task : groupTasks) {
                BiEvaluation evaluation = biEvaluationService.selectBiEvaluationByTaskId(task.getId());
                if (evaluation == null) {
                    continue;
                }
                biEvaluationMap.put(task.getId(), evaluation);
                if (evaluation.getSystemLevel() != null
                        && (minSystemLevel == null || evaluation.getSystemLevel() > minSystemLevel)) {
                    minSystemLevel = evaluation.getSystemLevel();
                }
            }
            testConclusionService.handleTestConclusion(document, targetParagraph, groupTasks, groupName,
                    biEvaluationMap, minSystemLevel);
            log.info("检测结论处理完成");
        } catch (Exception e) {
            log.error("处理检测结论失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理检测结论桥梁详情
     */
    private void handleTestConclusionBridge(XWPFDocument document, String key, List<Task> groupTasks) {
        try {
            log.info("开始处理检测结论桥梁详情, key: {}, 子桥数量: {}", key, groupTasks.size());
            XWPFParagraph targetParagraph = ReportGenerateTools.findParagraphByPlaceholder(document, key);
            if (targetParagraph == null) {
                log.warn("未找到检测结论桥梁详情占位符: {}", key);
                return;
            }
            testConclusionService.handleTestConclusionBridge(document, targetParagraph, groupTasks);
            log.info("检测结论桥梁详情处理完成");
        } catch (Exception e) {
            log.error("处理检测结论桥梁详情失败: key={}", key, e);
            throw e;
        }
    }

    private enum AppendixPart {
        BRIDGE_CARD,
        INSPECTION_TABLE
    }

    /**
     * 附表先按勾选子桥出桥梁卡片，再按全部子桥顺序出定期检查记录表。
     * 未保存过勾选时仍出全部卡片，兼容旧数据。
     */
    private void fillAppendixForGroup(XWPFDocument document, List<Task> groupTasks,
                                      Project project, byte[] templateBytes,
                                      List<LineReportData> groupData) {
        if (document == null || groupTasks == null || groupTasks.isEmpty() || templateBytes == null) {
            return;
        }
        XWPFParagraph appendixHeading = findBridgeBlockHeading(document, "附表");
        XWPFParagraph blockEnd = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-end}");
        if (appendixHeading == null || blockEnd == null) {
            log.warn("大桥章节未找到附表标题或结束锚点，跳过附表生成");
            return;
        }
        Set<Long> selectedCardTaskIds = selectedAppendixCardTaskIds(groupData);
        Map<Long, JsonNode> evaluationHistory = parseAppendixEvaluationHistory(groupData);
        for (Task task : groupTasks) {
            if (shouldGenerateAppendixCard(task, selectedCardTaskIds)) {
                insertAppendixPart(document, blockEnd, templateBytes, project, task, AppendixPart.BRIDGE_CARD,
                        evaluationHistory.get(task.getId()));
            }
        }
        for (Task task : groupTasks) {
            insertAppendixPart(document, blockEnd, templateBytes, project, task, AppendixPart.INSPECTION_TABLE, null);
        }
    }

    /**
     * {@code null} 表示未配置，全部出卡片；空集合表示明确不选。
     */
    private Set<Long> selectedAppendixCardTaskIds(List<LineReportData> groupData) {
        String raw = firstGroupDataValue(groupData, APPENDIX_BRIDGE_CARD_TASK_IDS_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            List<Long> ids = new ObjectMapper().readValue(raw.trim(), new TypeReference<List<Long>>() {
            });
            return ids == null ? Collections.emptySet() : new LinkedHashSet<>(ids);
        } catch (Exception e) {
            log.warn("解析附表桥梁卡片子桥选择失败: {}", raw, e);
            return null;
        }
    }

    private boolean shouldGenerateAppendixCard(Task task, Set<Long> selectedCardTaskIds) {
        if (selectedCardTaskIds == null) {
            return true;
        }
        return task != null && task.getId() != null && selectedCardTaskIds.contains(task.getId());
    }

    private String firstGroupDataValue(List<LineReportData> groupData, String key) {
        if (groupData == null || key == null) {
            return null;
        }
        for (LineReportData data : groupData) {
            if (data == null || data.getKey() == null) {
                continue;
            }
            String stored = data.getKey().replace("${", "").replace("}", "").trim();
            if (key.equals(stored) || key.equals(data.getKey())) {
                return data.getValue();
            }
        }
        return null;
    }

    private Map<Long, JsonNode> parseAppendixEvaluationHistory(List<LineReportData> groupData) {
        Map<Long, JsonNode> result = new HashMap<>();
        String raw = firstGroupDataValue(groupData, APPENDIX_EVALUATION_HISTORY_KEY);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(raw);
            if (root == null || !root.isObject()) {
                return result;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                try {
                    result.put(Long.valueOf(field.getKey()), field.getValue());
                } catch (NumberFormatException ignored) {
                    // 跳过无法对应子桥的历史行
                }
            }
        } catch (Exception e) {
            log.warn("解析附表评定历史失败: {}", e.getMessage());
        }
        return result;
    }

    private void insertAppendixPart(XWPFDocument document, XWPFParagraph blockEnd, byte[] templateBytes,
                                    Project project, Task task, AppendixPart part, JsonNode evaluationHistory) {
        Building building = task == null ? null : task.getBuilding();
        if (building == null) {
            return;
        }
        XWPFDocument appendixSource = null;
        try {
            appendixSource = new XWPFDocument(new ByteArrayInputStream(templateBytes));
            applyBridgeIdentity(appendixSource, building, project);
            if (part == AppendixPart.INSPECTION_TABLE) {
                regularInspectionService.fillSingleBridgeRegularInspectionTable(
                        appendixSource, building, task, project, BRIDGE_CHAPTER_TEMPLATE_TYPE, true);
            } else {
                BiEvaluation evaluation = task.getId() == null
                        ? null : biEvaluationService.selectBiEvaluationByTaskId(task.getId());
                bridgeCardService.processBridgeCardData(
                        appendixSource, building, BRIDGE_CHAPTER_TEMPLATE_TYPE,
                        evaluation == null ? null : evaluation.getSystemLevel());
                applyAppendixEvaluationHistory(appendixSource, evaluationHistory);
            }
            insertBodyElementCopies(document, blockEnd, appendixSource,
                    copyAppendixBodyElements(appendixSource, part));
        } catch (Exception e) {
            log.warn("大桥附表生成失败: taskId={}, part={}", task.getId(), part, e);
        } finally {
            if (appendixSource != null) {
                try {
                    appendixSource.close();
                } catch (IOException e) {
                    log.warn("关闭附表临时文档失败: taskId={}", task.getId(), e);
                }
            }
        }
    }

    /**
     * 卡片「桥梁检测评定历史」表头下一行，按填报覆盖五个格子。空白项保留卡片原值。
     */
    private void applyAppendixEvaluationHistory(XWPFDocument document, JsonNode history) {
        if (document == null) {
            return;
        }
        boolean hasHistory = history != null && !history.isNull();
        String date = hasHistory ? formatYearMonthDash(historyText(history, "date")) : "";
        String category = hasHistory ? historyText(history, "category") : "";
        String result = hasHistory ? historyText(history, "result") : "";
        String disposal = hasHistory ? historyText(history, "disposal") : "";
        String next = hasHistory ? formatYear(historyText(history, "next")) : "";
        XWPFTableRow dataRow = findEvaluationHistoryDataRow(document);
        if (dataRow == null) {
            if (hasHistory) {
                log.warn("未找到桥梁检测评定历史第一行，跳过填报覆盖");
            }
            return;
        }
        List<XWPFTableCell> cells = dataRow.getTableCells();
        if (isBlank(date) && !cells.isEmpty()) {
            String current = cells.get(0).getText();
            String dashed = formatYearMonthDash(current);
            if (dashed.matches("\\d{4}-\\d{2}")) {
                date = dashed;
            }
        }
        if (isBlank(date) && isBlank(category) && isBlank(result) && isBlank(disposal) && isBlank(next)) {
            return;
        }
        String[] values = {date, category, result, disposal, next};
        for (int i = 0; i < values.length && i < cells.size(); i++) {
            if (!isBlank(values[i])) {
                setHistoryCell(cells.get(i), values[i]);
            }
        }
    }

    private XWPFTableRow findEvaluationHistoryDataRow(XWPFDocument document) {
        for (XWPFTable table : document.getTables()) {
            List<XWPFTableRow> rows = table.getRows();
            for (int i = 0; i < rows.size() - 1; i++) {
                String text = rows.get(i).getTableCells().stream()
                        .map(XWPFTableCell::getText)
                        .collect(Collectors.joining());
                if (text.contains("评定时间") && text.contains("检测类别") && text.contains("下次检测时间")) {
                    return rows.get(i + 1);
                }
            }
        }
        return null;
    }

    private void setHistoryCell(XWPFTableCell cell, String text) {
        while (cell.getParagraphs().size() > 0) {
            cell.removeParagraph(0);
        }
        XWPFParagraph paragraph = cell.addParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        ReportGenerateTools.setParagraphMixedFontText(paragraph, text, ReportGenerateTools.TABLE_FONT_HALF_POINTS);
    }

    private String historyText(JsonNode history, String field) {
        JsonNode node = history.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private String formatYear(String value) {
        if (isBlank(value)) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})").matcher(value);
        return matcher.find() ? matcher.group(1) + "年" : value;
    }

    private String formatYearMonthDash(String value) {
        if (isBlank(value)) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})\\D*(\\d{1,2})").matcher(value);
        if (!matcher.find()) {
            return value;
        }
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) {
            return value;
        }
        return matcher.group(1) + "-" + String.format("%02d", month);
    }

    private List<IBodyElement> copyAppendixBodyElements(XWPFDocument document, AppendixPart part) {
        XWPFParagraph appendixHeading = findBridgeBlockHeading(document, "附表");
        XWPFParagraph end = ReportGenerateTools.findParagraphByPlaceholder(document, "${line.bridge-block-end}");
        if (appendixHeading == null || end == null) {
            return Collections.emptyList();
        }
        int startIndex = document.getPosOfParagraph(appendixHeading);
        int endIndex = document.getPosOfParagraph(end);
        int splitIndex = indexOfAppendixInspectionTable(document, startIndex, endIndex);
        int from;
        int to;
        if (splitIndex < 0) {
            if (part != AppendixPart.BRIDGE_CARD) {
                return Collections.emptyList();
            }
            from = startIndex + 1;
            to = endIndex;
        } else if (part == AppendixPart.BRIDGE_CARD) {
            from = startIndex + 1;
            to = splitIndex;
        } else {
            from = splitIndex;
            to = endIndex;
        }
        List<IBodyElement> elements = new ArrayList<>();
        for (int i = from; i < to; i++) {
            elements.add(document.getBodyElements().get(i));
        }
        return elements;
    }

    private int indexOfAppendixInspectionTable(XWPFDocument document, int startIndex, int endIndex) {
        for (int i = startIndex + 1; i < endIndex; i++) {
            IBodyElement element = document.getBodyElements().get(i);
            if (!(element instanceof XWPFParagraph)) {
                continue;
            }
            String text = safeParagraphText((XWPFParagraph) element).replace(" ", "");
            if (text.contains("定期检查记录表")) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 以下表格工具参照组合桥报告实现，独立维护 ====================

    private static final Set<String> COMPONENT_STRUCTURE_NAMES = Set.of("上部结构", "下部结构", "桥面系");
    private static final Set<String> SKIPPED_COMPONENT_NODE_NAMES = Set.of("其他", "附属设施");

    /**
     * 部件表一行：按评定部件出表，锥坡/护坡这类复合部件可拆成子行。
     */
    private static final class ComponentTableRow {
        private final String structureName;
        private final String groupName;
        private final String componentName;
        private final String countText;
        private final String remarkText;

        private ComponentTableRow(String structureName, String groupName, String componentName,
                                  String countText, String remarkText) {
            this.structureName = structureName;
            this.groupName = groupName;
            this.componentName = componentName;
            this.countText = countText;
            this.remarkText = remarkText;
        }

        private boolean nested() {
            return groupName != null && !groupName.isEmpty();
        }
    }

    /**
     * 一张部件表对应一个出表根节点：普通桥是桥梁本身，组合桥则是主桥/引桥。
     */
    private List<BiObject> resolveComponentTableRoots(BiObject root, List<BiObject> allObjects) {
        List<BiObject> children = listComponentChildren(root.getId(), allObjects);
        if (children.stream().anyMatch(child -> COMPONENT_STRUCTURE_NAMES.contains(child.getName()))) {
            return List.of(root);
        }
        List<BiObject> subBridges = children.stream()
                .filter(child -> listComponentChildren(child.getId(), allObjects).stream()
                        .anyMatch(grand -> COMPONENT_STRUCTURE_NAMES.contains(grand.getName())))
                .collect(Collectors.toList());
        return subBridges.isEmpty() ? List.of(root) : subBridges;
    }

    /**
     * 收集评定部件，按上部结构/下部结构/桥面系分组；不展开到节段、孔等构件。
     */
    private Map<String, List<ComponentTableRow>> collectComponentStructure(BiObject tableRoot, List<BiObject> allObjects) {
        Map<String, List<ComponentTableRow>> structureMap = new LinkedHashMap<>();
        for (BiObject structure : listComponentChildren(tableRoot.getId(), allObjects)) {
            List<ComponentTableRow> rows = new ArrayList<>();
            for (BiObject component : listComponentChildren(structure.getId(), allObjects)) {
                List<BiObject> nestedChildren = nestedComponentChildren(component, allObjects);
                if (nestedChildren.isEmpty()) {
                    rows.add(new ComponentTableRow(structure.getName(), null, component.getName(),
                            formatComponentCount(resolveComponentCount(component, allObjects)),
                            formatComponentRemark(component.getRemark())));
                } else {
                    for (BiObject child : nestedChildren) {
                        rows.add(new ComponentTableRow(structure.getName(), component.getName(), child.getName(),
                                formatComponentCount(resolveComponentCount(child, allObjects)),
                                formatComponentRemark(child.getRemark())));
                    }
                }
            }
            if (!rows.isEmpty()) {
                structureMap.put(structure.getName(), rows);
            }
        }
        return structureMap;
    }

    private List<BiObject> listComponentChildren(Long parentId, List<BiObject> allObjects) {
        if (parentId == null || allObjects == null) {
            return List.of();
        }
        return ReportTemplateValueUtils.sortedReportChildren(allObjects, parentId);
    }

    private boolean isSkippedComponentNode(BiObject object) {
        return object == null || object.getName() == null || SKIPPED_COMPONENT_NODE_NAMES.contains(object.getName());
    }

    /**
     * 仅当子节点名称正好是父节点顿号拆分结果时展开，例如「锥坡、护坡」→ 锥坡 / 护坡。
     * 上部承重构件下的节段、孔跨不会展开。
     */
    private List<BiObject> nestedComponentChildren(BiObject parent, List<BiObject> allObjects) {
        if (parent == null || parent.getName() == null || !parent.getName().contains("、")) {
            return List.of();
        }
        List<BiObject> children = listComponentChildren(parent.getId(), allObjects);
        if (children.size() < 2) {
            return List.of();
        }
        Set<String> parts = Arrays.stream(parent.getName().split("、"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parts.size() < 2 || !children.stream().allMatch(child -> parts.contains(child.getName()))) {
            return List.of();
        }
        return children;
    }

    /**
     * 上部承重构件这类评定部件本身往往没有 count，改为累加下级已填写的构件数量。
     * 子树里都没有 count 时返回 0，表格显示为「/」，不能把结构树占位节点个数当成构件数。
     */
    private Integer resolveComponentCount(BiObject node, List<BiObject> allObjects) {
        if (node == null) {
            return 0;
        }
        if (node.getCount() != null && node.getCount() > 0) {
            return node.getCount();
        }
        List<BiObject> children = listComponentChildren(node.getId(), allObjects);
        if (children.isEmpty()) {
            return node.getCount();
        }
        int sum = 0;
        for (BiObject child : children) {
            Integer childCount = resolveComponentCount(child, allObjects);
            if (childCount != null && childCount > 0) {
                sum += childCount;
            }
        }
        return sum;
    }

    private String formatComponentCount(Integer count) {
        if (count == null || count <= 0) {
            return "/";
        }
        return String.valueOf(count);
    }

    /** 与部件划分表一致：构件数量为「/」即无此构件。 */
    private boolean isMissingComponent(BiObject node, List<BiObject> allObjects) {
        Integer count = resolveComponentCount(node, allObjects);
        return count == null || count <= 0;
    }

    private String formatComponentRemark(String remark) {
        if (remark == null || remark.trim().isEmpty()) {
            return "/";
        }
        return remark.trim();
    }

    /**
     * 生成部件划分及构件数量表格。桥梁部件列预留两格，供「锥坡、护坡」左右拆分。
     */
    private void generateComponentTable(XWPFDocument document, XmlCursor cursor,
                                        Map<String, List<ComponentTableRow>> structureMap) {
        int totalRows = 1;
        for (List<ComponentTableRow> rows : structureMap.values()) {
            totalRows += rows.size();
        }

        XWPFTable table;
        if (cursor != null) {
            table = document.insertNewTbl(cursor);
            cursor.toNextToken();
        } else {
            table = document.createTable();
        }

        int columnCount = 6;
        for (int i = 0; i < totalRows; i++) {
            XWPFTableRow row = table.getRow(i);
            if (row == null) {
                row = table.createRow();
            }
            for (int j = 0; j < columnCount; j++) {
                if (row.getCell(j) == null) {
                    row.createCell();
                }
            }
        }

        applyTableGrid(table, columnCount);

        XWPFTableRow headerRow = table.getRow(0);
        setTableCell(headerRow, 0, "序号", true);
        setTableCell(headerRow, 1, "桥梁结构", true);
        setTableCell(headerRow, 2, "桥梁部件", true);
        setTableCell(headerRow, 3, "", true);
        setTableCell(headerRow, 4, "构件数量", true);
        setTableCell(headerRow, 5, "说明", true);
        mergeHorizontalCells(table, 0, 2, 3);
        ReportGenerateTools.setTableHeaderRepeat(table, 1);

        int rowIndex = 1;
        int sequenceNum = 1;
        List<int[]> structureMerges = new ArrayList<>();
        List<int[]> groupMerges = new ArrayList<>();

        for (Map.Entry<String, List<ComponentTableRow>> entry : structureMap.entrySet()) {
            List<ComponentTableRow> rows = entry.getValue();
            int structureStart = rowIndex;
            int groupStart = -1;
            String currentGroup = null;
            for (ComponentTableRow item : rows) {
                XWPFTableRow row = table.getRow(rowIndex);
                setTableCell(row, 0, String.valueOf(sequenceNum));
                setTableCell(row, 1, item.structureName);
                if (item.nested()) {
                    setTableCell(row, 2, item.groupName);
                    setTableCell(row, 3, item.componentName);
                    if (!Objects.equals(currentGroup, item.groupName)) {
                        if (groupStart >= 0 && rowIndex - 1 > groupStart) {
                            groupMerges.add(new int[]{groupStart, rowIndex - 1});
                        }
                        groupStart = rowIndex;
                        currentGroup = item.groupName;
                    }
                } else {
                    if (groupStart >= 0 && rowIndex - 1 > groupStart) {
                        groupMerges.add(new int[]{groupStart, rowIndex - 1});
                    }
                    groupStart = -1;
                    currentGroup = null;
                    setTableCell(row, 2, item.componentName);
                    setTableCell(row, 3, "");
                    mergeHorizontalCells(table, rowIndex, 2, 3);
                }
                setTableCell(row, 4, item.countText);
                setTableCell(row, 5, item.remarkText, false,
                        "/".equals(item.remarkText) ? ParagraphAlignment.CENTER : ParagraphAlignment.LEFT);
                rowIndex++;
                sequenceNum++;
            }
            if (groupStart >= 0 && rowIndex - 1 > groupStart) {
                groupMerges.add(new int[]{groupStart, rowIndex - 1});
            }
            if (rows.size() > 1) {
                structureMerges.add(new int[]{structureStart, rowIndex - 1});
            }
        }

        for (int[] range : structureMerges) {
            mergeVerticalCells(table, range[0], range[1], 1);
        }
        for (int[] range : groupMerges) {
            mergeVerticalCells(table, range[0], range[1], 2);
        }
    }

    /**
     * 垂直合并单元格
     *
     * @param table    表格
     * @param startRow 起始行
     * @param endRow   结束行
     * @param col      列索引
     */
    private void mergeVerticalCells(XWPFTable table, int startRow, int endRow, int col) {
        if (startRow >= endRow) {
            return;
        }

        // 获取起始单元格
        XWPFTableCell startCell = table.getRow(startRow).getCell(col);
        if (startCell == null) {
            return;
        }

        // 设置合并：从startRow到endRow
        for (int i = startRow + 1; i <= endRow; i++) {
            XWPFTableCell cell = table.getRow(i).getCell(col);
            if (cell != null) {
                // 设置垂直合并
                CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTVMerge vMerge = tcPr.isSetVMerge() ? tcPr.getVMerge() : tcPr.addNewVMerge();
                vMerge.setVal(STMerge.CONTINUE);
            }
        }

        // 设置起始单元格的垂直合并为开始
        CTTcPr tcPr = startCell.getCTTc().getTcPr();
        if (tcPr == null) {
            tcPr = startCell.getCTTc().addNewTcPr();
        }
        CTVMerge vMerge = tcPr.isSetVMerge() ? tcPr.getVMerge() : tcPr.addNewVMerge();
        vMerge.setVal(STMerge.RESTART);
    }

    /**
     * 横向合并单元格。
     */
    private void mergeHorizontalCells(XWPFTable table, int row, int startCol, int endCol) {
        if (startCol >= endCol) {
            return;
        }
        XWPFTableRow tableRow = table.getRow(row);
        if (tableRow == null) {
            return;
        }
        for (int col = startCol; col <= endCol; col++) {
            XWPFTableCell cell = tableRow.getCell(col);
            if (cell == null) {
                continue;
            }
            CTTcPr tcPr = cell.getCTTc().getTcPr();
            if (tcPr == null) {
                tcPr = cell.getCTTc().addNewTcPr();
            }
            CTHMerge hMerge = tcPr.isSetHMerge() ? tcPr.getHMerge() : tcPr.addNewHMerge();
            hMerge.setVal(col == startCol ? STMerge.RESTART : STMerge.CONTINUE);
        }
    }

    /**
     * 全文标题：中文黑体，数字/英文 Times New Roman，不加粗。二级～三级四号，四级及以下小四。
     * 段前段后各 0.5 行、1.5 倍行距；一级沿用模板字号。
     */
    private void applyDocumentHeadingStyles(XWPFDocument document) {
        if (document == null) {
            return;
        }
        unboldHeadingStyles(document);
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (isCaptionParagraph(paragraph)) {
                continue;
            }
            int level = resolveHeadingLevel(paragraph);
            if (level >= 1) {
                applyHeadingFormat(paragraph, level);
            }
        }
        applyAppearanceAndConclusionHeadingSizes(document);
        // 模板 heading 5 默认小五(18)，三级及以下标题编号/未覆盖 run 会吃到它；统一抬到小四
        forceHeadingStyleSize(document, 4, 24);
        forceHeadingStyleSize(document, 5, 24);
        forceHeadingStyleSize(document, 6, 24);
        forceHeadingStyleSize(document, 7, 24);
        forceHeadingStyleSize(document, 8, 24);
        forceHeadingStyleSize(document, 9, 24);
        // 附表题注样式继承标题 4，上面把四级标题抬到小四后必须把表名钉回五号
        applyCaptionFonts(document);
    }

    private void forceHeadingStyleSize(XWPFDocument document, int headingLevel, int fontHalfPoints) {
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            return;
        }
        Set<String> styleIds = new LinkedHashSet<>();
        addStyleId(styleIds, styles.getStyleWithName("Heading " + headingLevel));
        addStyleId(styleIds, styles.getStyleWithName("heading " + headingLevel));
        addStyleId(styleIds, styles.getStyleWithName("标题 " + headingLevel));
        addStyleId(styleIds, styles.getStyleWithName("标题" + headingLevel));
        // 本模板常见：styleId = headingLevel + 1（2=标题1）
        styleIds.add(String.valueOf(headingLevel + 1));
        for (String styleId : styleIds) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style == null || style.getCTStyle() == null) {
                continue;
            }
            CTStyle ctStyle = style.getCTStyle();
            CTRPr rPr = ctStyle.isSetRPr() ? ctStyle.getRPr() : ctStyle.addNewRPr();
            while (rPr.sizeOfSzArray() > 0) {
                rPr.removeSz(0);
            }
            while (rPr.sizeOfSzCsArray() > 0) {
                rPr.removeSzCs(0);
            }
            rPr.addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
            rPr.addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
            stripBold(rPr);
        }
    }

    /**
     * 模板标题样式自带 &lt;w:b/&gt;、&lt;w:bCs/&gt;（空标签即加粗）。
     * 只改 run 不够，编号和中文仍会吃样式里的加粗。
     */
    private void unboldHeadingStyles(XWPFDocument document) {
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            return;
        }
        Set<String> styleIds = new LinkedHashSet<>();
        for (int level = 1; level <= 9; level++) {
            styleIds.add(String.valueOf(level));
            addStyleId(styleIds, styles.getStyle(String.valueOf(level)));
            addStyleId(styleIds, styles.getStyleWithName("Heading " + level));
            addStyleId(styleIds, styles.getStyleWithName("heading " + level));
            addStyleId(styleIds, styles.getStyleWithName("标题 " + level));
            addStyleId(styleIds, styles.getStyleWithName("标题" + level));
        }
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (resolveHeadingLevel(paragraph) >= 1 && paragraph.getStyle() != null) {
                styleIds.add(paragraph.getStyle());
            }
        }
        for (String styleId : styleIds) {
            unboldStyleAndLinked(styles, styleId, new HashSet<>());
        }
    }

    private void addStyleId(Set<String> styleIds, XWPFStyle style) {
        if (style != null && style.getStyleId() != null) {
            styleIds.add(style.getStyleId());
        }
    }

    private void unboldStyleAndLinked(XWPFStyles styles, String styleId, Set<String> visited) {
        if (styleId == null || styleId.trim().isEmpty() || !visited.add(styleId)) {
            return;
        }
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null) {
            return;
        }
        CTStyle ctStyle = style.getCTStyle();
        if (ctStyle != null && ctStyle.isSetRPr()) {
            stripBold(ctStyle.getRPr());
        }
        if (ctStyle != null && ctStyle.isSetLink() && ctStyle.getLink().getVal() != null) {
            unboldStyleAndLinked(styles, ctStyle.getLink().getVal(), visited);
        }
    }

    private void stripBold(CTRPr rPr) {
        if (rPr == null) {
            return;
        }
        while (rPr.sizeOfBArray() > 0) {
            rPr.removeB(0);
        }
        while (rPr.sizeOfBCsArray() > 0) {
            rPr.removeBCs(0);
        }
    }

    /**
     * 表题保持模板黑体五号。附表「图表类标题」基于标题 4，全文把四级标题改成小四后，
     * 未写死字号的题注会跟着变成小四。
     */
    private void applyCaptionFonts(XWPFDocument document) {
        if (document == null) {
            return;
        }
        for (XWPFParagraph storedParagraph : document.getParagraphs()) {
            // 合并章节直接替换了 CTP，原段落对象的 runs 缓存仍可能为空。
            XWPFParagraph paragraph = new XWPFParagraph(storedParagraph.getCTP(), document);
            if (!isTableOrFigureCaption(paragraph)) {
                continue;
            }
            if (safeParagraphText(paragraph).contains("桥梁基本状况卡片")) {
                applyBridgeCardCaptionStyle(document, paragraph);
            }
            for (XWPFRun run : paragraph.getRuns()) {
                ReportGenerateTools.setMixedFontFamily(run, 21, "黑体");
            }
        }
    }

    /** 保留 STYLEREF / SEQ 域，仅把桥梁卡片表题设为独立的五号题注。 */
    private void applyBridgeCardCaptionStyle(XWPFDocument document, XWPFParagraph paragraph) {
        String styleId = "BIAppendixCaption";
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            styles = document.createStyles();
        }
        if (styles.getStyle(styleId) == null) {
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId(styleId);
            style.setType(STStyleType.PARAGRAPH);
            style.addNewName().setVal("附表题注");
            CTPPrGeneral stylePPr = style.addNewPPr();
            stylePPr.addNewJc().setVal(STJc.CENTER);
            stylePPr.addNewOutlineLvl().setVal(BigInteger.valueOf(9));
            CTRPr styleRPr = style.addNewRPr();
            styleRPr.addNewSz().setVal(BigInteger.valueOf(21));
            styleRPr.addNewSzCs().setVal(BigInteger.valueOf(21));
            styles.addStyle(new XWPFStyle(style));
        }
        paragraph.setStyle(styleId);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        CTPPr pPr = paragraph.getCTP().getPPr();
        // 模板带有直接设置的四级大纲；题注不能继续进入章节标题层级。
        (pPr.isSetOutlineLvl() ? pPr.getOutlineLvl() : pPr.addNewOutlineLvl())
                .setVal(BigInteger.valueOf(9));
        if (pPr.isSetNumPr()) {
            pPr.unsetNumPr();
        }
        CTParaRPr rPr = pPr.isSetRPr() ? pPr.getRPr() : pPr.addNewRPr();
        while (rPr.sizeOfSzArray() > 0) {
            rPr.removeSz(0);
        }
        while (rPr.sizeOfSzCsArray() > 0) {
            rPr.removeSzCs(0);
        }
        rPr.addNewSz().setVal(BigInteger.valueOf(21));
        rPr.addNewSzCs().setVal(BigInteger.valueOf(21));
    }

    private boolean isTableOrFigureCaption(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return false;
        }
        String text = safeParagraphText(paragraph).replace(" ", "").replace("　", "");
        if (text.contains("桥梁基本状况卡片") || text.contains("定期检查记录表")
                || text.startsWith("表") || text.startsWith("图")) {
            return true;
        }
        String styleId = paragraph.getStyle();
        if ("13".equals(styleId) || (styleId != null && styleId.toLowerCase().contains("caption"))) {
            return true;
        }
        String styleName = resolveStyleName(paragraph);
        if (styleName == null) {
            return false;
        }
        String compact = styleName.replace(" ", "").toLowerCase();
        return compact.contains("题注") || compact.contains("图表类标题")
                || compact.contains("表格标题") || compact.contains("caption");
    }

    private String resolveStyleName(XWPFParagraph paragraph) {
        String styleId = paragraph.getStyle();
        if (styleId == null || styleId.trim().isEmpty()) {
            return null;
        }
        try {
            XWPFDocument document = paragraph.getDocument();
            XWPFStyles styles = document == null ? null : document.getStyles();
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId.trim());
            return style == null ? null : style.getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 表题、图题在模板里常带 outlineLvl，不能当成正文标题去改成左对齐。
     */
    private boolean isCaptionParagraph(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return false;
        }
        paragraph = new XWPFParagraph(paragraph.getCTP(), paragraph.getBody());
        String text = safeParagraphText(paragraph).replace(" ", "");
        if (text.contains("桥梁基本状况卡片") || text.contains("定期检查记录表")
                || text.startsWith("表") || text.startsWith("图")) {
            return true;
        }
        try {
            CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr != null && ppr.isSetJc() && ppr.getJc().getVal() == STJc.CENTER) {
                return true;
            }
        } catch (Exception ignored) {
            // 按普通段落处理
        }
        String style = paragraph.getStyle();
        return "13".equals(style) || (style != null && style.toLowerCase().contains("caption"));
    }

    private int resolveHeadingLevel(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return 0;
        }
        String styleId = paragraph.getStyle();
        if (styleId != null && !styleId.trim().isEmpty()) {
            // 本模板 styleId 与级别错位（2=标题1、3=标题2…），必须先看样式名
            try {
                XWPFDocument doc = paragraph.getDocument();
                XWPFStyles styles = doc == null ? null : doc.getStyles();
                XWPFStyle style = styles == null ? null : styles.getStyle(styleId.trim());
                if (style != null && style.getName() != null) {
                    int named = parseHeadingNameLevel(style.getName());
                    if (named >= 1) {
                        return named;
                    }
                }
            } catch (Exception ignored) {
                // 回退到其他解析
            }
            int namedFromId = parseHeadingNameLevel(styleId);
            if (namedFromId >= 1) {
                return namedFromId;
            }
        }
        try {
            CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr != null && ppr.isSetOutlineLvl() && ppr.getOutlineLvl().getVal() != null) {
                return ppr.getOutlineLvl().getVal().intValue() + 1;
            }
        } catch (Exception ignored) {
            return 0;
        }
        return 0;
    }

    private int parseHeadingNameLevel(String rawName) {
        if (rawName == null) {
            return 0;
        }
        String normalized = rawName.trim().replace(" ", "").toLowerCase();
        if (normalized.startsWith("heading")) {
            try {
                int level = Integer.parseInt(normalized.substring("heading".length()));
                return level >= 1 && level <= 9 ? level : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        if (normalized.startsWith("标题")) {
            try {
                int level = Integer.parseInt(normalized.substring("标题".length()));
                return level >= 1 && level <= 9 ? level : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 标题：中文黑体，数字/英文 Times New Roman，不加粗。二级～三级四号，四级及以下小四。
     */
    private void applyHeadingFormat(XWPFParagraph paragraph, int headingLevel) {
        if (paragraph == null || headingLevel < 1) {
            return;
        }
        // 章节合并直接替换了 CTP，原对象可能仍缓存空的 runs；从当前 XML 重建。
        paragraph = new XWPFParagraph(paragraph.getCTP(), paragraph.getBody());
        applyParagraphLineSpacing(paragraph, true);
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        Integer fontHalfPoints = headingLevel == 1 ? null : (headingLevel <= 3 ? 28 : 24);
        applyHeadingRuns(paragraph, fontHalfPoints);
    }

    /**
     * 外观检测、结论与建议：一级黑体三号，二级黑体四号，三级及以下黑体小四。
     * 按相对层级：本节标题=一级，其下第一级标题=二级，再往下全部小四。
     */
    private void applyAppearanceAndConclusionHeadingSizes(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph paragraph = paragraphs.get(i);
            if (isCaptionParagraph(paragraph)) {
                continue;
            }
            if (!isAppearanceOrConclusionSectionHeading(safeParagraphText(paragraph))) {
                continue;
            }
            int sectionLevel = resolveHeadingLevel(paragraph);
            if (sectionLevel < 1) {
                // 模板偶发未挂上标题样式时，仍按本节一级处理
                sectionLevel = 2;
            }
            applyHeadingLook(paragraph, 32); // 一级：三号
            for (int j = i + 1; j < paragraphs.size(); j++) {
                XWPFParagraph next = paragraphs.get(j);
                if (isCaptionParagraph(next)) {
                    continue;
                }
                int nextLevel = resolveHeadingLevel(next);
                if (nextLevel < 1) {
                    continue;
                }
                if (nextLevel <= sectionLevel) {
                    break;
                }
                int relative = nextLevel - sectionLevel;
                // 二级四号；三级及以下一律小四（覆盖样式默认小五等）
                applyHeadingLook(next, relative <= 1 ? 28 : 24);
            }
        }
    }

    private boolean isAppearanceOrConclusionSectionHeading(String text) {
        if (text == null) {
            return false;
        }
        String compact = text.replace(" ", "").replace("　", "");
        return compact.contains("外观检测结果")
                || compact.contains("外观检测")
                || compact.contains("结论与建议")
                || compact.contains("结论和建议");
    }

    private void applyHeadingLook(XWPFParagraph paragraph, int fontHalfPoints) {
        paragraph = new XWPFParagraph(paragraph.getCTP(), paragraph.getBody());
        applyParagraphLineSpacing(paragraph, true);
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            XWPFRun run = paragraph.createRun();
            applyHeadingRun(run, fontHalfPoints);
            return;
        }
        applyHeadingRuns(paragraph, fontHalfPoints);
    }

    /**
     * 填报页正文：按换行拆段后写成宋体小四、数字 Times New Roman 小四、
     * 1.5 倍行距、两端对齐、首行缩进 2 字符。
     */
    private void replacePlaceholderWithBodyFormat(XWPFDocument document, String placeholder, String value) {
        if (document == null || isBlank(placeholder)) {
            return;
        }
        XWPFParagraph target = ReportGenerateTools.findParagraphByPlaceholder(document, placeholder);
        if (target != null) {
            writeFilledBodyIntoParagraph(document, target, value);
            replaceText(document, placeholder, "");
            return;
        }
        replaceText(document, placeholder, value == null ? "" : value);
    }

    private void insertFilledBodyParagraphs(XWPFDocument document, XmlCursor cursor, String text) {
        if (document == null || cursor == null) {
            return;
        }
        for (String line : splitFilledParagraphs(text)) {
            XWPFParagraph paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
            XWPFRun run = paragraph.createRun();
            run.setText(line);
            applyBodyFormat(paragraph, true);
        }
    }

    private XWPFParagraph writeFilledBodyIntoParagraph(XWPFDocument document, XWPFParagraph firstParagraph, String text) {
        if (firstParagraph == null) {
            return null;
        }
        clearParagraph(firstParagraph);
        List<String> lines = splitFilledParagraphs(text);
        if (lines.isEmpty()) {
            return firstParagraph;
        }
        XWPFRun firstRun = firstParagraph.createRun();
        firstRun.setText(lines.get(0));
        applyBodyFormat(firstParagraph, true);
        XWPFParagraph lastParagraph = firstParagraph;
        XmlCursor cursor = null;
        try {
            for (int i = 1; i < lines.size(); i++) {
                if (cursor == null) {
                    cursor = lastParagraph.getCTP().newCursor();
                    if (!cursor.toNextSibling()) {
                        cursor.toEndToken();
                        cursor.toNextToken();
                    }
                }
                XWPFParagraph paragraph = document.insertNewParagraph(cursor);
                cursor.toNextToken();
                XWPFRun run = paragraph.createRun();
                run.setText(lines.get(i));
                applyBodyFormat(paragraph, true);
                lastParagraph = paragraph;
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return lastParagraph;
    }

    private List<String> splitFilledParagraphs(String text) {
        if (isBlank(text)) {
            return Collections.emptyList();
        }
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * 正文：小四宋体、1.5 倍行距、两端对齐，可选首行缩进 2 字符。
     */
    private void applyBodyFormat(XWPFParagraph paragraph, boolean firstLineIndent) {
        if (paragraph == null) {
            return;
        }
        String style = paragraph.getStyle();
        if (style != null && style.trim().matches("[1-9]")) {
            paragraph.setStyle(null);
        }
        applyParagraphLineSpacing(paragraph, false);
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        if (ppr.isSetOutlineLvl()) {
            ppr.unsetOutlineLvl();
        }
        if (ppr.isSetNumPr()) {
            ppr.unsetNumPr();
        }
        CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(STJc.BOTH);
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        if (firstLineIndent) {
            ind.setFirstLine(BigInteger.valueOf(480));
        }
        for (XWPFRun run : paragraph.getRuns()) {
            boolean bold = Boolean.TRUE.equals(run.isBold());
            ReportGenerateTools.setMixedFontFamily(run, 24);
            run.setBold(bold);
        }
    }

    private void applyParagraphLineSpacing(XWPFParagraph paragraph, boolean halfLineBeforeAfter) {
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        if (halfLineBeforeAfter) {
            spacing.setBeforeLines(BigInteger.valueOf(50));
            spacing.setAfterLines(BigInteger.valueOf(50));
        }
    }

    /** 标题中数字/英文独立成 run，避免 WPS 按中文字体显示桥名中的数字。 */
    private void applyHeadingRuns(XWPFParagraph paragraph, Integer fontHalfPoints) {
        List<XWPFRun> runs = new ArrayList<>(paragraph.getRuns());
        for (int i = runs.size() - 1; i >= 0; i--) {
            XWPFRun run = runs.get(i);
            applyHeadingRun(run, fontHalfPoints);
            if (!isPlainHeadingRun(run)) {
                continue;
            }
            String text = run.text();
            if (text.isEmpty()) {
                continue;
            }
            List<String> chunks = new ArrayList<>();
            int start = 0;
            for (int j = 1; j <= text.length(); j++) {
                if (j == text.length() || (text.charAt(j) <= 0x7F) != (text.charAt(start) <= 0x7F)) {
                    chunks.add(text.substring(start, j));
                    start = j;
                }
            }
            if (chunks.size() < 2) {
                continue;
            }
            CTRPr properties = (CTRPr) run.getCTR().getRPr().copy();
            while (run.getCTR().sizeOfTArray() > 0) {
                run.getCTR().removeT(0);
            }
            run.setText(chunks.get(0));
            applyHeadingRun(run, fontHalfPoints);
            for (int j = 1; j < chunks.size(); j++) {
                XWPFRun part = paragraph.insertNewRun(i + j);
                part.getCTR().setRPr((CTRPr) properties.copy());
                part.setText(chunks.get(j));
                applyHeadingRun(part, fontHalfPoints);
            }
        }
    }

    private boolean isPlainHeadingRun(XWPFRun run) {
        // 保留域、图片、换行及超链接的结构，只拆普通文本。
        if (run instanceof XWPFHyperlinkRun) {
            return false;
        }
        try (XmlCursor cursor = run.getCTR().newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    String name = cursor.getName().getLocalPart();
                    if (!"rPr".equals(name) && !"t".equals(name)) {
                        return false;
                    }
                } while (cursor.toNextSibling());
            }
        }
        return true;
    }

    private void applyHeadingRun(XWPFRun run, Integer fontHalfPoints) {
        run.setBold(false);
        run.setColor("000000");
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
        boolean western = !run.text().isEmpty() && run.text().chars().allMatch(ch -> ch <= 0x7F);
        String font = western ? "Times New Roman" : "黑体";
        fonts.setAscii(font);
        fonts.setHAnsi(font);
        fonts.setEastAsia(font);
        fonts.setCs(font);
        fonts.setHint(STHint.DEFAULT);
        if (fontHalfPoints != null) {
            rPr.addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
            rPr.addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
        }
        stripBold(rPr);
    }

    /**
     * 表格全框线、居中、按列数铺满宽度。
     */
    private void applyTableGrid(XWPFTable table, int columnCount) {
        table.setWidth("100%");
        table.setTableAlignment(TableRowAlign.CENTER);
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        if (tblPr.isSetTblBorders()) {
            tblPr.unsetTblBorders();
        }
        CTTblBorders borders = tblPr.addNewTblBorders();
        borders.addNewBottom().setVal(STBorder.SINGLE);
        borders.addNewLeft().setVal(STBorder.SINGLE);
        borders.addNewRight().setVal(STBorder.SINGLE);
        borders.addNewTop().setVal(STBorder.SINGLE);
        borders.addNewInsideH().setVal(STBorder.SINGLE);
        borders.addNewInsideV().setVal(STBorder.SINGLE);
        if (columnCount > 0) {
            XWPFTableRow firstRow = table.getRow(0);
            if (firstRow != null) {
                for (int i = firstRow.getTableCells().size(); i < columnCount; i++) {
                    firstRow.createCell();
                }
            }
        }
    }

    /**
     * 设置表格单元格内容（五号宋体，1倍行距）
     */
    private void setTableCell(XWPFTableRow row, int cellIndex, String text) {
        setTableCell(row, cellIndex, text, false, ParagraphAlignment.CENTER);
    }

    /**
     * 设置表格单元格内容
     *
     * @param row       表格行
     * @param cellIndex 单元格索引
     * @param text      文本内容
     * @param isHeader  是否为表头（表头宋体加粗，数据宋体）
     */
    private void setTableCell(XWPFTableRow row, int cellIndex, String text, boolean isHeader) {
        setTableCell(row, cellIndex, text, isHeader, ParagraphAlignment.CENTER);
    }

    private void setTableCell(XWPFTableRow row, int cellIndex, String text, boolean isHeader,
                              ParagraphAlignment alignment) {
        XWPFTableCell cell = row.getCell(cellIndex);
        if (cell == null) {
            cell = row.createCell();
        }

        // 清空单元格内容
        while (cell.getParagraphs().size() > 0) {
            cell.removeParagraph(0);
        }

        // 创建段落
        XWPFParagraph paragraph = cell.addParagraph();
        paragraph.setAlignment(alignment == null ? ParagraphAlignment.CENTER : alignment);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);

        // 设置1倍行距
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) ppr = paragraph.getCTP().addNewPPr();
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(240));
        spacing.setLineRule(STLineSpacingRule.AUTO);

        // 创建文本运行（五号宋体）
        XWPFRun run = paragraph.createRun();
        run.setText(text != null ? text : "");
        run.setFontSize(10.5);
        run.setBold(isHeader);
        ReportGenerateTools.setMixedFontFamily(run, 21);
        if (isHeader) {
            run.setBold(true);
        }
    }
}
