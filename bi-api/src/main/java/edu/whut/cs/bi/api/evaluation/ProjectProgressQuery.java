package edu.whut.cs.bi.api.evaluation;

import edu.whut.cs.bi.biz.domain.BiEvaluation;
import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.Task;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把部门业务里的一个项目组装成进度清单：是否已提交、是否已评定、系统等级和得分。
 */
public final class ProjectProgressQuery {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ProjectProgressQuery() {
    }

    public static Map<String, Object> build(Integer year, Long projectId, List<MemberProject> projects,
                                            List<Task> tasks, List<BiEvaluation> evaluations) {
        Map<Long, BiEvaluation> latest = latestByTaskId(evaluations);
        Map<Long, List<Task>> tasksByProject = new LinkedHashMap<>();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null || task.getProjectId() == null) {
                    continue;
                }
                tasksByProject.computeIfAbsent(task.getProjectId(), id -> new ArrayList<>()).add(task);
            }
        }
        List<Map<String, Object>> projectRows = new ArrayList<>();
        if (projects != null) {
            for (MemberProject member : projects) {
                if (member == null || member.project == null || member.project.getId() == null) {
                    continue;
                }
                projectRows.add(projectRow(member, tasksByProject.getOrDefault(member.project.getId(), List.of()), latest));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (year != null) {
            body.put("year", year);
        }
        if (projectId != null) {
            body.put("projectId", projectId);
        }
        body.put("projects", projectRows);
        return body;
    }

    private static Map<String, Object> projectRow(MemberProject member, List<Task> tasks, Map<Long, BiEvaluation> latest) {
        Project project = member.project;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectId", project.getId());
        if (notBlank(project.getName())) {
            row.put("projectName", project.getName());
        }
        if (project.getYear() != null) {
            row.put("year", project.getYear());
        }
        row.put("roles", member.roles == null ? List.of() : member.roles);
        List<Map<String, Object>> bridges = new ArrayList<>();
        int submitted = 0;
        int evaluated = 0;
        int submittedUnevaluated = 0;
        int unsubmittedUnevaluated = 0;
        Map<String, Integer> byLevel = new TreeMap<>();
        for (Task task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            BiEvaluation evaluation = latest.get(task.getId());
            boolean isSubmitted = isSubmitted(task);
            boolean isEvaluated = evaluation != null;
            if (isSubmitted) {
                submitted++;
            }
            if (isEvaluated) {
                evaluated++;
                if (evaluation.getSystemLevel() != null) {
                    byLevel.merge(String.valueOf(evaluation.getSystemLevel()), 1, Integer::sum);
                }
            } else if (isSubmitted) {
                submittedUnevaluated++;
            } else {
                unsubmittedUnevaluated++;
            }
            bridges.add(bridgeRow(task, evaluation, isSubmitted, isEvaluated));
        }
        int total = bridges.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("submitted", submitted);
        summary.put("unsubmitted", total - submitted);
        summary.put("evaluated", evaluated);
        summary.put("unevaluated", total - evaluated);
        summary.put("submittedUnevaluated", submittedUnevaluated);
        summary.put("unsubmittedUnevaluated", unsubmittedUnevaluated);
        summary.put("complete", total > 0 && evaluated == total);
        summary.put("byLevel", byLevel);
        row.put("summary", summary);
        row.put("bridges", bridges);
        return row;
    }

    private static Map<String, Object> bridgeRow(Task task, BiEvaluation evaluation, boolean submitted, boolean evaluated) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.getId());
        if (task.getBuildingId() != null) {
            row.put("bridgeId", task.getBuildingId());
        }
        Building building = task.getBuilding();
        if (building != null) {
            if (notBlank(building.getName())) {
                row.put("bridgeName", building.getName());
            }
            if (notBlank(building.getParentName())) {
                row.put("parentName", building.getParentName());
            }
        }
        String status = statusLabel(task.getStatus());
        if (status != null) {
            row.put("status", status);
        }
        row.put("submitted", submitted);
        row.put("evaluated", evaluated);
        if (evaluated) {
            putIfPresent(row, "systemLevel", evaluation.getSystemLevel());
            putIfPresent(row, "systemScore", evaluation.getSystemScore());
        }
        String updateTime = format(task.getUpdateTime());
        if (updateTime != null) {
            row.put("updateTime", updateTime);
        }
        return row;
    }

    static Map<Long, BiEvaluation> latestByTaskId(List<BiEvaluation> evaluations) {
        Map<Long, BiEvaluation> latest = new LinkedHashMap<>();
        if (evaluations == null) {
            return latest;
        }
        for (BiEvaluation evaluation : evaluations) {
            if (evaluation == null || evaluation.getTaskId() == null) {
                continue;
            }
            BiEvaluation current = latest.get(evaluation.getTaskId());
            if (current == null || newer(evaluation, current)) {
                latest.put(evaluation.getTaskId(), evaluation);
            }
        }
        return latest;
    }

    private static boolean newer(BiEvaluation candidate, BiEvaluation current) {
        Date candidateTime = candidate.getUpdateTime() != null ? candidate.getUpdateTime() : candidate.getCreateTime();
        Date currentTime = current.getUpdateTime() != null ? current.getUpdateTime() : current.getCreateTime();
        if (candidateTime == null && currentTime == null) {
            return idOf(candidate) > idOf(current);
        }
        if (candidateTime == null) {
            return false;
        }
        if (currentTime == null) {
            return true;
        }
        int compared = candidateTime.compareTo(currentTime);
        if (compared != 0) {
            return compared > 0;
        }
        return idOf(candidate) > idOf(current);
    }

    private static long idOf(BiEvaluation evaluation) {
        return evaluation.getId() == null ? 0L : evaluation.getId();
    }

    private static boolean isSubmitted(Task task) {
        return task.getType() != null && task.getType() == 1;
    }

    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if ("0".equals(status)) {
            return "正常";
        }
        if ("1".equals(status)) {
            return "停用";
        }
        return status;
    }

    private static String format(Date date) {
        if (date == null) {
            return null;
        }
        return UPDATED_AT.format(date.toInstant().atZone(SHANGHAI));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static final class MemberProject {
        public final Project project;
        public final List<String> roles;

        public MemberProject(Project project, List<String> roles) {
            this.project = project;
            this.roles = roles;
        }
    }
}
