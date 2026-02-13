package com.ltp.peter.augtrade.strategy.gold.dimension;

import com.ltp.peter.augtrade.strategy.gold.GoldPricingContext;
import com.ltp.peter.augtrade.strategy.gold.GoldPricingResult;

/**
 * 维度评估器接口
 * 每个维度对应博主分析框架中的一个分析角度
 *
 * @author Peter Wang
 */
public interface DimensionEvaluator {

    /**
     * 评估该维度，返回评分
     *
     * @param context 定价上下文
     * @return 维度评分结果
     */
    GoldPricingResult.DimensionScore evaluate(GoldPricingContext context);

    /**
     * 获取维度名称
     */
    String getName();

    /**
     * 获取维度分类
     */
    GoldPricingResult.DimensionCategory getCategory();

    /**
     * 获取维度权重（0-1）
     */
    double getWeight();
}
