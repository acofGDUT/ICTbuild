package edu.whut.cs.bi.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysDictDataService;
import edu.whut.cs.bi.biz.controller.DiseaseController;
import edu.whut.cs.bi.biz.controller.FileMapController;
import edu.whut.cs.bi.biz.config.MinioConfig;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.domain.dto.CauseQuery;
import edu.whut.cs.bi.biz.domain.temp.DiseaseReport;
import edu.whut.cs.bi.biz.mapper.*;
import edu.whut.cs.bi.biz.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.Resource;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static edu.whut.cs.bi.biz.utils.ThumbPhotoUtils.createThumbnail;


/**
 * 病害Service业务层处理
 */
@Service
@Slf4j
public class DiseaseServiceImpl implements IDiseaseService {
    @Resource
    private DiseaseMapper diseaseMapper;

    @Resource
    private DiseaseTypeMapper diseaseTypeMapper;

    @Resource
    private IComponentService componentService;

    @Resource
    private BiObjectMapper biObjectMapper;

    @Resource
    private IFileMapService fileMapService;

    @Resource
    private MinioConfig minioConfig;

    @Resource
    private AttachmentService attachmentService;

    @Resource
    private DiseaseDetailMapper diseaseDetailMapper;

    @Resource
    private DiseaseController diseaseController;

    @Resource
    private BuildingMapper buildingMapper;

    @Resource
    private IDiseaseTypeService diseaseTypeService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private ITaskService taskService;

    @Resource
    private IProjectService projectService;

    @Autowired
    private ComponentMapper componentMapper;

    @Resource
    private FileMapController fileMapController;

    @Resource
    private IBuildingService buildingService;

    @Resource
    private ProjectMapper projectMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Resource
    private ISysDictDataService sysDictDataService;

    @Resource
    private IPropertyService propertyService;

    /**
     * 查询病害
     *
     * @param id 病害ID
     * @return 病害
     */
    @Override
    public Disease selectDiseaseById(Long id) {
        Disease disease = diseaseMapper.selectDiseaseById(id);
        // 关联查询其它属性
        Long componentId = disease.getComponentId();
        Long biObjectId = disease.getBiObjectId();

        if (biObjectId == null) {
            throw new RuntimeException("biObjectId不存在！");
        }
        BiObject biObject = biObjectMapper.selectBiObjectById(biObjectId);
        if (componentId == null) {
            throw new RuntimeException("componentId不存在！");
        }
        Component component = componentService.selectComponentById(componentId);
        disease.setComponent(component);
        disease.setBiObject(biObject);
        BiObject biObjectParent = biObjectMapper.selectBiObjectById(biObject.getParentId());
        disease.setBindBiObjectName(biObjectParent.getName() + "——" + biObject.getName());
        disease.setBiObjectName(extractComponentObjectName(component.getName()));
        DiseaseDetail diseaseDetail = new DiseaseDetail();
        diseaseDetail.setDiseaseId(id);
        List<DiseaseDetail> diseaseDetails = diseaseDetailMapper.selectDiseaseDetailList(diseaseDetail);
        disease.setDiseaseDetails(diseaseDetails);

//        List<Map<String, Object>> diseaseImage = diseaseController.getDiseaseImage(disease.getId());


        return disease;
    }

    /**
     * 查询病害列表
     *
     * @param disease 病害
     * @return 病害
     */
    @Override
    public List<Disease> selectDiseaseList(Disease disease) {
        // 这里是病害列表只有 biObjectId 一个查询条件
        Long biObjectId = disease.getBiObjectId();
        List<Disease> diseases;

        if (biObjectId != null) {
            List<Long> biObjectIds = new ArrayList<>();
            biObjectIds.add(biObjectId);
            List<BiObject> biObjects = biObjectMapper.selectChildrenById(biObjectId);
            biObjectIds.addAll(biObjects.stream().map(BiObject::getId).collect(Collectors.toList()));
            PageUtils.startPage();
            diseases = diseaseMapper.selectDiseaseListByBiObjectIds(biObjectIds, disease);
        } else {
            PageUtils.startPage();
            diseases = diseaseMapper.selectDiseaseList(disease);
        }

        diseases.forEach(ds -> {
            Long componentId = ds.getComponentId();

            if (componentId != null) {
                Component component = componentService.selectComponentById(componentId);
                BiObject parent = biObjectMapper.selectDirectParentById(component.getBiObjectId());

                if (parent != null) {
                    component.setParentObjectName(parent.getName());
                    BiObject grandBiObject = biObjectMapper.selectBiObjectById(parent.getParentId());
                    if (grandBiObject != null) {
                        component.setGrandObjectName(grandBiObject.getName());
                    }
                }
                ds.setComponent(component);
            }

            BiObject biObject = biObjectMapper.selectBiObjectById(ds.getBiObjectId());
            ds.setBindBiObjectName(biObject.getName());

            // 病害类型
            DiseaseType diseaseType = diseaseTypeMapper.selectDiseaseTypeById(ds.getDiseaseTypeId());
            ds.setBindType(diseaseType.getName());

            DiseaseDetail diseaseDetail = new DiseaseDetail();
            diseaseDetail.setDiseaseId(ds.getId());
            List<DiseaseDetail> diseaseDetails = diseaseDetailMapper.selectDiseaseDetailList(diseaseDetail);
            ds.setDiseaseDetails(diseaseDetails);

            List<String> images = new ArrayList<>();
            List<String> ADImgs = new ArrayList<>();
            List<Map<String, Object>> diseaseImage = diseaseController.getDiseaseImage(ds.getId());
            if (CollUtil.isNotEmpty(diseaseImage)) {
                diseaseImage.forEach(di -> {
                    Integer type = (Integer) di.get("type");
                    if (type.equals(7)) {
                        ADImgs.add((String) di.get("url"));
                    } else {
                        images.add((String) di.get("url"));
                    }
                });

                ds.setImages(images);
                ds.setADImgs(ADImgs);
            }

            String imgNoExp = ds.getImgNoExp();
            if (imgNoExp != null) {
                ObjectMapper mapper = new ObjectMapper();

                try {
                    List<String> imgs = mapper.readValue(imgNoExp, new TypeReference<List<String>>() {});
                    ds.setImgNoExp(imgs.stream().collect(Collectors.joining("、")));
                } catch (JsonProcessingException e) {
                    log.error("图片格式有误转化失败");
                }

            }
        });
        return diseases;
    }

    /**
     * 查询病害列表
     *
     * @param disease 病害
     * @return 病害
     */
    @Override
    public List<Disease> selectDiseaseListForTask(Disease disease) {
        List<Disease> diseases;
        diseases = diseaseMapper.selectDiseaseList(disease);
        diseases.forEach(ds -> {
            Long componentId = ds.getComponentId();

            if (componentId != null) {
                Component component = componentService.selectComponentById(componentId);
                BiObject parent = biObjectMapper.selectDirectParentById(component.getBiObjectId());

                if (parent != null) {
                    component.setParentObjectName(parent.getName());
                    BiObject grandBiObject = biObjectMapper.selectBiObjectById(parent.getParentId());
                    if (grandBiObject != null) {
                        component.setGrandObjectName(grandBiObject.getName());
                    }
                }
                ds.setComponent(component);
            }

            DiseaseDetail diseaseDetail = new DiseaseDetail();
            diseaseDetail.setDiseaseId(ds.getId());
            List<DiseaseDetail> diseaseDetails = diseaseDetailMapper.selectDiseaseDetailList(diseaseDetail);
            ds.setDiseaseDetails(diseaseDetails);

            List<String> images = new ArrayList<>();
            List<String> ADImgs = new ArrayList<>();
            List<Map<String, Object>> diseaseImage = diseaseController.getDiseaseImage(ds.getId());
            if (CollUtil.isNotEmpty(diseaseImage)) {
                diseaseImage.forEach(di -> {
                    Integer type = (Integer) di.get("type");
                    if (type.equals(7)) {
                        ADImgs.add((String) di.get("url"));
                    } else {
                        images.add((String) di.get("url"));
                    }
                });

                ds.setImages(images);
                ds.setADImgs(ADImgs);
            }
        });
        return diseases;
    }

