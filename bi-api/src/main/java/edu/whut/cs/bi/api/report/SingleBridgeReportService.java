package edu.whut.cs.bi.api.report;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import edu.whut.cs.bi.biz.domain.Disease;
import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.Report;
import edu.whut.cs.bi.biz.domain.ReportData;
import edu.whut.cs.bi.biz.domain.ReportTemplate;
import edu.whut.cs.bi.biz.domain.Task;
import edu.whut.cs.bi.biz.domain.enums.ReportTemplateTypes;
import edu.whut.cs.bi.biz.service.IDiseaseService;
import edu.whut.cs.bi.biz.service.IProjectService;
import edu.whut.cs.bi.biz.service.IReportDataService;
import edu.whut.cs.bi.biz.service.IReportService;
import edu.whut.cs.bi.biz.service.IReportTemplateService;
import edu.whut.cs.bi.biz.service.ITaskService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP「单桥检测报告」：模板分流、审核人解析、创建报告、重点病害读写。
 * 行为对齐网页端 ReportController.addSave 与 fill_single_* 填报页。
 */
@Service
@RequiredArgsConstructor
public class SingleBridgeReportService {

    /** 二级梁桥 / 二级拱桥填报页「重点关注病害」存储 key（fill_single_beam / fill_single_arch） */
    public static final String FOCUS_DISEASES_KEY_LEVEL2 = "${chapter-4-1-6-diseases}";

    /** 一级梁桥填报页「重点关注病害」存储 key（fill_single_beam_level1） */
    public static final String FOCUS_DISEASES_KEY_LEVEL1 = "${focusDiseases}";

    /** 默认审核人/批准人登录名 */
    public static final String DEFAULT_REVIEWER_LOGIN_NAME = "crh@znjc";

    private final IReportService reportService;
    private final IReportTemplateService reportTemplateService;
    private final IReportDataService reportDataService;
    private final ITaskService taskService;
    private final IProjectService projectService;
    private final IDiseaseService diseaseService;
    private final ISysUserService userService;
    private final ISysDeptService deptService;
    private final SysUserMapper sysUserMapper;

    /**
     * 列出状态正常的单桥报告模板（二级梁桥 / 二级拱桥 / 一级梁桥）。
     * bridgeKind / level 只过滤模板名类别，不是桥梁实体属性（系统里的桥没有一二级之分）。
     */
    public List<Map<String, Object>> listSingleBridgeTemplates(String bridgeKind, Integer level) {
        ReportTemplate query = new ReportTemplate();
        query.setIsActive(0); // 状态正常
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportTemplate template : reportTemplateService.selectReportTemplateList(query)) {
            String fillKind = ReportTemplateTypes.resolveSingleBridgeFillKind(template.getName());
            if (fillKind == null) {
                continue;
            }
            String kind = ReportTemplateTypes.singleBridgeKindOf(template.getName());
            Integer templateLevel = ReportTemplateTypes.singleBridgeLevelOf(template.getName());
            if (bridgeKind != null && !bridgeKind.isBlank() && !bridgeKind.equalsIgnoreCase(kind)) {
                continue;
            }
            if (level != null && !level.equals(templateLevel)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("templateId", template.getId());
            item.put("name", template.getName());
            item.put("bridgeKind", kind);
            item.put("level", templateLevel);
            item.put("fillKind", fillKind);
            result.add(item);
        }
        return result;
    }

    /**
     * 解析项目下的可选审核人/批准人；命中 preferredLoginName 时给出默认 id。
     */
    public Map<String, Object> resolveUsers(Long projectId, String preferredLoginName) {
        if (projectId == null) {
            throw new ServiceException("参数错误：projectId 不能为空");
        }
        Project project = projectService.selectProjectById(projectId);
        if (project == null) {
            throw new ServiceException("项目不存在：" + projectId);
        }
        String preferred = (preferredLoginName == null || preferredLoginName.isBlank())
                ? DEFAULT_REVIEWER_LOGIN_NAME : preferredLoginName.trim();
        List<SysUser> users = projectService.selectUserList(projectId);
        Map<String, Object> result = resolveDefaults(users == null ? List.of() : users, preferred);
        result.put("projectId", projectId);
        result.put("projectName", project.getName());
        return result;
    }

