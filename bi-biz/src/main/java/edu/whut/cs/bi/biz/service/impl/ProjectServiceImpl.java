package edu.whut.cs.bi.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ObjectUtil;
import com.ruoyi.common.core.domain.Ztree;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysDeptService;
import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.Task;
import edu.whut.cs.bi.biz.domain.dto.ProjectUserAssignment;
import edu.whut.cs.bi.biz.domain.enums.ProjectUserRoleEnum;
import edu.whut.cs.bi.biz.mapper.PackageMapper;
import edu.whut.cs.bi.biz.mapper.ProjectMapper;
import edu.whut.cs.bi.biz.mapper.ProjectUserMapper;
import edu.whut.cs.bi.biz.mapper.TaskMapper;
import edu.whut.cs.bi.biz.service.IProjectService;
import org.apache.shiro.util.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 项目Service业务层处理
 *
 * @author chenwenqi
 * @date 2025-04-02
 */
@Service
public class ProjectServiceImpl implements IProjectService {
    @Resource
    private ProjectMapper projectMapper;

    @Resource
    private SysDeptMapper deptMapper;

    @Resource
    private ISysDeptService deptService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private ProjectUserMapper projectUserMapper;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private PackageMapper packageMapper;


    /**
     * 查询项目
     *
     * @param id 项目主键
     * @return 项目
     */
    @Override
    public Project selectProjectById(Long id) {
        return projectMapper.selectProjectById(id);
    }

    /**
     * 查询项目列表
     *
     * @param project 项目
     * @return 项目
     */
    @Override
    public List<Project> selectProjectList(Project project) {
        String select = project.getSelect();
        Long currentUserId = ShiroUtils.getUserId();
        List<String> roles = sysUserMapper.selectUserRoleByUserId(currentUserId);
        SysUser sysUser = sysUserMapper.selectUserById(currentUserId);

        // 检查用户是否有admin角色
        boolean isAdmin = roles.stream().anyMatch(role -> "admin".equals(role));

        // 查询部门 - 设置父部门
        SysDept curSysDept = deptService.selectDeptById(sysUser.getDeptId());

        PageUtils.startPage();
        List<Project> projects = null;
        if (isAdmin || select.equals("platform")) {
            // 超级管理员, 所有数据都能看到
            projects = projectMapper.selectProjectList(project, null, null);
        } else {
            // 部门管理员
            if (select.equals("department")) {

                // 当前登录用户所属Department与bi_project表中owner_dept_id 或 dept_id一致的所有业务实体
                project.setSelectDeptId(sysUser.getDeptId());
                if (curSysDept != null) {
                    project.setParentDeptId(curSysDept.getParentId());
                }

                projects = projectMapper.selectProjectList(project, null, null);
            } else {
                // 当前登录用户关联的业务实体
                projects = projectMapper.selectProjectList(project, currentUserId, null);
            }
        }

        projects.forEach(pj -> {
            // 承担单位
            SysDept dept = deptService.selectDeptById(pj.getDeptId());
            pj.setDept(dept);
            // 所属单位
            SysDept ownerDept = deptService.selectDeptById(pj.getOwnerDeptId());
            pj.setOwnerDept(ownerDept);

            Long parentId = curSysDept == null ? null : curSysDept.getParentId();
            boolean sameParent = parentId != null && (
                    (dept != null && Objects.equals(dept.getDeptId(), parentId))
                            || (ownerDept != null && Objects.equals(ownerDept.getDeptId(), parentId)));
            if (sameParent) {
                pj.setParentDeptId(parentId);
            }

            // 已选择桥梁数
            List<Task> tasks = taskMapper.selectTaskListByProjectId(pj.getId());
            if (CollUtil.isNotEmpty(tasks)) {
                pj.setBridgeCount(tasks.size());
            }
        });
        return projects;
    }