    /**
     * 批量加载纯Excel导出需要的数据。
     * 按项目+桥梁取数，避免 task_id 错乱导致丢病害；病害详情用于长度、缝宽、面积。
     */
    @Override
    public List<Disease> selectDiseaseListForExcel(List<Task> tasks) {
        if (CollUtil.isEmpty(tasks)) {
            return new ArrayList<>();
        }

        List<Task> queryTasks = tasks.stream()
                .filter(task -> task != null && task.getProjectId() != null && task.getBuildingId() != null)
                .collect(Collectors.toList());
        if (queryTasks.isEmpty()) {
            return new ArrayList<>();
        }

        List<Disease> diseases = diseaseMapper.selectDiseaseListByProjectBuildings(queryTasks);
        if (CollUtil.isEmpty(diseases)) {
            return diseases;
        }

        List<Long> diseaseIds = diseases.stream()
                .map(Disease::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<Long> componentIds = diseases.stream()
                .map(Disease::getComponentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Component> componentMap = new HashMap<>();
        if (!componentIds.isEmpty()) {
            componentService.selectComponentsByIds(componentIds).stream()
                    .filter(component -> "0".equals(component.getDelFlag()))
                    .forEach(component -> componentMap.put(component.getId(), component));
        }

        Map<Long, List<DiseaseDetail>> detailMap = new HashMap<>();
        if (!diseaseIds.isEmpty()) {
            for (DiseaseDetail detail : diseaseDetailMapper.selectDiseaseDetailsByDiseaseIds(diseaseIds)) {
                if (detail.getDiseaseId() != null) {
                    detailMap.computeIfAbsent(detail.getDiseaseId(), key -> new ArrayList<>()).add(detail);
                }
            }
        }

        Map<Long, List<Attachment>> attachmentMap = new HashMap<>();
        List<Attachment> attachments = diseaseIds.isEmpty()
                ? Collections.emptyList()
                : attachmentService.getAttachmentBySubjectIds(diseaseIds);
        for (Attachment attachment : attachments) {
            if (attachment.getSubjectId() != null
                    && attachment.getName() != null
                    && attachment.getName().startsWith("disease")) {
                attachmentMap.computeIfAbsent(attachment.getSubjectId(), key -> new ArrayList<>())
                        .add(attachment);
            }
        }

        List<Long> minioIds = attachments.stream()
                .filter(attachment -> attachment.getName() != null
                        && attachment.getName().startsWith("disease"))
                .map(Attachment::getMinioId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FileMap> fileMapById = fileMapService.selectFileMapByIds(minioIds).stream()
                .collect(Collectors.toMap(fileMap -> fileMap.getId().longValue(), Function.identity(), (left, right) -> left));

        for (Disease disease : diseases) {
            disease.setComponent(componentMap.get(disease.getComponentId()));
            disease.setDiseaseDetails(detailMap.getOrDefault(disease.getId(), new ArrayList<>()));

            List<String> imageUrls = new ArrayList<>();
            for (Attachment attachment : attachmentMap.getOrDefault(disease.getId(), Collections.emptyList())) {
                if (Integer.valueOf(7).equals(attachment.getType())) {
                    continue;
                }

                FileMap fileMap = fileMapById.get(attachment.getMinioId());
                if (fileMap == null || StringUtils.isEmpty(fileMap.getNewName())
                        || fileMap.getNewName().length() < 2) {
                    continue;
                }

                String newName = fileMap.getNewName();
                imageUrls.add(minioConfig.getUrl() + "/" + minioConfig.getBucketName()
                        + "/" + newName.substring(0, 2) + "/" + newName);
            }
            disease.setImages(imageUrls);
        }

        return diseases;
    }

    /**
     * 查询病害列表
     *
     * @param disease 病害
     * @return 病害
     */
    @Override
    public List<Disease> selectDiseaseListForApi(Disease disease) {

        List<Disease> diseases = diseaseMapper.selectDiseaseList(disease);
        if (diseases == null) {
            return List.of();
        }

        diseases.forEach(ds -> {
            Long componentId = ds.getComponentId();

            if (componentId != null) {
                Component component = componentService.selectComponentById(componentId);
                if (component != null) {
                    BiObject parent = component.getBiObjectId() == null
                            ? null : biObjectMapper.selectDirectParentById(component.getBiObjectId());
                    if (parent != null) {
                        component.setParentObjectName(parent.getName());
                        if (parent.getParentId() != null) {
                            BiObject grandBiObject = biObjectMapper.selectBiObjectById(parent.getParentId());
                            if (grandBiObject != null) {
                                component.setGrandObjectName(grandBiObject.getName());
                            }
                        }
                    }
                    ds.setComponent(component);
                }
            }

            DiseaseDetail diseaseDetail = new DiseaseDetail();
            diseaseDetail.setDiseaseId(ds.getId());
            List<DiseaseDetail> diseaseDetails = diseaseDetailMapper.selectDiseaseDetailList(diseaseDetail);
            ds.setDiseaseDetails(diseaseDetails);

            List<String> images = new ArrayList<>();
            List<String> ADImgs = new ArrayList<>();
            List<Map<String, Object>> diseaseImage = diseaseController.getDiseaseImage(ds.getId());
            if (CollUtil.isNotEmpty(diseaseImage)) {
                diseaseImage.forEach(di -> {
                    Integer type = (Integer) di.get("type");
                    if (Integer.valueOf(7).equals(type)) {
                        ADImgs.add((String) di.get("url"));
                    } else {
                        images.add((String) di.get("url"));
                    }
                });

                ds.setImages(images);
                ds.setADImgs(ADImgs);
            }
        });
        return diseases;
    }

    /**
     * 为离线包查询病害列表，不加载图片URLs
     *
     * @param disease 病害
     * @return 病害列表
     */
    @Override
    public List<Disease> selectDiseaseListForZip(Disease disease) {
        // 获取基础的病害列表
        List<Disease> diseases = diseaseMapper.selectDiseaseList(disease);
        if (diseases.isEmpty()) {
            return diseases;
        }

        // 收集所有病害ID，构件ID和BiObjectId，用于批量查询
        List<Long> diseaseIds = new ArrayList<>();
        List<Long> componentIds = new ArrayList<>();
        Set<Long> biObjectIds = new HashSet<>();

        for (Disease ds : diseases) {
            diseaseIds.add(ds.getId());
            if (ds.getComponentId() != null) {
                componentIds.add(ds.getComponentId());
            }
            if (ds.getBiObjectId() != null) {
                biObjectIds.add(ds.getBiObjectId());
            }
        }

        // 批量查询所有病害详情
        List<DiseaseDetail> allDiseaseDetails = new ArrayList<>();
        allDiseaseDetails = diseaseDetailMapper.selectDiseaseDetailsByDiseaseIds(diseaseIds);

        // 批量查询所有构件
        Map<Long, Component> componentMap = new HashMap<>(16);
        if (!componentIds.isEmpty()) {
            List<Component> components = componentService.selectComponentsByIds(componentIds);
            for (Component component : components) {
                componentMap.put(component.getId(), component);
                // 收集BiObjectId用于后续查询
                if (component.getBiObjectId() != null) {
                    biObjectIds.add(component.getBiObjectId());
                }
            }
        }

        // 第一次查询所有直接关联的BiObject
        Map<Long, BiObject> biObjectMap = new HashMap<>(16);
        if (!biObjectIds.isEmpty()) {
            List<BiObject> biObjects = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(biObjectIds));

            // 收集所有父级ID
            Set<Long> parentIds = new HashSet<>();
            for (BiObject biObject : biObjects) {
                biObjectMap.put(biObject.getId(), biObject);
                if (biObject.getParentId() != null) {
                    parentIds.add(biObject.getParentId());
                }
            }

            // 查询所有父级BiObject
            if (!parentIds.isEmpty()) {
                List<BiObject> parentObjects = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(parentIds));

                // 收集所有祖父级ID
                Set<Long> grandParentIds = new HashSet<>();
                for (BiObject parent : parentObjects) {
                    biObjectMap.put(parent.getId(), parent);
                    if (parent.getParentId() != null) {
                        grandParentIds.add(parent.getParentId());
                    }
                }

                // 查询所有祖父级BiObject
                if (!grandParentIds.isEmpty()) {
                    List<BiObject> grandParentObjects = biObjectMapper.selectBiObjectsByIds(new ArrayList<>(grandParentIds));
                    for (BiObject grandParent : grandParentObjects) {
                        biObjectMap.put(grandParent.getId(), grandParent);
                    }
                }
            }
        }

        // 收集所有病害ID，用于批量查询附件
        List<Long> subjectIds = diseaseIds;

        // 批量查询所有附件
        Map<Long, List<Attachment>> attachmentMap = new HashMap<>();
        if (!subjectIds.isEmpty()) {
            List<Attachment> allAttachments = attachmentService.getAttachmentBySubjectIds(subjectIds);
            // 按病害ID分组
            for (Attachment attachment : allAttachments) {
                if (attachment.getSubjectId() != null) {
                    attachmentMap.computeIfAbsent(attachment.getSubjectId(), k -> new ArrayList<>()).add(attachment);
                }
            }
        }

        // 处理每个病害的数据
        for (Disease ds : diseases) {
            // 设置构件信息
            Long componentId = ds.getComponentId();
            if (componentId != null && componentMap.containsKey(componentId)) {
                Component component = componentMap.get(componentId);

                // 设置BiObject信息
                if (component.getBiObjectId() != null) {
                    BiObject biObject = biObjectMap.get(component.getBiObjectId());
                    if (biObject != null && biObject.getParentId() != null) {
                        BiObject parent = biObjectMap.get(biObject.getParentId());
                        if (parent != null) {
                            component.setParentObjectName(parent.getName());
                            if (parent.getParentId() != null) {
                                BiObject grandParent = biObjectMap.get(parent.getParentId());
                                if (grandParent != null) {
                                    component.setGrandObjectName(grandParent.getName());
                                }
                            }
                        }
                    }
                }

                ds.setComponent(component);
            }

            // 设置病害详情
            List<DiseaseDetail> diseaseDetails = allDiseaseDetails.stream().filter(detail -> detail.getDiseaseId().equals(ds.getId())).collect(Collectors.toList());
            ds.setDiseaseDetails(diseaseDetails);

            // 设置图片路径
            List<String> imagePaths = new ArrayList<>();
            List<String> adImagePaths = new ArrayList<>();

            // 获取该病害的所有附件
            List<Attachment> attachments = attachmentMap.getOrDefault(ds.getId(), Collections.emptyList());

            if (!attachments.isEmpty()) {
                // 直接构建预定义的相对路径格式
                Long buildingId = ds.getBuildingId();

                for (Attachment attachment : attachments) {
                    if (attachment.getName().startsWith("disease")) {
                        // 获取原始文件名，用于在ZIP中保存
                        String originalFileName = attachment.getId() + "_" + (attachment.getName().split("_", 2).length > 1 ? attachment.getName().split("_", 2)[1] : attachment.getName());

                        // 构建相对路径 (相对于buildingId的路径)
                        String relativePath = buildingId + "/disease/images/" + originalFileName;

                        // 根据类型区分普通图片和AD图片
                        if (attachment.getType() != null && attachment.getType() == 7) {
                            adImagePaths.add(relativePath);
                        } else {
                            imagePaths.add(relativePath);
                        }
                    }
                }
            }

            ds.setImages(imagePaths);
            ds.setADImgs(adImagePaths);
        }

        return diseases;
    }

    /**
     * 新增病害
     *
     * @param disease 病害
     * @return 结果
     */
    @Override
    @Transactional
    public Integer insertDisease(Disease disease) {
        disease.setCreateTime(DateUtils.getNowDate());
        if (disease.getType() == null || disease.getType().equals("")) {
            Long diseaseTypeId = disease.getDiseaseTypeId();
            DiseaseType diseaseType = diseaseTypeMapper.selectDiseaseTypeById(diseaseTypeId);
            disease.setType(diseaseType.getCode() + "#" + diseaseType.getName());
        }

        // 新增部件
        // 判断数据库是否存在相应构件
        BiObject biObject = biObjectMapper.selectBiObjectById(disease.getBiObjectId());
        Component component = disease.getComponent();

        component.setBiObjectId(disease.getBiObjectId());

        Component old = componentService.selectComponent(component);
        if (old == null) {
            if (disease.getBiObjectName() == null || disease.getBiObjectName().equals("")) {
                component.setName(component.getCode() + "#" + biObject.getName());
            } else {
                component.setName(component.getCode() + "#" + disease.getBiObjectName());
            }

            componentService.insertComponent(component);
            disease.setComponentId(component.getId());
        } else {
            disease.setComponentId(old.getId());
        }

        if (disease.getBiObjectName() == null || disease.getBiObjectName().equals("")) {
            disease.setBiObjectName(biObject.getName());
        }

        String imgNoExp = disease.getImgNoExp();
        if (imgNoExp != null && !imgNoExp.trim().equals("")) {
            try {
                String[] split = imgNoExp.split("、");
                disease.setImgNoExp(ReadFileServiceImpl.convertToDbFormat(Arrays.asList(split)));
            } catch (Exception e) {
                log.error("图片编号格式错误：{}", imgNoExp);
                throw new RuntimeException("图片编号格式错误：" + imgNoExp);
            }
        }


        Integer result = diseaseMapper.insertDisease(disease);
        if (disease.getLocalId() == null) {
            disease.setLocalId(disease.getId());
            diseaseMapper.updateDisease(disease);
        }

        // 添加病害详情
        List<DiseaseDetail> diseaseDetails = disease.getDiseaseDetails();
        if (CollUtil.isNotEmpty(diseaseDetails)) {
            diseaseDetails.forEach(diseaseDetail -> diseaseDetail.setDiseaseId(disease.getId()));
            diseaseDetailMapper.insertDiseaseDetails(diseaseDetails);
        }


        return result;
    }

    /**
     * 修改病害
     *
     * @param disease 病害
     * @return 结果
     */
    @Override
    @Transactional
    public int updateDisease(Disease disease) {
        Disease old = diseaseMapper.selectDiseaseById(disease.getId());
        if (old.getDiseaseTypeId().equals(disease.getDiseaseTypeId())) {
            DiseaseType diseaseType = diseaseTypeMapper.selectDiseaseTypeById(disease.getDiseaseTypeId());
            if (!diseaseType.getName().equals("其他")) {
                disease.setType(diseaseType.getCode() + "#" + diseaseType.getName());
            }
        }

        disease.setUpdateTime(DateUtils.getNowDate());

        if (disease.getComponent() != null
                && disease.getComponent().getCode() != null
                && !disease.getComponent().getCode().trim().isEmpty()) {
            rebindDiseaseComponent(old, disease);
        }

        // 删除病害详情
        diseaseDetailMapper.deleteDiseaseDetailByDiseaseId(disease.getId());

        // 新增病害详情
        List<DiseaseDetail> diseaseDetails = disease.getDiseaseDetails();
        if (CollUtil.isNotEmpty(diseaseDetails)) {
            diseaseDetails.forEach(diseaseDetail -> diseaseDetail.setDiseaseId(disease.getId()));
            diseaseDetailMapper.insertDiseaseDetails(diseaseDetails);
        }

        String imgNoExp = disease.getImgNoExp();
        if (imgNoExp != null && !imgNoExp.trim().equals("")) {
            try {
                String[] split = imgNoExp.split("、");
                disease.setImgNoExp(ReadFileServiceImpl.convertToDbFormat(Arrays.asList(split)));
            } catch (Exception e) {
                log.error("图片编号格式错误：{}", imgNoExp);
                throw new RuntimeException("图片编号格式错误：" + imgNoExp);
            }
        }

        return diseaseMapper.updateDisease(disease);
    }

    /**
     * 修改病害
     *
     * @param disease 病害
     * @return 结果
     */
    @Override
    @Transactional
    public int newUpdateDisease(Disease disease) {
        Disease old = diseaseMapper.selectDiseaseById(disease.getId());
        if (old == null) {
            throw new ServiceException("病害不存在，无法修改");
        }
        if (disease.getBiObjectName() == null || disease.getBiObjectName().trim().isEmpty()) {
            throw new ServiceException("当前病害缺少构件名称，请填写后再保存");
        }
        disease.setBiObjectName(disease.getBiObjectName().trim());

        DiseaseType diseaseType = diseaseTypeMapper.selectDiseaseTypeById(disease.getDiseaseTypeId());
        if (diseaseType == null) {
            throw new ServiceException("请选择有效的病害类型");
        }
        if (diseaseType.getName() != null && diseaseType.getName().startsWith("其他")) {
            if (disease.getType() == null || disease.getType().trim().isEmpty()) {
                throw new ServiceException("请输入自定义病害名称");
            }
            disease.setType(disease.getType().trim());
        } else {
            disease.setType(diseaseType.getCode() + "#" + diseaseType.getName());
        }

        disease.setUpdateTime(DateUtils.getNowDate());

        rebindDiseaseComponent(old, disease);

        // 删除病害详情
        diseaseDetailMapper.deleteDiseaseDetailByDiseaseId(disease.getId());

        // 新增病害详情
        List<DiseaseDetail> diseaseDetails = disease.getDiseaseDetails();
        if (CollUtil.isNotEmpty(diseaseDetails)) {
            diseaseDetails.forEach(diseaseDetail -> diseaseDetail.setDiseaseId(disease.getId()));
            diseaseDetailMapper.insertDiseaseDetails(diseaseDetails);
        }

        String imgNoExp = disease.getImgNoExp();
        if (imgNoExp != null && !imgNoExp.trim().equals("")) {
            try {
                String[] split = imgNoExp.split("、");
                disease.setImgNoExp(ReadFileServiceImpl.convertToDbFormat(Arrays.asList(split)));
            } catch (Exception e) {
                log.error("图片编号格式错误：{}", imgNoExp);
                throw new RuntimeException("图片编号格式错误：" + imgNoExp);
            }
        }

        return diseaseMapper.updateDisease(disease);
    }

    @Override
    public int updateDevelopmentTrend(Long id, String developmentTrend, String updateBy) {
        if (id == null || developmentTrend == null || developmentTrend.trim().isEmpty()) {
            throw new ServiceException("病害ID和发展趋势不能为空");
        }

        Disease update = new Disease();
        update.setId(id);
        update.setDevelopmentTrend(developmentTrend.trim());
        update.setUpdateBy(updateBy);
        update.setUpdateTime(DateUtils.getNowDate());
        return diseaseMapper.updateDisease(update);
    }

    /**
     * 只为当前病害换绑构件，不修改已有构件的编号或对象归属。
     * 唯一例外是原构件名称缺失时，允许补全该构件的名称。
     */
    private void rebindDiseaseComponent(Disease old, Disease disease) {
        if (disease.getBiObjectId() == null) {
            throw new ServiceException("所属对象不能为空");
        }
        if (disease.getComponent() == null) {
            throw new ServiceException("构件编号不能为空");
        }

        String newComponentCode = disease.getComponent().getCode() == null
                ? null
                : disease.getComponent().getCode().trim();
        if (StringUtils.isEmpty(newComponentCode)) {
            throw new ServiceException("构件编号不能为空");
        }
        disease.getComponent().setCode(newComponentCode);

        Component oldComponent = componentService.selectComponentById(old.getComponentId());
        if (oldComponent == null) {
            throw new ServiceException("原构件不存在，无法修改病害");
        }

        if (Objects.equals(old.getBiObjectId(), disease.getBiObjectId())
                && Objects.equals(oldComponent.getCode(), newComponentCode)) {
            String componentObjectName = extractComponentObjectName(oldComponent.getName());
            if (StringUtils.isEmpty(componentObjectName)) {
                componentObjectName = disease.getBiObjectName();
                oldComponent.setName(newComponentCode + "#" + componentObjectName);
                oldComponent.setUpdateBy(disease.getUpdateBy());
                oldComponent.setUpdateTime(DateUtils.getNowDate());
                if (componentService.updateComponent(oldComponent) <= 0) {
                    throw new ServiceException("构件名称补录失败，请稍后重试");
                }
            }
            disease.setComponentId(oldComponent.getId());
            disease.setBiObjectName(componentObjectName);
            return;
        }

        Component query = new Component();
        query.setBiObjectId(disease.getBiObjectId());
        query.setCode(newComponentCode);
        List<Component> targetComponents = componentMapper.selectComponentList(query);
        Component targetComponent = CollUtil.isEmpty(targetComponents)
                ? null
                : targetComponents.stream()
                .filter(component -> component.getId() != null)
                .min(Comparator.comparing(Component::getId))
                .orElse(null);

        if (targetComponent == null) {
            BiObject targetBiObject = biObjectMapper.selectBiObjectById(disease.getBiObjectId());
            if (targetBiObject == null) {
                throw new ServiceException("所属对象不存在，无法新建构件");
            }

            String componentObjectName = disease.getBiObjectName() == null
                    ? null
                    : disease.getBiObjectName().trim();
            if (StringUtils.isEmpty(componentObjectName)) {
                componentObjectName = targetBiObject.getName();
                if (StringUtils.isEmpty(componentObjectName)) {
                    throw new ServiceException("所属对象名称为空，无法新建构件");
                }
            }
            disease.setBiObjectName(componentObjectName);

            targetComponent = new Component();
            targetComponent.setBiObjectId(disease.getBiObjectId());
            targetComponent.setCode(newComponentCode);
            targetComponent.setName(newComponentCode + "#" + componentObjectName);
            targetComponent.setStatus("0");
            targetComponent.setDelFlag("0");
            targetComponent.setCreateBy(disease.getUpdateBy());
            componentService.insertComponent(targetComponent);
            if (targetComponent.getId() == null) {
                throw new ServiceException("新建构件失败，未生成构件ID");
            }
        } else {
            String targetComponentObjectName = extractComponentObjectName(targetComponent.getName());
            if (StringUtils.isEmpty(targetComponentObjectName)) {
                throw new ServiceException("目标构件缺少构件名称，请先完善构件信息");
            }
            disease.setBiObjectName(targetComponentObjectName);
        }

        disease.setComponentId(targetComponent.getId());
    }

    private String extractComponentObjectName(String componentName) {
        if (componentName == null) {
            return "";
        }
        String[] parts = componentName.split("#", 2);
        return parts.length > 1 ? parts[1].trim() : "";
    }

    /**
     * 批量删除病害
     *
     * @param ids 需要删除的病害主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteDiseaseByIds(String ids) {
        String[] strArray = Convert.toStrArray(ids);

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);

        List<CompletableFuture<Void>> futures = Arrays.stream(strArray).map(id -> CompletableFuture.runAsync(() -> {
            Disease disease = diseaseMapper.selectDiseaseById(Long.parseLong(id));
            diseaseDetailMapper.deleteDiseaseDetailByDiseaseId(disease.getId());
            deleteDiseaseImage(disease);
        }, executor)).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        executor.shutdown();

        return diseaseMapper.deleteDiseaseByIds(strArray);
    }

    @Override
    @Transactional
    public int deleteDiseasesByProjectIdentity(String projectName, Integer year, String code) {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new ServiceException("项目名称不能为空");
        }
        if (year == null) {
            throw new ServiceException("年份不能为空");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new ServiceException("项目编号不能为空");
        }

        String normalizedProjectName = projectName.trim();
        String normalizedCode = code.trim();
        Project query = new Project();
        query.setName(normalizedProjectName);
        query.setYear(year);
        query.setCode(normalizedCode);

        List<Project> projects = projectMapper.selectProjectList(query, null, null);
        List<Project> matchedProjects = (projects == null ? Collections.<Project>emptyList() : projects).stream()
                .filter(project -> normalizedProjectName.equals(project.getName()))
                .filter(project -> Objects.equals(year, project.getYear()))
                .filter(project -> normalizedCode.equals(project.getCode()))
                .collect(Collectors.toList());

        if (matchedProjects.isEmpty()) {
            throw new ServiceException("未找到匹配的项目");
        }
        if (matchedProjects.size() > 1) {
            throw new ServiceException("匹配到多个项目，请检查项目名称、年份、编号是否唯一");
        }

        List<Task> tasks = taskMapper.selectTaskListByProjectId(matchedProjects.get(0).getId());
        if (CollUtil.isEmpty(tasks)) {
            return 0;
        }

        List<String> diseaseIds = new ArrayList<>();
        for (Task task : tasks) {
            if (task.getId() == null) {
                continue;
            }
            Disease diseaseQuery = new Disease();
            diseaseQuery.setTaskId(task.getId());
            List<Disease> diseases = diseaseMapper.selectDiseaseList(diseaseQuery);
            if (CollUtil.isNotEmpty(diseases)) {
                diseases.stream()
                        .map(Disease::getId)
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .forEach(diseaseIds::add);
            }
        }

        if (diseaseIds.isEmpty()) {
            return 0;
        }
        return deleteDiseaseByIds(String.join(",", diseaseIds));
    }

    private void deleteDiseaseImage(Disease disease) {
        List<Attachment> attachmentList = attachmentService.getAttachmentList(disease.getId());
        for (Attachment value : attachmentList) {
            if (value.getName().startsWith("disease")) {
                attachmentService.deleteAttachmentById(value.getId());
            }
        }
    }

    /**
     * 删除病害信息
     *
     * @param id 病害主键
     * @return 结果
     */
    @Override
    @Transactional
    public int deleteDiseaseById(Long id) {
        Disease disease = diseaseMapper.selectDiseaseById(id);
        diseaseDetailMapper.deleteDiseaseDetailByDiseaseId(disease.getId());

        return diseaseMapper.deleteDiseaseById(id);
    }

    /**
     * 批量删除病害
     *
     * @param ids 需要删除的病害主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteDiseaseByDiseaseIds(String ids) {
        if (ids == null || ids.equals("")) {
            return 0;
        }
        String[] strArray = Convert.toStrArray(ids);
        Long[] longArray = Convert.toLongArray(ids);
        diseaseDetailMapper.deleteDiseaseDetailByDiseaseIds(longArray);
        return diseaseMapper.deleteDiseaseByIds(strArray);
    }

    /**
     * 计算扣分
     *
     * @param maxScale 最大分值
     * @param scale    当前分值
     * @return 结果
     */
    @Override
    public int computeDeductPoints(int maxScale, int scale) {
        return switch (maxScale) {
            case 3 -> switch (scale) {
                case 1 -> 0;
                case 2 -> 20;
                case 3 -> 35;
                default -> throw new IllegalArgumentException("当 max_scale 为 3 时，scale 只能为 1、2 或 3");
            };
            case 4 -> switch (scale) {
                case 1 -> 0;
                case 2 -> 25;
                case 3 -> 40;
                case 4 -> 50;
                default -> throw new IllegalArgumentException("当 max_scale 为 4 时，scale 只能为 1、2、3 或 4");
            };
            case 5 -> switch (scale) {
                case 1 -> 0;
                case 2 -> 35;
                case 3 -> 45;
                case 4 -> 60;
                case 5 -> 100;
                default -> throw new IllegalArgumentException("当 max_scale 为 5 时，scale 只能为 1、2、3、4 或 5");
            };
            default -> throw new IllegalArgumentException("max_scale 只能为 3、4 或 5");
        };
    }

    /**
     * 处理病害附件
     *
     * @param files 文件
     * @param id    病害id
     */
    @Override
    public void handleDiseaseAttachment(MultipartFile[] files, Long id, int type) {
        if (files == null) return;
        Arrays.stream(files).forEach(e -> {
            // 原始图片
            FileMap originalFileMap = fileMapService.handleFileUpload(e);
            Attachment attachment = new Attachment();
            try {
                // 缩略图
                MultipartFile thumbnailFile = null;
                thumbnailFile = createThumbnail(e, 1024,768, 0.5f);
                FileMap thumbnailFileMap = fileMapService.handleFileUpload(thumbnailFile);
                attachment.setThumbMinioId(Long.valueOf(thumbnailFileMap.getId()));
            } catch (Exception ex) {
                log.error("转化缩略图异常！" + ex.toString());
//                throw new RuntimeException("转化缩略图异常！");
            }
            attachment.setMinioId(Long.valueOf(originalFileMap.getId()));
            attachment.setName("disease_" + originalFileMap.getOldName());
            attachment.setSubjectId(id);
            attachment.setType(type);

            attachmentService.insertAttachment(attachment);
        });
    }



    /**
     * 处理病害附件(入参是文件）
     *
     * @param files 文件
     * @param id    病害id
     */
    public void handleDiseaseAttachmentWithFile(List<File> files, int type, Long id) {
        if (files == null || files.isEmpty()) return;

        files.stream().filter(Objects::nonNull).forEach(file -> {
            FileMap fileMap = fileMapService.handleFileUploadFromFile(file, file.getName(), ShiroUtils.getLoginName());

            Attachment attachment = new Attachment();
            try {
                // 缩略图
                File thumbnailFile = createThumbnail(file, 1024,768, 0.5f);
                FileMap thumbnailFileMap = fileMapService.handleFileUploadFromFile(thumbnailFile, file.getName(), ShiroUtils.getLoginName());
                attachment.setThumbMinioId(Long.valueOf(thumbnailFileMap.getId()));
            } catch (Exception ex) {
                log.error("转化缩略图异常！" + ex.toString());
//                throw new RuntimeException("转化缩略图异常！");
            }

            attachment.setMinioId(Long.valueOf(fileMap.getId()));
            attachment.setName("disease_" + fileMap.getOldName());
            attachment.setSubjectId(id);
            attachment.setType(type);
            attachmentService.insertAttachment(attachment);
        });
    }


    @Value("${springAi_Rag.endpoint}")
    private String springAiRagEndpoint;

    /**
     * 获取成因分析
     *
     * @param causeQuery
     * @return
     */
    @Override
    public String getCauseAnalysis(CauseQuery causeQuery) {
//        String host = "59.110.81.142";
//        int port = 8081;
        String url = springAiRagEndpoint + "/api-ai/disease/cause";

        causeQuery.setObjectId(null);
        try {
            String response = WebClient.create().post().uri(url).contentType(MediaType.APPLICATION_JSON).body(BodyInserters.fromValue(causeQuery)) // 直接发送对象作为 JSON
                    .retrieve().bodyToMono(String.class).block();

            return response;
        } catch (Exception e) {
            throw new RuntimeException("请求失败: " + e.getMessage());
        }
    }

    private void addComponent(Sheet sheet, Map<String, Building> buildingMap, Map<Long, BiObject> biObjectMap, Map<String, List<Component>> componentMap) {
        Set<Component> componentSet = new HashSet<>();

        for (int i = 3; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            String buildingName = getCellValueAsString(row.getCell(1));
            String side = getCellValueAsString(row.getCell(3));
            String component_2 = getCellValueAsString(row.getCell(4));
            String component_3 = getCellValueAsString(row.getCell(5));
            String location = getCellValueAsString(row.getCell(6));

            Building building = null;
            if (side != null && !side.equals("")) {
                building = buildingMap.get(buildingName + '-' + side);
            } else {
                building = buildingMap.get(buildingName);
            }
            if (building == null) {
                // 跳过
                return;
            }

            BiObject rootBiObject = biObjectMap.get(building.getRootObjectId());
            List<BiObject> children = rootBiObject.getChildren();
            BiObject biObject2 = children.stream().filter(child -> rootBiObject.getId().equals(child.getParentId()) && component_2.equals(child.getName())).findFirst().orElse(null);
            if (biObject2 == null) {
                log.info("未找到对应的子对象, parentId:{}, name:{}", rootBiObject.getId(), component_2);
                return;
            }

            BiObject biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && component_3.equals(child.getName())).findFirst().orElse(null);
            if (biObject3 == null) {
                log.info("未找到对应的子对象, parentId:{}, name:{}", biObject2.getId(), component_3);
                return;
            }


            // 新增部件
            Component component = handleDefectLocation(location);
            String biObjectName = component.getName().split("#")[1];

            BiObject biObject4 = null;
            if (biObject3.getName().equals("其他")) {
                biObject4 = children.stream().filter(child -> biObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
            } else {
                biObject4 = children.stream().filter(child -> biObject3.getId().equals(child.getParentId()) && biObjectName.equals(child.getName())).findFirst().orElse(null);
            }
            if (biObject4 == null) {
                log.info("未找到对应的子对象, parentId:{}, name:{}", biObject3.getId(), biObjectName);
                return;
            }

            List<Component> oldComponents = componentMap.get(component.getName());

            if (oldComponents != null && oldComponents.size() > 0) {
                Set<String> oldComponentRootObjectIds = oldComponents.stream().map(oldComponent -> oldComponent.getBiObject().getAncestors().split(",")[1]).collect(Collectors.toSet());

                if (oldComponentRootObjectIds.contains(rootBiObject.getId())) {
                    continue;
                }

            }

            component.setBiObjectId(biObject4.getId());
            component.setCreateBy(ShiroUtils.getLoginName());
            component.setStatus("0");
            componentSet.add(component);
        }
        // 持久化
        for (Component component : componentSet) {
            componentService.insertComponent(component);
        }
//        componentMapper.batchAddComponents(componentSet);
    }

    @Override
    public void readDiseaseExcel(MultipartFile file, Long projectId) {

        List<Component> components = componentService.selectComponentList(new Component());
        Map<String, List<Component>> componentMap = components.stream().collect(Collectors.groupingBy(Component::getName));
        Set<Disease> diseaseSet = new ConcurrentHashSet<>();

        // 病害类型”其他“，当病害类型都不存在时，默认为其他
        DiseaseType otherDiseaseType = diseaseTypeMapper.selectDiseaseTypeByCode("0.0.0.0-0");

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (int j = 0; j < workbook.getNumberOfSheets(); j++) {
                Sheet sheet = workbook.getSheetAt(j); // 获取第一个工作表

                Set<String> buildingSet = new HashSet<>();
                for (int i = 3; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    // 用来查询building
                    String buildingName = getCellValueAsString(row.getCell(1));
                    String side = getCellValueAsString(row.getCell(3));
                    if (side != null && !side.equals("")) {
                        buildingSet.add(buildingName + '-' + side);
                    } else {
                        buildingSet.add(buildingName);
                    }
                }

                List<Building> buildings = buildingMapper.selectBuildingByNames(buildingSet);
                Map<String, Building> buildingMap = buildings.stream().collect(Collectors.toMap(Building::getName, Function.identity()));

                Set<Long> rootObjectIds = buildings.stream().map(building -> building.getRootObjectId()).collect(Collectors.toSet());
                List<BiObject> biObjects = biObjectMapper.selectBiObjects(rootObjectIds);
                Map<Long, BiObject> biObjectMap = biObjects.stream().collect(Collectors.toMap(BiObject::getId, Function.identity()));

                // 添加构件
                addComponent(sheet, buildingMap, biObjectMap, componentMap);

                components = componentService.selectComponentList(new Component());
                Map<String, List<Component>> newComponentMap = components.stream().collect(Collectors.groupingBy(Component::getName));
                componentMap = newComponentMap;

                // 新增病害
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Subject subject = ThreadContext.getSubject();
                // 跳过表头行（前3行）
                for (int i = 3; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        // 解决多线程无上下文信息
                        ThreadContext.bind(subject);

                        String buildingName = getCellValueAsString(row.getCell(1));
                        String side = getCellValueAsString(row.getCell(3));
                        String component_2 = getCellValueAsString(row.getCell(4));
                        String component_3 = getCellValueAsString(row.getCell(5));
                        String location = getCellValueAsString(row.getCell(6));
                        String diseaseType = getCellValueAsString(row.getCell(7));
                        String diseaseNumber = getCellValueAsString(row.getCell(8));
                        String length = getCellValueAsString(row.getCell(9)); // 这里是暂存
                        String diseaseDescription = getCellValueAsString(row.getCell(11));
                        String defectLevel = getCellValueAsString(row.getCell(12));
                        String repairSuggestion = getCellValueAsString(row.getCell(13));

                        Building building = null;
                        if (side != null && !side.equals("")) {
                            building = buildingMap.get(buildingName + '-' + side);
                        } else {
                            building = buildingMap.get(buildingName);
                        }
                        if (building == null) {
                            // 跳过
                            return;
                        }

                        Component com = handleDefectLocation(location);
                        String biObjectName = com.getName().split("#")[1];

                        BiObject rootBiObject = biObjectMap.get(building.getRootObjectId());
                        List<BiObject> children = rootBiObject.getChildren();
                        BiObject biObject2 = children.stream().filter(child -> rootBiObject.getId().equals(child.getParentId()) && component_2.equals(child.getName())).findFirst().orElse(null);

                        BiObject biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && component_3.equals(child.getName())).findFirst().orElse(null);

                        BiObject biObject4 = null;
                        if (biObject3.getName().equals("其他")) {
                            biObject4 = children.stream().filter(child -> biObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
                        } else {
                            biObject4 = children.stream().filter(child -> biObject3.getId().equals(child.getParentId()) && biObjectName.equals(child.getName())).findFirst().orElse(null);
                        }

                        List<Component> oldComponents = newComponentMap.get(com.getName());

                        BiObject finalBiObject = biObject4;
                        Component component = oldComponents.stream().filter(oldComponent -> oldComponent.getBiObjectId().equals(finalBiObject.getId())).findFirst().orElse(null);

                        Disease disease = new Disease();
                        disease.setPosition(location);

                        // 病害类型
                        List<DiseaseType> diseaseTypes = diseaseTypeService.selectDiseaseTypeListByTemplateObjectId(biObject4.getId());

                        DiseaseType queryDiseaseType = null;
                        if (biObject3.getName().equals("其他")) {
                            queryDiseaseType = diseaseTypes.stream().filter(dt -> dt.getName().equals("其他")).findFirst().orElse(null);
                        } else {
                            queryDiseaseType = diseaseTypes.stream().filter(dt -> dt.getName().equals(diseaseType)).findFirst().orElse(null);
                        }

                        if (queryDiseaseType == null) {
                            queryDiseaseType = otherDiseaseType;
                        }

                        disease.setType(diseaseType);
                        disease.setDiseaseTypeId(queryDiseaseType.getId());

                        disease.setComponentId(component.getId());
                        disease.setBuildingId(building.getId());
                        disease.setProjectId(projectId);
                        disease.setBiObjectName(biObjectName);
                        disease.setBiObjectId(component.getBiObjectId());
                        disease.setRepairRecommendation(repairSuggestion);
                        if (defectLevel == null || defectLevel.equals("/") || defectLevel.equals("")) {
                            disease.setLevel(1);
                        } else {
                            disease.setLevel((int) Double.parseDouble(defectLevel));
                        }

                        disease.setDescription(diseaseDescription);
                        if (diseaseNumber == null || diseaseNumber.equals("/") || diseaseNumber.equals("")) {
                            disease.setQuantity(1);
                        } else {
                            disease.setQuantity((int) Double.parseDouble(diseaseNumber));
                        }

                        DiseaseDetail diseaseDetail = new DiseaseDetail();
                        if (length != null && !length.equals("/") && !length.equals("")) {
                            BigDecimal decimal = new BigDecimal(length);
                            diseaseDetail.setLength1(decimal);
                        }

                        List<DiseaseDetail> diseaseDetails = new ArrayList<>();
                        diseaseDetails.add(diseaseDetail);
                        disease.setDiseaseDetails(diseaseDetails);

                        diseaseSet.add(disease);
                    });
                    futures.add(future);
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        } catch (IOException e) {
            log.error("读取Excel文件时出错", e);
            throw new RuntimeException(e);
        }

        if (!diseaseSet.isEmpty()) {
            transactionTemplate.execute(status -> {
                diseaseMapper.batchInsertDiseases(new ArrayList<>(diseaseSet));

                List<DiseaseDetail> allDetails = diseaseSet.stream().flatMap(disease -> disease.getDiseaseDetails().stream().peek(detail -> detail.setDiseaseId(disease.getId()))).collect(Collectors.toList());
                if (!allDetails.isEmpty()) {
                    diseaseDetailMapper.insertDiseaseDetails(allDetails);
                }

                return true;
            });

        }

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
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    final String regex = "^.+#.+";

    private Component handleDefectLocation(String location) {
        Component component = new Component();

        switch (location) {
            case "R-1#台":
                component.setName("R-1#台身");
                component.setCode("R-1");
                break;
            case "L-0#台":
                component.setName("L-0#台身");
                component.setCode("L-0");
                break;
            case "R-0#台":
                component.setName("R-0#台身");
                component.setCode("R-0");
                break;
            case "R-梁":
                component.setName("R#T梁");
                component.setCode("R");
                break;
            case "L-梁":
                component.setName("L#T梁");
                component.setCode("L");
                break;
            case "通道内":
                component.setName("通道内");
                break;
            case "进水口":
                component.setName("出水口");
                break;
            case "出水口":
                component.setName("出水口");
                break;
            case "桥台":
                component.setName("桥身");
                break;
            case "其他":
                component.setName("其他");
                break;
            case "顶板":
                component.setName("实心板");
                break;
            default:
                if (location.matches(regex)) {
                    String[] split = location.split("#");
                    component.setName(location);
                    component.setCode(split[0]);
                    return component;
                }
        }

        return component;
    }

    private static final Map<String, Long> BRIDGE_TYPE_MAP = new HashMap<>();

    static {
        BRIDGE_TYPE_MAP.put("梁式桥", 1L);
        BRIDGE_TYPE_MAP.put("箱形拱桥", 3L);
        BRIDGE_TYPE_MAP.put("双曲拱桥", 4L);
        BRIDGE_TYPE_MAP.put("板拱桥", 5L);
        BRIDGE_TYPE_MAP.put("刚架拱桥", 6L);
        BRIDGE_TYPE_MAP.put("桁架拱桥", 7L);
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
    public Boolean readDiseaseZip(MultipartFile file) {
        List<Component> components = componentService.selectComponentList(new Component());
        Map<String, List<Component>> componentMap = components.stream().collect(Collectors.groupingBy(Component::getName));

        Path tempDir = null;

        try {
            tempDir = extractToTempDirectory(file);

            String originalFilename = file.getOriginalFilename();
            String zipName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
            Path jsonPath = tempDir.resolve(zipName).resolve("result.json");

//            String path = tempDir.toString() + "\\" + file.getOriginalFilename().split("\\.")[0];

            if (!Files.exists(jsonPath)) {
                log.error("result.json文件不存在于: " + jsonPath);
                throw new RuntimeException("result.json文件不存在");
            }

            String jsonContent = Files.readString(jsonPath);

            ObjectMapper objectMapper = new ObjectMapper();
            DiseaseReport diseaseReport = objectMapper.readValue(jsonContent, DiseaseReport.class);

            // 项目
            Project project = new Project();
            project.setName(diseaseReport.getProjectName());
            project.setYear(diseaseReport.getReportYear());

            List<Project> projects = projectMapper.selectProjectList(project, null, null);
            if (CollUtil.isEmpty(projects)) {
                // 新建项目
                project.setDeptId(101L);
                projectService.insertProject(project);
            } else {
                project = projects.get(0);
            }

            // 桥梁
            List<DiseaseReport.Building> buildings = diseaseReport.getBuilding();

            List<Building> buildingList = buildings.stream().map(building -> {
                Building bd = new Building();
                bd.setName(building.getBuildingName());
                if (building.getLineCode() != null) {
                    // 检查路线字典是否存在
                    String biBuildeingLine = sysDictDataService.selectDictLabel("bi_buildeing_line", building.getLineCode());
                    if (biBuildeingLine == null || biBuildeingLine.equals("")) {
                        SysDictData sysDictData = new SysDictData();
                        sysDictData.setDictType("bi_buildeing_line");
                        sysDictData.setDictValue(building.getLineCode());
                        sysDictData.setDictLabel(building.getLineName());
                        sysDictData.setStatus("0");
                        sysDictData.setDictSort(0L);
                        sysDictDataService.insertDictData(sysDictData);
                    }
                    bd.setLine(building.getLineCode());
                }

                if (building.getZipCode() != null) bd.setArea(building.getZipCode());


                List<Building> selectBuildings = buildingMapper.selectBuildingList(bd);
                if (CollUtil.isEmpty(selectBuildings)) {
                    bd.setIsLeaf("1");
                    bd.setStatus("0");

                    Long templateId = BRIDGE_TYPE_MAP.get(building.getBridgeType());
                    if (templateId == null) {
                        log.error("无法识别的桥梁类型: " + building.getBridgeType());
                        throw new RuntimeException("无法识别的桥梁类型");
                    }
                    bd.setTemplateId(templateId);
                    buildingService.insertBuilding(bd);
                } else {
                    bd = selectBuildings.get(0);
                }

                return bd;
            }).toList();
            Map<String, Building> buildingMap = buildingList.stream().collect(Collectors.toMap(Building::getName, Function.identity()));

            Set<Long> rootObjectIds = buildingList.stream().map(building -> building.getRootObjectId()).collect(Collectors.toSet());
            List<BiObject> biObjects = biObjectMapper.selectBiObjects(rootObjectIds);
            Map<Long, BiObject> biObjectMap = biObjects.stream().collect(Collectors.toMap(BiObject::getId, Function.identity()));

            // 项目绑定桥梁
            Task task = new Task();
            task.setProjectId(project.getId());
            List<Task> tasks = taskMapper.selectTaskList(task, null);
            List<Building> filterBuildings = buildingList.stream().filter(building -> !tasks.stream().anyMatch(t -> t.getBuildingId().equals(building.getId()))).toList();

            if (CollUtil.isNotEmpty(filterBuildings))
                taskService.batchInsertTasks(project.getId(), filterBuildings.stream().map(Building::getId).toList());

            for (DiseaseReport.Building building : buildings) {
                addComponent(building, buildingMap, biObjectMap, componentMap);

                addDiseases(building, buildingMap, biObjectMap, project, tempDir.resolve(zipName));
            }


        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (tempDir != null) {
                Path finalTempDir = tempDir;
                transactionTemplate.execute(status -> {
                    try {
                        deleteDirectory(finalTempDir);
                    } catch (IOException e) {
                        log.error("清理临时目录时出错: " + e.getMessage());
                    }

                    return true;
                });

            }
        }
        return true;
    }

    private void addDiseases(DiseaseReport.Building DRbuilding, Map<String, Building> buildingMap, Map<Long, BiObject> biObjectMap, Project project, Path path) {

        List<Component> components = componentService.selectComponentList(new Component());
        Map<String, List<Component>> componentMap = components.stream().collect(Collectors.groupingBy(Component::getName));
        String buildingName = DRbuilding.getBuildingName();
        Set<Disease> diseaseSet = new ConcurrentHashSet<>();

        // 病害类型”其他“，当病害类型都不存在时，默认为其他
        DiseaseType otherDiseaseType = diseaseTypeMapper.selectDiseaseTypeByCode("0.0.0.0-0");

        // 新增病害
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Subject subject = ThreadContext.getSubject();
        // 跳过表头行（前3行）
        for (DiseaseReport.Disease disease : DRbuilding.getDisease()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 解决多线程无上下文信息
                ThreadContext.bind(subject);

                String biObject_2 = disease.getBiObject2();
                String biObject_3 = disease.getBiObject3();
                String biObject_4 = disease.getBiObject4();
                String position = disease.getPosition();
                String diseaseType = disease.getDiseaseType();
                Integer diseaseNumber = 1;
                if (disease.getQuantity() != null && !disease.getQuantity().equals("/") && !disease.getQuantity().equals(""))
                    diseaseNumber = Integer.valueOf(disease.getQuantity());
                String description = disease.getDescription();
                String developmentTrend = disease.getDevelopmentTrend();
                String image = disease.getImage();
                Integer defectLevel = 1;
                if (disease.getLevel() != null && !disease.getLevel().equals("/") && !disease.getLevel().equals(""))
                    defectLevel = Integer.valueOf(disease.getLevel());

                Building building = buildingMap.get(buildingName);

                BiObject rootBiObject = biObjectMap.get(building.getRootObjectId());
                List<BiObject> children = rootBiObject.getChildren();
                BiObject biObject2 = children.stream().filter(child -> rootBiObject.getId().equals(child.getParentId()) && biObject_2.equals(child.getName())).findFirst().orElse(null);

                BiObject biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && biObject_3.equals(child.getName())).findFirst().orElse(null);
                if (biObject3 == null) {
                    log.info("未找到对应的子对象, parentId:{}, name:{}", biObject2.getId(), biObject_3);
                    biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
                }

                BiObject finalBiObject3 = biObject3;
                BiObject biObject4 = null;
                if (biObject3.getName().equals("其他")) {
                    biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
                } else {
                    biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && biObject_4.equals(child.getName())).findFirst().orElse(null);
                    if (biObject4 == null) {
                        biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
                    }
                }

                List<Component> oldComponents = componentMap.get(position);

                BiObject finalBiObject = biObject4;
                Component component = oldComponents.stream().filter(oldComponent -> oldComponent.getBiObjectId().equals(finalBiObject.getId())).findFirst().orElse(null);

                // 新增病害
                Disease newDisease = new Disease();
                String newPosition = null;
                if (position.matches(regex)) {
                    String[] split = position.split("#");
                    newPosition = split[1];
                } else {
                    newPosition = position;
                }
                newDisease.setPosition(newPosition);

                // 病害类型
                List<DiseaseType> diseaseTypes = diseaseTypeService.selectDiseaseTypeListByTemplateObjectId(biObject4.getId());

                DiseaseType queryDiseaseType = null;
                if (biObject3.getName().equals("其他")) {
                    queryDiseaseType = diseaseTypes.stream().filter(dt -> dt.getName().equals("其他")).findFirst().orElse(null);
                } else {
                    queryDiseaseType = diseaseTypes.stream().filter(dt -> dt.getName().equals(diseaseType)).findFirst().orElse(null);
                }

                if (queryDiseaseType == null) {
                    queryDiseaseType = otherDiseaseType;
                }

                newDisease.setType(diseaseType);
                newDisease.setDiseaseTypeId(queryDiseaseType.getId());

                newDisease.setComponentId(component.getId());
                newDisease.setBuildingId(building.getId());
                newDisease.setProjectId(project.getId());
                newDisease.setBiObjectName(biObject_4);
                newDisease.setBiObjectId(biObject4.getId());
                if (defectLevel != null) newDisease.setLevel(defectLevel);
                else newDisease.setLevel(1);
                if (diseaseNumber != null) newDisease.setQuantity(diseaseNumber);
                else newDisease.setQuantity(1);
                newDisease.setDescription(description);
                newDisease.setDevelopmentTrend(developmentTrend);
                newDisease.setImages(List.of(image));
                diseaseSet.add(newDisease);
            });
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        if (!diseaseSet.isEmpty()) {
            transactionTemplate.execute(status -> {
                diseaseMapper.batchInsertDiseases(new ArrayList<>(diseaseSet));

                for (Disease disease : diseaseSet) {
                    List<String> images = disease.getImages();

                    if (images != null && StringUtils.isNotEmpty(images.get(0))) {
                        MultipartFile file = convert(path, images.get(0));
                        handleDiseaseAttachment(new MultipartFile[]{file}, disease.getId(), 1);
                    }
                }


                return true;
            });
        }

