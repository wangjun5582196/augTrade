package com.ltp.peter.augtrade.strategy.gold;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 黄金多维度定价模型输出结果
 * 包含定价区间、综合评分、各维度信号及操作建议
 *
 * @author Peter Wang
 */
@Data
@Builder
public class GoldPricingResult {

    /**
     * 分析日期
     */
    private LocalDate date;

    /**
     * 当前金价
     */
    private double currentPrice;

    // ==================== 定价区间 ====================

    /**
     * 年度目标价上限
     */
    private double annualTargetHigh;

    /**
     * 年度目标价下限
     */
    private double annualTargetLow;

    /**
     * 月度波动区间上限
     */
    private double monthlyRangeHigh;

    /**
     * 月度波动区间下限
     */
    private double monthlyRangeLow;

    /**
     * 周度波动区间上限（基于Gamma敞口）
     */
    private double weeklyRangeHigh;

    /**
     * 周度波动区间下限（基于Gamma敞口）
     */
    private double weeklyRangeLow;

    /**
     * Gamma磁吸锚点价格
     */
    private double gammaMagnetPrice;

    /**
     * 绝对支撑位（Gamma敞口最大的支撑价位）
     */
    private double absoluteSupport;

    /**
     * 绝对阻力位（Gamma敞口最大的阻力价位）
     */
    private double absoluteResistance;

    // ==================== 综合评分 ====================

    /**
     * 综合评分（-100 到 +100）
     * 正值=看多，负值=看空，绝对值=信号强度
     */
    private double compositeScore;

    /**
     * 综合信号方向
     */
    private SignalDirection direction;

    /**
     * 信号强度等级
     */
    private SignalStrength strength;

    /**
     * 信号置信度（0-100%）
     */
    private double confidence;

    // ==================== 各维度明细 ====================

    /**
     * 各维度评分详情
     */
    private List<DimensionScore> dimensionScores;

    // ==================== 操作建议 ====================

    /**
     * 操作建议
     */
    private TradingAdvice advice;

    /**
     * 市场状态描述
     */
    private String marketStateDescription;

    /**
     * 详细分析报告
     */
    private String analysisReport;

    // ==================== 内部类 ====================

    /**
     * 信号方向枚举
     */
    public enum SignalDirection {
        STRONG_BULLISH("强烈看多"),
        BULLISH("看多"),
        NEUTRAL("中性/震荡"),
        BEARISH("看空"),
        STRONG_BEARISH("强烈看空");

        private final String description;

        SignalDirection(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 信号强度枚举
     */
    public enum SignalStrength {
        EXTREME("极端信号", 80),
        STRONG("强信号", 60),
        MODERATE("中等信号", 40),
        WEAK("弱信号", 20),
        NOISE("噪音/无效", 0);

        private final String description;
        private final int threshold;

        SignalStrength(String description, int threshold) {
            this.description = description;
            this.threshold = threshold;
        }

        public String getDescription() {
            return description;
        }

        public int getThreshold() {
            return threshold;
        }

        public static SignalStrength fromAbsoluteScore(double absScore) {
            if (absScore >= 80) return EXTREME;
            if (absScore >= 60) return STRONG;
            if (absScore >= 40) return MODERATE;
            if (absScore >= 20) return WEAK;
            return NOISE;
        }
    }

    /**
     * 单个维度的评分
     */
    @Data
    @Builder
    public static class DimensionScore {
        /**
         * 维度名称
         */
        private String dimensionName;

        /**
         * 维度类别（MACRO/MICRO/SENTIMENT）
         */
        private DimensionCategory category;

        /**
         * 评分（-100到+100）
         */
        private double score;

        /**
         * 权重（0-1）
         */
        private double weight;

        /**
         * 加权后的贡献值
         */
        private double weightedContribution;

        /**
         * 信号说明
         */
        private String explanation;

        /**
         * 是否触发极端值
         */
        private boolean extremeTriggered;
    }

    /**
     * 维度分类
     */
    public enum DimensionCategory {
        MACRO("宏观定价层"),
        MICRO("微观定价层"),
        SENTIMENT("情绪与节奏层");

        private final String description;

        DimensionCategory(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 交易操作建议
     */
    @Data
    @Builder
    public static class TradingAdvice {
        /**
         * 建议操作类型
         */
        private AdviceType type;

        /**
         * 建议入场区间下限
         */
        private double entryRangeLow;

        /**
         * 建议入场区间上限
         */
        private double entryRangeHigh;

        /**
         * 建议止损位
         */
        private double stopLoss;

        /**
         * 建议止盈位
         */
        private double takeProfit;

        /**
         * 最低容错空间要求（美元）
         */
        private double minToleranceRequired;

        /**
         * 仓位建议（占总资金百分比）
         */
        private double positionSizePercent;

        /**
         * 分批建仓建议（每批次的价位列表）
         */
        private List<Double> scalingLevels;

        /**
         * 操作说明
         */
        private String description;

        /**
         * 风险等级（1-5）
         */
        private int riskLevel;
    }

    /**
     * 操作建议类型
     */
    public enum AdviceType {
        AGGRESSIVE_LONG("积极做多"),
        SCALE_IN_LONG("逢低分批做多"),
        RANGE_TRADE("高抛低吸"),
        WAIT("观望等待"),
        SCALE_IN_SHORT("逢高分批做空"),
        AGGRESSIVE_SHORT("积极做空");

        private final String description;

        AdviceType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
