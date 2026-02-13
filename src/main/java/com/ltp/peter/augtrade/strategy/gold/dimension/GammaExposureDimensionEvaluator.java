package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

import java.util.Map;

/**
 * 维度2：期权Gamma敞口评估器
 *
 * 博主核心逻辑：
 * - 做市商为保持delta中性，需时刻对标的资产进行买卖对冲
 * - Gamma敞口分布决定了价格的"磁吸点"和"支撑/阻力"区域
 * - 当金价触碰大Gamma支撑位时，做市商需大量买入期货/现货（形成支撑）
 * - 价格倾向于向Gamma最大的执行价收敛（磁吸效应）
 *
 * 评分逻辑：
 * - 当前价格在Gamma支撑位之上 → 安全，看多
 * - 当前价格接近/在Gamma支撑位 → 强支撑，极度看多
 * - 当前价格在Gamma阻力位之下 → 有压力，看空
 * - 距离磁吸点的距离决定短期方向
 *
 * @author Peter Wang
 */
public class GammaExposureDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "期权Gamma敞口";
    private static final double WEIGHT = 0.20;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        Map<Double, Double> gammaMap = context.getGoldGammaExposure();
        double currentPrice = context.getCurrentGoldPrice();

        if (gammaMap == null || gammaMap.isEmpty()) {
            return DimensionScore.builder()
                    .dimensionName(NAME)
                    .category(DimensionCategory.MICRO)
                    .score(0)
                    .weight(WEIGHT)
                    .weightedContribution(0)
                    .explanation("无Gamma敞口数据，无法评估")
                    .extremeTriggered(false)
                    .build();
        }

        // 1. 找到最大正Gamma（支撑）和最大负Gamma（阻力）的执行价
        double maxPositiveGamma = 0;
        double maxPositiveGammaStrike = 0;
        double maxNegativeGamma = 0;
        double maxNegativeGammaStrike = 0;
        double maxAbsGamma = 0;
        double magnetStrike = 0;

        // 找到最近的支撑位和阻力位
        double nearestSupportStrike = 0;
        double nearestSupportGamma = 0;
        double nearestResistanceStrike = Double.MAX_VALUE;
        double nearestResistanceGamma = 0;

        for (Map.Entry<Double, Double> entry : gammaMap.entrySet()) {
            double strike = entry.getKey();
            double gamma = entry.getValue();
            double absGamma = Math.abs(gamma);

            if (absGamma > maxAbsGamma) {
                maxAbsGamma = absGamma;
                magnetStrike = strike;
            }

            if (gamma > maxPositiveGamma) {
                maxPositiveGamma = gamma;
                maxPositiveGammaStrike = strike;
            }

            if (gamma < maxNegativeGamma) {
                maxNegativeGamma = gamma;
                maxNegativeGammaStrike = strike;
            }

            // 找当前价格下方最近的正Gamma支撑
            if (gamma > 0 && strike <= currentPrice && strike > nearestSupportStrike) {
                nearestSupportStrike = strike;
                nearestSupportGamma = gamma;
            }

            // 找当前价格上方最近的负Gamma阻力
            if (gamma < 0 && strike >= currentPrice && strike < nearestResistanceStrike) {
                nearestResistanceStrike = strike;
                nearestResistanceGamma = gamma;
            }
        }

        // 2. 评估当前价格相对于Gamma结构的位置
        // 距离支撑的百分比
        double distToSupport = (currentPrice - nearestSupportStrike) / currentPrice * 100;
        // 距离阻力的百分比
        double distToResistance = nearestResistanceStrike == Double.MAX_VALUE ? 100 :
                (nearestResistanceStrike - currentPrice) / currentPrice * 100;
        // 距离磁吸点的百分比（正=在磁吸点上方，负=在下方）
        double distToMagnet = (currentPrice - magnetStrike) / currentPrice * 100;

        // 3. 综合评分
        // 3a. 支撑强度评分（-30到+40）
        if (distToSupport <= 1.0 && nearestSupportGamma > 0) {
            // 当前价格非常接近强支撑
            score += 40;
            extreme = true;
            explanation.append(String.format("金价%.0f非常接近Gamma强支撑%.0f（做市商将大量买入），极度看多；",
                    currentPrice, nearestSupportStrike));
        } else if (distToSupport <= 3.0 && nearestSupportGamma > 0) {
            score += 25;
            explanation.append(String.format("金价%.0f距Gamma支撑%.0f仅%.1f%%，有较强支撑；",
                    currentPrice, nearestSupportStrike, distToSupport));
        } else if (distToSupport <= 5.0) {
            score += 10;
            explanation.append(String.format("Gamma支撑位在%.0f，距当前价%.1f%%；", nearestSupportStrike, distToSupport));
        } else {
            score -= 10;
            explanation.append(String.format("距Gamma支撑%.0f较远(%.1f%%)；", nearestSupportStrike, distToSupport));
        }

        // 3b. 阻力距离评分（-30到+30）
        if (distToResistance > 5.0) {
            score += 30;
            explanation.append(String.format("上方Gamma阻力%.0f较远(%.1f%%)，上行空间充裕；",
                    nearestResistanceStrike, distToResistance));
        } else if (distToResistance > 2.0) {
            score += 10;
            explanation.append(String.format("上方Gamma阻力%.0f距%.1f%%；", nearestResistanceStrike, distToResistance));
        } else if (nearestResistanceStrike != Double.MAX_VALUE) {
            score -= 20;
            explanation.append(String.format("接近Gamma阻力%.0f（做市商将卖出对冲），上行受阻；",
                    nearestResistanceStrike));
        }

        // 3c. 磁吸效应评分（-30到+30）
        if (distToMagnet < -2.0) {
            // 当前价格在磁吸点下方，有向上拉力
            score += Math.min(30, Math.abs(distToMagnet) * 5);
            explanation.append(String.format("价格在Gamma磁吸点%.0f下方，存在向上拉力；", magnetStrike));
        } else if (distToMagnet > 2.0) {
            // 当前价格在磁吸点上方，有向下拉力
            score -= Math.min(30, distToMagnet * 5);
            explanation.append(String.format("价格在Gamma磁吸点%.0f上方，存在向下拉力；", magnetStrike));
        } else {
            explanation.append(String.format("价格接近Gamma磁吸点%.0f，短期可能横盘；", magnetStrike));
        }

        score = Math.max(-100, Math.min(100, score));

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.MICRO)
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
        return DimensionCategory.MICRO;
    }

    @Override
    public double getWeight() {
        return WEIGHT;
    }
}
