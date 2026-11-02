package edu.whut.cs.bi.api.evaluation;

import edu.whut.cs.bi.biz.domain.Project;

/**
 * 部门业务的项目范围：承担单位或所属单位是用户所在部门，或是该部门的直接上级。
 */
public final class DepartmentProjectScope {

    private DepartmentProjectScope() {
    }

    public static Long parentId(Long parentId) {
        return parentId != null && parentId > 0 ? parentId : null;
    }

    public static boolean contains(Project project, Long deptId, Long parentDeptId) {
        if (project == null || "2".equals(project.getDelFlag()) || deptId == null) {
            return false;
        }
        Long parent = parentId(parentDeptId);
        return same(project.getDeptId(), deptId, parent) || same(project.getOwnerDeptId(), deptId, parent);
    }

    private static boolean same(Long projectDeptId, Long deptId, Long parentDeptId) {
        if (projectDeptId == null) {
            return false;
        }
        if (deptId.equals(projectDeptId)) {
            return true;
        }
        return parentDeptId != null && parentDeptId.equals(projectDeptId);
    }
}
