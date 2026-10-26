package edu.whut.cs.bi.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDictDataMapper;
import edu.whut.cs.bi.biz.domain.BiObject;
import edu.whut.cs.bi.biz.domain.BiTemplateObject;
import edu.whut.cs.bi.biz.domain.Building;
import edu.whut.cs.bi.biz.domain.Task;
import edu.whut.cs.bi.biz.domain.vo.BuildingMapPoint;
import edu.whut.cs.bi.biz.domain.vo.ProjectBuildingVO;
import edu.whut.cs.bi.biz.mapper.BuildingMapper;
import edu.whut.cs.bi.biz.service.IBiObjectService;
import edu.whut.cs.bi.biz.service.IBiTemplateObjectService;
import edu.whut.cs.bi.biz.service.IBuildingService;
import edu.whut.cs.bi.biz.service.ITaskService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 建筑Service业务层处理
 *
 * @author wanzheng
 * @date 2025-03-27
 */
@Service
public class BuildingServiceImpl implements IBuildingService {
    private static final Logger log = LoggerFactory.getLogger(BuildingServiceImpl.class);
    @Autowired
    private BuildingMapper buildingMapper;
    
    @Autowired
    private IBiObjectService biObjectService;
    
    @Autowired
    private IBiTemplateObjectService biTemplateObjectService;
    
    @Resource
    private ITaskService taskService;
    @Autowired
    private SysDictDataMapper sysDictDataMapper;
    
    
    /**
     * 查询建筑
     *
     * @param id 建筑主键
     * @return 建筑
     */
    @Override
    public Building selectBuildingById(Long id) {
        return buildingMapper.selectBuildingById(id);
    }
    
    /**
     * 查询建筑列表
     *
     * @param building 建筑
     * @return 建筑
     */
    @Override
    public List<Building> selectBuildingList(Building building) {
        return buildingMapper.selectBuildingList(building);
    }

    @Override
    public int countActiveBuildings() {
        return buildingMapper.countActiveBuildings();
    }

    @Override
    public List<BuildingMapPoint> selectBuildingMapPoints() {
        return buildingMapper.selectBuildingMapPoints();
    }

    @Override
    public List<Building> selectAbnormalBridgeSpanList(Building building) {
        return buildingMapper.selectAbnormalBridgeSpanList(building);
    }
    
    /**
     * 新增建筑
     *
     * @param building 建筑
     * @return 结果
     */
    @Override
    @Transactional
    public int insertBuilding(Building building) {
        // 先校验是否已经存在
        Building query = new Building();
        query.setName(building.getName());
        query.setArea(building.getArea());
        query.setLine(building.getLine());
        List<Building> buildings = buildingMapper.selectBuildingExactList(query);
        if (CollUtil.isNotEmpty(buildings)) {
            log.error("该片区线路桥梁已存在");
            throw new RuntimeException("该片区线路桥梁已存在");
        }

        building.setCreateTime(DateUtils.getNowDate());
        
        // 1. 创建BiObject根节点
        BiObject rootObject = new BiObject();
        rootObject.setName(building.getName());
        rootObject.setOrderNum(0); // 默认排序号
        rootObject.setStatus("0"); // 默认状态为正常
        rootObject.setCreateTime(DateUtils.getNowDate());
        
        if ("0".equals(building.getIsLeaf())) {
            // 组合桥
            // 检查是否有父桥（组合桥也可以有父桥）
            if (building.getParentId() != null) {
                // 有父桥，获取父桥的根节点ID
                Building parentBridge = buildingMapper.selectBuildingById(building.getParentId());
                if (parentBridge != null && parentBridge.getRootObjectId() != null) {
                    Long parentRootObjectId = Long.valueOf(parentBridge.getRootObjectId());
                    // 挂载到父桥的根节点下
                    rootObject.setParentId(parentRootObjectId);
                    
                    // 获取父节点的ancestors
                    BiObject parentObject = biObjectService.selectBiObjectById(parentRootObjectId);
                    if (parentObject != null) {
                        rootObject.setAncestors(parentObject.getAncestors() + "," + parentRootObjectId);
                    } else {
                        rootObject.setAncestors("0," + parentRootObjectId);
                    }
                }
            } else {
                // 无父桥，设置为顶级节点
                rootObject.setParentId(0L);
                rootObject.setAncestors("0");
            }
            
            // 插入BiObject根节点
            biObjectService.insertBiObject(rootObject);
            
            // 设置Building的rootObjectId，避免后续更新
            building.setRootObjectId(rootObject.getId());
        } else if ("1".equals(building.getIsLeaf())) {
            // 桥幅：可以选择一个父桥（组合桥）或直接放在根目录，需要选择模板
            if (building.getTemplateId() != null) {
                Long parentRootObjectId = 0L; // 默认为根目录
                
                // 如果指定了父桥，则获取父桥的根节点ID
                if (building.getParentId() != null) {
                    Building parentBridge = buildingMapper.selectBuildingById(building.getParentId());
                    if (parentBridge != null && parentBridge.getRootObjectId() != null) {
                        parentRootObjectId = Long.valueOf(parentBridge.getRootObjectId());
                    }
                }
                
                // 获取模版树结构和所有子节点（一次性查询，避免多次数据库访问）
                BiTemplateObject template = biTemplateObjectService.selectBiTemplateObjectById(building.getTemplateId());
                if (template != null) {
                    List<BiTemplateObject> children = biTemplateObjectService.selectChildrenById(building.getTemplateId());
                    
                    // 生成维护树并挂载到父节点下（可能是父桥根节点或根目录）
                    Long rootObjectId = generateMaintenanceTree(building.getName(), template, children, parentRootObjectId);
                    
                    // 设置Building的rootObjectId，避免后续更新
                    building.setRootObjectId(rootObjectId);
                } else {
                    log.error("未找到指定模版!");
                    throw new RuntimeException("未找到指定模版!");
                }
            }
        }
        
        // 插入Building记录（已包含rootObjectId，避免额外更新）
        return buildingMapper.insertBuilding(building);
    }
    
