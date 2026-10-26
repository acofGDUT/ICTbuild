package edu.whut.cs.bi.biz.mapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.vo.BuildingMapPoint;
import edu.whut.cs.bi.biz.domain.vo.ProjectBuildingVO;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;

/**
 * 建筑Mapper接口
 *
 * @author wanzheng
 * @date 2025-03-27
 */
public interface BuildingMapper {
    /**
     * 查询建筑
     *
     * @param id 建筑主键
     * @return 建筑
     */
    public Building selectBuildingById(Long id);

    /**
     * 根据ID列表批量查询有效建筑。
     *
     * @param ids 建筑ID列表
     * @return 建筑集合
     */
    public List<Building> selectBuildingsByIds(@Param("ids") List<Long> ids);

    /**
     * 查询建筑列表
     *
     * @param building 建筑
     * @return 建筑集合
     */
    public List<Building> selectBuildingList(Building building);

    /**
     * 查询异常桥幅及可确定的父级组合桥
     *
     * @param building 查询条件
     * @return 建筑集合
     */
    public List<Building> selectAbnormalBridgeSpanList(Building building);

    /**
     * 按名称、片区、线路精确查询建筑
     *
     * @param building 建筑
     * @return 建筑集合
     */
    public List<Building> selectBuildingExactList(Building building);

    /**
     * 查询建筑列表
     *
     * @param building 建筑
     * @return 建筑集合
     */
    public List<ProjectBuildingVO> selectProjectBuildingVOList(@Param("building") ProjectBuildingVO building, @Param("projectId") Long projectId);

    /**
     * 新增建筑
     *
     * @param building 建筑
     * @return 结果
     */
    public int insertBuilding(Building building);

    /**
     * 修改建筑
     *
     * @param building 建筑
     * @return 结果
     */
    public int updateBuilding(Building building);

    /**
     * 更新建筑根对象ID
     *
     * @param id 建筑ID
     * @param rootObjectId 根对象ID
     * @param updateBy 更新人
     * @return 结果
     */
    public List<Building> selectBatchUpdateLineConflicts(@Param("originalLine") String originalLine, @Param("targetLine") String targetLine);

    public int batchUpdateLine(@Param("originalLine") String originalLine, @Param("targetLine") String targetLine, @Param("updateBy") String updateBy);

    public int updateBuildingRootObjectId(@Param("id") Long id, @Param("rootObjectId") Long rootObjectId, @Param("updateBy") String updateBy);

    /**
     * 清理引用指定属性根节点的建筑记录，避免属性删除后留下失效的根属性ID
     *
     * @param propertyId 属性根节点ID
     * @param updateBy 更新人
     * @return 结果
     */
    public int clearRootPropertyIdByPropertyId(@Param("propertyId") Long propertyId, @Param("updateBy") String updateBy);

    /**
     * 删除建筑
     *
     * @param id 建筑主键
     * @return 结果
     */
    public int deleteBuildingById(Long id);

    /**
     * 批量删除建筑
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteBuildingByIds(String[] ids);


    /**
     * 查询组合桥下所有层级的子桥
     * 包括直接子桥和间接子桥，使用递归查询
     *
     * @param rootObjectId 根节点对象ID
     * @return 所有子桥列表
     */
    public List<Building> selectAllChildBridges(@Param("rootObjectId") Long rootObjectId);

    /**
     * 查询建筑及其父桥关系信息
     *
     * @param id 建筑主键
     * @return 带有父桥信息的建筑
     */
    public Building selectBuildingWithParentInfo(Long id);

    /**
     * 根据名称查询建筑信息
     */
    List<Building> selectBuildingByNames(@Param("buildingSet") Set<String> buildingSet);

    /**
     * 未删除桥梁总数。
     */
    int countActiveBuildings();

    /**
     * 卡片属性里带有有效 GPS 的桥梁。
     */
    List<BuildingMapPoint> selectBuildingMapPoints();
}
