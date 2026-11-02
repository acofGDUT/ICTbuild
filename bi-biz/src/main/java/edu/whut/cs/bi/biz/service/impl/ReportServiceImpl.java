package edu.whut.cs.bi.biz.service.impl;

import java.math.BigDecimal;
import java.util.*;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.math.BigInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.whut.cs.bi.biz.config.MinioConfig;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.controller.FileMapController;
import edu.whut.cs.bi.biz.controller.DiseaseController;
import edu.whut.cs.bi.biz.domain.dto.CauseQuery;
import edu.whut.cs.bi.biz.domain.enums.ReportTemplateTypes;
import edu.whut.cs.bi.biz.domain.enums.SingleReportMainDiseaseSummaryList;
import edu.whut.cs.bi.biz.domain.temp.ComponentDiseaseAnalysis;
import edu.whut.cs.bi.biz.domain.temp.ComponentDiseaseType;
import edu.whut.cs.bi.biz.domain.vo.Disease2ReportSummaryAiVO;
import edu.whut.cs.bi.biz.domain.vo.DiseaseCauseMatch;
import edu.whut.cs.bi.biz.domain.vo.DiseaseComparisonData;
import edu.whut.cs.bi.biz.mapper.DiseaseDetailMapper;
import edu.whut.cs.bi.biz.mapper.DiseaseMapper;
import edu.whut.cs.bi.biz.mapper.DiseaseTypeMapper;
import edu.whut.cs.bi.biz.service.*;
import edu.whut.cs.bi.biz.utils.Convert2VO;
import edu.whut.cs.bi.biz.utils.DiseaseComparisonTableUtils;
import edu.whut.cs.bi.biz.utils.ReportGenerateTools;
import edu.whut.cs.bi.biz.utils.ReportTemplateValueUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import edu.whut.cs.bi.biz.mapper.ReportMapper;
import edu.whut.cs.bi.biz.mapper.BiObjectMapper;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysDeptService;
import org.springframework.web.client.RestTemplate;

import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import edu.whut.cs.bi.biz.utils.WordFieldUtils;
import edu.whut.cs.bi.biz.utils.WordSectionLayoutUtils;

import javax.annotation.Resource;

/**
 * 检测报告Service业务层处理
 *
 * @author wanzheng
 */
@Slf4j
@Service
public class ReportServiceImpl implements IReportService {
    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private IReportDataService reportDataService;

    @Autowired
    private IReportTemplateService reportTemplateService;

    @Autowired
    private ITemplateVariableService templateVariableService;

    @Autowired
    private IFileMapService fileMapService;

    @Autowired
    private IProjectService projectService;

    @Autowired
    private IBuildingService buildingService;

    @Autowired
    private BiObjectMapper biObjectMapper;

    @Autowired
    private FileMapController fileMapController;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    @Autowired
    private DiseaseController diseaseController;

    @Autowired
    private IComponentService componentService;

    @Autowired
    private DiseaseComparisonService diseaseComparisonService;

    @Resource
    private IDiseaseService diseaseService;

    @Resource
    private IDiseaseCauseMatchService diseaseCauseMatchService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ISysDeptService deptService;

    @Value("${springAi_Rag.endpoint}")
    private String SpringAiUrl;
    @Autowired
    private DiseaseMapper diseaseMapper;

    @Autowired
    private DiseaseDetailMapper diseaseDetailMapper;

    @Autowired
    private ProgrammaticDiseaseSummaryService programmaticDiseaseSummaryService;

    @Autowired
    private DiseaseTypeMapper diseaseTypeMapper;

    @Autowired
    private IBiEvaluationService biEvaluationService;

    @Autowired
    private EvaluationTableService evaluationTableService;

    @Autowired
    private ComparisonAnalysisService comparisonAnalysisService;

    @Autowired
    private TestConclusionService testConclusionService;

    @Autowired
    private IBridgeCardService bridgeCardService;

    @Autowired
    private RegularInspectionService regularInspectionService;

    @Resource
    private IBiTemplateObjectService biTemplateObjectService;

    @Resource
    private Report1LevelSingleBridgeService report1LevelSingleBridgeService;

    @Resource
    private ILineMultiBridgeReportService lineMultiBridgeReportService;

    @Resource
    private ReadFileService readFileService;
    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private IPropertyService propertyService;

    /**
     * 查询检测报告
     *
     * @param id 检测报告ID
     * @return 检测报告
     */
    @Override
    public Report selectReportById(Long id) {
        return reportMapper.selectReportById(id);
    }

    /**
     * 查询检测报告列表
     *
     * @param report 检测报告
     * @return 检测报告
     */
    @Override
    public List<Report> selectReportList(Report report) {
        Long currentUserId = ShiroUtils.getUserId();
        List<String> roles = sysUserMapper.selectUserRoleByUserId(currentUserId);
        SysUser sysUser = sysUserMapper.selectUserById(currentUserId);

        // 检查用户是否有管理员角色
        boolean isAdmin = roles.stream().anyMatch(role -> "admin".equals(role) || "system_admin".equals(role) || "business_admin".equals(role));

        List<Report> reports = null;
        if (isAdmin) {
            // 超级管理员, 所有数据都能看到
            PageUtils.startPage();
            reports = reportMapper.selectReportList(report, null, null, null);
        } else {
            if (roles.stream().anyMatch(role -> "department_business_admin".equals(role))) {
                // 部门管理员
                // 查询部门 - 设置父部门
                SysDept curSysDept = deptService.selectDeptById(sysUser.getDeptId());
                // 当前登录用户所属Department与报告关联项目的owner_dept_id或dept_id一致的所有业务实体
                PageUtils.startPage();
                reports = reportMapper.selectReportList(report, null, sysUser.getDeptId(), curSysDept.getParentId());
            } else {
                // 普通用户：只能看到自己创建、审核、批准的报告，或参与项目的报告
                PageUtils.startPage();
                reports = reportMapper.selectReportList(report, currentUserId, null, null);
            }
        }
        return reports;
    }

    /**
     * 新增检测报告
     *
     * @param report 检测报告
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertReport(Report report) {
        report.setCreateBy(ShiroUtils.getLoginName());
        // 设置状态为未生成
        report.setStatus(0);
        // 插入报告记录
        int rows = reportMapper.insertReport(report);
        // 生成报告数据
        if (rows > 0) {
            generateReportData(report.getId());
        }
        return rows;
    }

    /**
     * 修改检测报告
     *
     * @param report 检测报告
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateReport(Report report) {
        // 如果修改了模板，需要重新生成报告数据
        Report oldReport = reportMapper.selectReportById(report.getId());
        report.setUpdateBy(ShiroUtils.getLoginName());
        int rows = reportMapper.updateReport(report);
        if (rows > 0 && oldReport != null && !oldReport.getReportTemplateId().equals(report.getReportTemplateId())) {
            // 删除原有报告数据
            reportDataService.deleteReportDataByReportId(report.getId());
            // 重新生成报告数据
            generateReportData(report.getId());
        }
        return rows;
    }

    /**
     * 删除检测报告对象
     *
     * @param ids 需要删除的数据ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteReportByIds(String ids) {
        // 删除报告数据
        Long[] idArray = Convert.toLongArray(ids);
        for (Long id : idArray) {
            reportDataService.deleteReportDataByReportId(id);
        }
        return reportMapper.deleteReportByIds(Convert.toStrArray(ids));
    }

    /**
     * 删除检测报告信息
     *
     * @param id 检测报告ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteReportById(Long id) {
        // 删除报告数据
        reportDataService.deleteReportDataByReportId(id);
        return reportMapper.deleteReportById(id);
    }

    /**
     * 生成报告数据
     *
     * @param reportId 报告ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int generateReportData(Long reportId) {
        // 查询报告信息
        Report report = reportMapper.selectReportById(reportId);
        if (report == null) {
            return 0;
        }

        // 查询模板信息
        ReportTemplate template = reportTemplateService.selectReportTemplateById(report.getReportTemplateId());
        if (template == null) {
            return 0;
        }

        // 查询模板变量
        TemplateVariable templateVariable = new TemplateVariable();
        templateVariable.setReportTemplateId(template.getId());
        List<TemplateVariable> templateVariables = templateVariableService.selectTemplateVariableList(templateVariable);

        // 生成报告数据
        List<ReportData> reportDataList = new ArrayList<>();
        String loginName = ShiroUtils.getSysUser().getLoginName();

        for (TemplateVariable variable : templateVariables) {
            ReportData reportData = new ReportData();
            reportData.setReportId(reportId);
            reportData.setKey(variable.getName());
            reportData.setValue(""); // 初始值为空
            reportData.setType(variable.getType());
            reportData.setCreateBy(loginName);
            reportDataList.add(reportData);
        }

        // 批量插入报告数据
        if (reportDataList.size() > 0) {
            return reportDataService.batchInsertReportData(reportDataList);
        }

        return 0;
    }

    /**
     * 生成报告文档
     *
     * @param report 报告ID
     * @param tasks  建筑物ID
     * @return 生成的文件路径
     */
    @Override
    public String generateReportDocument(Report report, List<Task> tasks, Long rootParentId, ReportTemplate template) {
        return generateReportDocument(report, tasks, rootParentId, template, ShiroUtils.getLoginName());
    }

    private String generateReportDocument(Report report, List<Task> tasks, Long rootParentId,
                                          ReportTemplate template, String operator) {
        try {
            return doGenerateReportDocument(report, tasks, rootParentId, template, operator);
        } finally {
            // 线程池会复用 report-async 线程，生成结束后必须摘掉 ThreadLocal。
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
        }
    }

    private String doGenerateReportDocument(Report report, List<Task> tasks, Long rootParentId,
                                            ReportTemplate template, String operator) {
        // 判断是否为单桥模板（根据模板名称判断）
        if (report != null && report.getReportTemplateId() != null) {
            try {
                ReportTemplateTypes templateType = null; // 默认
                if (template != null) {
                    templateType = ReportTemplateTypes.getEnumByDesc(template.getName());
                }
                if (lineMultiBridgeReportService.isMultiBridgeTemplate(template)) {
                    log.info("检测到多桥定期检查模板：{}，使用独立多桥生成逻辑", template.getName());
                    return lineMultiBridgeReportService.generateReportDocument(report, tasks, template, operator);
                } else if (template != null && templateType != null && ReportTemplateTypes.is2LevelSigleBridge(templateType.getType())) {
                    // 等待所有任务完成
                    Task task = tasks.get(0);
                    Disease disease = new Disease();
                    disease.setProjectId(report.getProjectId());
                    disease.setBuildingId(task.getBuildingId());
                    List<Disease> diseases = diseaseMapper.selectDiseaseList(disease);
                    List<Long> list = diseases.stream().map(Disease::getId).toList();
                    List<Attachment> attachmentBySubjects = attachmentService.getAttachmentBySubjectIds(list);
                    List<CompletableFuture<Void>> futures = readFileService.addThumbPhoto(attachmentBySubjects);
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    } catch (Exception e) {
                        log.error("处理缩略图任务时发生异常", e);
                    }
                    log.info("缩略图生成完成，共处理" + attachmentBySubjects.size() + "个附件");
                    log.info("检测到二级单桥模板：{}，使用二级单桥生成逻辑", template.getName());
                    // 添加 桥梁模板类型 参数。
                    return generateSingleBridgeReportDocument(report, tasks.get(0), templateType, operator);
                } else if (template != null && templateType != null && ReportTemplateTypes.is1LevelSigleBridge(templateType.getType())) {
                    // 等待所有任务完成
                    Task task = tasks.get(0);
                    Disease disease = new Disease();
                    disease.setProjectId(report.getProjectId());
                    disease.setBuildingId(task.getBuildingId());
                    List<Disease> diseases = diseaseMapper.selectDiseaseList(disease);
                    List<Long> list = diseases.stream().map(Disease::getId).toList();
                    List<Attachment> attachmentBySubjects = attachmentService.getAttachmentBySubjectIds(list);
                    List<CompletableFuture<Void>> futures = readFileService.addThumbPhoto(attachmentBySubjects);
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    } catch (Exception e) {
                        log.error("处理缩略图任务时发生异常", e);
                    }
                    log.info("缩略图生成完成，共处理" + attachmentBySubjects.size() + "个附件");
                    log.info("检测到一级单桥模板：{}，使用一级单桥生成逻辑", template.getName());
                    // 添加 桥梁模板类型 参数。
                    return report1LevelSingleBridgeService.generateReportDocument(report, tasks.get(0), templateType, operator);
                } else if (template != null && templateType != null && ReportTemplateTypes.isTestTemplate(templateType.getType())) {
                    log.info("检测到测试模板：{}，使用测试模板独立生成逻辑", template.getName());
                    // 调用测试模板独立生成方法
                    return generateTestTemplateReportDocument(report, tasks, rootParentId, template, operator);
                }
            } catch (Exception e) {
                if (lineMultiBridgeReportService.isMultiBridgeTemplate(template)) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                log.error("获取模板信息失败，使用默认生成逻辑", e);
            }
        }

        // 原有的组合桥模板生成逻辑
        // 获取任务关联的建筑ID和项目ID
        Building buildingQry = new Building();
        buildingQry.setRootObjectId(rootParentId);
        List<Building> buildings = buildingService.selectBuildingList(buildingQry);
        if (CollectionUtils.isEmpty(buildings)) {
            log.error("未找到组合桥的建筑物");
            return null;
        }
        Building building = buildings.get(0);
        InputStream templateStream = null;
        FileOutputStream out = null;
        XWPFDocument document = null;
        File outputFile = null;

        try {
            if (report == null) {
                return null;
            }
            // 查询项目信息
            Project project = null;
            if (report.getProjectId() != null) {
                project = projectService.selectProjectById(report.getProjectId());
            }
            log.info("project: {}", project);

            // 查询报告模板信息
            if (template == null || template.getMinioId() == null) {
                return null;
            }

            // 通过模板文件ID获取文件信息
            FileMap fileMap = fileMapService.selectFileMapById(template.getMinioId());
            if (fileMap == null) {
                return null;
            }

            // 拼接Minio下载地址
            String s = fileMap.getNewName();

            // 从Minio下载模板文件
            templateStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(s.substring(0, 2) + "/" + s)
                            .build()
            );

            // 加载Word文档
            document = new XWPFDocument(templateStream);

            // 重置域计数器（开始新文档）
            WordFieldUtils.resetCounters();

            // 查询报告数据
            ReportData queryParam = new ReportData();
            queryParam.setReportId(report.getId());
            List<ReportData> reportDataList = reportDataService.selectReportDataList(queryParam);

            // 创建数据映射
            Map<String, String> dataMap = new HashMap<>();
            for (ReportData data : reportDataList) {
                dataMap.put(data.getKey(), data.getValue());
            }

            // 获取当前日期
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);

            // 替换日期相关占位符
            replaceText(document, "${year}", String.valueOf(year));
            replaceText(document, "${month}", String.valueOf(month));
            replaceText(document, "${day}", String.valueOf(day));

            // 组合桥封面字段：用户填报值优先，项目数据只作为默认值。
            Map<String, String> defaultValues = new LinkedHashMap<>();
            if (project != null) {
                defaultValues.put("${project-name}", project.getName());
                if (project.getDept() != null && project.getDept().getDeptName() != null) {
                    defaultValues.put("${client-unit}", project.getDept().getDeptName());
                }
            }
            Map<String, String> placeholderValues = ReportTemplateValueUtils.buildPlaceholderValues(reportDataList, defaultValues);
            List<String> earlyTextKeys = Arrays.asList(
                    "${project-name}", "${client-unit}", "${委托单位}",
                    "${engineering-name}", "${工程名称}", "${detection-category}", "${检测类别}"
            );
            for (String key : earlyTextKeys) {
                if (placeholderValues.containsKey(key)) {
                    replaceText(document, key, placeholderValues.get(key));
                }
            }

            // 处理桥梁图片
            insertBridgeImages(document, building.getId());
            log.info("桥梁照片处理结束");

