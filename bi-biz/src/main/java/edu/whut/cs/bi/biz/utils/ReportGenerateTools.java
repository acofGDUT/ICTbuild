package edu.whut.cs.bi.biz.utils;

import cn.hutool.core.util.ObjectUtil;
import com.ruoyi.common.utils.StringUtils;
import edu.whut.cs.bi.biz.config.MinioConfig;
import edu.whut.cs.bi.biz.domain.Attachment;
import edu.whut.cs.bi.biz.domain.BiObject;
import edu.whut.cs.bi.biz.domain.Disease;
import edu.whut.cs.bi.biz.domain.FileMap;
import edu.whut.cs.bi.biz.domain.constants.ReportConstants;
import edu.whut.cs.bi.biz.domain.vo.DiseaseCauseMatch;
import edu.whut.cs.bi.biz.service.AttachmentService;
import edu.whut.cs.bi.biz.service.IFileMapService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jetbrains.annotations.NotNull;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ReportGenerateTools {

    @Autowired
    private MinioClient minioClientAutoWired;

    @Autowired
    private MinioConfig minioConfigAutoWired;

    @Autowired
    private AttachmentService attachmentServiceAutoWired;

    @Autowired
    private IFileMapService fileMapServiceAutoWired;

    private static MinioClient minioClient;

    private static MinioConfig minioConfig;

    private static AttachmentService attachmentService;

    private static IFileMapService fileMapService;

    @PostConstruct
    public void init() {
        // 静态变量初始化后注入。
        minioClient = minioClientAutoWired;
        minioConfig = minioConfigAutoWired;
        attachmentService = attachmentServiceAutoWired;
        fileMapService = fileMapServiceAutoWired;
    }

    /**
     * 替换Word文档中的文本
     * ! 如果一个 paragarph中有很多个 占位符需要替换 ， 可能会有问题。
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    public static void replaceText(XWPFDocument document, String oldText, String newText) {
        if (newText == null) {
            newText = "";
        }

        // 替换正文段落中的文本
        replaceTextInParagraphs(document.getParagraphs(), oldText, newText, "正文段落");

        // 替换正文表格中的文本
        replaceTextInTables(document.getTables(), oldText, newText, "正文表格");

        // 替换页眉中的文本
        replaceTextInHeaders(document, oldText, newText);

        // 替换页脚中的文本
        replaceTextInFooters(document, oldText, newText);
    }

    /**
     * 替换段落列表中的文本
     * ! 如果一个 paragarph中有很多个 占位符需要替换 ， 可能会有问题。
     *
     * @param paragraphs 段落列表
     * @param oldText    要替换的文本
     * @param newText    新文本
     * @param location   位置描述（用于日志）
     */
    public static void replaceTextInParagraphs(List<XWPFParagraph> paragraphs, String oldText, String newText, String location) {
        for (XWPFParagraph paragraph : paragraphs) {
            String paragraphText = paragraph.getText();
            if (paragraphText != null && paragraphText.contains(oldText)) {
                List<XWPFRun> runs = paragraph.getRuns();

                // 先尝试简单替换（单个run包含完整占位符的情况）
                boolean replaced = false;
                for (int i = 0; i < runs.size(); i++) {
                    XWPFRun run = runs.get(i);
                    String text = run.getText(0);
                    if (text != null && text.contains(oldText)) {
                        // 保留原有的字体样式和大小
                        String replacedText = text.replace(oldText, newText);
                        // 使用支持换行符的方法设置文本
                        setTextWithLineBreaks(run, replacedText);
                        replaced = true;
                        log.debug("在{}中替换占位符: {} -> {}", location, oldText, newText);
                        break;
                    }
                }

                // 如果简单替换失败，处理占位符分散在多个run的情况
                if (!replaced && runs.size() > 1) {
                    // 查找占位符的开始和结束位置
                    int startRunIndex = -1;
                    int endRunIndex = -1;
                    StringBuilder fullText = new StringBuilder();

                    // 寻找占位符的开始和结束位置
                    for (int i = 0; i < runs.size(); i++) {
                        String text = runs.get(i).getText(0);
                        if (text == null) continue;

                        fullText.append(text);
                        // 记录可能的起始位置
                        if (startRunIndex == -1 && text.contains("${")) {
                            startRunIndex = i;
                        }

                        // 检查到目前为止的文本是否包含完整占位符
                        if (fullText.toString().contains(oldText)) {
                            endRunIndex = i;
                            break;
                        }
                    }

                    // 如果找到了完整的占位符
                    if (startRunIndex != -1 && endRunIndex != -1) {
                        log.info("在{}中发现分散占位符: {} 从run[{}]到run[{}]", location, oldText, startRunIndex, endRunIndex);

                        // 将第一个run设置为替换后的文本（使用支持换行符的方法）
                        setTextWithLineBreaks(runs.get(startRunIndex), newText);

                        // 清空其他包含占位符的run
                        for (int i = startRunIndex + 1; i <= endRunIndex; i++) {
                            runs.get(i).setText("", 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * 替换表格列表中的文本
     *
     * @param tables   表格列表
     * @param oldText  要替换的文本
     * @param newText  新文本
     * @param location 位置描述（用于日志）
     */
    public static void replaceTextInTables(List<XWPFTable> tables, String oldText, String newText, String location) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    // 替换表格单元格中的段落文本
                    replaceTextInParagraphs(cell.getParagraphs(), oldText, newText, location + "单元格");

                    // 递归处理嵌套表格
                    if (!cell.getTables().isEmpty()) {
                        replaceTextInTables(cell.getTables(), oldText, newText, location + "嵌套表格");
                    }
                }
            }
        }
    }


    /**
     * 替换页眉中的文本
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    public static void replaceTextInHeaders(XWPFDocument document, String oldText, String newText) {
        try {
            List<XWPFHeader> headers = document.getHeaderList();
            if (headers != null && !headers.isEmpty()) {
                log.debug("开始处理页眉，共{}个", headers.size());

                for (int i = 0; i < headers.size(); i++) {
                    XWPFHeader header = headers.get(i);
                    String location = "页眉" + (i + 1);

                    // 替换页眉段落中的文本
                    replaceTextInParagraphs(header.getParagraphs(), oldText, newText, location);

                    // 替换页眉表格中的文本
                    replaceTextInTables(header.getTables(), oldText, newText, location + "表格");
                }

                log.debug("页眉文本替换完成");
            }
        } catch (Exception e) {
            log.warn("处理页眉时发生错误: {}", e.getMessage());
        }
    }

    /**
     * 替换页脚中的文本
     *
     * @param document Word文档
     * @param oldText  要替换的文本
     * @param newText  新文本
     */
    public static void replaceTextInFooters(XWPFDocument document, String oldText, String newText) {
        try {
            List<XWPFFooter> footers = document.getFooterList();
            if (footers != null && !footers.isEmpty()) {
                log.debug("开始处理页脚，共{}个", footers.size());

                for (int i = 0; i < footers.size(); i++) {
                    XWPFFooter footer = footers.get(i);
                    String location = "页脚" + (i + 1);

                    // 替换页脚段落中的文本
                    replaceTextInParagraphs(footer.getParagraphs(), oldText, newText, location);

                    // 替换页脚表格中的文本
                    replaceTextInTables(footer.getTables(), oldText, newText, location + "表格");
                }

                log.debug("页脚文本替换完成");
            }
        } catch (Exception e) {
            log.warn("处理页脚时发生错误: {}", e.getMessage());
        }
    }


    /**
     * 设置Run的文本，支持处理换行符
     * <p>
     * 如果文本中包含换行符（\n或\r\n），会将文本分行，并在每行之间插入换行符。
     * 这样可以在Word中正确显示用户在前端textarea中输入的多行文本。
     * </p>
     *
     * @param run  XWPFRun对象
     * @param text 要设置的文本（可能包含换行符）
     */
    /** 报告正文片段；highlight 为 true 时标红，提示人工补充。 */
    public record TextSegment(String text, boolean highlight) {
    }

    /**
     * 「成因分析：…」「处治建议：…」两段；未匹配或原文为空时写红字占位。
     */
    public static List<TextSegment> causeMatchSegments(DiseaseCauseMatch match) {
        List<TextSegment> segments = new ArrayList<>();
        segments.add(new TextSegment("成因分析：\n", false));
        addTextOrPlaceholder(segments, match == null ? null : match.getCause(), DiseaseCauseMatch.CAUSE_PLACEHOLDER);
        segments.add(new TextSegment("\n处治建议：\n", false));
        addTextOrPlaceholder(segments, match == null ? null : match.getSuggestion(), DiseaseCauseMatch.SUGGESTION_PLACEHOLDER);
        return segments;
    }

    private static void addTextOrPlaceholder(List<TextSegment> segments, String text, String placeholder) {
        boolean missing = text == null || text.isBlank();
        segments.add(new TextSegment(missing ? placeholder : text.trim(), missing));
    }

    /** 小四 = 12 磅 = 24 half-points。 */
    public static final int BODY_FONT_HALF_POINTS = 24;

    private static final Pattern CAUSE_POINT_PREFIX = Pattern.compile("^[（(]?\\d+[）).、．]\\s*");

    private static final List<String> CAUSE_STRUCTURE_NAMES = List.of("上部结构", "下部结构", "桥面系");

    /**
     * 成因分析、处治建议的排序键：先上部结构、下部结构、桥面系，再按该层下部件的显示顺序。
     * 只用于排序，不把这三层名称写进正文。
     */
    public static int[] causeStructureSortKey(Long biObjectId, Map<Long, BiObject> nodeMap) {
        int structureRank = CAUSE_STRUCTURE_NAMES.size();
        int componentOrder = Integer.MAX_VALUE;
        int selfOrder = Integer.MAX_VALUE;
        if (biObjectId == null || nodeMap == null || nodeMap.isEmpty()) {
            return new int[]{structureRank, componentOrder, selfOrder};
        }
        BiObject self = nodeMap.get(biObjectId);
        if (self != null && self.getOrderNum() != null) {
            selfOrder = self.getOrderNum();
        }
        BiObject current = self;
        int guard = 0;
        while (current != null && guard++ < 32) {
            String name = current.getName() == null ? "" : current.getName().trim();
            int rank = CAUSE_STRUCTURE_NAMES.indexOf(name);
            if (rank >= 0) {
                structureRank = rank;
                componentOrder = -1;
                break;
            }
            Long parentId = current.getParentId();
            BiObject parent = parentId == null ? null : nodeMap.get(parentId);
            if (parent != null) {
                String parentName = parent.getName() == null ? "" : parent.getName().trim();
                int parentRank = CAUSE_STRUCTURE_NAMES.indexOf(parentName);
                if (parentRank >= 0) {
                    structureRank = parentRank;
                    componentOrder = current.getOrderNum() == null ? Integer.MAX_VALUE : current.getOrderNum();
                    break;
                }
            }
            current = parent;
        }
        return new int[]{structureRank, componentOrder, selfOrder};
    }

    public static int compareCauseGroup(Long leftObjectId, Long rightObjectId, Long leftTypeId, Long rightTypeId,
                                        Map<Long, BiObject> nodeMap) {
        int[] left = causeStructureSortKey(leftObjectId, nodeMap);
        int[] right = causeStructureSortKey(rightObjectId, nodeMap);
        for (int i = 0; i < left.length; i++) {
            int compared = Integer.compare(left[i], right[i]);
            if (compared != 0) {
                return compared;
            }
        }
        long leftType = leftTypeId == null ? Long.MAX_VALUE : leftTypeId;
        long rightType = rightTypeId == null ? Long.MAX_VALUE : rightTypeId;
        return Long.compare(leftType, rightType);
    }

    /**
     * 重点关注病害中的一条成因。单条写成「成因分析：1）…」，多条先单独写「成因分析：」再逐条编号。
     * 知识库里已有的 1) / 1） 编号会去掉后重排，右括号用全角，以便符号走宋体、数字走 Times New Roman。
     */
    public static List<List<TextSegment>> causeAnalysisParagraphs(int index, String title, String cause) {
        List<List<TextSegment>> paragraphs = new ArrayList<>();
        paragraphs.add(List.of(new TextSegment("（" + index + "）" + (title == null ? "" : title), false)));
        if (cause == null || cause.isBlank()) {
            paragraphs.add(List.of(
                    new TextSegment("成因分析：", false),
                    new TextSegment(DiseaseCauseMatch.CAUSE_PLACEHOLDER, true)));
            return paragraphs;
        }
        List<String> points = splitCausePoints(cause);
        if (points.size() <= 1) {
            String text = points.isEmpty() ? "" : withEnding(points.get(0), "。");
            paragraphs.add(List.of(new TextSegment("成因分析：1）" + text, false)));
            return paragraphs;
        }
        paragraphs.add(List.of(new TextSegment("成因分析：", false)));
        for (int i = 0; i < points.size(); i++) {
            String ending = i == points.size() - 1 ? "。" : "；";
            paragraphs.add(List.of(new TextSegment((i + 1) + "）" + withEnding(points.get(i), ending), false)));
        }
        return paragraphs;
    }

    /**
     * 技术建议：每条处治建议一行，编号 1）2）。正文相同（忽略空白和句末标点）的合并成一条，保留先出现的一条。
     * 未匹配时写红字「需补充处治建议（病害名称）」，每条病害单独保留。
     */
    public static List<List<TextSegment>> technicalAdviceParagraphs(List<String> suggestions, List<String> diseases) {
        List<String> texts = new ArrayList<>();
        List<Boolean> missingFlags = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (suggestions != null) {
            for (int i = 0; i < suggestions.size(); i++) {
                String raw = suggestions.get(i);
                boolean missing = raw == null || raw.isBlank()
                        || DiseaseCauseMatch.SUGGESTION_PLACEHOLDER.equals(raw.trim());
                if (missing) {
                    String disease = diseases != null && i < diseases.size() ? diseases.get(i) : null;
                    texts.add(missingAdvice(disease));
                    missingFlags.add(true);
                    continue;
                }
                String text = flattenAdvice(raw);
                if (!seen.add(adviceKey(text))) {
                    continue;
                }
                texts.add(text);
                missingFlags.add(false);
            }
        }
        List<List<TextSegment>> paragraphs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String ending = i == texts.size() - 1 ? "。" : "；";
            paragraphs.add(List.of(
                    new TextSegment((i + 1) + "）", false),
                    new TextSegment(withEnding(texts.get(i), ending), missingFlags.get(i))));
        }
        return paragraphs;
    }

    static List<String> splitCausePoints(String cause) {
        List<String> points = new ArrayList<>();
        for (String raw : cause.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            line = CAUSE_POINT_PREFIX.matcher(line).replaceFirst("").trim();
            if (!line.isEmpty()) {
                points.add(line);
            }
        }
        return points;
    }

    private static String missingAdvice(String disease) {
        if (disease == null || disease.isBlank()) {
            return DiseaseCauseMatch.SUGGESTION_PLACEHOLDER;
        }
        return DiseaseCauseMatch.SUGGESTION_PLACEHOLDER + "（" + disease.trim() + "）";
    }

    private static String flattenAdvice(String text) {
        return text.replace("\r\n", "").replace("\n", "").replace("\r", "").trim();
    }

    private static String adviceKey(String text) {
        return text.replaceAll("\\s+", "").replaceAll("[。；;，,、.]+$", "");
    }

    static String withEnding(String text, String ending) {
        String trimmed = text == null ? "" : text.trim().replaceAll("[。；;]+$", "");
        return trimmed + ending;
    }

    /**
     * 用多段文字替换正文及表格中的占位符：沿用占位符所在 run 的格式，换行写为段内换行，highlight 片段标红。
     */
    public static void replaceTextWithSegments(XWPFDocument document, String placeholder, List<TextSegment> segments) {
        List<XWPFParagraph> paragraphs = new ArrayList<>(document.getParagraphs());
        collectTableParagraphs(document.getTables(), paragraphs);
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            int index = text == null ? -1 : text.indexOf(placeholder);
            if (index < 0) {
                continue;
            }
            CTRPr baseRPr = null;
            for (XWPFRun run : paragraph.getRuns()) {
                String runText = run.getText(0);
                if (runText != null && runText.contains("${")) {
                    baseRPr = run.getCTR().isSetRPr() ? (CTRPr) run.getCTR().getRPr().copy() : null;
                    break;
                }
            }
            List<TextSegment> all = new ArrayList<>();
            all.add(new TextSegment(text.substring(0, index), false));
            all.addAll(segments);
            all.add(new TextSegment(text.substring(index + placeholder.length()), false));
            clearParagraph(paragraph);
            XWPFRun last = null;
            for (TextSegment segment : all) {
                String[] lines = segment.text() == null ? new String[0]
                        : segment.text().replace("\r", "").split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        if (last == null) {
                            last = createStyledRun(paragraph, baseRPr, false);
                        }
                        last.addBreak();
                    }
                    if (!lines[i].isEmpty()) {
                        last = createStyledRun(paragraph, baseRPr, segment.highlight());
                        last.setText(lines[i]);
                    }
                }
            }
        }
    }

    private static XWPFRun createStyledRun(XWPFParagraph paragraph, CTRPr baseRPr, boolean highlight) {
        XWPFRun run = paragraph.createRun();
        if (baseRPr != null) {
            run.getCTR().setRPr((CTRPr) baseRPr.copy());
        }
        if (highlight) {
            run.setColor("FF0000");
        }
        return run;
    }

    /**
     * 用正文段落替换占位符。每段宋体小四、两端对齐、1.5 倍行距、首行缩进 2 字符；
     * 数字和英文 Times New Roman，中文和全角符号宋体。highlight 片段标红。
     */
    public static void writeBodyParagraphs(XWPFDocument document, String placeholder,
                                           List<List<TextSegment>> paragraphs) {
        XWPFParagraph first = findParagraphByPlaceholder(document, placeholder);
        if (first == null) {
            log.warn("未找到占位符: {}", placeholder);
            return;
        }
        clearParagraph(first);
        if (paragraphs == null || paragraphs.isEmpty()) {
            return;
        }
        fillBodyParagraph(first, paragraphs.get(0));
        XmlCursor cursor = null;
        try {
            for (int i = 1; i < paragraphs.size(); i++) {
                if (cursor == null) {
                    cursor = first.getCTP().newCursor();
                    if (!cursor.toNextSibling()) {
                        cursor.toEndToken();
                        cursor.toNextToken();
                    }
                }
                XWPFParagraph paragraph = document.insertNewParagraph(cursor);
                if (paragraph == null) {
                    log.warn("无法在占位符后插入段落: {}", placeholder);
                    return;
                }
                cursor.toNextToken();
                fillBodyParagraph(paragraph, paragraphs.get(i));
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static void fillBodyParagraph(XWPFParagraph paragraph, List<TextSegment> runs) {
        if (runs != null) {
            for (TextSegment segment : runs) {
                if (segment == null || segment.text() == null || segment.text().isEmpty()) {
                    continue;
                }
                int before = paragraph.getRuns().size();
                appendMixedFontRuns(paragraph, segment.text(), BODY_FONT_HALF_POINTS, false, "宋体");
                if (segment.highlight()) {
                    List<XWPFRun> written = paragraph.getRuns();
                    for (int i = before; i < written.size(); i++) {
                        written.get(i).setColor("FF0000");
                    }
                }
            }
        }
        applyReportBodyFormat(paragraph);
    }

    /** 正文：两端对齐、首行缩进 2 字符、1.5 倍行距。字体由调用方按中西文分别写入。 */
    static void applyReportBodyFormat(XWPFParagraph paragraph) {
        if (paragraph == null) {
            return;
        }
        String style = paragraph.getStyle();
        if (style != null && style.trim().matches("[1-9]")) {
            paragraph.setStyle(null);
        }
        paragraph.setAlignment(ParagraphAlignment.BOTH);
        CTPPr ppr = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        if (ppr.isSetOutlineLvl()) {
            ppr.unsetOutlineLvl();
        }
        if (ppr.isSetNumPr()) {
            ppr.unsetNumPr();
        }
        CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(STJc.BOTH);
        CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
        if (ind.isSetFirstLineChars()) {
            ind.unsetFirstLineChars();
        }
        ind.setFirstLine(BigInteger.valueOf(480));
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setLine(BigInteger.valueOf(360));
        spacing.setLineRule(STLineSpacingRule.AUTO);
    }

    private static void collectTableParagraphs(List<XWPFTable> tables, List<XWPFParagraph> paragraphs) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    paragraphs.addAll(cell.getParagraphs());
                    collectTableParagraphs(cell.getTables(), paragraphs);
                }
            }
        }
    }

    private static void setTextWithLineBreaks(XWPFRun run, String text) {
        if (text == null || text.isEmpty()) {
            run.setText("", 0);
            return;
        }

        // 分割文本（支持Windows和Unix风格的换行符）
        String[] lines = text.split("\\r?\\n");

        if (lines.length == 1) {
            // 没有换行符，直接设置
            run.setText(text, 0);
        } else {
            // 有换行符，需要分行处理
            for (int i = 0; i < lines.length; i++) {
                if (i == 0) {
                    // 第一行使用setText设置到run的位置0
                    run.setText(lines[i], 0);
                } else {
                    // 后续行先添加换行符，再添加文本
                    run.addBreak();
                    run.setText(lines[i]);
                }
            }
        }
    }


    /**
     * 替换文档中的图片（旧版本，保留兼容性）
     */
    public static void replaceImageInDocument(XWPFDocument document, String placeholder, String imageFileName,
                                              String imageTitle, boolean isCoverImage) throws Exception {
        String imageUrl = null;
        if (imageFileName != null) {
            imageUrl = imageFileName.substring(imageFileName.lastIndexOf('/') + 1);
        }

        // 替换段落中的图片
        for (int i = 0; i < document.getParagraphs().size(); i++) {
            XWPFParagraph paragraph = document.getParagraphs().get(i);
            String paragraphText = paragraph.getText();

            // 先检查段落文本中是否包含占位符
            if (paragraphText.contains(placeholder)) {
                log.info("在普通段落中找到占位符: {}, 段落文本: {}", placeholder, paragraphText);

                // 合并段落中的runs，解决Word分割占位符的问题
                mergeParagraphRuns(paragraph, placeholder);
                try {
                    // 清除占位符段落的内容，但保留段落本身
                    clearParagraph(paragraph);

                    // 设置段落居中对齐
                    paragraph.setAlignment(ParagraphAlignment.CENTER);

                    // 封面图片特殊处理
                    if (isCoverImage) {
                        // 封面图片设置特殊属性
                        paragraph.setSpacingBefore(0);
                        paragraph.setSpacingAfter(0);
                        paragraph.setSpacingBetween(1.0);
                    }

                    if (imageUrl != null) {
                        try (InputStream imageStream = minioClient.getObject(
                                GetObjectArgs.builder()
                                        .bucket(minioConfig.getBucketName())
                                        .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                        .build()
                        )) {
                            XWPFRun run = paragraph.createRun();

                            // 根据是否为封面图片设置不同尺寸
                            int width, height;
                            if (isCoverImage) {
                                // 封面图片使用更大尺寸，并设置为内联图片
                                width = Units.toEMU(400);
                                height = Units.toEMU(300);
                            } else {
//                                width = Units.toEMU(400);
//                                height = Units.toEMU(300);
                                // 11.11 修改 ， 固定大小为 8cm x 6cm
                                width = Units.toEMU(8 * 567);   // 8 cm
                                height = Units.toEMU(6 * 567);  // 6 cm
                            }

                            run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder, width, height);
                        }
                    } else {
                        // 当图片为空时，清除占位符，不显示任何内容
                        // 段落已经被清空，不需要额外操作
                        log.debug("图片为空，已清除占位符: {}", placeholder);
                    }

                    // 如果有标题且不是封面图片，添加标题段落
                    if (imageTitle != null && !imageTitle.isEmpty() && !isCoverImage) {
                        XWPFParagraph titleParagraph = insertParagraphAfter(document, paragraph);
                        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                        titleParagraph.setStyle("12");
                        titleParagraph.setSpacingBefore(100);
                        titleParagraph.setSpacingAfter(200);
                        XWPFRun titleRun = titleParagraph.createRun();
                        titleRun.setText(imageTitle);
                    }

                    return;

                } catch (Exception e) {
                    log.error("替换图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                    // 异常时恢复占位符
                    clearParagraph(paragraph);
                    XWPFRun run = paragraph.createRun();
                    run.setText(placeholder);
                    throw e;
                }
            }
        }

        // 替换表格中的图片
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        String cellParagraphText = paragraph.getText();

                        // 先检查单元格段落文本中是否包含占位符
                        if (cellParagraphText.contains(placeholder)) {
                            log.info("在表格单元格中找到占位符: {}, 单元格文本: {}", placeholder, cellParagraphText);

                            // 合并段落中的runs，解决Word分割占位符的问题
                            mergeParagraphRuns(paragraph, placeholder);

                            try {
                                // 清除占位符
                                clearParagraph(paragraph);

                                // 设置段落居中对齐
                                paragraph.setAlignment(ParagraphAlignment.CENTER);

                                if (imageUrl != null) {
                                    try (InputStream imageStream = minioClient.getObject(
                                            GetObjectArgs.builder()
                                                    .bucket(minioConfig.getBucketName())
                                                    .object(imageUrl.substring(0, 2) + "/" + imageUrl)
                                                    .build()
                                    )) {
                                        XWPFRun run = paragraph.createRun();
                                        // 表格中的图片使用较小尺寸
                                        run.addPicture(imageStream, XWPFDocument.PICTURE_TYPE_JPEG, placeholder,
                                                Units.toEMU(200), Units.toEMU(150));
                                        log.info("成功在表格单元格中插入图片: {}", placeholder);
                                    }
                                } else {
                                    log.info("图片为空，已清除表格中的占位符: {}", placeholder);
                                }

                                // 如果有标题，在图片之后添加标题段落
                                if (imageTitle != null && !imageTitle.isEmpty()) {
                                    XWPFParagraph titleParagraph = cell.addParagraph();
                                    setSingleLineSpacing(titleParagraph);  // 设置单倍行距
                                    titleParagraph.setAlignment(ParagraphAlignment.CENTER);
                                    titleParagraph.setStyle("12");
                                    titleParagraph.setSpacingBefore(100);
                                    titleParagraph.setSpacingAfter(200);
                                    XWPFRun titleRun = titleParagraph.createRun();
                                    titleRun.setText(imageTitle);
                                }

                                return;

                            } catch (Exception e) {
                                log.error("替换表格中图片失败，占位符: {}, 错误: {}", placeholder, e.getMessage(), e);
                                // 异常时恢复占位符
                                clearParagraph(paragraph);
                                XWPFRun run = paragraph.createRun();
                                run.setText(placeholder);
                            }
                        }
                    }
                }
            }
        }

        log.warn("未找到占位符在文档中: {}, 请检查Word模板中是否存在该占位符", placeholder);
    }

    private static XWPFParagraph insertParagraphAfter(XWPFDocument document, XWPFParagraph paragraph) {
        org.apache.xmlbeans.XmlCursor cursor = paragraph.getCTP().newCursor();
        try {
            cursor.toEndToken();
            cursor.toNextToken();
            return document.insertNewParagraph(cursor);
        } finally {
            cursor.dispose();
        }
    }

    /**
     * 合并段落中的所有文本运行（Runs），解决Word将占位符分割的问题
     *
     * @param paragraph   段落对象
     * @param placeholder 要查找的占位符
     * @return 如果找到占位符返回true，否则返回false
     */
    private static boolean mergeParagraphRuns(XWPFParagraph paragraph, String placeholder) {
        String fullText = paragraph.getText();
        if (!fullText.contains(placeholder)) {
            return false;
        }

        // 清除所有现有的runs
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }

        // 创建一个新的run，包含合并后的文本
        XWPFRun newRun = paragraph.createRun();
        newRun.setText(fullText);

        return true;
    }


    /**
     * 设置表格前几行在跨页时重复显示（对应 Word「重复标题行」）。
     */
    public static void setTableHeaderRepeat(XWPFTable table, int headerRowCount) {
        if (table == null || headerRowCount <= 0) {
            return;
        }
        int limit = Math.min(headerRowCount, table.getNumberOfRows());
        for (int i = 0; i < limit; i++) {
            setTableHeaderRepeat(table.getRow(i));
        }
    }

    /**
     * 设置表格标题行在跨页时重复显示
     * <p>
     * 当表格内容跨越多页时，会在每个新页面的顶部自动重复显示标题行，
     * 这对于长表格非常有用，可以让读者始终看到列标题。
     * </p>
     *
     * @param headerRow 需要设置为重复标题的表格行（通常是第一行）
     */
    public static void setTableHeaderRepeat(XWPFTableRow headerRow) {
        if (headerRow == null) {
            return;
        }
        CTTrPr trPr = headerRow.getCtRow().getTrPr();
        if (trPr == null) {
            trPr = headerRow.getCtRow().addNewTrPr();
        }
        if (trPr.sizeOfTblHeaderArray() == 0) {
            trPr.addNewTblHeader();
        }
    }

    /**
     * 设置段落为单倍行距（用于表格单元格）
     * <p>
     * 单倍行距（240 Twips = 1.0倍）可以使表格内容更紧凑，
     * 相比默认的1.15倍行距，可减少约13%的行高。
     * </p>
     *
     * @param paragraph 需要设置行距的段落
     */
    public static void setSingleLineSpacing(XWPFParagraph paragraph) {
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }

        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        // 设置单倍行距：240 Twips = 1.0倍
        spacing.setLine(BigInteger.valueOf(240));
        spacing.setLineRule(STLineSpacingRule.AUTO);
    }

    /**
     * 设置Run的中英文字体（英文Times New Roman，中文宋体），并指定字号
     * <p>
     * 英文和数字使用Times New Roman，中文使用宋体。
     * </p>
     *
     * @param run      需要设置字体的Run对象
     * @param fontSize 字号（half-points，例如21表示10.5pt，20表示10pt）
     */
    public static void setMixedFontFamily(XWPFRun run, int fontSize) {
        setMixedFontFamily(run, fontSize, "宋体");
    }

    /**
     * 英文和数字使用 Times New Roman，中文使用指定字体。
     *
     * @param eastAsiaFont 中文字体，如「宋体」「黑体」
     */
    public static void setMixedFontFamily(XWPFRun run, int fontSize, String eastAsiaFont) {
        CTRPr rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();

        while (rpr.sizeOfRFontsArray() > 0) {
            rpr.removeRFonts(0);
        }
        while (rpr.sizeOfSzArray() > 0) {
            rpr.removeSz(0);
        }
        while (rpr.sizeOfSzCsArray() > 0) {
            rpr.removeSzCs(0);
        }

        CTFonts fonts = rpr.addNewRFonts();
        fonts.setAscii("Times New Roman");
        fonts.setHAnsi("Times New Roman");
        fonts.setEastAsia(eastAsiaFont == null || eastAsiaFont.isEmpty() ? "宋体" : eastAsiaFont);

        rpr.addNewSz().setVal(BigInteger.valueOf(fontSize));
        rpr.addNewSzCs().setVal(BigInteger.valueOf(fontSize));
    }

    private static void clearParagraph(XWPFParagraph paragraph) {
        while (paragraph.getRuns().size() > 0) {
            paragraph.removeRun(0);
        }
    }

    /**
     * 根据占位符查找段落
     */
    public static XWPFParagraph findParagraphByPlaceholder(XWPFDocument document, String placeholder) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            if (text != null && text.contains(placeholder)) {
                return paragraph;
            }
        }
        return null;
    }

    @NotNull
    public static List<Map<String, Object>> getDiseaseImage(Long id) {
        List<Attachment> attachments = attachmentService.getAttachmentList(id).stream().filter(e -> e.getName().startsWith("disease")).toList();

        // 转换为需要的格式
        List<Map<String, Object>> result = new ArrayList<>();
        for (Attachment attachment : attachments) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", attachment.getId());
            map.put("fileName", attachment.getName().split("_")[1]);
            FileMap fileMap = fileMapService.selectFileMapById(attachment.getThumbMinioId() == null ? attachment.getMinioId() : attachment.getThumbMinioId());
            if (fileMap == null) continue;
            String s = fileMap.getNewName();
            map.put("url", minioConfig.getUrl() + "/" + minioConfig.getBucketName() + "/" + s.substring(0, 2) + "/" + s);
            // 根据文件后缀判断是否为图片
            map.put("isImage", isImageFile(attachment.getName()));
            map.put("type", attachment.getType());
            result.add(map);
        }
        return result;
    }

    // 判断文件是否为图片的辅助方法
    public static boolean isImageFile(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return false;
        }
        String extension = fileName.toLowerCase();
        return extension.endsWith(".jpg") ||
                extension.endsWith(".jpeg") ||
                extension.endsWith(".png") ||
                extension.endsWith(".gif") ||
                extension.endsWith(".bmp");
    }

    /**
     * 外观检测表「病害描述」：去掉「面积 S=」里的「面积」，只保留 S=。
     */
    /**
     * 评定表、附表评分：正好为 0 或 100 时不带小数，其余四舍五入保留两位。
     */
    public static String formatAssessmentScore(BigDecimal score) {
        if (score == null) {
            return "/";
        }
        BigDecimal rounded = score.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) == 0 || rounded.compareTo(new BigDecimal("100")) == 0) {
            return rounded.stripTrailingZeros().toPlainString();
        }
        return rounded.toPlainString();
    }

    public static String formatAppearanceDiseaseDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return "/";
        }
        return description.replaceAll("面积\\s*(?=S)", "");
    }

    /** S-1 / S_1 / S－1 写成 S 加下标数字。 */
    public static final Pattern AREA_SUBSCRIPT_LABEL = Pattern.compile("(?i)S[-_－—](\\d+)");

    public static void writeTextWithAreaSubscripts(XWPFParagraph paragraph, String text, int fontHalfPoints) {
        if (paragraph == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = AREA_SUBSCRIPT_LABEL.matcher(text);
        int pos = 0;
        while (matcher.find()) {
            if (matcher.start() > pos) {
                addAreaSubscriptRun(paragraph, text.substring(pos, matcher.start()), fontHalfPoints, false);
            }
            addAreaSubscriptRun(paragraph, "S", fontHalfPoints, false);
            addAreaSubscriptRun(paragraph, matcher.group(1), fontHalfPoints, true);
            pos = matcher.end();
        }
        if (pos < text.length()) {
            addAreaSubscriptRun(paragraph, text.substring(pos), fontHalfPoints, false);
        }
    }

    private static void addAreaSubscriptRun(XWPFParagraph paragraph, String text, int fontHalfPoints, boolean subscript) {
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        setMixedFontFamily(run, fontHalfPoints);
        if (subscript) {
            run.setVerticalAlignment("subscript");
        }
    }

    /**
     * 针对报告的需求， 根据病害裂缝特征 返回 带有特征的病害类型 ， 例如 纵向裂缝。
     */
    public static String reportDiseaseTypeNameIfCrack(Disease disease) {
        if (ObjectUtil.isEmpty(disease.getDiseaseType())) {
            log.error("病害类型对象缺失，数据异常或未封装病害类型");
            return disease.getType();
        }
        // 非裂缝类型
        if (StringUtils.isEmpty(disease.getCrackType()) || !disease.getDiseaseType().getName().contains(ReportConstants.DISEASE_TYPE_NAME_CRACK)) {
            return disease.getDiseaseType().getName();
        }
        return disease.getCrackType() + ReportConstants.DISEASE_TYPE_NAME_CRACK;
    }

    /**
     * 病害照片题注：表格构件名 + 部位 + 表格缺损类型（裂缝用横向/纵向裂缝）。
     */
    public static String diseasePhotoCaption(Disease disease, String componentName) {
        String name = componentName != null ? componentName : "";
        String position = disease != null && disease.getPosition() != null ? disease.getPosition() : "";
        String typeName = "";
        if (disease != null) {
            typeName = reportDiseaseTypeNameIfCrack(disease);
            if (typeName == null) {
                typeName = "";
            } else if (typeName.contains("#")) {
                typeName = typeName.substring(typeName.lastIndexOf("#") + 1);
            }
        }
        return name + position + typeName;
    }

    /**
     * 表格默认小五（9 磅 = 18 half-points）。
     */
    public static final int TABLE_FONT_HALF_POINTS = 18;

    /**
     * 把文档里所有表格的数字/英文改成 Times New Roman，中文保持宋体。
     * Word/WPS 在 hint=eastAsia 时会用宋体画数字，所以数字必须拆成独立 run。
     */
    public static void applyMixedFontsToTables(XWPFDocument document) {
        applyMixedFontsToTables(document, TABLE_FONT_HALF_POINTS);
    }

    public static void applyMixedFontsToTables(XWPFDocument document, int defaultHalfPoints) {
        if (document == null) {
            return;
        }
        for (XWPFTable table : document.getTables()) {
            applyMixedFontsToTable(table, defaultHalfPoints);
        }
    }

    public static void applyMixedFontsToTable(XWPFTable table, int defaultHalfPoints) {
        if (table == null) {
            return;
        }
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    rewriteParagraphMixedFonts(paragraph, defaultHalfPoints, "宋体");
                }
            }
        }
    }

    public static void setParagraphMixedFontText(XWPFParagraph paragraph, String text, int fontHalfPoints) {
        setParagraphMixedFontText(paragraph, text, fontHalfPoints, false, "宋体");
    }

    public static void setParagraphMixedFontText(XWPFParagraph paragraph, String text, int fontHalfPoints,
                                                 boolean bold, String eastAsiaFont) {
        if (paragraph == null) {
            return;
        }
        while (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        appendMixedFontRuns(paragraph, text == null ? "" : text, fontHalfPoints, bold, eastAsiaFont);
    }

    static void rewriteParagraphMixedFonts(XWPFParagraph paragraph, int defaultHalfPoints, String eastAsiaFont) {
        if (paragraph == null || paragraphHasField(paragraph) || paragraphHasDrawing(paragraph)) {
            return;
        }
        String text = paragraph.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean bold = false;
        int fontHalfPoints = defaultHalfPoints;
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs != null) {
            for (XWPFRun run : runs) {
                if (Boolean.TRUE.equals(run.isBold())) {
                    bold = true;
                }
                Integer size = runFontHalfPoints(run);
                if (size != null) {
                    fontHalfPoints = size;
                    break;
                }
            }
        }
        while (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        appendMixedFontRuns(paragraph, text, fontHalfPoints, bold, eastAsiaFont);
    }

    public static void appendMixedFontRuns(XWPFParagraph paragraph, String text, int fontHalfPoints,
                                           boolean bold, String eastAsiaFont) {
        if (paragraph == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        String chineseFont = eastAsiaFont == null || eastAsiaFont.isEmpty() ? "宋体" : eastAsiaFont;
        StringBuilder chunk = new StringBuilder();
        Boolean ascii = null;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                flushMixedFontChunk(paragraph, chunk, ascii, fontHalfPoints, bold, chineseFont);
                ascii = null;
                if (!paragraph.getRuns().isEmpty()) {
                    paragraph.getRuns().get(paragraph.getRuns().size() - 1).addBreak();
                } else {
                    paragraph.createRun().addBreak();
                }
                continue;
            }
            boolean currentAscii = isWesternChar(ch);
            if (ascii != null && currentAscii != ascii) {
                flushMixedFontChunk(paragraph, chunk, ascii, fontHalfPoints, bold, chineseFont);
            }
            ascii = currentAscii;
            chunk.append(ch);
        }
        flushMixedFontChunk(paragraph, chunk, ascii, fontHalfPoints, bold, chineseFont);
    }

    private static void flushMixedFontChunk(XWPFParagraph paragraph, StringBuilder chunk, Boolean ascii,
                                            int fontHalfPoints, boolean bold, String chineseFont) {
        if (chunk.length() == 0) {
            return;
        }
        XWPFRun run = paragraph.createRun();
        run.setText(chunk.toString());
        run.setBold(bold);
        if (Boolean.TRUE.equals(ascii)) {
            applyWesternFont(run, fontHalfPoints);
        } else {
            setMixedFontFamily(run, fontHalfPoints, chineseFont);
        }
        chunk.setLength(0);
    }

    /**
     * 现状照题注。有备注就用备注；没有备注时用当前章节的直接子节点名称，
     * 例如桥面系下的桥面铺装、伸缩缝装置、人行道。末尾统一加「现状」。
     */
    public static String statusPhotoCaption(FileMap fileMap, BiObject sectionNode, Map<Long, BiObject> nodeMap) {
        String remark = stripStatusSuffix(fileMap == null ? null : fileMap.getAttachmentRemark());
        String body = remark.isEmpty() ? statusSubsetName(fileMap, sectionNode, nodeMap) : remark;
        if (body.isEmpty()) {
            body = statusSubsetName(fileMap, sectionNode, nodeMap);
        }
        return body.endsWith("现状") ? body : body + "现状";
    }

    /** 给已有题注补上「现状」，并去掉末尾的「现状照片」。 */
    public static String finishStatusCaption(String text) {
        String body = stripStatusSuffix(text);
        return body.endsWith("现状") ? body : body + "现状";
    }

    private static String statusSubsetName(FileMap fileMap, BiObject sectionNode, Map<Long, BiObject> nodeMap) {
        BiObject captionNode = statusPhotoCaptionNode(
                fileMap != null ? fileMap.getSubjectId() : null, sectionNode, nodeMap);
        if (captionNode != null && StringUtils.isNotEmpty(captionNode.getName())) {
            return captionNode.getName().trim();
        }
        return sectionNode != null && sectionNode.getName() != null ? sectionNode.getName().trim() : "";
    }

    /** 去掉备注末尾已经写过的「现状」或「现状照片」，避免再拼一次。 */
    static String stripStatusSuffix(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.endsWith("现状照片")) {
            return trimmed.substring(0, trimmed.length() - "现状照片".length()).trim();
        }
        if (trimmed.endsWith("现状")) {
            return trimmed.substring(0, trimmed.length() - "现状".length()).trim();
        }
        return trimmed;
    }

    public static int statusPhotoCaptionOrder(FileMap fileMap, BiObject sectionNode, Map<Long, BiObject> nodeMap) {
        BiObject captionNode = statusPhotoCaptionNode(
                fileMap != null ? fileMap.getSubjectId() : null, sectionNode, nodeMap);
        if (captionNode == null) {
            return Integer.MAX_VALUE;
        }
        if (sectionNode != null && sectionNode.getId() != null && sectionNode.getId().equals(captionNode.getId())) {
            return -1;
        }
        return captionNode.getOrderNum() != null ? captionNode.getOrderNum() : Integer.MAX_VALUE;
    }

    /** 从照片挂接节点向上走到当前章节的直接子节点，例如桥面系 → 排水系统。 */
    static BiObject statusPhotoCaptionNode(Long subjectId, BiObject sectionNode, Map<Long, BiObject> nodeMap) {
        if (sectionNode == null) {
            return null;
        }
        if (subjectId == null || nodeMap == null || nodeMap.isEmpty()) {
            return sectionNode;
        }
        BiObject current = nodeMap.get(subjectId);
        if (current == null) {
            return sectionNode;
        }
        Long sectionId = sectionNode.getId();
        if (sectionId != null && sectionId.equals(current.getId())) {
            return sectionNode;
        }
        int guard = 0;
        while (current != null && current.getParentId() != null && guard++ < 32) {
            if (sectionId != null && sectionId.equals(current.getParentId())) {
                return current;
            }
            current = nodeMap.get(current.getParentId());
            if (current != null && sectionId != null && sectionId.equals(current.getId())) {
                return sectionNode;
            }
        }
        return current != null ? current : sectionNode;
    }

    /**
     * 数字、英文必须四个字体槽都写 Times New Roman，否则 WPS/Word 在 eastAsia hint 下仍用宋体画数字。
     */
    private static void applyWesternFont(XWPFRun run, int fontHalfPoints) {
        CTRPr rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        while (rpr.sizeOfRFontsArray() > 0) {
            rpr.removeRFonts(0);
        }
        while (rpr.sizeOfSzArray() > 0) {
            rpr.removeSz(0);
        }
        while (rpr.sizeOfSzCsArray() > 0) {
            rpr.removeSzCs(0);
        }
        CTFonts fonts = rpr.addNewRFonts();
        fonts.setAscii("Times New Roman");
        fonts.setHAnsi("Times New Roman");
        fonts.setCs("Times New Roman");
        fonts.setEastAsia("Times New Roman");
        fonts.setHint(STHint.DEFAULT);
        rpr.addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
        rpr.addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
    }

    static boolean isWesternChar(char ch) {
        return ch <= 0x7F;
    }

    private static boolean paragraphHasDrawing(XWPFParagraph paragraph) {
        if (paragraph.getCTP() == null) {
            return false;
        }
        for (CTR run : paragraph.getCTP().getRArray()) {
            if (run.sizeOfDrawingArray() > 0 || run.sizeOfPictArray() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean paragraphHasField(XWPFParagraph paragraph) {
        if (paragraph.getCTP() == null) {
            return false;
        }
        String xml = paragraph.getCTP().xmlText();
        return xml.contains("w:fldChar") || xml.contains("w:instrText");
    }

    private static Integer runFontHalfPoints(XWPFRun run) {
        if (run == null || !run.getCTR().isSetRPr()) {
            return null;
        }
        CTRPr rpr = run.getCTR().getRPr();
        if (rpr.sizeOfSzArray() > 0 && rpr.getSzArray(0).getVal() != null) {
            Object val = rpr.getSzArray(0).getVal();
            if (val instanceof Number) {
                return ((Number) val).intValue();
            }
        }
        Double points = run.getFontSizeAsDouble();
        if (points != null && points > 0) {
            return (int) Math.round(points * 2);
        }
        return null;
    }
}