    /**
     * 创建单桥检测报告（对齐 ReportController.addSave → ReportServiceImpl.insertReport，含 generateReportData）。
     * 不在此触发 Word 文档生成。
     */
    public Map<String, Object> createReport(String name, Long projectId, Long taskId, Long bridgeId,
                                            Long reportTemplateId, Long reviewerId, Long approverId) {
        requirePermission("biz:report:add");
        if (name == null || name.isBlank()) {
            throw new ServiceException("参数错误：报告名称 name 不能为空");
        }
        if (projectId == null) {
            throw new ServiceException("参数错误：projectId 不能为空");
        }
        if (reportTemplateId == null) {
            throw new ServiceException("参数错误：reportTemplateId 不能为空");
        }
        if (reviewerId == null || approverId == null) {
            throw new ServiceException("参数错误：reviewerId 和 approverId 不能为空");
        }
        Project project = projectService.selectProjectById(projectId);
        if (project == null) {
            throw new ServiceException("项目不存在：" + projectId);
        }
        ReportTemplate template = requireSingleBridgeTemplate(reportTemplateId);

        List<Task> projectTasks = listProjectTasks(projectId);
        Task task = resolveSingleTask(projectTasks, taskId, bridgeId);

        // 审核人/批准人必须是项目下可选用户（与网页下拉框口径一致）
        Set<Long> projectUserIds = projectUserIds(projectId);
        if (!projectUserIds.contains(reviewerId)) {
            throw new ServiceException("审核人不在该项目的用户列表中：" + reviewerId);
        }
        if (!projectUserIds.contains(approverId)) {
            throw new ServiceException("批准人不在该项目的用户列表中：" + approverId);
        }

        Report report = new Report();
        report.setName(name.trim());
        report.setProjectId(projectId);
        report.setReportTemplateId(reportTemplateId);
        // 单桥报告只允许一个任务
        report.setTaskIds(String.valueOf(task.getId()));
        report.setReviewerId(reviewerId);
        report.setApproverId(approverId);
        int rows = reportService.insertReport(report);
        if (rows <= 0 || report.getId() == null) {
            throw new ServiceException("创建报告失败");
        }
        return buildReportSummary(reportService.selectReportById(report.getId()), template, task, project);
    }

    /**
     * 读取报告基本信息、填报入口与已保存的重点病害选择。
     */
    public Map<String, Object> getReport(Long reportId) {
        Report report = requireAccessibleReport(reportId);
        ReportTemplate template = report.getReportTemplateId() == null ? null
                : reportTemplateService.selectReportTemplateById(report.getReportTemplateId());
        Project project = report.getProjectId() == null ? null : projectService.selectProjectById(report.getProjectId());
        Task task = firstTask(report);

        Map<String, Object> result = buildReportSummary(report, template, task, project);
        if (template != null) {
            // 模板类别，不是桥梁档案字段；桥梁实体没有一二级之分
            result.put("bridgeKind", ReportTemplateTypes.singleBridgeKindOf(template.getName()));
            result.put("templateLevel", ReportTemplateTypes.singleBridgeLevelOf(template.getName()));
        }
        result.put("reviewer", report.getReviewer());
        result.put("approver", report.getApprover());
        result.put("reviewerId", report.getReviewerId());
        result.put("approverId", report.getApproverId());
        result.put("createBy", report.getCreateBy());

        String raw = readFocusDiseasesRaw(reportId, template == null ? null : template.getName());
        if (raw != null) {
            result.put("focusDiseasesRaw", raw);
            result.put("focusDiseases", parseFocusDiseases(raw));
        }
        return result;
    }

    /**
     * 列出该报告任务下可作为重点关注病害的候选病害明细。
     * 口径与网页填报页病害选择器一致（同一构件病害查询），明细粒度到病害记录。
     */
    public Map<String, Object> listFocusDiseaseCandidates(Long reportId, Integer minLevel) {
        Report report = requireAccessibleReport(reportId);
        requireSingleBridgeTemplate(report.getReportTemplateId());
        Task task = firstTask(report);
        if (task == null) {
            throw new ServiceException("报告未关联任务");
        }
        Project project = projectService.selectProjectById(report.getProjectId());

        Map<String, Map<String, Object>> candidates = candidateMap(report);
        List<Disease> diseases = listTaskDiseases(task, project);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Disease disease : diseases) {
            if (!candidates.containsKey(candidateKey(disease.getBiObjectId(), disease.getDiseaseTypeId()))) {
                continue;
            }
            if (minLevel != null && disease.getLevel() < minLevel) {
                continue;
            }
            items.add(toCandidateItem(disease, candidates));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("taskId", task.getId());
        result.put("bridgeId", task.getBuildingId());
        result.put("total", items.size());
        result.put("diseases", items);
        return result;
    }

