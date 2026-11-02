package edu.whut.cs.bi.biz.utils;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Word域工具类，用于生成表格和图片的自动编号域
 *
 * @author wanzheng
 */
public class WordFieldUtils {

    /**
     * 表/图序号和书签 ID 按生成线程隔离。
     * 报告并发时若共用一份静态计数器，一份报告 reset 会把另一份的书签起止 ID 对乱。
     */
    private static final ThreadLocal<Counters> COUNTERS = ThreadLocal.withInitial(Counters::new);

    private static final class Counters {
        private final AtomicInteger tableSequence = new AtomicInteger(1);
        private final AtomicInteger figureSequence = new AtomicInteger(1);
        private final AtomicInteger bookmarkCounter = new AtomicInteger(1);
    }

    private static Counters counters() {
        return COUNTERS.get();
    }

    /**
     * 重置当前线程计数器（用于新文档）
     */
    public static void resetCounters() {
        Counters counters = counters();
        counters.tableSequence.set(1);
        counters.figureSequence.set(1);
        counters.bookmarkCounter.set(1);
    }

    /**
     * 释放当前线程计数器，避免线程池复用残留。
     */
    public static void removeCounters() {
        COUNTERS.remove();
    }

    /**
     * 创建表格标题域
     *
     * @param document      Word文档
     * @param titleText     标题文本（如"检测结果表"）
     * @param cursor        插入位置的游标，如果为null则追加到文档末尾
     * @param chapterNumber 章节号，如果需要按章节编号则提供，否则传null
     * @return 生成的书签名，用于后续引用
     */
    public static String createTableCaption(XWPFDocument document, String titleText, XmlCursor cursor, Integer chapterNumber) {
        XWPFParagraph paragraph;
        if (cursor != null) {
            paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            paragraph = document.createParagraph();
        }

        paragraph.setAlignment(ParagraphAlignment.CENTER);

        // 设置段落样式
        try {
            paragraph.setStyle("12");
        } catch (Exception e) {
            // 如果样式不存在，手动设置格式
            CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr == null) {
                ppr = paragraph.getCTP().addNewPPr();
            }

            CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
            spacing.setAfter(BigInteger.valueOf(120));
            spacing.setBefore(BigInteger.valueOf(120));
            spacing.setLine(BigInteger.valueOf(240));
            spacing.setLineRule(STLineSpacingRule.AUTO);

            CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
            jc.setVal(STJc.CENTER);
        }

        // 生成唯一的书签名
        String bookmarkName = "Table_" + counters().bookmarkCounter.getAndIncrement();

