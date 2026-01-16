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
    
    // 🔥 P0修复: 优化评分阈值,平衡交易频率与信号质量
    private static final int DEFAULT_SCORE_THRESHOLD = 7;  // 从5提高到7
    private static final int CHOPPY_MARKET_THRESHOLD = 7;  // 从9降低到7,避免过度过滤
    
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
            // 🔥 P0修复: 计算所有指标(包括ADX趋势方向)
            Double williamsR = williamsCalculator.calculate(context.getKlines());
            ADXCalculator.ADXResult adxResult = adxCalculator.calculateWithDirection(context.getKlines());
            CandlePattern pattern = candlePatternAnalyzer.calculate(context.getKlines());
            
            if (williamsR == null || adxResult == null) {
                log.warn("[{}] 指标计算失败", STRATEGY_NAME);
                return createHoldSignal("指标计算失败", 0, 0);
            }
            
            double adx = adxResult.getAdx();
            ADXCalculator.TrendDirection trendDirection = adxResult.getTrendDirection();
            
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
            
            log.debug("[{}] Williams: {}, ADX: {} (趋势:{}), ML: {}, 动量: {}, K线形态: {}", 
                    STRATEGY_NAME, williamsR, adx, trendDirection, String.format("%.2f", mlPrediction), momentum, 
                    pattern.hasPattern() ? pattern.getType() : "无");
            
            // 评分系统
            int buyScore = 0;
            int sellScore = 0;
            
            // 🔥 P1修复: 渐进式评分阈值（避免临界值跳变）
            int requiredScore = DEFAULT_SCORE_THRESHOLD;
            if (adx < 15) {
                // 🔥 修复: 极弱趋势显著提高评分要求（而非完全禁止）
                requiredScore = 12;  // 提高到12分（正常是7分）
                log.debug("[{}] ADX={}, 极弱趋势，提高评分要求至{}分", STRATEGY_NAME, adx, requiredScore);
            } else if (adx < 17) {
                // 弱趋势初期（15-17）：适度提高门槛
                requiredScore = 7;  // 使用默认阈值
                log.debug("[{}] ADX={}, 弱趋势初期，评分要求{}分", STRATEGY_NAME, adx, requiredScore);
            } else if (adx < 20) {
                // 弱趋势后期（17-20）：进一步提高门槛
                requiredScore = 8;  // 比默认高1分
                log.debug("[{}] ADX={}, 弱趋势后期，提高评分要求至{}分", STRATEGY_NAME, adx, requiredScore);
            } else if (adx > 30) {
                // 🔥 P0修复: 强趋势市场，根据趋势方向加分
                if (trendDirection == ADXCalculator.TrendDirection.UP) {
                    buyScore += 3;  // 上升趋势，做多加分
                    log.debug("[{}] ADX={}, 强上升趋势 → 做多+3分", STRATEGY_NAME, adx);
                } else if (trendDirection == ADXCalculator.TrendDirection.DOWN) {
                    sellScore += 3;  // 下降趋势，做空加分
                    log.debug("[{}] ADX={}, 强下降趋势 → 做空+3分", STRATEGY_NAME, adx);
                } else {
                    // 中性趋势，根据动量加分
                    if (momentum.compareTo(BigDecimal.ZERO) > 0) {
                        buyScore += 1;
                        log.debug("[{}] ADX={}, 中性趋势 + 上涨动量 → +1分", STRATEGY_NAME, adx);
                    } else if (momentum.compareTo(BigDecimal.ZERO) < 0) {
                        sellScore += 1;
                        log.debug("[{}] ADX={}, 中性趋势 + 下跌动量 → +1分", STRATEGY_NAME, adx);
                    }
                }
            }
            
            // 🔥 P1修复: 趋势方向过滤 - 降低惩罚力度（从+5降为+3）
            if (adx > 25) {  // 提高到25，避免过早触发
                if (trendDirection == ADXCalculator.TrendDirection.DOWN) {
                    // 下降趋势，提高做多门槛（从+5降为+3）
                    int originalRequired = requiredScore;
                    requiredScore = requiredScore + 3;
                    log.warn("[{}] ⚠️ 下降趋势市场(ADX={}, +DI<-DI),做多需要更高评分({}→{}分)", 
                            STRATEGY_NAME, adx, originalRequired, requiredScore);
                } else if (trendDirection == ADXCalculator.TrendDirection.UP) {
                    // 上升趋势，提高做空门槛（从+5降为+3）
                    int originalRequired = requiredScore;
                    requiredScore = requiredScore + 3;
                    log.info("[{}] 📈 上升趋势市场(ADX={}, +DI>-DI),做空需要更高评分({}→{}分)", 
                            STRATEGY_NAME, adx, originalRequired, requiredScore);
                }
            }
            
            // 🔥 P0修复: Williams评分（权重5）- 优化区间划分,提高超卖区域权重
            if (williamsR < -70) {
                // 深度超卖,安全做多
                buyScore += 5;
                log.debug("[{}] Williams深度超卖 ({}) → +5分", STRATEGY_NAME, williamsR);
            } else if (williamsR < -65) {
                // 次深度超卖,较安全做多
                buyScore += 4;
                log.debug("[{}] Williams次深度超卖 ({}) → +4分", STRATEGY_NAME, williamsR);
            } else if (williamsR < -60) {
                // 中度超卖,适度做多
                buyScore += 3;
                log.debug("[{}] Williams中度超卖 ({}) → +3分", STRATEGY_NAME, williamsR);
            } else if (williamsR > -30) {
                // 超买,可以做空
                sellScore += 5;
                log.debug("[{}] Williams超买 ({}) → +5分", STRATEGY_NAME, williamsR);
            } else if (williamsR > -35) {
                // 次超买,较安全做空
                sellScore += 4;
                log.debug("[{}] Williams次超买 ({}) → +4分", STRATEGY_NAME, williamsR);
            } else if (williamsR > -40) {
                // 轻度超买
                sellScore += 3;
                log.debug("[{}] Williams轻度超买 ({}) → +3分", STRATEGY_NAME, williamsR);
            }
            // 🔥 P0修复: Williams在-40到-60之间属于中性区,不给分
            
            // RSI评分 - 🔥 已删除: 与Williams重复,删除后Williams权重从3提高到5
            // if (rsi < 45) {
            //     buyScore += 2;
            //     log.debug("[{}] RSI偏低 ({}) → +2分", STRATEGY_NAME, rsi);
            // } else if (rsi > 55) {
            //     sellScore += 2;
            //     log.debug("[{}] RSI偏高 ({}) → +2分", STRATEGY_NAME, rsi);
            // }
            
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
                        String.format("均衡激进策略做多 (评分:%d, Williams:%.1f, 形态:%s)", 
                                buyScore, williamsR, pattern.getType().getDescription()) :
                        String.format("均衡激进策略做多 (评分:%d, Williams:%.1f)", 
                                buyScore, williamsR);
                
                // 🔥 修复：不在这里记录ML，应该在实际开仓时记录
                // recordMLPrediction(context.getSymbol(), mlPrediction, "BUY", mlConfidence, 
                //                    williamsR, context.getCurrentPrice(), true);
                
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
                        String.format("均衡激进策略做空 (评分:%d, Williams:%.1f, 形态:%s)", 
                                sellScore, williamsR, pattern.getType().getDescription()) :
                        String.format("均衡激进策略做空 (评分:%d, Williams:%.1f)", 
                                sellScore, williamsR);
                
                // 🔥 修复：不在这里记录ML，应该在实际开仓时记录
                // recordMLPrediction(context.getSymbol(), mlPrediction, "SELL", mlConfidence, 
                //                    williamsR, context.getCurrentPrice(), true);
                
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
     * 🔥 修复：降低倍数，提高信号区分度
     * 
     * @param score 实际得分
     * @param threshold 门槛分数
     * @return 信号强度（0-100）
     */
    private int calculateSignalStrength(int score, int threshold) {
        // 基础强度：超过门槛的分数 * 5（从10降到5）
        int baseStrength = (score - threshold) * 5;
        
        // 额外强度：根据总分数 * 3（从5降到3）
        int bonusStrength = score * 3;
        
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
     * 🔥 注意：此方法已弃用，应该在实际开仓时才记录ML预测
     */
    @Deprecated
    private void recordMLPrediction(String symbol, double mlPrediction, String predictedSignal,
                                    double confidence, double williamsR, BigDecimal currentPrice,
                                    boolean tradeTaken) {
        // 🔥 已禁用：不应该在信号生成时记录，应该在实际开仓时记录
        // if (mlRecordService != null) {
        //     try {
        //         mlRecordService.recordPrediction(
        //             symbol,
        //             BigDecimal.valueOf(mlPrediction),
        //             predictedSignal,
        //             BigDecimal.valueOf(confidence),
        //             BigDecimal.valueOf(williamsR),
        //             currentPrice,
        //             tradeTaken,
        //             null  // orderNo在开仓时才有，这里先传null
        //         );
        //         log.debug("[{}] ✅ ML预测已记录: 信号={}, 预测值={}, 置信度={}, 是否交易={}", 
        //                  STRATEGY_NAME, predictedSignal, String.format("%.2f", mlPrediction), 
        //                  String.format("%.2f", confidence), tradeTaken);
        //     } catch (Exception e) {
        //         log.warn("[{}] ⚠️ ML预测记录失败", STRATEGY_NAME, e);
        //     }
        // }
    }
}