    /**
     * 写入重点关注病害（与网页填报页 4.1.6 同一存储字段）。
     * 网页存储粒度为「构件 + 病害类型」组合，这里把选中的病害记录归并到该粒度写入，
     * 保证网页打开填报页能看到已选，后续生成 Word 能读到。
     */
    public Map<String, Object> setFocusDiseases(Long reportId, List<Long> diseaseIds) {
        requirePermission("biz:report:edit");
        Report report = requireAccessibleReport(reportId);
        ReportTemplate template = requireSingleBridgeTemplate(report.getReportTemplateId());
        if (diseaseIds == null || diseaseIds.isEmpty()) {
            throw new ServiceException("参数错误：diseaseIds 不能为空");
        }
        Task task = firstTask(report);
        if (task == null) {
            throw new ServiceException("报告未关联任务");
        }
        Project project = projectService.selectProjectById(report.getProjectId());
        Map<String, Map<String, Object>> candidates = candidateMap(report);
        Map<Long, Disease> diseaseById = new LinkedHashMap<>();
        for (Disease disease : listTaskDiseases(task, project)) {
            if (candidates.containsKey(candidateKey(disease.getBiObjectId(), disease.getDiseaseTypeId()))) {
                diseaseById.put(disease.getId(), disease);
            }
        }
        List<Long> distinctIds = diseaseIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        List<Disease> selected = new ArrayList<>();
        for (Long id : distinctIds) {
            Disease disease = diseaseById.get(id);
            if (disease == null) {
                throw new ServiceException("病害不属于该报告的检测任务或不在候选范围：" + id);
            }
            selected.add(disease);
        }

        List<Map<Long, List<Long>>> groups = groupFocusDiseases(selected);
        String value = toFocusDiseasesJson(groups);
        ReportData reportData = new ReportData();
        reportData.setReportId(reportId);
        reportData.setKey(focusDiseasesKey(template.getName()));
        reportData.setType(0);
        reportData.setValue(value);
        // 与网页 /biz/report_data/save 走同一存储路径
        reportDataService.saveReportDataBatch(reportId, List.of(reportData));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("savedDiseaseCount", selected.size());
        result.put("selectionCount", groups.stream().mapToInt(g -> g.values().iterator().next().size()).sum());
        result.put("componentCount", groups.size());
        result.put("value", value);
        result.put("summary", buildFocusSummary(selected));
        return result;
    }

    // ---------- 纯函数（便于单元测试） ----------

    /**
     * 按模板选重点病害存储 key：一级梁桥用 ${focusDiseases}，二级梁/拱桥用 ${chapter-4-1-6-diseases}。
     */
    public static String focusDiseasesKey(String templateName) {
        if ("fill_single_beam_level1".equals(ReportTemplateTypes.resolveSingleBridgeFillKind(templateName))) {
            return FOCUS_DISEASES_KEY_LEVEL1;
        }
        return FOCUS_DISEASES_KEY_LEVEL2;
    }

    /**
     * 解析单桥报告的唯一任务。bridgeId 必须在该项目下唯一对应一个任务，否则报错。
     */
    static Task resolveSingleTask(List<Task> projectTasks, Long taskId, Long bridgeId) {
        if (taskId == null && bridgeId == null) {
            throw new ServiceException("参数错误：taskId 和 bridgeId 必须提供一个");
        }
        List<Task> tasks = projectTasks == null ? List.of() : projectTasks;
        if (taskId != null) {
            return tasks.stream()
                    .filter(task -> taskId.equals(task.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("任务不存在或不属于该项目：" + taskId));
        }
        List<Task> matched = tasks.stream()
                .filter(task -> bridgeId.equals(task.getBuildingId()))
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            throw new ServiceException("该项目下没有这座桥的检测任务：" + bridgeId);
        }
        if (matched.size() > 1) {
            throw new ServiceException("该项目下这座桥有 " + matched.size() + " 个检测任务，单桥报告无法自动确定，请改用 taskId");
        }
        return matched.get(0);
    }

