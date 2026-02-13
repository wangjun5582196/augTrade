package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionCategory;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult.DimensionScore;

/**
 * 维度7：ETF区域流向评估器
 *
 * 博主核心逻辑：
 * - 黄金暴跌之际，亚洲市场买盘强势介入，成为ETF买盘主要贡献力量
 * - 亚洲净流入40吨，欧洲净流出6吨，北美净流入10吨
 * - 亚洲买盘是当前金价的重要支撑力量
 * - 区域力量的博弈反映了全球资金的真实流向
 *
 * 评分逻辑：
 * - 总净流入为正且亚洲主导 → 强看多（逢低买入的聪明钱）
 * - 总净流入为正 → 看多
 * - 总净流入接近零 → 中性
 * - 总净流出 → 看空
 *
 * @author Peter Wang
 */
public class ETFFlowDimensionEvaluator implements DimensionEvaluator {

    private static final String NAME = "ETF区域流向";
    private static final double WEIGHT = 0.08;

    // 流入量阈值（吨）
    private static final double STRONG_INFLOW = 30;
    private static final double MODERATE_INFLOW = 10;
    private static final double STRONG_OUTFLOW = -20;

    @Override
    public DimensionScore evaluate(GoldPricingContext context) {
        double score = 0;
        StringBuilder explanation = new StringBuilder();
        boolean extreme = false;

        double asiaFlow = context.getEtfFlowAsia();
        double europeFlow = context.getEtfFlowEurope();
        double naFlow = context.getEtfFlowNorthAmerica();
        double totalFlow = context.getTotalETFNetFlow();

        // 1. 总流入评分（-50到+50）
        double totalScore;
        if (totalFlow >= STRONG_INFLOW) {
            totalScore = 50;
            explanation.append(String.format("ETF总净流入%.1f吨（强劲），资金持续涌入贵金属；", totalFlow));
        } else if (totalFlow >= MODERATE_INFLOW) {
            totalScore = 25;
            explanation.append(String.format("ETF总净流入%.1f吨（温和偏强）；", totalFlow));
        } else if (totalFlow >= 0) {
            totalScore = 5;
            explanation.append(String.format("ETF总净流入%.1f吨（接近平衡）；", totalFlow));
        } else if (totalFlow >= STRONG_OUTFLOW) {
            totalScore = -25;
            explanation.append(String.format("ETF总净流出%.1f吨，资金外流中；", Math.abs(totalFlow)));
        } else {
            totalScore = -50;
            explanation.append(String.format("ETF总净流出%.1f吨（严重），资金大举撤离；", Math.abs(totalFlow)));
        }
        score += totalScore;

        // 2. 亚洲买盘加分/减分（-30到+30）
        // 博主特别强调亚洲买盘的重要性
        if (asiaFlow > 20 && totalFlow > 0) {
            score += 30;
            extreme = asiaFlow > 35;
            explanation.append(String.format(
                    "亚洲净流入%.1f吨，成为买盘主力（关键支撑）；", asiaFlow));
        } else if (asiaFlow > 10) {
            score += 15;
            explanation.append(String.format("亚洲净流入%.1f吨，买盘活跃；", asiaFlow));
        } else if (asiaFlow > 0) {
            score += 5;
            explanation.append(String.format("亚洲净流入%.1f吨；", asiaFlow));
        } else {
            score -= 10;
            explanation.append(String.format("亚洲净流出%.1f吨，亚洲买盘缺位；", Math.abs(asiaFlow)));
        }

        // 3. 区域分化加分（如果亚洲买入而欧洲卖出，说明逢低吸纳）
        if (asiaFlow > 20 && europeFlow < -5) {
            score += 20;
            explanation.append(String.format(
                    "亚洲强势买入+欧洲流出（%.1f吨），呈现'东买西卖'格局，暗示聪明钱逢低买入；",
                    europeFlow));
        }

        score = Math.max(-100, Math.min(100, score));

        return DimensionScore.builder()
                .dimensionName(NAME)
                .category(DimensionCategory.SENTIMENT)
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
        return DimensionCategory.SENTIMENT;
    }

    @Override
    public double getWeight() {
        return WEIGHT;
    }
}