    @Override
    public List<Project> selectDepartmentProjects(Long deptId, Long parentDeptId, Integer year, String name) {
        if (deptId == null) {
            return List.of();
        }
        Project query = new Project();
        query.setSelectDeptId(deptId);
        if (parentDeptId != null && parentDeptId > 0) {
            query.setParentDeptId(parentDeptId);
        }
        query.setYear(year);
        if (name != null && !name.isBlank()) {
            query.setName(name.trim());
        }
        List<Project> projects = projectMapper.selectProjectList(query, null, null);
        return projects == null ? List.of() : projects;
    }

    /**
     * 新增项目
     *
     * @param project 项目
     * @return 结果
     */
    @Override
    @Transactional
    public int insertProject(Project project) {
        project.setCreateTime(DateUtils.getNowDate());

        return projectMapper.insertProject(project);
    }

    /**
     * 修改项目
     *
     * @param project 项目
     * @return 结果
     */
    @Override
    public int updateProject(Project project) {
        project.setUpdateTime(DateUtils.getNowDate());

        if (project.getStatus() != null) {
            // 更新任务状态
            List<Task> tasks = taskMapper.selectTaskListByProjectId(project.getId());
            tasks.forEach(task -> {
                task.setStatus(project.getStatus());
                task.setUpdateTime(DateUtils.getNowDate());
                taskMapper.updateTask(task);
            });
        }

        return projectMapper.updateProject(project);
    }

    /**
     * 批量删除项目
     *
     * @param ids 需要删除的项目主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteProjectByIds(String ids) {
        // 这个地方应该做一次权限校验

        // 删除项目下的任务
        String[] strArray = Convert.toStrArray(ids);
        for (int i = 0; i < strArray.length; i++) {
            Long id = Long.valueOf(strArray[i]);
            taskMapper.deleteTaskByProjectId(id);
            projectUserMapper.deleteProjectUser(id);
        }
        return projectMapper.deleteProjectByIds(strArray);
    }

    /**
     * 删除项目信息
     *
     * @param id 项目主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteProjectById(Long id) {
        // 删除项目下的任务
        taskMapper.deleteTaskByProjectId(id);
        // 删除项目相关的人员关联
        projectUserMapper.deleteProjectUser(id);
        return projectMapper.deleteProjectById(id);
    }

    /**
     * 查询承担单位树列表
     *
     * @param sysDept
     * @return
     */
    @Override
    public List<Ztree> selectDeptTree(SysDept sysDept) {

        List<SysDept> deptList = deptMapper.selectDeptList(sysDept);
        // 设置业主自检的选项
        SysDept dept = new SysDept();
        dept.setDeptId(-1L);
        dept.setParentId(0L);
        dept.setStatus("0");
        dept.setDeptName("业主自检");
        deptList.add(dept);

        List<Ztree> ztrees = deptService.initZtree(deptList);
        return ztrees;
    }

    /**
     * 查询所属单位树列表
     *
     * @param sysDept
     * @return
     */
    @Override
    public List<Ztree> selectOwnerDeptTree(SysDept sysDept) {

        List<SysDept> deptList = deptMapper.selectDeptList(sysDept);

        List<Ztree> ztrees = deptService.initZtree(deptList);
        return ztrees;
    }

