package edu.whut.cs.bi.api.evaluation;

import edu.whut.cs.bi.biz.domain.BiEvaluation;
import edu.whut.cs.bi.biz.domain.BiObject;
import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.Component;
import edu.whut.cs.bi.biz.domain.Condition;
import edu.whut.cs.bi.biz.domain.Score;
import edu.whut.cs.bi.biz.domain.Task;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把已保存的桥幅评定组装成和评定页一致的结构。不触发重新计算。
 */
public final class BridgeEvaluationQuery {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EVALUATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String SUPERSTRUCTURE = "上部结构";
    private static final String SUBSTRUCTURE = "下部结构";
    private static final String DECK = "桥面系";

    private BridgeEvaluationQuery() {
    }

    public static Map<String, Object> build(Building building, Integer requestedYear, List<Task> tasks,
                                            Map<Long, BiEvaluation> evaluationsByTaskId,
                                            List<Condition> conditions, Map<Long, String> parentNameByObjectId,
                                            Map<Long, List<Score>> scoresByConditionId) {
        Selection selection = select(building, requestedYear, tasks, evaluationsByTaskId);
        if (!selection.evaluated) {
            Map<String, Object> miss = base(building, requestedYear);
            miss.put("evaluated", false);
            miss.put("message", selection.message);
            return miss;
        }
        Task task = selection.task;
        BiEvaluation evaluation = selection.evaluation;
        Integer year = task.getProject() == null ? requestedYear : task.getProject().getYear();
        Map<String, Object> body = base(building, year);
        body.put("evaluated", true);
        body.put("taskId", task.getId());
        if (task.getProjectId() != null) {
            body.put("projectId", task.getProjectId());
        }
        if (task.getProject() != null && notBlank(task.getProject().getName())) {
            body.put("projectName", task.getProject().getName());
        }
        String evaluatedAt = format(evaluation.getUpdateTime() != null ? evaluation.getUpdateTime() : evaluation.getCreateTime());
        if (evaluatedAt != null) {
            body.put("evaluatedAt", evaluatedAt);
        }
        body.put("overall", overall(evaluation));
        body.put("structures", structures(evaluation, conditions, parentNameByObjectId, scoresByConditionId));
        return body;
    }

