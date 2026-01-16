package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.core.indicator.BollingerBands;
import com.ltp.peter.augtrade.service.core.indicator.CandlePattern;
import com.ltp.peter.augtrade.service.core.indicator.EMACalculator;
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 组合策略
 * 
 * 整合多个子策略的信号，通过加权投票生成最终交易信号：
 * - 收集所有子策略的信号
 * - 根据策略权重计算做多/做空得分
 * - 得分最高且达到阈值时生成信号
 * 
 * 决策规则：
 * - 做多得分 >= 15 且 > 做空得分：生成做多信号
 * - 做空得分 >= 15 且 > 做多得分：生成做空信号
 * - 其他情况：观望
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class CompositeStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "Composite";
    private static final int STRATEGY_WEIGHT = 10;
    private static final int SIGNAL_THRESHOLD = 6; // 🔥 降低阈值：从15→6，允许单个策略（如BollingerBreakout权重6）触发信号
    
    @Autowired(required = false)
    private List<Strategy> strategies;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足", 0, 0);
        }
        
        if (strategies == null || strategies.isEmpty()) {
            log.warn("[{}] 没有可用的子策略", STRATEGY_NAME);
            return createHoldSignal("无子策略", 0, 0);
        }
        
        try {
            int buyScore = 0;
            int sellScore = 0;
            List<String> buyReasons = new ArrayList<>();
            List<String> sellReasons = new ArrayList<>();
            
            // 过滤掉自己，避免递归调用
            List<Strategy> activeStrategies = strategies.stream()
                    .filter(s -> !s.getName().equals(STRATEGY_NAME))
                    .filter(Strategy::isEnabled)
                    .collect(Collectors.toList());
            
            log.debug("[{}] 共有 {} 个活跃策略", STRATEGY_NAME, activeStrategies.size());
            
            // 收集所有子策略的信号
            for (Strategy strategy : activeStrategies) {
                try {
                    TradingSignal signal = strategy.generateSignal(context);
                    
                    if (signal == null || signal.isHold()) {
                        log.debug("[{}] {} 策略返回观望信号", STRATEGY_NAME, strategy.getName());
                        continue;
                    }
                    
                    int weight = strategy.getWeight();
                    
                    if (signal.isBuy()) {
                        buyScore += weight;
                        buyReasons.add(String.format("%s(权重%d)", strategy.getName(), weight));
                        log.debug("[{}] {} 策略建议做多，权重: {}", STRATEGY_NAME, strategy.getName(), weight);
                    } else if (signal.isSell()) {
                        sellScore += weight;
                        sellReasons.add(String.format("%s(权重%d)", strategy.getName(), weight));
                        log.debug("[{}] {} 策略建议做空，权重: {}", STRATEGY_NAME, strategy.getName(), weight);
                    }
                    
                } catch (Exception e) {
                    log.error("[{}] {} 策略执行失败", STRATEGY_NAME, strategy.getName(), e);
                }
            }
            
            log.info("[{}] 综合评分 - 做多: {}, 做空: {}", STRATEGY_NAME, buyScore, sellScore);
            
            // 🔥 P0修复: 震荡市和弱趋势市场过滤 - 改为提高门槛而非完全禁止
            Double adx = context.getIndicator("ADX");
            if (adx != null) {
                if (adx < 15) {
                    // 极弱趋势(ADX<15): 显著提高阈值（而非完全禁止）
                    int veryWeakTrendThreshold = 12;  // 需要12分以上（比正常的6分高一倍）
                    if (buyScore < veryWeakTrendThreshold && sellScore < veryWeakTrendThreshold) {
                        log.warn("[{}] ⛔ 极弱趋势市场(ADX={}),信号强度不足(需要≥{}分)", 
                                STRATEGY_NAME, String.format("%.2f", adx), veryWeakTrendThreshold);
                        return createHoldSignal(String.format("极弱趋势需要超强信号(ADX=%.2f, 需要≥%d分)", 
                                adx, veryWeakTrendThreshold), buyScore, sellScore);
                    }
                    log.info("[{}] ⚡ 极弱趋势市场(ADX={}),但信号足够强(做多:{}, 做空:{})，允许交易", 
                            STRATEGY_NAME, String.format("%.2f", adx), buyScore, sellScore);
                } else if (adx >= 15 && adx < 25) {
                    // 弱趋势(ADX 15-25): 适度提高阈值
                    int weakTrendThreshold = 9;  // 需要9分以上
                    if (buyScore < weakTrendThreshold && sellScore < weakTrendThreshold) {
                        log.warn("[{}] ⏸️ 弱趋势市场(ADX={}),信号强度不足(需要≥{}分)", 
                                STRATEGY_NAME, String.format("%.2f", adx), weakTrendThreshold);
                        return createHoldSignal(String.format("弱趋势需要更强信号(ADX=%.2f, 需要≥%d分)", 
                                adx, weakTrendThreshold), buyScore, sellScore);
                    }
                    log.info("[{}] ⚡ 弱趋势市场(ADX={}),但信号足够强(做多:{}, 做空:{})", 
                            STRATEGY_NAME, String.format("%.2f", adx), buyScore, sellScore);
                }
            }
            
            // 根据得分生成信号
            if (buyScore >= SIGNAL_THRESHOLD && buyScore > sellScore) {
                // 🔥 P0修复: 生成做多信号前进行过滤检查
                TradingSignal buySignal = TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(calculateSignalStrength(buyScore, sellScore))
                        .score(buyScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做多 (得分:%d) [%s]", 
                                buyScore, String.join(", ", buyReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
                
                // 价格位置过滤
                if (!validatePricePosition(context, buySignal)) {
                    return createHoldSignal("价格位置不合理,做多信号被过滤", buyScore, sellScore);
                }
                
                // K线形态检查
                if (!validateCandlePattern(context, TradingSignal.SignalType.BUY)) {
                    return createHoldSignal("K线形态不支持做多", buyScore, sellScore);
                }
                
                return buySignal;
            }
            
            if (sellScore >= SIGNAL_THRESHOLD && sellScore > buyScore) {
                // 🔥 P0修复: 生成做空信号前进行过滤检查
                TradingSignal sellSignal = TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(calculateSignalStrength(sellScore, buyScore))
                        .score(sellScore)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("综合策略做空 (得分:%d) [%s]", 
                                sellScore, String.join(", ", sellReasons)))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
                
                // 价格位置过滤
                if (!validatePricePosition(context, sellSignal)) {
                    return createHoldSignal("价格位置不合理,做空信号被过滤", buyScore, sellScore);
                }
                
                // K线形态检查
                if (!validateCandlePattern(context, TradingSignal.SignalType.SELL)) {
                    return createHoldSignal("K线形态不支持做空", buyScore, sellScore);
                }
                
                return sellSignal;
            }
            
            // 得分不足或冲突，观望
            String reason;
            if (buyScore == 0 && sellScore == 0) {
                reason = "所有策略均为中性";
            } else if (buyScore < SIGNAL_THRESHOLD && sellScore < SIGNAL_THRESHOLD) {
                reason = String.format("信号强度不足 (做多:%d, 做空:%d, 阈值:%d)", 
                        buyScore, sellScore, SIGNAL_THRESHOLD);
            } else {
                reason = String.format("信号冲突 (做多:%d, 做空:%d)", buyScore, sellScore);
            }
            
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
        return "组合策略 - 整合多个子策略信号的加权投票系统";
    }
    
    /**
     * 计算信号强度
     * 基于主导得分和得分差距
     * 
     * ✨ 优化：提高倍数（×2→×3）和上限（70→80），确保TrendFilter单独信号也能达到开仓阈值
     */
    private int calculateSignalStrength(int dominantScore, int oppositeScore) {
        // 得分差距
        int scoreDiff = dominantScore - oppositeScore;
        
        // 基础强度（根据主导得分）- 提高倍数和上限
        int baseStrength = Math.min(dominantScore * 3, 80);  // 从×2改为×3，从70改为80
        
        // 额外强度（根据得分差距）
        int bonusStrength = Math.min(scoreDiff, 30);
        
        return Math.min(baseStrength + bonusStrength, 100);
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
     * 获取活跃的子策略列表
     */
    public List<Strategy> getActiveStrategies() {
        if (strategies == null) {
            return new ArrayList<>();
        }
        
        return strategies.stream()
                .filter(s -> !s.getName().equals(STRATEGY_NAME))
                .filter(Strategy::isEnabled)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取子策略总权重
     */
    public int getTotalWeight() {
        return getActiveStrategies().stream()
                .mapToInt(Strategy::getWeight)
                .sum();
    }
    
    /**
     * 🔥 P0修复: 验证价格位置是否合理
     * 
     * 规则:
     * - 做多: 价格不能高于布林上轨
     * - 做空: 价格不能低于布林下轨
     * - 如果无布林带,使用EMA判断(价格偏离EMA20不超过0.5%)
     */
    private boolean validatePricePosition(MarketContext context, TradingSignal signal) {
        if (signal.getType() == TradingSignal.SignalType.HOLD) {
            return true;
        }
        
        BollingerBands bb = context.getIndicator("BollingerBands");
        BigDecimal price = context.getCurrentPrice();
        
        // 如果没有布林带,使用EMA判断
        if (bb == null) {
            EMACalculator.EMATrend trend = context.getIndicator("EMATrend");
            if (trend == null) {
                log.warn("[{}] 无布林带和EMA数据,跳过价格位置检查", STRATEGY_NAME);
                return true;
            }
            
            double priceVal = price.doubleValue();
            double emaShort = trend.getEmaShort();  // 使用emaShort (EMA20)
            
            if (signal.getType() == TradingSignal.SignalType.BUY) {
                // 做多: 价格不能高于EMA20超过0.5%
                if (priceVal > emaShort * 1.005) {
                    log.warn("[{}] ⛔ BUY信号被过滤: 价格{}高于EMA20 {}超过0.5%", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), String.format("%.2f", emaShort));
                    return false;
                }
            } else if (signal.getType() == TradingSignal.SignalType.SELL) {
                // 做空: 价格不能低于EMA20超过0.5%
                if (priceVal < emaShort * 0.995) {
                    log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于EMA20 {}超过0.5%", 
                            STRATEGY_NAME, String.format("%.2f", priceVal), String.format("%.2f", emaShort));
                    return false;
                }
            }
            return true;
        }
        
        // 有布林带,严格检查
        Double upper = bb.getUpper();
        Double lower = bb.getLower();
        Double middle = bb.getMiddle();
        double priceVal = price.doubleValue();
        
        if (signal.getType() == TradingSignal.SignalType.BUY) {
            // 做多: 价格不能高于布林上轨
            if (priceVal > upper) {
                double exceeds = priceVal - upper;
                log.warn("[{}] ⛔ BUY信号被过滤: 价格{}高于布林上轨{} (+{} USD)", 
                        STRATEGY_NAME, 
                        String.format("%.2f", priceVal), 
                        String.format("%.2f", upper), 
                        String.format("%.2f", exceeds));
                return false;
            }
        } else if (signal.getType() == TradingSignal.SignalType.SELL) {
            // 做空: 价格不能低于布林下轨
            if (priceVal < lower) {
                double below = lower - priceVal;
                log.warn("[{}] ⛔ SELL信号被过滤: 价格{}低于布林下轨{} (-{} USD)", 
                        STRATEGY_NAME, 
                        String.format("%.2f", priceVal), 
                        String.format("%.2f", lower), 
                        String.format("%.2f", below));
                return false;
            }
        }
        
        log.debug("[{}] ✅ 价格位置检查通过: 价格{} 在布林带[{}, {}]内", 
                STRATEGY_NAME, 
                String.format("%.2f", priceVal), 
                String.format("%.2f", lower), 
                String.format("%.2f", upper));
        return true;
    }
    
    /**
     * 🔥 P0修复: 验证K线形态是否支持交易
     * 
     * 规则:
     * - DOJI等不确定形态: 不交易
     * - 形态方向与信号方向相反: 不交易
     */
    private boolean validateCandlePattern(MarketContext context, TradingSignal.SignalType signalType) {
        CandlePattern pattern = context.getIndicator("CandlePattern");
        if (pattern == null || !pattern.hasPattern()) {
            return true; // 无形态,不限制
        }
        
        CandlePattern.Direction pDir = pattern.getDirection();
        
        // DOJI等不确定形态: 不交易
        if (pDir == CandlePattern.Direction.NEUTRAL) {
            log.warn("[{}] ⏸️ 不确定K线形态{},暂停交易", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        // 方向相反: 不交易
        if (signalType == TradingSignal.SignalType.BUY && 
            pDir == CandlePattern.Direction.BEARISH) {
            log.warn("[{}] ⛔ BUY信号与看跌形态{}矛盾", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        if (signalType == TradingSignal.SignalType.SELL && 
            pDir == CandlePattern.Direction.BULLISH) {
            log.warn("[{}] ⛔ SELL信号与看涨形态{}矛盾", STRATEGY_NAME, pattern.getType().getDescription());
            return false;
        }
        
        log.debug("[{}] ✅ K线形态检查通过: {}方向{}", 
                STRATEGY_NAME, pattern.getType().getDescription(), pDir.getDescription());
        return true;
    }
}
