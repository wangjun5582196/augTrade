package com.ltp.peter.augtrade.strategy.gold;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 黄金多维度定价模型的输入上下文
 * 包含宏观、微观、情绪三个层面的全部数据
 *
 * 基于博主分析框架：宏观流动性定大方向 → 微观数据定区间 → 情绪/季节性定节奏
 *
 * @author Peter Wang
 */
@Data
@Builder
public class GoldPricingContext {

    // ==================== 基础信息 ====================

    /**
     * 当前日期
     */
    private LocalDate date;

    /**
     * 当前金价（美元/盎司）
     */
    private double currentGoldPrice;

    /**
     * 当前银价（美元/盎司）
     */
    private double currentSilverPrice;

    // ==================== 第一层：宏观流动性数据 ====================

    /**
     * 全球流动性总量（万亿美元）
     * = Σ(主要央行资产负债表) + 全球M2
     */
    private double globalLiquidity;

    /**
     * 上期全球流动性总量（万亿美元）
     */
    private double previousGlobalLiquidity;

    /**
     * 全球债务总额（万亿美元）
     */
    private double globalDebt;

    /**
     * 全球GDP（万亿美元）
     */
    private double globalGDP;

    /**
     * 黄金在全球官方储备中的份额（百分比，如15.5表示15.5%）
     */
    private double goldReserveShare;

    /**
     * 黄金储备市值（万亿美元）
     */
    private double goldReserveMarketCap;

    /**
     * 美债总额（万亿美元）
     */
    private double usTreasuryTotal;

    // ==================== 第二层：微观期权/保证金数据 ====================

    /**
     * 黄金期权Gamma敞口分布
     * key=执行价（美元），value=Gamma敞口值（正=支撑，负=阻力）
     */
    private Map<Double, Double> goldGammaExposure;

    /**
     * 白银期权Gamma敞口分布
     */
    private Map<Double, Double> silverGammaExposure;

    /**
     * CME黄金保证金率（百分比，如20表示20%）
     */
    private double cmeGoldMarginRate;

    /**
     * CME白银保证金率（百分比）
     */
    private double cmeSilverMarginRate;

    /**
     * 保证金占头寸名义价值的历史比例序列（用于判断是否见顶）
     */
    private List<Double> historicalMarginRatios;

    /**
     * 看涨期权成交量（当日）
     */
    private double callOptionVolume;

    /**
     * 看涨期权成交量3日移动平均
     */
    private double callOptionVolume3dMA;

    /**
     * 看涨期权成交量近期高点
     */
    private double callOptionVolumeRecentHigh;

    /**
     * COMEX黄金交割量（手）
     */
    private double comexGoldDeliveryVolume;

    /**
     * COMEX白银交割量（手）
     */
    private double comexSilverDeliveryVolume;

    /**
     * COMEX黄金交割量历史均值（手）
     */
    private double comexGoldDeliveryAvg;

    /**
     * COMEX白银交割量历史均值（手）
     */
    private double comexSilverDeliveryAvg;

    // ==================== 第三层：情绪与节奏数据 ====================

    /**
     * 黄金市场强度指标（0-100，50为中性）
     */
    private double goldMarketStrength;

    /**
     * 白银市场强度指标
     */
    private double silverMarketStrength;

    /**
     * 黄金多空顶底指标值
     */
    private double goldTopBottomIndicator;

    /**
     * 黄金多空顶底指标近期低点
     */
    private double goldTopBottomRecentLow;

    /**
     * 散户做空指数（0-100，低=散户过度做多）
     */
    private double retailShortIndex;

    /**
     * ETF区域净流入：亚洲（吨）
     */
    private double etfFlowAsia;

    /**
     * ETF区域净流入：欧洲（吨）
     */
    private double etfFlowEurope;

    /**
     * ETF区域净流入：北美（吨）
     */
    private double etfFlowNorthAmerica;

    /**
     * 美元指数DXY当前值
     */
    private double dxyIndex;

    /**
     * 金价与美元30日滚动相关系数（-1到1）
     */
    private double goldDxyCorrelation30d;

    /**
     * 金价与美元60日滚动相关系数
     */
    private double goldDxyCorrelation60d;

    /**
     * 金价与美元90日滚动相关系数
     */
    private double goldDxyCorrelation90d;

    /**
     * 金价历史价格序列（用于计算回撤）
     */
    private List<Double> goldPriceHistory;

    /**
     * 金价近期高点
     */
    private double goldRecentHigh;

    /**
     * 金价当前回撤幅度（百分比，如5.2表示从高点回撤5.2%）
     */
    private double goldDrawdownPercent;

    /**
     * 历史最大回撤幅度列表（用于分位数比较）
     */
    private List<Double> historicalMaxDrawdowns;

    // ==================== 辅助方法 ====================

    /**
     * 计算流动性增长率
     */
    public double getLiquidityGrowthRate() {
        if (previousGlobalLiquidity <= 0) return 0;
        return (globalLiquidity - previousGlobalLiquidity) / previousGlobalLiquidity;
    }

    /**
     * 计算全球债务/GDP比率
     */
    public double getDebtToGDPRatio() {
        if (globalGDP <= 0) return 0;
        return globalDebt / globalGDP;
    }

    /**
     * 计算ETF总净流入（吨）
     */
    public double getTotalETFNetFlow() {
        return etfFlowAsia + etfFlowEurope + etfFlowNorthAmerica;
    }

    /**
     * 黄金储备市值是否超过美债总额
     */
    public boolean isGoldReserveExceedsTreasury() {
        return goldReserveMarketCap > usTreasuryTotal;
    }
}
