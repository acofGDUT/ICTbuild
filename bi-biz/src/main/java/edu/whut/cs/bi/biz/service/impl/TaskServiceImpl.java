package edu.whut.cs.bi.biz.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.mapper.SysUserMapper;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.domain.enums.ProjectUserRoleEnum;
import edu.whut.cs.bi.biz.mapper.*;

import edu.whut.cs.bi.biz.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 任务Service业务层处理
 *
 * @author chenwenqi
 * @date 2025-04-07
 */
@Service
public class TaskServiceImpl implements ITaskService {
    @Resource
    private TaskMapper taskMapper;

    @Resource
    private BuildingMapper buildingMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private IBiEvaluationService evaluationService;

    @Resource
    private PropertyMapper propertyMapper;

    @Resource
    private IBiObjectService biObjectService;

    @Resource
    private IBiTemplateObjectService biTemplateObjectService;

    @Autowired
    private ProjectMapper projectMapper;

    @Resource
    private ProjectUserMapper projectUserMapper;

    @Resource
    private PackageMapper packageMapper;

    /**
     * 查询任务
     *
     * @param id 任务主键
     * @return 任务
     */
    @Override
    public Task selectTaskById(Long id) {
        Task task = taskMapper.selectTaskById(id);
        if (ObjUtil.isNotEmpty(task)) {
            try {
                task.setBuilding(buildingMapper.selectBuildingById(task.getBuildingId()));
            } catch (Exception e) {
                throw new RuntimeException("桥梁已经被删除！");
            }
        }
        return task;
    }

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务
     */
    @Override
    public List<Task> selectTaskList(Task task) {
        String select = task.getSelect();

        Long currentUserId = ShiroUtils.getUserId();
        List<String> roles = sysUserMapper.selectUserRoleByUserId(currentUserId);
        SysUser sysUser = sysUserMapper.selectUserById(currentUserId);

        // 检查用户是否有admin角色
        boolean isAdmin = roles.stream().anyMatch(role -> "admin".equals(role));

        PageUtils.startPage();
        List<Task> tasks = null;
        if (isAdmin || select.equals("platform")) {
            // 超级管理员, 所有数据都能看到
            tasks = taskMapper.selectTaskList(task, null);
        } else {
            // 部门管理员
            if (select.equals("department")) {
                if (task.getProject() == null) {
                    // 当前登录用户所属Department与bi_project表中ower_dept_id 或 dept_id一致的所有业务实体
                    task.setSelectDeptId(sysUser.getDeptId());
                }

                tasks = taskMapper.selectTaskList(task, null);
            } else {
                // 当前登录用户关联的业务实体
                tasks = taskMapper.selectTaskList(task, currentUserId);
            }
        }

        tasks.forEach(t -> {
            BiEvaluation biEvaluation = evaluationService.selectBiEvaluationByTaskId(t.getId());
            if (biEvaluation != null) {
                t.setEvaluationResult(biEvaluation.getSystemLevel());
            }
        });


        return tasks;
    }

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务
     */
    @Override
    public List<Task> selectTaskList(Task task, String select) {
        // 权限区分
        task.setSelect(select);

        return selectTaskList(task);
    }

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务
     */
    @Override
    public List<Task> selectTaskVOList(Task task) {
        // 1. 同步查询基础任务列表
        List<Task> tasks = taskMapper.selectTaskList(task, null);

        if (ObjUtil.isEmpty(tasks)) {
            return tasks;
        }

        // 2. 准备异步任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 3. 为每个任务创建异步处理
        for (Task t : tasks) {
            CompletableFuture<Void> future = CompletableFuture
                    // 异步获取评价结果
                    .supplyAsync(() -> {
                        try {
                            return Optional.ofNullable(evaluationService.selectBiEvaluationByTaskId(t.getId()));
                        } catch (Exception e) {
                            return Optional.<BiEvaluation>empty();
                        }
                    })
                    .thenCombineAsync(
                            // 异步获取属性数据
                            CompletableFuture.supplyAsync(() -> {
                                try {
                                    if (t.getBuilding() != null && t.getBuilding().getRootPropertyId() != null) {
                                        return Optional.ofNullable(
                                                propertyMapper.selectAllChildrenById(t.getBuilding().getRootPropertyId())
                                        );
                                    }
                                    return Optional.<List<Property>>empty();
                                } catch (Exception e) {
                                    return Optional.<List<Property>>empty();
                                }
                            }),
                            // 合并两个异步结果并设置到Task对象
                            (biEvaluationOpt, propertiesOpt) -> {
                                biEvaluationOpt.ifPresent(biEvaluation ->
                                        t.setEvaluationResult(biEvaluation.getSystemLevel())
                                );

                                propertiesOpt.ifPresent(properties -> {
                                    Building building = t.getBuilding();
                                    building.setBuildingCode(findPropertyValue(properties, "桥梁编号"));
                                    building.setRouteCode(findPropertyValue(properties, "路线编号"));
                                    building.setRouteName(findPropertyValue(properties, "路线名称"));
                                    building.setBridgePileNumber(findPropertyValue(properties, "桥位桩号"));
                                    building.setBridgeLength(findPropertyValue(properties, "桥梁全长(m)"));
                                    t.setBuilding(building);
                                });
                                return null;
                            }
                    )
                    .thenComposeAsync(aVoid -> {
                        // 异步获取桥梁类型
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                Building building = t.getBuilding();
                                if (building != null && building.getRootObjectId() != null) {
                                    // 获取根对象
                                    BiObject rootObject = biObjectService.selectBiObjectById(building.getRootObjectId());
                                    if (rootObject != null && rootObject.getTemplateObjectId() != null) {
                                        // 获取模板对象
                                        BiTemplateObject templateObject = biTemplateObjectService.selectBiTemplateObjectById(rootObject.getTemplateObjectId());
                                        if (templateObject != null && templateObject.getName() != null) {
                                            String templateName = templateObject.getName();
                                            // 根据模板名称设置桥梁类型
                                            if (templateName.length() >= 3) {
                                                String suffix = templateName.substring(templateName.length() - 3);
                                                if ("梁式桥".equals(suffix)) {
                                                    building.setBridgeType(1);
                                                } else if (templateName.length() >= 2 && "拱桥".equals(templateName.substring(templateName.length() - 2))) {
                                                    building.setBridgeType(2);
                                                } else if ("悬索桥".equals(suffix)) {
                                                    building.setBridgeType(3);
                                                } else if ("斜拉桥".equals(suffix)) {
                                                    building.setBridgeType(4);
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // 忽略异常，保持桥梁类型为null
                            }
                            return null;
                        });
                    });

            futures.add(future);
        }

        // 4. 等待所有异步任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            throw new RuntimeException("异步处理任务数据时出错", e);
        }

        return tasks;
    }

    /**
     * 从属性树中查找特定属性值
     */
    private String findPropertyValue(List<Property> propertyTree, String propertyName) {
        return propertyTree.stream()
                .filter(p -> propertyName.equals(p.getName()))
                .findFirst()
                .map(Property::getValue)
                .orElse(null);
    }

    /**
     * 新增任务
     *
     * @param task 任务
     * @return 结果
     */
    @Override
    @Transactional
    public int insertTask(Task task) {
        task.setCreateTime(DateUtils.getNowDate());
        int result = taskMapper.insertTask(task);
        List<Long> users = projectUserMapper.selectUserIdsByProjectAndRole(task.getProjectId(), ProjectUserRoleEnum.INSPECTOR.getValue());
        if(!users.isEmpty()) {
            packageMapper.batchUpdateUpdateTimeNow(users);
        }
        projectMapper.updateProjectTimeByProjectId(task.getBuildingId());

        return result;
    }

    /**
     * 修改任务
     *
     * @param task 任务
     * @return 结果
     */
    @Override
    @Transactional
    public int updateTask(Task task) {
        task.setUpdateTime(DateUtils.getNowDate());

        projectMapper.updateProjectTimeByProjectId(task.getBuildingId());

        return taskMapper.updateTask(task);
    }

    /**
     * 批量删除任务
     *
     * @param ids 需要删除的任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteTaskByIds(String ids) {
        List<Task> taskList = taskMapper.selectTaskByIds(ids);
        for (Task task : taskList) {
            projectMapper.updateProjectTimeByProjectId(task.getBuildingId());
        }

        return taskMapper.deleteTaskByIds(Convert.toStrArray(ids));
    }

    /**
     * 删除任务信息
     *
     * @param id 任务主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteTaskById(Long id) {
        Task task = taskMapper.selectTaskById(id);
        projectMapper.updateProjectTimeByProjectId(task.getBuildingId());

        return taskMapper.deleteTaskById(id);
    }

    /**
     * 批量保存任务
     *
     * @param projectId
     * @param buildingIds
     * @return
     */
    @Override
    @Transactional
    public int batchInsertTasks(Long projectId, List<Long> buildingIds) {
        if (ObjUtil.isEmpty(projectId) || ObjUtil.isEmpty(buildingIds)) {
            throw new ServiceException("传入的参数不能为空");
        }

        Project project = projectMapper.selectProjectById(projectId);
        project.setUpdateTime(DateUtils.getNowDate());
        projectMapper.updateProject(project);
        List<Long> users = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.INSPECTOR.getValue());
        if (!users.isEmpty()) {
            packageMapper.batchUpdateUpdateTimeNow(users);
        }

        return taskMapper.batchInsertTask(projectId, buildingIds, ShiroUtils.getLoginName());
    }