    /**
     * 查询项目封装实体
     *
     * @param id 项目主键
     * @return 项目
     */
    @Override
    public Project selectProjectVOById(Long id) {
        Project project = projectMapper.selectProjectById(id);
        if (ObjectUtil.isEmpty(project)) {
            return project;
        }

        // 承担单位
        SysDept dept = deptService.selectDeptById(project.getDeptId());
        project.setDept(dept);
        // 所属单位
        SysDept ownerDept = deptService.selectDeptById(project.getOwnerDeptId());
        project.setOwnerDept(ownerDept);

        // 检测人员
        List<Long> inspectorIds = projectUserMapper.selectUserIdsByProjectAndRole(id, ProjectUserRoleEnum.INSPECTOR.getValue());
        List<SysUser> inspectors = inspectorIds.stream().map(inspectorId -> sysUserMapper.selectUserById(inspectorId)).collect(Collectors.toList());
        project.setInspectors(inspectors);

        // 报告编写人员
        List<Long> authors = projectUserMapper.selectUserIdsByProjectAndRole(id, ProjectUserRoleEnum.AUTHOR.getValue());
        if (!CollectionUtils.isEmpty(authors)) {
            Long authorId = authors.get(0);
            SysUser author = sysUserMapper.selectUserById(authorId);
            project.setAuthor(author);
        }

        // 报告审核人员
        List<Long> reviewers = projectUserMapper.selectUserIdsByProjectAndRole(id, ProjectUserRoleEnum.REVIEWER.getValue());
        if (!CollectionUtils.isEmpty(reviewers)) {
            Long reviewerId = reviewers.get(0);
            SysUser reviewer = sysUserMapper.selectUserById(reviewerId);
            project.setReviewer(reviewer);
        }

        // 报告批准人员
        List<Long> approvers = projectUserMapper.selectUserIdsByProjectAndRole(id, ProjectUserRoleEnum.APPROVER.getValue());
        if (!CollectionUtils.isEmpty(approvers)) {
            Long approverId = approvers.get(0);
            SysUser approver = sysUserMapper.selectUserById(approverId);
            project.setApprover(approver);
        }

        // 关联的任务列表
        List<Task> tasks = taskMapper.selectTaskListByProjectId(id);
        project.setTasks(tasks);

        // 下面非必要信息
        // 关联的建筑列表
//        private List<Building> buildings;
        // 关联的标准列表
//        private List<Standard> standards;
        // 关联的设备列表
//        private List<BiDevice> biDevices;

        return project;
    }

    /**
     * 根据项目ID查询用户列表 （这里是根据项目的承担单位id查询用户)
     *
     * @param projectId 项目ID
     * @return 用户信息集合信息
     */
    @Override
    public List<SysUser> selectUserList(Long projectId) {
        Project project = projectMapper.selectProjectById(projectId);
        // 获取承担单位ID
        if (ObjUtil.isEmpty(project)) {
            return List.of();
        }
        Long deptId = project.getDeptId();
        SysUser sysUser = new SysUser();
        sysUser.setDeptId(deptId);

        return sysUserMapper.selectUserList(sysUser);
    }