    public static Selection select(Building building, Integer requestedYear, List<Task> tasks,
                                   Map<Long, BiEvaluation> evaluationsByTaskId) {
        List<Task> matched = new ArrayList<>();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null || task.getId() == null) {
                    continue;
                }
                if (requestedYear != null) {
                    Integer taskYear = task.getProject() == null ? null : task.getProject().getYear();
                    if (!requestedYear.equals(taskYear)) {
                        continue;
                    }
                }
                matched.add(task);
            }
        }
        Task chosen = null;
        BiEvaluation chosenEvaluation = null;
        for (Task task : matched) {
            BiEvaluation evaluation = evaluationsByTaskId == null ? null : evaluationsByTaskId.get(task.getId());
            if (evaluation == null) {
                continue;
            }
            if (chosen == null || newer(evaluation, chosenEvaluation)) {
                chosen = task;
                chosenEvaluation = evaluation;
            }
        }
        if (chosen != null) {
            return new Selection(true, null, chosen, chosenEvaluation);
        }
        return new Selection(false, missMessage(building, requestedYear, !matched.isEmpty()), null, null);
    }

    private static String missMessage(Building building, Integer requestedYear, boolean hadTask) {
        if (!hadTask && building != null && !"1".equals(building.getIsLeaf())) {
            return "该桥没有已保存的评定，请改用分幅桥梁的 id";
        }
        if (!hadTask && requestedYear != null) {
            return "该年份无检测任务";
        }
        if (requestedYear != null) {
            return "该年份尚未计算评定";
        }
        return "尚未计算评定";
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

    private static Map<String, Object> base(Building building, Integer year) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (building != null) {
            body.put("bridgeId", building.getId());
            if (notBlank(building.getName())) {
                body.put("bridgeName", building.getName());
            }
            if (notBlank(building.getParentName())) {
                body.put("parentName", building.getParentName());
            }
        }
        if (year != null) {
            body.put("year", year);
        }
        return body;
    }

    private static Map<String, Object> overall(BiEvaluation evaluation) {
        Map<String, Object> overall = new LinkedHashMap<>();
        putIfPresent(overall, "level", evaluation.getLevel());
        putIfPresent(overall, "systemLevel", evaluation.getSystemLevel());
        putIfPresent(overall, "systemScore", evaluation.getSystemScore());
        return overall;
    }

    private static List<Map<String, Object>> structures(BiEvaluation evaluation, List<Condition> conditions,
                                                        Map<Long, String> parentNameByObjectId,
                                                        Map<Long, List<Score>> scoresByConditionId) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        parts.put(SUPERSTRUCTURE, new ArrayList<>());
        parts.put(SUBSTRUCTURE, new ArrayList<>());
        parts.put(DECK, new ArrayList<>());
        List<Map<String, Object>> other = new ArrayList<>();
        if (conditions != null) {
            for (Condition condition : conditions) {
                if (condition == null) {
                    continue;
                }
                String parentName = parentNameByObjectId == null ? null : parentNameByObjectId.get(condition.getBiObjectId());
                String group = groupOf(parentName);
                Map<String, Object> part = part(condition, scoresByConditionId);
                if (SUPERSTRUCTURE.equals(group) || SUBSTRUCTURE.equals(group) || DECK.equals(group)) {
                    parts.get(group).add(part);
                } else {
                    other.add(part);
                }
            }
        }
        List<Map<String, Object>> structures = new ArrayList<>();
        structures.add(structure(SUPERSTRUCTURE, evaluation.getSuperstructureLevel(), evaluation.getSuperstructureScore(), parts.get(SUPERSTRUCTURE)));
        structures.add(structure(SUBSTRUCTURE, evaluation.getSubstructureLevel(), evaluation.getSubstructureScore(), parts.get(SUBSTRUCTURE)));
        structures.add(structure(DECK, evaluation.getDeckSystemLevel(), evaluation.getDeckSystemScore(), parts.get(DECK)));
        if (!other.isEmpty()) {
            structures.add(structure("其他", null, null, other));
        }
        return structures;
    }

    private static String groupOf(String parentName) {
        if (parentName == null) {
            return "其他";
        }
        if (parentName.startsWith("上部")) {
            return SUPERSTRUCTURE;
        }
        if (parentName.startsWith("下部")) {
            return SUBSTRUCTURE;
        }
        if (parentName.startsWith("桥面")) {
            return DECK;
        }
        return "其他";
    }

    private static Map<String, Object> structure(String name, Integer level, BigDecimal score, List<Map<String, Object>> parts) {
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("name", name);
        putIfPresent(structure, "level", level);
        putIfPresent(structure, "score", score);
        structure.put("parts", parts);
        return structure;
    }

    private static Map<String, Object> part(Condition condition, Map<Long, List<Score>> scoresByConditionId) {
        Map<String, Object> part = new LinkedHashMap<>();
        BiObject object = condition.getBiObject();
        String name = object == null ? null : object.getName();
        part.put("name", notBlank(name) ? name : "未命名部位");
        putIfPresent(part, "level", condition.getLevel());
        putIfPresent(part, "score", condition.getScore());
        BigDecimal weight = object == null || object.getWeight() == null ? condition.getWeight() : object.getWeight();
        putIfPresent(part, "weight", weight);
        putIfPresent(part, "componentCount", condition.getComponentsCount());
        part.put("componentScores", componentScores(condition.getId(), scoresByConditionId));
        return part;
    }

    private static List<Map<String, Object>> componentScores(Long conditionId, Map<Long, List<Score>> scoresByConditionId) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Score> scores = conditionId == null || scoresByConditionId == null ? null : scoresByConditionId.get(conditionId);
        if (scores == null) {
            return items;
        }
        for (Score score : scores) {
            if (score == null || score.getScore() == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            Component component = score.getComponent();
            if (component != null && notBlank(component.getCode())) {
                item.put("code", component.getCode());
            }
            item.put("score", score.getScore());
            items.add(item);
        }
        return items;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String format(Date date) {
        if (date == null) {
            return null;
        }
        return EVALUATED_AT.format(date.toInstant().atZone(SHANGHAI));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static final class Selection {
        public final boolean evaluated;
        public final String message;
        public final Task task;
        public final BiEvaluation evaluation;

        Selection(boolean evaluated, String message, Task task, BiEvaluation evaluation) {
            this.evaluated = evaluated;
            this.message = message;
            this.task = task;
            this.evaluation = evaluation;
        }
    }
}
