package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度1：全球流动性宏观评估器
 *
 * 博主核心逻辑：
 * - 黄金上涨的一股重要力量是法币长期贬值所带来的内核驱动
 * - 其背后是全球无法回头和回避的债务困境
 * - 债务攀升和每年的再融资需求（流动性作为载体）驱动金价
 * - 全球债务346万亿美元 → 侵蚀法币信用 → 黄金被迫上涨
 *
 * 定价逻辑：
 * - 流动性增长率越高 → 金价目标越高 → 看多评分越高
 * - 债务/GDP比率越高 → 法币贬值压力越大 → 看多评分越高
 * - 黄金储备市值超过美债总额 → 结构性变革确认 → 加分
 *
 * @author Peter Wang
 */
public class LiquidityDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "全球流动性宏观趋势";
    private static final double WEIGHT = 0.25;

    // 流动性增长率阈值
    private static final double LIQUIDITY_GROWTH_STRONG_BULLISH = 0.08;  // 8%以上，强烈看多
    private static final double LIQUIDITY_GROWTH_BULLISH = 0.04;          // 4%-8%，看多
    private static final double LIQUIDITY_GROWTH_NEUTRAL = 0.01;          // 1%-4%，中性偏多
    private static final double LIQUIDITY_GROWTH_BEARISH = -0.02;         // -2%-1%，中性偏空

    // 债务/GDP阈值
    private static final double DEBT_GDP_EXTREME = 3.5;   // 350%以上为极端
    private static final double DEBT_GDP_HIGH = 3.0;       // 300%以上为高

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        // 1. 流动性增长率评分（-50到+50）
        double liquidityGrowth = context.getLiquidityGrowthRate();
        double liquidityScore;
        if (liquidityGrowth >= LIQUIDITY_GROWTH_STRONG_BULLISH) {
            liquidityScore = 50;
            explanation.append(String.format("流动性增长率%.1f%%，极度扩张，强烈看多；", liquidityGrowth * 100));
        } else if (liquidityGrowth >= LIQUIDITY_GROWTH_BULLISH) {
            liquidityScore = 30 + (liquidityGrowth - LIQUIDITY_GROWTH_BULLISH) /
                    (LIQUIDITY_GROWTH_STRONG_BULLISH - LIQUIDITY_GROWTH_BULLISH) * 20;
            explanation.append(String.format("流动性增长率%.1f%%，持续扩张，看多；", liquidityGrowth * 100));
        } else if (liquidityGrowth >= LIQUIDITY_GROWTH_NEUTRAL) {
            liquidityScore = 10 + (liquidityGrowth - LIQUIDITY_GROWTH_NEUTRAL) /
                    (LIQUIDITY_GROWTH_BULLISH - LIQUIDITY_GROWTH_NEUTRAL) * 20;
            explanation.append(String.format("流动性增长率%.1f%%，温和扩张，中性偏多；", liquidityGrowth * 100));
        } else if (liquidityGrowth >= LIQUIDITY_GROWTH_BEARISH) {
            liquidityScore = -20 + (liquidityGrowth - LIQUIDITY_GROWTH_BEARISH) /
                    (LIQUIDITY_GROWTH_NEUTRAL - LIQUIDITY_GROWTH_BEARISH) * 30;
            explanation.append(String.format("流动性增长率%.1f%%，接近停滞，中性偏空；", liquidityGrowth * 100));
        } else {
            liquidityScore = -50;
            explanation.append(String.format("流动性增长率%.1f%%，流动性收缩，看空；", liquidityGrowth * 100));
        }
        score += liquidityScore;

        // 2. 债务/GDP比率评分（-25到+25）
        double debtGdp = context.getDebtToGDPRatio();
        double debtScore;
        if (debtGdp >= DEBT_GDP_EXTREME) {
            debtScore = 25;
            extreme = true;
            explanation.append(String.format("全球债务/GDP=%.0f%%，达极端水平，法币信用持续侵蚀；", debtGdp * 100));
        } else if (debtGdp >= DEBT_GDP_HIGH) {
            debtScore = 15 + (debtGdp - DEBT_GDP_HIGH) / (DEBT_GDP_EXTREME - DEBT_GDP_HIGH) * 10;
            explanation.append(String.format("全球债务/GDP=%.0f%%，高位水平，法币贬值压力大；", debtGdp * 100));
        } else {
            debtScore = debtGdp / DEBT_GDP_HIGH * 15;
            explanation.append(String.format("全球债务/GDP=%.0f%%，", debtGdp * 100));
        }
        score += debtScore;

        // 3. 黄金储备结构性变化加分（-25到+25）
        double reserveScore;
        if (context.isGoldReserveExceedsTreasury()) {
            reserveScore = 25;
            explanation.append("黄金储备市值已超美债总额，金本位回归趋势确认；");
        } else if (context.getGoldReserveShare() > 15) {
            reserveScore = 15;
            explanation.append(String.format("黄金储备份额%.1f%%，持续攀升中；", context.getGoldReserveShare()));
        } else if (context.getGoldReserveShare() > 10) {
            reserveScore = 5;
            explanation.append(String.format("黄金储备份额%.1f%%，温和增长；", context.getGoldReserveShare()));
        } else {
            reserveScore = 0;
            explanation.append(String.format("黄金储备份额%.1f%%；", context.getGoldReserveShare()));
        }
        score += reserveScore;

        // 限制总分范围
        score = Math.max(-100, Math.min(100, score));

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.MACRO)
                .score(score)
                .weight(WEIGHT)
                .weightedContribution(score * WEIGHT)
                .explanation(explanation.toString())
                .extremeTriggered(extreme)
                .build();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public DimensionCategory getCategory() {
        return DimensionCategory.MACRO;
    }

    @Override
    public double getWeight() {
        return WEIGHT;
    }
}