    /**
     * 修改建筑
     *
     * @param building 建筑
     * @return 结果
     */
    @Override
    @Transactional
    public int updateBuilding(Building building) {
        // 先校验是否已经存在
        Building query = new Building();
        query.setName(building.getName());
        query.setArea(building.getArea());
        query.setLine(building.getLine());
        List<Building> buildings = buildingMapper.selectBuildingExactList(query);
        List<Long> queryIds = buildings.stream().map(Building::getId).filter(id -> !id.equals(building.getId())).toList();
        if (CollUtil.isNotEmpty(queryIds)) {
            log.error("该片区线路桥梁已存在");
            throw new RuntimeException("该片区线路桥梁已存在");
        }

        building.setUpdateTime(DateUtils.getNowDate());
        building.setUpdateBy(ShiroUtils.getLoginName());
        
        // 获取需要更新的字段，避免不必要的更新
        boolean needUpdateBiObject = false;
        boolean nameChanged = false;
        boolean parentChanged = false;
        
        // 获取原始记录
        Building oldBuilding = buildingMapper.selectBuildingById(building.getId());
        if (oldBuilding == null) {
            return 0;
        }
        
        // 检查名称是否变化
        if (StringUtils.isNotEmpty(building.getName()) && !building.getName().equals(oldBuilding.getName())) {
            nameChanged = true;
            needUpdateBiObject = true;
        }
        
        // 检查父桥ID是否发生变化
        if (building.getParentId() != null && !building.getParentId().equals(oldBuilding.getParentId())) {
            parentChanged = true;
        }
        
        // 如果父桥ID发生变化，并且有根节点对象，则需要迁移部件树
        if (parentChanged && oldBuilding.getRootObjectId() != null) {
            Long rootObjectId = oldBuilding.getRootObjectId();
            BiObject rootObject = biObjectService.selectBiObjectById(rootObjectId);
            
            if (rootObject != null) {
                Long newParentId;
                String newAncestors;
                
                // 确定新的父节点ID和ancestors
                if (building.getParentId() != null) {
                    // 有父桥，获取父桥的根节点ID
                    Building parentBridge = buildingMapper.selectBuildingById(building.getParentId());
                    if (parentBridge != null && parentBridge.getRootObjectId() != null) {
                        // 获取新父桥的根节点ID作为新的父节点ID
                        Long parentRootObjectId = parentBridge.getRootObjectId();
                        newParentId = parentRootObjectId;
                        
                        // 获取新父节点的ancestors
                        BiObject parentObject = biObjectService.selectBiObjectById(parentRootObjectId);
                        if (parentObject != null) {
                            newAncestors = parentObject.getAncestors() + "," + parentRootObjectId;
                        } else {
                            newAncestors = "0," + parentRootObjectId;
                        }
                    } else {
                        // 父桥不存在或无根节点，设为顶级节点
                        newParentId = 0L;
                        newAncestors = "0";
                    }
                } else {
                    // 无父桥，设置为顶级节点
                    newParentId = 0L;
                    newAncestors = "0";
                }
                
                // 记录原始的parent_id和ancestors
                String oldAncestors = rootObject.getAncestors();
                
                // 更新根节点对象的parent_id和ancestors
                rootObject.setParentId(newParentId);
                rootObject.setAncestors(newAncestors);
                
                // 如果名称也变了，一并更新
                if (nameChanged) {
                    rootObject.setName(building.getName());
                }
                
                rootObject.setUpdateBy(building.getUpdateBy());
                rootObject.setUpdateTime(building.getUpdateTime());
                biObjectService.updateBiObject(rootObject);
                
                // 批量更新所有子节点的ancestors
                String oldAncestorsPrefix = oldAncestors + "," + rootObjectId;
                String newAncestorsPrefix = newAncestors + "," + rootObjectId;
                
                // 使用SQL批量更新子节点ancestors，避免逐个更新
                biObjectService.batchUpdateAncestors(rootObjectId, oldAncestorsPrefix, newAncestorsPrefix, building.getUpdateBy());
                
                needUpdateBiObject = false; // 已经更新过BiObject，不需要再更新
            }
        }
        
        // 如果只有名称变化，需要更新BiObject
        if (needUpdateBiObject && oldBuilding.getRootObjectId() != null) {
            BiObject biObject = new BiObject();
            biObject.setId(oldBuilding.getRootObjectId());
            biObject.setName(building.getName());
            biObject.setUpdateBy(building.getUpdateBy());
            biObject.setUpdateTime(building.getUpdateTime());
            biObjectService.updateBiObject(biObject);
        }
        
        return buildingMapper.updateBuilding(building);
    }
    
