package edu.whut.cs.bi.biz.service;

import java.io.IOException;
import java.util.List;

import com.ruoyi.common.core.domain.Ztree;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import edu.whut.cs.bi.biz.domain.Project;
import edu.whut.cs.bi.biz.domain.dto.ProjectUserAssignment;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目Service接口
 *
 * @author chenwenqi
 * @date 2025-04-02
 */
public interface IProjectService
{
    /**
     * 查询项目
     *
     * @param id 项目主键
     * @return 项目
     */
    public Project selectProjectById(Long id);

    /**
     * 查询项目列表
     *
     * @param project 项目
     * @return 项目集合
     */
    public List<Project> selectProjectList(Project project);

    /**
     * 部门业务范围：承担单位或所属单位是该部门，或是它的直接上级。不做分页，也不按项目人员收窄。
     */
    List<Project> selectDepartmentProjects(Long deptId, Long parentDeptId, Integer year, String name);

    /**
     * 新增项目
     *
     * @param project 项目
     * @return 结果
     */
    public int insertProject(Project project);

    /**
     * 修改项目
     *
     * @param project 项目
     * @return 结果
     */
    public int updateProject(Project project);

    /**
     * 批量删除项目
     *
     * @param ids 需要删除的项目主键集合
     * @return 结果
     */
    public int deleteProjectByIds(String ids);

    /**
     * 删除项目信息
     *
     * @param id 项目主键
     * @return 结果
     */
    public int deleteProjectById(Long id);

    /**
     * 查询承担单位树结构信息
     *
     * @param sysDept
     * @return
     */
    List<Ztree> selectDeptTree(SysDept sysDept);

    /**
     * 查询所属单位树结构信息
     *
     * @param sysDept
     * @return
     */
    List<Ztree> selectOwnerDeptTree(SysDept sysDept);

    /**
     * 查询项目封装实体
     *
     * @param id 项目主键
     * @return 项目
     */
    Project selectProjectVOById(Long id);

    /**
     * 根据项目ID查询用户列表 （这里是根据项目的承担单位id查询用户)
     *
     * @param projectId 项目ID
     * @return 用户信息集合信息
     */
    List<SysUser> selectUserList(Long projectId);

    /**
     * 查询项目人员信息
     *
     * @param projectId
     * @return
     */
    ProjectUserAssignment selectProjectUsers(Long projectId);

    /**
     * 保存项目人员信息
     *
     * @param assignment
     * @return
     */
    int saveProjectUserAssignments(ProjectUserAssignment assignment);

    /**
     * 根据 用户ID和role 查询项目列表
     * @param userId
     * @param value
     */
    List<Project> selectProjectListByUserIdAndRole(Project project, Long userId, String value);
}
