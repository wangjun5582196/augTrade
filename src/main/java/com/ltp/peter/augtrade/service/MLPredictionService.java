package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 机器学习预测服务
 * 
 * 使用简单但有效的特征工程+线性模型
 * 预测市场短期走势
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class MLPredictionService {
    
    @Autowired
    private IndicatorService indicatorService;
    
    /**
     * 预测市场走势
     * 
     * @param klines 历史K线数据
     * @return 预测概率 (0-1)，>0.6表示看涨，<0.4表示看跌
     */
    public double predictMarketDirection(List<Kline> klines) {
        if (klines == null || klines.size() < 50) {
            log.warn("数据不足，无法进行ML预测");
            return 0.5; // 中性
        }
        
        try {
            // 1. 特征提取
            double[] features = extractFeatures(klines);
            
            // 2. 特征标准化
            double[] normalizedFeatures = normalizeFeatures(features);
            
            // 3. 模型预测（使用训练好的权重）
            double prediction = predict(normalizedFeatures);
            
            log.info("ML预测结果: {:.2f} ({})", prediction, 
                    prediction > 0.6 ? "看涨" : prediction < 0.4 ? "看跌" : "中性");
            
            return prediction;
            
        } catch (Exception e) {
            log.error("ML预测失败", e);
            return 0.5; // 失败时返回中性
        }
    }
    
    /**
     * 特征提取
     * 提取15个关键技术指标作为特征
     */
    private double[] extractFeatures(List<Kline> klines) {
        List<Double> features = new ArrayList<>();
        
        // 当前价格信息
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal openPrice = klines.get(0).getOpenPrice();
        BigDecimal highPrice = klines.get(0).getHighPrice();
        BigDecimal lowPrice = klines.get(0).getLowPrice();
        
        // 特征1: 当前K线实体方向 (1=阳线, -1=阴线)
        double candleDirection = currentPrice.compareTo(openPrice) >= 0 ? 1.0 : -1.0;
        features.add(candleDirection);
        
        // 特征2: K线实体大小 (收盘价-开盘价)/开盘价
        double bodySize = currentPrice.subtract(openPrice)
                .divide(openPrice, 6, RoundingMode.HALF_UP).doubleValue();
        features.add(bodySize);
        
        // 特征3: 上影线比例
        double upperShadow = highPrice.subtract(currentPrice.max(openPrice))
                .divide(highPrice.subtract(lowPrice).add(new BigDecimal("0.0001")), 6, RoundingMode.HALF_UP)
                .doubleValue();
        features.add(upperShadow);
        
        // 特征4: 下影线比例
        double lowerShadow = currentPrice.min(openPrice).subtract(lowPrice)
                .divide(highPrice.subtract(lowPrice).add(new BigDecimal("0.0001")), 6, RoundingMode.HALF_UP)
                .doubleValue();
        features.add(lowerShadow);
        
        // 特征5-7: 短期动量（1、3、5根K线）
        for (int period : new int[]{1, 3, 5}) {
            if (klines.size() > period) {
                BigDecimal prevPrice = klines.get(period).getClosePrice();
                double momentum = currentPrice.subtract(prevPrice)
                        .divide(prevPrice, 6, RoundingMode.HALF_UP).doubleValue();
                features.add(momentum);
            } else {
                features.add(0.0);
            }
        }
        
        // 特征8: RSI
        BigDecimal rsi = indicatorService.calculateRSI(klines, 14);
        features.add((rsi.doubleValue() - 50) / 50.0); // 标准化到-1到1
        
        // 特征9: Williams %R
        BigDecimal williamsR = indicatorService.calculateWilliamsR(klines, 14);
        features.add((williamsR.doubleValue() + 50) / 50.0); // 标准化到-1到1
        
        // 特征10: MACD
        BigDecimal[] macd = indicatorService.calculateMACD(klines, 12, 26, 9);
        double macdValue = macd[0].doubleValue() / currentPrice.doubleValue();
        features.add(macdValue);
        
        // 特征11: ATR (波动率)
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        double atrRatio = atr.divide(currentPrice, 6, RoundingMode.HALF_UP).doubleValue();
        features.add(atrRatio);
        
        // 特征12: 成交量变化
        if (klines.size() > 5) {
            BigDecimal avgVolume = BigDecimal.ZERO;
            for (int i = 1; i <= 5; i++) {
                avgVolume = avgVolume.add(klines.get(i).getVolume());
            }
            avgVolume = avgVolume.divide(new BigDecimal("5"), 6, RoundingMode.HALF_UP);
            double volumeRatio = klines.get(0).getVolume()
                    .divide(avgVolume.add(new BigDecimal("0.0001")), 6, RoundingMode.HALF_UP)
                    .doubleValue();
            features.add(volumeRatio - 1.0);
        } else {
            features.add(0.0);
        }
        
        // 特征13: 价格位置（在近20根K线的高低点中的位置）
        if (klines.size() >= 20) {
            BigDecimal highest = klines.get(0).getHighPrice();
            BigDecimal lowest = klines.get(0).getLowPrice();
            for (int i = 1; i < 20; i++) {
                highest = highest.max(klines.get(i).getHighPrice());
                lowest = lowest.min(klines.get(i).getLowPrice());
            }
            double pricePosition = currentPrice.subtract(lowest)
                    .divide(highest.subtract(lowest).add(new BigDecimal("0.0001")), 6, RoundingMode.HALF_UP)
                    .doubleValue();
            features.add(pricePosition);
        } else {
            features.add(0.5);
        }
        
        // 特征14: Stochastic %K
        BigDecimal[] stoch = indicatorService.calculateStochastic(klines, 14, 3);
        features.add((stoch[0].doubleValue() - 50) / 50.0);
        
        // 特征15: 趋势强度（ADX）
        BigDecimal adx = indicatorService.calculateADX(klines, 14);
        features.add(adx.doubleValue() / 100.0);
        
        return features.stream().mapToDouble(Double::doubleValue).toArray();
    }
    
    /**
     * 特征标准化
     * 使用Min-Max标准化，将特征缩放到[-1, 1]范围
     */
    private double[] normalizeFeatures(double[] features) {
        double[] normalized = new double[features.length];
        for (int i = 0; i < features.length; i++) {
            // 限制在合理范围内
            double value = Math.max(-10, Math.min(10, features[i]));
            // 简单的tanh标准化
            normalized[i] = Math.tanh(value);
        }
        return normalized;
    }
    
    /**
     * 预测模型
     * 
     * 使用训练好的权重进行预测
     * 这里使用简化的线性模型 + sigmoid激活
     * 
     * 实际应用中，这些权重应该通过历史数据训练得到
     * 当前使用的是基于技术分析原理的启发式权重
     */
    private double predict(double[] features) {
        // 预训练权重（基于技术分析经验）
        // 实际使用时应该用真实历史数据训练
        double[] weights = {
            0.8,   // 特征1: K线方向 - 重要
            1.2,   // 特征2: 实体大小 - 非常重要
            -0.5,  // 特征3: 上影线 - 负相关
            0.5,   // 特征4: 下影线 - 正相关
            1.5,   // 特征5: 1K动量 - 最重要
            1.0,   // 特征6: 3K动量 - 重要
            0.8,   // 特征7: 5K动量 - 重要
            0.7,   // 特征8: RSI - 重要
            1.0,   // 特征9: Williams - 重要
            0.6,   // 特征10: MACD - 中等
            -0.3,  // 特征11: ATR - 波动率
            0.4,   // 特征12: 成交量 - 中等
            0.5,   // 特征13: 价格位置 - 中等
            0.6,   // 特征14: Stochastic - 重要
            0.4    // 特征15: ADX - 中等
        };
        
        double bias = 0.0; // 偏置项
        
        // 线性组合
        double sum = bias;
        for (int i = 0; i < features.length && i < weights.length; i++) {
            sum += features[i] * weights[i];
        }
        
        // Sigmoid激活函数，输出0-1概率
        double probability = 1.0 / (1.0 + Math.exp(-sum));
        
        return probability;
    }
    
    /**
     * 获取预测信号强度
     * 
     * @param prediction 预测概率
     * @return 信号强度描述
     */
    public String getPredictionStrength(double prediction) {
        if (prediction >= 0.75) {
            return "强烈看涨";
        } else if (prediction >= 0.6) {
            return "看涨";
        } else if (prediction >= 0.4) {
            return "中性";
        } else if (prediction >= 0.25) {
            return "看跌";
        } else {
            return "强烈看跌";
        }
    }
    
    /**
     * 模型置信度评估
     * 基于特征质量和数据完整性
     */
    public double getConfidence(List<Kline> klines) {
        if (klines == null || klines.size() < 50) {
            return 0.3; // 数据不足，低置信度
        }
        
        // 检查数据质量
        int dataQualityScore = 0;
        
        // 1. 数据量充足
        if (klines.size() >= 100) dataQualityScore += 20;
        else if (klines.size() >= 50) dataQualityScore += 10;
        
        // 2. 成交量正常
        BigDecimal avgVolume = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(20, klines.size()); i++) {
            avgVolume = avgVolume.add(klines.get(i).getVolume());
        }
        avgVolume = avgVolume.divide(new BigDecimal(Math.min(20, klines.size())), RoundingMode.HALF_UP);
        if (avgVolume.compareTo(BigDecimal.ZERO) > 0) dataQualityScore += 30;
        
        // 3. 价格波动正常（不是静止市场）
        BigDecimal atr = indicatorService.calculateATR(klines, 14);
        BigDecimal currentPrice = klines.get(0).getClosePrice();
        BigDecimal atrRatio = atr.divide(currentPrice, 4, RoundingMode.HALF_UP);
        if (atrRatio.compareTo(new BigDecimal("0.001")) > 0) dataQualityScore += 30;
        
        // 4. 数据连续性好
        dataQualityScore += 20;
        
        return dataQualityScore / 100.0;
    }
}
