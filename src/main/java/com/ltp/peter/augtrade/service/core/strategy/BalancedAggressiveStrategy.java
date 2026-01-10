package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.MLPredictionService;
import com.ltp.peter.augtrade.service.MLRecordService;
import com.ltp.peter.augtrade.service.core.indicator.ADXCalculator;
import com.ltp.peter.augtrade.service.core.indicator.CandlePattern;
import com.ltp.peter.augtrade.service.core.indicator.CandlePatternAnalyzer;
import com.ltp.peter.augtrade.service.core.indicator.RSICalculator;
import com.ltp.peter.augtrade.service.core.indicator.WilliamsRCalculator;
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 均衡激进策略（增强版）
 * 
 * 综合多个指标但降低所有阈值，平衡交易频率和信号质量
 * 
 * 特点：
 * - 结合Williams %R、RSI、ADX、ML预测和K线形态
 * - 使用评分系统（需要5分以上）
 * - 根据ADX动态调整门槛
 * - K线形态识别增强信号确认
 * - 交易频率：中高（每天5-15次）
 * 
 * 权重：7（适合激进型交易者）
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class BalancedAggressiveStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "BalancedAggressive";
    private static final int STRATEGY_WEIGHT = 7;
    
    // 默认评分阈值
    private static final int DEFAULT_SCORE_THRESHOLD = 5;
    private static final int CHOPPY_MARKET_THRESHOLD = 7;
    
    @Autowired
    private RSICalculator rsiCalculator;
    
    @Autowired
    private WilliamsRCalculator williamsCalculator;
    
    @Autowired
    private ADXCalculator adxCalculator;
    
    @Autowired
    private CandlePatternAnalyzer candlePatternAnalyzer;
    
    @Autowired(required = false)
    private MLPredictionService mlPredictionService;
    
    @Autowired(required = false)
    private MLRecordService mlRecordService;
    
    @Autowired
    private MarketRegimeDetector marketRegimeDetector;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足", 0, 0);
        }
        
        try {
            // 计算所有指标
            Double williamsR = williamsCalculator.calculate(context.getKlines());
            Double rsi = rsiCalculator.calculate(context.getKlines());
            Double adx = adxCalculator.calculate(context.getKlines());
            CandlePattern pattern = candlePatternAnalyzer.calculate(context.getKlines());
            
            if (williamsR == null || rsi == null || adx == null) {
                log.warn("[{}] 指标计算失败", STRATEGY_NAME);
                return createHoldSignal("指标计算失败", 0, 0);
            }
            
            // ML预测（可选）
            double mlPrediction = 0.5;
            double mlConfidence = 0.5;
            if (mlPredictionService != null) {
                try {
                    mlPrediction = mlPredictionService.predictMarketDirection(context.getKlines());
                    mlConfidence = mlPredictionService.getConfidence(context.getKlines());
                } catch (Exception e) {
                    log.warn("[{}] ML预测失败，使用默认值0.5", STRATEGY_NAME);
                }
            }
            
            // 计算动量
            BigDecimal momentum = BigDecimal.ZERO;
            if (context.getKlines().size() >= 6) {
                BigDecimal currentPrice = context.getKlines().get(0).getClosePrice();
                BigDecimal price5 = context.getKlines().get(5).getClosePrice();
                momentum = currentPrice.subtract(price5);
            }
            
            log.debug("[{}] Williams: {}, RSI: {}, ADX: {}, ML: {}, 动量: {}, K线形态: {}", 
                    STRATEGY_NAME, williamsR, rsi, adx, String.format("%.2f", mlPrediction), momentum, 
                    pattern.hasPattern() ? pattern.getType() : "无");
            
            // 评分系统
            int buyScore = 0;
            int sellScore = 0;
            
            // 确定评分阈值（根据ADX调整）
            int requiredScore = DEFAULT_SCORE_THRESHOLD;
            if (adx < 20) {
                // 震荡市场：提高门槛
                requiredScore = CHOPPY_MARKET_THRESHOLD;
                log.debug("[{}] ADX={}, 震荡市场，提高评分要求至{}分", STRATEGY_NAME, adx, requiredScore);
            } else if (adx > 30) {
                // 强趋势市场：趋势确认加分
                if (momentum.compareTo(BigDecimal.ZERO) > 0) {
                    buyScore += 2;
                    log.debug("[{}] ADX={}, 强趋势 + 上涨动量 → +2分", STRATEGY_NAME, adx);
                } else if (momentum.compareTo(BigDecimal.ZERO) < 0) {
                    sellScore += 2;
                    log.debug("[{}] ADX={}, 强趋势 + 下跌动量 → +2分", STRATEGY_NAME, adx);
                }
            }
            
            // Williams评分（权重3）
            if (williamsR < -60) {
                buyScore += 3;
                log.debug("[{}] Williams超卖 ({}) → +3分", STRATEGY_NAME, williamsR);
            } else if (williamsR > -40) {
                sellScore += 3;
                log.debug("[{}] Williams超买 ({}) → +3分", STRATEGY_NAME, williamsR);
            }
            
            // RSI评分（权重2）
            if (rsi < 45) {
                buyScore += 2;
                log.debug("[{}] RSI偏低 ({}) → +2分", STRATEGY_NAME, rsi);
            } else if (rsi > 55) {
                sellScore += 2;
                log.debug("[{}] RSI偏高 ({}) → +2分", STRATEGY_NAME, rsi);
            }
            
            // ML评分（权重2）
            if (mlPrediction > 0.52) {
                buyScore += 2;
                log.debug("[{}] ML看涨 ({}) → +2分", STRATEGY_NAME, String.format("%.2f", mlPrediction));
            } else if (mlPrediction < 0.48) {
                sellScore += 2;
                log.debug("[{}] ML看跌 ({}) → +2分", STRATEGY_NAME, String.format("%.2f", mlPrediction));
            }
            
            // 动量评分（权重1）
            if (momentum.compareTo(BigDecimal.ZERO) > 0) {
                buyScore += 1;
                log.debug("[{}] 动量向上 ({}) → +1分", STRATEGY_NAME, momentum);
            } else if (momentum.compareTo(BigDecimal.ZERO) < 0) {
                sellScore += 1;
                log.debug("[{}] 动量向下 ({}) → +1分", STRATEGY_NAME, momentum);
            }
            
            // ✨ K线形态评分（权重3，根据强度调整）
            if (pattern.hasPattern()) {
                int patternScore = Math.min(pattern.getStrength() / 3, 3); // 强度10→3分，强度9→3分，强度7→2分
                if (pattern.isBullish()) {
                    buyScore += patternScore;
                    log.info("[{}] K线形态: {} (强度{}) → 看涨信号 +{}分", 
                            STRATEGY_NAME, pattern.getType().getDescription(), pattern.getStrength(), patternScore);
                } else if (pattern.isBearish()) {
                    sellScore += patternScore;
                    log.info("[{}] K线形态: {} (强度{}) → 看跌信号 +{}分", 
                            STRATEGY_NAME, pattern.getType().getDescription(), pattern.getStrength(), patternScore);
                } else {
                    log.debug("[{}] K线形态: {} → 中性，观望", STRATEGY_NAME, pattern.getType().getDescription());
                }
            }
            
            log.info("[{}] 综合评分 - 买入: {}, 卖出: {}, 需要: {}分", 
                    STRATEGY_NAME, buyScore, sellScore, requiredScore);
            
            // 根据评分生成信号
            if (buyScore >= requiredScore && buyScore > sellScore) {
                int strength = calculateSignalStrength(buyScore, requiredScore);
                String reason = pattern.hasPattern() && pattern.isBullish() ?
                        String.format("均衡激进策略做多 (评分:%d, Williams:%.1f, RSI:%.1f, 形态:%s)", 
                                buyScore, williamsR, rsi, pattern.getType().getDescription()) :
                        String.format("均衡激进策略做多 (评分:%d, Williams:%.1f, RSI:%.1f)", 
                                buyScore, williamsR, rsi);
                
                // ✨ 记录ML预测（买入信号）
                recordMLPrediction(context.getSymbol(), mlPrediction, "BUY", mlConfidence, 
                                   williamsR, context.getCurrentPrice(), true);
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(strength)
                        .score(buyScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(reason)
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            if (sellScore >= requiredScore && sellScore > buyScore) {
                int strength = calculateSignalStrength(sellScore, requiredScore);
                String reason = pattern.hasPattern() && pattern.isBearish() ?
                        String.format("均衡激进策略做空 (评分:%d, Williams:%.1f, RSI:%.1f, 形态:%s)", 
                                sellScore, williamsR, rsi, pattern.getType().getDescription()) :
                        String.format("均衡激进策略做空 (评分:%d, Williams:%.1f, RSI:%.1f)", 
                                sellScore, williamsR, rsi);
                
                // ✨ 记录ML预测（卖出信号）
                recordMLPrediction(context.getSymbol(), mlPrediction, "SELL", mlConfidence, 
                                   williamsR, context.getCurrentPrice(), true);
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(strength)
                        .score(sellScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(reason)
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 评分不足或冲突 - 不记录ML（只有开仓时才记录）
            String reason = String.format("评分不足或冲突 (买入:%d, 卖出:%d, 需要:%d)", 
                    buyScore, sellScore, requiredScore);
            return createHoldSignal(reason, buyScore, sellScore);
            
        } catch (Exception e) {
            log.error("[{}] 生成交易信号时发生错误", STRATEGY_NAME, e);
            return createHoldSignal("策略执行异常", 0, 0);
        }
    }
    
    @Override
    public String getName() {
        return STRATEGY_NAME;
    }
    
    @Override
    public int getWeight() {
        return STRATEGY_WEIGHT;
    }
    
    @Override
    public String getDescription() {
        return "均衡激进策略（增强版）- 结合多个指标和K线形态的评分系统，适合激进型交易者";
    }
    
    /**
     * 计算信号强度
     * 
     * @param score 实际得分
     * @param threshold 门槛分数
     * @return 信号强度（0-100）
     */
    private int calculateSignalStrength(int score, int threshold) {
        // 基础强度：超过门槛的分数 * 10
        int baseStrength = (score - threshold) * 10;
        
        // 额外强度：根据总分数
        int bonusStrength = score * 5;
        
        int totalStrength = baseStrength + bonusStrength;
        
        // 限制在0-100之间
        return Math.min(Math.max(totalStrength, 0), 100);
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason, int buyScore, int sellScore) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
    
    /**
     * ✨ 新增：记录ML预测到数据库
     */
    private void recordMLPrediction(String symbol, double mlPrediction, String predictedSignal,
                                    double confidence, double williamsR, BigDecimal currentPrice,
                                    boolean tradeTaken) {
        if (mlRecordService != null) {
            try {
                mlRecordService.recordPrediction(
                    symbol,
                    BigDecimal.valueOf(mlPrediction),
                    predictedSignal,
                    BigDecimal.valueOf(confidence),
                    BigDecimal.valueOf(williamsR),
                    currentPrice,
                    tradeTaken,
                    null  // orderNo在开仓时才有，这里先传null
                );
                log.debug("[{}] ✅ ML预测已记录: 信号={}, 预测值={}, 置信度={}, 是否交易={}", 
                         STRATEGY_NAME, predictedSignal, String.format("%.2f", mlPrediction), 
                         String.format("%.2f", confidence), tradeTaken);
            } catch (Exception e) {
                log.warn("[{}] ⚠️ ML预测记录失败", STRATEGY_NAME, e);
            }
        }
    }
}
