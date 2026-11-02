package edu.whut.cs.bi.biz.domain.enums;

public enum ReportTemplateTypes {
    COMBINED_BRIDGE(0, new String[]{"组合桥"}),
    MULTI_BRIDGE(4, new String[]{"多桥"}),
    LEVEL_2_BEAM_BRIDGE(1, new String[]{"梁桥", "二级"}),
    LEVEL_2_ARCH_BRIDGE(2, new String[]{"拱桥", "二级"}),
    LEVEL_1_BEAM_BRIDGE(3, new String[]{"梁桥", "一级"}),
    TEST_TEMPLATE(99, new String[]{"测试"});

    private static final String REGULAR_MULTI_BRIDGE_TEMPLATE_NAME = "2026普通桥梁定期检查报告模板";

    private Integer type;
    private String[] desc;

    ReportTemplateTypes(Integer type, String[] desc) {
        this.type = type;
        this.desc = desc;
    }

    public Integer getType() {
        return type;
    }

    public String[] getDesc() {
        return desc;
    }

    public static boolean is2LevelSigleBridge(Integer type) {
        if (type.equals(LEVEL_2_BEAM_BRIDGE.getType())
                || type.equals(LEVEL_2_ARCH_BRIDGE.getType())
        ) {
            return true;
        }
        return false;
    }

    public static boolean is1LevelSigleBridge(Integer type) {
        if (type.equals(LEVEL_1_BEAM_BRIDGE.getType())

        ) {
            return true;
        }
        return false;
    }

    public static boolean isTestTemplate(Integer type) {
        if (type != null && type.equals(TEST_TEMPLATE.getType())) {
            return true;
        }
        return false;
    }

    /**
     * 是否单桥报告模板类型（二级梁桥、二级拱桥、一级梁桥）。
     */
    public static boolean isSingleBridge(Integer type) {
        return type != null && (is2LevelSigleBridge(type) || is1LevelSigleBridge(type));
    }

    /**
     * 单桥模板名 → 填报页类型（fillKind），与 resolveFillPath 的单桥分流规则一致。
     * 非单桥模板返回 null。
     */
    public static String resolveSingleBridgeFillKind(String templateName) {
        if (templateName == null || isMultiBridge(templateName)) {
            return null;
        }
        if (templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("二级")) {
            return "fill_single_beam";
        }
        if (templateName.contains("单桥") && templateName.contains("拱桥") && templateName.contains("二级")) {
            return "fill_single_arch";
        }
        if (templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("一级")) {
            return "fill_single_beam_level1";
        }
        return null;
    }

    /**
     * 单桥模板名 → 桥型：beam 梁桥 / arch 拱桥；非单桥模板返回 null。
     */
    public static String singleBridgeKindOf(String templateName) {
        String fillKind = resolveSingleBridgeFillKind(templateName);
        if (fillKind == null) {
            return null;
        }
        return "fill_single_arch".equals(fillKind) ? "arch" : "beam";
    }

    /**
     * 单桥模板名 → 评定等级：1 一级 / 2 二级；非单桥模板返回 null。
     */
    public static Integer singleBridgeLevelOf(String templateName) {
        String fillKind = resolveSingleBridgeFillKind(templateName);
        if (fillKind == null) {
            return null;
        }
        return "fill_single_beam_level1".equals(fillKind) ? 1 : 2;
    }

    public static boolean isMultiBridge(String templateName) {
        return templateName != null && (templateName.contains("多桥")
                || REGULAR_MULTI_BRIDGE_TEMPLATE_NAME.equals(templateName.trim()));
    }

    /**
     * 按模板名选择填报页路径（不含 ctx），填充和生成共用同一套名字规则。
     */
    public static String resolveFillPath(String templateName, Long reportId) {
        String prefix = "biz/report/fill";
        if (isMultiBridge(templateName)) {
            prefix = "biz/line_multi_bridge_data/fill";
        } else if (templateName != null && templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("二级")) {
            prefix = "biz/report_data/fill_single_beam";
        } else if (templateName != null && templateName.contains("单桥") && templateName.contains("拱桥") && templateName.contains("二级")) {
            prefix = "biz/report_data/fill_single_arch";
        } else if (templateName != null && templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("一级")) {
            prefix = "biz/report_data/fill_single_beam_level1";
        } else if (templateName != null && templateName.contains("测试")) {
            prefix = "biz/report_data/fill_test";
        }
        return prefix + "/" + reportId;
    }

    public static String resolveFillTitle(String templateName) {
        if (isMultiBridge(templateName)) {
            return "多桥定期检查报告填充";
        }
        if (templateName != null && templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("二级")) {
            return "二级单桥梁桥报告填充";
        }
        if (templateName != null && templateName.contains("单桥") && templateName.contains("拱桥") && templateName.contains("二级")) {
            return "二级单桥拱桥报告填充";
        }
        if (templateName != null && templateName.contains("单桥") && templateName.contains("梁桥") && templateName.contains("一级")) {
            return "一级单桥梁桥报告填充";
        }
        if (templateName != null && templateName.contains("测试")) {
            return "测试模板报告填充";
        }
        return "报告模板填充";
    }

    // 根据桥梁模板名 获取 桥梁模板类型。
    public static ReportTemplateTypes getEnumByDesc(String templateName) {
        if (templateName == null) {
            return null;
        }
        if (isMultiBridge(templateName)) {
            return MULTI_BRIDGE;
        }
        for (ReportTemplateTypes item : ReportTemplateTypes.values()) {
            boolean flag = true;
            for (String desc : item.getDesc()) {
                if (!templateName.contains(desc)) {
                    flag = false;
                    break;
                }
            }
            if (flag) {
                return item;
            }
        }
        return null;
    }

    public static String[] getDescByType(Integer type) {
        for (ReportTemplateTypes item : ReportTemplateTypes.values()) {
            if (item.getType().equals(type)) {
                return item.getDesc();
            }
        }
        return null;
    }

    public static ReportTemplateTypes getEnumByType(Integer type) {
        for (ReportTemplateTypes item : ReportTemplateTypes.values()) {
            if (item.getType().equals(type)) {
                return item;
            }
        }
        return null;
    }
}
