package edu.whut.cs.bi.biz.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 桥梁地图上的一个点。坐标来自桥梁卡片属性 GPS经度 / GPS纬度。
 */
@Data
public class BuildingMapPoint {

    private Long id;

    private String name;

    /** 0 组合桥，1 桥幅 */
    private String isLeaf;

    private BigDecimal longitude;

    private BigDecimal latitude;

    /** 最新一次评定的技术状况等级；没有评定时为 null */
    private Integer systemLevel;

    /** 全桥得分 */
    private BigDecimal systemScore;

    private Integer superstructureLevel;

    private Integer substructureLevel;

    private Integer deckSystemLevel;

    /** 这座桥最近一次检查任务，用来打开病害列表 */
    private Long taskId;

    /** 当前技术状况所属的检查任务，用来打开评定详情 */
    private Long evaluationTaskId;
}