//            debugAllStyles(document);

            // 清空第十章病害汇总缓存，准备重新缓存第三章的结果
            testConclusionService.clearDiseaseSummaryCache();

            // 处理第三章外观检测结果
            processChapter3(document, tasks, rootParentId);
            Integer minSystemLevel = null;
            Map<Long, BiEvaluation> biEvaluationMap = new HashMap<>();
            // 处理第八章评定结果（不依赖ReportData）
            try {
                biEvaluationMap = handleChapter8EvaluationResults(document, "${chapter-8-evaluationResults}", tasks, building.getName());
                Optional<Integer> systemLevel = biEvaluationMap.values().stream()
                        // 过滤掉null对象 + systemLevel为null的情况
                        .filter(biEvaluation -> biEvaluation != null && biEvaluation.getSystemLevel() != null)
                        // 提取systemLevel值
                        .map(BiEvaluation::getSystemLevel)
                        // 查找最小值
                        .min(Integer::compareTo);
                if (systemLevel.isPresent()) {
                    minSystemLevel = systemLevel.get();
                }
            } catch (Exception e) {
                log.error("处理第八章评定结果出错: error={}", e.getMessage());
                // 如果处理失败，降级为普通文本替换
                replaceText(document, "${chapter-8-evaluationResults}", "评定结果数据获取失败");
            }

            // 处理桥梁卡片数据（使用抽象的公共服务）
            try {
                Map<String, Object> additionalData = new HashMap<>();
                additionalData.put("report", report);
                additionalData.put("project", project);
                Building bridgeCardBuilding = resolveCombinedBridgeCardBuilding(building, tasks);
                bridgeCardService.processBridgeCardData(document, bridgeCardBuilding, ReportTemplateTypes.COMBINED_BRIDGE, minSystemLevel);
                log.info("桥梁卡片数据处理完成");
            } catch (Exception e) {
                log.error("处理桥梁卡片数据失败", e);
            }

            // 处理第九章比较分析（多桥版本）
            try {
                handleChapter9ComparisonAnalysisForMultiBridge(document, "${chapter-9-comparativeAnalysisOfEvaluationResults}", tasks);
            } catch (Exception e) {
                log.error("处理第九章比较分析出错: error={}", e.getMessage());
                // 如果处理失败，降级为普通文本替换
                replaceText(document, "${chapter-9-comparativeAnalysisOfEvaluationResults}", "比较分析数据获取失败");
            }

            // 处理第十章检测结论（不依赖ReportData）
            try {
                handleChapter10TestConclusion(document, "${chapter-10-testConclusion}", tasks, building.getName(), biEvaluationMap, minSystemLevel);
                handleChapter10TestConclusionBridge(document, "${chapter-10-testConclusionBridge}", tasks);
            } catch (Exception e) {
                log.error("处理第十章检测结论出错: error={}", e.getMessage());
                // 如果处理失败，降级为普通文本替换
                replaceText(document, "${chapter-10-testConclusion}", "检测结论数据获取失败");
                replaceText(document, "${chapter-10-testConclusionBridge}", "检测结论详情数据获取失败");
            }

            // 处理第11章定期检查记录表占位符
            try {
                // 调用定期检查记录表服务处理占位符
                regularInspectionService.generateRegularInspectionTable(document, "${chapter-11-regularInspectionRecord}", tasks);
            } catch (Exception e) {
                log.error("处理第11章定期检查记录表失败: error={}", e.getMessage(), e);
                // 如果处理失败，降级为普通文本替换
            }

            // 替换其他占位符
            for (ReportData data : reportDataList) {
                String key = data.getKey();
                String value = data.getValue();
                Integer type = data.getType();

                if (value == null || value.isEmpty()) {
                    continue;
                }

                try {
                    // 根据类型处理不同的数据
                    if (type != null && type == 1) {
                        // 图片类型
                        try {
                            // 处理图片
                            handleImagePlaceholder(document, key, value, building);
                        } catch (Exception e) {
                            log.error("处理图片占位符出错: key={}, value={}, error={}", key, value, e.getMessage());
                        }
                    } else {
                        // 文本类型（默认）
                        // 检查是否是第七章的特殊字段，需要生成表格
                        if (key.contains("${chapter-7-1-focusOnDiseases}") || key.contains("${chapter-7-2-analysisOfTheCausesOfMajorDiseases}")) {
                            try {
                                handleChapter7DiseaseTable(document, key, value, tasks);
                            } catch (Exception e) {
                                log.error("处理第七章病害表格出错: key={}, value={}, error={}", key, value, e.getMessage());
                                // 如果处理失败，降级为普通文本替换
                                replaceText(document, key, value);
                            }
                        } else if (isSpatialDisplacementConclusionKey(key)) {
                            if (!replaceParagraphPlaceholderAfterFollowingTable(document, key, value)) {
                                replaceText(document, key, value);
                            }
                        } else {
                            replaceText(document, key, value);
                        }
                    }
                } catch (Exception e) {
                    log.error("替换占位符出错: key={}, value={}, type={}, error={}", key, value, type, e.getMessage());
                }
            }

            // 更新文档中的所有域
            WordFieldUtils.updateAllFields(document);

            // 创建临时文件保存生成的文档
            String fileName = report.getName() + "_" + ".docx";
            outputFile = File.createTempFile("report_" + report.getId(), ".docx");
            out = new FileOutputStream(outputFile);
            document.write(out);
            out.close();

            // 上传到MinIO
            FileMap reportFileMap = fileMapService.handleFileUploadFromFile(outputFile, fileName, operator);

            // 更新报告状态和MinioId已生成
            report.setStatus(1);
            report.setMinioId(Long.valueOf(reportFileMap.getId()));
            reportMapper.updateReport(report);

            return reportFileMap.getId().toString();
        } catch (Exception e) {
            log.error("生成报告报错：{}", e.getMessage());
            return null;
        } finally {
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
            // 关闭资源
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
                // 删除临时文件
                if (outputFile != null && outputFile.exists()) {
                    outputFile.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 异步生成报告文档（支持多任务）
     *
     * @param report 报告
     * @param tasks  任务列表
     */
    @Async("reportTaskExecutor")
    public void generateReportDocumentAsync(Report report, List<Task> tasks, Long rootParentId,
                                            ReportTemplate template, String operator) {
        Long previousMinioId = report.getMinioId();
        try {
            // 更新报告状态为生成中
            Report updateReport = new Report();
            updateReport.setId(report.getId());
            updateReport.setStatus(2);
            updateReport.setUpdateBy(operator);
            updateReport.setRemark("");
            updateReport.setUpdateTime(new Date());
            reportMapper.updateReport(updateReport);
            report.setRemark("");

            // 生成报告文档
            log.info("开始生成报告");
            String minioId = generateReportDocument(report, tasks, rootParentId, template, operator);
            if (minioId == null || minioId.isBlank()) {
                throw new IllegalStateException("报告生成未返回文件ID");
            }
            log.info("生成报告结束");

            // 更新报告状态为已生成并保存MinioID
            updateReport = new Report();
            updateReport.setId(report.getId());
            updateReport.setStatus(1);
            updateReport.setMinioId(Long.valueOf(minioId));
            updateReport.setUpdateBy(operator);
            updateReport.setRemark("");
            updateReport.setUpdateTime(new Date());
            reportMapper.updateReport(updateReport);

            // 只有新文件已保存且报告状态已更新，才清理旧文件。
            if (previousMinioId != null && !previousMinioId.equals(updateReport.getMinioId())) {
                try {
                    fileMapService.deleteFileMapById(previousMinioId);
                } catch (Exception cleanupError) {
                    log.warn("新报告已生成，清理旧文件失败: reportId={}, fileId={}",
                            report.getId(), previousMinioId, cleanupError);
                }
            }
        } catch (Exception e) {
            log.error("异步生成报告文档失败", e);
            // 更新报告状态为生成失败
            Report updateReport = new Report();
            updateReport.setId(report.getId());
            updateReport.setStatus(3);
            updateReport.setUpdateBy(operator);
            updateReport.setUpdateTime(new Date());
            // 2025 12.5 更新
            // 失败时设置 remark 为 错误原因 ， 在鼠标悬停时显示。
            String errorReason = e.getMessage();
            updateReport.setRemark("生成报告失败，原因：" + errorReason);
            reportMapper.updateReport(updateReport);
        } finally {
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
        }
    }

    /**
     * 测试模板独立生成方法
     * 复用组合桥逻辑，但使用测试模板特有的处理方式
     */
    public String generateTestTemplateReportDocument(Report report, List<Task> tasks, Long rootParentId,
                                                     ReportTemplate template, String operator) {
        // 获取任务关联的建筑ID和项目ID
        Building buildingQry = new Building();
        buildingQry.setRootObjectId(rootParentId);
        List<Building> buildings = buildingService.selectBuildingList(buildingQry);
        if (CollectionUtils.isEmpty(buildings)) {
            log.error("测试模板：未找到组合桥的建筑物");
            return null;
        }
        Building building = buildings.get(0);
        InputStream templateStream = null;
        FileOutputStream out = null;
        XWPFDocument document = null;
        File outputFile = null;

        try {
            if (report == null) {
                return null;
            }
            // 查询项目信息
            Project project = null;
            if (report.getProjectId() != null) {
                project = projectService.selectProjectById(report.getProjectId());
            }
            log.info("测试模板 - project: {}", project);

            // 查询报告模板信息
            if (template == null || template.getMinioId() == null) {
                return null;
            }

            // 通过模板文件ID获取文件信息
            FileMap fileMap = fileMapService.selectFileMapById(template.getMinioId());
            if (fileMap == null) {
                return null;
            }

            // 拼接Minio下载地址
            String s = fileMap.getNewName();

            // 从Minio下载模板文件
            templateStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(s.substring(0, 2) + "/" + s)
                            .build()
            );

            // 加载Word文档
            document = new XWPFDocument(templateStream);

            // 重置域计数器（开始新文档）
            WordFieldUtils.resetCounters();

            // 查询报告数据
            ReportData queryParam = new ReportData();
            queryParam.setReportId(report.getId());
            List<ReportData> reportDataList = reportDataService.selectReportDataList(queryParam);

            // 创建数据映射
            Map<String, String> dataMap = new HashMap<>();
            for (ReportData data : reportDataList) {
                dataMap.put(data.getKey(), data.getValue());
            }

            // 获取当前日期
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);

            // 替换日期相关占位符
            replaceText(document, "${year}", String.valueOf(year));
            replaceText(document, "${month}", String.valueOf(month));
            replaceText(document, "${day}", String.valueOf(day));

            // 测试模板封面字段：用户填报值优先，项目数据只作为默认值
            Map<String, String> defaultValues = new LinkedHashMap<>();
            if (project != null) {
                defaultValues.put("${project-name}", project.getName());
                if (project.getDept() != null && project.getDept().getDeptName() != null) {
                    defaultValues.put("${client-unit}", project.getDept().getDeptName());
                }
            }
            Map<String, String> placeholderValues = ReportTemplateValueUtils.buildPlaceholderValues(reportDataList, defaultValues);
            List<String> earlyTextKeys = Arrays.asList(
                    "${project-name}", "${client-unit}", "${委托单位}",
                    "${engineering-name}", "${工程名称}", "${detection-category}", "${检测类别}"
            );
            for (String key : earlyTextKeys) {
                if (placeholderValues.containsKey(key)) {
                    replaceText(document, key, placeholderValues.get(key));
                }
            }

            // 处理桥梁图片
            insertBridgeImages(document, building.getId());
            log.info("测试模板 - 桥梁照片处理结束");

            // 清空第十章病害汇总缓存，准备重新缓存第三章的结果
            testConclusionService.clearDiseaseSummaryCache();

            // 处理第一章特殊结构桥梁一览表（测试模板特有）
            try {
                processChapter1SpecialStructureBridgeList(document, building, rootParentId);
                log.info("测试模板 - 第一章特殊结构桥梁一览表处理完成");
            } catch (Exception e) {
                log.error("测试模板 - 处理第一章特殊结构桥梁一览表出错: error={}", e.getMessage());
            }

            // 处理第二章部件划分及构件数量（测试模板特有）
            try {
                processChapter2ComponentDivision(document, tasks, rootParentId, building.getName());
                log.info("测试模板 - 第二章部件划分及构件数量处理完成");
            } catch (Exception e) {
                log.error("测试模板 - 处理第二章部件划分及构件数量出错: error={}", e.getMessage());
            }

            // 处理第三章外观检测结果（测试模板版本）
            // baseHeadingLevel=2：子桥标题为 Heading 3（比"外观检测结果"低一级），结构 Heading 4，构件 Heading 5
            // startSubChapterNum=1：从 3.2.1 开始编号（3.2 是"外观检测结果"）
            processChapter3ForTestTemplate(document, tasks, rootParentId, 2, 1);

            // 处理与上一次检查病害变化情况分析（独立占位符 ${chapter-3-3-diseaseComparison}）
            // baseHeadingLevel=2：标题为 Heading 2（与"外观检测结果"同级）
            // startSubChapterNum=1：子桥编号从 1 开始
            processDiseaseComparisonForTestTemplate(document, tasks, 2, 1);

            Integer minSystemLevel = null;
            Map<Long, BiEvaluation> biEvaluationMap = new HashMap<>();
            // 处理第八章评定结果（不依赖ReportData）
            try {
                biEvaluationMap = handleChapter8EvaluationResults(document, "${chapter-8-evaluationResults}", tasks, building.getName());
                Optional<Integer> systemLevel = biEvaluationMap.values().stream()
                        .filter(biEvaluation -> biEvaluation != null && biEvaluation.getSystemLevel() != null)
                        .map(BiEvaluation::getSystemLevel)
                        .min(Integer::compareTo);
                if (systemLevel.isPresent()) {
                    minSystemLevel = systemLevel.get();
                }
            } catch (Exception e) {
                log.error("测试模板 - 处理第八章评定结果出错: error={}", e.getMessage());
                replaceText(document, "${chapter-8-evaluationResults}", "评定结果数据获取失败");
            }

            // 处理桥梁卡片数据
            try {
                Map<String, Object> additionalData = new HashMap<>();
                additionalData.put("report", report);
                additionalData.put("project", project);
                Building bridgeCardBuilding = resolveCombinedBridgeCardBuilding(building, tasks);
                bridgeCardService.processBridgeCardData(document, bridgeCardBuilding, ReportTemplateTypes.TEST_TEMPLATE, minSystemLevel);
                log.info("测试模板 - 桥梁卡片数据处理完成");
            } catch (Exception e) {
                log.error("测试模板 - 处理桥梁卡片数据失败", e);
            }

            // 处理第九章比较分析（多桥版本）
            try {
                handleChapter9ComparisonAnalysisForMultiBridge(document, "${chapter-9-comparativeAnalysisOfEvaluationResults}", tasks);
            } catch (Exception e) {
                log.error("测试模板 - 处理第九章比较分析出错: error={}", e.getMessage());
                replaceText(document, "${chapter-9-comparativeAnalysisOfEvaluationResults}", "比较分析数据获取失败");
            }

            // 处理第十章检测结论（不依赖ReportData）
            try {
                handleChapter10TestConclusion(document, "${chapter-10-testConclusion}", tasks, building.getName(), biEvaluationMap, minSystemLevel);
                handleChapter10TestConclusionBridge(document, "${chapter-10-testConclusionBridge}", tasks);
            } catch (Exception e) {
                log.error("测试模板 - 处理第十章检测结论出错: error={}", e.getMessage());
                replaceText(document, "${chapter-10-testConclusion}", "检测结论数据获取失败");
                replaceText(document, "${chapter-10-testConclusionBridge}", "检测结论详情数据获取失败");
            }

            // 处理第11章定期检查记录表占位符
            try {
                regularInspectionService.generateRegularInspectionTable(document, "${chapter-11-regularInspectionRecord}", tasks);
            } catch (Exception e) {
                log.error("测试模板 - 处理第11章定期检查记录表失败: error={}", e.getMessage(), e);
            }

            // 替换其他占位符（包括用户自定义的 ${chapter-2-componentCodeRule} 等）
            for (ReportData data : reportDataList) {
                String key = data.getKey();
                String value = data.getValue();
                Integer type = data.getType();

                if (value == null || value.isEmpty()) {
                    continue;
                }

                try {
                    // 根据类型处理不同的数据
                    if (type != null && type == 1) {
                        // 图片类型
                        try {
                            handleImagePlaceholder(document, key, value, building);
                        } catch (Exception e) {
                            log.error("测试模板 - 处理图片占位符出错: key={}, value={}, error={}", key, value, e.getMessage());
                        }
                    } else {
                        // 文本类型（默认）
                        if (key.contains("${chapter-7-1-focusOnDiseases}") || key.contains("${chapter-7-2-analysisOfTheCausesOfMajorDiseases}")) {
                            try {
                                handleChapter7DiseaseTable(document, key, value, tasks);
                            } catch (Exception e) {
                                log.error("测试模板 - 处理第七章病害表格出错: key={}, value={}, error={}", key, value, e.getMessage());
                                replaceText(document, key, value);
                            }
                        } else if (isSpatialDisplacementConclusionKey(key)) {
                            if (!replaceParagraphPlaceholderAfterFollowingTable(document, key, value)) {
                                replaceText(document, key, value);
                            }
                        } else {
                            replaceText(document, key, value);
                        }
                    }
                } catch (Exception e) {
                    log.error("测试模板 - 替换占位符出错: key={}, value={}, type={}, error={}", key, value, type, e.getMessage());
                }
            }

            // 更新文档中的所有域
            WordFieldUtils.updateAllFields(document);

            // 创建临时文件保存生成的文档
            String fileName = report.getName() + "_" + ".docx";
            outputFile = File.createTempFile("report_" + report.getId(), ".docx");
            out = new FileOutputStream(outputFile);
            document.write(out);
            out.close();

            // 上传到MinIO
            FileMap reportFileMap = fileMapService.handleFileUploadFromFile(outputFile, fileName, operator);

            // 更新报告状态和MinioId已生成
            report.setStatus(1);
            report.setMinioId(Long.valueOf(reportFileMap.getId()));
            reportMapper.updateReport(report);

            return reportFileMap.getId().toString();
        } catch (Exception e) {
            log.error("测试模板 - 生成报告报错：{}", e.getMessage());
            return null;
        } finally {
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
            // 关闭资源
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
                // 删除临时文件
                if (outputFile != null && outputFile.exists()) {
                    outputFile.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 替换Word文档中的文本
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    private void replaceText(XWPFDocument document, String oldText, String newText) {
        if (newText == null) {
            newText = "";
        }

        // 替换正文段落中的文本
        replaceTextInParagraphs(document.getParagraphs(), oldText, newText, "正文段落");

        // 替换正文表格中的文本
        replaceTextInTables(document.getTables(), oldText, newText, "正文表格");

        // 替换页眉中的文本
        replaceTextInHeaders(document, oldText, newText);

        // 替换页脚中的文本
        replaceTextInFooters(document, oldText, newText);

        // 覆盖文本框、形状等普通段落 API 无法遍历到的 w:t 文本节点。
        replaceTextInXmlTextNodes(document.getDocument(), oldText, newText, "正文XML");
    }

    private void replaceTextInXmlTextNodes(XmlObject root, String oldText, String newText, String location) {
        if (root == null || oldText == null || oldText.isEmpty()) {
            return;
        }
        try {
            XmlObject[] textNodes = root.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:t");
            for (XmlObject textNode : textNodes) {
                XmlCursor cursor = textNode.newCursor();
                try {
                    String text = cursor.getTextValue();
                    if (text != null && text.contains(oldText)) {
                        cursor.setTextValue(text.replace(oldText, newText));
                        log.debug("在{}中替换XML文本节点占位符: {}", location, oldText);
                    }
                } finally {
                    // cursor.dispose() 已过时，不再调用
                }
            }
        } catch (Exception e) {
            log.warn("处理{}文本节点时发生错误: {}", location, e.getMessage());
        }
    }

    private Building resolveCombinedBridgeCardBuilding(Building combinedBuilding, List<Task> tasks) {
        if (combinedBuilding == null || combinedBuilding.getRootPropertyId() != null || tasks == null) {
            return combinedBuilding;
        }
        Optional<Building> propertySource = tasks.stream()
                .filter(Objects::nonNull)
                .map(Task::getBuilding)
                .filter(Objects::nonNull)
                .filter(taskBuilding -> taskBuilding.getRootPropertyId() != null)
                .findFirst();
        if (propertySource.isEmpty()) {
            log.warn("组合桥及子任务均未找到rootPropertyId，桥梁卡片将仅使用Building基础字段兜底。buildingId={}", combinedBuilding.getId());
            return combinedBuilding;
        }

        Building source = propertySource.get();
        Building bridgeCardBuilding = new Building();
        BeanUtils.copyProperties(combinedBuilding, bridgeCardBuilding);
        bridgeCardBuilding.setRootPropertyId(source.getRootPropertyId());
        if (bridgeCardBuilding.getRootObjectId() == null) {
            bridgeCardBuilding.setRootObjectId(source.getRootObjectId());
        }
        log.info("组合桥父级建筑缺少rootPropertyId，桥梁卡片使用子桥属性根兜底。combinedBuildingId={}, sourceBuildingId={}, rootPropertyId={}",
                combinedBuilding.getId(), source.getId(), source.getRootPropertyId());
        return bridgeCardBuilding;
    }

    /**
     * 设置Run的文本，支持处理换行符
     * <p>
     * 如果文本中包含换行符（\n或\r\n），会将文本分行，并在每行之间插入换行符。
     * 这样可以在Word中正确显示用户在前端textarea中输入的多行文本。
     * </p>
     *
     * @param run  XWPFRun对象
     * @param text 要设置的文本（可能包含换行符）
     */
    private void setTextWithLineBreaks(XWPFRun run, String text) {
        if (text == null || text.isEmpty()) {
            run.setText("", 0);
            return;
        }

        // 分割文本（支持Windows和Unix风格的换行符）
        String[] lines = text.split("\\r?\\n");

        if (lines.length == 1) {
            // 没有换行符，直接设置
            run.setText(text, 0);
        } else {
            // 有换行符，需要分行处理
            for (int i = 0; i < lines.length; i++) {
                if (i == 0) {
                    // 第一行使用setText设置到run的位置0
                    run.setText(lines[i], 0);
                } else {
                    // 后续行先添加换行符，再添加文本
                    run.addBreak();
                    run.setText(lines[i]);
                }
            }
        }
    }

    private void setParagraphTextWithLineBreaks(XWPFParagraph paragraph, XWPFRun run, String text) {
        if (text == null || !text.matches("(?s).*\\R.*")) {
            setTextWithLineBreaks(run, text);
            return;
        }

        String[] lines = text.split("\\R", -1);
        ReportRunsStyle runStyle = ReportRunsStyle.snapshot(run);
        run.setText(lines[0], 0);

        XWPFParagraph anchor = paragraph;
        for (int i = 1; i < lines.length; i++) {
            XWPFParagraph nextParagraph = insertParagraphAfter(anchor);
            if (nextParagraph == null) {
                run.addBreak();
                run.setText(lines[i]);
                continue;
            }
            copyParagraphStyle(paragraph, nextParagraph);
            XWPFRun nextRun = nextParagraph.createRun();
            runStyle.apply(nextRun);
            nextRun.setText(lines[i]);
            anchor = nextParagraph;
        }
    }

    private XWPFParagraph insertParagraphAfter(XWPFParagraph paragraph) {
        XmlCursor cursor = paragraph.getCTP().newCursor();
        try {
            cursor.toEndToken();
            cursor.toNextToken();
            IBody body = paragraph.getBody();
            if (body instanceof XWPFDocument) {
                return ((XWPFDocument) body).insertNewParagraph(cursor);
            }
            if (body instanceof XWPFTableCell) {
                return ((XWPFTableCell) body).insertNewParagraph(cursor);
            }
            return null;
        } finally {
            // cursor.dispose() 已过时，不再调用
        }
    }

    private void copyParagraphStyle(XWPFParagraph source, XWPFParagraph target) {
        if (source.getStyle() != null) {
            target.setStyle(source.getStyle());
        }
        target.setAlignment(source.getAlignment());
        target.setVerticalAlignment(source.getVerticalAlignment());
        if (source.getCTP().isSetPPr()) {
            target.getCTP().setPPr((CTPPr) source.getCTP().getPPr().copy());
        }
    }

    private boolean isSpatialDisplacementConclusionKey(String key) {
        // 临时回退：按用户反馈，先关闭“表后插入”特殊处理，恢复为普通占位符替换。
        return false;
    }

    private boolean replaceParagraphPlaceholderAfterFollowingTable(XWPFDocument document, String key, String value) {
        List<IBodyElement> bodyElements = document.getBodyElements();
        for (int i = 0; i < bodyElements.size(); i++) {
            IBodyElement element = bodyElements.get(i);
            if (!(element instanceof XWPFParagraph)) {
                continue;
            }
            XWPFParagraph placeholderParagraph = (XWPFParagraph) element;
            String text = placeholderParagraph.getText();
            if (text == null || !text.contains(key)) {
                continue;
            }
            int tableIndex = ReportTemplateValueUtils.findNearestFollowingTableIndex(
                    bodyElements.stream().map(this::bodyElementType).collect(Collectors.toList()), i, 8);
            if (tableIndex < 0) {
                return false;
            }
            XWPFTable followingTable = (XWPFTable) bodyElements.get(tableIndex);

            XWPFParagraph conclusionParagraph = insertParagraphAfter(followingTable);
            if (conclusionParagraph == null) {
                return false;
            }
            copyParagraphStyle(placeholderParagraph, conclusionParagraph);
            XWPFRun conclusionRun = conclusionParagraph.createRun();
            if (!placeholderParagraph.getRuns().isEmpty()) {
                ReportRunsStyle.snapshot(placeholderParagraph.getRuns().get(0)).apply(conclusionRun);
            }
            setParagraphTextWithLineBreaks(conclusionParagraph, conclusionRun, value);

            int pos = document.getPosOfParagraph(placeholderParagraph);
            if (pos >= 0) {
                document.removeBodyElement(pos);
            } else {
                clearParagraph(placeholderParagraph);
            }
            return true;
        }
        return false;
    }

    private String bodyElementType(IBodyElement element) {
        if (element instanceof XWPFTable) {
            return "tbl";
        }
        if (element instanceof XWPFParagraph) {
            return "p";
        }
        return "";
    }

    private XWPFParagraph insertParagraphAfter(XWPFTable table) {
        XmlCursor cursor = table.getCTTbl().newCursor();
        try {
            cursor.toEndToken();
            cursor.toNextToken();
            IBody body = table.getBody();
            if (body instanceof XWPFDocument) {
                return ((XWPFDocument) body).insertNewParagraph(cursor);
            }
            if (body instanceof XWPFTableCell) {
                return ((XWPFTableCell) body).insertNewParagraph(cursor);
            }
            return null;
        } finally {
            // cursor.dispose() 已过时，不再调用
        }
    }

    /**
     * 替换段落列表中的文本
     *
     * @param paragraphs 段落列表
     * @param oldText    要替换的文本
     * @param newText    新文本
     * @param location   位置描述（用于日志）
     */
    private void replaceTextInParagraphs(List<XWPFParagraph> paragraphs, String oldText, String newText, String location) {
        for (XWPFParagraph paragraph : new ArrayList<>(paragraphs)) {
            String paragraphText = paragraph.getText();
            if (paragraphText != null && paragraphText.contains(oldText)) {
                List<XWPFRun> runs = paragraph.getRuns();

                // 先尝试简单替换（单个run包含完整占位符的情况）
                boolean replaced = false;
                for (int i = 0; i < runs.size(); i++) {
                    XWPFRun run = runs.get(i);
                    String text = run.getText(0);
                    if (text != null && text.contains(oldText)) {
                        // 保留原有的字体样式和大小
                        String replacedText = text.replace(oldText, newText);
                        // 使用支持换行符的方法设置文本
                        setParagraphTextWithLineBreaks(paragraph, run, replacedText);
                        replaced = true;
                        log.debug("在{}中替换占位符: {} -> {}", location, oldText, newText);
                        break;
                    }
                }

                // 如果简单替换失败，处理占位符分散在多个run的情况
                if (!replaced && runs.size() > 1) {
                    // 查找占位符的开始和结束位置
                    int startRunIndex = -1;
                    int endRunIndex = -1;
                    StringBuilder fullText = new StringBuilder();

                    // 寻找占位符的开始和结束位置
                    for (int i = 0; i < runs.size(); i++) {
                        String text = runs.get(i).getText(0);
                        if (text == null) continue;

                        fullText.append(text);
                        // 记录可能的起始位置
                        if (startRunIndex == -1 && text.contains("${")) {
                            startRunIndex = i;
                        }

                        // 检查到目前为止的文本是否包含完整占位符
                        if (fullText.toString().contains(oldText)) {
                            endRunIndex = i;
                            break;
                        }
                    }

                    // 如果找到了完整的占位符
                    if (startRunIndex != -1 && endRunIndex != -1) {
                        log.info("在{}中发现分散占位符: {} 从run[{}]到run[{}]", location, oldText, startRunIndex, endRunIndex);

                        // 将第一个run设置为替换后的文本（使用支持换行符的方法）
                        setParagraphTextWithLineBreaks(paragraph, runs.get(startRunIndex), newText);

                        // 清空其他包含占位符的run
                        for (int i = startRunIndex + 1; i <= endRunIndex; i++) {
                            runs.get(i).setText("", 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * 替换表格列表中的文本
     *
     * @param tables   表格列表
     * @param oldText  要替换的文本
     * @param newText  新文本
     * @param location 位置描述（用于日志）
     */
    private void replaceTextInTables(List<XWPFTable> tables, String oldText, String newText, String location) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    // 替换表格单元格中的段落文本
                    replaceTextInParagraphs(cell.getParagraphs(), oldText, newText, location + "单元格");

                    // 递归处理嵌套表格
                    if (!cell.getTables().isEmpty()) {
                        replaceTextInTables(cell.getTables(), oldText, newText, location + "嵌套表格");
                    }
                }
            }
        }
    }

    /**
     * 替换页眉中的文本
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    private void replaceTextInHeaders(XWPFDocument document, String oldText, String newText) {
        try {
            List<XWPFHeader> headers = document.getHeaderList();
            if (headers != null && !headers.isEmpty()) {
                log.debug("开始处理页眉，共{}个", headers.size());

                for (int i = 0; i < headers.size(); i++) {
                    XWPFHeader header = headers.get(i);
                    String location = "页眉" + (i + 1);

                    // 替换页眉段落中的文本
                    replaceTextInParagraphs(header.getParagraphs(), oldText, newText, location);

                    // 替换页眉表格中的文本
                    replaceTextInTables(header.getTables(), oldText, newText, location + "表格");
                }

                log.debug("页眉文本替换完成");
            }
        } catch (Exception e) {
            log.warn("处理页眉时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 替换页脚中的文本
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    private void replaceTextInFooters(XWPFDocument document, String oldText, String newText) {
        try {
            List<XWPFFooter> footers = document.getFooterList();
            if (footers != null && !footers.isEmpty()) {
                log.debug("开始处理页脚，共{}个", footers.size());

                for (int i = 0; i < footers.size(); i++) {
                    XWPFFooter footer = footers.get(i);
                    String location = "页脚" + (i + 1);

                    // 替换页脚段落中的文本
                    replaceTextInParagraphs(footer.getParagraphs(), oldText, newText, location);

                    // 替换页脚表格中的文本
                    replaceTextInTables(footer.getTables(), oldText, newText, location + "表格");
                }

                log.debug("页脚文本替换完成");
            }
        } catch (Exception e) {
            log.warn("处理页脚时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 处理桥梁图片
     *
     * @param document   Word文档
     * @param buildingId 建筑物ID
     * @throws Exception 异常
     */
    private void insertBridgeImages(XWPFDocument document, Long buildingId) throws Exception {
        List<FileMap> images = fileMapController.getImageMaps(buildingId, "newfront", "newside");
        String[] front = slotBridgeOverviewImages(images, "newfront");
        String[] side = slotBridgeOverviewImages(images, "newside");

        replaceImageInDocument(document, "${chapter-1-2-leftFront}", leftOverviewImage(front));
        replaceImageInDocument(document, "${chapter-1-2-rightFront}", rightOverviewImage(front));
        replaceImageInDocument(document, "${chapter-1-2-leftSide}", leftOverviewImage(side));
        replaceImageInDocument(document, "${chapter-1-2-rightSide}", rightOverviewImage(side));
    }


    /**
     * 在Word文档中替换占位符为图片（无标题版本）
     *
     * @param document      Word文档
     * @param placeholder   占位符（包含${}）
     * @param imageFileName 图片文件名
     * @throws Exception 异常
     */
    private void replaceImageInDocument(XWPFDocument document, String placeholder, String imageFileName) throws Exception {
        replaceImageInDocument(document, placeholder, imageFileName, null, false);
    }

    private void clearParagraph(XWPFParagraph paragraph) {
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }
    }

    /**
     * 处理第一章特殊结构桥梁一览表（测试模板特有）
     * 生成表1.1 洪监高速特殊结构桥梁一览表
     *
     * @param document     Word文档
     * @param building     组合桥建筑物
     * @param rootParentId 组合桥根对象ID
     */
    private void processChapter1SpecialStructureBridgeList(XWPFDocument document, Building building, Long rootParentId) throws Exception {
        // 找到占位符所在的段落
        XWPFParagraph placeholderParagraph = null;
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            if (paragraph.getText().contains("${chapter-1-specialStructureBridgeList}")) {
                placeholderParagraph = paragraph;
                break;
            }
        }

        // 如果找不到占位符，直接返回
        if (placeholderParagraph == null) {
            log.warn("测试模板 - 未找到 ${chapter-1-specialStructureBridgeList} 占位符");
            return;
        }

        // 获取占位符段落的XML游标
        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

        // 获取组合桥的跨径组合属性
        String spanCombination = "/";
        try {
            if (building.getRootPropertyId() != null) {
                Property rootProperty = propertyService.selectPropertyById(building.getRootPropertyId());
                if (rootProperty != null) {
                    List<Property> properties = propertyService.selectPropertyList(rootProperty);
                    if (properties != null) {
                        for (Property prop : properties) {
                            if ("跨径组合".equals(prop.getName()) && prop.getValue() != null) {
                                spanCombination = prop.getValue().replace('*', '×');
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("测试模板 - 获取跨径组合属性失败: {}", e.getMessage());
        }

        // 添加表格标题（五号黑体，1.5倍行距）
        XWPFParagraph tableTitlePara;
        if (cursor != null) {
            tableTitlePara = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            tableTitlePara = document.createParagraph();
        }
        tableTitlePara.setAlignment(ParagraphAlignment.CENTER);

        // 设置1.5倍行距
        CTPPr titlePpr = tableTitlePara.getCTP().getPPr();
        if (titlePpr == null) titlePpr = tableTitlePara.getCTP().addNewPPr();
        CTSpacing titleSpacing = titlePpr.isSetSpacing() ? titlePpr.getSpacing() : titlePpr.addNewSpacing();
        titleSpacing.setLine(BigInteger.valueOf(360)); // 1.5倍行距

        XWPFRun tableTitleRun = tableTitlePara.createRun();
        tableTitleRun.setText("表1.1 洪监高速特殊结构桥梁一览表");
        tableTitleRun.setBold(true);
        tableTitleRun.setFontFamily("黑体");
        tableTitleRun.setFontSize(10.5); // 五号

        // 创建表格（2行5列：表头+1行数据）
        XWPFTable table;
        if (cursor != null) {
            table = document.insertNewTbl(cursor);
            cursor.toNextToken();
        } else {
            table = document.createTable();
        }

        // 初始化表格结构（2行5列）
        for (int i = 0; i < 2; i++) {
            XWPFTableRow row = table.getRow(i);
            if (row == null) {
                row = table.createRow();
            }
            for (int j = 0; j < 5; j++) {
                XWPFTableCell cell = row.getCell(j);
                if (cell == null) {
                    row.createCell();
                }
            }
        }

        // 设置表格样式
        table.setWidth("100%");
        table.setTableAlignment(TableRowAlign.CENTER);

        // 设置表头（五号黑体）
        XWPFTableRow headerRow = table.getRow(0);
        setTableCell(headerRow, 0, "序号", true);
        setTableCell(headerRow, 1, "桥梁名称", true);
        setTableCell(headerRow, 2, "中心桩号", true);
        setTableCell(headerRow, 3, "桥梁全长", true);
        setTableCell(headerRow, 4, "跨径组合", true);

        // 填充数据（组合桥信息）
        XWPFTableRow dataRow = table.getRow(1);
        setTableCell(dataRow, 0, "1");
        setTableCell(dataRow, 1, building.getName());
        setTableCell(dataRow, 2, building.getBridgePileNumber() != null ? building.getBridgePileNumber() : "/");
        setTableCell(dataRow, 3, building.getBridgeLength() != null ? building.getBridgeLength() : "/");
        setTableCell(dataRow, 4, spanCombination);

        // 删除占位符段落
        placeholderParagraph.removeRun(0);
        if (placeholderParagraph.getRuns().size() == 0) {
            for (int i = 0; i < document.getParagraphs().size(); i++) {
                if (document.getParagraphs().get(i) == placeholderParagraph) {
                    document.removeBodyElement(i);
                    break;
                }
            }
        }
    }

    /**
     * 处理第二章部件划分及构件数量（测试模板特有）
     * 生成 2.2.部件划分及构件数量 章节
     *
     * @param document     Word文档
     * @param tasks        任务列表
     * @param rootParentId 组合桥根对象ID
     * @param bridgeName   组合桥名称
     */
    private void processChapter2ComponentDivision(XWPFDocument document, List<Task> tasks, Long rootParentId, String bridgeName) throws Exception {
        // 找到占位符所在的段落
        XWPFParagraph placeholderParagraph = null;
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            if (paragraph.getText().contains("${chapter-2-componentDivision}")) {
                placeholderParagraph = paragraph;
                break;
            }
        }

        // 如果找不到占位符，直接返回
        if (placeholderParagraph == null) {
            log.warn("测试模板 - 未找到 ${chapter-2-componentDivision} 占位符");
            return;
        }

        // 获取占位符段落的XML游标
        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

        // 获取所有子部件
        List<BiObject> allObjects = biObjectMapper.selectChildrenById(rootParentId);

        // 获取子桥的根对象
        List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(
                tasks.stream()
                        .filter(t -> Objects.nonNull(t) && Objects.nonNull(t.getBuilding()) && Objects.nonNull(t.getBuilding().getRootObjectId()))
                        .map(t -> t.getBuilding().getRootObjectId())
                        .distinct()
                        .collect(Collectors.toList())
        );

        Map<Long, BiObject> biObjectMap = biObjects.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(BiObject::getId, Function.identity(), (a, b) -> a));

        // 为每个子桥生成部件划分及构件数量表
        int subChapterNum = 1;
        for (Task task : tasks) {
            Building subBuilding = task.getBuilding();
            BiObject subBridge = biObjectMap.get(subBuilding.getRootObjectId());
            if (subBridge == null) {
                log.warn("测试模板 - 未找到子桥的根对象：{}", subBuilding.getName());
                continue;
            }

            // 生成子桥标题（不使用标题样式，使用普通段落）
            XWPFParagraph titlePara;
            if (cursor != null) {
                titlePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                titlePara = document.createParagraph();
            }
            titlePara.setAlignment(ParagraphAlignment.LEFT);

            // 创建标题运行（加粗，黑体）
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText("2.2." + subChapterNum + " " + subBridge.getName());
            titleRun.setBold(true);
            titleRun.setFontFamily("黑体");
            titleRun.setFontSize(14);

            // 添加说明段落（小四宋体，1.5倍行距）
            XWPFParagraph introPara;
            if (cursor != null) {
                introPara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                introPara = document.createParagraph();
            }

            // 设置1.5倍行距
            CTPPr introPpr = introPara.getCTP().getPPr();
            if (introPpr == null) introPpr = introPara.getCTP().addNewPPr();
            CTSpacing introSpacing = introPpr.isSetSpacing() ? introPpr.getSpacing() : introPpr.addNewSpacing();
            introSpacing.setLine(BigInteger.valueOf(360)); // 1.5倍行距

            XWPFRun introRun = introPara.createRun();
            introRun.setText("各桥梁部件划分及构件数量如下表所示：");
            introRun.setFontFamily("宋体");
            introRun.setFontSize(12); // 小四

            // 收集该子桥的构件数据，按层级结构分组
            Map<String, List<BiObject>> structureMap = collectComponentStructure(subBridge, allObjects);

            // 添加表格标题（五号黑体，1.5倍行距）
            XWPFParagraph tableTitlePara;
            if (cursor != null) {
                tableTitlePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                tableTitlePara = document.createParagraph();
            }
            tableTitlePara.setAlignment(ParagraphAlignment.CENTER);

            // 设置1.5倍行距
            CTPPr tableTitlePpr = tableTitlePara.getCTP().getPPr();
            if (tableTitlePpr == null) tableTitlePpr = tableTitlePara.getCTP().addNewPPr();
            CTSpacing tableTitleSpacing = tableTitlePpr.isSetSpacing() ? tableTitlePpr.getSpacing() : tableTitlePpr.addNewSpacing();
            tableTitleSpacing.setLine(BigInteger.valueOf(360)); // 1.5倍行距

            XWPFRun tableTitleRun = tableTitlePara.createRun();
            tableTitleRun.setText("表3." + subChapterNum + " " + subBridge.getName() + "桥梁部件划分及构件数量表");
            tableTitleRun.setBold(true);
            tableTitleRun.setFontFamily("黑体");
            tableTitleRun.setFontSize(10.5); // 五号

            // 生成表格
            generateComponentTable(document, cursor, subBridge.getName(), structureMap);

            subChapterNum++;
        }

        // 删除占位符段落
        placeholderParagraph.removeRun(0);
        if (placeholderParagraph.getRuns().size() == 0) {
            for (int i = 0; i < document.getParagraphs().size(); i++) {
                if (document.getParagraphs().get(i) == placeholderParagraph) {
                    document.removeBodyElement(i);
                    break;
                }
            }
        }
    }

    /**
     * 收集构件结构数据，按桥梁结构分组
     *
     * @param subBridge 子桥根对象
     * @param allObjects 所有对象
     * @return 按桥梁结构分组的构件数据
     */
    private Map<String, List<BiObject>> collectComponentStructure(BiObject subBridge, List<BiObject> allObjects) {
        Map<String, List<BiObject>> structureMap = new LinkedHashMap<>();

        // 获取子桥的直接子节点（第一层：上部结构、下部结构、桥面系）
        List<BiObject> level1Children = allObjects.stream()
                .filter(o -> subBridge.getId().equals(o.getParentId()))
                .sorted(Comparator.comparing(BiObject::getOrderNum, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        for (BiObject level1 : level1Children) {
            String structureName = level1.getName(); // 上部结构、下部结构、桥面系
            List<BiObject> components = new ArrayList<>();

            // 获取第二层构件（桥梁部件）
            List<BiObject> level2Children = allObjects.stream()
                    .filter(o -> level1.getId().equals(o.getParentId()))
                    .sorted(Comparator.comparing(BiObject::getOrderNum, Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());

            for (BiObject level2 : level2Children) {
                // 获取第三层构件（如果有）
                List<BiObject> level3Children = allObjects.stream()
                        .filter(o -> level2.getId().equals(o.getParentId()))
                        .sorted(Comparator.comparing(BiObject::getOrderNum, Comparator.nullsLast(Comparator.naturalOrder())))
                        .collect(Collectors.toList());

                if (level3Children.isEmpty()) {
                    // 没有第三层，直接添加第二层
                    components.add(level2);
                } else {
                    // 有第三层，添加第三层
                    components.addAll(level3Children);
                }
            }

            structureMap.put(structureName, components);
        }

        return structureMap;
    }

    /**
     * 生成部件划分及构件数量表格（带单元格合并）
     *
     * @param document     Word文档
     * @param cursor       XML游标
     * @param bridgeName   桥梁名称
     * @param structureMap 构件数据
     */
    private void generateComponentTable(XWPFDocument document, XmlCursor cursor, String bridgeName, Map<String, List<BiObject>> structureMap) {
        // 计算总行数（表头 + 数据行）
        int totalRows = 1; // 表头
        for (Map.Entry<String, List<BiObject>> entry : structureMap.entrySet()) {
            totalRows += entry.getValue().size();
        }

        // 创建表格
        XWPFTable table;
        if (cursor != null) {
            table = document.insertNewTbl(cursor);
            cursor.toNextToken();
        } else {
            table = document.createTable();
        }

        // 初始化表格结构（添加行和单元格）
        for (int i = 0; i < totalRows; i++) {
            XWPFTableRow row = table.getRow(i);
            if (row == null) {
                row = table.createRow();
            }
            // 确保每行有5个单元格
            for (int j = 0; j < 5; j++) {
                XWPFTableCell cell = row.getCell(j);
                if (cell == null) {
                    row.createCell();
                }
            }
        }

        // 设置表格样式
        table.setWidth("100%");
        table.setTableAlignment(TableRowAlign.CENTER);

        // 设置表头
        XWPFTableRow headerRow = table.getRow(0);
        setTableCell(headerRow, 0, "序号", true);
        setTableCell(headerRow, 1, "桥梁结构", true);
        setTableCell(headerRow, 2, "桥梁部件", true);
        setTableCell(headerRow, 3, "构件数量", true);
        setTableCell(headerRow, 4, "说明", true);

        // 填充数据，并记录需要合并的单元格范围
        int rowIndex = 1;
        int sequenceNum = 1;
        // 记录每个桥梁结构的起始行和结束行
        List<int[]> mergeRanges = new ArrayList<>();

        for (Map.Entry<String, List<BiObject>> entry : structureMap.entrySet()) {
            String structureName = entry.getKey();
            List<BiObject> components = entry.getValue();

            int startRow = rowIndex;
            for (int i = 0; i < components.size(); i++) {
                BiObject component = components.get(i);
                XWPFTableRow row = table.getRow(rowIndex);

                // 序号
                setTableCell(row, 0, String.valueOf(sequenceNum));

                // 桥梁结构（先设置文本，后面再合并）
                setTableCell(row, 1, structureName);

                // 桥梁部件
                setTableCell(row, 2, component.getName());

                // 构件数量
                Integer count = component.getCount();
                setTableCell(row, 3, count != null ? String.valueOf(count) : "/");

                // 说明
                String remark = component.getRemark();
                setTableCell(row, 4, remark != null ? remark : "/");

                rowIndex++;
                sequenceNum++;
            }
            int endRow = rowIndex - 1;

            // 如果该结构有多个构件，记录合并范围
            if (components.size() > 1) {
                mergeRanges.add(new int[]{startRow, endRow});
            }
        }

        // 合并第二列（桥梁结构）相同的单元格
        for (int[] range : mergeRanges) {
            mergeVerticalCells(table, range[0], range[1], 1);
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
                cell.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
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
     * 设置表格单元格内容（五号宋体，1倍行距）
     */
    private void setTableCell(XWPFTableRow row, int cellIndex, String text) {
        setTableCell(row, cellIndex, text, false);
    }

    /**
     * 设置表格单元格内容
     *
     * @param row       表格行
     * @param cellIndex 单元格索引
     * @param text      文本内容
     * @param isHeader  是否为表头（表头使用黑体，数据使用宋体）
     */
    private void setTableCell(XWPFTableRow row, int cellIndex, String text, boolean isHeader) {
        XWPFTableCell cell = row.getCell(cellIndex);
        if (cell == null) {
            cell = row.createCell();
        }

        // 清空单元格内容
        cell.removeParagraph(0);

        // 创建段落
        XWPFParagraph paragraph = cell.addParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);

        // 设置1倍行距
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) ppr = paragraph.getCTP().addNewPPr();
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(240)); // 1倍行距

        // 创建文本运行（五号）
        XWPFRun run = paragraph.createRun();
        run.setText(text != null ? text : "");
        run.setFontSize(10.5); // 五号

        if (isHeader) {
            // 表头：黑体加粗
            run.setFontFamily("黑体");
            run.setBold(true);
        } else {
            // 数据：宋体
            run.setFontFamily("宋体");
        }
    }

    /**
     * 处理第三章外观检测结果
     *
     * @param document Word文档
     * @param tasks    建筑物信息
     * @throws Exception 异常
     */
    private void processChapter3(XWPFDocument document, List<Task> tasks, Long rootParentId) throws Exception {


        // 获取所有子部件
        List<BiObject> allObjects = biObjectMapper.selectChildrenById(rootParentId);

        List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(
                tasks.stream()
                        .filter(t -> Objects.nonNull(t) && Objects.nonNull(t.getBuilding()) && Objects.nonNull(t.getBuilding().getRootObjectId()))
                        .map(t -> t.getBuilding().getRootObjectId())
                        .distinct()
                        .collect(Collectors.toList())
        );

        Map<Long, BiObject> biObjectMap = biObjects.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(BiObject::getId, Function.identity(), (a, b) -> a));

        // 找到占位符所在的段落
        XWPFParagraph placeholderParagraph = null;

        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            if (paragraph.getText().contains("${chapter-3-appearanceInspectionResults}")) {
                placeholderParagraph = paragraph;
                break;
            }
        }

        // 如果找不到占位符，直接返回
        if (placeholderParagraph == null) {
            return;
        }

        // 获取占位符段落的XML游标，用于指定内容插入位置
        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

        // 为每个子桥生成章节
        int chapterNum = 3;
        int subChapterNum = 1;

        AtomicInteger chapter3ImageCounter = new AtomicInteger(1);
        AtomicInteger chapter3TableCounter = new AtomicInteger(1);

        // 直接在文档中指定位置生成内容
        for (Task task : tasks) {
            XmlCursor subCursor = cursor.newCursor();
            Building subBuilding = task.getBuilding();
            Long projectId = task.getProjectId();
            // 收集病害数据
            List<Disease> subBridgeDiseases;
            // 获取子桥的所有病害
            Disease queryParam = new Disease();
            queryParam.setBuildingId(subBuilding.getId());
            queryParam.setProjectId(projectId);
            subBridgeDiseases = diseaseMapper.selectDiseaseList(queryParam);

            BiObject subBridge = biObjectMap.get(subBuilding.getRootObjectId());
            // 为每个子桥收集病害
            Map<Long, List<Disease>> bridgeDiseaseMap = new LinkedHashMap<>();
            collectDiseases(subBridge, allObjects, subBridgeDiseases, 4, bridgeDiseaseMap);

            // 在指定位置生成内容
            String prefix = chapterNum + "." + subChapterNum;

            // 在游标位置生成内容 - 将baseHeadingLevel设为3，使其成为第三章的子章节
            writeBiObjectTreeToWord(document, subBridge, allObjects,
                    bridgeDiseaseMap, prefix, 1, chapter3ImageCounter, chapter3TableCounter, subCursor, 2, false);

            subChapterNum++;
        }

        // 为每个子桥生成病害对比表格
        generateDiseaseComparisonTables(document, biObjectMap, tasks, cursor, chapterNum, subChapterNum, chapter3TableCounter);

        // 删除占位符段落
        placeholderParagraph.removeRun(0); // 清除占位符文本
        if (placeholderParagraph.getRuns().size() == 0) {
            // 如果段落为空，找到它的索引并删除
            for (int i = 0; i < document.getParagraphs().size(); i++) {
                if (document.getParagraphs().get(i) == placeholderParagraph) {
                    document.removeBodyElement(i);
                    break;
                }
            }
        }
    }

    /**
     * 处理第三章外观检测结果（测试模板版本）
     * 支持可配置的标题级别和起始子章节号，适用于不同模板的目录结构
     * <p>
     * 标题级别说明（baseHeadingLevel 控制子桥标题级别 = baseHeadingLevel + 1）：
     * - baseHeadingLevel = 1：子桥 Heading 2、结构 Heading 3、构件 Heading 4（与"外观检测结果"同级）
     * - baseHeadingLevel = 2：子桥 Heading 3、结构 Heading 4、构件 Heading 5（"外观检测结果"的下一级）
     *
     * @param document           Word文档
     * @param tasks              建筑物信息
     * @param rootParentId       根父对象ID
     * @param baseHeadingLevel   基础标题级别
     * @param startSubChapterNum 起始子章节号（如 1 表示从 3.2.1 开始编号）
     * @throws Exception 异常
     */
    private void processChapter3ForTestTemplate(XWPFDocument document, List<Task> tasks, Long rootParentId,
                                                int baseHeadingLevel, int startSubChapterNum) throws Exception {

        // 获取所有子部件
        List<BiObject> allObjects = biObjectMapper.selectChildrenById(rootParentId);

        List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(
                tasks.stream()
                        .filter(t -> Objects.nonNull(t) && Objects.nonNull(t.getBuilding()) && Objects.nonNull(t.getBuilding().getRootObjectId()))
                        .map(t -> t.getBuilding().getRootObjectId())
                        .distinct()
                        .collect(Collectors.toList())
        );

        Map<Long, BiObject> biObjectMap = biObjects.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(BiObject::getId, Function.identity(), (a, b) -> a));

        // 找到占位符所在的段落
        XWPFParagraph placeholderParagraph = null;

        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            String paragraphText = paragraph.getText();
            if (paragraphText.contains("${chapter-3-2-appearanceInspection}")
                    || paragraphText.contains("${chapter-3-2-appearanceInspectionResults}")) {
                placeholderParagraph = paragraph;
                break;
            }
        }

        // 如果找不到占位符，直接返回
        if (placeholderParagraph == null) {
            return;
        }

        // 获取占位符段落的XML游标，用于指定内容插入位置
        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

        // 为每个子桥生成章节
        int chapterNum = 3;
        int subChapterNum = startSubChapterNum;

        AtomicInteger chapter3ImageCounter = new AtomicInteger(1);
        AtomicInteger chapter3TableCounter = new AtomicInteger(1);

        // 直接在文档中指定位置生成内容
        for (Task task : tasks) {
            XmlCursor subCursor = cursor.newCursor();
            Building subBuilding = task.getBuilding();
            Long projectId = task.getProjectId();
            // 收集病害数据
            List<Disease> subBridgeDiseases;
            // 获取子桥的所有病害
            Disease queryParam = new Disease();
            queryParam.setBuildingId(subBuilding.getId());
            queryParam.setProjectId(projectId);
            subBridgeDiseases = diseaseMapper.selectDiseaseList(queryParam);

            BiObject subBridge = biObjectMap.get(subBuilding.getRootObjectId());
            // 为每个子桥收集病害
            Map<Long, List<Disease>> bridgeDiseaseMap = new LinkedHashMap<>();
            collectDiseases(subBridge, allObjects, subBridgeDiseases, 4, bridgeDiseaseMap);

            // 在指定位置生成内容
            String prefix = chapterNum + "." + subChapterNum;

            // 使用传入的 baseHeadingLevel 控制标题级别
            writeBiObjectTreeToWord(document, subBridge, allObjects,
                    bridgeDiseaseMap, prefix, 1, chapter3ImageCounter, chapter3TableCounter, subCursor, baseHeadingLevel, true);

            subChapterNum++;
        }

        // 删除占位符段落
        placeholderParagraph.removeRun(0); // 清除占位符文本
        if (placeholderParagraph.getRuns().size() == 0) {
            // 如果段落为空，找到它的索引并删除
            for (int i = 0; i < document.getParagraphs().size(); i++) {
                if (document.getParagraphs().get(i) == placeholderParagraph) {
                    document.removeBodyElement(i);
                    break;
                }
            }
        }
    }

    /**
     * 处理"与上一次检查病害变化情况分析"章节（测试模板独立版本）
     * 使用独立占位符 ${chapter-3-3-diseaseComparison}，可单独放置在模板中
     *
     * @param document         Word文档
     * @param tasks            任务列表
     * @param baseHeadingLevel 标题级别（如 2 表示 Heading 2）
     * @param startSubChapterNum 起始子章节号（如 1 表示从 3.3.1 开始编号）
     */
    private void processDiseaseComparisonForTestTemplate(XWPFDocument document, List<Task> tasks,
                                                         int baseHeadingLevel, int startSubChapterNum) {
        try {
            // 找到占位符所在的段落
            XWPFParagraph placeholderParagraph = null;

            for (int i = 0; i < document.getParagraphs().size(); i++) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);
                if (paragraph.getText().contains("${chapter-3-3-diseaseComparison}")) {
                    placeholderParagraph = paragraph;
                    break;
                }
            }

            // 如果找不到占位符，直接返回
            if (placeholderParagraph == null) {
                return;
            }

            // 获取占位符段落的XML游标
            XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

            // 获取子桥BiObject映射
            List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(
                    tasks.stream()
                            .filter(t -> Objects.nonNull(t) && Objects.nonNull(t.getBuilding()) && Objects.nonNull(t.getBuilding().getRootObjectId()))
                            .map(t -> t.getBuilding().getRootObjectId())
                            .distinct()
                            .collect(Collectors.toList())
            );

            Map<Long, BiObject> biObjectMap = biObjects.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(BiObject::getId, Function.identity(), (a, b) -> a));

            int chapterNum = 3;
            int currentSubChapterNum = startSubChapterNum;
            AtomicInteger tableCounter = new AtomicInteger(1);

            // 为每个task生成病害对比表格
            for (Task task : tasks) {
                BiObject subBridge = biObjectMap.get(task.getBuilding().getRootObjectId());
                Long projectId = task.getProjectId();
                Building building = task.getBuilding();

                // 生成病害对比数据
                List<DiseaseComparisonData> comparisonData = diseaseComparisonService.generateComparisonData(subBridge, projectId, building.getId());

                if (!comparisonData.isEmpty()) {
                    // 为每个桥单独生成横向表格
                    generateSingleDiseaseComparisonTable(document, placeholderParagraph, comparisonData,
                            tableCounter, chapterNum, currentSubChapterNum, subBridge.getName());

                    currentSubChapterNum++;
                    log.info("已生成病害对比表格: {}", subBridge.getName());
                }
            }

            // 删除占位符段落
            placeholderParagraph.removeRun(0);
            if (placeholderParagraph.getRuns().size() == 0) {
                for (int i = 0; i < document.getParagraphs().size(); i++) {
                    if (document.getParagraphs().get(i) == placeholderParagraph) {
                        document.removeBodyElement(i);
                        break;
                    }
                }
            }

        } catch (Exception e) {
            log.error("处理与上一次检查病害变化情况分析出错", e);
        }
    }

    /**
     * 收集病害数据，参考ReportControllertest.java中的collectDiseases方法
     */
    private void collectDiseases(BiObject node, List<BiObject> allNodes, List<Disease> properties,
                                 int level, Map<Long, List<Disease>> map) {
        // 找到当前节点所属的 level3 祖先（如自身就是 level3 则返回自身）
        BiObject level3 = findLevel3Ancestor(node, allNodes, level);

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
            collectDiseases(child, allNodes, properties, level, map);
        }
    }

    /**
     * 找到第三层祖先节点
     */
    private BiObject findLevel3Ancestor(BiObject node, List<BiObject> allNodes, int level) {
        BiObject cur = node;
        int lv = getLevel(cur, allNodes);
        while (lv > level && cur.getParentId() != null) {
            BiObject finalCur = cur;
            cur = allNodes.stream()
                    .filter(o -> o.getId().equals(finalCur.getParentId()))
                    .findFirst()
                    .orElse(null);
            lv--;
        }
        return cur;
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
                                         XmlCursor cursor, int baseHeadingLevel, boolean resolveHeadingStyleByName) throws Exception {
        if (level > 3) {
            return; // 不再写标题，也不再递归写标题
        }

        // 写目录标题，根据是否有游标决定在哪里创建段落
        XWPFParagraph p;
        if (cursor != null) {
            p = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            p = document.createParagraph();
        }
        int actualHeadingLevel = baseHeadingLevel + level;
        if (resolveHeadingStyleByName) {
            setHeadingStyleByLevel(document, p, actualHeadingLevel);
        } else {
            p.setStyle(String.valueOf(Math.min(actualHeadingLevel, 9)));
        }

        // 设置段落左对齐
        p.setAlignment(ParagraphAlignment.LEFT);

        // 设置缩进 - 根据需要选择一种方式
        CTPPr ppr = p.getCTP().getPPr();
        if (ppr == null) ppr = p.getCTP().addNewPPr();
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();

        ind.setFirstLine(BigInteger.valueOf(0));
        ind.setLeft(BigInteger.valueOf(0));

        // 创建标题运行
        XWPFRun run = p.createRun();
        run.setText(node.getName());
        run.setBold(false);
        run.setFontFamily("黑体");

        // 写病害信息
        List<Disease> nodeDiseases = diseaseMap.getOrDefault(node.getId(), List.of());

        // 提前收集所有病害的图片书签信息
        Map<Long, List<String>> diseaseImageRefs = new HashMap<>(); // key: 病害ID, value: 图片书签列表

        for (Disease d : nodeDiseases) {
            List<String> imageBookmarks = new ArrayList<>();
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(d.getId());
            if (images != null) {
                for (Map<String, Object> img : images) {
                    if (Boolean.TRUE.equals(img.get("isImage"))) {
                        // 为每张病害图片创建书签，暂时先创建占位符
                        // 实际的图片标题域将在插入图片时创建
                        String componentName = "";
                        Component component = componentService.selectComponentById(d.getComponentId());
                        if (component != null) {
                            componentName = component.getName();
                        }
                        // 照片 图注 的 描述 不需要带病害规范号。
                        String type = d.getType().substring(d.getType().lastIndexOf("#") + 1);
                        String imageDesc = componentName + d.getPosition() + type;
                        // 使用第3章编号规则
                        String bookmark = "Figure_Chapter3_" + chapterImageCounter.getAndIncrement();
                        imageBookmarks.add(bookmark + "|" + imageDesc); // 书签名和描述用|分隔
                    }
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

            // Part 1: 加粗的开头部分
            XWPFRun runBold = introPara.createRun();
            runBold.setText("经检查，" + node.getName() + " 主要病害为：");
            runBold.setBold(true);
            runBold.setFontSize(12); // 设置字号与后面一致

            // Part 2: 生成病害小结

            log.info("开始生成病害小结");
            String diseaseString = "";
            try {
                diseaseString = getDiseaseSummary(nodeDiseases, node.getName());
                diseaseString = normalizeDiseaseSummary(diseaseString, node.getName());
            } catch (Exception e) {
                log.error("ai小结病害失败，使用原始病害拼接兜底，生成报告继续。", e);
            }
            if (diseaseString == null || diseaseString.trim().isEmpty()) {
                diseaseString = ReportTemplateValueUtils.buildDiseaseSummary(nodeDiseases);
            }


//             缓存病害汇总到第十章服务，供后续复用
            testConclusionService.cacheDiseaseSummary(node.getId(), diseaseString);

            log.info("生成结束");

            // 按行分割字符串并创建多个段落
            String[] lines = diseaseString.split("\\r?\\n");
            for (String line : lines) {
                // 创建新的段落并设置首行缩进
                XWPFParagraph diseasePara;
                if (cursor != null) {
                    diseasePara = document.insertNewParagraph(cursor);
                    cursor.toNextToken();
                } else {
                    diseasePara = document.createParagraph();
                }
                CTPPr diseasePpr = diseasePara.getCTP().getPPr();
                if (diseasePpr == null) {
                    diseasePpr = diseasePara.getCTP().addNewPPr();
                }
                ind = diseasePpr.isSetInd() ? diseasePpr.getInd() : diseasePpr.addNewInd();
                ind.setFirstLine(BigInteger.valueOf(480));

                XWPFRun runItem = diseasePara.createRun();
                runItem.setText(line);
                runItem.setFontSize(12);
            }

            // Part 3: 表格引用部分
            XWPFParagraph tableRefPara;
            if (cursor != null) {
                tableRefPara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                tableRefPara = document.createParagraph();
            }

            // 设置1.5倍行距
            CTPPr ppr1 = tableRefPara.getCTP().getPPr();
            if (ppr1 == null) {
                ppr1 = tableRefPara.getCTP().addNewPPr();
            }
            CTSpacing spacing = ppr1.isSetSpacing() ? ppr1.getSpacing() : ppr1.addNewSpacing();
            spacing.setLine(BigInteger.valueOf(360));

            // 添加表格标题，获取书签名用于引用
            String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(document, node.getName() + "检测结果表", cursor, 3, chapter3TableCounter);

            // 创建章节格式的表格引用域
            WordFieldUtils.createChapterTableReference(tableRefPara, tableBookmark, "具体检测结果见下表", "：");

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

            // 设置表格宽度为页面宽度（关键修改）
            CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
            tblWidth.setW(BigInteger.valueOf(9534));
            tblWidth.setType(STTblWidth.DXA);

            // 设置表头
            XWPFTableRow headerRow = table.getRow(0);

            // 表头文本数组
            String[] headers = {"序号", "缺损位置", "缺损类型", "数量", "病害描述", "评定类别 (1~5)", "发展趋势", "照片"};

            // 修改列宽比例，确保总和不超过页面宽度
            Double[] columnWidthRatios = {0.08, 0.18, 0.14, 0.08, 0.26, 0.10, 0.08, 0.08};
            int totalWidth = 9534;

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
                setSingleLineSpacing(paragraph);  // 设置单倍行距
                paragraph.setAlignment(ParagraphAlignment.CENTER);

                XWPFRun run1 = paragraph.createRun();
                run1.setText(headers[i]);
                run1.setBold(true);

                // 设置中英文字体和字号
                setMixedFontFamily(run1, 21);  // 10.5pt

                // 设置列宽
                CTTc cttc = cell.getCTTc();
                CTTcPr tcPr = cttc.isSetTcPr() ? cttc.getTcPr() : cttc.addNewTcPr();

                if (tcPr.isSetTcW()) {
                    tcPr.unsetTcW(); // 关键：去掉默认宽度
                }

                // 计算每列的实际宽度
                int columnWidth = (int) Math.round(totalWidth * columnWidthRatios[i]);
                CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                tcW.setW(BigInteger.valueOf(columnWidth));
                tcW.setType(STTblWidth.DXA);

                // 防止内容换行（可选）
                tcPr.addNewNoWrap();
                setCellVerticalCenter(cell);
            }

            // 设置标题行在跨页时重复显示
            setTableHeaderRepeat(headerRow);

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
                    int columnWidth = (int) Math.round(totalWidth * columnWidthRatios[i]);
                    CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                    tcW.setW(BigInteger.valueOf(columnWidth));
                    tcW.setType(STTblWidth.DXA);
                    setCellVerticalCenter(cell);

                    // 设置单元格内容居中
                    XWPFParagraph cellP = cell.getParagraphs().get(0);
                    setSingleLineSpacing(cellP);  // 设置单倍行距
                    cellP.setAlignment(ParagraphAlignment.CENTER);

                    // 设置文本内容
                    XWPFRun cellR = cellP.createRun();

                    // 设置中英文字体和字号
                    setMixedFontFamily(cellR, 21);  // 10.5pt
                    Component component = componentService.selectComponentById(d.getComponentId());
                    switch (i) {
                        case 0:
                            cellR.setText(String.valueOf(seqNum++));
                            break;
                        case 1:
                            cellR.setText(component != null ? component.getName() : "/");
                            break;
                        case 2:
                            cellR.setText(ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) != null ? ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) : "/");
                            break;
                        case 3:
                            cellR.setText(d.getQuantity() > 0 ? String.valueOf(d.getQuantity()) : "/");
                            break;
                        case 4:
                            cellR.setText(ReportGenerateTools.formatAppearanceDiseaseDescription(d.getDescription()));
                            break;
                        case 5:
                            cellR.setText(!"0".equals(d.getParticipateAssess()) && d.getLevel() > 0
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
                                        setMixedFontFamily(commaRun, 21);  // 10.5pt
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
            // 没有病害信息，显示未见明显病害的说明
            XWPFParagraph noDiseasePara;
            if (cursor != null) {
                noDiseasePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                noDiseasePara = document.createParagraph();
            }

            // 设置1.5倍行距
            CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
            spacing.setLine(BigInteger.valueOf(360)); // 1.5倍行距

            // 添加文本内容
            XWPFRun noDiseaseRun = noDiseasePara.createRun();
            noDiseaseRun.setText("经检查，" + node.getName() + "未见明显病害。");
            noDiseaseRun.setFontSize(12);

            // 插入当前结构的现状照片
            try {
                insertBiObjectStatusImages(document, node, chapterImageCounter, cursor);
            } catch (Exception e) {
                log.error("插入BiObject现状照片失败", e);
            }
        }

        // 递归写子节点
        List<BiObject> children = ReportTemplateValueUtils.sortedReportChildren(allNodes, node.getId());

        int idx = 1;
        for (BiObject child : children) {
            writeBiObjectTreeToWord(document, child, allNodes, diseaseMap, prefix + "." + idx, level + 1, chapterImageCounter, chapter3TableCounter, cursor, baseHeadingLevel, resolveHeadingStyleByName);
            idx++;
        }
    }

    private String normalizeDiseaseSummary(String diseaseSummary, String nodeName) {
        if (diseaseSummary == null) {
            return "";
        }

        String[] lines = diseaseSummary.replace("\r\n", "\n").replace("\r", "\n").split("\\n+");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String cleanedLine = line.trim().replaceFirst("^\\d+[）).、，,]\\s*", "");
            if (cleanedLine.isEmpty()) {
                continue;
            }
            builder.append(cleanedLine);
        }
        String normalized = builder.toString();

        if (nodeName != null) {
            String[] prefixes = {
                    nodeName + "：",
                    nodeName + ":",
                    nodeName + "，",
                    nodeName + ","
            };
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    break;
                }
            }
        }

        return normalized.trim();
    }

    /**
     * 根据当前模板中的样式名称解析真实 styleId，避免不同 Word 模板中 Heading 样式 ID 不一致。
     */
    private void setHeadingStyleByLevel(XWPFDocument document, XWPFParagraph paragraph, int headingLevel) {
        int normalizedLevel = Math.max(1, Math.min(headingLevel, 9));
        String fallbackStyleId = String.valueOf(normalizedLevel);
        String resolvedStyleId = null;

        XWPFStyles styles = document.getStyles();
        if (styles != null) {
            XWPFStyle style = findHeadingStyle(styles, normalizedLevel);
            if (style != null) {
                resolvedStyleId = style.getStyleId();
            }
        }

        paragraph.setStyle(resolvedStyleId != null && !resolvedStyleId.trim().isEmpty()
                ? resolvedStyleId
                : fallbackStyleId);
    }

    private XWPFStyle findHeadingStyle(XWPFStyles styles, int headingLevel) {
        List<String> headingNames = Arrays.asList(
                "Heading " + headingLevel,
                "heading " + headingLevel,
                "标题 " + headingLevel,
                "标题" + headingLevel
        );
        for (String headingName : headingNames) {
            XWPFStyle style = styles.getStyleWithName(headingName);
            if (style != null) {
                return style;
            }
        }
        return null;
    }

    /**
     * 针对单桥模板的病害树写入方法
     * 从第二层节点（如上部结构）开始，收集该层下所有病害，生成表格和图片，最后插入现状照片
     *
     * @param document             Word文档
     * @param node                 第二层BiObject节点（如上部结构）
     * @param allNodes             所有BiObject节点列表
     * @param diseaseMap           病害映射表
     * @param level                章节前缀
     * @param chapterImageCounter  图片计数器
     * @param chapter3TableCounter 表格计数器
     * @param cursor               XML游标
     * @param baseHeadingLevel     基础标题级别
     * @throws Exception 异常
     */
    private void writeBiObjectTreeToWordForSingleBridge(XWPFDocument document, BiObject node, List<BiObject> allNodes,
                                                        Map<Long, List<Disease>> diseaseMap, int level,
                                                        AtomicInteger chapterImageCounter, AtomicInteger chapter3TableCounter,
                                                        XmlCursor cursor, int baseHeadingLevel,
                                                        boolean omitArchUpperBearing) throws Exception {
        // 转换为 Map<id, BiObject>
        Map<Long, BiObject> nodeMap = allNodes.stream()
                .collect(Collectors.toMap(
                        BiObject::getId,
                        obj -> obj
                ));
        // 写标题
        XWPFParagraph p;
        if (cursor != null) {
            p = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            p = document.createParagraph();
        }

        // 设置标题样式
        int actualHeadingLevel = baseHeadingLevel + level;
        String headingStyle = String.valueOf(Math.min(actualHeadingLevel, 9));
        p.setStyle(headingStyle);
        p.setAlignment(ParagraphAlignment.LEFT);

        // 设置段落缩进
        CTPPr ppr = p.getCTP().getPPr();
        if (ppr == null) ppr = p.getCTP().addNewPPr();
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        ind.setFirstLine(BigInteger.valueOf(0));
        ind.setLeft(BigInteger.valueOf(0));

        // 创建标题运行。上部结构这一级与多桥外观检测的下一级标题一致：黑体四号、不加粗。
        XWPFRun run = p.createRun();
        run.setText(node.getName());
        applyLevel2AppearanceHeadingFormat(p);

        // 获取当前节点的所有第三层子节点（部件）
        List<BiObject> childComponents = allNodes.stream()
                .filter(obj -> node.getId().equals(obj.getParentId()))
                .sorted(Comparator.comparing(BiObject::getOrderNum, Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList());

        Set<Long> omittedSubtreeIds = omittedArchUpperBearingIds(node, childComponents, allNodes, omitArchUpperBearing);
        if (!omittedSubtreeIds.isEmpty()) {
            childComponents.removeIf(component -> omittedSubtreeIds.contains(component.getId()));
        }

        // 收集该节点下所有子节点的病害。已维修、未找到不进小结和检测结果表。
        List<Disease> allNodeDiseases = new ArrayList<>();
        collectAllDiseases(node, allNodes, diseaseMap, allNodeDiseases);
        allNodeDiseases.removeIf(ReportServiceImpl::isExcludedFromInspectionResultTable);
        if (!omittedSubtreeIds.isEmpty()) {
            allNodeDiseases.removeIf(disease -> disease.getBiObjectId() != null
                    && omittedSubtreeIds.contains(disease.getBiObjectId()));
        }

        // 提前收集所有病害的图片书签信息
        Map<Long, List<String>> diseaseImageRefs = new HashMap<>(); // key: 病害ID, value: 图片书签列表

        // 1. 查询所有组件
        List<Long> componentIds = allNodeDiseases.stream()
                .map(Disease::getComponentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        List<Component> components = componentService.selectComponentsByIds(componentIds);

        Map<Long, Component> componentMap = components.stream()
                .collect(Collectors.toMap(Component::getId, c -> c));

        Map<Long, Integer> biObjectOrderIndex = new HashMap<>();
        for (int i = 0; i < childComponents.size(); i++) {
            biObjectOrderIndex.put(childComponents.get(i).getId(), i);
        }

        allNodeDiseases.sort((d1, d2) -> {

            Long b1 = nodeMap.get(d1.getBiObjectId()).getParentId();
            Long b2 = nodeMap.get(d2.getBiObjectId()).getParentId();

            // -----------------------
            // (1) 第一优先级：按 BiObject 顺序排
            // -----------------------
            Integer bo1 = biObjectOrderIndex.getOrDefault(b1, Integer.MAX_VALUE);
            Integer bo2 = biObjectOrderIndex.getOrDefault(b2, Integer.MAX_VALUE);

            if (!bo1.equals(bo2)) {
                return bo1 - bo2;
            }

            // -----------------------
            // (2) 第二优先级：同 BiObject 下，按 Component.code 排
            // -----------------------
            Component c1 = componentMap.get(d1.getComponentId());
            Component c2 = componentMap.get(d2.getComponentId());

            String code1 = (c1 != null ? c1.getCode() : "");
            String code2 = (c2 != null ? c2.getCode() : "");

            return compareCodes(code1, code2);
        });


        for (Disease d : allNodeDiseases) {
            List<String> imageBookmarks = new ArrayList<>();
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(d.getId());
            if (images != null) {
                for (Map<String, Object> img : images) {
                    if (Boolean.TRUE.equals(img.get("isImage"))) {
                        // 为每张病害图片创建书签
                        String componentName = "";
                        Component component = componentService.selectComponentById(d.getComponentId());
                        if (component != null) {
                            componentName = component.getName();
                        }
                        String imageDesc = ReportGenerateTools.diseasePhotoCaption(d, componentName);
                        // 使用第3章编号规则
                        String bookmark = "Figure_Chapter3_" + UUID.randomUUID() + "_" + chapterImageCounter.getAndIncrement();
                        imageBookmarks.add(bookmark + "|" + imageDesc); // 书签名和描述用|分隔
                    }
                }
            }
            diseaseImageRefs.put(d.getId(), imageBookmarks);
        }

        // 结构下按 1) 2) 列出部件，正文格式与多桥外观检测一致。
        insertLevel2AppearanceBody(document, cursor, "经检查");

        log.info("开始按部件生成病害小结");
        int componentIndex = 1;
        for (BiObject component : childComponents) {
            if ("其他".equals(component.getName())) {
                continue;
            }
            List<Disease> componentDiseases = new ArrayList<>();
            collectAllDiseases(component, allNodes, diseaseMap, componentDiseases);
            componentDiseases.removeIf(ReportServiceImpl::isExcludedFromInspectionResultTable);

            if (component.getWeight() != null && component.getWeight().compareTo(BigDecimal.ZERO) == 0) {
                insertLevel2AppearanceBody(document, cursor,
                        formatLevel2ComponentLead(componentIndex, component.getName(), "无此构件"));
                testConclusionService.cacheDiseaseSummary(component.getId(), TestConclusionService.NO_SUCH_COMPONENT_TEXT);
                componentIndex++;
                continue;
            }

            if (componentDiseases.isEmpty()) {
                insertLevel2AppearanceBody(document, cursor,
                        formatLevel2ComponentLead(componentIndex, component.getName(), "未见明显病害。"));
            } else {
                String componentDiseaseSummary = stripComponentTypeColon(
                        programmaticDiseaseSummaryService.summarize(componentDiseases, component.getName()));
                writeLevel2ComponentSummary(document, cursor, componentIndex, component.getName(), componentDiseaseSummary);
                testConclusionService.cacheDiseaseSummary(component.getId(), componentDiseaseSummary);
            }

            componentIndex++;
        }

        log.info("按部件生成病害小结结束");

        // 如果存在病害信息，则生成表格。题注紧挨病害总结，段前段后为 0。
        if (!allNodeDiseases.isEmpty()) {
            WordFieldUtils.createTableCaptionWithCounter(
                    document, node.getName() + "检查汇总表", cursor, 4, chapter3TableCounter,
                    22, 240, false, 0, 0, "12");

            // 创建表格
            XWPFTable table;
            if (cursor != null) {
                table = document.insertNewTbl(cursor);
                cursor.toNextToken();
                // 初始化表格结构
                for (int i = 0; i < 7; i++) {
                    if (i == 0) {
                        table.getRow(0).getCell(i);
                    } else {
                        table.getRow(0).addNewTableCell();
                    }
                }
            } else {
                table = document.createTable(1, 7);
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

            // 1 cm = 567 twips。去掉发展趋势后 7 列合计 17.49 cm。
            int[] columnWidths = {
                    612,   // 序号 1.08 cm
                    1072,  // 缺损位置 1.89 cm
                    1225,  // 缺损类型 2.16 cm
                    765,   // 数量 1.35 cm
                    3975,  // 病害描述 7.01 cm
                    1111,  // 评定类别 1.96 cm
                    1157   // 照片 2.04 cm
            };
            int totalWidth = 0;
            for (int columnWidth : columnWidths) {
                totalWidth += columnWidth;
            }
            CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
            tblWidth.setW(BigInteger.valueOf(totalWidth));
            tblWidth.setType(STTblWidth.DXA);

            // 设置表头
            XWPFTableRow headerRow = table.getRow(0);

            String[] headers = {"序号", "缺损位置", "缺损类型", "数量", "病害描述", "评定类别 (1~5)", "照片"};

            CTTblLayoutType tblLayout = tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout();
            tblLayout.setType(STTblLayoutType.FIXED);

            // 设置每列
            for (int i = 0; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.getCell(i);

                // 清除内容
                for (int j = cell.getParagraphs().size() - 1; j >= 0; j--) {
                    cell.removeParagraph(j);
                }

                // 设置文本样式
                XWPFParagraph paragraph = cell.addParagraph();
                setSingleLineSpacing(paragraph);
                paragraph.setAlignment(ParagraphAlignment.CENTER);

                XWPFRun run1 = paragraph.createRun();
                run1.setText(headers[i]);
                run1.setBold(true);

                // 设置中英文字体和字号
                setMixedFontFamily(run1, 21);

                // 设置列宽
                CTTc cttc = cell.getCTTc();
                CTTcPr tcPr = cttc.isSetTcPr() ? cttc.getTcPr() : cttc.addNewTcPr();

                if (tcPr.isSetTcW()) {
                    tcPr.unsetTcW();
                }

                // 计算每列的实际宽度
                int columnWidth = columnWidths[i];
                CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                tcW.setW(BigInteger.valueOf(columnWidth));
                tcW.setType(STTblWidth.DXA);

                // 防止内容换行
                tcPr.addNewNoWrap();

                // 垂直居中 11.11 修改
                if (!tcPr.isSetVAlign()) {
                    tcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
                }
            }

            // 设置标题行在跨页时重复显示
            setTableHeaderRepeat(headerRow);

            // 填充数据行
            int seqNum = 1;
            for (Disease d : allNodeDiseases) {
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
                    setSingleLineSpacing(cellP);
                    cellP.setAlignment(ParagraphAlignment.CENTER);

                    // 垂直居中 11.11修改。
                    CTTcPr curTcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr()
                            : cell.getCTTc().addNewTcPr();
                    if (!curTcPr.isSetVAlign()) {
                        curTcPr.addNewVAlign().setVal(STVerticalJc.CENTER);
                    }
                    // 设置文本内容
                    XWPFRun cellR = cellP.createRun();

                    // 设置中英文字体和字号
                    setMixedFontFamily(cellR, 21);
                    switch (i) {
                        case 0:
                            cellR.setText(String.valueOf(seqNum++));
                            break;
                        case 1:
                            cellR.setText(componentMap.get(d.getComponentId()).getName());
                            break;
                        case 2:
                            cellR.setText(ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) != null ? ReportGenerateTools.reportDiseaseTypeNameIfCrack(d) : "/");
                            break;
                        case 3:
                            cellR.setText(d.getQuantity() > 0 ? String.valueOf(d.getQuantity()) : "/");
                            break;
                        case 4:
                            if (!cellP.getRuns().isEmpty()) {
                                cellP.removeRun(cellP.getRuns().size() - 1);
                            }
                            ReportGenerateTools.writeTextWithAreaSubscripts(cellP,
                                    ReportGenerateTools.formatAppearanceDiseaseDescription(d.getDescription()), 21);
                            break;
                        case 5:
                            cellR.setText(!"0".equals(d.getParticipateAssess()) && d.getLevel() > 0
                                    ? String.valueOf(d.getLevel()) : "/");
                            break;
                        case 6:
                            // 获取该病害对应的所有图片书签信息
                            List<String> refs = diseaseImageRefs.getOrDefault(d.getId(), new ArrayList<>());
                            // 从书签信息中提取书签名，创建图片引用域
                            if (!refs.isEmpty()) {
                                // 清除现有内容
                                cellP.removeRun(cellP.getRuns().size() - 1);
                                WordFieldUtils.createChapterFigureReferenceRange(cellP, refs);
                            } else {
                                cellR.setText("/");
                            }
                            break;
                    }
                }
            }

            // 病害照和现状照合成一张表：现状照先补空位，只在最后一行剩一张时居中。
            List<Pair<String, String>> photos = new ArrayList<>();
            if (!allNodeDiseases.isEmpty()) {
                photos.addAll(collectDiseaseImagePairs(allNodeDiseases, diseaseImageRefs));
            }
            photos.addAll(collectStatusImagePairs(node, allNodes, chapterImageCounter, omittedSubtreeIds));
            if (!photos.isEmpty()) {
                insertPhotoImageTable(document, photos, cursor, true);
            }
        } else {
            try {
                insertPhotoImageTable(document,
                        collectStatusImagePairs(node, allNodes, chapterImageCounter, omittedSubtreeIds),
                        cursor, true);
            } catch (Exception e) {
                log.error("插入BiObject现状照片失败", e);
            }
        }
    }

    /**
     * 二级单桥外观检测：部件用「1）上部承重构件：T梁梁底掉角…」这种编号。
     * 右括号宋体；部件名后保留冒号；T梁、湿接缝这类结构名后不再跟冒号和空格。
     * 小结内容来自程序汇总；多行时第一行挂在编号后，其余行单独成段。
     */
    private void writeLevel2ComponentSummary(XWPFDocument document, XmlCursor cursor, int index,
                                             String componentName, String summary) {
        List<String> lines = new ArrayList<>();
        if (summary != null) {
            for (String line : summary.split("\\r?\\n")) {
                if (line != null && !line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add("未见明显病害。");
        }
        if (lines.size() == 1) {
            insertLevel2AppearanceBody(document, cursor,
                    formatLevel2ComponentLead(index, componentName,
                            stripLeadingComponentName(lines.get(0), componentName)));
            return;
        }
        String firstLine = stripLeadingComponentName(lines.get(0).replaceFirst("^\\d+[）)]\\s*", ""), componentName);
        insertLevel2AppearanceBody(document, cursor, formatLevel2ComponentLead(index, componentName, firstLine));
        for (int i = 1; i < lines.size(); i++) {
            insertLevel2AppearanceBody(document, cursor, lines.get(i));
        }
    }

    /** 「1）上部承重构件：T梁梁底掉角…」，右括号用中文全角以便走宋体。 */
    private String formatLevel2ComponentLead(int index, String componentName, String rest) {
        String lead = index + "）" + (componentName == null ? "" : componentName) + "：";
        if (rest == null || rest.isBlank()) {
            return lead;
        }
        return lead + rest.trim();
    }

    private static final Pattern COMPONENT_TYPE_COLON = Pattern.compile(
            "^(\\d+[）)]\\s*)?(.+?)\\s*[:：]\\s*(.*)$");

    /** 去掉「T梁：」「湿接缝：」这类结构名后的冒号和空格，前面「上部承重构件：」保留。 */
    private String stripComponentTypeColon(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = COMPONENT_TYPE_COLON.matcher(lines[i]);
            if (matcher.matches()) {
                String numbering = matcher.group(1) == null ? "" : matcher.group(1);
                lines[i] = numbering + matcher.group(2) + matcher.group(3);
            }
        }
        return String.join("\n", lines);
    }

    private String stripLeadingComponentName(String line, String componentName) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (componentName != null && trimmed.startsWith(componentName + "：")) {
            return trimmed.substring(componentName.length() + 1).trim();
        }
        if (componentName != null && trimmed.startsWith(componentName + ":")) {
            return trimmed.substring(componentName.length() + 1).trim();
        }
        return trimmed;
    }

    private void insertLevel2AppearanceBody(XWPFDocument document, XmlCursor cursor, String text) {
        XWPFParagraph paragraph;
        if (cursor != null) {
            paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            paragraph = document.createParagraph();
        }
        fillLevel2AppearanceRuns(paragraph, text == null ? "" : text);
        applyLevel2AppearanceBodyFormat(paragraph);
    }

    /** 「其他」病害「其中」之后的描述标红；S-1 写成 S 加下标 1。 */
    private void fillLevel2AppearanceRuns(XWPFParagraph paragraph, String text) {
        if (text.isEmpty()) {
            paragraph.createRun().setText("");
            return;
        }
        boolean[] red = markOtherDescriptionChars(text);
        Matcher matcher = ReportGenerateTools.AREA_SUBSCRIPT_LABEL.matcher(text);
        int pos = 0;
        Integer nextStart = null;
        Integer nextEnd = null;
        String nextDigits = null;
        if (matcher.find()) {
            nextStart = matcher.start();
            nextEnd = matcher.end();
            nextDigits = matcher.group(1);
        }
        while (pos < text.length()) {
            if (nextStart != null && nextStart == pos) {
                addLevel2AppearanceRun(paragraph, "S", red[pos], false);
                addLevel2AppearanceRun(paragraph, nextDigits, red[pos], true);
                pos = nextEnd;
                if (matcher.find()) {
                    nextStart = matcher.start();
                    nextEnd = matcher.end();
                    nextDigits = matcher.group(1);
                } else {
                    nextStart = null;
                }
                continue;
            }
            int limit = nextStart != null ? nextStart : text.length();
            boolean redFlag = red[pos];
            int end = pos + 1;
            while (end < limit && red[end] == redFlag) {
                end++;
            }
            addLevel2AppearanceRun(paragraph, text.substring(pos, end), redFlag, false);
            pos = end;
        }
    }

    /**
     * 「其他」病害：前面「其他（最大标度 x）n处，总面积…，其中」保持黑色，
     * 「其中」之后的原始描述全部标红，不因描述里的分号中断。
     */
    private boolean[] markOtherDescriptionChars(String text) {
        boolean[] red = new boolean[text.length()];
        int from = 0;
        while (from < text.length()) {
            int marker = text.indexOf("其中", from);
            if (marker < 0) {
                break;
            }
            int start = marker + "其中".length();
            int end = text.indexOf('。', start);
            if (end < 0) {
                end = text.length();
            }
            for (int i = start; i < end; i++) {
                red[i] = true;
            }
            from = end;
        }
        return red;
    }

    private void addLevel2AppearanceRun(XWPFParagraph paragraph, String text, boolean red, boolean subscript) {
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        if (red) {
            run.setColor("FF0000");
        }
        if (subscript) {
            run.setVerticalAlignment("subscript");
        }
    }

    /**
     * 与多桥外观检测正文一致：宋体小四、数字 Times New Roman、1.5 倍行距、两端对齐、首行缩进 2 字符。
     */
    private void applyLevel2AppearanceBodyFormat(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return;
        }
        String style = paragraph.getStyle();
        if (style != null && style.trim().matches("[1-9]")) {
            paragraph.setStyle(null);
        }
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        if (ppr.isSetOutlineLvl()) {
            ppr.unsetOutlineLvl();
        }
        if (ppr.isSetNumPr()) {
            ppr.unsetNumPr();
        }
        CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(STJc.BOTH);
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        ind.setFirstLine(BigInteger.valueOf(480));
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        for (XWPFRun bodyRun : paragraph.getRuns()) {
            ReportGenerateTools.setMixedFontFamily(bodyRun, 24);
            bodyRun.setBold(false);
        }
    }

    /** 上部结构、下部结构、桥面系：黑体四号，数字 Times New Roman，不加粗。 */
    private void applyLevel2AppearanceHeadingFormat(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return;
        }
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        spacing.setBeforeLines(BigInteger.valueOf(50));
        spacing.setAfterLines(BigInteger.valueOf(50));
        for (XWPFRun headingRun : paragraph.getRuns()) {
            headingRun.setBold(false);
            ReportGenerateTools.setMixedFontFamily(headingRun, 28, "黑体");
        }
    }

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

    /** 已维修、未找到的病害不写入外观检测小结和检测结果表。 */
    private static boolean isExcludedFromInspectionResultTable(Disease disease) {
        if (disease == null || disease.getDevelopmentTrend() == null) {
            return false;
        }
        String trend = disease.getDevelopmentTrend().trim();
        return "已维修".equals(trend) || "未找到".equals(trend);
    }

    /**
     * 递归收集某个节点下所有子节点的病害
     *
     * @param node       当前节点
     * @param allNodes   所有节点列表
     * @param diseaseMap 病害映射表
     * @param resultList 结果列表（用于收集所有病害）
     */
    private void collectAllDiseases(BiObject node, List<BiObject> allNodes,
                                    Map<Long, List<Disease>> diseaseMap, List<Disease> resultList) {
        // 收集当前节点的病害
        List<Disease> nodeDiseases = diseaseMap.getOrDefault(node.getId(), List.of());
        resultList.addAll(nodeDiseases);

        // 递归收集子节点的病害
        List<BiObject> children = allNodes.stream()
                .filter(obj -> node.getId().equals(obj.getParentId()))
                .sorted(Comparator.comparing(BiObject::getOrderNum))
                .collect(Collectors.toList());

        for (BiObject child : children) {
            if ("其他".equals(child.getName())) {
                continue;
            }
            collectAllDiseases(child, allNodes, diseaseMap, resultList);
        }
    }

    /** 拱桥模板上部结构不列支座，连同其下病害和现状照一并略过。 */
    private Set<Long> omittedArchUpperBearingIds(BiObject structureNode, List<BiObject> childComponents,
                                                 List<BiObject> allNodes, boolean omitArchUpperBearing) {
        if (!omitArchUpperBearing || structureNode == null || structureNode.getName() == null
                || !structureNode.getName().contains("上部") || childComponents == null) {
            return Set.of();
        }
        Set<Long> omitted = new HashSet<>();
        for (BiObject child : childComponents) {
            if (child != null && "支座".equals(child.getName())) {
                collectSubtreeIds(child, allNodes, omitted);
            }
        }
        return omitted;
    }

    private void collectSubtreeIds(BiObject node, List<BiObject> allNodes, Set<Long> ids) {
        if (node == null || node.getId() == null || !ids.add(node.getId()) || allNodes == null) {
            return;
        }
        for (BiObject child : allNodes) {
            if (node.getId().equals(child.getParentId())) {
                collectSubtreeIds(child, allNodes, ids);
            }
        }
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
        insertBiObjectStatusImages(document, biObject, chapterImageCounter, cursor, false);
    }

    private void insertBiObjectStatusImages(XWPFDocument document, BiObject biObject,
                                            AtomicInteger chapterImageCounter, XmlCursor cursor,
                                            boolean fixedPhotoCell) throws Exception {
        insertBiObjectStatusImages(document, biObject, chapterImageCounter, cursor, fixedPhotoCell, null);
    }

    private void insertBiObjectStatusImages(XWPFDocument document, BiObject biObject,
                                            AtomicInteger chapterImageCounter, XmlCursor cursor,
                                            boolean fixedPhotoCell, List<BiObject> allNodes) throws Exception {
        insertPhotoImageTable(document, collectStatusImagePairs(biObject, allNodes, chapterImageCounter),
                cursor, fixedPhotoCell);
    }

    /** 把照片行两列合成一格，段落居中后单张照片位于行中间。 */
    private void spanPhotoRowFullWidth(XWPFTable table, int rowIndex, int widthTwips) {
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
        cellWidth.setW(BigInteger.valueOf(widthTwips));
        cellWidth.setType(STTblWidth.DXA);
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
        }
        row.removeCell(1);
    }

    private void setExactRowHeight(XWPFTableRow row, int heightTwips) {
        CTTrPr trPr = row.getCtRow().isSetTrPr() ? row.getCtRow().getTrPr() : row.getCtRow().addNewTrPr();
        CTHeight height = trPr.sizeOfTrHeightArray() > 0 ? trPr.getTrHeightArray(0) : trPr.addNewTrHeight();
        height.setVal(BigInteger.valueOf(heightTwips));
        height.setHRule(STHeightRule.EXACT);
    }

    private void clearCellMargin(CTTcPr tcPr) {
        CTTcMar mar = tcPr.isSetTcMar() ? tcPr.getTcMar() : tcPr.addNewTcMar();
        zeroMargin(mar.isSetTop() ? mar.getTop() : mar.addNewTop());
        zeroMargin(mar.isSetBottom() ? mar.getBottom() : mar.addNewBottom());
        zeroMargin(mar.isSetLeft() ? mar.getLeft() : mar.addNewLeft());
        zeroMargin(mar.isSetRight() ? mar.getRight() : mar.addNewRight());
    }

    private void zeroMargin(CTTblWidth margin) {
        margin.setW(BigInteger.ZERO);
        margin.setType(STTblWidth.DXA);
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
        insertDiseaseImagesWithStreaming(document, diseases, diseaseImageRefs, cursor, false);
    }

    private List<Pair<String, String>> collectDiseaseImagePairs(List<Disease> diseases,
                                                               Map<Long, List<String>> diseaseImageRefs) {
        List<Pair<String, String>> allImages = new ArrayList<>();
        if (diseases == null) {
            return allImages;
        }
        for (Disease d : diseases) {
            List<Map<String, Object>> images = ReportGenerateTools.getDiseaseImage(d.getId());
            if (images == null || images.isEmpty()) {
                continue;
            }
            List<String> imageRefs = diseaseImageRefs.getOrDefault(d.getId(), new ArrayList<>());
            int refIndex = 0;
            for (Map<String, Object> img : images) {
                if (!Boolean.TRUE.equals(img.get("isImage"))) {
                    continue;
                }
                String url = (String) img.get("url");
                String newName = url.substring(url.lastIndexOf("/") + 1);
                if (refIndex >= imageRefs.size()) {
                    continue;
                }
                String[] parts = imageRefs.get(refIndex).split("\\|");
                String bookmarkName = parts[0];
                String imageDesc = parts.length > 1 ? parts[1] : "";
                refIndex++;
                allImages.add(Pair.of(newName, bookmarkName + "|" + imageDesc));
            }
        }
        return allImages;
    }

    private List<Pair<String, String>> collectStatusImagePairs(BiObject biObject, AtomicInteger chapterImageCounter) {
        return collectStatusImagePairs(biObject, null, chapterImageCounter, null);
    }

    private List<Pair<String, String>> collectStatusImagePairs(BiObject biObject, List<BiObject> allNodes,
                                                               AtomicInteger chapterImageCounter) {
        return collectStatusImagePairs(biObject, allNodes, chapterImageCounter, null);
    }

    private List<Pair<String, String>> collectStatusImagePairs(BiObject biObject, List<BiObject> allNodes,
                                                               AtomicInteger chapterImageCounter,
                                                               Set<Long> skipSubjectIds) {
        List<Pair<String, String>> allImages = new ArrayList<>();
        if (biObject == null) {
            return allImages;
        }
        try {
            List<FileMap> photoList = fileMapService.selectBiObjectPhotoList(biObject.getId());
            if (photoList == null || photoList.isEmpty()) {
                return allImages;
            }
            Map<Long, BiObject> nodeMap = allNodes == null ? Collections.emptyMap()
                    : allNodes.stream()
                    .filter(obj -> obj.getId() != null)
                    .collect(Collectors.toMap(BiObject::getId, obj -> obj, (a, b) -> a));
            List<FileMap> photos = new ArrayList<>(photoList);
            photos.sort(Comparator
                    .comparingInt((FileMap fileMap) -> ReportGenerateTools.statusPhotoCaptionOrder(fileMap, biObject, nodeMap))
                    .thenComparingInt(fileMap -> fileMap.getId() == null ? 0 : fileMap.getId()));
            for (FileMap fileMap : photos) {
                if (skipSubjectIds != null && !skipSubjectIds.isEmpty()
                        && fileMap.getSubjectId() != null && skipSubjectIds.contains(fileMap.getSubjectId())) {
                    continue;
                }
                String newName = fileMap.getNewName();
                String bookmarkName = "fig_status_" + chapterImageCounter.getAndIncrement();
                String imageDesc = ReportGenerateTools.statusPhotoCaption(fileMap, biObject, nodeMap);
                allImages.add(Pair.of(newName, bookmarkName + "|" + imageDesc));
            }
        } catch (Exception e) {
            log.error("获取BiObject现状照片失败", e);
        }
        return allImages;
    }

    private void insertDiseaseImagesWithStreaming(XWPFDocument document, List<Disease> diseases,
                                                  Map<Long, List<String>> diseaseImageRefs, XmlCursor cursor,
                                                  boolean fixedPhotoCell) throws Exception {
        insertPhotoImageTable(document, collectDiseaseImagePairs(diseases, diseaseImageRefs), cursor, fixedPhotoCell);
    }

    private void insertPhotoImageTable(XWPFDocument document, List<Pair<String, String>> allImages,
                                       XmlCursor cursor, boolean fixedPhotoCell) throws Exception {
        if (allImages == null || allImages.isEmpty()) {
            return;
        }

        // 在表格前添加一个空段落，与上方内容分隔
        XWPFParagraph spacerBefore;
        if (cursor != null) {
            spacerBefore = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            spacerBefore = document.createParagraph();
        }
        spacerBefore.setSpacingAfter(300);
        spacerBefore.setSpacingBefore(300);

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

        // 单桥图片格宽 8.41 cm；组合桥仍铺满页宽。
        int photoCellWidth = fixedPhotoCell ? 4768 : 4767;
        int photoTableWidth = fixedPhotoCell ? photoCellWidth * 2 : 9534;
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(photoTableWidth));
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

        int cellWidth = photoCellWidth;

        // 设置所有单元格的样式
        for (int row = 0; row < totalRows; row++) {
            if (fixedPhotoCell) {
                // 偶数行是照片，高 6 cm；奇数行是图注，高 1.01 cm。
                setExactRowHeight(table.getRow(row), row % 2 == 0 ? 3402 : 573);
            }
            for (int col = 0; col < 2; col++) {
                XWPFTableCell cell = table.getRow(row).getCell(col);
                CTTc ctTc = cell.getCTTc();
                CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();

                // 设置单元格宽度
                CTTblWidth cellWidthObj = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cellWidthObj.setW(BigInteger.valueOf(cellWidth));
                cellWidthObj.setType(STTblWidth.DXA);
                if (fixedPhotoCell) {
                    clearCellMargin(tcPr);
                }

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
                setSingleLineSpacing(para);  // 设置单倍行距
                para.setAlignment(ParagraphAlignment.CENTER);
                if (fixedPhotoCell) {
                    para.setSpacingBefore(0);
                    para.setSpacingAfter(0);
                }
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
                    // 外观检测照片 8 cm × 6 cm；单桥表格格宽仍为 8.41 cm。
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
                titlePara.setStyle("12");
                titlePara.setAlignment(ParagraphAlignment.CENTER);

                // 清除现有内容
                while (titlePara.getRuns().size() > 0) {
                    titlePara.removeRun(0);
                }

                // 在现有段落中创建图片标题域，使用第3章编号和指定的书签名
                WordFieldUtils.createFigureCaptionInParagraph(titlePara, imageDesc, 3, bookmarkName);

                imageIndex++;
            }

            // 末行只有一张时，合并成通栏并居中，避免照片贴在左半边。
            if (imageIndex == allImages.size() && (imageIndex - 1) % 2 == 0) {
                spanPhotoRowFullWidth(table, imageRowIndex, photoTableWidth);
                spanPhotoRowFullWidth(table, titleRowIndex, photoTableWidth);
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
    }

    /**
     * 处理图片占位符
     *
     * @param document Word文档
     * @param key      占位符的key
     * @param value    图片的MinIO ID，可能是逗号分隔的多个ID
     * @throws Exception 异常
     */
    private void handleImagePlaceholder(XWPFDocument document, String key, String value, Building building) throws Exception {
        if (value == null || value.isEmpty()) {
            return;
        }

        // 检查是否有多个图片ID
        String[] imageIds = value.split(",");
        // 过滤空的imageId
        imageIds = Arrays.stream(imageIds)
                .filter(id -> id != null && !id.trim().isEmpty())
                .map(String::trim)
                .toArray(String[]::new);

        if (imageIds.length == 0) {
            return;
        }

        // 为特定的key设置特殊标题
        String imageTitle = null;
        String titleText = null;
        Integer chapterNumber = null;
        if ("${chapter-1-2-bridgeLayoutPlan}".equals(key)) {
            titleText = "桥型布置图";
            chapterNumber = 1;
        } else if ("${chapter-1-2-bridgeStandardCrossSection}".equals(key)) {
            titleText = "横断面布置图";
            chapterNumber = 1;
        } else if ("${chapter-4-1-layoutOfMeasuringPoints}".equals(key)) {
            // 构建桥梁测点布置示意图标题：桥梁名称 + "测点布置示意图"
            String bridgeName = building != null && building.getName() != null ? building.getName() : "桥梁";
            titleText = bridgeName + "测点布置示意图";
            chapterNumber = 4;
        }

        // 判断是否为封面图片，需要特殊处理
        boolean isCoverImage = "${cover-image}".equals(key);

        if (imageIds.length == 1) {
            // 单张图片
            String imageId = imageIds[0];
            FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(imageId));
            if (fileMap != null) {
                // 替换图片，并添加标题域
                replaceImageInDocumentWithField(document, key, fileMap.getNewName(), titleText, chapterNumber, isCoverImage);
            } else {
                log.warn("未找到图片文件: imageId={}", imageId);
            }
        } else {
            // 多张图片，需要创建表格来展示
            insertMultipleImagesTableWithField(document, key, imageIds, titleText, chapterNumber);
        }
    }

    /**
     * 在Word文档中插入多张图片的表格（使用域）
     *
     * @param document      Word文档
     * @param key           占位符的key（不包含${}）
     * @param imageIds      图片的MinIO ID数组
     * @param titleText     图片标题文本，可以为null
     * @param chapterNumber 章节号，可以为null
     * @throws Exception 异常
     */
    private void insertMultipleImagesTableWithField(XWPFDocument document, String key, String[] imageIds, String titleText, Integer chapterNumber) throws Exception {
        if (imageIds == null || imageIds.length == 0) return;

        // 查找占位符段落
        XWPFParagraph placeholderParagraph = null;
        int placeholderIndex = -1;
        XWPFTableCell placeholderCell = null;
        boolean isInTable = false;

        // 在段落中查找
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph paragraph = paragraphs.get(i);
            if (paragraph.getText().contains(key)) {
                placeholderParagraph = paragraph;
                placeholderIndex = i;
                break;
            }
        }

        // 如果在段落中没找到，尝试在表格中查找
        if (placeholderParagraph == null) {
            outerLoop:
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            if (paragraph.getText().contains(key)) {
                                placeholderParagraph = paragraph;
                                placeholderCell = cell;
                                isInTable = true;
                                break outerLoop;
                            }
                        }
                    }
                }
            }
        }

        if (placeholderParagraph == null) {
            log.warn("未找到占位符: {}", key);
            return;
        }

        try {
            if (isInTable) {
                // 在表格单元格中处理多张图片
                handleMultipleImagesInCellWithField(document, placeholderCell, placeholderParagraph, imageIds, titleText, chapterNumber);
            } else {
                // 在普通段落中处理多张图片
                handleMultipleImagesInParagraphWithField(document, placeholderParagraph, placeholderIndex, imageIds, titleText, chapterNumber);
            }
        } catch (Exception e) {
            log.error("插入多张图片失败，占位符: {}, 错误: {}", key, e.getMessage(), e);
            // 异常时恢复占位符
            if (!placeholderParagraph.getText().contains(key)) {
                clearParagraph(placeholderParagraph);
                XWPFRun run = placeholderParagraph.createRun();
                run.setText(key);
            }
            throw e;
        }
    }

    /**
     * 在Word文档中插入多张图片的表格（旧版本，保留兼容性）
     *
     * @param document   Word文档
     * @param key        占位符的key（不包含${}）
     * @param imageIds   图片的MinIO ID数组
     * @param imageTitle 图片标题，可以为null
     * @throws Exception 异常
     */
    private void insertMultipleImagesTable(XWPFDocument document, String key, String[] imageIds, String imageTitle) throws Exception {
        if (imageIds == null || imageIds.length == 0) return;

        // 查找占位符段落
        XWPFParagraph placeholderParagraph = null;
        int placeholderIndex = -1;
        XWPFTableCell placeholderCell = null;
        boolean isInTable = false;

        // 在段落中查找
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph paragraph = paragraphs.get(i);
            if (paragraph.getText().contains(key)) {
                placeholderParagraph = paragraph;
                placeholderIndex = i;
                break;
            }
        }

        // 如果在段落中没找到，尝试在表格中查找
        if (placeholderParagraph == null) {
            outerLoop:
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) {
                            if (paragraph.getText().contains(key)) {
                                placeholderParagraph = paragraph;
                                placeholderCell = cell;
                                isInTable = true;
                                break outerLoop;
                            }
                        }
                    }
                }
            }
        }

        if (placeholderParagraph == null) {
            log.warn("未找到占位符: {}", key);
            return;
        }

        try {
            if (isInTable) {
                // 在表格单元格中处理多张图片
                handleMultipleImagesInCell(placeholderCell, placeholderParagraph, imageIds, imageTitle);
            } else {
                // 在普通段落中处理多张图片
                handleMultipleImagesInParagraph(document, placeholderParagraph, placeholderIndex, imageIds, imageTitle);
            }
        } catch (Exception e) {
            log.error("插入多张图片失败，占位符: {}, 错误: {}", key, e.getMessage(), e);
            // 异常时恢复占位符
            if (!placeholderParagraph.getText().contains(key)) {
                clearParagraph(placeholderParagraph);
                XWPFRun run = placeholderParagraph.createRun();
                run.setText(key);
            }
            throw e;
        }
    }

    /**
     * 在表格单元格中处理多张图片（使用域）
     */
    private void handleMultipleImagesInCellWithField(XWPFDocument document, XWPFTableCell cell, XWPFParagraph placeholderParagraph, String[] imageIds, String titleText, Integer chapterNumber) throws Exception {
        // 清除占位符
        clearParagraph(placeholderParagraph);
        placeholderParagraph.setAlignment(ParagraphAlignment.CENTER);

        // 添加所有图片
        for (String imageId : imageIds) {
            if (imageId == null || imageId.trim().isEmpty()) continue;

            try {
                FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(imageId.trim()));
                if (fileMap != null) {
                    XWPFParagraph imgParagraph = cell.addParagraph();
                    setSingleLineSpacing(imgParagraph);  // 设置单倍行距
                    imgParagraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = imgParagraph.createRun();

                    // 从MinIO获取图片
                    try (InputStream imageStream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object(fileMap.getNewName().substring(0, 2) + "/" + fileMap.getNewName())
                                    .build()
                    )) {
                        // 表格中的图片使用较小尺寸
                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, fileMap.getNewName(),
                                Units.toEMU(200), Units.toEMU(150));
                    }
                } else {
                    log.warn("未找到图片文件: imageId={}", imageId);
                }
            } catch (Exception e) {
                log.error("处理图片出错: imageId={}, error={}", imageId, e.getMessage());
            }
        }

        // 如果有特定标题，在所有图片之后添加标题域
        if (titleText != null && !titleText.isEmpty()) {
            XWPFParagraph titleParagraph = cell.addParagraph();
            setSingleLineSpacing(titleParagraph);  // 设置单倍行距

            // 在现有段落中创建图片标题域
            WordFieldUtils.createFigureCaptionInParagraph(titleParagraph, titleText, chapterNumber, null);
        }
    }

    /**
     * 在表格单元格中处理多张图片（旧版本，保留兼容性）
     */
    private void handleMultipleImagesInCell(XWPFTableCell cell, XWPFParagraph placeholderParagraph, String[] imageIds, String imageTitle) throws Exception {
        // 清除占位符
        clearParagraph(placeholderParagraph);
        placeholderParagraph.setAlignment(ParagraphAlignment.CENTER);

        // 添加所有图片
        for (String imageId : imageIds) {
            if (imageId == null || imageId.trim().isEmpty()) continue;

            try {
                FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(imageId.trim()));
                if (fileMap != null) {
                    XWPFParagraph imgParagraph = cell.addParagraph();
                    setSingleLineSpacing(imgParagraph);  // 设置单倍行距
                    imgParagraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = imgParagraph.createRun();

                    // 从MinIO获取图片
                    try (InputStream imageStream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object(fileMap.getNewName().substring(0, 2) + "/" + fileMap.getNewName())
                                    .build()
                    )) {
                        // 表格中的图片使用较小尺寸
                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, fileMap.getNewName(),
                                Units.toEMU(200), Units.toEMU(150));
                    }
                } else {
                    log.warn("未找到图片文件: imageId={}", imageId);
                }
            } catch (Exception e) {
                log.error("处理图片出错: imageId={}, error={}", imageId, e.getMessage());
            }
        }

        // 如果有特定标题，在所有图片之后添加标题段落
        if (imageTitle != null && !imageTitle.isEmpty()) {
            XWPFParagraph titleParagraph = cell.addParagraph();
            setSingleLineSpacing(titleParagraph);  // 设置单倍行距
            titleParagraph.setAlignment(ParagraphAlignment.CENTER);
            titleParagraph.setStyle("12");
            titleParagraph.setSpacingBefore(100);
            titleParagraph.setSpacingAfter(200);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setText(imageTitle);
        }
    }

    /**
     * 在普通段落中处理多张图片（使用域）
     */
    private void handleMultipleImagesInParagraphWithField(XWPFDocument document, XWPFParagraph placeholderParagraph,
                                                          int placeholderIndex, String[] imageIds, String titleText, Integer chapterNumber) throws Exception {
        // 清除占位符段落的内容，但保留段落本身
        clearParagraph(placeholderParagraph);
        placeholderParagraph.setAlignment(ParagraphAlignment.CENTER);

        // 记录成功插入的段落，用于异常时清理
        List<XWPFParagraph> insertedParagraphs = new ArrayList<>();

        try {
            // 在占位符段落中插入第一张图片
            String firstImageId = imageIds[0];
            FileMap firstFileMap = fileMapService.selectFileMapById(Long.valueOf(firstImageId));
            if (firstFileMap != null) {
                XWPFRun run = placeholderParagraph.createRun();

                // 从MinIO获取图片
                try (InputStream imageStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(firstFileMap.getNewName().substring(0, 2) + "/" + firstFileMap.getNewName())
                                .build()
                )) {
                    // 添加图片到段落
                    run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, firstFileMap.getNewName(),
                            Units.toEMU(400), Units.toEMU(300));
                }
            }

            // 插入剩余图片到新段落
            int currentIndex = placeholderIndex;
            for (int i = 1; i < imageIds.length; i++) {
                String imageId = imageIds[i];
                if (imageId == null || imageId.trim().isEmpty()) continue;

                FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(imageId.trim()));
                if (fileMap != null) {
                    // 在指定位置插入新段落，而不是在末尾创建后移动
                    XWPFParagraph newParagraph = document.insertNewParagraph(placeholderParagraph.getCTP().newCursor());
                    newParagraph.setAlignment(ParagraphAlignment.CENTER);
                    insertedParagraphs.add(newParagraph);

                    XWPFRun run = newParagraph.createRun();

                    // 从MinIO获取图片
                    try (InputStream imageStream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object(fileMap.getNewName().substring(0, 2) + "/" + fileMap.getNewName())
                                    .build()
                    )) {
                        // 添加图片到段落
                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, fileMap.getNewName(),
                                Units.toEMU(400), Units.toEMU(300));
                    }
                    currentIndex++;
                } else {
                    log.warn("未找到图片文件: imageId={}", imageId);
                }
            }

            // 添加标题域
            if (titleText != null && !titleText.isEmpty()) {
                XWPFParagraph titleParagraph = document.insertNewParagraph(
                        insertedParagraphs.isEmpty() ?
                                placeholderParagraph.getCTP().newCursor() :
                                insertedParagraphs.get(insertedParagraphs.size() - 1).getCTP().newCursor()
                );

                // 在现有段落中创建图片标题域
                WordFieldUtils.createFigureCaptionInParagraph(titleParagraph, titleText, chapterNumber, null);
                insertedParagraphs.add(titleParagraph);
            }

        } catch (Exception e) {
            // 异常时清理已插入的段落
            for (XWPFParagraph p : insertedParagraphs) {
                try {
                    int pos = document.getPosOfParagraph(p);
                    if (pos >= 0) {
                        document.removeBodyElement(pos);
                    }
                } catch (Exception cleanupEx) {
                    log.error("清理段落失败", cleanupEx);
                }
            }
            throw e;
        }
    }

    /**
     * 在普通段落中处理多张图片（旧版本，保留兼容性）
     */
    private void handleMultipleImagesInParagraph(XWPFDocument document, XWPFParagraph placeholderParagraph,
                                                 int placeholderIndex, String[] imageIds, String imageTitle) throws Exception {
        // 清除占位符段落的内容，但保留段落本身
        clearParagraph(placeholderParagraph);
        placeholderParagraph.setAlignment(ParagraphAlignment.CENTER);

        // 记录成功插入的段落，用于异常时清理
        List<XWPFParagraph> insertedParagraphs = new ArrayList<>();

        try {
            // 在占位符段落中插入第一张图片
            String firstImageId = imageIds[0];
            FileMap firstFileMap = fileMapService.selectFileMapById(Long.valueOf(firstImageId));
            if (firstFileMap != null) {
                XWPFRun run = placeholderParagraph.createRun();

                // 从MinIO获取图片
                try (InputStream imageStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(firstFileMap.getNewName().substring(0, 2) + "/" + firstFileMap.getNewName())
                                .build()
                )) {
                    // 添加图片到段落
                    run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, firstFileMap.getNewName(),
                            Units.toEMU(400), Units.toEMU(300));
                }
            }

            // 插入剩余图片到新段落
            int currentIndex = placeholderIndex;
            for (int i = 1; i < imageIds.length; i++) {
                String imageId = imageIds[i];
                if (imageId == null || imageId.trim().isEmpty()) continue;

                FileMap fileMap = fileMapService.selectFileMapById(Long.valueOf(imageId.trim()));
                if (fileMap != null) {
                    // 在指定位置插入新段落，而不是在末尾创建后移动
                    XWPFParagraph newParagraph = document.insertNewParagraph(placeholderParagraph.getCTP().newCursor());
                    newParagraph.setAlignment(ParagraphAlignment.CENTER);
                    insertedParagraphs.add(newParagraph);

                    XWPFRun run = newParagraph.createRun();

                    // 从MinIO获取图片
                    try (InputStream imageStream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object(fileMap.getNewName().substring(0, 2) + "/" + fileMap.getNewName())
                                    .build()
                    )) {
                        // 添加图片到段落
                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, fileMap.getNewName(),
                                Units.toEMU(400), Units.toEMU(300));
                    }
                    currentIndex++;
                } else {
                    log.warn("未找到图片文件: imageId={}", imageId);
                }
            }

            // 添加标题段落
            if (imageTitle != null && !imageTitle.isEmpty()) {
                XWPFParagraph titleParagraph = document.insertNewParagraph(
                        insertedParagraphs.isEmpty() ?
                                placeholderParagraph.getCTP().newCursor() :
                                insertedParagraphs.get(insertedParagraphs.size() - 1).getCTP().newCursor()
                );
                titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                titleParagraph.setStyle("12");
                titleParagraph.setSpacingBefore(100);
                titleParagraph.setSpacingAfter(200);
                XWPFRun titleRun = titleParagraph.createRun();
                titleRun.setText(imageTitle);
                insertedParagraphs.add(titleParagraph);
            }

        } catch (Exception e) {
            // 异常时清理已插入的段落
            for (XWPFParagraph p : insertedParagraphs) {
                try {
                    int pos = document.getPosOfParagraph(p);
                    if (pos >= 0) {
                        document.removeBodyElement(pos);
                    }
                } catch (Exception cleanupEx) {
                    log.error("清理段落失败", cleanupEx);
                }
            }
            throw e;
        }
    }

    /**
     * 替换文档中的图片（使用域）
     */
    private void replaceImageInDocumentWithField(XWPFDocument document, String placeholder, String imageFileName,
                                                 String titleText, Integer chapterNumber, boolean isCoverImage) throws Exception {
        String imageUrl = null;
        if (imageFileName != null) {
            imageUrl = imageFileName.substring(imageFileName.lastIndexOf('/') + 1);
        }

        // 替换段落中的图片
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            String paragraphText = paragraph.getText();

            // 先检查段落文本中是否包含占位符
            if (paragraphText.contains(placeholder)) {
                log.info("在普通段落中找到占位符(带域): {}, 段落文本: {}", placeholder, paragraphText);

                // 合并段落中的runs，解决Word分割占位符的问题
                mergeParagraphRuns(paragraph, placeholder);
                try {
                    // 清除占位符段落的内容，但保留段落本身
                    clearParagraph(paragraph);

                    // 设置段落居中对齐
                    paragraph.setAlignment(ParagraphAlignment.CENTER);

                    // 封面图片特殊处理
                    if (isCoverImage) {
                        // 封面图片设置特殊属性
                        paragraph.setSpacingBefore(0);
                        paragraph.setSpacingAfter(0);
                        paragraph.setSpacingBetween(1.0);
                    }

                    if (imageUrl != null) {
                        try (InputStream imageStream = minioClient.getObject(
                                GetObjectArgs.builder()
                                        .bucket(minioConfig.getBucketName())
                                        .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                        .build()
                        )) {
                            XWPFRun run = paragraph.createRun();

                            // 根据是否为封面图片设置不同尺寸
                            int width, height;
                            if (isCoverImage) {
                                // 封面图片使用更大尺寸，并设置为内联图片
                                width = Units.toEMU(400);
                                height = Units.toEMU(300);
                            } else {
                                width = Units.toEMU(400);
                                height = Units.toEMU(300);
                            }

                            run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder, width, height);
                        }
                    }

                    // 如果有标题且不是封面图片，添加标题域
                    if (titleText != null && !titleText.isEmpty() && !isCoverImage) {
                        XWPFParagraph titleParagraph = insertParagraphAfter(paragraph);

                        // 在现有段落中创建图片标题域
                        WordFieldUtils.createFigureCaptionInParagraph(titleParagraph, titleText, chapterNumber, null);
                    }

                    return;

                } catch (Exception e) {
                    log.error("替换图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                    // 异常时恢复占位符
                    clearParagraph(paragraph);
                    XWPFRun run = paragraph.createRun();
                    run.setText(placeholder);
                    throw e;
                }
            }
        }

        // 替换表格中的图片
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        String cellParagraphText = paragraph.getText();

                        // 先检查单元格段落文本中是否包含占位符
                        if (cellParagraphText.contains(placeholder)) {
                            log.info("在表格单元格中找到占位符(带域): {}, 单元格文本: {}", placeholder, cellParagraphText);

                            // 合并段落中的runs，解决Word分割占位符的问题
                            mergeParagraphRuns(paragraph, placeholder);

                            try {
                                // 清除占位符
                                clearParagraph(paragraph);

                                // 设置段落居中对齐
                                paragraph.setAlignment(ParagraphAlignment.CENTER);

                                if (imageUrl != null) {
                                    try (InputStream imageStream = minioClient.getObject(
                                            GetObjectArgs.builder()
                                                    .bucket(minioConfig.getBucketName())
                                                    .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                                    .build()
                                    )) {
                                        XWPFRun run = paragraph.createRun();
                                        // 表格中的图片使用较小尺寸
                                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder,
                                                Units.toEMU(200), Units.toEMU(150));
                                        log.info("成功在表格单元格中插入图片(带域): {}", placeholder);
                                    }
                                } else {
                                    log.info("图片为空，已清除表格中的占位符(带域): {}", placeholder);
                                }

                                // 如果有标题，在图片之后添加标题域
                                if (titleText != null && !titleText.isEmpty()) {
                                    XWPFParagraph titleParagraph = cell.addParagraph();
                                    setSingleLineSpacing(titleParagraph);  // 设置单倍行距

                                    // 在现有段落中创建图片标题域
                                    WordFieldUtils.createFigureCaptionInParagraph(titleParagraph, titleText, chapterNumber, null);
                                }

                                return;

                            } catch (Exception e) {
                                log.error("替换表格中图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                                // 异常时恢复占位符
                                clearParagraph(paragraph);
                                XWPFRun run = paragraph.createRun();
                                run.setText(placeholder);
                            }
                        }
                    }
                }
            }
        }

        log.warn("未找到占位符在文档中: {}, 请检查Word模板中是否存在该占位符", placeholder);
    }

    /**
     * 合并段落中的所有文本运行（Runs），解决Word将占位符分割的问题
     *
     * @param paragraph   段落对象
     * @param placeholder 要查找的占位符
     * @return 如果找到占位符返回true，否则返回false
     */
    private boolean mergeParagraphRuns(XWPFParagraph paragraph, String placeholder) {
        String fullText = paragraph.getText();
        if (!fullText.contains(placeholder)) {
            return false;
        }

        // 清除所有现有的runs
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }

        // 创建一个新的run，包含合并后的文本
        XWPFRun newRun = paragraph.createRun();
        newRun.setText(fullText);

        return true;
    }

    /**
     * 替换文档中的图片（旧版本，保留兼容性）
     */
    private void replaceImageInDocument(XWPFDocument document, String placeholder, String imageFileName,
                                        String imageTitle, boolean isCoverImage) throws Exception {
        String imageUrl = null;
        if (imageFileName != null) {
            imageUrl = imageFileName.substring(imageFileName.lastIndexOf('/') + 1);
        }

        // 替换段落中的图片
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            String paragraphText = paragraph.getText();

            // 先检查段落文本中是否包含占位符
            if (paragraphText.contains(placeholder)) {
                log.info("在普通段落中找到占位符: {}, 段落文本: {}", placeholder, paragraphText);

                // 合并段落中的runs，解决Word分割占位符的问题
                mergeParagraphRuns(paragraph, placeholder);
                try {
                    // 清除占位符段落的内容，但保留段落本身
                    clearParagraph(paragraph);

                    // 设置段落居中对齐
                    paragraph.setAlignment(ParagraphAlignment.CENTER);

                    // 封面图片特殊处理
                    if (isCoverImage) {
                        // 封面图片设置特殊属性
                        paragraph.setSpacingBefore(0);
                        paragraph.setSpacingAfter(0);
                        paragraph.setSpacingBetween(1.0);
                    }

                    if (imageUrl != null) {
                        try (InputStream imageStream = minioClient.getObject(
                                GetObjectArgs.builder()
                                        .bucket(minioConfig.getBucketName())
                                        .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                        .build()
                        )) {
                            XWPFRun run = paragraph.createRun();

                            // 根据是否为封面图片设置不同尺寸
                            int width, height;
                            if (isCoverImage) {
                                // 封面图片使用更大尺寸，并设置为内联图片
                                width = Units.toEMU(400);
                                height = Units.toEMU(300);
                            } else {
//                                width = Units.toEMU(400);
//                                height = Units.toEMU(300);
                                // 11.11 修改 ， 固定大小为 8cm x 6cm
                                width = Units.toEMU(8 * 567);   // 8 cm
                                height = Units.toEMU(6 * 567);  // 6 cm
                            }

                            run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder, width, height);
                        }
                    } else {
                        // 当图片为空时，清除占位符，不显示任何内容
                        // 段落已经被清空，不需要额外操作
                        log.debug("图片为空，已清除占位符: {}", placeholder);
                    }

                    // 如果有标题且不是封面图片，添加标题段落
                    if (imageTitle != null && !imageTitle.isEmpty() && !isCoverImage) {
                        XWPFParagraph titleParagraph = insertParagraphAfter(paragraph);
                        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                        titleParagraph.setStyle("12");
                        titleParagraph.setSpacingBefore(100);
                        titleParagraph.setSpacingAfter(200);
                        XWPFRun titleRun = titleParagraph.createRun();
                        titleRun.setText(imageTitle);
                    }

                    return;

                } catch (Exception e) {
                    log.error("替换图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                    // 异常时恢复占位符
                    clearParagraph(paragraph);
                    XWPFRun run = paragraph.createRun();
                    run.setText(placeholder);
                    throw e;
                }
            }
        }

        // 替换表格中的图片
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        String cellParagraphText = paragraph.getText();

                        // 先检查单元格段落文本中是否包含占位符
                        if (cellParagraphText.contains(placeholder)) {
                            log.info("在表格单元格中找到占位符: {}, 单元格文本: {}", placeholder, cellParagraphText);

                            // 合并段落中的runs，解决Word分割占位符的问题
                            mergeParagraphRuns(paragraph, placeholder);

                            try {
                                // 清除占位符
                                clearParagraph(paragraph);

                                // 设置段落居中对齐
                                paragraph.setAlignment(ParagraphAlignment.CENTER);

                                if (imageUrl != null) {
                                    try (InputStream imageStream = minioClient.getObject(
                                            GetObjectArgs.builder()
                                                    .bucket(minioConfig.getBucketName())
                                                    .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                                    .build()
                                    )) {
                                        XWPFRun run = paragraph.createRun();
                                        // 表格中的图片使用较小尺寸
                                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder,
                                                Units.toEMU(200), Units.toEMU(150));
                                        log.info("成功在表格单元格中插入图片: {}", placeholder);
                                    }
                                } else {
                                    log.info("图片为空，已清除表格中的占位符: {}", placeholder);
                                }

                                // 如果有标题，在图片之后添加标题段落
                                if (imageTitle != null && !imageTitle.isEmpty()) {
                                    XWPFParagraph titleParagraph = cell.addParagraph();
                                    setSingleLineSpacing(titleParagraph);  // 设置单倍行距
                                    titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                                    titleParagraph.setStyle("12");
                                    titleParagraph.setSpacingBefore(100);
                                    titleParagraph.setSpacingAfter(200);
                                    XWPFRun titleRun = titleParagraph.createRun();
                                    titleRun.setText(imageTitle);
                                }

                                return;

                            } catch (Exception e) {
                                log.error("替换表格中图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                                // 异常时恢复占位符
                                clearParagraph(paragraph);
                                XWPFRun run = paragraph.createRun();
                                run.setText(placeholder);
                            }
                        }
                    }
                }
            }
        }

        log.warn("未找到占位符在文档中: {}, 请检查Word模板中是否存在该占位符", placeholder);
    }

    private void debugAllStyles(XWPFDocument document) {
        XWPFStyles styles = document.getStyles();
        if (styles == null) {
            System.out.println("文档没有样式信息！");
            return;
        }

        System.out.println("=== 调试样式信息 ===");

        // 1. 遍历文档中所有段落，打印段落使用的样式
        System.out.println("=== 段落使用样式 ===");
        for (XWPFParagraph para : document.getParagraphs()) {
            String styleId = para.getStyle();
            if (styleId != null) {
                XWPFStyle style = styles.getStyle(styleId);
                String styleName = style != null ? style.getName() : "未知";
                System.out.println("段落: " + para.getText() + " | StyleID: " + styleId + " | Name: " + styleName);
            }
        }

        // 2. 遍历文档中所有运行，打印运行使用的字符样式
        System.out.println("=== 运行使用字符样式 ===");
        for (XWPFParagraph para : document.getParagraphs()) {
            for (XWPFRun run : para.getRuns()) {
                String styleId = run.getStyle();
                if (styleId != null) {
                    XWPFStyle style = styles.getStyle(styleId);
                    String styleName = style != null ? style.getName() : "未知";
                    System.out.println("运行文本: " + run.text() + " | StyleID: " + styleId + " | Name: " + styleName);
                }
            }
        }

        // 3. 测试常见题注样式
        System.out.println("=== 常见题注样式 ===");
        String[] commonCaptionStyles = {"Caption", "题注", "图题", "FigureCaption"};
        for (String testStyle : commonCaptionStyles) {
            XWPFStyle style = styles.getStyle(testStyle);
            if (style != null) {
                System.out.println("找到样式: " + testStyle + " -> " + style.getName());
            } else {
                System.out.println("未找到样式: " + testStyle);
            }
        }
    }


    public String getDiseaseSummary(List<Disease> diseases) throws JsonProcessingException {
        return getDiseaseSummary(diseases, null);
    }

    public String getDiseaseSummary(List<Disease> diseases, String sectionName) throws JsonProcessingException {
        // 瘦身
        List<Disease2ReportSummaryAiVO> less = Disease2ReportSummaryAiVO.convert(diseases);
        // 序列化为JSON字符串
        ObjectMapper mapper = new ObjectMapper();
        // 传入所属部位，由 AI 服务决定是否保留位置分组；旧调用仍兼容数组请求。
        Object payload = sectionName == null ? less : Map.of("sectionName", sectionName, "diseases", less);
        String diseasesJson = mapper.writeValueAsString(payload);
        // 发送POST请求
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(diseasesJson, headers);
        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.postForObject(SpringAiUrl + "/api-ai" + "/diseaseSummary", request, String.class);
        return response;
    }

    /**
     * 从书签名中提取章节内序号
     *
     * @param bookmarkName 书签名，如"Figure_Chapter3_1"
     * @return 章节内序号
     */
    private int extractSequenceFromBookmark(String bookmarkName) {
        if (bookmarkName.startsWith("Figure_Chapter")) {
            String[] parts = bookmarkName.split("_");
            if (parts.length >= 3) {
                try {
                    int globalSeq = Integer.parseInt(parts[2]);
                    // 简化处理：假设每个节点的图片从1开始编号
                    // 这里可以根据实际需要调整计算逻辑
                    return globalSeq;
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    /**
     * 为每个子桥生成病害对比表格
     *
     * @param document           Word文档
     * @param biObjectMap        子桥列表
     * @param tasks              建筑物信息
     * @param cursor             插入位置游标
     * @param chapterNum         章节号
     * @param startSubChapterNum 起始子章节号
     * @param tableCounter       表格计数器
     */
    private void generateDiseaseComparisonTables(XWPFDocument document, Map<Long, BiObject> biObjectMap,
                                                 List<Task> tasks, XmlCursor cursor,
                                                 Integer chapterNum, Integer startSubChapterNum,
                                                 AtomicInteger tableCounter) {
        try {
            int currentSubChapterNum = startSubChapterNum;

            // 创建章节标题段落 - 使用与现有代码一致的方式
            XWPFParagraph titlePara;
            if (cursor != null) {
                titlePara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                titlePara = document.createParagraph();
            }

            // 设置标题样式 - 使用第3级标题
            titlePara.setStyle("3");
            titlePara.setAlignment(ParagraphAlignment.LEFT);

            // 设置缩进
            CTPPr ppr = titlePara.getCTP().getPPr();
            if (ppr == null) ppr = titlePara.getCTP().addNewPPr();
            CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
            ind.setFirstLine(BigInteger.valueOf(0));
            ind.setLeft(BigInteger.valueOf(0));

            // 创建标题运行
            XWPFRun run = titlePara.createRun();
            run.setText("与上一次检查病害变化情况分析");
            run.setBold(false);
            run.setFontFamily("黑体");

            log.info("已创建章节标题");

            // 完全模仿第八章的方式：在for循环中为每个task调用生成方法
            for (Task task : tasks) {
                BiObject subBridge = biObjectMap.get(task.getBuilding().getRootObjectId());
                Long projectId = task.getProjectId();
                Building building = task.getBuilding();

                // 生成病害对比数据
                List<DiseaseComparisonData> comparisonData = diseaseComparisonService.generateComparisonData(subBridge, projectId, building.getId());

                if (!comparisonData.isEmpty()) {
                    // 为每个桥单独生成横向表格
                    generateSingleDiseaseComparisonTable(document, titlePara, comparisonData,
                            tableCounter, chapterNum, currentSubChapterNum, subBridge.getName());

                    currentSubChapterNum++;
                    log.info("已生成病害对比表格: {}", subBridge.getName());
                }
            }

        } catch (Exception e) {
            log.error("生成病害对比表格时发生错误", e);
        }
    }

    /**
     * 为单个桥生成病害对比表格（模仿generateEvaluationTableAfterParagraph的方式）
     */
    private void generateSingleDiseaseComparisonTable(XWPFDocument document, XWPFParagraph afterParagraph,
                                                      List<DiseaseComparisonData> comparisonData,
                                                      AtomicInteger tableCounter, Integer chapterNum,
                                                      Integer subChapterNum, String bridgeName) {
        try {
            if (afterParagraph == null) {
                log.warn("插入位置段落为null，无法生成表格");
                return;
            }

            WordSectionLayoutUtils.Layouts layouts = WordSectionLayoutUtils.snapshot(document);
            XWPFParagraph landscapeParagraph = WordSectionLayoutUtils.beginLandscapeTableBlock(
                    document, afterParagraph, layouts);

            // 步骤3：从横向分节符段落后获取cursor位置，创建表格
            XmlCursor tableCursor = landscapeParagraph.getCTP().newCursor();

            // 生成病害对比表格
            String tableBookmark = DiseaseComparisonTableUtils.createDiseaseComparisonTable(
                    document,
                    comparisonData,
                    tableCursor,
                    tableCounter,
                    chapterNum,
                    subChapterNum,
                    bridgeName
            );

            log.info("横版表格生成完成，后续正文保留竖版");

        } catch (Exception e) {
            log.error("生成单个病害对比表格失败", e);
            throw e;
        }
    }

    /**
     * 处理第七章病害表格
     *
     * @param document Word文档
     * @param key      占位符key
     * @param value    JSON数据
     * @param tasks    任务列表
     */
    private void handleChapter7DiseaseTable(XWPFDocument document, String key, String value, List<Task> tasks) {
        try {
            log.info("开始处理第七章病害表格, key: {}, value: {}, tasks数量: {}", key, value, tasks.size());

            // 解析JSON数据（新格式：{taskId: [{componentId: [diseaseTypeIds]}]}）
            List<ComponentDiseaseType> combinations = parseChapter7Json(value);
            if (combinations.isEmpty()) {
                log.warn("第七章JSON数据为空或解析失败: {}", value);
                replaceText(document, key, "无数据");
                return;
            }

            if (key.contains("${chapter-7-1-focusOnDiseases}")) {
                // 重点关注病害 - 生成表格
                List<Map<String, Object>> tableData = generateChapter7TableData(combinations, tasks);
                if (tableData.isEmpty()) {
                    log.warn("第七章表格数据为空");
                    replaceText(document, key, "无数据");
                    return;
                }

                String tableTitle = "重点关注病害汇总表";
                insertChapter7Table(document, key, tableTitle, tableData);
                log.info("第七章重点关注病害表格处理完成, 生成{}行数据", tableData.size());
            } else if (key.contains("${chapter-7-2-analysisOfTheCausesOfMajorDiseases}")) {
                // 主要病害成因分析 - 按结构分类分别处理
                generateAndInsertChapter7AnalysisContent(combinations, tasks, document);
                log.info("第七章病害成因分析处理完成");
            }

        } catch (Exception e) {
            log.error("处理第七章病害表格失败: key={}, value={}", key, value, e);
            throw e;
        }
    }

    /**
     * 解析第七章JSON数据（支持多任务）
     *
     * @param jsonValue JSON字符串，格式：{taskId: [{componentId: [diseaseTypeIds]}]}
     * @return 构件病害类型组合列表（包含taskId）
     */
    private List<ComponentDiseaseType> parseChapter7Json(String jsonValue) {
        List<ComponentDiseaseType> combinations = new ArrayList<>();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            // 解析新格式：{taskId: [{componentId: [diseaseTypeIds]}]}
            Map<String, List<Map<String, List<Integer>>>> taskData = objectMapper.readValue(jsonValue, Map.class);

            for (Map.Entry<String, List<Map<String, List<Integer>>>> taskEntry : taskData.entrySet()) {
                Long taskId = Long.parseLong(taskEntry.getKey());
                List<Map<String, List<Integer>>> taskSelections = taskEntry.getValue();

                for (Map<String, List<Integer>> item : taskSelections) {
                    for (Map.Entry<String, List<Integer>> entry : item.entrySet()) {
                        Long componentId = Long.parseLong(entry.getKey());
                        List<Integer> diseaseTypeIds = entry.getValue();

                        for (Integer diseaseTypeId : diseaseTypeIds) {
                            combinations.add(new ComponentDiseaseType(taskId, componentId, diseaseTypeId.longValue()));
                        }
                    }
                }
            }

            log.info("解析JSON数据成功，共{}个构件病害类型组合", combinations.size());
        } catch (Exception e) {
            log.error("解析第七章JSON数据失败: {}", jsonValue, e);
        }
        return combinations;
    }

    private List<ComponentDiseaseType> parseChapter7JsonForSingleBridge(String jsonValue) {
        List<ComponentDiseaseType> combinations = new ArrayList<>();
        if (jsonValue == null || jsonValue.isBlank()) {
            log.info("未选择重点关注病害");
            return combinations;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            List<Map<String, List<Integer>>> selectedData = objectMapper.readValue(jsonValue, List.class);

            for (Map<String, List<Integer>> item : selectedData) {
                for (Map.Entry<String, List<Integer>> entry : item.entrySet()) {
                    Long componentId = Long.parseLong(entry.getKey());
                    List<Integer> diseaseTypeIds = entry.getValue();

                    for (Integer diseaseTypeId : diseaseTypeIds) {
                        combinations.add(new ComponentDiseaseType(null, componentId, diseaseTypeId.longValue()));
                    }
                }
            }

            log.info("解析JSON数据成功，共{}个构件病害类型组合", combinations.size());
        } catch (Exception e) {
            log.error("解析第七章JSON数据失败: {}", jsonValue, e);
        }
        return combinations;
    }

    /**
     * 生成第七章表格数据（支持多任务）
     *
     * @param combinations 构件病害类型组合（包含taskId）
     * @param tasks        任务列表
     * @return 表格数据
     */
    private List<Map<String, Object>> generateChapter7TableData(List<ComponentDiseaseType> combinations,
                                                                List<Task> tasks) {
        List<Map<String, Object>> tableData = new ArrayList<>();

        try {
            // 创建taskId到Task的映射
            Map<Long, Task> taskMap = tasks.stream()
                    .collect(Collectors.toMap(Task::getId, task -> task));

            // 按taskId分组处理
            Map<Long, List<ComponentDiseaseType>> taskCombinations = combinations.stream()
                    .collect(Collectors.groupingBy(ComponentDiseaseType::getTaskId));

            int index = 1;

            // 遍历每个任务
            for (Task task : tasks) {
                Long taskId = task.getId();
                List<ComponentDiseaseType> taskCombs = taskCombinations.get(taskId);

                if (taskCombs == null || taskCombs.isEmpty()) {
                    continue;
                }

                Building building = task.getBuilding();
                Project project = task.getProject();

                if (building == null || project == null) {
                    log.warn("任务{}的building或project为空，跳过", taskId);
                    continue;
                }

                // 提取该任务的所有构件ID
                List<Long> componentIds = taskCombs.stream()
                        .map(ComponentDiseaseType::getComponentId)
                        .distinct()
                        .collect(Collectors.toList());

                // 批量查询该任务的病害数据
                List<Disease> allDiseases = diseaseMapper.selectDiseaseComponentData(
                        componentIds, building.getId(), project.getYear());

                // 按构件ID+病害类型ID分组
                Map<String, List<Disease>> groupedDiseases = allDiseases.stream()
                        .collect(Collectors.groupingBy(disease ->
                                disease.getBiObjectId() + "_" + disease.getDiseaseTypeId()));

                // 批量查询构件信息
                List<BiObject> components = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(componentIds));
                Map<Long, BiObject> componentMap = components.stream()
                        .collect(Collectors.toMap(BiObject::getId, component -> component));

                // 生成该任务的表格数据
                for (ComponentDiseaseType combination : taskCombs) {
                    String key = combination.getComponentId() + "_" + combination.getDiseaseTypeId();
                    List<Disease> diseaseList = groupedDiseases.get(key);

                    if (diseaseList != null && !diseaseList.isEmpty()) {
                        Disease firstDisease = diseaseList.get(0);
                        BiObject component = componentMap.get(combination.getComponentId());

                        if (component != null && firstDisease.getDiseaseType() != null) {
                            Map<String, Object> rowData = new HashMap<>();
                            rowData.put("序号", index++);
                            rowData.put("桥梁名称", building.getName());
                            rowData.put("缺损位置", component.getName());
                            rowData.put("缺损类型", firstDisease.getDiseaseType().getName());
                            rowData.put("病害描述", generateDiseaseDescription(diseaseList));

                            tableData.add(rowData);
                        }
                    }
                }
            }

            log.info("生成第七章表格数据成功，共{}行", tableData.size());
        } catch (Exception e) {
            log.error("生成第七章表格数据失败", e);
        }

        return tableData;
    }

    /**
     * 生成病害描述
     *
     * @param diseases 病害列表
     * @return 病害描述
     */
    private String generateDiseaseDescription(List<Disease> diseases) {
        if (diseases == null || diseases.isEmpty()) {
            return "";
        }

        int count = diseases.size();
        double totalLength = 0;
        double maxWidth = 0;
        double totalArea = 0;

        for (Disease disease : diseases) {
            List<DiseaseDetail> details = disease.getDiseaseDetails();
            if (details != null) {
                for (DiseaseDetail detail : details) {
                    if (detail.getLength1() != null) {
                        totalLength += detail.getLength1().doubleValue();
                    }
                    if (detail.getCrackWidth() != null) {
                        maxWidth = Math.max(maxWidth, detail.getCrackWidth().doubleValue());
                    }
                    if (detail.getAreaLength() != null && detail.getAreaWidth() != null) {
                        totalArea += detail.getAreaLength().doubleValue() * detail.getAreaWidth().doubleValue();
                    }
                    if (detail.getWidthRangeEnd() != null) {
                        maxWidth = Math.max(maxWidth, detail.getWidthRangeEnd().doubleValue());
                    }
                    if (detail.getLengthRangeStart() != null && detail.getLengthRangeEnd() != null) {
                        totalLength += ((detail.getLengthRangeEnd().doubleValue() - detail.getLengthRangeStart().doubleValue()) / 2) * disease.getQuantity();
                        break;
                    }
                }
            }
        }
        // 生成汇总描述
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("共计%d条 ", count));
        if (totalLength > 0) {
            sb.append(String.format("总长度%.0fcm, ", totalLength * 100));
        }
        if (maxWidth > 0) {
            sb.append(String.format("最大缝宽%.2fmm, ", maxWidth));
        }
        if (totalArea > 0) {
            sb.append(String.format("面积%.4f㎡, ", totalArea));
        }
        return sb.toString();
    }


    /**
     * 插入第七章表格
     *
     * @param document   Word文档
     * @param key        占位符
     * @param tableTitle 表格标题
     * @param tableData  表格数据
     */
    private void insertChapter7Table(XWPFDocument document, String key, String tableTitle, List<Map<String, Object>> tableData) {
        try {
            // 查找占位符位置
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            XWPFParagraph targetParagraph = null;

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
                return;
            }

            // 获取插入位置的游标
            XmlCursor cursor = targetParagraph.getCTP().newCursor();

            // 清空占位符段落的所有Run
            while (targetParagraph.getRuns().size() > 0) {
                targetParagraph.removeRun(0);
            }

            // Part 3: 表格引用部分
            XWPFParagraph tableRefPara;
            if (cursor != null) {
                tableRefPara = document.insertNewParagraph(cursor);
                cursor.toNextToken();
            } else {
                tableRefPara = document.createParagraph();
            }

            // 设置1.5倍行距
            CTPPr ppr1 = tableRefPara.getCTP().getPPr();
            if (ppr1 == null) {
                ppr1 = tableRefPara.getCTP().addNewPPr();
            }
            CTSpacing spacing = ppr1.isSetSpacing() ? ppr1.getSpacing() : ppr1.addNewSpacing();
            spacing.setLine(BigInteger.valueOf(360));

            // 创建第七章表格计数器（如果不存在）
            AtomicInteger chapter7TableCounter = new AtomicInteger(1);

            // 使用现有方法创建表格标题
            String tableBookmark = WordFieldUtils.createTableCaptionWithCounter(
                    document, tableTitle, cursor, 7, chapter7TableCounter);

            // 创建章节格式的表格引用域
            WordFieldUtils.createChapterTableReference(tableRefPara, tableBookmark, "重点病害汇总表如下表", ":");

            // 创建表格
            XWPFTable table = document.insertNewTbl(cursor);

            // 设置表头
            XWPFTableRow headerRow = table.getRow(0);
            String[] headers = {"序号", "桥梁名称", "缺损位置", "缺损类型", "病害描述"};

            // 设置第一个单元格
            XWPFTableCell firstCell = headerRow.getCell(0);
            firstCell.removeParagraph(0);
            XWPFParagraph firstParagraph = firstCell.addParagraph();
            setSingleLineSpacing(firstParagraph);  // 设置单倍行距
            firstParagraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun firstRun = firstParagraph.createRun();
            firstRun.setText(headers[0]);
            firstRun.setBold(true);
            setMixedFontFamily(firstRun, 20);  // 10pt

            // 添加其余单元格并设置样式
            for (int i = 1; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.addNewTableCell();
                cell.removeParagraph(0);
                XWPFParagraph paragraph = cell.addParagraph();
                setSingleLineSpacing(paragraph);  // 设置单倍行距
                paragraph.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun run = paragraph.createRun();
                run.setText(headers[i]);
                run.setBold(true);
                setMixedFontFamily(run, 20);  // 10pt
            }

            // 设置标题行在跨页时重复显示
            setTableHeaderRepeat(headerRow);

            // 填充数据行
            for (Map<String, Object> rowData : tableData) {
                XWPFTableRow dataRow = table.createRow();
                String[] cellTexts = {
                        String.valueOf(rowData.get("序号")),
                        (String) rowData.get("桥梁名称"),
                        (String) rowData.get("缺损位置"),
                        (String) rowData.get("缺损类型"),
                        (String) rowData.get("病害描述")
                };

                // 设置每个单元格的文本和样式
                for (int i = 0; i < cellTexts.length; i++) {
                    XWPFTableCell cell = dataRow.getCell(i);
                    cell.removeParagraph(0);
                    XWPFParagraph paragraph = cell.addParagraph();
                    setSingleLineSpacing(paragraph);  // 设置单倍行距
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = paragraph.createRun();
                    run.setText(cellTexts[i]);
                    setMixedFontFamily(run, 20);  // 10pt
                }
            }

            // 设置表格边框和宽度
            table.setWidth("100%");

        } catch (Exception e) {
            log.error("插入第七章表格失败: key={}, title={}", key, tableTitle, e);
            throw e;
        }
    }

    /**
     * 生成并插入第七章病害成因分析内容（支持多任务）
     *
     * @param combinations 构件病害类型组合（包含taskId）
     * @param tasks        任务列表
     * @param document     Word文档
     */
    private void generateAndInsertChapter7AnalysisContent(List<ComponentDiseaseType> combinations, List<Task> tasks, XWPFDocument document) {
        try {
            // 创建taskId到Task的映射
            Map<Long, Task> taskMap = tasks.stream()
                    .collect(Collectors.toMap(Task::getId, task -> task));

            // 收集所有构件ID
            Set<Long> componentIds = combinations.stream()
                    .map(ComponentDiseaseType::getComponentId)
                    .collect(Collectors.toSet());

            // 批量查询构件信息
            List<BiObject> components = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(componentIds));
            Map<Long, BiObject> componentMap = components.stream()
                    .collect(Collectors.toMap(BiObject::getId, component -> component));

            // 批量获取结构分类信息
            Map<Long, String> componentStructureTypeMap = batchGetStructureTypes(components);

            // 收集所有病害类型ID并批量查询
            Set<Long> diseaseTypeIds = combinations.stream()
                    .map(ComponentDiseaseType::getDiseaseTypeId)
                    .collect(Collectors.toSet());
            List<DiseaseType> diseaseTypes = diseaseTypeMapper.selectDiseaseTypeListByIds(new ArrayList<>(diseaseTypeIds));
            Map<Long, DiseaseType> diseaseTypeMap = diseaseTypes.stream()
                    .collect(Collectors.toMap(DiseaseType::getId, diseaseType -> diseaseType));

            // 按结构分类分组
            Map<String, List<ComponentDiseaseAnalysis>> structureGroups = new LinkedHashMap<>();

            for (ComponentDiseaseType combination : combinations) {
                // 根据taskId获取对应的Task和Building
                Long taskId = combination.getTaskId();
                Task task = taskMap.get(taskId);
                if (task == null || task.getBuilding() == null) {
                    log.warn("任务或建筑物不存在，跳过该组合。taskId: {}", taskId);
                    continue;
                }

                BiObject component = componentMap.get(combination.getComponentId());
                if (component == null) continue;

                // 从缓存中获取结构分类
                String structureType = componentStructureTypeMap.get(combination.getComponentId());
                if (structureType == null) {
                    structureType = "other";
                }

                // 从缓存中获取病害类型信息
                DiseaseType diseaseType = diseaseTypeMap.get(combination.getDiseaseTypeId());
                if (diseaseType == null) continue;

                // 创建分析对象
                ComponentDiseaseAnalysis analysis = new ComponentDiseaseAnalysis();
                analysis.setComponentId(combination.getComponentId());
                analysis.setComponentName(component.getName());
                analysis.setBridgeName(task.getBuilding().getName());
                analysis.setDiseaseTypeId(combination.getDiseaseTypeId());
                analysis.setDiseaseTypeName(diseaseType.getName());
                analysis.setStructureType(structureType);

                // 添加到对应的结构分组
                structureGroups.computeIfAbsent(structureType, k -> new ArrayList<>()).add(analysis);
            }

            // 为每个结构分类生成内容并替换对应占位符
            // 从后往前插入，避免位置索引错位
            generateStructureAnalysisContent(structureGroups.get("deckSystem"), document, "${chapter-7-2-3-deckSystem}");
            generateStructureAnalysisContent(structureGroups.get("substructure"), document, "${chapter-7-2-2-substructure}");
            generateStructureAnalysisContent(structureGroups.get("superstructure"), document, "${chapter-7-2-1-superstructure}");

        } catch (Exception e) {
            log.error("生成第七章成因分析内容失败", e);
        }
    }

    /**
     * 生成单个结构分类的分析内容并替换占位符
     *
     * @param analyses    该结构分类的分析数据
     * @param document    Word文档
     * @param placeholder 占位符
     */
    private void generateStructureAnalysisContent(List<ComponentDiseaseAnalysis> analyses, XWPFDocument document, String placeholder) {
        try {
            if (analyses == null || analyses.isEmpty()) {
                replaceText(document, placeholder, "无数据");
                return;
            }

            StringBuilder content = new StringBuilder();

            // 按桥梁名+构件ID组合键分组，避免不同桥梁的相同构件被合并
            Map<String, List<ComponentDiseaseAnalysis>> componentGroups = analyses.stream()
                    .collect(Collectors.groupingBy(analysis ->
                            analysis.getBridgeName() + "_" + analysis.getComponentId()));

            // 按桥梁名和构件名排序，确保输出顺序稳定
            List<Map.Entry<String, List<ComponentDiseaseAnalysis>>> sortedEntries = componentGroups.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> {
                        ComponentDiseaseAnalysis first = entry.getValue().get(0);
                        return first.getBridgeName() + first.getComponentName();
                    }))
                    .collect(Collectors.toList());

            int index = 1;
            for (Map.Entry<String, List<ComponentDiseaseAnalysis>> entry : sortedEntries) {
                List<ComponentDiseaseAnalysis> componentAnalyses = entry.getValue();
                ComponentDiseaseAnalysis first = componentAnalyses.get(0);

                // 生成标题：桥梁名 + 构件名 + 病害类型列表
                String diseaseTypes = componentAnalyses.stream()
                        .map(ComponentDiseaseAnalysis::getDiseaseTypeName)
                        .distinct()  // 去重，避免重复的病害类型名称
                        .collect(Collectors.joining("、"));

                content.append(String.format("（%d）%s%s%s\n",
                        index++, first.getBridgeName(), first.getComponentName(), diseaseTypes));

                // 生成成因分析
                List<Long> diseaseTypeIds = componentAnalyses.stream()
                        .map(ComponentDiseaseAnalysis::getDiseaseTypeId)
                        .distinct()  // 去重病害类型ID
                        .collect(Collectors.toList());

                String causeAnalysis = getCauseAnalysis(first.getComponentId(), diseaseTypeIds);
                content.append("成因分析：").append(causeAnalysis).append("\n\n");
            }

            String finalContent = content.toString().trim();
            insertChapter7AnalysisText(document, placeholder, finalContent);
            log.info("结构分类内容生成完成: {}, 生成{}组数据", placeholder, componentGroups.size());

        } catch (Exception e) {
            log.error("生成结构分类分析内容失败: placeholder={}", placeholder, e);
            replaceText(document, placeholder, "数据处理失败");
        }
    }

    /**
     * 批量获取构件的结构分类
     *
     * @param components 构件列表
     * @return 构件ID到结构分类的映射
     */
    private Map<Long, String> batchGetStructureTypes(List<BiObject> components) {
        Map<Long, String> resultMap = new HashMap<>();

        try {
            // 收集所有需要查询的结构分类节点ID
            Set<Long> structureNodeIds = new HashSet<>();
            Map<Long, Long> componentToStructureNodeMap = new HashMap<>();

            for (BiObject component : components) {
                // 通过ancestors字段获取层级关系
                String ancestors = component.getAncestors();
                if (ancestors == null || ancestors.isEmpty()) {
                    resultMap.put(component.getId(), "other");
                    continue;
                }

                // 解析祖先节点ID列表，例如：0,1,101,1011
                String[] ancestorIds = ancestors.split(",");
                if (ancestorIds.length < 3) {
                    // 至少需要3层：根节点,桥梁节点,结构分类节点
                    resultMap.put(component.getId(), "other");
                    continue;
                }

                // 获取倒数第二个节点ID（结构分类节点）
                String structureNodeIdStr = ancestorIds[ancestorIds.length - 2].trim();
                if (structureNodeIdStr.isEmpty()) {
                    resultMap.put(component.getId(), "other");
                    continue;
                }

                try {
                    Long structureNodeId = Long.parseLong(structureNodeIdStr);
                    structureNodeIds.add(structureNodeId);
                    componentToStructureNodeMap.put(component.getId(), structureNodeId);
                } catch (NumberFormatException e) {
                    log.warn("解析结构分类节点ID失败: {}", structureNodeIdStr);
                    resultMap.put(component.getId(), "other");
                }
            }

            // 批量查询所有结构分类节点
            if (!structureNodeIds.isEmpty()) {
                List<BiObject> structureNodes = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(structureNodeIds));
                Map<Long, BiObject> structureNodeMap = structureNodes.stream()
                        .collect(Collectors.toMap(BiObject::getId, node -> node));

                // 为每个构件确定结构分类
                for (BiObject component : components) {
                    if (resultMap.containsKey(component.getId())) {
                        continue; // 已经处理过的跳过
                    }

                    Long structureNodeId = componentToStructureNodeMap.get(component.getId());
                    if (structureNodeId == null) {
                        resultMap.put(component.getId(), "other");
                        continue;
                    }

                    BiObject structureNode = structureNodeMap.get(structureNodeId);
                    if (structureNode == null) {
                        log.warn("未找到结构分类节点: {}", structureNodeId);
                        resultMap.put(component.getId(), "other");
                        continue;
                    }

                    // 根据结构分类节点名称判断分类
                    String structureType = determineStructureType(structureNode, component);
                    resultMap.put(component.getId(), structureType);

                    log.debug("构件结构分类确定 - 构件: {} (ID: {}), 结构节点: {} (ID: {}), 分类: {}",
                            component.getName(), component.getId(),
                            structureNode.getName(), structureNodeId, structureType);
                }
            }

        } catch (Exception e) {
            log.error("批量获取构件结构分类失败", e);
            // 异常情况下为所有构件设置默认分类
            for (BiObject component : components) {
                if (!resultMap.containsKey(component.getId())) {
                    resultMap.put(component.getId(), "other");
                }
            }
        }

        return resultMap;
    }

    /**
     * 根据结构分类节点和构件信息确定结构分类
     *
     * @param structureNode 结构分类节点
     * @param component     构件对象
     * @return 结构分类
     */
    private String determineStructureType(BiObject structureNode, BiObject component) {
        String structureName = structureNode.getName().toLowerCase();

        // 直接匹配结构分类关键词
        if (structureName.contains("上部结构") || structureName.contains("主体结构") ||
                structureName.contains("上部") || structureName.contains("主体")) {
            return "superstructure";
        } else if (structureName.contains("下部结构") || structureName.contains("基础结构") ||
                structureName.contains("下部") || structureName.contains("基础")) {
            return "substructure";
        } else if (structureName.contains("桥面系") || structureName.contains("桥面结构") ||
                structureName.contains("桥面") || structureName.contains("附属")) {
            return "deckSystem";
        }

        return "other";
    }

    /**
     * 获取成因分析
     *
     * @param componentId    构件ID
     * @param diseaseTypeIds 病害类型ID列表
     * @return 成因分析文本
     */
    private String getCauseAnalysis(Long componentId, List<Long> diseaseTypeIds) {
        if (componentId == null || diseaseTypeIds == null || diseaseTypeIds.isEmpty()) {
            return "";
        }
        try {
            BiObject component = biObjectMapper.selectBiObjectById(componentId);
            if (component == null) {
                return "";
            }
            List<DiseaseType> diseaseTypes = diseaseTypeMapper.selectDiseaseTypeListByIds(diseaseTypeIds);
            if (diseaseTypes == null || diseaseTypes.isEmpty()) {
                return "";
            }
            return diseaseTypes.stream()
                    .filter(Objects::nonNull)
                    .map(diseaseType -> {
                        CauseQuery causeQuery = new CauseQuery();
                        causeQuery.setObjectId(componentId);
                        causeQuery.setObject(component.getName());
                        causeQuery.setParentObject(component.getParentName());
                        causeQuery.setType(diseaseType.getName());
                        return diseaseService.getCauseAnalysis(causeQuery);
                    })
                    .filter(text -> text != null && !text.trim().isEmpty())
                    .distinct()
                    .collect(Collectors.joining("；"));
        } catch (Exception e) {
            log.warn("获取成因分析失败: componentId={}, diseaseTypeIds={}, error={}",
                    componentId, diseaseTypeIds, e.getMessage());
            return "";
        }
    }

    /**
     * 插入第七章成因分析文本
     *
     * @param document 文档对象
     * @param key      占位符
     * @param content  分析内容
     */
    private void insertChapter7AnalysisText(XWPFDocument document, String key, String content) {
        try {
            // 查找占位符位置
            XWPFParagraph targetParagraph = null;
            int targetParagraphIndex = -1;
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            for (int i = 0; i < paragraphs.size(); i++) {
                XWPFParagraph paragraph = paragraphs.get(i);
                String text = paragraph.getText();
                if (text != null && text.contains(key)) {
                    targetParagraph = paragraph;
                    targetParagraphIndex = i;
                    break;
                }
            }

            if (targetParagraph == null) {
                log.warn("未找到占位符: {}", key);
                return;
            }

            // 清空占位符段落的所有Run
            while (targetParagraph.getRuns().size() > 0) {
                targetParagraph.removeRun(0);
            }

            // 按行分割内容并插入
            String[] lines = content.split("\n");
            int currentPosition = targetParagraphIndex; // 跟踪当前插入位置

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                XWPFParagraph paragraph;
                XWPFRun run;

                if (i == 0) {
                    // 第一行使用现有段落
                    paragraph = targetParagraph;
                    run = paragraph.createRun();
                } else {
                    // 后续行在当前位置的下一行插入新段落
                    currentPosition++; // 每次插入后位置递增
                    paragraph = insertParagraphAtPosition(document, currentPosition);
                    run = paragraph.createRun();
                }

                // 添加Tab缩进并设置文本
                run.setText("\t" + line);
                run.setFontSize(12);
                run.setFontFamily("宋体");

                // 判断是否需要加粗（以（数字）开头的行）
                if (line.matches("^（\\d+）.*")) {
                    run.setBold(true);
                }
            }

            log.info("第七章成因分析文本插入成功: {}, 插入{}行内容", key, lines.length);

        } catch (Exception e) {
            log.error("插入第七章成因分析文本失败: key={}", key, e);
        }
    }

    /**
     * 在指定位置插入新段落
     *
     * @param document 文档对象
     * @param position 插入位置索引
     * @return 新创建的段落
     */
    private XWPFParagraph insertParagraphAtPosition(XWPFDocument document, int position) {
        try {
            // 获取文档的body
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody body = document.getDocument().getBody();

            // 获取当前段落数组
            java.util.List<org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP> pArray = body.getPList();

            // 检查位置是否有效
            if (position >= 0 && position <= pArray.size()) {
                // 在指定位置插入新段落的XML对象
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP newCTP = body.insertNewP(position);

                // 创建XWPFParagraph包装器，不需要手动添加到列表
                // POI会自动维护内部列表的一致性
                XWPFParagraph newParagraph = new XWPFParagraph(newCTP, document);

                return newParagraph;
            } else {
                // 位置无效，在末尾创建
                return document.createParagraph();
            }

        } catch (Exception e) {
            log.error("在位置{}插入段落失败", position, e);
            // 如果插入失败，返回在文档末尾创建的段落
            return document.createParagraph();
        }
    }

    /**
     * 处理第八章评定结果
     *
     * @param document Word文档
     * @param key      占位符
     * @param tasks    建筑物信息
     */
    private Map<Long, BiEvaluation> handleChapter8EvaluationResults(XWPFDocument document, String key, List<Task> tasks, String parentBuildingName) {
        try {
            log.info("开始处理第八章评定结果, key: {}", key);
            StringBuilder content = new StringBuilder();
            // 第一句话：依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，[桥梁名称]的技术状况评定结果如下：
            content.append("依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，")
                    .append(parentBuildingName)
                    .append("的技术状况评定结果如下：\n");
            Map<Long, BiEvaluation> biEvaluationMap = new HashMap<>();
            Integer minSystemLevel = Integer.MAX_VALUE;
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                BiEvaluation evaluation = biEvaluationService.selectBiEvaluationByTaskId(task.getId());
                Building building = task.getBuilding();
                String bridgeName = building != null ? building.getName() : "桥梁";
                if (evaluation != null) {
                    minSystemLevel = Math.min(minSystemLevel, evaluation.getSystemLevel());
                    biEvaluationMap.put(task.getId(), evaluation);
                    generateChapter8Content(evaluation, bridgeName, content);
                } else {
                    log.warn("未找到任务的评定结果: taskId={}", task.getId());
                    content.append("未找到").append(bridgeName).append("的评定结果。\n");
                }

            }
            // 第三句话：全桥技术状况评定按照评定单元最低分进行评定，因此评定为x类。
            content.append("全桥技术状况评定按照评定单元最低分进行评定，因此评定为")
                    .append(minSystemLevel)
                    .append("类。\n");

            // 第四句话：技术状况评定记录和具体评分见表10.1所示。
            content.append("技术状况评定记录和具体评分见下表所示。");
            // 插入四句话到文档中
            XWPFParagraph chapter8Paragraph = insertChapter8Content(document, key, content.toString());

            // 调用专门的服务在四句话后生成表格（包含分页符、横向设置和表格）
            for (Task task : tasks) {
                Building building = task.getBuilding();
                BiEvaluation evaluation = biEvaluationMap.get(task.getId());
                String bridgeName = building != null ? building.getName() : "桥梁";
                evaluationTableService.generateEvaluationTableAfterParagraph(document, chapter8Paragraph, building, evaluation, bridgeName);
            }
            log.info("第八章评定结果和表格处理完成");
            return biEvaluationMap;
        } catch (Exception e) {
            log.error("处理第八章评定结果失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理第八章评定结果
     *
     * @param document Word文档
     * @param key      占位符
     * @param building 建筑物信息
     * @param taskId   任务id
     */
    private void handleSingleBridgeEvaluationResults(XWPFDocument document, String key, Building building, Long taskId) {
        try {
            log.info("开始处理单桥评定结果, key: {}", key);

            // 查询评定结果
            BiEvaluation evaluation = biEvaluationService.selectBiEvaluationByTaskId(taskId);
            if (evaluation == null) {
                log.warn("未找到任务的评定结果: taskId={}", taskId);
                replaceText(document, key, "未找到评定结果");
                return;
            }

            // 获取桥梁名称
            String bridgeName = building != null && building.getName() != null ? building.getName() : "桥梁";

            // 生成 单桥评定结果文本
            String Content = generateSingleEvaluationContent(evaluation, bridgeName);

            // 插入到文档中
            XWPFParagraph Paragraph = insertChapter8Content(document, key, Content);

            // 调用专门的服务在四句话后生成表格（包含分页符、横向设置和表格）
            evaluationTableService.generateEvaluationTableAfterParagraph(document, Paragraph, building, evaluation, bridgeName, "12");

            log.info("评定结果和表格处理完成");

        } catch (Exception e) {
            log.error("处理评定结果失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 生成单桥评定结果内容
     *
     * @param evaluation 评定结果
     * @param bridgeName 桥梁名称
     * @return 第八章内容
     */
    private String generateSingleEvaluationContent(BiEvaluation evaluation, String bridgeName) {
        StringBuilder content = new StringBuilder();

        // 第一句话：依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，[桥梁名称]的技术状况评定结果如下：
        content.append("依据《公路桥梁技术状况评定标准》（JTG/T H21-2011）规定评定方法，")
                .append(bridgeName)
                .append("的技术状况评定结果如下：\n");

        // 第二句话：上部结构技术状况评分为xx分，等级为x类；下部结构技术状况评分为xx分，等级为x类；桥面系技术状况评分为xx分，等级为x类；全桥技术状况评分为xx分，评定为x类桥梁。
        content.append("上部结构技术状况评分为")
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

//        // 第三句话：全桥技术状况评定按照评定单元最低分进行评定，因此评定为x类。
//        content.append("全桥技术状况评定按照评定单元最低分进行评定，因此评定为")
//                .append(evaluation.getSystemLevel())
//                .append("类。\n");
        // 单桥不需要这句话。

        // 第四句话：技术状况评定记录和具体评分见表10.1所示。
        content.append("技术状况评定记录和具体评分见下表所示。");

        return content.toString();
    }


    /**
     * 生成第八章内容
     *
     * @param evaluation 评定结果
     * @param bridgeName 桥梁名称
     * @return 第八章内容
     */
    private String generateChapter8Content(BiEvaluation evaluation, String bridgeName, StringBuilder content) {
        // 第二句话：上部结构技术状况评分为xx分，等级为x类；下部结构技术状况评分为xx分，等级为x类；桥面系技术状况评分为xx分，等级为x类；全桥技术状况评分为xx分，评定为x类桥梁。
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
        return content.toString();
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
     * 插入第八章内容到文档中
     *
     * @param document 文档对象
     * @param key      占位符
     * @param content  第八章内容
     */
    private XWPFParagraph insertChapter8Content(XWPFDocument document, String key, String content) {
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

            // 清空占位符段落的所有Run
            while (targetParagraph.getRuns().size() > 0) {
                targetParagraph.removeRun(0);
            }

            // 设置段落格式
            targetParagraph.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT);

            // 设置1.5倍行距
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = targetParagraph.getCTP().getPPr();
            if (ppr == null) {
                ppr = targetParagraph.getCTP().addNewPPr();
            }
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
            spacing.setLine(java.math.BigInteger.valueOf(360));

            // 按行分割内容并在同一段落中添加多个Run
            String[] lines = content.split("\n");

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                if (i > 0) {
                    // 不是第一行时，先添加换行
                    XWPFRun breakRun = targetParagraph.createRun();
                    breakRun.addBreak();
                }

                // 创建文本Run
                XWPFRun textRun = targetParagraph.createRun();
                textRun.setText("\t" + line);
                textRun.setFontSize(12);
                textRun.setFontFamily("宋体");
            }

            log.info("第八章内容插入成功: {}, 插入{}行内容", key, lines.length);

            return targetParagraph;

        } catch (Exception e) {
            log.error("插入第八章内容失败: key={}", key, e);
            throw e;
        }
    }

    /**
     * 处理第九章比较分析
     *
     * @param document   Word文档
     * @param key        占位符
     * @param task       当前任务ID
     * @param bridgeName 桥梁名称
     */
    private void handleChapter9ComparisonAnalysis(XWPFDocument document, String key, Task task, String bridgeName, boolean isSingleBridege) {
        try {
            log.info("开始处理第九章比较分析, key: {}, taskId: {}", key, task.getId());

            // 查找占位符位置
            XWPFParagraph targetParagraph = null;
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && text.contains(key)) {
                    targetParagraph = paragraph;
                    break;
                }
            }

            if (targetParagraph == null) {
                log.warn("未找到第九章占位符: {}", key);
                return;
            }

            // 调用比较分析服务生成表格
            comparisonAnalysisService.generateComparisonAnalysisTable(document, targetParagraph, task, bridgeName, isSingleBridege);

            log.info("第九章比较分析处理完成");

        } catch (Exception e) {
            log.error("处理第九章比较分析失败: key={}, taskId={}, error={}", key, task.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理第九章比较分析（多桥版本）
     *
     * @param document Word文档
     * @param key      占位符
     * @param tasks    任务列表（同一组合桥下的所有子桥）
     */
    private void handleChapter9ComparisonAnalysisForMultiBridge(XWPFDocument document, String key, List<Task> tasks) {
        try {
            log.info("开始处理第九章比较分析（多桥版本）, key: {}, 任务数量: {}", key, tasks.size());

            // 查找占位符位置
            XWPFParagraph targetParagraph = null;
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && text.contains(key)) {
                    targetParagraph = paragraph;
                    break;
                }
            }

            if (targetParagraph == null) {
                log.warn("未找到第九章占位符: {}", key);
                return;
            }

            // 调用比较分析服务生成多桥表格
            comparisonAnalysisService.generateMultiBridgeComparisonAnalysisTable(document, targetParagraph, tasks);

            log.info("第九章比较分析（多桥版本）处理完成");

        } catch (Exception e) {
            log.error("处理第九章比较分析（多桥版本）失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理第十章检测结论
     *
     * @param document   Word文档
     * @param key        占位符
     * @param bridgeName 桥梁名称
     */
    private void handleChapter10TestConclusion(XWPFDocument document, String key, List<Task> tasks, String bridgeName, Map<Long, BiEvaluation> biEvaluationMap, Integer minSystemLevel) {
        try {
            log.info("开始处理第十章检测结论, key: {}", key);

            // 查找占位符位置
            XWPFParagraph targetParagraph = findParagraphByPlaceholder(document, key);
            if (targetParagraph == null) {
                log.warn("未找到第十章检测结论占位符: {}", key);
                return;
            }

            // 调用检测结论服务处理检测结论
            testConclusionService.handleTestConclusion(document, targetParagraph, tasks, bridgeName, biEvaluationMap, minSystemLevel);

            log.info("第十章检测结论处理完成");

        } catch (Exception e) {
            log.error("处理第十章检测结论失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 处理第十章检测结论桥梁详情
     *
     * @param document Word文档
     * @param key      占位符
     * @param tasks    当前任务
     */
    private void handleChapter10TestConclusionBridge(XWPFDocument document, String key, List<Task> tasks) {
        try {
            log.info("开始处理第十章检测结论桥梁详情, key: {}", key);

            // 查找占位符位置
            XWPFParagraph targetParagraph = findParagraphByPlaceholder(document, key);
            if (targetParagraph == null) {
                log.warn("未找到第十章检测结论桥梁详情占位符: {}", key);
                return;
            }

            // 调用检测结论服务处理检测结论桥梁详情
            testConclusionService.handleTestConclusionBridge(document, targetParagraph, tasks);

            log.info("第十章检测结论桥梁详情处理完成");

        } catch (Exception e) {
            log.error("处理第十章检测结论桥梁详情失败: key={}, error={}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 根据占位符查找段落
     */
    private XWPFParagraph findParagraphByPlaceholder(XWPFDocument document, String placeholder) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            if (text != null && text.contains(placeholder)) {
                return paragraph;
            }
        }
        return null;
    }

    /**
     * 生成单桥报告文档（完整版本）
     *
     * @param report 报告
     * @param task   任务
     * @return MinIO文件ID
     */
    private String generateSingleBridgeReportDocument(Report report, Task task,
                                                      ReportTemplateTypes templateType, String operator) {
        Long buildingId = task.getBuildingId();
        InputStream templateStream = null;
        FileOutputStream out = null;
        XWPFDocument document = null;
        File outputFile = null;

        try {
            if (report == null) {
                log.error("报告对象为空");
                return null;
            }

            // 查询建筑物信息
            Building building = null;
            if (buildingId != null) {
                building = buildingService.selectBuildingById(buildingId);
            }
            if (building == null) {
                log.error("未找到指定的建筑物，buildingId: {}", buildingId);
                return null;
            }

            // 查询项目信息
            Project project = null;
            if (report.getProjectId() != null) {
                project = projectService.selectProjectById(report.getProjectId());
            }
            log.info("单桥报告 - 项目: {}, 建筑: {}", project != null ? project.getName() : "无", building.getName());

            // 查询报告模板信息
            ReportTemplate template = reportTemplateService.selectReportTemplateById(report.getReportTemplateId());
            if (template == null || template.getMinioId() == null) {
                log.error("未找到报告模板或模板MinioId为空");
                return null;
            }

            // 通过模板文件ID获取文件信息
            FileMap fileMap = fileMapService.selectFileMapById(template.getMinioId());
            if (fileMap == null) {
                log.error("未找到模板文件映射");
                return null;
            }

            // 从Minio下载模板文件
            String fileName = fileMap.getNewName();
            templateStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(fileName.substring(0, 2) + "/" + fileName)
                            .build()
            );

            // 加载Word文档
            document = new XWPFDocument(templateStream);
            log.info("成功加载单桥模板Word文档");

            // 重置域计数器
            WordFieldUtils.resetCounters();

            // 查询报告数据
            ReportData queryParam = new ReportData();
            queryParam.setReportId(report.getId());
            List<ReportData> reportDataList = reportDataService.selectReportDataList(queryParam);
            log.info("查询到报告数据记录数: {}", reportDataList.size());

            // 创建数据映射（用于快速查找）
            Map<String, ReportData> dataMap = new HashMap<>();
            for (ReportData data : reportDataList) {
                dataMap.put(data.getKey(), data);
            }

            // 获取根对象信息
            BiObject biObject = biObjectMapper.selectBiObjectById(building.getRootObjectId());

            // 1. 替换日期占位符
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            replaceText(document, "${year}", String.valueOf(year));
            replaceText(document, "${month}", String.valueOf(month));
            replaceText(document, "${day}", String.valueOf(day));
            log.info("已替换日期占位符: {}-{}-{}", year, month, day);

            // 2. 替换项目相关信息
            if (project != null) {
                replaceText(document, "${project-name}", project.getName() != null ? project.getName() : "");
                if (project.getDept() != null && project.getDept().getDeptName() != null) {
                    replaceText(document, "${client-unit}", project.getDept().getDeptName());
                }
            }

            // 3. 替换建筑物相关信息
            replaceText(document, "${building-name}", building.getName() != null ? building.getName() : "");
            log.info("已替换项目和建筑物信息");

            BiEvaluation biEvaluation = biEvaluationService.selectBiEvaluationByTaskId(task.getId());
            if (ObjectUtils.isNotEmpty(biEvaluation)) {
                replaceText(document, "${4-1-7-1-evaluationLevel}", String.valueOf(biEvaluation.getSystemLevel()) + "类");
            }
            // 清空第十章病害汇总缓存
            testConclusionService.clearDiseaseSummaryCache();

            // 处理 最后的 定期检查记录表
            try {
                // 调用定期检查记录表服务处理占位符
                regularInspectionService.fillSingleBridgeRegularInspectionTable(document, building, task, project, templateType);
            } catch (Exception e) {
                log.error("处理定期检查记录表失败: error={}", e.getMessage(), e);
            }

            // 4. 处理桥梁基本状况卡片数据 和 桥梁概况 数据
            // 11.11  修改 ， 处理桥梁状况卡片 时 顺带 处理 桥梁概况数据。
            try {
                processBridgeBasicInfoCard(document, building, templateType, biEvaluation.getSystemLevel());
                log.info("桥梁基本状况卡片处理完成");
            } catch (Exception e) {
                log.error("处理桥梁基本状况卡片出错: error={}", e.getMessage(), e);
            }


            // 5. 先处理自动生成的章节内容（包括从数据库自动获取的照片）
            processSingleBridgeAutoGeneratedContent(document, building, project, task, biObject, dataMap, templateType);

            // 6. 再处理用户填写的简单数据
            processSingleBridgeUserData(document, reportDataList, building, dataMap, project, biObject);

            // 10. 更新文档中的所有域（图表自动编号）
            WordFieldUtils.updateAllFields(document);
            log.info("已更新文档域");

            // 11. 保存文档到临时文件
            String docFileName = report.getName() + "_" + System.currentTimeMillis() + ".docx";
            outputFile = File.createTempFile("single_bridge_report_" + report.getId(), ".docx");
            out = new FileOutputStream(outputFile);
            document.write(out);
            out.close();
            log.info("报告文档已保存到临时文件: {}", outputFile.getAbsolutePath());

            // 12. 上传到MinIO
            FileMap reportFileMap = fileMapService.handleFileUploadFromFile(
                    outputFile,
                    docFileName,
                    operator
            );
            log.info("报告文档已上传到MinIO，文件ID: {}", reportFileMap.getId());

            // 13. 更新报告状态
            report.setMinioId(Long.valueOf(reportFileMap.getId()));
            report.setStatus(1); // 已生成
            reportMapper.updateReport(report);

            return reportFileMap.getId().toString();

        } catch (Exception e) {
            log.error("生成单桥报告失败", e);
            return null;
        } finally {
            testConclusionService.removeDiseaseSummaryCache();
            WordFieldUtils.removeCounters();
            // 清理资源
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
                if (outputFile != null && outputFile.exists()) {
                    boolean deleted = outputFile.delete();
                    if (!deleted) {
                        log.warn("临时文件删除失败: {}", outputFile.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                log.error("清理资源失败", e);
            }
        }
    }


    /**
     * 设置段落为单倍行距（用于表格单元格）
     * <p>
     * 单倍行距（240 Twips = 1.0倍）可以使表格内容更紧凑，
     * 相比默认的1.15倍行距，可减少约13%的行高。
     * </p>
     *
     * @param paragraph 需要设置行距的段落
     */
    private void setSingleLineSpacing(XWPFParagraph paragraph) {
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }

        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        // 设置单倍行距：240 Twips = 1.0倍
        spacing.setLine(BigInteger.valueOf(240));
        spacing.setLineRule(STLineSpacingRule.AUTO);
    }

    private void setCellVerticalCenter(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTVerticalJc vAlign = tcPr.isSetVAlign() ? tcPr.getVAlign() : tcPr.addNewVAlign();
        vAlign.setVal(STVerticalJc.CENTER);
    }

    /**
     * 设置Run的中英文字体（英文Times New Roman，中文宋体）
     * <p>
     * 英文和数字使用Times New Roman，中文使用宋体。
     * 字号默认为10.5pt（21 half-points）。
     * </p>
     *
     * @param run 需要设置字体的Run对象
     */
    private void setMixedFontFamily(XWPFRun run) {
        setMixedFontFamily(run, 21); // 默认10.5pt = 21 half-points
    }

    /**
     * 设置Run的中英文字体（英文Times New Roman，中文宋体），并指定字号
     * <p>
     * 英文和数字使用Times New Roman，中文使用宋体。
     * </p>
     *
     * @param run      需要设置字体的Run对象
     * @param fontSize 字号（half-points，例如21表示10.5pt，20表示10pt）
     */
    private void setMixedFontFamily(XWPFRun run, int fontSize) {
        CTRPr rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();

        // 设置字体（直接添加，不检查isSet）
        CTFonts fonts = rpr.addNewRFonts();
        fonts.setAscii("Times New Roman");        // ASCII字符（英文字母、数字、标点）
        fonts.setHAnsi("Times New Roman");        // 高位ANSI字符（英文）
        fonts.setEastAsia("宋体");                 // 东亚字符（中文）

        // 设置字号（直接添加，不检查isSet）
        CTHpsMeasure sz = rpr.addNewSz();
        sz.setVal(BigInteger.valueOf(fontSize));

        CTHpsMeasure szCs = rpr.addNewSzCs();
        szCs.setVal(BigInteger.valueOf(fontSize));
    }

    /**
     * 设置表格标题行在跨页时重复显示
     * <p>
     * 当表格内容跨越多页时，会在每个新页面的顶部自动重复显示标题行，
     * 这对于长表格非常有用，可以让读者始终看到列标题。
     * </p>
     *
     * @param headerRow 需要设置为重复标题的表格行（通常是第一行）
     */
    private void setTableHeaderRepeat(XWPFTableRow headerRow) {
        CTTrPr trPr = headerRow.getCtRow().getTrPr();
        if (trPr == null) {
            trPr = headerRow.getCtRow().addNewTrPr();
        }

        // 设置 tblHeader 属性，标记该行为表头
        // 直接添加tblHeader元素即表示启用
        trPr.addNewTblHeader();
    }

    /**
     * 设置表格的多行标题在跨页时重复显示
     * <p>
     * 适用于有多行表头的复杂表格。
     * </p>
     *
     * @param table          表格对象
     * @param headerRowCount 标题行数量（从第0行开始计数）
     */
    private void setTableHeaderRepeat(XWPFTable table, int headerRowCount) {
        for (int i = 0; i < headerRowCount && i < table.getRows().size(); i++) {
            setTableHeaderRepeat(table.getRow(i));
        }
    }

    /**
     * 克隆报告
     *
     * @param id 需要克隆的报告ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cloneReport(Long id) {
        // 1. 查询原报告
        Report sourceReport = reportMapper.selectReportById(id);
        if (sourceReport == null) {
            throw new RuntimeException("源报告不存在");
        }

        // 2. 创建新报告对象，复制所有字段
        Report newReport = new Report();
        newReport.setName(sourceReport.getName() + "_副本");
        newReport.setProjectId(sourceReport.getProjectId());
        newReport.setTaskIds(sourceReport.getTaskIds());
        newReport.setReportTemplateId(sourceReport.getReportTemplateId());
        newReport.setStatus(0);
        newReport.setReviewerId(sourceReport.getReviewerId());
        newReport.setReviewer(sourceReport.getReviewer());
        newReport.setApproverId(sourceReport.getApproverId());
        newReport.setApprover(sourceReport.getApprover());
        newReport.setRemark(sourceReport.getRemark());
        newReport.setCreateBy(ShiroUtils.getLoginName());

        // 3. 插入新报告
        int rows = reportMapper.insertReport(newReport);
        if (rows <= 0) {
            throw new RuntimeException("克隆报告失败");
        }

        // 4. 查询原报告的所有数据
        List<ReportData> sourceDataList = reportDataService.selectReportDataByReportId(id);

        // 5. 克隆报告数据
        if (!sourceDataList.isEmpty()) {
            List<ReportData> newDataList = new ArrayList<>();

            for (ReportData sourceData : sourceDataList) {
                ReportData newData = new ReportData();
                newData.setKey(sourceData.getKey());
                newData.setReportId(newReport.getId());
                newData.setType(sourceData.getType());
                newData.setRemark(sourceData.getRemark());
                newData.setCreateBy(ShiroUtils.getLoginName());

                // 根据类型处理value字段
                if (sourceData.getType() != null && sourceData.getType() == 1) {
                    // 图片类型，需要克隆文件
                    if (sourceData.getValue() != null && !sourceData.getValue().isEmpty()) {
                        String[] minioIds = sourceData.getValue().split(",");
                        List<String> newMinioIds = new ArrayList<>();

                        for (String minioIdStr : minioIds) {
                            if (!minioIdStr.isEmpty()) {
                                try {
                                    Long minioId = Long.parseLong(minioIdStr.trim());
                                    // 调用copyFile方法克隆文件
                                    FileMap newFileMap = fileMapService.copyFile(minioId);
                                    newMinioIds.add(String.valueOf(newFileMap.getId()));
                                } catch (Exception e) {
                                    log.error("克隆图片文件失败，minioId: " + minioIdStr, e);
                                }
                            }
                        }

                        // 设置新的minioId列表
                        newData.setValue(String.join(",", newMinioIds));
                    }
                } else {
                    // 文本类型，直接复制
                    newData.setValue(sourceData.getValue());
                }

                newDataList.add(newData);
            }

            // 6. 批量插入新的报告数据
            if (!newDataList.isEmpty()) {
                reportDataService.batchInsertReportData(newDataList);
            }
        }

        return rows;
    }

    /**
     * 处理单桥模板用户填写的数据（仅处理文本，照片由系统自动处理）
     */
    private void processSingleBridgeUserData(XWPFDocument document, List<ReportData> reportDataList,
                                             Building building, Map<String, ReportData> dataMap, Project project, BiObject biObject) {
        String manualTechnicalAdvice = null;
        boolean sawTechnicalAdvice = false;
        boolean technicalAdviceGenerated = false;
        for (ReportData data : reportDataList) {
            String key = data.getKey();
            String value = data.getValue();
            Integer type = data.getType();
            try {
                // 跳过照片相关的字段，照片由系统自动处理
                if (key != null && (key.contains("leftFront") || key.contains("rightFront") ||
                        key.contains("leftSide") || key.contains("rightSide"))) {
                    log.debug("跳过照片字段（由系统自动处理）: {}", key);
                    continue;
                }

                if (type == 0) {
                    // 文本类型
                    // 检查是否是病害选择字段，需要特殊处理
                    if (key != null && key.contains("chapter-4-1-6-diseases")) {
                        try {
                            log.info("开始处理单桥病害数据，key: {}, value: {}", key, value);
                            // 解析病害数据
                            List<ComponentDiseaseType> combinations = parseChapter7JsonForSingleBridge(value);
                            if (!combinations.isEmpty()) {
                                // 成因分析写入重点关注病害，处治建议写入技术建议
                                technicalAdviceGenerated = generateSingleBridgeFocusOnDiseasesWithCauseMatch(
                                        document, combinations, building, project, biObject);
                                log.info("单桥病害分析生成完成");
                            } else {
                                log.warn("病害数据解析为空");
                                replaceText(document, "${chapter-4-1-6-focusOnDiseases}", "无重点关注病害");
                            }
                        } catch (Exception e) {
                            log.error("处理单桥病害数据出错: key={}, value={}, error={}", key, value, e.getMessage(), e);
                            replaceText(document, "${chapter-4-1-6-focusOnDiseases}", "【病害数据处理失败，请联系管理员】");
                        }
                    } else if ("chapter-4-1-7-4-technicalAdvice".equals(key)) {
                        // 有病害选择时由匹配结果生成；这里先记下手工内容，避免先把占位符清掉
                        sawTechnicalAdvice = true;
                        manualTechnicalAdvice = value;
                    } else {
                        // 普通文本字段 - 直接替换
                        String placeholder = "${" + key + "}";
                        replaceText(document, placeholder, value != null ? value : "");
                        log.debug("替换文本字段: {} = {}", key, value != null ? value : "(空)");
                    }
                }
            } catch (Exception e) {
                log.error("处理字段出错: key={}, type={}, error={}", key, type, e.getMessage(), e);
            }
        }
        if (!technicalAdviceGenerated && sawTechnicalAdvice) {
            replaceText(document, "${chapter-4-1-7-4-technicalAdvice}",
                    manualTechnicalAdvice != null ? manualTechnicalAdvice : "");
        }
    }

    /**
     * 处理单桥模板自动生成的内容
     */
    private void processSingleBridgeAutoGeneratedContent(XWPFDocument document, Building building,
                                                         Project project, Task task, BiObject biObject,
                                                         Map<String, ReportData> dataMap, ReportTemplateTypes templateType) {
        // 0. 处理桥梁概况照片（从数据库自动获取，与组合桥同步）
        try {
            insertSingleBridgeImages(document, building.getId());
            log.info("单桥桥梁概况照片处理完成");
        } catch (Exception e) {
            log.error("处理单桥桥梁概况照片出错: error={}", e.getMessage(), e);
            // 照片处理失败不影响其他内容生成，只记录日志
        }

        // 1. 处理第4.1.2章 - 外观检测结果（从数据库自动生成）
        try {
            processSingleBridgeAppearanceInspection(document, biObject, building, project.getId(),
                    "${chapter-4-1-2-appearanceInspectionResults}",
                    templateType == ReportTemplateTypes.LEVEL_2_ARCH_BRIDGE);
            log.info("第4.1.2章外观检测结果生成完成");
        } catch (Exception e) {
            log.error("处理第4.1.2章外观检测结果出错: error={}", e.getMessage(), e);
            replaceText(document, "${chapter-4-1-2-appearanceInspectionResults}", "【外观检测结果生成失败，请联系管理员】");
        }


        // 2. 处理第4.1.5章 - 技术状况评定（自动生成）
        try {
            handleSingleBridgeEvaluationResults(document, "${chapter-4-1-4-evaluationResults}", building, task.getId());
            log.info("第4.1.4章技术状况评定生成完成");
        } catch (Exception e) {
            log.error("处理第4.1.4章技术状况评定出错: error={}", e.getMessage(), e);
            replaceText(document, "${chapter-4-1-4-evaluationResults}", "【技术状况评定生成失败，请联系管理员】");
        }

        // 3. 处理第4.1.6章 - 近年评定结果对比（自动生成）
        try {
            handleChapter9ComparisonAnalysis(document, "${chapter-4-1-5-comparativeAnalysisOfEvaluationResults}", task, building.getName(), true);
            log.info("第4.1.5章近年评定结果对比生成完成");
        } catch (Exception e) {
            log.error("处理第4.1.5章近年评定结果对比出错: error={}", e.getMessage(), e);
            replaceText(document, "${chapter-4-1-5-comparativeAnalysisOfEvaluationResults}", "【近年评定结果对比生成失败，请联系管理员】");
        }

        // 4. 第4.1.7章病害分析已在processSingleBridgeUserData中处理，此处跳过

        // 5. 处理第4.1.8.2章 - 主要病害（新格式）
        try {
            generateSingleBridgeMainDiseases(document, building, project, task);
            log.info("第4.1.7.2章主要病害生成完成");
        } catch (Exception e) {
            log.error("处理第4.1.7.2章主要病害出错: error={}", e.getMessage(), e);
            replaceText(document, "${chapter-4-1-7-2-mainDiseases}", "【主要病害生成失败，请联系管理员】");
        }
    }

    /**
     * 处理桥梁基本状况卡片数据
     */
    private void processBridgeBasicInfoCard(XWPFDocument document, Building building, ReportTemplateTypes templateType, Integer minSystemLevel) {
        try {
            // 使用现有的桥梁卡片服务处理基本信息
            bridgeCardService.processBridgeCardData(document, building, templateType, minSystemLevel);
            log.info("桥梁基本状况卡片数据处理完成");
        } catch (Exception e) {
            log.error("处理桥梁基本状况卡片数据失败", e);
            // 对于卡片数据处理失败，不影响整体生成，只记录错误
        }
    }

    /**
     * 生成单桥重点关注病害内容
     */
    private void generateSingleBridgeFocusOnDiseases(XWPFDocument document, List<ComponentDiseaseType> combinations,
                                                     Building building, Project project, BiObject biObject) {
        try {
            StringBuilder content = new StringBuilder();

            // 1. 得到病害
            // 提取所有构件ID
            List<Long> componentIds = combinations.stream()
                    .map(ComponentDiseaseType::getComponentId)
                    .distinct()
                    .collect(Collectors.toList());

            // 批量查询病害数据
            List<Disease> allDiseases = diseaseMapper.selectDiseaseComponentData(
                    componentIds, building.getId(), project.getYear());

            // 2. 生成主要病害的描述和成因分析
            // 按照biObjectId 分类 ， 同时按照 diseaseTypeId 分组。
            // 分组逻辑
            Map<Long, Map<Long, List<Disease>>> groupedMap = allDiseases.stream()
                    .collect(Collectors.groupingBy(
                            Disease::getBiObjectId,
                            Collectors.groupingBy(Disease::getDiseaseTypeId)
                    ));
            int index = 1;
            for (Long biObjectId : groupedMap.keySet()) {
                Map<Long, List<Disease>> diseaseMap = groupedMap.get(biObjectId);
                BiObject tempBiObject = biObjectMapper.selectBiObjectById(biObjectId);
                for (Long diseaseTypeId : diseaseMap.keySet()) {
                    List<Disease> diseases = diseaseMap.get(diseaseTypeId);
                    Disease disease = diseases.get(0);
                    content.append(index).append(")");
                    content.append(tempBiObject.getName());
                    content.append(disease.getType().substring(disease.getType().lastIndexOf('#') + 1));
                    content.append("\n");
                    content.append("成因分析：\n");
                    disease.setBuildingId(building.getId());
                    content.append(getDiseaseCause(disease));
                    content.append("\n");
                    index++;
                }

            }

            // 替换占位符
            replaceText(document, "${chapter-4-1-6-focusOnDiseases}", content.toString().trim());

        } catch (Exception e) {
            log.error("生成单桥重点关注病害内容失败", e);
            replaceText(document, "${chapter-4-1-6-focusOnDiseases}", "【病害分析生成失败】");
        }
    }

    /**
     * 生成单桥重点关注病害和技术建议。成因分析写入重点关注病害，处治建议按正文去重后写入技术建议。
     * 调用 Springai-Rag 的 /api-ai/disease/cause-match。未匹配写红字，并在括号中注明病害。
     *
     * @return 技术建议已由匹配结果写入
     */
    private boolean generateSingleBridgeFocusOnDiseasesWithCauseMatch(XWPFDocument document, List<ComponentDiseaseType> combinations,
                                                                      Building building, Project project, BiObject biObject) {
        try {
            List<String> titles = new ArrayList<>();
            List<Disease> representatives = new ArrayList<>();
            List<Integer> levels = new ArrayList<>();
            collectCauseMatchGroups(combinations, building, project, titles, representatives, levels);
            if (titles.isEmpty()) {
                replaceText(document, "${chapter-4-1-6-focusOnDiseases}", "无重点关注病害");
                return false;
            }
            List<DiseaseCauseMatch> matches = diseaseCauseMatchService.matchAll(representatives, levels);
            List<List<ReportGenerateTools.TextSegment>> causeParagraphs = new ArrayList<>();
            List<String> suggestions = new ArrayList<>();
            for (int i = 0; i < titles.size(); i++) {
                DiseaseCauseMatch match = i < matches.size() ? matches.get(i) : null;
                causeParagraphs.addAll(ReportGenerateTools.causeAnalysisParagraphs(
                        i + 1, titles.get(i), match == null ? null : match.getCause()));
                suggestions.add(match == null ? null : match.getSuggestion());
            }
            ReportGenerateTools.writeBodyParagraphs(document, "${chapter-4-1-6-focusOnDiseases}", causeParagraphs);
            ReportGenerateTools.writeBodyParagraphs(document, "${chapter-4-1-7-4-technicalAdvice}",
                    ReportGenerateTools.technicalAdviceParagraphs(suggestions, titles));
            return true;
        } catch (Exception e) {
            log.error("生成单桥重点关注病害内容失败", e);
            replaceText(document, "${chapter-4-1-6-focusOnDiseases}", "【病害分析生成失败】");
            return false;
        }
    }

    private void collectCauseMatchGroups(List<ComponentDiseaseType> combinations, Building building, Project project,
                                         List<String> titles, List<Disease> representatives, List<Integer> levels) {
        List<Long> componentIds = combinations.stream()
                .map(ComponentDiseaseType::getComponentId)
                .distinct()
                .collect(Collectors.toList());
        Set<String> selectedKeys = combinations.stream()
                .filter(item -> item.getComponentId() != null && item.getDiseaseTypeId() != null)
                .map(item -> item.getComponentId() + "_" + item.getDiseaseTypeId())
                .collect(Collectors.toSet());
        List<Disease> loaded = diseaseMapper.selectDiseaseComponentData(
                componentIds, building.getId(), project.getYear());
        List<Disease> allDiseases = loaded == null ? List.of() : loaded.stream()
                .filter(disease -> disease.getBiObjectId() != null && disease.getDiseaseTypeId() != null
                        && selectedKeys.contains(disease.getBiObjectId() + "_" + disease.getDiseaseTypeId()))
                .collect(Collectors.toList());
        Map<Long, Map<Long, List<Disease>>> groupedMap = allDiseases.stream()
                .collect(Collectors.groupingBy(
                        Disease::getBiObjectId,
                        LinkedHashMap::new,
                        Collectors.groupingBy(Disease::getDiseaseTypeId, LinkedHashMap::new, Collectors.toList())
                ));
        Map<Long, BiObject> nodeMap = bridgeNodeMap(building);
        List<Map.Entry<Long, Long>> groups = new ArrayList<>();
        groupedMap.forEach((biObjectId, diseaseMap) -> diseaseMap.keySet()
                .forEach(diseaseTypeId -> groups.add(Map.entry(biObjectId, diseaseTypeId))));
        groups.sort((left, right) -> ReportGenerateTools.compareCauseGroup(
                left.getKey(), right.getKey(), left.getValue(), right.getValue(), nodeMap));
        for (Map.Entry<Long, Long> group : groups) {
            Long biObjectId = group.getKey();
            List<Disease> diseases = groupedMap.get(biObjectId).get(group.getValue());
            Disease disease = diseases.get(0);
            BiObject tempBiObject = nodeMap.get(biObjectId);
            if (tempBiObject == null) {
                tempBiObject = biObjectMapper.selectBiObjectById(biObjectId);
            }
            String objectName = tempBiObject != null && tempBiObject.getName() != null ? tempBiObject.getName() : "";
            titles.add(objectName + disease.getType().substring(disease.getType().lastIndexOf('#') + 1));
            representatives.add(disease);
            levels.add(diseases.stream().mapToInt(Disease::getLevel).max().getAsInt());
        }
    }

    private Map<Long, BiObject> bridgeNodeMap(Building building) {
        if (building == null || building.getRootObjectId() == null) {
            return Collections.emptyMap();
        }
        List<BiObject> nodes = biObjectMapper.selectBiObjectAndChildren(building.getRootObjectId());
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyMap();
        }
        return nodes.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(BiObject::getId, node -> node, (left, right) -> left));
    }

    /**
     * 生成单桥主要病害内容
     */
    private void generateSingleBridgeMainDiseases(XWPFDocument document, Building building, Project project, Task task) {
        try {
            // 11.14 修改 ， 使用病害小结的缓存。

            StringBuilder content = new StringBuilder();
            int structureIndex = 1;
            Map<Long, String> summaryCache = testConclusionService.getSummaryCache();
            List<Long> biObjectIdList = summaryCache.keySet().stream().toList();
            Map<String, List<Long>> parentObjectName2IdList = biObjectIdList.stream().collect(Collectors.groupingBy(a -> biObjectMapper.selectBiObjectById(a).getParentName()));
            for (String structureName : SingleReportMainDiseaseSummaryList.getAllStructureList()) {

                if (!parentObjectName2IdList.containsKey(structureName)) {
                    // 如果当前 结构 没有存放 病害小结 ， 跳过。
                    continue;
                }
                List<Long> curObjectIdList = parentObjectName2IdList.get(structureName);
                List<String> summaries = new ArrayList<>();
                for (Long objectId : curObjectIdList) {
                    String diseaseSummary = summaryCache.get(objectId);
                    if (isNoSuchComponentSummary(diseaseSummary)) {
                        continue;
                    }
                    summaries.add(diseaseSummary);
                }
                if (summaries.isEmpty()) {
                    continue;
                }
                content.append(String.format("%d、%s：\n", structureIndex++, structureName));
                int pointNum = 1;
                for (String diseaseSummary : summaries) {
                    content.append(String.format("%d）%s\n", pointNum++, diseaseSummary));
                }

            }
            writeSingleBridgeMainDiseases(document, content.toString().trim());

        } catch (Exception e) {
            log.error("生成单桥主要病害内容失败", e);
            replaceText(document, "${chapter-4-1-7-2-mainDiseases}", "【主要病害生成失败】");
        }
    }

    private void writeSingleBridgeMainDiseases(XWPFDocument document, String content) {
        XWPFParagraph target = ReportGenerateTools.findParagraphByPlaceholder(document, "${chapter-4-1-7-2-mainDiseases}");
        if (target == null) {
            replaceText(document, "${chapter-4-1-7-2-mainDiseases}", content);
            return;
        }
        while (!target.getRuns().isEmpty()) {
            target.removeRun(0);
        }
        if (content == null || content.isEmpty()) {
            return;
        }
        String[] lines = content.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                if (target.getRuns().isEmpty()) {
                    target.createRun().addBreak();
                } else {
                    target.getRuns().get(target.getRuns().size() - 1).addBreak();
                }
            }
            ReportGenerateTools.writeTextWithAreaSubscripts(target, lines[i], 24);
        }
    }

    private boolean isNoSuchComponentSummary(String summary) {
        if (summary == null) {
            return false;
        }
        String trimmed = summary.trim();
        return TestConclusionService.NO_SUCH_COMPONENT_TEXT.equals(trimmed) || trimmed.contains("无此构件");
    }

    /**
     * 按结构类型分组病害（基于BiObject祖先数组第三层）
     */
    private Map<String, List<Disease>> groupDiseasesByStructure(List<Disease> diseases) {
        Map<String, List<Disease>> groups = new LinkedHashMap<>();

        try {
            // 收集所有需要查询的BiObject ID
            Set<Long> biObjectIds = new HashSet<>();
            for (Disease disease : diseases) {
                if (disease.getBiObjectId() != null) {
                    biObjectIds.add(disease.getBiObjectId());
                }
            }

            if (biObjectIds.isEmpty()) {
                return groups;
            }

            // 批量查询BiObject
            List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(biObjectIds));

            // 收集所有第三层节点ID
            Set<Long> thirdLevelNodeIds = new HashSet<>();
            Map<Long, Long> componentToThirdLevelMap = new HashMap<>();

            for (BiObject biObject : biObjects) {
                String ancestors = biObject.getAncestors();
                if (ancestors != null && !ancestors.isEmpty()) {
                    String[] ancestorIds = ancestors.split(",");
                    if (ancestorIds.length >= 3) {
                        // 第三层节点ID（索引为2，因为从0开始：0,桥梁,第三层）
                        try {
                            Long thirdLevelId = Long.parseLong(ancestorIds[2].trim());
                            thirdLevelNodeIds.add(thirdLevelId);
                            componentToThirdLevelMap.put(biObject.getId(), thirdLevelId);
                        } catch (NumberFormatException e) {
                            log.warn("解析第三层节点ID失败: {}", ancestorIds[2]);
                        }
                    }
                }
            }

            // 批量查询第三层节点信息
            if (!thirdLevelNodeIds.isEmpty()) {
                List<BiObject> thirdLevelNodes = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(thirdLevelNodeIds));
                Map<Long, String> thirdLevelNameMap = thirdLevelNodes.stream()
                        .collect(Collectors.toMap(BiObject::getId, BiObject::getName));

                // 初始化分组（动态创建分组）
                for (String nodeName : thirdLevelNameMap.values()) {
                    groups.put(nodeName, new ArrayList<>());
                }

                // 分组病害
                for (Disease disease : diseases) {
                    if (disease.getBiObjectId() != null) {
                        Long thirdLevelId = componentToThirdLevelMap.get(disease.getBiObjectId());
                        if (thirdLevelId != null) {
                            String thirdLevelName = thirdLevelNameMap.get(thirdLevelId);
                            if (thirdLevelName != null && groups.containsKey(thirdLevelName)) {
                                groups.get(thirdLevelName).add(disease);
                            } else {
                                // 如果找不到对应的第三层节点，创建一个默认分组
                                String defaultGroup = "其他结构";
                                groups.computeIfAbsent(defaultGroup, k -> new ArrayList<>()).add(disease);
                            }
                        } else {
                            // 如果无法确定第三层节点，创建一个默认分组
                            String defaultGroup = "其他结构";
                            groups.computeIfAbsent(defaultGroup, k -> new ArrayList<>()).add(disease);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("按结构类型分组病害失败", e);
            // 异常情况下，创建一个默认分组
            String defaultGroup = "未分类结构";
            groups.put(defaultGroup, new ArrayList<>(diseases));
        }

        return groups;
    }


    /**
     * 清理和标准化结构级别的AI生成病害描述
     */
    private String cleanAndStandardizeStructureAiDescription(String aiDescription, String structureType) {
        if (aiDescription == null || aiDescription.trim().isEmpty()) {
            return "";
        }

        String cleaned = aiDescription.trim();

        // 移除可能的结构类型重复
        cleaned = cleaned.replaceAll("^" + structureType + "[:：]?\\s*", "");

        // 确保每行都有正确的序号格式
        String[] lines = cleaned.split("\\r?\\n");
        StringBuilder result = new StringBuilder();
        int index = 1;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 移除可能的原有序号
            line = line.replaceAll("^\\d+[）)]\\s*", "");

            if (!line.isEmpty()) {
                result.append(String.format("%d）", index++));
                result.append(line);

                // 确保以分号结尾
                if (!line.endsWith("；") && !line.endsWith(";")) {
                    result.append("；");
                }
                result.append("\n");
            }
        }

        return result.toString();
    }


    /**
     * 处理单桥模板的外观检测结果（跳过桥名层级，直接生成结构内容）
     */
    private void processSingleBridgeAppearanceInspection(XWPFDocument document, BiObject biObject,
                                                         Building building, Long projectId, String placeholder,
                                                         boolean omitArchUpperBearing) throws Exception {
        // 获取所有子部件
        List<BiObject> allObjects = biObjectMapper.selectChildrenById(building.getRootObjectId());

        // 找到占位符所在的段落
        XWPFParagraph placeholderParagraph = null;
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            if (paragraph.getText().contains(placeholder)) {
                placeholderParagraph = paragraph;
                break;
            }
        }

        // 如果找不到占位符，直接返回
        if (placeholderParagraph == null) {
            log.warn("未找到占位符: {}", placeholder);
            return;
        }

        // 获取占位符段落的XML游标
        XmlCursor cursor = placeholderParagraph.getCTP().newCursor();

        // 收集病害数据
        Disease queryParam = new Disease();
        queryParam.setBuildingId(building.getId());
        queryParam.setProjectId(projectId);
        List<Disease> diseases = diseaseMapper.selectDiseaseList(queryParam);
        hydrateDiseaseDetails(diseases);

        // 为桥梁收集病害
        Map<Long, List<Disease>> bridgeDiseaseMap = new LinkedHashMap<>();
        collectDiseases(biObject, allObjects, diseases, 3, bridgeDiseaseMap);

        AtomicInteger imageCounter = new AtomicInteger(1);
        AtomicInteger tableCounter = new AtomicInteger(1);

        // 直接生成结构内容，跳过桥名层级
        // 获取桥梁的直接子节点（通常是上部结构、下部结构、桥面系等）
        List<BiObject> structureNodes = allObjects.stream()
                .filter(obj -> biObject.getId().equals(obj.getParentId()))
                .collect(Collectors.toList());

        // 先删除占位符段落（在插入内容之前删除，避免索引错位）
        // 找到占位符段落在body中的实际位置
        int placeholderIndex = -1;
        for (int i = 0; i < document.getBodyElements().size(); i++) {
            if (document.getBodyElements().get(i) instanceof XWPFParagraph) {
                XWPFParagraph p = (XWPFParagraph) document.getBodyElements().get(i);
                if (p == placeholderParagraph) {
                    placeholderIndex = i;
                    break;
                }
            }
        }

        // 删除占位符段落
        if (placeholderIndex >= 0) {
            document.removeBodyElement(placeholderIndex);
            log.info("已删除占位符段落，索引: {}", placeholderIndex);
        }

        // 为每个结构节点生成内容（在删除占位符后，cursor仍然指向正确的位置）
        for (BiObject structureNode : structureNodes) {
            if ("附属设施".equals(structureNode.getName())) {
                continue;
            }

            // 生成结构内容，从第2层开始（跳过桥名层级）
            writeBiObjectTreeToWordForSingleBridge(document, structureNode, allObjects, bridgeDiseaseMap, 2
                    , imageCounter, tableCounter, cursor, 2, omitArchUpperBearing);
        }
    }

    /**
     * 按结构类型分组构件病害类型组合（基于BiObject祖先数组第三层）
     */
    private Map<String, List<ComponentDiseaseType>> groupByStructureType(List<ComponentDiseaseType> combinations) {
        Map<String, List<ComponentDiseaseType>> groups = new LinkedHashMap<>();

        try {
            // 收集所有需要查询的BiObject ID
            Set<Long> componentIds = new HashSet<>();
            for (ComponentDiseaseType combination : combinations) {
                if (combination.getComponentId() != null) {
                    componentIds.add(combination.getComponentId());
                }
            }

            if (componentIds.isEmpty()) {
                return groups;
            }

            // 批量查询BiObject
            List<BiObject> components = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(componentIds));

            // 收集所有第三层节点ID
            Set<Long> thirdLevelNodeIds = new HashSet<>();
            Map<Long, Long> componentToThirdLevelMap = new HashMap<>();

            for (BiObject component : components) {
                String ancestors = component.getAncestors();
                if (ancestors != null && !ancestors.isEmpty()) {
                    String[] ancestorIds = ancestors.split(",");
                    if (ancestorIds.length >= 3) {
                        // 第三层节点ID（索引为2，因为从0开始：0,桥梁,第三层）
                        try {
                            Long thirdLevelId = Long.parseLong(ancestorIds[2].trim());
                            thirdLevelNodeIds.add(thirdLevelId);
                            componentToThirdLevelMap.put(component.getId(), thirdLevelId);
                        } catch (NumberFormatException e) {
                            log.warn("解析第三层节点ID失败: {}", ancestorIds[2]);
                        }
                    }
                }
            }

            // 批量查询第三层节点信息
            if (!thirdLevelNodeIds.isEmpty()) {
                List<BiObject> thirdLevelNodes = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(thirdLevelNodeIds));
                Map<Long, String> thirdLevelNameMap = thirdLevelNodes.stream()
                        .collect(Collectors.toMap(BiObject::getId, BiObject::getName));

                // 初始化分组（动态创建分组）
                for (String nodeName : thirdLevelNameMap.values()) {
                    groups.put(nodeName, new ArrayList<>());
                }

                // 分组病害组合
                for (ComponentDiseaseType combination : combinations) {
                    if (combination.getComponentId() != null) {
                        Long thirdLevelId = componentToThirdLevelMap.get(combination.getComponentId());
                        if (thirdLevelId != null) {
                            String thirdLevelName = thirdLevelNameMap.get(thirdLevelId);
                            if (thirdLevelName != null && groups.containsKey(thirdLevelName)) {
                                groups.get(thirdLevelName).add(combination);
                            } else {
                                // 如果找不到对应的第三层节点，创建一个默认分组
                                String defaultGroup = "其他结构";
                                groups.computeIfAbsent(defaultGroup, k -> new ArrayList<>()).add(combination);
                            }
                        } else {
                            // 如果无法确定第三层节点，创建一个默认分组
                            String defaultGroup = "其他结构";
                            groups.computeIfAbsent(defaultGroup, k -> new ArrayList<>()).add(combination);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("按结构类型分组构件病害类型组合失败", e);
            // 异常情况下，创建一个默认分组
            String defaultGroup = "未分类结构";
            groups.put(defaultGroup, new ArrayList<>(combinations));
        }

        return groups;
    }

    /**
     * 处理单桥桥梁概况照片（与组合桥同步）
     *
     * @param document   Word文档
     * @param buildingId 建筑物ID
     * @throws Exception 异常
     */
    private void insertSingleBridgeImages(XWPFDocument document, Long buildingId) throws Exception {
        log.info("开始处理单桥桥梁概况照片，buildingId: {}", buildingId);

        List<FileMap> images = fileMapController.getImageMaps(buildingId, "newfront", "newside");
        log.info("从数据库获取到 {} 张图片", images != null ? images.size() : 0);
        String[] front = slotBridgeOverviewImages(images, "newfront");
        String[] side = slotBridgeOverviewImages(images, "newside");
        String leftFront = leftOverviewImage(front);
        String rightFront = rightOverviewImage(front);
        String leftSide = leftOverviewImage(side);
        String rightSide = rightOverviewImage(side);

        if (leftFront != null) {
            replaceImageInDocument(document, "%{chapter-4-1-1-leftFront}", leftFront);
            replaceImageInDocument(document, "%{chapter-4-1-1-rightFront}", rightFront);
        } else {
            replaceText(document, "%{chapter-4-1-1-leftFront}", "");
            replaceText(document, "%{chapter-4-1-1-rightFront}", "");
        }
        if (leftSide != null) {
            replaceImageInDocument(document, "%{chapter-4-1-1-leftSide}", leftSide);
            replaceImageInDocument(document, "%{chapter-4-1-1-rightSide}", rightSide);
        } else {
            replaceText(document, "%{chapter-4-1-1-leftSide}", "");
            replaceText(document, "%{chapter-4-1-1-rightSide}", "");
        }
        log.info("单桥桥梁概况照片处理完成 - 正面照: {}/{}, 立面照: {}/{}",
                front[0] != null, front[1] != null, side[0] != null, side[1] != null);
    }

    /**
     * 编辑页左槽是 0_newfront/0_newside，右槽是 1_。不能按数据库返回顺序取第一张。
     */
    private String[] slotBridgeOverviewImages(List<FileMap> images, String type) {
        String[] slots = new String[2];
        if (images == null) {
            return slots;
        }
        for (FileMap image : images) {
            if (image == null || image.getOldName() == null) {
                continue;
            }
            String[] parts = image.getOldName().split("_");
            if (parts.length < 2 || !type.equals(parts[1])) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (index >= 0 && index < slots.length) {
                slots[index] = image.getNewName();
            }
        }
        return slots;
    }

    private String leftOverviewImage(String[] slots) {
        return slots[0] != null ? slots[0] : slots[1];
    }

    private String rightOverviewImage(String[] slots) {
        return slots[1] != null ? slots[1] : slots[0];
    }

    /**
     * 生成表格内容文本
     */
    private String generateTableContent(List<Map<String, Object>> tableData) {
        StringBuilder table = new StringBuilder();

        // 表头
        table.append("序号\t桥梁名称\t缺损位置\t缺损类型\t病害描述\n");

        // 数据行
        for (Map<String, Object> row : tableData) {
            table.append(row.get("序号")).append("\t")
                    .append(row.get("桥梁名称")).append("\t")
                    .append(row.get("缺损位置")).append("\t")
                    .append(row.get("缺损类型")).append("\t")
                    .append(row.get("病害描述")).append("\n");
        }

        return table.toString();
    }

    /**
     * 对于单个病害 生成 成因分析
     */
    private String getDiseaseCause(Disease disease) {
        CauseQuery causeQuery = new CauseQuery();
        Building building = buildingService.selectBuildingById(disease.getBuildingId());
        BiObject biObject = biObjectMapper.selectBiObjectById(disease.getBiObjectId());
        BiObject biObject_building = biObjectMapper.selectBiObjectById(building.getRootObjectId());
        BiTemplateObject biTemplateObject = biTemplateObjectService.selectBiTemplateObjectById(biObject_building.getTemplateObjectId());
        causeQuery.setTemplate(biTemplateObject.getName());
        causeQuery.setObject(biObject.getName());
        causeQuery.setParentObject(biObject.getParentName());
        causeQuery.setDescription(disease.getDescription());
        causeQuery.setPosition(disease.getPosition());
        causeQuery.setType(disease.getType());
        return diseaseService.getCauseAnalysis(causeQuery);
    }

    public static int compareCodes(String c1, String c2) {
        String[] p1 = c1.split("-");
        String[] p2 = c2.split("-");

        int len = Math.min(p1.length, p2.length);

        for (int i = 0; i < len; i++) {
            String s1 = p1[i];
            String s2 = p2[i];

            boolean isNum1 = s1.matches("\\d+");
            boolean isNum2 = s2.matches("\\d+");

            if (isNum1 && isNum2) {
                // 都是数字：按数值排序
                int n1 = Integer.parseInt(s1);
                int n2 = Integer.parseInt(s2);
                if (n1 != n2) return n1 - n2;
            } else if (!isNum1 && !isNum2) {
                // 都是字母：按字典序
                int cmp = s1.compareToIgnoreCase(s2);
                if (cmp != 0) return cmp;
            } else {
                // 一个数字一个字母：数字优先
                return isNum1 ? -1 : 1;
            }
        }

        // 比较长度，例如 1-2 < 1-2-1
        return p1.length - p2.length;
    }

}