    /**
     * 批量删除建筑
     *
     * @param ids 需要删除的建筑主键
     * @return 结果
     */
    @Override
    @Transactional
    public int batchUpdateLine(String originalLine, String targetLine) {
        if (StringUtils.isEmpty(originalLine) || StringUtils.isEmpty(targetLine)) {
            throw new RuntimeException("原线路和修改后的线路不能为空");
        }
        if (originalLine.equals(targetLine)) {
            return 0;
        }

        List<Building> conflicts = buildingMapper.selectBatchUpdateLineConflicts(originalLine, targetLine);
        if (CollUtil.isNotEmpty(conflicts)) {
            String conflictNames = conflicts.stream()
                    .limit(5)
                    .map(Building::getName)
                    .collect(Collectors.joining("、"));
            throw new RuntimeException("修改后会产生同片区同线路同名桥梁：" + conflictNames);
        }

        return buildingMapper.batchUpdateLine(originalLine, targetLine, ShiroUtils.getLoginName());
    }

    @Override
    @Transactional
    public int deleteBuildingByIds(String ids) {
        String[] idArray = Convert.toStrArray(ids);
        int rows = 0;
        
        for (String id : idArray) {
            // 删除单个建筑及其部件树
            rows += deleteBuildingById(Long.valueOf(id));
        }
        
        return rows;
    }
    
    /**
     * 删除建筑信息
     * 高性能版本：基于优化的子桥查询，减少数据库交互
     *
     * @param id 建筑主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteBuildingById(Long id) {
        int rows = 0;
        
        // 1. 获取要删除的建筑
        Building building = buildingMapper.selectBuildingById(id);
        if (building == null) {
            return 0;
        }
        
        // 2. 如果不是组合桥或没有根节点，直接删除当前建筑
        if (!"0".equals(building.getIsLeaf()) || building.getRootObjectId() == null) {
            // 删除部件树（如果有）
            if (building.getRootObjectId() != null) {
                biObjectService.logicDeleteByRootObjectId(building.getRootObjectId(),
                        ShiroUtils.getLoginName());
            }
            // 删除任务
            taskService.deleteTaskByBuildingId(id);
            
            // 删除建筑记录
            return buildingMapper.deleteBuildingById(id);
        }
        
        // 3. 处理组合桥情况：先查询所有子桥，然后批量删除
        try {
            Long rootObjectId = building.getRootObjectId();
            
            // 使用优化后的方法查询所有子桥
            List<Building> childBridges = buildingMapper.selectAllChildBridges(rootObjectId);
            
            // 删除所有子桥
            if (childBridges != null && !childBridges.isEmpty()) {
                // 收集所有需要删除的根对象ID
                List<String> buildingList = new ArrayList<>();
                for (Building child : childBridges) {
                    buildingList.add(child.getId().toString());
                    // 删除任务
                    taskService.deleteTaskByBuildingId(child.getId());
                }
                String[] array = buildingList.toArray(new String[buildingList.size()]);
                buildingMapper.deleteBuildingByIds(array);
            }
            
            // 删除当前建筑的部件树
            biObjectService.logicDeleteByRootObjectId(rootObjectId, ShiroUtils.getLoginName());
            
            // 删除当前建筑记录
            int result = buildingMapper.deleteBuildingById(id);
            if (result > 0) {
                rows += result;
            }
            
            return rows;
        } catch (Exception e) {
            log.info(e.getMessage());
            throw new RuntimeException("删除建筑失败", e);
        }
        
        
    }
    
    /**
     * 批量删除接口，用于支持批量根对象删除
     */
    private interface BatchDeletable {
        int batchLogicDeleteByRootObjectIds(List<Long> rootObjectIds, String updateBy);
    }
    
