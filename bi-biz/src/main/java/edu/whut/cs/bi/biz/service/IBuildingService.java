package edu.whut.cs.bi.biz.service;

import java.io.IOException;
import java.util.List;

import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.vo.BuildingMapPoint;
import edu.whut.cs.bi.biz.domain.vo.ProjectBuildingVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 建筑Service接口
 *
 * @author wanzheng
 * @date 2025-03-27
 */
public interface IBuildingService {
    /**
     * 查询建筑
     *
     * @param id 建筑主键
     * @return 建筑
     */
    public Building selectBuildingById(Long id);

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
     * @param building 建筑
     * @return 建筑集合
     */
    public List<Building> selectAbnormalBridgeSpanList(Building building);

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
     * 批量删除建筑
     *
     * @param ids 需要删除的建筑主键集合
     * @return 结果
     */
    public int batchUpdateLine(String originalLine, String targetLine);

    public int deleteBuildingByIds(String ids);

    /**
     * 删除建筑信息
     *
     * @param id 建筑主键
     * @return 结果
     */
    public int deleteBuildingById(Long id);

    /**
     * 导入JSON文件
     *
     * @param file JSON文件
     * @return 结果
     */
    public int importJson(MultipartFile file) throws IOException;

    /**
     * 查询建筑VO列表
     *
     * @param building  建筑
     * @param projectId 项目ID
     * @return 结果
     */
    List<ProjectBuildingVO> selectBuildingVOList(ProjectBuildingVO building, Long projectId);


    /**
     * 获取指定组合桥下的所有子桥ID（包括直接子桥和间接子桥）
     *
     * @param buildingId 组合桥ID
     * @return 所有子桥ID列表
     */
    public List<Long> selectChildBuildingIds(Long buildingId);

    /**
     * 查询建筑及其父桥关系信息
     *
     * @param id 建筑主键
     * @return 带有父桥信息的建筑
     */
    public Building selectBuildingWithParentInfo(Long id);

    /**
     * 为已存在且缺少构件树的桥幅补建构件树
     *
     * @param building 桥幅信息，需包含id、templateId，可选parentId
     * @return 更新结果
     */
    public int repairBridgeSpanObjectTree(Building building);

    /**
     * 修复组合桥根节点。若历史数据把组合桥保存为无构件树的桥幅，则转换为组合桥并补建根节点。
     *
     * @param building 组合桥信息，需包含id，可选parentId
     * @return 更新结果
     */
    public int repairCombinationBridgeRoot(Building building);

    /**
     * excel导入建筑信息
     *
     * @param file
     */
    public void readBuildingExcel(MultipartFile file, Long projectId);
    
    Building getUniqueBuilding(String bridgeName, String lineCode, String zipCode);

    /**
     * 未删除桥梁总数。
     */
    int countActiveBuildings();

    /**
     * 卡片属性里带有有效 GPS 的桥梁，供地图标注。
     */
    List<BuildingMapPoint> selectBuildingMapPoints();
}