    /**
     * 删除任务
     *
     * @param projectId
     * @param buildingId
     * @return
     */
    @Override
    @Transactional
    public int removeTask(Long projectId, Long buildingId) {
        if (ObjUtil.isEmpty(projectId) || ObjUtil.isEmpty(buildingId)) {
            throw new ServiceException("传入的参数不能为空");
        }

        Project project = projectMapper.selectProjectById(projectId);
        project.setUpdateTime(DateUtils.getNowDate());
        projectMapper.updateProject(project);
        List<Long> users = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.INSPECTOR.getValue());
        if(!users.isEmpty()) {
            packageMapper.batchUpdateUpdateTimeNow(users);
        }

        return taskMapper.deleteTaskByProjectIdAndBuildingId(projectId, buildingId);
    }

    /**
     * 批量删除项目关联建筑信息
     *
     * @param projectId
     * @param buildingIds
     * @return
     */
    @Override
    public int batchRemoveTasks(Long projectId, List<Long> buildingIds) {
        if (ObjUtil.isEmpty(projectId) || ObjUtil.isEmpty(buildingIds)) {
            throw new ServiceException("传入的参数不能为空");
        }

        Project project = projectMapper.selectProjectById(projectId);
        project.setUpdateTime(DateUtils.getNowDate());
        projectMapper.updateProject(project);
        List<Long> users = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.INSPECTOR.getValue());
        if(!users.isEmpty()) {
            packageMapper.batchUpdateUpdateTimeNow(users);
        }

        return taskMapper.batchDeleteTaskByProjectIdAndBuildingIds(projectId, buildingIds);
    }

    @Override
    public int deleteTaskByBuildingId(Long buildingId) {
        return taskMapper.deleteTaskByBuildingId(buildingId);
    }

    @Override
    public int deleteTaskByProjectId(Long projectId) {
        return taskMapper.deleteTaskByProjectId(projectId);
    }

    /**
     * 通过ids批量查询任务列表（包含Building信息）
     *
     * @param ids 任务ID列表
     * @return 任务集合
     */
    @Override
    public List<Task> selectTaskListByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return taskMapper.selectTaskListByIds(ids);
    }

    @Override
    public List<Task> selectTasksByBuildingId(Long buildingId, Integer year) {
        if (buildingId == null) {
            return new ArrayList<>();
        }
        Task query = new Task();
        query.setBuildingId(buildingId);
        if (year != null) {
            Project project = new Project();
            project.setYear(year);
            query.setProject(project);
        }
        List<Task> tasks = taskMapper.selectTaskList(query, null);
        return tasks == null ? new ArrayList<>() : tasks;
    }

    @Override
    public List<Task> selectTasksForUser(Long userId, Long projectId, Integer year) {
        if (userId == null) {
            return new ArrayList<>();
        }
        Task query = new Task();
        query.setProjectId(projectId);
        if (year != null) {
            Project project = new Project();
            project.setYear(year);
            query.setProject(project);
        }
        List<Task> tasks = taskMapper.selectTaskList(query, userId);
        return tasks == null ? new ArrayList<>() : tasks;
    }

    @Override
    public List<Task> selectTasksByProject(Long projectId, Integer year) {
        if (projectId == null) {
            return new ArrayList<>();
        }
        Task query = new Task();
        query.setProjectId(projectId);
        if (year != null) {
            Project project = new Project();
            project.setYear(year);
            query.setProject(project);
        }
        List<Task> tasks = taskMapper.selectTaskList(query, null);
        return tasks == null ? new ArrayList<>() : tasks;
    }

}
