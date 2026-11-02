package edu.whut.cs.bi.biz.service;

import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.Task;

import java.io.IOException;
import java.util.List;

/**
 * 任务Service接口
 *
 * @author chenwenqi
 * @date 2025-04-07
 */
public interface ITaskService
{
    /**
     * 查询任务
     *
     * @param id 任务主键
     * @return 任务
     */
    public Task selectTaskById(Long id);

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务集合
     */
    public List<Task> selectTaskList(Task task);

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务集合
     */
    public List<Task> selectTaskVOList(Task task);

    /**
     * 查询任务列表
     *
     * @param task 任务
     * @return 任务集合
     */
    public List<Task> selectTaskList(Task task, String select);


    /**
     * 修改任务
     *
     * @param task 任务
     * @return 结果
     */
    public int updateTask(Task task);

    /**
     * 批量删除任务
     *
     * @param ids 需要删除的任务主键集合
     * @return 结果
     */
    public int deleteTaskByIds(String ids);

    /**
     * 删除任务信息
     *
     * @param id 任务主键
     * @return 结果
     */
    public int deleteTaskById(Long id);

    /**
     * 新增任务
     *
     * @param task 任务
     * @return 结果
     */
    public int insertTask(Task task);


    /**
     * 批量保存任务
     *
     * @param projectId
     * @param buildingIds
     * @return
     */
    int batchInsertTasks(Long projectId, List<Long> buildingIds);

    /**
     * 删除任务
     *
     * @param projectId
     * @param buildingId
     */
    int removeTask(Long projectId, Long buildingId);

    /**
     * 批量删除任务
     *
     * @param projectId
     * @param buildingIds
     * @return
     */
    int batchRemoveTasks(Long projectId, List<Long> buildingIds);

    /**
     * 通过桥梁id删除任务
     */
    int deleteTaskByBuildingId(Long buildingId);

    /**
     * 通过项目id删除任务
     */
    int deleteTaskByProjectId(Long projectId);

    /**
     * 通过ids批量查询任务列表（包含Building信息）
     *
     * @param ids 任务ID列表
     * @return 任务集合
     */
    List<Task> selectTaskListByIds(List<Long> ids);

    /**
     * 按桥梁查询未删除的检测任务，可选项目年份。不做分页，也不按当前用户收窄。
     */
    List<Task> selectTasksByBuildingId(Long buildingId, Integer year);

    /**
     * 查询某个用户参与的项目任务。可按项目和年份收窄。不做分页。
     */
    List<Task> selectTasksForUser(Long userId, Long projectId, Integer year);

    /**
     * 查询某个项目下未删除的检测任务，可选项目年份。不做分页，也不按项目人员收窄。
     */
    List<Task> selectTasksByProject(Long projectId, Integer year);
}