    @Override
    @Transactional
    public int importJson(MultipartFile file) throws IOException {
        String jsonContent = new String(file.getBytes(), "UTF-8");
        int successCount = 0;
        
        try {
            // 首先尝试解析为JSON数组
            JSONArray jsonArray = JSONArray.parseArray(jsonContent);
            if (jsonArray != null) {
                // 处理JSON数组格式
                for (int i = 0; i < jsonArray.size(); i++) {
                    JSONObject buildingJson = jsonArray.getJSONObject(i);
                    successCount += processBuilding(buildingJson);
                }
            }
        } catch (Exception e) {
            // 如果不是JSON数组，尝试解析为单个JSON对象
            try {
                JSONObject jsonObject = JSONObject.parseObject(jsonContent);
                if (jsonObject != null) {
                    successCount += processBuilding(jsonObject);
                }
            } catch (Exception ex) {
                throw new RuntimeException("无效的JSON格式");
            }
        }
        
        return successCount;
    }
    
    /**
     * 查询建筑VO列表
     *
     * @param building  建筑
     * @param projectId 项目ID
     * @return
     */
    @Override
    public List<ProjectBuildingVO> selectBuildingVOList(ProjectBuildingVO building, Long projectId) {
        return buildingMapper.selectProjectBuildingVOList(building, projectId);
    }
    
    /**
     * 处理单个建筑的JSON数据
     */
    private int processBuilding(JSONObject jsonObject) {
        if (jsonObject == null || jsonObject.isEmpty()) {
            return 0;
        }
        
        // 获取第一个key作为建筑名称
        String buildingName = jsonObject.keySet().iterator().next();
        JSONObject buildingData = jsonObject.getJSONObject(buildingName);
        
        Building building = new Building();
        building.setName(buildingName);
        building.setStatus("0"); // 默认正常状态
        building.setArea("0"); // 默认片区
        building.setLine("0"); // 默认线路
        building.setCreateBy(ShiroUtils.getLoginName());
        building.setCreateTime(DateUtils.getNowDate());
        
        // 创建Building
        int rows = buildingMapper.insertBuilding(building);
        if (rows > 0) {
            // 创建BiObject根节点
            BiObject rootObject = new BiObject();
            rootObject.setName(buildingName);
            rootObject.setParentId(0L);
            rootObject.setAncestors("0");
            rootObject.setCreateBy(ShiroUtils.getLoginName());
            rootObject.setOrderNum(0); // 根节点序号为0
            rootObject.setStatus("0");
            
            // 插入BiObject根节点
            biObjectService.insertBiObject(rootObject);
            
            // 更新Building的rootObjectId
            building.setRootObjectId(rootObject.getId());
            buildingMapper.updateBuilding(building);
            
            // 递归创建子节点
            createBiObjectTree(buildingData, rootObject.getId(), rootObject.getAncestors(), 1);
            
            return 1;
        }
        
        return 0;
    }
    
    /**
     * 递归创建BiObject树
     *
     * @param jsonObject      JSON对象
     * @param parentId        父节点ID
     * @param parentAncestors 父节点的祖先字符串
     * @param level           当前层级
     */
    private void createBiObjectTree(JSONObject jsonObject, Long parentId, String parentAncestors, int level) {
        if (jsonObject == null) {
            return;
        }
        
        int orderNum = 0; // 从0开始
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            
            BiObject biObject = new BiObject();
            biObject.setName(key);
            biObject.setParentId(parentId);
            biObject.setCreateBy(ShiroUtils.getLoginName());
            biObject.setAncestors(parentAncestors + "," + parentId);
            biObject.setOrderNum(orderNum); // 直接使用orderNum作为排序号
            biObject.setStatus("0");
            
            // 插入节点
            biObjectService.insertBiObject(biObject);
            
            // 如果值是JSONObject，继续递归创建子节点
            if (value instanceof JSONObject) {
                createBiObjectTree((JSONObject) value, biObject.getId(), biObject.getAncestors(), level + 1);
            }
            // 如果值是JSONArray，为每个元素创建子节点
            else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                int childOrderNum = 0; // 子节点从0开始计数
                for (int i = 0; i < array.size(); i++) {
                    Object arrayValue = array.get(i);
                    if (arrayValue instanceof JSONObject) {
                        createBiObjectTree((JSONObject) arrayValue, biObject.getId(), biObject.getAncestors(), level + 1);
                    }
                    // 处理数组中的字符串元素，为每个字符串创建一个子节点
                    else if (arrayValue instanceof String || arrayValue instanceof Number || arrayValue instanceof Boolean) {
                        BiObject childObject = new BiObject();
                        childObject.setName(arrayValue.toString());
                        childObject.setParentId(biObject.getId());
                        childObject.setAncestors(biObject.getAncestors() + "," + biObject.getId());
                        childObject.setOrderNum(childOrderNum++); // 子节点按顺序编号
                        childObject.setStatus("0");
                        childObject.setCreateBy(ShiroUtils.getLoginName());
                        
                        // 插入子节点
                        biObjectService.insertBiObject(childObject);
                    }
                }
            }
            // 如果值是基本类型，将值存储在remark中
            else if (value != null) {
                biObject.setRemark(value.toString());
                biObjectService.updateBiObject(biObject);
            }
            