        // 添加静态前缀"表"字
        XWPFRun prefixRun = paragraph.createRun();
        prefixRun.setText("表");
        prefixRun.setFontFamily("黑体");
        prefixRun.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(22));
        prefixRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(22));

        // 创建表格序号域（使用平铺多域显示"3.1"格式）
        if (chapterNumber != null) {
            // 按章节编号
            createChapterSequenceField(paragraph, "Table", bookmarkName, chapterNumber);
        } else {
            // 全文档编号
            createSequenceField(paragraph, "Table", bookmarkName);
        }

        // 添加标题文本
        XWPFRun titleRun = paragraph.createRun();
        titleRun.setText(" " + titleText);
        titleRun.setFontFamily("黑体");
        titleRun.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(22));
        titleRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(22));

        return bookmarkName;
    }

    /**
     * 创建表格标题域（兼容性方法，无章节号）
     *
     * @param document  Word文档
     * @param titleText 标题文本（如"检测结果表"）
     * @param cursor    插入位置的游标，如果为null则追加到文档末尾
     * @return 生成的书签名，用于后续引用
     */
    public static String createTableCaption(XWPFDocument document, String titleText, XmlCursor cursor) {
        return createTableCaption(document, titleText, cursor, null);
    }

    /**
     * 创建表格标题，支持传入计数器
     *
     * @param document      Word文档
     * @param titleText     标题文本
     * @param cursor        插入位置的游标，如果为null则追加到文档末尾
     * @param chapterNumber 章节号
     * @param tableCounter  表格计数器
     * @return 返回表格的书签名，用于后续引用
     */
    public static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor, Integer chapterNumber, AtomicInteger tableCounter) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter, 22, 240, false);
    }

    /**
     * 创建表格标题，支持自定义字号与行距。
     *
     * @param fontHalfPoints 字号（half-points，五号为 21）
     * @param lineTwips      行距（twips，1.5 倍为 360）
     */
    public static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                       Integer chapterNumber, AtomicInteger tableCounter,
                                                       int fontHalfPoints, long lineTwips) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, true, true, 120);
    }

    /**
     * 创建表格标题，并指定是否加粗、段前段后间距。
     *
     * @param bold             是否加粗
     * @param beforeAfterTwips 段前段后间距（twips，0 表示不要段前段后）
     */
    public static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                       Integer chapterNumber, AtomicInteger tableCounter,
                                                       int fontHalfPoints, long lineTwips,
                                                       boolean bold, long beforeAfterTwips) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, bold, beforeAfterTwips, "13");
    }

    /**
     * 创建表格标题，并指定段落样式。单桥评定表、对比表用 12，避开 WPS 写在样式 13 上的蓝边。
     */
    public static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                       Integer chapterNumber, AtomicInteger tableCounter,
                                                       int fontHalfPoints, long lineTwips,
                                                       boolean bold, long beforeAfterTwips, String styleId) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, true, bold, beforeAfterTwips, beforeAfterTwips, styleId);
    }

    /**
     * 创建表格标题，段前段后可分别指定。图片题注：段前 5 磅、段后 10 磅、单倍行距。
     */
    public static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                       Integer chapterNumber, AtomicInteger tableCounter,
                                                       int fontHalfPoints, long lineTwips,
                                                       boolean bold, long beforeTwips, long afterTwips, String styleId) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, true, bold, beforeTwips, afterTwips, styleId);
    }

    private static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                        Integer chapterNumber, AtomicInteger tableCounter,
                                                        int fontHalfPoints, long lineTwips, boolean applyCustomStyle) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, applyCustomStyle, true, 120);
    }

    private static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                        Integer chapterNumber, AtomicInteger tableCounter,
                                                        int fontHalfPoints, long lineTwips, boolean applyCustomStyle,
                                                        boolean bold, long beforeAfterTwips) {
        return createTableCaptionWithCounter(document, titleText, cursor, chapterNumber, tableCounter,
                fontHalfPoints, lineTwips, applyCustomStyle, bold, beforeAfterTwips, beforeAfterTwips, "13");
    }

    private static String createTableCaptionWithCounter(XWPFDocument document, String titleText, XmlCursor cursor,
                                                        Integer chapterNumber, AtomicInteger tableCounter,
                                                        int fontHalfPoints, long lineTwips, boolean applyCustomStyle,
                                                        boolean bold, long beforeTwips, long afterTwips, String styleId) {
        XWPFParagraph paragraph;
        if (cursor != null) {
            paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            paragraph = document.createParagraph();
        }

        if (applyCustomStyle) {
            applyCaptionParagraphStyle(paragraph, lineTwips, beforeTwips, afterTwips, styleId);
        } else {
            paragraph.setAlignment(ParagraphAlignment.CENTER);
            try {
                paragraph.setStyle("12");
            } catch (Exception e) {
                applyCaptionParagraphStyle(paragraph, lineTwips, beforeTwips, afterTwips, "13");
            }
        }

        // 使用传入的计数器生成书签名
        String bookmarkName = "Table_Chapter" + chapterNumber + "_" + tableCounter.getAndIncrement();

        // 添加静态前缀"表"字
        XWPFRun prefixRun = paragraph.createRun();
        prefixRun.setText("表");
        if (applyCustomStyle) {
            applyHeitiRun(prefixRun, fontHalfPoints, bold);
        } else {
            prefixRun.setFontFamily("黑体");
            prefixRun.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
            prefixRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
        }

        // 创建书签开始 - 只包围编号部分
        CTBookmark bookmarkStart = paragraph.getCTP().addNewBookmarkStart();
        bookmarkStart.setName(bookmarkName);
        bookmarkStart.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));

        // 创建表格序号域（使用平铺多域显示"3.1"格式）
        if (chapterNumber != null) {
            // 按章节编号 - 不创建内部书签
            createChapterSequenceFieldWithoutBookmark(paragraph, "表", chapterNumber);
        } else {
            // 全文档编号 - 不创建内部书签
            createSequenceFieldWithoutBookmark(paragraph, "表");
        }

        // 创建书签结束 - 只包围编号部分
        CTMarkupRange bookmarkEnd = paragraph.getCTP().addNewBookmarkEnd();
        bookmarkEnd.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));
        counters().bookmarkCounter.incrementAndGet();

        // 添加标题文本
        XWPFRun titleRun = paragraph.createRun();
        titleRun.setText(" " + titleText);
        if (applyCustomStyle) {
            applyHeitiRun(titleRun, fontHalfPoints, bold);
        } else {
            titleRun.setFontFamily("黑体");
            titleRun.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
            titleRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
        }

        return bookmarkName;
    }

    /**
     * 表格标题：黑体、居中，并指定字号与行距。
     */
    public static void applyTableCaptionStyle(XWPFParagraph paragraph, long lineTwips) {
        applyTableCaptionStyle(paragraph, lineTwips, 120);
    }

    /**
     * 表格标题：黑体、居中，并指定行距和段前段后。
     *
     * @param beforeAfterTwips 段前段后间距（twips，0 表示不要段前段后）
     */
    public static void applyTableCaptionStyle(XWPFParagraph paragraph, long lineTwips, long beforeAfterTwips) {
        applyCaptionParagraphStyle(paragraph, lineTwips, beforeAfterTwips, beforeAfterTwips, "13");
    }

    private static void applyCaptionParagraphStyle(XWPFParagraph paragraph, long lineTwips, long beforeTwips,
                                                   long afterTwips, String styleId) {
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        try {
            paragraph.setStyle(styleId);
        } catch (Exception ignored) {
            // 样式不存在时仍按手动格式输出
        }

        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr == null) {
            ppr = paragraph.getCTP().addNewPPr();
        }
        if (ppr.isSetInd()) {
            ppr.unsetInd();
        }
        if (ppr.isSetOutlineLvl()) {
            ppr.unsetOutlineLvl();
        }
        CTSpacing spacing = ppr.isSetSpacing() ? ppr.getSpacing() : ppr.addNewSpacing();
        spacing.setAfter(BigInteger.valueOf(afterTwips));
        spacing.setBefore(BigInteger.valueOf(beforeTwips));
        if (spacing.isSetBeforeLines()) {
            spacing.unsetBeforeLines();
        }
        if (spacing.isSetAfterLines()) {
            spacing.unsetAfterLines();
        }
        spacing.setLine(BigInteger.valueOf(lineTwips));
        spacing.setLineRule(STLineSpacingRule.AUTO);
        CTJc jc = ppr.isSetJc() ? ppr.getJc() : ppr.addNewJc();
        jc.setVal(STJc.CENTER);
    }

    private static void applyHeitiRun(XWPFRun run, int fontHalfPoints, boolean bold) {
        run.setBold(bold);
        run.setFontFamily("黑体");
        CTR ctr = run.getCTR();
        CTRPr rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
        CTFonts fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
        fonts.setAscii("黑体");
        fonts.setHAnsi("黑体");
        fonts.setEastAsia("黑体");
        if (rpr.sizeOfSzArray() > 0) {
            rpr.getSzArray(0).setVal(BigInteger.valueOf(fontHalfPoints));
        } else {
            rpr.addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
        }
        if (rpr.sizeOfSzCsArray() > 0) {
            rpr.getSzCsArray(0).setVal(BigInteger.valueOf(fontHalfPoints));
        } else {
            rpr.addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
        }
        if (bold) {
            if (rpr.sizeOfBArray() > 0) {
                rpr.getBArray(0).setVal(true);
            } else {
                rpr.addNewB().setVal(true);
            }
        } else {
            while (rpr.sizeOfBArray() > 0) {
                rpr.removeB(0);
            }
            while (rpr.sizeOfBCsArray() > 0) {
                rpr.removeBCs(0);
            }
        }
    }

    /**
     * 创建图片标题域
     *
     * @param document      Word文档
     * @param titleText     标题文本（如"桥型布置图"）
     * @param cursor        插入位置的游标，如果为null则追加到文档末尾
     * @param chapterNumber 章节号，如果需要按章节编号则提供，否则传null
     * @return 生成的书签名，用于后续引用
     */
    public static String createFigureCaption(XWPFDocument document, String titleText, XmlCursor cursor, Integer chapterNumber) {
        return createFigureCaptionWithBookmark(document, titleText, cursor, chapterNumber, null);
    }

    /**
     * 创建图片标题域，指定书签名
     *
     * @param document      Word文档
     * @param titleText     标题文本（如"桥型布置图"）
     * @param cursor        插入位置的游标，如果为null则追加到文档末尾
     * @param chapterNumber 章节号，如果需要按章节编号则提供，否则传null
     * @param bookmarkName  指定的书签名，如果为null则自动生成
     * @return 生成的书签名，用于后续引用
     */
    public static String createFigureCaptionWithBookmark(XWPFDocument document, String titleText, XmlCursor cursor, Integer chapterNumber, String bookmarkName) {
        XWPFParagraph paragraph;
        if (cursor != null) {
            paragraph = document.insertNewParagraph(cursor);
            cursor.toNextToken();
        } else {
            paragraph = document.createParagraph();
        }

        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setStyle("12");
        paragraph.setSpacingBefore(100);
        paragraph.setSpacingAfter(200);

        XWPFRun run = paragraph.createRun();

        // 添加"图"字
        run.setText("图 ");
        run.setFontFamily("黑体");
        run.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(21));
        run.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(21));

        // 生成或使用指定的书签名
        if (bookmarkName == null) {
            bookmarkName = "Figure_" + counters().bookmarkCounter.getAndIncrement();
        }

        // 创建图片序号域。SEQ 标识用「图」，与 Word 中文题注一致，避免英文 Figure 和正文引用对不上。
        if (chapterNumber != null) {
            createChapterSequenceField(paragraph, "图", bookmarkName, chapterNumber);
        } else {
            createSequenceField(paragraph, "图", bookmarkName);
        }

        // 添加标题文本
        if (titleText != null && !titleText.isEmpty()) {
            XWPFRun titleRun = paragraph.createRun();
            titleRun.setText(" " + titleText);
            titleRun.setFontFamily("黑体");
            titleRun.getCTR().addNewRPr().addNewSz().setVal(BigInteger.valueOf(21));
            titleRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(21));
        }

        return bookmarkName;
    }

    /**
     * 在现有段落中创建图片标题域（不创建新段落）
     *
     * @param paragraph     现有的段落
     * @param titleText     标题文本
     * @param chapterNumber 章节号
     * @param bookmarkName  指定的书签名，如果为null则自动生成
     * @return 生成的书签名
     */
    public static String createFigureCaptionInParagraph(XWPFParagraph paragraph, String titleText, Integer chapterNumber, String bookmarkName) {
        // 与模板图片题注一致：样式 12、居中、段前 5 磅、段后 10 磅、单倍行距
        return createFigureCaptionInParagraph(paragraph, titleText, chapterNumber, bookmarkName, 240, 100, 200, "12");
    }

    /**
     * 在现有段落中创建图片标题域，并指定行距与段前段后。
     *
     * @param lineTwips        行距（twips，单倍为 240，1.5 倍为 360）
     * @param beforeAfterTwips 段前段后间距（twips，0 表示不要段前段后）
     */
    public static String createFigureCaptionInParagraph(XWPFParagraph paragraph, String titleText, Integer chapterNumber,
                                                       String bookmarkName, long lineTwips, long beforeAfterTwips) {
        return createFigureCaptionInParagraph(paragraph, titleText, chapterNumber, bookmarkName,
                lineTwips, beforeAfterTwips, beforeAfterTwips, "13");
    }

    private static String createFigureCaptionInParagraph(XWPFParagraph paragraph, String titleText, Integer chapterNumber,
                                                        String bookmarkName, long lineTwips, long beforeTwips,
                                                        long afterTwips, String styleId) {
        applyCaptionParagraphStyle(paragraph, lineTwips, beforeTwips, afterTwips, styleId);

        // 生成或使用指定的书签名
        if (bookmarkName == null) {
            bookmarkName = "Figure_" + counters().bookmarkCounter.getAndIncrement();
        }

        // 添加静态前缀"图"字：五号黑体，不加粗
        XWPFRun prefixRun = paragraph.createRun();
        prefixRun.setText("图");
        applyHeitiRun(prefixRun, 21, false);

        // 创建书签开始 - 只包围编号部分
        CTBookmark bookmarkStart = paragraph.getCTP().addNewBookmarkStart();
        bookmarkStart.setName(bookmarkName);
        bookmarkStart.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));

        // 创建图片序号域（Word 题注：STYLEREF 1 \s + 连接符 + SEQ，显示为图x-x）
        if (chapterNumber != null) {
            createChapterSequenceFieldWithoutBookmark(paragraph, "图", chapterNumber);
        } else {
            createSequenceFieldWithoutBookmark(paragraph, "图");
        }

        // 创建书签结束 - 只包围编号部分
        CTMarkupRange bookmarkEnd = paragraph.getCTP().addNewBookmarkEnd();
        bookmarkEnd.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));
        counters().bookmarkCounter.incrementAndGet();

        // 添加标题文本
        if (titleText != null && !titleText.isEmpty()) {
            XWPFRun titleRun = paragraph.createRun();
            titleRun.setText(" " + titleText);
            applyHeitiRun(titleRun, 21, false);
        }

        return bookmarkName;
    }

    /**
     * 创建表格引用域
     *
     * @param paragraph    要插入引用的段落
     * @param bookmarkName 表格的书签名
     * @param prefixText   前缀文本（如"具体检测结果见下表"）
     * @param suffixText   后缀文本（如"："）
     */
    public static void createTableReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText) {
        XWPFRun prefixRun = paragraph.createRun();
        prefixRun.setText(prefixText);

        // 创建引用域
        createReferenceField(paragraph, bookmarkName);

        if (suffixText != null && !suffixText.isEmpty()) {
            XWPFRun suffixRun = paragraph.createRun();
            suffixRun.setText(suffixText);
        }
    }

    /**
     * 创建章节格式的表格引用域（使用REF域引用整个表格标题）
     *
     * @param paragraph    要插入引用的段落
     * @param bookmarkName 表格的书签名，格式如"Table_Chapter3_1"
     * @param prefixText   前缀文本（如"具体检测结果见下表"）
     * @param suffixText   后缀文本（如"："）
     */
    public static void createChapterTableReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText) {
        XWPFRun prefixRun = paragraph.createRun();
        prefixRun.setText(prefixText);
        // 设置前缀字体格式
        CTRPr prefixRPr = prefixRun.getCTR().addNewRPr();
        prefixRPr.addNewRFonts().setAscii("宋体");
        prefixRPr.addNewRFonts().setEastAsia("宋体");
        prefixRPr.addNewSz().setVal(BigInteger.valueOf(24)); // 12号字体
        prefixRPr.addNewSzCs().setVal(BigInteger.valueOf(24));

        // 引用表号（如 3.1）：Times New Roman 小四，CHARFORMAT 避免域更新时带回题注黑体
        createReferenceField(paragraph, bookmarkName, "Times New Roman", "Times New Roman", 24, true);

        if (suffixText != null && !suffixText.isEmpty()) {
            XWPFRun suffixRun = paragraph.createRun();
            suffixRun.setText(suffixText);
            // 设置后缀字体格式
            CTRPr suffixRPr = suffixRun.getCTR().addNewRPr();
            suffixRPr.addNewRFonts().setAscii("宋体");
            suffixRPr.addNewRFonts().setEastAsia("宋体");
            suffixRPr.addNewSz().setVal(BigInteger.valueOf(24)); // 12号字体
            suffixRPr.addNewSzCs().setVal(BigInteger.valueOf(24));
        }
    }

    /**
     * 创建图片引用域
     *
     * @param paragraph    要插入引用的段落
     * @param bookmarkName 图片的书签名
     * @param prefixText   前缀文本（如"如"）
     * @param suffixText   后缀文本（如"所示"）
     */
    public static void createFigureReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText) {
        if (prefixText != null && !prefixText.isEmpty()) {
            XWPFRun prefixRun = paragraph.createRun();
            prefixRun.setText(prefixText);
        }

        // 创建引用域
        createReferenceField(paragraph, bookmarkName);

        if (suffixText != null && !suffixText.isEmpty()) {
            XWPFRun suffixRun = paragraph.createRun();
            suffixRun.setText(suffixText);
        }
    }

    /**
     * 创建章节格式的图片引用域（使用REF域引用整个图片标题）
     *
     * @param paragraph    段落
     * @param bookmarkName 书签名称，格式如"Figure_Chapter3_1"
     * @param prefixText   前缀文本（如"图"）
     * @param suffixText   后缀文本
     */
    public static void createChapterFigureReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText) {
        if (prefixText != null && !prefixText.isEmpty()) {
            XWPFRun prefixRun = paragraph.createRun();
            prefixRun.setText(prefixText);
            // 设置前缀字体格式
            CTRPr prefixRPr = prefixRun.getCTR().addNewRPr();
            prefixRPr.addNewRFonts().setAscii("宋体");
            prefixRPr.addNewRFonts().setEastAsia("宋体");
            prefixRPr.addNewSz().setVal(BigInteger.valueOf(21));
            prefixRPr.addNewSzCs().setVal(BigInteger.valueOf(21));
        }

        // 引用图号（如 3.1）：Times New Roman 五号，CHARFORMAT 避免域更新时带回题注字体
        createReferenceField(paragraph, bookmarkName, "Times New Roman", "Times New Roman", 21, true);

        if (suffixText != null && !suffixText.isEmpty()) {
            XWPFRun suffixRun = paragraph.createRun();
            suffixRun.setText(suffixText);
            // 设置后缀字体格式
            CTRPr suffixRPr = suffixRun.getCTR().addNewRPr();
            suffixRPr.addNewRFonts().setAscii("宋体");
            suffixRPr.addNewRFonts().setEastAsia("宋体");
            suffixRPr.addNewSz().setVal(BigInteger.valueOf(21));
            suffixRPr.addNewSzCs().setVal(BigInteger.valueOf(21));
        }
    }

    /**
     * 单桥病害汇总表照片列：不超过 3 张逐个列出，超过 3 张只写首尾并用 ～ 连接。
     * refs 元素为「书签名|描述」。
     */
    public static void createChapterFigureReferenceRange(XWPFParagraph paragraph, List<String> refs) {
        if (paragraph == null || refs == null || refs.isEmpty()) {
            return;
        }
        List<String> bookmarks = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            bookmarks.add(ref.split("\\|", 2)[0]);
        }
        if (bookmarks.isEmpty()) {
            return;
        }
        if (bookmarks.size() > 3) {
            createChapterFigureReference(paragraph, bookmarks.get(0), "图", "");
            addFigureReferenceSeparator(paragraph, "～");
            createChapterFigureReference(paragraph, bookmarks.get(bookmarks.size() - 1), "图", "");
            return;
        }
        for (int i = 0; i < bookmarks.size(); i++) {
            if (i > 0) {
                addFigureReferenceSeparator(paragraph, ",");
            }
            createChapterFigureReference(paragraph, bookmarks.get(i), "图", "");
        }
    }

    private static void addFigureReferenceSeparator(XWPFParagraph paragraph, String text) {
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        CTRPr rPr = run.getCTR().addNewRPr();
        rPr.addNewRFonts().setAscii("宋体");
        rPr.addNewRFonts().setEastAsia("宋体");
        rPr.addNewSz().setVal(BigInteger.valueOf(21));
        rPr.addNewSzCs().setVal(BigInteger.valueOf(21));
    }

    /**
     * 创建序列域（SEQ字段）
     *
     * @param paragraph    段落
     * @param seqName      序列名称（如"Table"或"Figure"）
     * @param bookmarkName 书签名称
     */
    private static void createSequenceField(XWPFParagraph paragraph, String seqName, String bookmarkName) {
        CTR ctr = paragraph.getCTP().addNewR();

        // 创建书签开始
        CTBookmark bookmarkStart = paragraph.getCTP().addNewBookmarkStart();
        bookmarkStart.setName(bookmarkName);
        bookmarkStart.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));

        // 创建域开始
        CTFldChar fieldBegin = ctr.addNewFldChar();
        fieldBegin.setFldCharType(STFldCharType.BEGIN);

        // 创建域代码
        CTR instrCtr = paragraph.getCTP().addNewR();
        writeFieldInstruction(instrCtr, "SEQ " + seqName + " \\* ARABIC");

        // 创建域分隔符
        CTR sepCtr = paragraph.getCTP().addNewR();
        CTFldChar fieldSep = sepCtr.addNewFldChar();
        fieldSep.setFldCharType(STFldCharType.SEPARATE);

        // 创建域结果（显示文本）
        CTR resultCtr = paragraph.getCTP().addNewR();
        // 设置域结果的字体格式，与前缀保持一致
        CTRPr resultRPr = resultCtr.addNewRPr();
        if ("Table".equals(seqName)) {
            // 表格字体：黑体，22pt
            resultRPr.addNewRFonts().setAscii("黑体");
            resultRPr.addNewRFonts().setEastAsia("黑体");
            resultRPr.addNewSz().setVal(BigInteger.valueOf(22));
            resultRPr.addNewSzCs().setVal(BigInteger.valueOf(22));
            // 设置初始显示值
            CTText resultText = resultCtr.addNewT();
            resultText.setStringValue(String.valueOf(counters().tableSequence.getAndIncrement()));
        } else if ("Figure".equals(seqName) || "图".equals(seqName)) {
            // 图片字体：黑体，21pt
            resultRPr.addNewRFonts().setAscii("黑体");
            resultRPr.addNewRFonts().setEastAsia("黑体");
            resultRPr.addNewSz().setVal(BigInteger.valueOf(21));
            resultRPr.addNewSzCs().setVal(BigInteger.valueOf(21));
            // 设置初始显示值
            CTText resultText = resultCtr.addNewT();
            resultText.setStringValue(String.valueOf(counters().figureSequence.getAndIncrement()));
        }

        // 创建域结束
        CTR endCtr = paragraph.getCTP().addNewR();
        CTFldChar fieldEnd = endCtr.addNewFldChar();
        fieldEnd.setFldCharType(STFldCharType.END);

        // 创建书签结束
        CTMarkupRange bookmarkEnd = paragraph.getCTP().addNewBookmarkEnd();
        bookmarkEnd.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));
    }

    /**
     * 创建章节序列域（按章节重新编号）
     *
     * @param paragraph     段落
     * @param seqName       序列名称
     * @param bookmarkName  书签名称
     * @param chapterNumber 章节号
     */
    private static void createChapterSequenceField(XWPFParagraph paragraph, String seqName, String bookmarkName, Integer chapterNumber) {
        // 使用平铺多域方案：STYLEREF + 连接符 + SEQ
        // 1. 创建STYLEREF域
        createStyleRefField(paragraph);

        appendCaptionConnector(paragraph, seqName);

        // 3. 创建SEQ域
        createSimpleSEQField(paragraph, seqName, bookmarkName);
    }

    /**
     * 创建完整格式的图片引用（如"图3-1"）
     *
     * @param paragraph      段落
     * @param bookmarkName   书签名称，格式如"Figure_Chapter3_1"
     * @param prefixText     前缀文本
     * @param suffixText     后缀文本
     * @param chapterNumber  章节号
     * @param sequenceNumber 序号
     */
    public static void createFullFormatFigureReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText, Integer chapterNumber, Integer sequenceNumber) {
        if (prefixText != null && !prefixText.isEmpty()) {
            XWPFRun prefixRun = paragraph.createRun();
            prefixRun.setText(prefixText);
            // 设置字体格式
            prefixRun.getCTR().addNewRPr().addNewRFonts().setEastAsia("宋体");
            prefixRun.getCTR().getRPr().addNewSz().setVal(BigInteger.valueOf(21));
            prefixRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(21));
        }

        // 生成完整格式显示文本
        String displayText;
        if (chapterNumber != null && sequenceNumber != null) {
            displayText = chapterNumber + "-" + sequenceNumber;
        } else {
            // 回退到解析书签名
            displayText = parseBookmarkToDisplayFormat(bookmarkName);
        }
        XWPFRun displayRun = paragraph.createRun();
        displayRun.setText(displayText);
        // 设置字体格式
        displayRun.getCTR().addNewRPr().addNewRFonts().setEastAsia("宋体");
        displayRun.getCTR().getRPr().addNewSz().setVal(BigInteger.valueOf(21));
        displayRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(21));

        if (suffixText != null && !suffixText.isEmpty()) {
            XWPFRun suffixRun = paragraph.createRun();
            suffixRun.setText(suffixText);
            // 设置字体格式
            suffixRun.getCTR().addNewRPr().addNewRFonts().setEastAsia("宋体");
            suffixRun.getCTR().getRPr().addNewSz().setVal(BigInteger.valueOf(21));
            suffixRun.getCTR().getRPr().addNewSzCs().setVal(BigInteger.valueOf(21));
        }
    }

    /**
     * 创建完整格式的图片引用（如"图3-1"）- 重载方法，向后兼容
     *
     * @param paragraph    段落
     * @param bookmarkName 书签名称，格式如"Figure_Chapter3_1"
     * @param prefixText   前缀文本
     * @param suffixText   后缀文本
     */
    public static void createFullFormatFigureReference(XWPFParagraph paragraph, String bookmarkName, String prefixText, String suffixText) {
        createFullFormatFigureReference(paragraph, bookmarkName, prefixText, suffixText, null, null);
    }

    /**
     * 从书签名解析出显示格式
     *
     * @param bookmarkName 书签名，如"Figure_Chapter3_1"或"Table_1"
     * @return 显示格式，如"3-1"或"3.1"
     */
    private static String parseBookmarkToDisplayFormat(String bookmarkName) {
        if (bookmarkName.startsWith("Figure_Chapter")) {
            // 解析图片书签：Figure_Chapter3_1 -> 3-1
            String[] parts = bookmarkName.split("_");
            if (parts.length >= 3) {
                String chapterPart = parts[1].replace("Chapter", ""); // "3"
                String sequencePart = parts[2]; // "1"
                // 需要将全局序号转换为章节内序号
                // 假设每章从1开始编号，这里简化处理
                int globalSeq = Integer.parseInt(sequencePart);
                int chapterSeq = ((globalSeq - 1) % 100) + 1; // 简化的章节内序号计算
                return chapterPart + "-" + chapterSeq;
            }
        } else if (bookmarkName.startsWith("Table_")) {
            // 解析表格书签：Table_1 -> 3.1
            String[] parts = bookmarkName.split("_");
            if (parts.length >= 2) {
                String sequencePart = parts[1];
                // 假设表格在第3章
                return "3." + sequencePart;
            }
        }
        // 默认返回序号
        return "1";
    }

    /**
     * 创建引用域（REF字段）
     *
     * @param paragraph    段落
     * @param bookmarkName 要引用的书签名称
     */
    public static void createReferenceField(XWPFParagraph paragraph, String bookmarkName) {
        createReferenceField(paragraph, bookmarkName, "宋体", "宋体", 21, false);
    }

    private static void createReferenceField(XWPFParagraph paragraph, String bookmarkName,
                                             String asciiFont, String eastAsiaFont,
                                             int fontHalfPoints, boolean charFormat) {
        CTR ctr = paragraph.getCTP().addNewR();
        applyFieldRunFont(ctr, asciiFont, eastAsiaFont, fontHalfPoints);
        CTFldChar fieldBegin = ctr.addNewFldChar();
        fieldBegin.setFldCharType(STFldCharType.BEGIN);

        CTR instrCtr = paragraph.getCTP().addNewR();
        applyFieldRunFont(instrCtr, asciiFont, eastAsiaFont, fontHalfPoints);
        String instruction = "REF " + bookmarkName + " \\h";
        if (charFormat) {
            instruction += " \\* CHARFORMAT";
        }
        writeFieldInstruction(instrCtr, instruction);

        CTR sepCtr = paragraph.getCTP().addNewR();
        applyFieldRunFont(sepCtr, asciiFont, eastAsiaFont, fontHalfPoints);
        CTFldChar fieldSep = sepCtr.addNewFldChar();
        fieldSep.setFldCharType(STFldCharType.SEPARATE);

        CTR resultCtr = paragraph.getCTP().addNewR();
        applyFieldRunFont(resultCtr, asciiFont, eastAsiaFont, fontHalfPoints);
        CTText resultText = resultCtr.addNewT();
        resultText.setStringValue("3.1");

        CTR endCtr = paragraph.getCTP().addNewR();
        applyFieldRunFont(endCtr, asciiFont, eastAsiaFont, fontHalfPoints);
        CTFldChar fieldEnd = endCtr.addNewFldChar();
        fieldEnd.setFldCharType(STFldCharType.END);
    }

    private static void applyFieldRunFont(CTR ctr, String asciiFont, String eastAsiaFont, int fontHalfPoints) {
        CTRPr rpr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
        CTFonts fonts = rpr.sizeOfRFontsArray() > 0 ? rpr.getRFontsArray(0) : rpr.addNewRFonts();
        fonts.setAscii(asciiFont);
        fonts.setHAnsi(asciiFont);
        fonts.setCs(asciiFont);
        fonts.setEastAsia(eastAsiaFont);
        if (rpr.sizeOfSzArray() > 0) {
            rpr.getSzArray(0).setVal(BigInteger.valueOf(fontHalfPoints));
        } else {
            rpr.addNewSz().setVal(BigInteger.valueOf(fontHalfPoints));
        }
        if (rpr.sizeOfSzCsArray() > 0) {
            rpr.getSzCsArray(0).setVal(BigInteger.valueOf(fontHalfPoints));
        } else {
            rpr.addNewSzCs().setVal(BigInteger.valueOf(fontHalfPoints));
        }
    }

    /**
     * 创建章节号域。
     * <p>使用 Word 题注的 {@code STYLEREF 1 \s}。{@code \n} 是 WPS 题注写法，Microsoft Word 更新后经常取不到章节号。</p>
     */
    private static void createStyleRefField(XWPFParagraph paragraph) {
        CTR ctr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(ctr);
        CTFldChar fieldBegin = ctr.addNewFldChar();
        fieldBegin.setFldCharType(STFldCharType.BEGIN);

        CTR instrCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(instrCtr);
        writeFieldInstruction(instrCtr, "STYLEREF 1 \\s");

        CTR sepCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(sepCtr);
        CTFldChar fieldSep = sepCtr.addNewFldChar();
        fieldSep.setFldCharType(STFldCharType.SEPARATE);

        CTR resultCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(resultCtr);
        CTText resultText = resultCtr.addNewT();
        resultText.setStringValue("3");

        CTR endCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(endCtr);
        CTFldChar fieldEnd = endCtr.addNewFldChar();
        fieldEnd.setFldCharType(STFldCharType.END);
    }

    /**
     * Word 域指令必须保留首尾空格，否则 Microsoft Word 不把这段文字当成域，只剩 WPS 能更新。
     */
    private static void writeFieldInstruction(CTR run, String instruction) {
        CTText instrText = run.addNewInstrText();
        String value = instruction == null ? "" : instruction.trim();
        instrText.setStringValue(" " + value + " ");
        instrText.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** 题注编号：四个字体槽都写 Times New Roman，避免 Word 更新域后把数字画成宋体。 */
    private static void applyCaptionNumberFont(CTR ctr) {
        applyFieldRunFont(ctr, "Times New Roman", "Times New Roman", 21);
    }

    private static void appendCaptionConnector(XWPFParagraph paragraph, String seqName) {
        XWPFRun connectorRun = paragraph.createRun();
        if ("表".equals(seqName)) {
            connectorRun.setText(".");
        } else if ("图".equals(seqName)) {
            connectorRun.setText("-");
        }
        applyCaptionNumberFont(connectorRun.getCTR());
    }

    /**
     * 创建简单的SEQ域
     *
     * @param paragraph    段落
     * @param seqName      序列名称
     * @param bookmarkName 书签名称
     */
    private static void createSimpleSEQField(XWPFParagraph paragraph, String seqName, String bookmarkName) {
        // 创建书签开始
        CTBookmark bookmarkStart = paragraph.getCTP().addNewBookmarkStart();
        bookmarkStart.setName(bookmarkName);
        bookmarkStart.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));

        // 创建域开始
        CTR ctr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(ctr);
        CTFldChar fieldBegin = ctr.addNewFldChar();
        fieldBegin.setFldCharType(STFldCharType.BEGIN);

        // 创建域代码
        CTR instrCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(instrCtr);
        writeFieldInstruction(instrCtr, "SEQ " + seqName + " \\* ARABIC \\s 1");

        // 创建域分隔符
        CTR sepCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(sepCtr);
        CTFldChar fieldSep = sepCtr.addNewFldChar();
        fieldSep.setFldCharType(STFldCharType.SEPARATE);

        // 创建域结果（显示文本）
        CTR resultCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(resultCtr);
        CTText resultText = resultCtr.addNewT();
        resultText.setStringValue("1");

        // 创建域结束
        CTR endCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(endCtr);
        CTFldChar fieldEnd = endCtr.addNewFldChar();
        fieldEnd.setFldCharType(STFldCharType.END);

        // 创建书签结束
        CTMarkupRange bookmarkEnd = paragraph.getCTP().addNewBookmarkEnd();
        bookmarkEnd.setId(BigInteger.valueOf(counters().bookmarkCounter.get()));
    }

    /**
     * 更新文档中的所有域
     *
     * @param document Word文档
     */
    public static void updateAllFields(XWPFDocument document) {
        // POI目前不支持直接更新域，需要在Word中打开文档时手动更新
        // 或者可以设置文档属性，让Word在打开时自动更新域
        try {
            document.getProperties().getCoreProperties().setCategory("AutoUpdateFields");
        } catch (Exception e) {
            // 忽略异常
        }
    }

    /**
     * 创建章节序列域（不包含书签）
     *
     * @param paragraph     段落
     * @param seqName       序列名称
     * @param chapterNumber 章节号
     */
    private static void createChapterSequenceFieldWithoutBookmark(XWPFParagraph paragraph, String seqName, Integer chapterNumber) {
        // 使用平铺多域方案：STYLEREF + 连接符 + SEQ
        // 1. 创建STYLEREF域
        createStyleRefField(paragraph);

        appendCaptionConnector(paragraph, seqName);

        // 3. 创建SEQ域（不包含书签）
        createSimpleSEQFieldWithoutBookmark(paragraph, seqName);
    }

    /**
     * 创建简单的SEQ域（不包含书签）
     *
     * @param paragraph 段落
     * @param seqName   序列名称
     */
    private static void createSimpleSEQFieldWithoutBookmark(XWPFParagraph paragraph, String seqName) {
        CTR ctr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(ctr);
        CTFldChar fieldBegin = ctr.addNewFldChar();
        fieldBegin.setFldCharType(STFldCharType.BEGIN);

        CTR instrCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(instrCtr);
        writeFieldInstruction(instrCtr, "SEQ " + seqName + " \\* ARABIC \\s 1");

        CTR sepCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(sepCtr);
        CTFldChar fieldSep = sepCtr.addNewFldChar();
        fieldSep.setFldCharType(STFldCharType.SEPARATE);

        CTR resultCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(resultCtr);
        CTText resultText = resultCtr.addNewT();
        resultText.setStringValue("1");

        CTR endCtr = paragraph.getCTP().addNewR();
        applyCaptionNumberFont(endCtr);
        CTFldChar fieldEnd = endCtr.addNewFldChar();
        fieldEnd.setFldCharType(STFldCharType.END);
    }

    /**
     * 创建序列域（不包含书签）
     *
     * @param paragraph 段落
     * @param seqName   序列名称
     */
    private static void createSequenceFieldWithoutBookmark(XWPFParagraph paragraph, String seqName) {
        createSimpleSEQFieldWithoutBookmark(paragraph, seqName);
    }
}