        // 上传正面照
        List<String> frontImage = DRbuilding.getFrontImage();
        if (frontImage != null && !frontImage.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                String imageName = frontImage.get(i);
                if (imageName != null && !imageName.isEmpty()) {
                    // 转换为 MultipartFile
                    MultipartFile file = convert(path, imageName);
                    // 处理附件
                    fileMapController.uploadAttachment(buildingMap.get(buildingName).getId(), file, "newfront", i);
                }
            }
        }

        // 上传侧面照
        List<String> sideImage = DRbuilding.getSideImage();
        if (sideImage != null && !sideImage.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                String imageName = sideImage.get(i);
                if (imageName != null && !imageName.isEmpty()) {
                    // 转换为 MultipartFile
                    MultipartFile file = convert(path, imageName);
                    // 处理附件
                    fileMapController.uploadAttachment(buildingMap.get(buildingName).getId(), file, "newside", i);
                }
            }
        }

        // 上传桥梁卡片
        if (DRbuilding.getBuildingCard() != null && !DRbuilding.getBuildingCard().isEmpty()) {
            try {

                String filename = DRbuilding.getBuildingCard();
                File file = path.resolve(filename).toFile();

                if (!file.exists()) {
                    throw new FileNotFoundException("目标文件不存在: " + file.getAbsolutePath());
                }

                // 3. 读取文件内容到字节数组
                byte[] fileContent = Files.readAllBytes(file.toPath());

                // 4. 正确创建 MockMultipartFile 实例
                MockMultipartFile multipartFile = new MockMultipartFile("file", file.getName(), "application/octet-stream", fileContent);

                // 5. 调用服务层方法
                Property property = new Property();
                property.setCreateBy(ShiroUtils.getLoginName());
                property.setUpdateBy(ShiroUtils.getLoginName());
                propertyService.readWordFile(multipartFile, property, buildingMap.get(buildingName).getId());

            } catch (IOException e) {
                log.error("处理文件时发生错误: " + e.getMessage(), e);
                throw new RuntimeException("处理文件时发生错误: " + e.getMessage(), e);
            }
        }
    }

    public MultipartFile convert(Path path, String imageName) {
        // 构建完整的文件路径
        if (imageName.startsWith("/")) {
            imageName = imageName.substring(1);
        }
        File imageFile = path.resolve(imageName).toFile();

        // 检查文件是否存在
        if (!imageFile.exists()) {
            try {
                throw new IOException("文件不存在: " + imageFile.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 读取文件内容到字节数组

        byte[] bytes = null;
        try {
            FileInputStream inputStream = new FileInputStream(imageFile);
            bytes = inputStream.readAllBytes();
            inputStream.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 获取文件扩展名
        String originalFilename = imageFile.getName();
        String contentType = determineContentType(originalFilename);

        // 创建 MultipartFile 实例
        return new MockMultipartFile("file", // 表单字段名
                originalFilename, // 原始文件名
                contentType, // 内容类型
                bytes // 文件内容
        );
    }

    private static String determineContentType(String filename) {
        if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (filename.toLowerCase().endsWith(".png")) {
            return "image/png";
        } else if (filename.toLowerCase().endsWith(".gif")) {
            return "image/gif";
        } else if (filename.toLowerCase().endsWith(".bmp")) {
            return "image/bmp";
        } else {
            return "application/octet-stream"; // 默认类型
        }
    }

    private void addComponent(DiseaseReport.Building building, Map<String, Building> buildingMap, Map<Long, BiObject> biObjectMap, Map<String, List<Component>> componentMap) {
        Set<Component> componentSet = new HashSet<>();
        List<DiseaseReport.Disease> diseases = building.getDisease();
        String buildingName = building.getBuildingName();
        Building bd = buildingMap.get(buildingName);
        BiObject rootBiObject = biObjectMap.get(bd.getRootObjectId());
        List<BiObject> children = rootBiObject.getChildren();

        for (DiseaseReport.Disease disease : diseases) {
            String biObject_2 = disease.getBiObject2();
            String biObject_3 = disease.getBiObject3();
            String biObject_4 = disease.getBiObject4();
            String position = disease.getPosition();

            BiObject biObject2 = children.stream().filter(child -> rootBiObject.getId().equals(child.getParentId()) && biObject_2.equals(child.getName())).findFirst().orElse(null);
            if (biObject2 == null) {
                log.info("未找到对应的子对象, parentId:{}, name:{}, 层次:2", rootBiObject.getId(), biObject_2);
                return;
            }

            BiObject biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && biObject_3.equals(child.getName())).findFirst().orElse(null);
            if (biObject3 == null) {
                log.info("未找到对应的子对象, parentId:{}, name:{}, 层次:3", biObject2.getId(), biObject_3);
                biObject3 = children.stream().filter(child -> biObject2.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
            }

            BiObject finalBiObject3 = biObject3;
            BiObject biObject4 = null;
            if (biObject3.getName().equals("其他")) {
                biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
            } else {
                biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && biObject_4.equals(child.getName())).findFirst().orElse(null);
            }
            if (biObject4 == null) {
                log.info("未找到对应的四级子对象, parentId:{}, name:{}, 层次:4", biObject3.getId(), biObject_4);
                biObject4 = children.stream().filter(child -> finalBiObject3.getId().equals(child.getParentId()) && "其他".equals(child.getName())).findFirst().orElse(null);
            }

            Component component = new Component();
            if (position.matches(regex)) {
                String[] split = position.split("#");
                component.setName(position);
                component.setCode(split[0]);
            } else {
                component.setName(position);
                component.setCode("null");
            }

            List<Component> oldComponents = componentMap.get(component.getName());
            if (oldComponents != null && oldComponents.size() > 0) {
                Set<String> oldComponentRootObjectIds = oldComponents.stream().map(oldComponent -> oldComponent.getBiObject().getAncestors().split(",")[1]).collect(Collectors.toSet());

                if (oldComponentRootObjectIds.contains(rootBiObject.getId())) {
                    continue;
                }

            }
            component.setBiObjectId(biObject4.getId());
            component.setCreateBy(ShiroUtils.getLoginName());
            component.setStatus("0");
            componentSet.add(component);
        }
        ;

        // 持久化
        for (Component component : componentSet) {
            componentService.insertComponent(component);
        }
//        componentMapper.batchAddComponents(componentSet);
    }


    /**
     * 批量保存病害信息
     *
     * @param diseases 病害列表
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchSaveDiseases(List<Disease> diseases) {
        if (diseases == null || diseases.isEmpty()) {
            return 0;
        }

        int successCount = 0;

        // 记录已经插入了的构件
        Set<String> componentNames = new HashSet<>();
        Set<Long> localIds = new HashSet<>();
        HashMap<String, Long> componentMap = new HashMap<>(16);

        for (Disease disease : diseases) {
            if (disease.getComponent() != null && disease.getComponent().getName() != null) {
                componentNames.add(disease.getComponent().getName());
            }
            localIds.add(disease.getLocalId());
        }
        if (!componentNames.isEmpty()) {
            Component query = new Component();
            query.setParams(new HashMap<>());
            query.getParams().put("nameList", new ArrayList<>(componentNames));
            List<Component> components = componentService.selectComponentList(query);
            // 建立构件名称与ID的映射关系
            for (Component component : components) {
                BiObject biObject = component.getBiObject();
                componentMap.put(component.getName() + biObject.getId(), component.getId());
            }
        }


        // 记录已经插入的病害
        HashMap<Long, Disease> localIdMap = new HashMap<>(16);
        if (!localIds.isEmpty()) {
            Disease query = new Disease();
            query.setParams(new HashMap<>());
            query.getParams().put("localIds", new ArrayList<>(localIds));
            List<Disease> diseaseList = diseaseMapper.selectDiseaseListByLocalIds(query);
            for (Disease disease : diseaseList) {
                localIdMap.put(disease.getLocalId(), disease);
            }
        }

        // 收集需要删除的病害ID
        StringJoiner joiner = new StringJoiner(",");

        // 删除对应的病害文件 类型为1或者2都先删除
        StringJoiner attachmentJoiner = new StringJoiner(",");
        List<Long> subjectIds = new ArrayList<>();
        for (Disease disease : diseases) {
            int type = disease.getCommitType();
            boolean isTypeValid = (type == 1 || type == 2);
            if (isTypeValid && localIdMap.containsKey(disease.getLocalId())) {
                Disease oldDisease = localIdMap.get(disease.getLocalId());
                joiner.add(String.valueOf(oldDisease.getId()));
                subjectIds.add(oldDisease.getId());

            }
        }
        if (!subjectIds.isEmpty()) {
            List<Attachment> attachmentBySubjectId = attachmentService.getAttachmentBySubjectIds(subjectIds).stream().filter(e -> e.getName().startsWith("disease")).toList();

            for (Attachment attachment : attachmentBySubjectId) {
                attachmentJoiner.add(String.valueOf(attachment.getId()));
            }
            String attachmentIds = attachmentJoiner.toString();
            if (!attachmentIds.isEmpty()) {
                attachmentService.deleteAttachmentByIds(attachmentIds);
            }
        }

        // 批量删除病害及详细病害信息
        String deleteIds = joiner.toString();
        if (!deleteIds.isEmpty()) {
            deleteDiseaseByDiseaseIds(deleteIds);
        }

        // 处理新增或更新的病害
        Set<Disease> diseaseSet = new HashSet<>();
        List<DiseaseDetail> allDiseaseDetails = new ArrayList<>();
        for (Disease disease : diseases) {
            if (disease.getCommitType() == 0) {
                continue;
            }

            // 类型为1的需要再插入
            if (disease.getCommitType() == 1) {
                // 通过构件名称查找构件ID
                Component component = disease.getComponent();
                if (component == null) {
                    log.warn("构件为null，跳过不完整病害，localId: {}", disease.getLocalId());
                    continue;
                }
                if (component.getBiObject() == null) {
                    log.warn("构件缺少biObject，跳过不完整病害，component: {}, localId: {}",
                            JSON.toJSONString(component), disease.getLocalId());
                    continue;
                }
                component.setCreateBy(ShiroUtils.getLoginName());
                component.setUpdateBy(ShiroUtils.getLoginName());
                Long root = component.getBiObject().getId();

                // 判断是否需要新增构件
                if (disease.getComponent() != null && disease.getComponent().getName() != null && disease.getComponentId() == null && !componentMap.containsKey(component.getName() + root)) {
                    componentService.insertComponent(component);
                    componentMap.put(component.getName() + root, component.getId());
                }

                // 更新构件
                if (disease.getComponent() != null && disease.getComponentId() != null) {
                    componentService.updateComponent(component);
                }

                // 病害类型id为空则默认为其他的病害类型
                if (disease.getDiseaseTypeId() == null || disease.getDiseaseType().getId() == null || disease.getDiseaseType().getName().equals("其他")) {
                    disease.setDiseaseTypeId(238L);
                }

                // 设置构件ID
                disease.setComponentId(componentMap.get(component.getName() + root));
                disease.setCreateBy(ShiroUtils.getLoginName());
                disease.setUpdateBy(ShiroUtils.getLoginName());
                disease.setUpdateTime(new Date());
                int attachmentCount = 0;
                //记录附件数量
                if (disease.getADImgs() != null) {
                    attachmentCount = attachmentCount + disease.getADImgs().size();
                }
                if (disease.getImages() != null) {
                    attachmentCount = attachmentCount + disease.getImages().size();
                }
                disease.setAttachmentCount(attachmentCount);

                // 插入病害记录
                diseaseSet.add(disease);

                // 添加病害详情
                List<DiseaseDetail> diseaseDetails = disease.getDiseaseDetails();
                allDiseaseDetails.addAll(diseaseDetails);
            }
        }
        // 批量插入病害
        if (!diseaseSet.isEmpty()) {
            successCount += diseaseMapper.batchInsertDiseases(new ArrayList<>(diseaseSet));

            // 更新病害详情中的diseaseId
            for (Disease disease : diseaseSet) {
                List<DiseaseDetail> details = disease.getDiseaseDetails();
                if (details != null && !details.isEmpty()) {
                    for (DiseaseDetail detail : details) {
                        detail.setDiseaseId(disease.getId());
                    }
                }
            }

            // 批量插入病害详情
            if (!allDiseaseDetails.isEmpty()) {
                diseaseDetailMapper.insertDiseaseDetails(allDiseaseDetails);
            }
        }
        return successCount;
    }

    /**
     * 从Zip文件中提取文件
     */
    public Path extractToTempDirectory(MultipartFile multipartFile) throws IOException {
        // 创建临时目录
        Path tempDir = Files.createTempDirectory("zip-extract-");

        try (InputStream inputStream = multipartFile.getInputStream(); ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {

            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                Path filePath = tempDir.resolve(entry.getName());

                // 安全检查
                if (!filePath.normalize().startsWith(tempDir)) {
                    throw new IOException("恶意ZIP条目: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    Files.copy(zipInputStream, filePath);
                }
                zipInputStream.closeEntry();
            }
        }

        return tempDir;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    System.err.println("无法删除文件: " + p);
                }
            });
        }
    }

}
