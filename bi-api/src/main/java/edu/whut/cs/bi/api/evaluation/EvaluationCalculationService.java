package edu.whut.cs.bi.api.evaluation;

import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysUserService;
import edu.whut.cs.bi.biz.domain.BiEvaluation;
import edu.whut.cs.bi.biz.domain.BiObject;
import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.Task;
import edu.whut.cs.bi.biz.service.IBiEvaluationService;
import edu.whut.cs.bi.biz.service.IBiObjectService;
import edu.whut.cs.bi.biz.service.IBuildingService;
import edu.whut.cs.bi.biz.service.IProjectService;
import edu.whut.cs.bi.biz.service.ITaskService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按网页任务页「评定」按钮的流程补算未评定：
 * 任务 → 桥幅 → 结构根节点，必要时修正权重并锁定，再调用评定计算。
 */
@Service
public class EvaluationCalculationService {

    private static final String EVALUATE_PERMISSION = "biz:evaluation:calculate";
    private static final String OBJECT_EDIT_PERMISSION = "biz:object:edit";
    private static final String LOCKED_STATUS = "3";
    private static final int SUBMITTED = 1;

    @Resource
    private IBiEvaluationService biEvaluationService;
    @Resource
    private IBiObjectService biObjectService;
    @Resource
    private IBuildingService buildingService;
    @Resource
    private ITaskService taskService;
    @Resource
    private IProjectService projectService;
    @Resource
    private ISysUserService userService;
    @Resource
    private ISysDeptService deptService;

    /**
     * 计算一座桥的评定。bridgeId 只用于校验任务归属，实际根节点由任务关联的桥梁解析。
     */
    public Map<String, Object> calculate(Long taskId, Long expectedBridgeId) {
        if (taskId == null) {
            throw new EvaluationException("taskId 不能为空");
        }
        Task task = taskService.selectTaskById(taskId);
        if (task == null) {
            throw new EvaluationException("未找到任务（taskId=" + taskId + "），可能已被删除");
        }
        Building building = resolveBuilding(task);
        if (expectedBridgeId != null && !expectedBridgeId.equals(building.getId())) {
            throw new EvaluationException("任务与桥梁不匹配：任务 " + taskId + " 对应的是「"
                    + text(building.getName()) + "」（bridgeId=" + building.getId() + "）");
        }
        requireLeaf(building);

        Long rootObjectId = building.getRootObjectId();
        if (rootObjectId == null) {
            throw new EvaluationException("桥梁「" + text(building.getName()) + "」还没有结构树，无法计算评定");
        }
        if (task.getProjectId() == null) {
            throw new EvaluationException("任务没有关联项目，无法计算评定");
        }
        Project project = projectService.selectProjectById(task.getProjectId());
        if (project == null) {
            throw new EvaluationException("任务关联的项目不存在，无法计算评定");
        }
        assertProjectAccess(project);

        boolean structureAdjusted = ensureStructureReady(rootObjectId, building.getName());
        BiEvaluation evaluation;
        try {
            evaluation = biEvaluationService.calculateBiEvaluation(taskId, rootObjectId);
        } catch (RuntimeException e) {
            throw new EvaluationException("评定计算失败：" + readable(e));
        }
        BiEvaluation saved = biEvaluationService.selectBiEvaluationByTaskId(taskId);
        if (saved == null) {
            saved = evaluation;
        }
        return result(task, building, project, saved, structureAdjusted);
    }

