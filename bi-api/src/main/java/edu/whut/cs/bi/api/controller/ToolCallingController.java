package edu.whut.cs.bi.api.controller;

import com.alibaba.fastjson.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysUserService;
import edu.whut.cs.bi.api.evaluation.BridgeEvaluationQuery;
import edu.whut.cs.bi.api.evaluation.DepartmentProjectScope;
import edu.whut.cs.bi.api.evaluation.EvaluationCalculateRequest;
import edu.whut.cs.bi.api.evaluation.EvaluationCalculationService;
import edu.whut.cs.bi.api.evaluation.EvaluationException;
import edu.whut.cs.bi.api.evaluation.ProjectEvaluationCalculateRequest;
import edu.whut.cs.bi.api.evaluation.ProjectProgressQuery;
import edu.whut.cs.bi.api.report.SingleBridgeReportService;
import edu.whut.cs.bi.api.vo.DiseasesOfYearVo;
import edu.whut.cs.bi.api.vo.ProjectsOfUserVo;
import edu.whut.cs.bi.api.vo.PropertyTreeVo;
import edu.whut.cs.bi.api.vo.TasksOfProjectVo;
import edu.whut.cs.bi.biz.controller.FileMapController;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.domain.enums.ProjectUserRoleEnum;
import edu.whut.cs.bi.biz.domain.vo.Disease2FunctionCall;
import edu.whut.cs.bi.biz.service.*;
import edu.whut.cs.bi.biz.utils.Convert2VO;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Junbo
 * @date 2025/6/11
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/toolCallingApi")
public class ToolCallingController {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingController.class);

    @Resource
    private FileMapController fileMapController;
    @Resource
    private IBuildingService buildingService;
    @Resource
    private IPropertyService propertyService;
    @Resource
    private IBiObjectService biObjectService;
    @Resource
    private IProjectService projectService;
    @Resource
    private ITaskService taskService;
    @Resource
    private IDiseaseService diseaseService;
    @Resource
    private IBiEvaluationService biEvaluationService;
    @Resource
    private IConditionService conditionService;
    @Resource
    private IScoreService scoreService;
    @Resource
    private ISysDeptService deptService;
    @Resource
    private ISysUserService userService;
    @Resource
    private EvaluationCalculationService evaluationCalculationService;
    @Resource
    private SingleBridgeReportService singleBridgeReportService;

    @GetMapping("/building/{bName}/property")
    @ResponseBody
    @Anonymous
    public AjaxResult getProperty(@PathVariable("bName") String bName) {
        if (bName == null) {
            return AjaxResult.error("参数错误");
        }
        Building building_name = new Building();
        building_name.setName(bName);
        Long buildingId = buildingService.selectBuildingList(building_name).get(0).getId();
        return buildPropertyResult(buildingId);
    }

    @GetMapping("/building/id/{bid}/property")
    @ResponseBody
    public AjaxResult getPropertyById(@PathVariable("bid") Long buildingId) {
        if (buildingService.selectBuildingById(buildingId) == null) {
            return AjaxResult.error("未找到指定的桥梁");
        }
        return buildPropertyResult(buildingId);
    }

    private AjaxResult buildPropertyResult(Long buildingId) {
        Building building = buildingService.selectBuildingById(buildingId);
        List<FileMap> imageMaps = fileMapController.getImageMaps(buildingId, "newfront", "newside");
        Map<String, List<String>> collect = imageMaps.stream().collect(Collectors.groupingBy(
                image -> image.getOldName().split("_")[1],
                Collectors.mapping(FileMap::getNewName, Collectors.toList())
        ));
        Property property = propertyService.selectPropertyTree(building.getRootPropertyId());

        // 封装返回结果
        PropertyTreeVo propertyTreeVo = new PropertyTreeVo();
        propertyTreeVo.setProperty(property);
        propertyTreeVo.setImages(collect);
        return AjaxResult.success("查询成功", propertyTreeVo);
    }


    @GetMapping("/building/{bName}/object")
    @ResponseBody
    public AjaxResult getObjectTree(@PathVariable("bName") String bName) {
        try {
            // 查询建筑物的root_object_id
            Building building_query = new Building();
            building_query.setName(bName);
            Building building = buildingService.selectBuildingList(building_query).get(0);
            return buildObjectTreeResult(building);
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @GetMapping("/building/id/{bid}/object")
    @ResponseBody
    public AjaxResult getObjectTreeById(@PathVariable("bid") Long buildingId) {
        try {
            return buildObjectTreeResult(buildingService.selectBuildingById(buildingId));
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    private AjaxResult buildObjectTreeResult(Building building) throws Exception {
        if (building == null || building.getRootObjectId() == null) {
            return AjaxResult.error("未找到指定的建筑物或其结构信息");
        }

        // 获取对象树的JSON结构
        String jsonTree = biObjectService.bridgeStructureJson(building.getRootObjectId());
        JSONObject jsonObject = JSONObject.parseObject(jsonTree);
        return AjaxResult.success("ObjectTree success", jsonObject);
    }

    @GetMapping("/me")
    @ResponseBody
    public AjaxResult getCurrentUser() {
        SysUser user = ShiroUtils.getSysUser();
        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getUserId());
        result.put("loginName", user.getLoginName());
        result.put("userName", user.getUserName());
        result.put("deptName", user.getDept() == null ? null : user.getDept().getDeptName());
        return AjaxResult.success("查询成功", result);
    }

    @GetMapping("/project/mine")
    @ResponseBody
    public AjaxResult getMyProjects() {
        Long userId = ShiroUtils.getUserId();
        Map<Long, Project> projects = new LinkedHashMap<>();
        for (ProjectUserRoleEnum role : ProjectUserRoleEnum.values()) {
            for (Project project : projectService.selectProjectListByUserIdAndRole(new Project(), userId, role.getValue())) {
                projects.putIfAbsent(project.getId(), project);
            }
        }
        ProjectsOfUserVo projectsOfUserVo = new ProjectsOfUserVo();
        projectsOfUserVo.setProjects(new ArrayList<>(projects.values()));
        projectsOfUserVo.setUserId(userId);
        return AjaxResult.success("查询成功", projectsOfUserVo);
    }

    @GetMapping("/project/{id}")
    @ResponseBody
    public AjaxResult getProject(@PathVariable("id") Long userId) {

        List<Project> projects = projectService.selectProjectListByUserIdAndRole(new Project(), userId, ProjectUserRoleEnum.INSPECTOR.getValue());
        ProjectsOfUserVo projectsOfUserVo = new ProjectsOfUserVo();
        projectsOfUserVo.setProjects(projects);
        projectsOfUserVo.setUserId(userId);
        return AjaxResult.success("查询成功", projectsOfUserVo);
    }

    @GetMapping("/project/progress")
    @ResponseBody
    public AjaxResult projectProgress(@RequestParam(required = false) Integer year,
                                      @RequestParam(required = false) Long projectId,
                                      @RequestParam(required = false) String projectName) {
        SysUser user = userService.selectUserById(ShiroUtils.getUserId());
        if (user == null) {
            return AjaxResult.error("未找到当前登录用户");
        }
        if (user.getDeptId() == null) {
            return AjaxResult.error("当前用户没有部门，无法按部门业务查询");
        }
        SysDept dept = deptService.selectDeptById(user.getDeptId());
        Long parentDeptId = DepartmentProjectScope.parentId(dept == null ? null : dept.getParentId());
        boolean named = projectName != null && !projectName.isBlank();
        if (projectId == null && !named) {
            return AjaxResult.error("请先向用户询问项目名称，再按部门业务查询");
        }

        Integer effectiveYear = year;
        List<Project> matched;
        if (projectId != null) {
            Project project = projectService.selectProjectById(projectId);
            if (!DepartmentProjectScope.contains(project, user.getDeptId(), parentDeptId)) {
                return AjaxResult.error("未找到本部门的该项目");
            }
            if (effectiveYear != null && !effectiveYear.equals(project.getYear())) {
                return AjaxResult.error("该项目不在指定年份");
            }
            matched = List.of(project);
        } else {
            if (effectiveYear == null) {
                effectiveYear = Year.now(ZoneId.of("Asia/Shanghai")).getValue();
            }
            matched = new ArrayList<>(projectService.selectDepartmentProjects(
                    user.getDeptId(), parentDeptId, effectiveYear, projectName));
            matched.sort(Comparator.comparing(project -> project.getName() == null ? "" : project.getName()));
        }
        if (matched.isEmpty()) {
            return AjaxResult.error("本部门没有名称相符的项目");
        }
        if (projectId == null && matched.size() > 1) {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("year", effectiveYear);
            choice.put("chooseProject", true);
            List<Map<String, Object>> options = new ArrayList<>();
            for (Project project : matched) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("projectId", project.getId());
                option.put("projectName", project.getName());
                option.put("year", project.getYear());
                options.add(option);
            }
            choice.put("projects", options);
            return AjaxResult.success("有多个相符项目，请让用户选定", choice);
        }

        Project chosen = matched.get(0);
        List<ProjectProgressQuery.MemberProject> projects = List.of(
                new ProjectProgressQuery.MemberProject(chosen, List.of()));
        List<Task> tasks = taskService.selectTasksByProject(chosen.getId(), chosen.getYear());
        List<Long> taskIds = tasks.stream().map(Task::getId).filter(Objects::nonNull).collect(Collectors.toList());
        List<BiEvaluation> evaluations = taskIds.isEmpty()
                ? List.of()
                : biEvaluationService.selectBiEvaluationByTaskIds(taskIds);
        return AjaxResult.success("查询成功", ProjectProgressQuery.build(
                chosen.getYear(), chosen.getId(), projects, tasks, evaluations));
    }

    /**
     * 计算一座桥的评定（写操作）。流程与网页任务页的「评定」按钮一致：
     * 由任务解析桥幅和结构根节点，必要时修正权重并锁定，再计算评定。
     */
    @PostMapping("/building/evaluation/calculate")
    @ResponseBody
    @Log(title = "桥梁技术状况评定", businessType = BusinessType.OTHER)
    public AjaxResult calculateBridgeEvaluation(@RequestBody EvaluationCalculateRequest request) {
        if (request == null || request.getTaskId() == null) {
            return AjaxResult.error("taskId 不能为空");
        }
        if (!EvaluationCalculationService.canEvaluate()) {
            return AjaxResult.error("当前用户没有评定计算权限（biz:evaluation:calculate），无法计算评定");
        }
        try {
            return AjaxResult.success("评定计算完成",
                    evaluationCalculationService.calculate(request.getTaskId(), request.getBridgeId()));
        } catch (EvaluationException e) {
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("MCP 评定计算失败：taskId={}", request.getTaskId(), e);
            return AjaxResult.error("评定计算失败：" + readableMessage(e));
        }
    }

    /**
     * 批量补算一个项目下未评定的桥（写操作）。单座失败不阻断其它桥。
     */
    @PostMapping("/building/evaluation/calculate-project")
    @ResponseBody
    @Log(title = "桥梁技术状况评定", businessType = BusinessType.OTHER)
    public AjaxResult calculateProjectEvaluations(@RequestBody ProjectEvaluationCalculateRequest request) {
        if (request == null || request.getProjectId() == null) {
            return AjaxResult.error("projectId 不能为空");
        }
        if (!EvaluationCalculationService.canEvaluate()) {
            return AjaxResult.error("当前用户没有评定计算权限（biz:evaluation:calculate），无法计算评定");
        }
        try {
            return AjaxResult.success("批量评定计算完成", evaluationCalculationService.calculateProject(
                    request.getProjectId(), request.getTaskIds(), request.getBridgeIds(),
                    request.getOnlySubmitted() == null || request.getOnlySubmitted()));
        } catch (EvaluationException e) {
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("MCP 批量评定计算失败：projectId={}", request.getProjectId(), e);
            return AjaxResult.error("批量评定计算失败：" + readableMessage(e));
        }
    }

    private static String readableMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }

    @GetMapping("/project/{projectId}/task")
    @ResponseBody
    public AjaxResult getTask(@PathVariable("projectId") Long projectId) {
        if (projectId == null) {
            return AjaxResult.error("参数错误");
        }
        Task task = new Task();
        task.setProjectId(projectId);
        List<Task> tasks = taskService.selectTaskVOList(task);

        TasksOfProjectVo tasksOfProjectVo = new TasksOfProjectVo();
        tasksOfProjectVo.setTasks(tasks);
        tasksOfProjectVo.setProjectId(projectId);

        return AjaxResult.success("查询成功", tasksOfProjectVo);
    }

    @GetMapping("/building/id/{bid}/evaluation")
    @ResponseBody
    public AjaxResult getEvaluation(@PathVariable("bid") Long buildingId,
                                    @RequestParam(required = false, name = "year") Integer year) {
        if (buildingId == null) {
            return AjaxResult.error("参数错误");
        }
        Building building = buildingService.selectBuildingById(buildingId);
        if (building == null) {
            return AjaxResult.error("未找到指定的桥梁");
        }
        List<Task> tasks = taskService.selectTasksByBuildingId(buildingId, year);
        Map<Long, BiEvaluation> evaluations = new HashMap<>();
        for (Task task : tasks) {
            evaluations.put(task.getId(), biEvaluationService.selectBiEvaluationByTaskId(task.getId()));
        }
        BridgeEvaluationQuery.Selection selection = BridgeEvaluationQuery.select(building, year, tasks, evaluations);
        List<Condition> conditions = List.of();
        Map<Long, String> parentNames = new HashMap<>();
        Map<Long, List<Score>> scores = new HashMap<>();
        if (selection.evaluated) {
            conditions = conditionService.selectConditionsByBiEvaluationId(selection.evaluation.getId());
            if (conditions == null) {
                conditions = List.of();
            }
            for (Condition condition : conditions) {
                if (condition == null || condition.getBiObjectId() == null || parentNames.containsKey(condition.getBiObjectId())) {
                    continue;
                }
                BiObject parent = biObjectService.selectDirectParentById(condition.getBiObjectId());
                parentNames.put(condition.getBiObjectId(), parent == null ? null : parent.getName());
            }
            for (Condition condition : conditions) {
                if (condition == null || condition.getId() == null) {
                    continue;
                }
                List<Score> partScores = scoreService.selectScoresByConditionId(condition.getId());
                scores.put(condition.getId(), partScores == null ? List.of() : partScores);
            }
        }
        return AjaxResult.success("查询成功", BridgeEvaluationQuery.build(
                building, year, tasks, evaluations, conditions, parentNames, scores));
    }

    @GetMapping("/building/{bid}/disease")
    @ResponseBody
    public AjaxResult getDisease(@PathVariable("bid") Long buildingId, @RequestParam(required = false, name = "year") Integer year) {
        if (buildingId == null) {
            return AjaxResult.error("参数错误");
        }
        Disease disease = new Disease();
        disease.setBuildingId(buildingId);
        if (year != null) {
            disease.setYear(year);
        }

        List<Disease> diseases = diseaseService.selectDiseaseListForApi(disease);
        List<Disease2FunctionCall> result = Convert2VO.copyList(diseases, Disease2FunctionCall.class);
        return AjaxResult.success("查询成功", result);
    }

    @GetMapping("/building/{bName}")
    @ResponseBody
    public AjaxResult getBuildingListByName(@PathVariable(required = true) String bName) {
        Building building = new Building();
        building.setName(bName);
        List<Building> buildings = buildingService.selectBuildingList(building);
        return AjaxResult.success("查询成功", buildings);
    }

    // ---------- MCP 单桥检测报告 ----------

    /**
     * 列出状态正常的单桥报告模板（二级梁桥 / 二级拱桥 / 一级梁桥）。
     * bridgeKind / level 只过滤模板名类别；桥梁实体本身没有一二级之分。
     */
    @GetMapping("/report/templates")
    @ResponseBody
    public AjaxResult listReportTemplates(@RequestParam(required = false) String bridgeKind,
                                          @RequestParam(required = false) Integer level) {
        try {
            return AjaxResult.success("查询成功", singleBridgeReportService.listSingleBridgeTemplates(bridgeKind, level));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    /**
     * 解析项目下的可选审核人/批准人；命中 preferredLoginName（默认 crh@znjc）时给出默认 id。
     */
    @GetMapping("/report/resolveUsers")
    @ResponseBody
    public AjaxResult resolveReportUsers(@RequestParam("projectId") Long projectId,
                                         @RequestParam(required = false) String preferredLoginName) {
        try {
            return AjaxResult.success("查询成功", singleBridgeReportService.resolveUsers(projectId, preferredLoginName));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    /**
     * 创建单桥检测报告（写操作）。行为与网页新建报告一致，含报告数据初始化，不触发 Word 生成。
     */
    @PostMapping("/report/createSingle")
    @ResponseBody
    @Log(title = "检测报告", businessType = BusinessType.INSERT)
    public AjaxResult createSingleBridgeReport(@RequestBody Map<String, Object> body) {
        try {
            return AjaxResult.success("创建成功", singleBridgeReportService.createReport(
                    asString(body.get("name")),
                    asLong(body.get("projectId")),
                    asLong(body.get("taskId")),
                    asLong(body.get("bridgeId")),
                    asLong(body.get("reportTemplateId")),
                    asLong(body.get("reviewerId")),
                    asLong(body.get("approverId"))));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    /**
     * 读取报告基本信息、填报入口和已保存的重点病害选择。
     */
    @GetMapping("/report/{reportId}")
    @ResponseBody
    public AjaxResult getReport(@PathVariable("reportId") Long reportId) {
        try {
            return AjaxResult.success("查询成功", singleBridgeReportService.getReport(reportId));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    /**
     * 列出该报告任务下可作为重点关注病害的候选病害明细。
     */
    @GetMapping("/report/{reportId}/focusDiseaseCandidates")
    @ResponseBody
    public AjaxResult listFocusDiseaseCandidates(@PathVariable("reportId") Long reportId,
                                                 @RequestParam(required = false) Integer minLevel) {
        try {
            return AjaxResult.success("查询成功", singleBridgeReportService.listFocusDiseaseCandidates(reportId, minLevel));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    /**
     * 写入重点关注病害（写操作）。与网页填报页 4.1.6 同一存储字段。
     */
    @PostMapping("/report/{reportId}/focusDiseases")
    @ResponseBody
    @Log(title = "报告数据", businessType = BusinessType.UPDATE)
    public AjaxResult setReportFocusDiseases(@PathVariable("reportId") Long reportId,
                                             @RequestBody Map<String, Object> body) {
        try {
            Object rawIds = body.get("diseaseIds");
            List<Long> diseaseIds = new ArrayList<>();
            if (rawIds instanceof List) {
                for (Object item : (List<?>) rawIds) {
                    Long id = asLong(item);
                    if (id != null) {
                        diseaseIds.add(id);
                    }
                }
            }
            return AjaxResult.success("保存成功", singleBridgeReportService.setFocusDiseases(reportId, diseaseIds));
        } catch (Exception e) {
            return AjaxResult.error(readableMessage(e));
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
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