    /**
     * 查询项目人员信息
     *
     * @param projectId
     * @return
     */
    @Override
    public ProjectUserAssignment selectProjectUsers(Long projectId) {
        ProjectUserAssignment projectUserAssignment = new ProjectUserAssignment();

        if (projectId == null) {
            return projectUserAssignment;
        }
        projectUserAssignment.setProjectId(projectId);

        // 检测人员
        List<Long> inspectorIds = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.INSPECTOR.getValue());
        projectUserAssignment.setInspectorIds(inspectorIds);
        // 报告编写人员
        List<Long> author = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.AUTHOR.getValue());
        if (author != null && author.size() > 0) {
            projectUserAssignment.setAuthorId(author.get(0));
        }
        // 报告审核人员
        List<Long> reviewer = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.REVIEWER.getValue());
        if (reviewer != null && reviewer.size() > 0) {
            projectUserAssignment.setReviewerId(reviewer.get(0));
        }
        // 报告批准人员
        List<Long> approver = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.APPROVER.getValue());
        if (approver != null && approver.size() > 0) {
            projectUserAssignment.setApproverId(approver.get(0));
        }

        return projectUserAssignment;
    }

    /**
     * 保存项目人员信息
     *
     * @param assignment
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class )
    public int saveProjectUserAssignments(ProjectUserAssignment assignment) {
        if (ObjUtil.isEmpty(assignment) || assignment.getProjectId() == null) {
            throw new ServiceException("传入的参数不能为空");
        }

        Long projectId = assignment.getProjectId();
        // 如果存在项目相关人员，则删除，再添加
        int count = projectUserMapper.countProjectUser(projectId);
        List<Long> preInspectors = projectUserMapper.selectUserIdsByProjectAndRole(projectId, ProjectUserRoleEnum.INSPECTOR.getValue());
        if (count > 0) {
            projectUserMapper.deleteProjectUser(projectId);
        }
        List<Long> inspectorIds = assignment.getInspectorIds();
        int save = 0;
        if (inspectorIds != null && !inspectorIds.isEmpty()) {
            save = projectUserMapper.saveProjectUser(projectId, inspectorIds, ProjectUserRoleEnum.INSPECTOR.getValue());
        }

        // 找出被删除的（旧集合有，新的没有）
        List<Long> deletedInspectors = preInspectors.stream()
                .filter(id -> !inspectorIds.contains(id))
                .collect(Collectors.toList());

        // 找出新增的（新集合有，旧的没有）
        List<Long> addedInspectors = inspectorIds.stream()
                .filter(id -> !preInspectors.contains(id))
                .collect(Collectors.toList());

        List<Long> changedInspectors = new ArrayList<>();
        changedInspectors.addAll(deletedInspectors);
        changedInspectors.addAll(addedInspectors);

        //更新所有被删除的或者新增的检测人员的数据包的update_time
        if(!changedInspectors.isEmpty()) {
            packageMapper.batchUpdateUpdateTimeNow(changedInspectors);
        }

        Long authorId = assignment.getAuthorId();
        if (authorId != null) {
            save += projectUserMapper.saveProjectUser(projectId, List.of(authorId), ProjectUserRoleEnum.AUTHOR.getValue());

        }

        Long reviewerId = assignment.getReviewerId();
        if (reviewerId != null) {
            save += projectUserMapper.saveProjectUser(projectId, List.of(reviewerId), ProjectUserRoleEnum.REVIEWER.getValue());
        }

        Long approverId = assignment.getApproverId();
        if (approverId != null) {
            save += projectUserMapper.saveProjectUser(projectId, List.of(approverId), ProjectUserRoleEnum.APPROVER.getValue());
        }

        projectMapper.updateProjectTimeByProjectId(projectId);
        return save;
    }

    /**
     * 根据用户ID和角色查询项目列表
     *
     * @param userId
     * @param value
     * @return
     */
    @Override
    public List<Project> selectProjectListByUserIdAndRole(Project projectQuery, Long userId, String value) {
        List<Project> projects = projectMapper.selectProjectList(projectQuery, userId, value);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        projects.forEach(project -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Long deptId = project.getDeptId();
                project.setDept(deptMapper.selectDeptById(deptId));

                Long ownerDeptId = project.getOwnerDeptId();
                project.setOwnerDept(deptMapper.selectDeptById(ownerDeptId));

                List<SysUser> inspectors = projectUserMapper.selectUsersByProjectAndRole(project.getId(), ProjectUserRoleEnum.INSPECTOR.getValue());
                project.setInspectors(inspectors);
            });
            futures.add(future);
        });
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return projects;
    }


    public List<Project> selectProjectListForReport(Project project) {
        Long currentUserId = ShiroUtils.getUserId();
        List<String> roles = sysUserMapper.selectUserRoleByUserId(currentUserId);
        SysUser sysUser = sysUserMapper.selectUserById(currentUserId);

        // 检查用户是否有管理员角色
        boolean isAdmin = roles.stream().anyMatch(role -> "admin".equals(role) || "system_admin".equals(role) || "business_admin".equals(role));
        // 查询部门 - 设置父部门
        SysDept curSysDept = deptService.selectDeptById(sysUser.getDeptId());
        List<Project> projects = null;
        if (isAdmin) {
            // 超级管理员, 所有数据都能看到
            projects = projectMapper.selectProjectList(project, null, null);
        } else {
            if(roles.stream().anyMatch(role -> "department_business_admin".equals(role))) {
                // 部门管理员
                // 当前登录用户所属Department与bi_project表中ower_dept_id 或 dept_id一致的所有业务实体
                project.setSelectDeptId(sysUser.getDeptId());
                project.setParentDeptId(curSysDept.getParentId());
                projects = projectMapper.selectProjectList(project, null, null);
            }else {
                projects = projectMapper.selectProjectList(project, currentUserId, null);
            }
        }
        return projects;
    }
}
