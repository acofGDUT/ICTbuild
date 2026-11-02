package edu.whut.cs.bi.biz.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.PageUtils;
import com.ruoyi.common.utils.ShiroUtils;
import edu.whut.cs.bi.biz.domain.*;
import edu.whut.cs.bi.biz.domain.enums.AreaIdentifierEnums;
import edu.whut.cs.bi.biz.mapper.BiObjectMapper;
import edu.whut.cs.bi.biz.mapper.BuildingMapper;
import edu.whut.cs.bi.biz.mapper.ProjectMapper;
import edu.whut.cs.bi.biz.mapper.TaskMapper;
import edu.whut.cs.bi.biz.service.IDiseaseService;
import edu.whut.cs.bi.biz.service.ITaskService;
import edu.whut.cs.bi.biz.utils.NaturalStringComparator;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 任务Controller
 *
 * @author chenwenqi
 * @date 2025-04-09
 */
@Controller
@RequestMapping("/biz/task")
public class TaskController extends BaseController {
    private String prefix = "biz/task";

    private static final String[] BATCH_DISEASE_HEADERS = {
            "序号", "桥梁名称", "幅别", "部位", "部件", "缺损位置", "缺损类型", "数量",
            "长度(m)", "缝宽(mm)", "面积(㎡)",
            "缺损情况", "维修建议", "评定类别", "照片编号", "发展趋势", "备注"
    };

    /** 纯Excel普通列的最大宽度（字符数）；缺损位置、缺损类型、缺损情况使用对应的固定宽度。 */
    private static final int[] BATCH_DISEASE_EXCEL_MAX_WIDTHS = {
            8, 18, 10, 16, 16, 22, 26, 8, 12, 12, 12, 46, 30, 10, 12, 24, 16
    };

    private static final MathContext DISEASE_AREA_CONTEXT = new MathContext(4, RoundingMode.HALF_UP);

    private static class BatchDiseaseExcelData {
        private final Map<Long, Task> taskMap = new HashMap<>();
        private final Map<Long, List<Disease>> diseaseMap = new LinkedHashMap<>();
        private final Map<Long, Building> buildingMap = new HashMap<>();
        private final Map<Long, BiObject> biObjectMap = new HashMap<>();
        private int maxPhotoCount = 1;
    }

    @Resource
    private ITaskService taskService;
    @Autowired
    private TaskMapper taskMapper;

    @Resource
    private ProjectMapper projectMapper;

    @Resource
    private IDiseaseService diseaseService;

    @Resource
    private BuildingMapper buildingMapper;

    @Resource
    private BiObjectMapper biObjectMapper;

    // 初始化OkHttp客户端（用于下载图片）
    private final okhttp3.OkHttpClient okHttpClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    @RequiresPermissions("biz:task:view")
    @GetMapping("/{select}")
    public String task(@PathVariable("select") String select, @RequestParam(name = "projectId", required = false) Long projectId, ModelMap mmap) {
        mmap.put("select", select);
        if (projectId != null) {
            mmap.put("projectId", projectId);
        }

        return prefix + "/task";
    }

    /**
     * 查询任务列表
     */
    @RequiresPermissions("biz:task:list")
    @PostMapping("/list/{select}")
    @ResponseBody
    public TableDataInfo list(@PathVariable("select") String select, Task task) {
        List<Task> list = taskService.selectTaskList(task, select);
        return getDataTable(list);
    }