    /**
     * 批量补算一个项目下未评定的桥。单座失败不阻断其它桥。
     */
    public Map<String, Object> calculateProject(Long projectId, List<Long> taskIds, List<Long> bridgeIds,
                                                Boolean onlySubmitted) {
        if (projectId == null) {
            throw new EvaluationException("projectId 不能为空");
        }
        Project project = projectService.selectProjectById(projectId);
        if (project == null) {
            throw new EvaluationException("未找到项目（projectId=" + projectId + "）");
        }
        assertProjectAccess(project);

        boolean skipUnsubmitted = onlySubmitted == null || onlySubmitted;
        List<Task> tasks = taskService.selectTasksByProject(projectId, project.getYear());
        List<Long> allTaskIds = tasks.stream()
                .map(Task::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<Long> evaluatedTaskIds = evaluatedTaskIds(allTaskIds);

        List<Map<String, Object>> successes = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> skippedEvaluated = new ArrayList<>();
        List<Map<String, Object>> skippedUnsubmitted = new ArrayList<>();

        for (Task task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            if (!targeted(task, taskIds, bridgeIds)) {
                continue;
            }
            if (evaluatedTaskIds.contains(task.getId())) {
                skippedEvaluated.add(bridgeRow(task));
                continue;
            }
            if (skipUnsubmitted && !isSubmitted(task)) {
                skippedUnsubmitted.add(bridgeRow(task));
                continue;
            }
            try {
                successes.add(calculate(task.getId(), task.getBuildingId()));
            } catch (RuntimeException e) {
                Map<String, Object> failure = bridgeRow(task);
                failure.put("message", readable(e));
                failures.add(failure);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        if (notBlank(project.getName())) {
            result.put("projectName", project.getName());
        }
        if (project.getYear() != null) {
            result.put("year", project.getYear());
        }
        result.put("successes", successes);
        result.put("failures", failures);
        result.put("skippedEvaluated", skippedEvaluated);
        result.put("skippedUnsubmitted", skippedUnsubmitted);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", successes.size());
        summary.put("failure", failures.size());
        summary.put("skippedEvaluated", skippedEvaluated.size());
        summary.put("skippedUnsubmitted", skippedUnsubmitted.size());
        result.put("summary", summary);
        result.put("message", projectMessage(successes.size(), failures.size()));
        return result;
    }

    /**
     * 校验当前用户有评定计算权限，与网页 biz:evaluation:calculate 一致。
     */
    public static boolean canEvaluate() {
        return ShiroUtils.getSubject().isPermitted(EVALUATE_PERMISSION);
    }

    static void requireLeaf(Building building) {
        if (!"1".equals(building.getIsLeaf())) {
            throw new EvaluationException("「" + text(building.getName())
                    + "」不是桥幅（叶子桥），请选择具体的左幅、右幅或独立桥再计算评定");
        }
    }

    static boolean isSubmitted(Task task) {
        return task != null && task.getType() != null && task.getType() == SUBMITTED;
    }

    static boolean targeted(Task task, List<Long> taskIds, List<Long> bridgeIds) {
        if (task == null) {
            return false;
        }
        if (hasValues(taskIds) && !taskIds.contains(task.getId())) {
            return false;
        }
        return !hasValues(bridgeIds) || bridgeIds.contains(task.getBuildingId());
    }

    static Map<String, Object> result(Task task, Building building, Project project, BiEvaluation evaluation,
                                      boolean structureAdjusted) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.getId());
        row.put("bridgeId", building.getId());
        if (notBlank(building.getName())) {
            row.put("bridgeName", building.getName());
        }
        if (project != null && project.getYear() != null) {
            row.put("year", project.getYear());
        }
        boolean evaluated = evaluation != null && (evaluation.getSystemLevel() != null
                || evaluation.getSystemScore() != null);
        row.put("evaluated", evaluated);
        Map<String, Object> overall = new LinkedHashMap<>();
        if (evaluation != null && evaluation.getSystemLevel() != null) {
            overall.put("systemLevel", evaluation.getSystemLevel());
        }
        if (evaluation != null && evaluation.getSystemScore() != null) {
            overall.put("systemScore", evaluation.getSystemScore());
        }
        row.put("overall", overall);
        row.put("message", evaluated
                ? "评定计算完成"
                : "评定已执行，但没有生成系统等级和得分，请检查病害数据、结构权重是否完整");
        if (structureAdjusted) {
            row.put("structureAdjusted", true);
        }
        return row;
    }

    /**
     * 任务查询关联出来的 Building 只有名称、根节点等部分字段，没有 isLeaf。
     * 评定必须按桥梁 id 再读一次完整档案，否则叶子桥也会被误判成「不是桥幅」。
     */
    private Building resolveBuilding(Task task) {
        Long buildingId = task.getBuildingId();
        if (buildingId == null && task.getBuilding() != null) {
            buildingId = task.getBuilding().getId();
        }
        Building building = buildingId == null ? null : buildingService.selectBuildingById(buildingId);
        if (building == null) {
            throw new EvaluationException("任务没有关联桥梁，无法计算评定");
        }
        return building;
    }

    /**
     * 与网页 getStatus != 3 时的前置一致：先修正构件数量与权重，再把根节点锁定为状态 3。
     * 这些操作在网页端需要 biz:object:edit，这里也要求同一权限。
     */
    private boolean ensureStructureReady(Long rootObjectId, String bridgeName) {
        BiObject root = biObjectService.selectBiObjectById(rootObjectId);
        if (root == null) {
            throw new EvaluationException("未找到桥梁「" + text(bridgeName) + "」的结构根节点");
        }
        if (LOCKED_STATUS.equals(root.getStatus())) {
            return false;
        }
        if (!ShiroUtils.getSubject().isPermitted(OBJECT_EDIT_PERMISSION)) {
            throw new EvaluationException("桥梁「" + text(bridgeName)
                    + "」的结构树还没有锁定，需要先修正权重并锁定；当前用户没有对象编辑权限（"
                    + OBJECT_EDIT_PERMISSION + "），无法完成前置步骤");
        }
        biObjectService.repairCountsFromSubtree(rootObjectId);
        biObjectService.correctAllWeights(rootObjectId);
        BiObject lock = new BiObject();
        lock.setId(rootObjectId);
        lock.setStatus(LOCKED_STATUS);
        lock.setUpdateBy(ShiroUtils.getLoginName());
        lock.setUpdateTime(new Date());
        if (biObjectService.updateBiObject(lock) <= 0) {
            throw new EvaluationException("锁定桥梁「" + text(bridgeName) + "」的结构树失败，请稍后重试");
        }
        return true;
    }

    private void assertProjectAccess(Project project) {
        SysUser user = userService.selectUserById(ShiroUtils.getUserId());
        if (user == null) {
            throw new EvaluationException("未找到当前登录用户");
        }
        if (user.isAdmin()) {
            return;
        }
        if (user.getDeptId() == null) {
            throw new EvaluationException("当前用户没有部门，无法确认项目权限");
        }
        SysDept dept = deptService.selectDeptById(user.getDeptId());
        Long parentDeptId = DepartmentProjectScope.parentId(dept == null ? null : dept.getParentId());
        if (!DepartmentProjectScope.contains(project, user.getDeptId(), parentDeptId)) {
            throw new EvaluationException("当前用户不在项目「" + text(project.getName())
                    + "」的部门业务范围内，无法计算评定");
        }
    }

    private Set<Long> evaluatedTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return biEvaluationService.selectBiEvaluationByTaskIds(taskIds).stream()
                .map(BiEvaluation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, Object> bridgeRow(Task task) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.getId());
        if (task.getBuildingId() != null) {
            row.put("bridgeId", task.getBuildingId());
        }
        if (task.getBuilding() != null && notBlank(task.getBuilding().getName())) {
            row.put("bridgeName", task.getBuilding().getName());
        }
        return row;
    }

    private static String projectMessage(int success, int failure) {
        if (success == 0 && failure == 0) {
            return "没有需要计算的桥，可能都已评定或没有已提交的桥";
        }
        if (failure == 0) {
            return "已计算 " + success + " 座桥的评定";
        }
        return "计算完成：成功 " + success + " 座，失败 " + failure + " 座";
    }

    private static String readable(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    private static boolean hasValues(List<Long> values) {
        return values != null && !values.isEmpty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}