    /**
     * 在项目用户列表中解析默认审核人/批准人；未命中 preferredLoginName 时 defaults 为空并说明。
     */
    static Map<String, Object> resolveDefaults(List<SysUser> users, String preferredLoginName) {
        List<Map<String, Object>> userItems = new ArrayList<>();
        SysUser preferred = null;
        for (SysUser user : users) {
            if (user == null || user.getUserId() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", user.getUserId());
            item.put("loginName", user.getLoginName());
            item.put("userName", user.getUserName());
            userItems.add(item);
            if (preferredLoginName.equals(user.getLoginName())) {
                preferred = user;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferredLoginName", preferredLoginName);
        result.put("users", userItems);
        if (preferred != null) {
            result.put("defaultReviewerId", preferred.getUserId());
            result.put("defaultApproverId", preferred.getUserId());
        } else {
            result.put("defaultReviewerId", null);
            result.put("defaultApproverId", null);
            result.put("message", userItems.isEmpty()
                    ? "该项目下没有可选用户，请在网页端为项目安排人员后再试"
                    : "项目下没有用户 " + preferredLoginName + "，请从 users 中选择审核人和批准人");
        }
        return result;
    }

    /**
     * 把选中的病害记录按网页存储粒度归并：[{构件id: [病害类型id, ...]}, ...]，保持选择顺序。
     */
    static List<Map<Long, List<Long>>> groupFocusDiseases(List<Disease> selected) {
        Map<Long, List<Long>> byComponent = new LinkedHashMap<>();
        for (Disease disease : selected) {
            byComponent.computeIfAbsent(disease.getBiObjectId(), key -> new ArrayList<>());
            List<Long> typeIds = byComponent.get(disease.getBiObjectId());
            if (!typeIds.contains(disease.getDiseaseTypeId())) {
                typeIds.add(disease.getDiseaseTypeId());
            }
        }
        List<Map<Long, List<Long>>> groups = new ArrayList<>();
        byComponent.forEach((componentId, typeIds) -> groups.add(Map.of(componentId, typeIds)));
        return groups;
    }

    /**
     * 序列化为网页填报页使用的 JSON 格式（与 fill_single_*.html 的 confirmDiseaseSelection 一致）。
     * 注意 WriteNonStringKeyAsString：JSON 标准的 key 必须是字符串，否则网页 JSON.parse 会失败。
     */
    static String toFocusDiseasesJson(List<Map<Long, List<Long>>> groups) {
        return JSON.toJSONString(groups, com.alibaba.fastjson.serializer.SerializerFeature.WriteNonStringKeyAsString);
    }

    /**
     * 报告可见性校验，与 ReportMapper.selectReportList 的过滤口径一致：
     * 管理员全量；部门管理员按项目部门；普通用户限自己创建、审核、批准或参与项目的报告。
     */
    static boolean canAccessReport(Report report, Project project, SysUser user, List<String> roles,
                                   Long parentDeptId, Set<Long> projectUserIds) {
        List<String> safeRoles = roles == null ? List.of() : roles;
        boolean isAdmin = safeRoles.stream()
                .anyMatch(role -> "admin".equals(role) || "system_admin".equals(role) || "business_admin".equals(role));
        if (isAdmin) {
            return true;
        }
        if (safeRoles.contains("department_business_admin") && project != null && user.getDeptId() != null) {
            if (user.getDeptId().equals(project.getDeptId()) || user.getDeptId().equals(project.getOwnerDeptId())) {
                return true;
            }
            if (parentDeptId != null
                    && (parentDeptId.equals(project.getDeptId()) || parentDeptId.equals(project.getOwnerDeptId()))) {
                return true;
            }
        }
        if (user.getLoginName() != null && user.getLoginName().equals(report.getCreateBy())) {
            return true;
        }
        if (user.getUserId() != null
                && (user.getUserId().equals(report.getReviewerId()) || user.getUserId().equals(report.getApproverId()))) {
            return true;
        }
        return projectUserIds != null && projectUserIds.contains(user.getUserId());
    }

    static String candidateKey(Long biObjectId, Long diseaseTypeId) {
        return biObjectId + "_" + diseaseTypeId;
    }

    // ---------- 内部实现 ----------

    /** 当前登录用户（抽出以便测试覆盖，避免 Shiro 静态依赖） */
    protected SysUser currentUser() {
        return ShiroUtils.getSysUser();
    }

    /** 网页同款权限点校验（抽出以便测试覆盖） */
    protected void requirePermission(String permission) {
        if (!SecurityUtils.getSubject().isPermitted(permission)) {
            throw new ServiceException("当前用户没有权限：" + permission + "（与网页端报告管理权限一致）");
        }
    }

    private ReportTemplate requireSingleBridgeTemplate(Long reportTemplateId) {
        ReportTemplate template = reportTemplateId == null ? null
                : reportTemplateService.selectReportTemplateById(reportTemplateId);
        if (template == null) {
            throw new ServiceException("报告模板不存在：" + reportTemplateId);
        }
        if (template.getIsActive() == null || template.getIsActive() != 0) {
            throw new ServiceException("报告模板已停用：" + template.getName());
        }
        if (ReportTemplateTypes.resolveSingleBridgeFillKind(template.getName()) == null) {
            throw new ServiceException("模板不是单桥报告模板（需为二级梁桥、二级拱桥或一级梁桥单桥模板）：" + template.getName());
        }
        return template;
    }

    private List<Task> listProjectTasks(Long projectId) {
        Task query = new Task();
        query.setProjectId(projectId);
        List<Task> tasks = taskService.selectTaskVOList(query);
        return tasks == null ? List.of() : tasks;
    }

    private Set<Long> projectUserIds(Long projectId) {
        List<SysUser> users = projectService.selectUserList(projectId);
        if (users == null) {
            return Set.of();
        }
        return users.stream().filter(Objects::nonNull).map(SysUser::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Task firstTask(Report report) {
        if (report.getTaskIds() == null || report.getTaskIds().isBlank()) {
            return null;
        }
        Long taskId;
        try {
            taskId = Long.parseLong(report.getTaskIds().split(",")[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return taskService.selectTaskById(taskId);
    }

    /**
     * 网页病害选择器的候选集合：key 为「构件id_病害类型id」，与 ReportDataServiceImpl.getDiseaseComponentData 同源。
     */
    private Map<String, Map<String, Object>> candidateMap(Report report) {
        Map<Long, Map<String, Object>> data = reportDataService.getDiseaseComponentData(report);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (data == null || report.getTaskIds() == null || report.getTaskIds().isBlank()) {
            return result;
        }
        Long firstTaskId = Long.parseLong(report.getTaskIds().split(",")[0].trim());
        Map<String, Object> taskData = data.get(firstTaskId);
        if (taskData == null) {
            return result;
        }
        Object diseases = taskData.get("diseases");
        if (!(diseases instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) diseases) {
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;
            Long componentId = toLong(entry.get("componentId"));
            Long diseaseTypeId = toLong(entry.get("diseaseTypeId"));
            if (componentId != null && diseaseTypeId != null) {
                result.put(candidateKey(componentId, diseaseTypeId), entry);
            }
        }
        return result;
    }

    /** 该任务下的病害明细，与 ToolCallingController 既有病害查询同源（按桥 + 项目年份） */
    private List<Disease> listTaskDiseases(Task task, Project project) {
        Disease query = new Disease();
        query.setBuildingId(task.getBuildingId());
        if (project != null && project.getYear() != null) {
            query.setYear(project.getYear());
        }
        List<Disease> diseases = diseaseService.selectDiseaseListForApi(query);
        return diseases == null ? List.of() : diseases;
    }

    private Map<String, Object> toCandidateItem(Disease disease, Map<String, Map<String, Object>> candidates) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("diseaseId", disease.getId());
        item.put("componentId", disease.getBiObjectId());
        item.put("diseaseTypeId", disease.getDiseaseTypeId());
        Map<String, Object> candidate = candidates.get(candidateKey(disease.getBiObjectId(), disease.getDiseaseTypeId()));
        String componentName = disease.getBiObjectName();
        if ((componentName == null || componentName.isBlank()) && candidate != null) {
            componentName = Objects.toString(candidate.get("componentName"), null);
        }
        item.put("component", componentName);
        item.put("type", disease.getDiseaseType() != null && disease.getDiseaseType().getName() != null
                ? disease.getDiseaseType().getName() : disease.getType());
        item.put("level", disease.getLevel());
        item.put("position", disease.getPosition());
        item.put("description", disease.getDescription());
        if (disease.getYear() != null) {
            item.put("year", disease.getYear());
        }
        return item;
    }

    private Report requireAccessibleReport(Long reportId) {
        if (reportId == null) {
            throw new ServiceException("参数错误：reportId 不能为空");
        }
        Report report = reportService.selectReportById(reportId);
        if (report == null) {
            throw new ServiceException("报告不存在：" + reportId);
        }
        SysUser user = currentUser();
        if (user == null) {
            throw new ServiceException("未获取到当前登录用户");
        }
        List<String> roles = sysUserMapper.selectUserRoleByUserId(user.getUserId());
        Project project = report.getProjectId() == null ? null : projectService.selectProjectById(report.getProjectId());
        Long parentDeptId = null;
        if (user.getDeptId() != null) {
            SysDept dept = deptService.selectDeptById(user.getDeptId());
            parentDeptId = dept == null ? null : dept.getParentId();
        }
        Set<Long> projectUserIds = report.getProjectId() == null ? Set.of() : projectUserIds(report.getProjectId());
        if (!canAccessReport(report, project, user, roles, parentDeptId, projectUserIds)) {
            throw new ServiceException("没有权限访问该报告：" + reportId);
        }
        return report;
    }

    private String readFocusDiseasesRaw(Long reportId, String templateName) {
        List<ReportData> dataList = reportDataService.selectReportDataByReportId(reportId);
        if (dataList == null) {
            return null;
        }
        String preferred = templateName == null ? null : focusDiseasesKey(templateName);
        String preferredValue = null;
        String fallbackValue = null;
        for (ReportData data : dataList) {
            if (data.getKey() == null || data.getValue() == null || data.getValue().isBlank()) {
                continue;
            }
            if (preferred != null && preferred.equals(data.getKey())) {
                preferredValue = data.getValue();
            } else if (FOCUS_DISEASES_KEY_LEVEL1.equals(data.getKey())
                    || FOCUS_DISEASES_KEY_LEVEL2.equals(data.getKey())) {
                // 兼容早期写错 key 的数据，仍能读出
                fallbackValue = data.getValue();
            }
        }
        return preferredValue != null ? preferredValue : fallbackValue;
    }

    /** 解析已保存的重点病害选择为 [{componentId, diseaseTypeIds}] 列表 */
    static List<Map<String, Object>> parseFocusDiseases(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (Object element : JSON.parseArray(raw)) {
                if (!(element instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) element;
                for (Map.Entry<String, Object> e : entry.entrySet()) {
                    Map<String, Object> selection = new LinkedHashMap<>();
                    selection.put("componentId", toLong(e.getKey()));
                    List<Long> typeIds = new ArrayList<>();
                    if (e.getValue() instanceof List) {
                        for (Object typeId : (List<?>) e.getValue()) {
                            Long id = toLong(typeId);
                            if (id != null) {
                                typeIds.add(id);
                            }
                        }
                    }
                    selection.put("diseaseTypeIds", typeIds);
                    result.add(selection);
                }
            }
        } catch (Exception ignored) {
            // 已保存值格式异常时不影响报告基本信息返回
        }
        return result;
    }

    private Map<String, Object> buildReportSummary(Report report, ReportTemplate template, Task task, Project project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", report.getId());
        result.put("name", report.getName());
        result.put("status", report.getStatus());
        result.put("projectId", report.getProjectId());
        if (project != null) {
            result.put("projectName", project.getName());
            result.put("year", project.getYear());
        }
        if (template != null) {
            result.put("templateId", template.getId());
            result.put("templateName", template.getName());
            // 填报入口与网页一致（不含 ctx）
            result.put("fillPath", ReportTemplateTypes.resolveFillPath(template.getName(), report.getId()));
            result.put("fillTitle", ReportTemplateTypes.resolveFillTitle(template.getName()));
        }
        if (task != null) {
            result.put("taskId", task.getId());
            result.put("bridgeId", task.getBuildingId());
            if (task.getBuilding() != null) {
                result.put("bridgeName", task.getBuilding().getName());
            }
        }
        return result;
    }

    private String buildFocusSummary(List<Disease> selected) {
        return selected.stream()
                .limit(5)
                .map(disease -> {
                    String component = disease.getBiObjectName() == null ? "构件" + disease.getBiObjectId() : disease.getBiObjectName();
                    String type = disease.getDiseaseType() != null && disease.getDiseaseType().getName() != null
                            ? disease.getDiseaseType().getName() : String.valueOf(disease.getDiseaseTypeId());
                    return component + "·" + type + "（" + disease.getLevel() + "级）";
                })
                .collect(Collectors.joining("；"))
                + (selected.size() > 5 ? " 等 " + selected.size() + " 条" : "");
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}