            orderNum++;
        }
    }
    
    /**
     * 根据模版生成维护树，并挂载到父桥根节点下
     *
     * @param buildingName       建筑物名称
     * @param template           模版节点
     * @param children           所有子节点列表
     * @param parentRootObjectId 父桥根节点ID
     * @return 创建的根节点ID
     */
    private Long generateMaintenanceTree(String buildingName, BiTemplateObject template, List<BiTemplateObject> children, Long parentRootObjectId) {
        // 创建根节点，挂载到父桥的根节点下
        BiObject rootObject = new BiObject();
        rootObject.setName(buildingName + "(" + template.getName() + ")");
        rootObject.setParentId(parentRootObjectId); // 挂载到父桥的根节点下
        
        // 获取父节点的ancestors
        BiObject parentObject = biObjectService.selectBiObjectById(parentRootObjectId);
        String ancestors = "0";
        if (parentObject != null) {
            ancestors = parentObject.getAncestors() + "," + parentRootObjectId;
        }
        rootObject.setAncestors(ancestors);
        rootObject.setProps(template.getProps());
        rootObject.setOrderNum(0);
        rootObject.setStatus("0");
        rootObject.setCreateBy(ShiroUtils.getLoginName());
        rootObject.setWeight(template.getWeight());
        rootObject.setTemplateObjectId(template.getId()); // 设置对应的模板ID
        
        // 插入根节点
        biObjectService.insertBiObject(rootObject);
        
        // 如果没有子节点，直接返回
        if (children == null || children.isEmpty()) {
            return rootObject.getId();
        }
        
        // 创建模板ID到实际对象的映射
        Map<Long, BiObject> templateIdToObjectMap = new HashMap<>();
        templateIdToObjectMap.put(template.getId(), rootObject);
        
        // 按层级处理子节点，确保父节点先创建
        Map<Long, List<BiTemplateObject>> levelMap = new HashMap<>();
        
        // 第一步：找出所有直接挂在根节点下的子节点（第一层）
        List<BiTemplateObject> firstLevel = children.stream()
                .filter(child -> child.getParentId().equals(template.getId()))
                .collect(Collectors.toList());
        
        // 将第一层节点放入levelMap
        levelMap.put(1L, firstLevel);
        
        // 第二步：逐层构建节点层级
        int currentLevel = 1;
        boolean hasMoreLevels = true;
        
        while (hasMoreLevels) {
            List<BiTemplateObject> currentLevelNodes = levelMap.get((long) currentLevel);
            if (currentLevelNodes == null || currentLevelNodes.isEmpty()) {
                break;
            }
            
            // 获取当前层级所有节点的ID
            List<Long> currentLevelIds = currentLevelNodes.stream()
                    .map(BiTemplateObject::getId)
                    .collect(Collectors.toList());
            
            // 找出下一层级的节点
            List<BiTemplateObject> nextLevelNodes = children.stream()
                    .filter(child -> currentLevelIds.contains(child.getParentId()))
                    .collect(Collectors.toList());
            
            if (!nextLevelNodes.isEmpty()) {
                levelMap.put((long) (currentLevel + 1), nextLevelNodes);
                currentLevel++;
            } else {
                hasMoreLevels = false;
            }
        }
        
        // 第三步：按层级顺序创建节点
        String loginName = ShiroUtils.getLoginName();
        Date now = new Date();
        
        for (int level = 1; level <= currentLevel; level++) {
            List<BiTemplateObject> nodesInLevel = levelMap.get((long) level);
            List<BiObject> objectsToInsert = new ArrayList<>();
            
            for (BiTemplateObject templateObj : nodesInLevel) {
                // 获取父节点
                BiObject parentObj = templateIdToObjectMap.get(templateObj.getParentId());
                if (parentObj == null) {
                    continue; // 跳过找不到父节点的情况
                }
                
                // 创建节点
                BiObject biObject = new BiObject();
                biObject.setName(templateObj.getName());
                biObject.setParentId(parentObj.getId());
                biObject.setProps(templateObj.getProps());
                biObject.setAncestors(parentObj.getAncestors() + "," + parentObj.getId());
                biObject.setOrderNum(templateObj.getOrderNum());
                biObject.setStatus("0");
                biObject.setWeight(templateObj.getWeight());
                biObject.setCreateBy(loginName);
                biObject.setCreateTime(now);
                biObject.setTemplateObjectId(templateObj.getId());
                
                // 添加到待插入列表
                objectsToInsert.add(biObject);
            }
            
            // 批量插入当前层级的节点
            if (!objectsToInsert.isEmpty()) {
                biObjectService.batchInsertBiObjects(objectsToInsert);
                
                // 更新映射，为下一层级做准备
                for (int i = 0; i < nodesInLevel.size(); i++) {
                    if (i < objectsToInsert.size()) {
                        templateIdToObjectMap.put(nodesInLevel.get(i).getId(), objectsToInsert.get(i));
                    }
                }
            }
        }
        
        return rootObject.getId();
    }
    
    /**
     * 获取指定组合桥下的所有子桥ID（包括直接子桥和间接子桥）
     * 高性能版本：使用数据库递归查询
     *
     * @param buildingId 组合桥ID
     * @return 所有子桥ID列表
     */
    @Override
    public List<Long> selectChildBuildingIds(Long buildingId) {
        List<Long> childIds = new ArrayList<>();
        
        // 获取当前组合桥
        Building building = buildingMapper.selectBuildingById(buildingId);
        if (building == null || building.getRootObjectId() == null) {
            return childIds;
        }
        
        try {
            Long rootObjectId = Long.valueOf(building.getRootObjectId());
            
            // 尝试使用递归CTE SQL查询所有子桥
            List<Building> allChildBridges = buildingMapper.selectAllChildBridges(rootObjectId);
            
            // 提取所有子桥ID
            if (allChildBridges != null && !allChildBridges.isEmpty()) {
                for (Building childBridge : allChildBridges) {
                    childIds.add(childBridge.getId());
                }
                return childIds;
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return childIds;
    }
    
    /**
     * 查询建筑及其父桥关系信息
     *
     * @param id 建筑主键
     * @return 带有父桥信息的建筑
     */
    @Override
    public Building selectBuildingWithParentInfo(Long id) {
        return buildingMapper.selectBuildingWithParentInfo(id);
    }

    @Override
    @Transactional
    public int repairBridgeSpanObjectTree(Building building) {
        if (building == null || building.getId() == null) {
            throw new RuntimeException("修复桥幅ID不能为空");
        }
        if (building.getTemplateId() == null) {
            throw new RuntimeException("修复桥幅模板ID不能为空");
        }

        Building existing = buildingMapper.selectBuildingById(building.getId());
        if (existing == null) {
            throw new RuntimeException("未找到需要修复的桥梁：" + building.getName());
        }
        if (!"1".equals(existing.getIsLeaf())) {
            throw new RuntimeException("仅支持修复桥幅：" + existing.getName());
        }
        if (existing.getRootObjectId() != null) {
            throw new RuntimeException("桥幅已存在构件树，无需修复：" + existing.getName());
        }

        Long parentRootObjectId = 0L;
        if (building.getParentId() != null) {
            Building parentBridge = buildingMapper.selectBuildingById(building.getParentId());
            if (parentBridge == null || parentBridge.getRootObjectId() == null) {
                throw new RuntimeException("父桥不存在或未生成构件树：" + existing.getName());
            }
            parentRootObjectId = parentBridge.getRootObjectId();
        }

        BiTemplateObject template = biTemplateObjectService.selectBiTemplateObjectById(building.getTemplateId());
        if (template == null) {
            throw new RuntimeException("未找到指定模版：" + building.getTemplateId());
        }
        List<BiTemplateObject> children = biTemplateObjectService.selectChildrenById(building.getTemplateId());
        Long rootObjectId = generateMaintenanceTree(existing.getName(), template, children, parentRootObjectId);
        int rows = buildingMapper.updateBuildingRootObjectId(existing.getId(), rootObjectId, ShiroUtils.getLoginName());
        if (rows <= 0) {
            throw new RuntimeException("回写桥幅构件树失败：" + existing.getName());
        }
        return rows;
    }

    @Override
    @Transactional
    public int repairCombinationBridgeRoot(Building building) {
        if (building == null || building.getId() == null) {
            throw new RuntimeException("修复组合桥ID不能为空");
        }

        Building existing = buildingMapper.selectBuildingById(building.getId());
        if (existing == null) {
            throw new RuntimeException("未找到需要修复的组合桥：" + building.getName());
        }
        if (StringUtils.isNotEmpty(building.getArea()) && !building.getArea().equals(existing.getArea())) {
            throw new RuntimeException("片区不一致，不能自动转换为组合桥：" + existing.getName());
        }
        if (StringUtils.isNotEmpty(building.getLine()) && !building.getLine().equals(existing.getLine())) {
            throw new RuntimeException("线路不一致，不能自动转换为组合桥：" + existing.getName());
        }
        if ("0".equals(existing.getIsLeaf()) && existing.getRootObjectId() != null) {
            return 0;
        }
        if (!"0".equals(existing.getIsLeaf()) && !"1".equals(existing.getIsLeaf())) {
            throw new RuntimeException("桥梁类型异常，不能修复为组合桥：" + existing.getName());
        }

        Long parentRootObjectId = 0L;
        if (building.getParentId() != null) {
            Building parentBridge = buildingMapper.selectBuildingById(building.getParentId());
            if (parentBridge == null || parentBridge.getRootObjectId() == null) {
                throw new RuntimeException("父桥不存在或未生成构件树：" + existing.getName());
            }
            parentRootObjectId = parentBridge.getRootObjectId();
        }

        if ("1".equals(existing.getIsLeaf()) && existing.getRootObjectId() != null) {
            int deletedRows = biObjectService.logicDeleteByRootObjectId(existing.getRootObjectId(), ShiroUtils.getLoginName());
            if (deletedRows <= 0) {
                throw new RuntimeException("删除桥幅旧构件树失败：" + existing.getName());
            }
        }

        BiObject rootObject = new BiObject();
        rootObject.setName(existing.getName());
        rootObject.setParentId(parentRootObjectId);
        BiObject parentObject = biObjectService.selectBiObjectById(parentRootObjectId);
        rootObject.setAncestors(parentObject == null ? "0" : parentObject.getAncestors() + "," + parentRootObjectId);
        rootObject.setOrderNum(0);
        rootObject.setStatus("0");
        rootObject.setCreateBy(ShiroUtils.getLoginName());
        rootObject.setCreateTime(DateUtils.getNowDate());
        biObjectService.insertBiObject(rootObject);

        Building update = new Building();
        update.setId(existing.getId());
        update.setIsLeaf("0");
        update.setRootObjectId(rootObject.getId());
        update.setUpdateBy(ShiroUtils.getLoginName());
        update.setUpdateTime(DateUtils.getNowDate());
        int rows = buildingMapper.updateBuilding(update);
        if (rows <= 0) {
            throw new RuntimeException("回写组合桥构件树失败：" + existing.getName());
        }
        return rows;
    }
    
    private static final Map<String, Long> BRIDGE_TYPE_MAP = new HashMap<>();
    
    static {
        BRIDGE_TYPE_MAP.put("梁桥", 1L);
        BRIDGE_TYPE_MAP.put("箱形拱桥", 3L);
        BRIDGE_TYPE_MAP.put("双曲拱", 4L);
        BRIDGE_TYPE_MAP.put("板拱", 5L);
        BRIDGE_TYPE_MAP.put("刚架拱", 6L);
        BRIDGE_TYPE_MAP.put("桁架拱", 7L);
        BRIDGE_TYPE_MAP.put("钢-混凝土组合拱桥", 8L);
        BRIDGE_TYPE_MAP.put("预应力混凝土悬索桥", 9L);
        BRIDGE_TYPE_MAP.put("预应力混凝土斜拉桥", 10L);
        BRIDGE_TYPE_MAP.put("钢箱梁斜拉桥", 11L);
        BRIDGE_TYPE_MAP.put("肋拱桥", 12L);
        BRIDGE_TYPE_MAP.put("钢箱梁悬索桥", 13L);
        BRIDGE_TYPE_MAP.put("钢桁梁悬索桥", 14L);
        BRIDGE_TYPE_MAP.put("叠合梁悬索桥", 15L);
        BRIDGE_TYPE_MAP.put("钢桁梁斜拉桥", 16L);
        BRIDGE_TYPE_MAP.put("叠合梁斜拉桥", 17L);
    }
    
    @Override
    @Transactional
    public void readBuildingExcel(MultipartFile file, Long projectId) {
        List<Long> buildings = new ArrayList<>();
        
        Task task = new Task();
        task.setProjectId(projectId);
        task.setSelect("platform");
        task.setStatus("0");
        List<Task> tasks = taskService.selectTaskList(task);
        Set<Long> taskSet = tasks.stream().map(t -> t.getBuildingId()).collect(Collectors.toSet());
        
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (int j = 0; j < workbook.getNumberOfSheets(); j++) {
                Sheet sheet = workbook.getSheetAt(j); // 获取第一个工作表
                
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    
                    String buildingArea = getCellValueAsString(row.getCell(1));
                    String buildingName = getCellValueAsString(row.getCell(4));
                    String lineCode = getCellValueAsString(row.getCell(7));
                    String lineName = getCellValueAsString(row.getCell(8));
                    String buildingTemplate = getCellValueAsString(row.getCell(15));
                    
                    SysDictData areaDictData = new SysDictData();
                    areaDictData.setDictType("bi_building_area");
                    areaDictData.setDictLabel(buildingArea);
                    List<SysDictData> areaDictDatas = sysDictDataMapper.selectDictDataList(areaDictData);
                    if (areaDictDatas == null || CollUtil.isEmpty(areaDictDatas)) {
                        // 这里值是随便给的
                        areaDictData.setDictSort(100L);
                        sysDictDataMapper.insertDictData(areaDictData);
                        areaDictDatas = sysDictDataMapper.selectDictDataList(areaDictData);
                    }
                    
                    SysDictData lineDictData = new SysDictData();
                    lineDictData.setDictType("bi_buildeing_line");
                    lineDictData.setDictLabel(lineName);
                    List<SysDictData> lineDictDatas = sysDictDataMapper.selectDictDataList(lineDictData);
                    if (lineDictDatas == null || CollUtil.isEmpty(lineDictDatas)) {
                        // 这里值是随便给的
                        lineDictData.setDictSort(100L);
                        lineDictData.setDictValue(lineCode);
                        sysDictDataMapper.insertDictData(lineDictData);
                    }
                    
                    Building building = new Building();
                    building.setArea(areaDictDatas.get(0).getDictValue());
                    building.setLine(lineCode);
                    building.setName(buildingName);
                    
                    List<Building> buildingList = buildingMapper.selectBuildingList(building);
                    
                    if (CollUtil.isEmpty(buildingList) || buildingList.size() == 0) {
                        building.setStatus("0");
                        building.setIsLeaf("1");
                        building.setTemplateId(BRIDGE_TYPE_MAP.get(buildingTemplate));
                        building.setCreateBy(ShiroUtils.getLoginName());
                        this.insertBuilding(building);
                    } else {
                        building = buildingList.get(0);
                    }
                    
                    if (taskSet.contains(building.getId()))
                        continue;
                    
                    buildings.add(building.getId());
                }
            }
        } catch (IOException e) {
            log.error("导入失败", e);
            throw new RuntimeException("导入失败" + e.getMessage());
        }
        
        taskService.batchInsertTasks(projectId, buildings);
    }
    
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf((int) cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }
    
    @Override
    public Building getUniqueBuilding(String bridgeName, String lineCode, String zipCode) {
        // 基于桥梁名称 + 路线编号(line) + 行政编号(area前三位) 唯一定位
        if (StringUtils.isEmpty(bridgeName) || StringUtils.isEmpty(lineCode) || StringUtils.isEmpty(zipCode)) {
            return null;
        }
        
        Building query = new Building();
        query.setName(bridgeName.trim());
        query.setLine(lineCode.trim());
        // 只在叶子层面定位具体桥梁（桥幅）
        query.setIsLeaf("1");
        // 优先查启用状态
        query.setStatus("0");
        
        List<Building> list = buildingMapper.selectBuildingList(query);
        if (CollUtil.isEmpty(list)) {
            // 放宽一次约束（可能有停用数据），仅用叶子过滤
            query.setStatus(null);
            list = buildingMapper.selectBuildingList(query);
        }
        
        if (CollUtil.isEmpty(list)) {
            return null;
        }
        if (list.size() == 1) {
            return list.get(0);
        }

        // 行政编号前三位相同即视为同一区：先按前三位前缀筛选
    final String zipTrim = zipCode.trim();
    final String areaPrefix = zipTrim.length() > 3 ? zipTrim.substring(0, 3) : zipTrim;

        List<Building> prefixMatches = list.stream()
                .filter(b -> StringUtils.isNotEmpty(b.getArea()))
                .filter(b -> b.getArea().startsWith(areaPrefix))
                .collect(Collectors.toList());

        if (prefixMatches.size() == 1) {
            return prefixMatches.get(0);
        }
        if (prefixMatches.size() > 1) {
            final String areaKey = zipTrim;
            List<Building> exactArea = prefixMatches.stream()
                    .filter(b -> areaKey.equals(b.getArea()))
                    .collect(Collectors.toList());
            if (exactArea.size() == 1) {
                return exactArea.get(0);
            }

            return prefixMatches.get(0);
        }

    final String areaKey = zipTrim;
    List<Building> exactArea = list.stream()
                .filter(b -> areaKey.equals(b.getArea()))
                .collect(Collectors.toList());
        if (exactArea.size() == 1) {
            return exactArea.get(0);
        }

        return list.get(0);
    }
}