    /**
     * 修改任务
     */
    @RequiresPermissions("biz:project:edit")
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable("id") Long id, ModelMap mmap) {
        Task task = taskService.selectTaskById(id);

        mmap.put("task", task);
        return prefix + "/edit";
    }

    /**
     * 修改任务
     */
    @RequiresPermissions("biz:project:edit")
    @Log(title = "任务", businessType = BusinessType.UPDATE)
    @PostMapping("/edit")
    @ResponseBody
    public AjaxResult changeStatus(Task task) {
        task.setUpdateBy(ShiroUtils.getLoginName());
        return toAjax(taskService.updateTask(task));
    }

    /**
     * 新增任务
     */
    @RequiresPermissions("biz:project:edit")
    @Log(title = "项目-任务", businessType = BusinessType.INSERT)
    @PostMapping("/addProjectBuilding")
    @ResponseBody
    public AjaxResult addProjectBuilding(Task task) {
        task.setCreateBy(ShiroUtils.getLoginName());
        return toAjax(taskService.insertTask(task));
    }

    /**
     * 批量新增任务
     */
    @RequiresPermissions("biz:project:edit")
    @Log(title = "项目-任务", businessType = BusinessType.INSERT)
    @PostMapping("/batchAddProjectBuilding")
    @ResponseBody
    public AjaxResult batchAddProjectBuilding(Long projectId, @RequestParam List<Long> buildingIds) {

        return toAjax(taskService.batchInsertTasks(projectId, buildingIds));
    }

    /**
     * 删除项目桥梁
     */
    @RequiresPermissions("biz:project:remove")
    @Log(title = "项目-任务", businessType = BusinessType.DELETE)
    @PostMapping("/cancelProjectBuilding")
    @ResponseBody
    public AjaxResult cancelProjectBuilding(Long projectId, Long buildingId) {
        return toAjax(taskService.removeTask(projectId, buildingId));
    }

    /**
     * 批量新增项目桥梁
     */
    @RequiresPermissions("biz:project:remove")
    @Log(title = "项目-任务", businessType = BusinessType.DELETE)
    @PostMapping("/batchCancelProjectBuilding")
    @ResponseBody
    public AjaxResult batchCancelProjectBuilding(Long projectId, @RequestParam List<Long> buildingIds) {

        return toAjax(taskService.batchRemoveTasks(projectId, buildingIds));
    }

    /**
     * 病害列表
     */
    @RequiresPermissions("biz:disease:view")
    @GetMapping("/inspect/{taskId}")
    public String userslist(@PathVariable("taskId") Long taskId, ModelMap mmap) {
        Task task = taskService.selectTaskById(taskId);

        mmap.put("task", task);
        return "biz/disease/disease";
    }

    /**
     * 查询任务列表
     */
    @RequiresPermissions("biz:task:list")
    @PostMapping("/listAll/{projectId}")
    @ResponseBody
    public List<Task> tasklist(@PathVariable("projectId") Long projectId) {
        List<Task> list = taskMapper.selectFullTaskListByProjectId(projectId);
        return list;
    }

    /**
     * 批量导出多个任务的病害数据
     */
    @RequiresPermissions("biz:disease:export")
    @Log(title = "批量导出任务病害", businessType = BusinessType.EXPORT)
    @GetMapping("/batchExport")
    public void batchExport(@RequestParam("taskIds") String taskIds, HttpServletResponse response) throws IOException {
        System.out.println("开始批量导出任务病害数据，任务ID: " + taskIds);
        List<Long> taskIdList = parseTaskIds(taskIds);
        if (taskIdList.isEmpty()) {
            return;
        }

        // -------------------------- 步骤1：生成Excel --------------------------
        ByteArrayOutputStream excelBaos = new ByteArrayOutputStream();
        List<String> allPhotoUrls = new ArrayList<>();
        try (Workbook workbook = buildBatchDiseaseWorkbook(taskIdList, allPhotoUrls, false)) {
            workbook.write(excelBaos);
        }

        // -------------------------- 步骤2：构建Zip（含Excel+照片文件夹） --------------------------

        // 设置Zip响应头
        response.setContentType("application/zip");
        String zipFileName = URLEncoder.encode("任务病害数据.zip", StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + zipFileName + "\"");
        response.setHeader("Cache-Control", "no-store, no-cache");

        // 初始化Zip输出流（直接写入响应，无中间文件）
        try (ZipOutputStream zipOut = new ZipOutputStream(response.getOutputStream());
             InputStream excelIs = new ByteArrayInputStream(excelBaos.toByteArray())) {

            // 2.1 向Zip添加Excel文件（根目录）
            ZipEntry excelEntry = new ZipEntry("病害清单.xlsx");
            zipOut.putNextEntry(excelEntry);
            byte[] buffer = new byte[1024 * 8]; // 8KB缓冲区
            int len;
            while ((len = excelIs.read(buffer)) != -1) {
                zipOut.write(buffer, 0, len);
            }
            zipOut.closeEntry(); // 关闭Excel条目

            // 2.2 向Zip添加照片文件夹及图片（001.jpg、002.jpg...）
            // 遍历所有图片URL，下载并写入Zip
            for (int i = 0; i < allPhotoUrls.size(); i++) {
                String imgUrl = allPhotoUrls.get(i);
                // 生成图片文件名（与Excel一致：001.jpg、002.jpg...）
                String photoFileName = String.format("%03d.jpg", i + 1);
                // Zip中路径：病害图片/001.jpg（放入单独文件夹）
                String zipPhotoPath = "病害图片/" + photoFileName;
                ZipEntry photoEntry = new ZipEntry(zipPhotoPath);
                zipOut.putNextEntry(photoEntry);

                // 核心：通过URL下载图片到Zip流
                try (InputStream imgIs = downloadImageByUrl(imgUrl)) {
                    if (imgIs == null) {
                        // 图片下载失败，写入提示文本（避免Zip损坏）
                        String errorMsg = "图片下载失败：" + imgUrl;
                        zipOut.write(errorMsg.getBytes(StandardCharsets.UTF_8));
                        System.err.println(errorMsg);
                        zipOut.closeEntry();
                        continue;
                    }
                    // 将图片流写入Zip
                    while ((len = imgIs.read(buffer)) != -1) {
                        zipOut.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    // 捕获下载异常，避免整个导出失败
                    String errorMsg = "图片处理异常：" + imgUrl + "，原因：" + e.getMessage();
                    zipOut.write(errorMsg.getBytes(StandardCharsets.UTF_8));
                    System.err.println(errorMsg);
                }
                zipOut.closeEntry(); // 关闭当前图片条目
            }

            zipOut.flush(); // 强制刷新，确保所有数据写入响应
        }
    }

    /**
     * 批量导出多个任务的病害 Excel，照片编号为可点击的图片 URL。
     * 照片列统一放在末尾；同一条病害有多张照片时，在同一行增加照片列，
     * 让每个照片编号都对应一个独立的 Excel 超链接。
     */
    @RequiresPermissions("biz:disease:export")
    @Log(title = "批量导出任务病害Excel", businessType = BusinessType.EXPORT)
    @RequestMapping(value = "/batchExportExcel", method = {RequestMethod.GET, RequestMethod.POST})
    public void batchExportExcel(@RequestParam("taskIds") String taskIds, HttpServletResponse response) throws IOException {
        System.out.println("开始批量导出任务病害Excel，任务ID: " + taskIds);
        List<Long> taskIdList = parseTaskIds(taskIds);
        if (taskIdList.isEmpty()) {
            return;
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("任务病害数据.xlsx", StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setHeader("Cache-Control", "no-store, no-cache");

        Workbook workbook = buildBatchDiseaseWorkbook(taskIdList, null, true);
        try {
            workbook.write(response.getOutputStream());
            response.getOutputStream().flush();
        } finally {
            workbook.close();
        }
    }

    private List<Long> parseTaskIds(String taskIds) {
        List<Long> taskIdList = new ArrayList<>();
        if (taskIds == null || taskIds.trim().isEmpty()) {
            return taskIdList;
        }

        for (String taskId : taskIds.split(",")) {
            try {
                taskIdList.add(Long.parseLong(taskId.trim()));
            } catch (NumberFormatException e) {
                // 忽略无效ID，保持与原批量导出逻辑一致
            }
        }
        return taskIdList;
    }

    /**
     * 构造批量病害 Excel。
     *
     * @param taskIdList   任务 ID
     * @param allPhotoUrls ZIP 导出时收集图片 URL；纯 Excel 导出时可传 null
     * @param photoLinks   是否将照片编号设置为图片 URL 超链接
     */
    private Workbook buildBatchDiseaseWorkbook(List<Long> taskIdList, List<String> allPhotoUrls,
                                               boolean photoLinks) {
        BatchDiseaseExcelData excelData = photoLinks ? loadBatchDiseaseExcelData(taskIdList) : null;
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("病害数据");
        CellStyle leftAlignedStyle = createLeftAlignedStyle(workbook);
        CellStyle hyperlinkStyle = photoLinks ? createHyperlinkStyle(workbook) : null;
        String[] headers = photoLinks
                ? createBatchDiseaseExcelHeaders(excelData.maxPhotoCount)
                : BATCH_DISEASE_HEADERS;

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(leftAlignedStyle);
        }

        int rowIndex = 1;
        int diseaseSerialNum = 1;
        int photoSerialNum = 1;
        Set<String> exportedProjectBuildings = new LinkedHashSet<>();

        for (Long taskId : taskIdList) {
            Task task = photoLinks ? excelData.taskMap.get(taskId) : taskService.selectTaskById(taskId);
            if (task == null) {
                continue;
            }

            List<Disease> diseaseList;
            if (photoLinks) {
                diseaseList = excelData.diseaseMap.getOrDefault(taskId, Collections.emptyList());
            } else {
                if (task.getProjectId() == null || task.getBuildingId() == null) {
                    continue;
                }
                String pairKey = projectBuildingKey(task.getProjectId(), task.getBuildingId());
                if (!exportedProjectBuildings.add(pairKey)) {
                    // 同一项目同一桥只导出一次，避免勾选重复任务时病害翻倍
                    continue;
                }
                // 与病害页单桥导出一致：按项目+桥梁取数，不按可能错乱的 task_id 过滤
                Disease queryDisease = new Disease();
                queryDisease.setProjectId(task.getProjectId());
                queryDisease.setBuildingId(task.getBuildingId());
                diseaseList = diseaseService.selectDiseaseListForTask(queryDisease);
            }

            Map<Long, BiObject> structureObjects = photoLinks
                    ? excelData.biObjectMap
                    : loadStructureObjects(diseaseList);
            List<Disease> sortedDiseaseList = new ArrayList<>(diseaseList);
            sortedDiseaseList.sort((left, right) ->
                    compareDiseaseByStructureOrder(left, right, structureObjects));

            for (Disease disease : sortedDiseaseList) {
                Row row = sheet.createRow(rowIndex++);
                int cellIndex = 0;

                row.createCell(cellIndex++).setCellValue(diseaseSerialNum++);

                Building building = disease.getBuildingId() == null ? null
                        : photoLinks ? excelData.buildingMap.get(disease.getBuildingId())
                        : buildingMapper.selectBuildingById(disease.getBuildingId());
                String bridgeName = building != null && building.getName() != null ? building.getName() : "";
                row.createCell(cellIndex++).setCellValue(bridgeName);

                row.createCell(cellIndex++).setCellValue(resolveBridgeSide(bridgeName));

                BiObject biObject = photoLinks ? excelData.biObjectMap.get(disease.getBiObjectId())
                        : biObjectMapper.selectBiObjectById(disease.getBiObjectId());
                String[] ancestorsIdArray = null;
                if (biObject != null && biObject.getAncestors() != null && !biObject.getAncestors().isEmpty()) {
                    ancestorsIdArray = biObject.getAncestors().split(",");
                }

                BiObject buildingObject = null;
                if (building != null && building.getRootObjectId() != null) {
                    buildingObject = photoLinks ? excelData.biObjectMap.get(building.getRootObjectId())
                            : biObjectMapper.selectBiObjectById(building.getRootObjectId());
                }
                boolean isFixedBridge = buildingObject != null
                        && buildingObject.getAncestors() != null
                        && buildingObject.getAncestors().length() >= 2;

                BiObject partLocationObject = null;
                BiObject nextPartLocationObject = null;
                if (ancestorsIdArray != null) {
                    int partLocationObjectIndex = isFixedBridge ? 3 : 2;
                    int nextPartLocationObjectIndex = isFixedBridge ? 4 : 3;
                    if (partLocationObjectIndex < ancestorsIdArray.length) {
                        Long partLocationObjectId = Long.valueOf(ancestorsIdArray[partLocationObjectIndex]);
                        partLocationObject = photoLinks ? excelData.biObjectMap.get(partLocationObjectId)
                                : biObjectMapper.selectBiObjectById(partLocationObjectId);
                    }
                    if (nextPartLocationObjectIndex < ancestorsIdArray.length) {
                        Long nextPartLocationObjectId = Long.valueOf(ancestorsIdArray[nextPartLocationObjectIndex]);
                        nextPartLocationObject = photoLinks ? excelData.biObjectMap.get(nextPartLocationObjectId)
                                : biObjectMapper.selectBiObjectById(nextPartLocationObjectId);
                    }
                }

                row.createCell(cellIndex++).setCellValue(
                        partLocationObject != null && partLocationObject.getName() != null
                                ? partLocationObject.getName() : "");
                row.createCell(cellIndex++).setCellValue(
                        nextPartLocationObject != null && nextPartLocationObject.getName() != null
                                ? nextPartLocationObject.getName() : "");

                String componentName = disease.getComponent() != null && disease.getComponent().getName() != null
                        ? disease.getComponent().getName() : "";
                row.createCell(cellIndex++).setCellValue(componentName);

                String type = disease.getType();
                if (type != null && !type.isEmpty() && type.contains("#")) {
                    type = type.substring(type.lastIndexOf("#") + 1);
                }
                row.createCell(cellIndex++).setCellValue(type != null ? type : "");
                row.createCell(cellIndex++).setCellValue(disease.getQuantity());
                row.createCell(cellIndex++).setCellValue(diseaseLengthText(disease));
                row.createCell(cellIndex++).setCellValue(diseaseCrackWidthText(disease));
                row.createCell(cellIndex++).setCellValue(diseaseAreaText(disease));
                row.createCell(cellIndex++).setCellValue(
                        disease.getDescription() != null ? disease.getDescription() : "");
                row.createCell(cellIndex++).setCellValue(
                        disease.getRepairRecommendation() != null ? disease.getRepairRecommendation() : "");
                row.createCell(cellIndex++).setCellValue(disease.getLevel());

                List<String> photoNames = new ArrayList<>();
                List<String> photoUrls = new ArrayList<>();
                List<String> diseaseImages = disease.getImages();
                if (diseaseImages != null) {
                    for (String imgUrl : diseaseImages) {
                        if (imgUrl == null || imgUrl.trim().isEmpty()) {
                            continue;
                        }
                        String photoFileName = String.format("%03d.jpg", photoSerialNum++);
                        photoNames.add(photoFileName);
                        photoUrls.add(imgUrl);
                        if (allPhotoUrls != null) {
                            allPhotoUrls.add(imgUrl);
                        }
                    }
                }

                if (photoLinks) {
                    row.createCell(cellIndex++).setCellValue(
                            disease.getDevelopmentTrend() != null ? disease.getDevelopmentTrend() : "");
                    row.createCell(cellIndex++).setCellValue(
                            disease.getRemark() != null ? disease.getRemark() : "");
                    row.createCell(cellIndex++).setCellValue(
                            disease.getUpdateBy() != null ? disease.getUpdateBy() : "");

                    for (int i = 0; i < excelData.maxPhotoCount; i++) {
                        Cell photoCell = row.createCell(cellIndex++);
                        if (i < photoNames.size()) {
                            setPhotoHyperlink(photoCell, photoNames.get(i), photoUrls.get(i), workbook, hyperlinkStyle);
                        }
                    }
                    applyLeftAlignment(row, leftAlignedStyle, headers.length);
                } else {
                    Cell photoCell = row.createCell(cellIndex++);
                    photoCell.setCellValue(String.join(", ", photoNames));
                    row.createCell(cellIndex++).setCellValue(
                            disease.getDevelopmentTrend() != null ? disease.getDevelopmentTrend() : "");
                    row.createCell(cellIndex).setCellValue(disease.getRemark() != null ? disease.getRemark() : "");
                    applyLeftAlignment(row, leftAlignedStyle, headers.length);
                }
            }
        }

        int maxColumnWidth = 255 * 256;
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = Math.min(sheet.getColumnWidth(i), maxColumnWidth);
            int compactWidth = Math.min(currentWidth + 2 * 256, maxColumnWidth);
            if (i == 5 || i == 6 || i == 11) {
                // 缺损位置、缺损类型、缺损情况使用指定的固定宽度。
                sheet.setColumnWidth(i, BATCH_DISEASE_EXCEL_MAX_WIDTHS[i] * 256);
            } else {
                int compactMaxWidth = getBatchDiseaseColumnMaxWidth(i, photoLinks) * 256;
                sheet.setColumnWidth(i, Math.min(compactWidth, compactMaxWidth));
            }
        }
        return workbook;
    }

    /**
     * 按结构树显示顺序排序。祖先链从桥根排到病害节点：上部结构在下部结构前，
     * 同一部位里上部承重构件在上部一般构件前。同一节点下再按构件名称，最后用病害 id 保持稳定。
     */
    private int compareDiseaseByStructureOrder(Disease left, Disease right, Map<Long, BiObject> objectMap) {
        int pathCompared = compareOrderPath(structureOrderPath(left, objectMap), structureOrderPath(right, objectMap));
        if (pathCompared != 0) {
            return pathCompared;
        }

        int nameCompared = NaturalStringComparator.compare(getDiseaseLocation(left), getDiseaseLocation(right));
        if (nameCompared != 0) {
            return nameCompared;
        }
        long leftId = left == null || left.getId() == null ? Long.MAX_VALUE : left.getId();
        long rightId = right == null || right.getId() == null ? Long.MAX_VALUE : right.getId();
        return Long.compare(leftId, rightId);
    }

    private int compareOrderPath(List<Integer> leftPath, List<Integer> rightPath) {
        if (leftPath.isEmpty() || rightPath.isEmpty()) {
            if (leftPath.isEmpty() == rightPath.isEmpty()) {
                return 0;
            }
            return leftPath.isEmpty() ? 1 : -1;
        }
        int size = Math.min(leftPath.size(), rightPath.size());
        for (int i = 0; i < size; i++) {
            int compared = Integer.compare(leftPath.get(i), rightPath.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftPath.size(), rightPath.size());
    }

    /**
     * 用祖先 id 串组路径，不依赖 parentId。祖先对象缺失时补一个空位，避免层级错开。
     */
    private List<Integer> structureOrderPath(Disease disease, Map<Long, BiObject> objectMap) {
        List<Integer> path = new ArrayList<>();
        if (disease == null || disease.getBiObjectId() == null || objectMap == null) {
            return path;
        }
        BiObject self = objectMap.get(disease.getBiObjectId());
        if (self == null) {
            return path;
        }
        if (self.getAncestors() != null && !self.getAncestors().isEmpty()) {
            for (String ancestorId : self.getAncestors().split(",")) {
                Long id = parseObjectId(ancestorId);
                if (id == null || id <= 0) {
                    continue;
                }
                path.add(orderNumOf(objectMap.get(id)));
            }
        }
        path.add(orderNumOf(self));
        return path;
    }

    private Long parseObjectId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int orderNumOf(BiObject node) {
        if (node == null || node.getOrderNum() == null) {
            return Integer.MAX_VALUE;
        }
        return node.getOrderNum();
    }

    /**
     * 压缩包导出没有预加载结构树，排序前补齐病害节点及其祖先的显示顺序。
     */
    private Map<Long, BiObject> loadStructureObjects(List<Disease> diseases) {
        Map<Long, BiObject> objectMap = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (Disease disease : diseases) {
            if (disease.getBiObjectId() != null) {
                ids.add(disease.getBiObjectId());
            }
        }
        putValidBiObjects(objectMap, ids);

        Set<Long> ancestorIds = new LinkedHashSet<>();
        for (BiObject biObject : new ArrayList<>(objectMap.values())) {
            if (biObject.getAncestors() == null || biObject.getAncestors().isEmpty()) {
                continue;
            }
            for (String ancestorId : biObject.getAncestors().split(",")) {
                try {
                    Long id = Long.valueOf(ancestorId.trim());
                    if (id > 0 && !objectMap.containsKey(id)) {
                        ancestorIds.add(id);
                    }
                } catch (NumberFormatException ignored) {
                    // 非法祖先 ID 不参与排序。
                }
            }
        }
        putValidBiObjects(objectMap, ancestorIds);
        return objectMap;
    }

    /**
     * 获取批量导出 Excel 中“缺损位置”列的实际显示值。
     */
    private String getDiseaseLocation(Disease disease) {
        if (disease == null || disease.getComponent() == null) {
            return null;
        }
        return disease.getComponent().getName();
    }

    private String projectBuildingKey(Long projectId, Long buildingId) {
        return String.valueOf(projectId) + "_" + String.valueOf(buildingId);
    }

    /**
     * 根据桥梁名称识别幅别；未包含左幅或右幅时保持为空。
     */
    private String resolveBridgeSide(String bridgeName) {
        if (bridgeName == null || bridgeName.isEmpty()) {
            return "";
        }
        if (bridgeName.contains("左幅")) {
            return "左幅";
        }
        if (bridgeName.contains("右幅")) {
            return "右幅";
        }
        return "";
    }

    /**
     * 批量准备纯Excel导出数据。
     * 病害按任务的项目+桥梁归属，避免 task_id 错乱时批量导出比单桥少。
     */
    private BatchDiseaseExcelData loadBatchDiseaseExcelData(List<Long> taskIdList) {
        BatchDiseaseExcelData data = new BatchDiseaseExcelData();

        Map<Long, Task> tasksById = new LinkedHashMap<>();
        for (Long taskId : taskIdList) {
            // 先占位，后面用库中任务覆盖，保持勾选顺序
            tasksById.put(taskId, null);
        }
        for (Task task : taskService.selectTaskListByIds(taskIdList)) {
            tasksById.put(task.getId(), task);
            data.taskMap.put(task.getId(), task);
        }

        List<Task> loadedTasks = new ArrayList<>();
        Map<String, Long> projectBuildingToTaskId = new LinkedHashMap<>();
        for (Long taskId : taskIdList) {
            Task task = tasksById.get(taskId);
            if (task == null || task.getProjectId() == null || task.getBuildingId() == null) {
                continue;
            }
            loadedTasks.add(task);
            String pairKey = projectBuildingKey(task.getProjectId(), task.getBuildingId());
            // 同一项目同一桥被勾选多次时，病害只挂到第一次出现的任务，避免重复行
            projectBuildingToTaskId.putIfAbsent(pairKey, task.getId());
        }

        List<Disease> diseases = diseaseService.selectDiseaseListForExcel(loadedTasks);
        Set<Long> buildingIds = new LinkedHashSet<>();
        Set<Long> initialBiObjectIds = new LinkedHashSet<>();
        for (Disease disease : diseases) {
            Long ownerTaskId = projectBuildingToTaskId.get(
                    projectBuildingKey(disease.getProjectId(), disease.getBuildingId()));
            if (ownerTaskId == null) {
                continue;
            }
            data.diseaseMap.computeIfAbsent(ownerTaskId, key -> new ArrayList<>()).add(disease);
            if (disease.getImages() != null) {
                int photoCount = 0;
                for (String imageUrl : disease.getImages()) {
                    if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                        photoCount++;
                    }
                }
                data.maxPhotoCount = Math.max(data.maxPhotoCount, photoCount);
            }
            if (disease.getBuildingId() != null) {
                buildingIds.add(disease.getBuildingId());
            }
            if (disease.getBiObjectId() != null) {
                initialBiObjectIds.add(disease.getBiObjectId());
            }
        }

        if (!buildingIds.isEmpty()) {
            for (Building building : buildingMapper.selectBuildingsByIds(new ArrayList<>(buildingIds))) {
                data.buildingMap.put(building.getId(), building);
                if (building.getRootObjectId() != null) {
                    initialBiObjectIds.add(building.getRootObjectId());
                }
            }
        }

        putValidBiObjects(data.biObjectMap, initialBiObjectIds);

        Set<Long> ancestorIds = new LinkedHashSet<>();
        for (Disease disease : diseases) {
            BiObject biObject = data.biObjectMap.get(disease.getBiObjectId());
            if (biObject == null || biObject.getAncestors() == null || biObject.getAncestors().isEmpty()) {
                continue;
            }
            for (String ancestorId : biObject.getAncestors().split(",")) {
                try {
                    Long id = Long.valueOf(ancestorId.trim());
                    if (!data.biObjectMap.containsKey(id)) {
                        ancestorIds.add(id);
                    }
                } catch (NumberFormatException ignored) {
                    // 与原导出保持空值容错，非法祖先ID不参与批量查询。
                }
            }
        }
        putValidBiObjects(data.biObjectMap, ancestorIds);
        return data;
    }

    private void putValidBiObjects(Map<Long, BiObject> target, Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        for (BiObject biObject : biObjectMapper.selectBiObjectsByIds(new ArrayList<>(ids))) {
            if ("0".equals(biObject.getDelFlag())) {
                target.put(biObject.getId(), biObject);
            }
        }
    }

    private String[] createBatchDiseaseExcelHeaders(int photoCount) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            headers.add(BATCH_DISEASE_HEADERS[i]);
        }
        headers.add(BATCH_DISEASE_HEADERS[15]);
        headers.add(BATCH_DISEASE_HEADERS[16]);
        headers.add("更新用户名");
        for (int i = 0; i < photoCount; i++) {
            headers.add(photoCount == 1 ? "照片" : "照片" + (i + 1));
        }
        return headers.toArray(new String[0]);
    }

    private int getBatchDiseaseColumnMaxWidth(int columnIndex, boolean photoLinks) {
        if (photoLinks) {
            return columnIndex < BATCH_DISEASE_EXCEL_MAX_WIDTHS.length
                    ? BATCH_DISEASE_EXCEL_MAX_WIDTHS[columnIndex] : 12;
        }

        // ZIP内Excel仍保持“照片编号、发展趋势、备注”的原列顺序。
        if (columnIndex == 14) {
            return 12;
        }
        if (columnIndex == 15) {
            return BATCH_DISEASE_EXCEL_MAX_WIDTHS[14];
        }
        if (columnIndex == 16) {
            return BATCH_DISEASE_EXCEL_MAX_WIDTHS[15];
        }
        return BATCH_DISEASE_EXCEL_MAX_WIDTHS[columnIndex];
    }

    /**
     * 与病害页导出一致：一条明细取长度或长度范围中值；多条明细取长度的最小、最大值中值。
     */
    private String diseaseLengthText(Disease disease) {
        List<DiseaseDetail> details = diseaseDetailsOf(disease);
        if (details.isEmpty()) {
            return "";
        }
        if (details.size() == 1) {
            DiseaseDetail detail = details.get(0);
            if (detail.getLength1() != null) {
                return detail.getLength1().toPlainString();
            }
            return averageOf(detail.getLengthRangeStart(), detail.getLengthRangeEnd());
        }
        if (details.get(0).getLength1() == null) {
            return "";
        }
        return averageOfExtremes(details.stream().map(DiseaseDetail::getLength1).toList());
    }

    /**
     * 缝宽是裂缝张开宽度，单位毫米。计算方式与长度相同。
     */
    private String diseaseCrackWidthText(Disease disease) {
        List<DiseaseDetail> details = diseaseDetailsOf(disease);
        if (details.isEmpty()) {
            return "";
        }
        if (details.size() == 1) {
            DiseaseDetail detail = details.get(0);
            if (detail.getCrackWidth() != null) {
                return detail.getCrackWidth().toPlainString();
            }
            return averageOf(detail.getCrackWidthRangeStart(), detail.getCrackWidthRangeEnd());
        }
        if (details.get(0).getCrackWidth() == null) {
            return "";
        }
        return averageOfExtremes(details.stream().map(DiseaseDetail::getCrackWidth).toList());
    }

    /**
     * 面积＝面积长×面积宽。平均面积再乘病害数量；多条明细时把各条面积相加。结果保留 4 位有效数字。
     */
    private String diseaseAreaText(Disease disease) {
        List<DiseaseDetail> details = diseaseDetailsOf(disease);
        if (details.isEmpty()) {
            return "";
        }
        if (details.size() == 1) {
            DiseaseDetail detail = details.get(0);
            if (detail.getAreaLength() == null || detail.getAreaWidth() == null) {
                return "";
            }
            BigDecimal area = detail.getAreaLength().multiply(detail.getAreaWidth());
            Integer identifier = detail.getAreaIdentifier();
            if (identifier != null && identifier == AreaIdentifierEnums.AVERAGE.getCode()) {
                area = area.multiply(BigDecimal.valueOf(disease.getQuantity()));
            }
            return area.round(DISEASE_AREA_CONTEXT).toPlainString();
        }

        BigDecimal total = BigDecimal.ZERO;
        boolean hasArea = false;
        for (DiseaseDetail detail : details) {
            if (detail.getAreaLength() == null || detail.getAreaWidth() == null) {
                continue;
            }
            total = total.add(detail.getAreaLength().multiply(detail.getAreaWidth()));
            hasArea = true;
        }
        return hasArea ? total.round(DISEASE_AREA_CONTEXT).toPlainString() : "";
    }

    private List<DiseaseDetail> diseaseDetailsOf(Disease disease) {
        if (disease == null || disease.getDiseaseDetails() == null) {
            return Collections.emptyList();
        }
        return disease.getDiseaseDetails();
    }

    private String averageOf(BigDecimal start, BigDecimal end) {
        if (start == null || end == null) {
            return "";
        }
        return start.add(end).divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private String averageOfExtremes(List<BigDecimal> values) {
        BigDecimal min = null;
        BigDecimal max = null;
        for (BigDecimal value : values) {
            if (value == null) {
                continue;
            }
            if (min == null || value.compareTo(min) < 0) {
                min = value;
            }
            if (max == null || value.compareTo(max) > 0) {
                max = value;
            }
        }
        return averageOf(min, max);
    }

    private CellStyle createLeftAlignedStyle(Workbook workbook) {
        CellStyle leftAlignedStyle = workbook.createCellStyle();
        leftAlignedStyle.setAlignment(HorizontalAlignment.LEFT);
        leftAlignedStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        return leftAlignedStyle;
    }

    private CellStyle createHyperlinkStyle(Workbook workbook) {
        Font hyperlinkFont = workbook.createFont();
        hyperlinkFont.setColor(IndexedColors.BLUE.getIndex());
        hyperlinkFont.setUnderline(Font.U_SINGLE);

        CellStyle hyperlinkStyle = workbook.createCellStyle();
        hyperlinkStyle.setFont(hyperlinkFont);
        hyperlinkStyle.setAlignment(HorizontalAlignment.LEFT);
        hyperlinkStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        return hyperlinkStyle;
    }

    private void applyLeftAlignment(Row row, CellStyle leftAlignedStyle, int columnCount) {
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            Cell cell = row.getCell(columnIndex);
            if (cell != null && cell.getHyperlink() == null) {
                cell.setCellStyle(leftAlignedStyle);
            }
        }
    }

    private void setPhotoHyperlink(Cell cell, String displayName, String imageUrl,
                                   Workbook workbook, CellStyle hyperlinkStyle) {
        cell.setCellValue(displayName);
        Hyperlink hyperlink = workbook.getCreationHelper().createHyperlink(HyperlinkType.URL);
        hyperlink.setAddress(imageUrl);
        cell.setHyperlink(hyperlink);
        cell.setCellStyle(hyperlinkStyle);
    }

    /**
     * 工具方法：通过URL下载图片，返回图片输入流
     *
     * @param imgUrl 图片URL
     * @return 图片输入流（null表示下载失败）
     */
    private InputStream downloadImageByUrl(String imgUrl) {
        try {
            // 构建HTTP请求
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(imgUrl)
                    .build();
            // 发送请求获取响应
            okhttp3.Response response = okHttpClient.newCall(request).execute();
            // 检查响应是否成功（200状态码）
            if (!response.isSuccessful()) {
                System.err.println("图片URL响应失败：" + imgUrl + "，状态码：" + response.code());
                return null;
            }
            // 返回图片输入流（无需关闭，外层try-with-resources会处理）
            return response.body().byteStream();
        } catch (Exception e) {
            System.err.println("图片下载异常：" + imgUrl + "，原因：" + e.getMessage());
            return null;
        }
    }
}
