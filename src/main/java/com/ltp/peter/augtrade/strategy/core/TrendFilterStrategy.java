package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.indicator.EMACalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 趋势过滤器策略
 * 
 * 核心逻辑：根据EMA识别市场趋势，只做顺势交易
 * - 上升趋势（价格 > EMA20 > EMA50）：只做多，禁止做空
 * - 下降趋势（价格 < EMA20 < EMA50）：只做空，禁止做多  
 * - 震荡整理：观望或使用其他策略
 * 
 * 这是解决"在上涨趋势中持续做空"问题的关键策略！
 * 
 * 权重设置：20（最高优先级）
 * - 强趋势时，此策略会主导交易方向
 * - 确保系统不会在上涨趋势中做空，或在下跌趋势中做多
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class TrendFilterStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "TrendFilter";
    private static final int STRATEGY_WEIGHT = 12;  // ✅ 从20降低到12，避免单一策略主导
    private static final int SHORT_PERIOD = 20;
    private static final int LONG_PERIOD = 50;
    private static final int MIN_TREND_STRENGTH = 15;  // 最小趋势强度
    
    @Autowired
    private EMACalculator emaCalculator;
    
    @Autowired
    private MarketRegimeDetector regimeDetector;
    
    // 重构v3：TrendFilter不再参与投票，趋势判断已内化到 CompositeStrategy Layer2
    private boolean enabled = false;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        try {
            // ✅ 市场环境检测（优化版）
            MarketRegimeDetector.MarketRegime regime = regimeDetector.detectRegime(context.getKlines());
            
            // 震荡市和盘整市，TrendFilter不适用（EMA信号不可靠）
            if (regime == MarketRegimeDetector.MarketRegime.CHOPPY || 
                regime == MarketRegimeDetector.MarketRegime.CONSOLIDATION) {
                log.warn("[{}] ⚠️ {}市场，EMA趋势策略不适用，交给震荡市策略处理", 
                        STRATEGY_NAME, regime.getDescription());
                return createHoldSignal(String.format("%s市场，EMA不可靠", regime.getDescription()));
            }
            
            // 只在趋势市（强趋势/弱趋势）使用EMA策略
            log.info("[{}] ✅ {}市场，EMA趋势策略适用", STRATEGY_NAME, regime.getDescription());
            
            // 计算EMA趋势
            EMACalculator.EMATrend trend = emaCalculator.calculateTrend(
                    context.getKlines(), SHORT_PERIOD, LONG_PERIOD);
            
            if (trend == null) {
                log.warn("[{}] 无法计算EMA趋势", STRATEGY_NAME);
                return createHoldSignal("EMA计算失败");
            }
            
            // 将EMA趋势添加到上下文（供其他策略使用）
            context.addIndicator("EMATrend", trend);
            
            int trendStrength = trend.getTrendStrength();
            String trendDesc = trend.getTrendDescription();
            
            log.info("[{}] {}, 强度: {}", STRATEGY_NAME, trendDesc, trendStrength);
            
            // ✨ 优化1: 震荡市识别 - EMA距离太近说明震荡
            double emaDistance = Math.abs(trend.getEmaShort() - trend.getEmaLong());
            double emaDistancePercent = (emaDistance / trend.getEmaLong()) * 100;
            
            if (emaDistancePercent < 0.15 && trendStrength < 30) {
                // EMA距离<0.15%且趋势强度<30，判定为震荡市
                log.info("[{}] ⚠️ 震荡市（EMA距离{}%, 强度{}），降低开仓",
                        STRATEGY_NAME, String.format("%.2f", emaDistancePercent), trendStrength);
                return createHoldSignal(String.format("震荡市（EMA距离%.2f%%）", emaDistancePercent));
            }
            
            // 判断趋势并生成信号
            if (trend.isUpTrend()) {
                // 上升趋势：强烈建议做多
                if (trendStrength >= MIN_TREND_STRENGTH) {
                    // ✨ 优化2: 高位过滤 - 避免在最高点附近开仓
                    double recentHigh = context.getKlines().stream()
                            .limit(20)  // 最近20根K线
                            .mapToDouble(k -> k.getHighPrice().doubleValue())
                            .max()
                            .orElse(0);
                    
                    double currentPrice = context.getCurrentPrice().doubleValue();
                    
                    // 如果当前价格接近最近高点（>99%），降低信号强度
                    if (currentPrice > recentHigh * 0.99) {
                        int reducedStrength = Math.min(70 + trendStrength / 2, 95) - 20;  // 降低20强度
                        log.info("[{}] ⚠️ 价格接近高点（${} vs ${}），降低强度至{}", 
                                STRATEGY_NAME, currentPrice, recentHigh, reducedStrength);
                        
                        if (reducedStrength < 60) {
                            return createHoldSignal(String.format("上升趋势但价格过高（$%.2f接近$%.2f）", 
                                    currentPrice, recentHigh));
                        }
                        
                        return TradingSignal.builder()
                                .type(TradingSignal.SignalType.BUY)
                                .strength(reducedStrength)
                                .score(STRATEGY_WEIGHT)
                                .strategyName(STRATEGY_NAME)
                                .reason(String.format("上升趋势但价格偏高 - %s (强度:%d)", trendDesc, trendStrength))
                                .symbol(context.getSymbol())
                                .currentPrice(context.getCurrentPrice())
                                .build();
                    }
                    
                    // 正常上升趋势信号
                    int strength = Math.min(70 + trendStrength / 2, 95);
                    String reason = String.format("上升趋势 - %s (强度:%d)", trendDesc, trendStrength);
                    
                    log.info("[{}] ✅ 识别到上升趋势，建议做多（权重:{}，强度:{}）", 
                            STRATEGY_NAME, STRATEGY_WEIGHT, strength);
                    
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(reason)
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                } else {
                    log.info("[{}] 上升趋势但强度不足（{}），观望", STRATEGY_NAME, trendStrength);
                    return createHoldSignal("上升趋势但强度不足");
                }
                
            } else if (trend.isDownTrend()) {
                // 下降趋势：强烈建议做空
                if (trendStrength >= MIN_TREND_STRENGTH) {
                    // ✨ 优化3: 低位过滤 - 避免在最低点附近开空仓
                    double recentLow = context.getKlines().stream()
                            .limit(20)  // 最近20根K线
                            .mapToDouble(k -> k.getLowPrice().doubleValue())
                            .min()
                            .orElse(Double.MAX_VALUE);
                    
                    double currentPrice = context.getCurrentPrice().doubleValue();
                    
                    // 如果当前价格接近最近低点（<101%），降低信号强度
                    if (currentPrice < recentLow * 1.01) {
                        int reducedStrength = Math.min(70 + trendStrength / 2, 95) - 20;  // 降低20强度
                        log.info("[{}] ⚠️ 价格接近低点（${} vs ${}），降低强度至{}", 
                                STRATEGY_NAME, currentPrice, recentLow, reducedStrength);
                        
                        if (reducedStrength < 60) {
                            return createHoldSignal(String.format("下降趋势但价格过低（$%.2f接近$%.2f）", 
                                    currentPrice, recentLow));
                        }
                        
                        return TradingSignal.builder()
                                .type(TradingSignal.SignalType.SELL)
                                .strength(reducedStrength)
                                .score(STRATEGY_WEIGHT)
                                .strategyName(STRATEGY_NAME)
                                .reason(String.format("下降趋势但价格偏低 - %s (强度:%d)", trendDesc, trendStrength))
                                .symbol(context.getSymbol())
                                .currentPrice(context.getCurrentPrice())
                                .build();
                    }
                    
                    // 正常下降趋势信号
                    int strength = Math.min(70 + trendStrength / 2, 95);
                    String reason = String.format("下降趋势 - %s (强度:%d)", trendDesc, trendStrength);
                    
                    log.info("[{}] ✅ 识别到下降趋势，建议做空（权重:{}，强度:{}）", 
                            STRATEGY_NAME, STRATEGY_WEIGHT, strength);
                    
                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(reason)
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();
                } else {
                    log.info("[{}] 下降趋势但强度不足（{}），观望", STRATEGY_NAME, trendStrength);
                    return createHoldSignal("下降趋势但强度不足");
                }
                
            } else {
                // 🔥 P0修复-20260316: 震荡时根据EMA交叉方向提供趋势偏好（否决权模式）
                // 问题回顾：之前在EMA死叉+价格反弹时返回HOLD，导致TrendFilter(权重12)弃权
                //   其他策略(如Supertrend=8)单独推动做多，在下跌趋势中逆势开仓亏损$151
                // 修复：EMA死叉时仍投SELL票（降低权重），阻止做多信号通过

                if (trend.isBearishCross()) {
                    // EMA死叉：中期趋势偏空，即使价格暂时在EMA20上方（死猫反弹）
                    int strength = Math.min(50 + trendStrength / 3, 70);
                    String reason = String.format("EMA死叉偏空（价格反弹中）- %s (强度:%d)", trendDesc, trendStrength);

                    log.info("[{}] 📉 EMA死叉，中期偏空，投SELL票防止逆势做多（权重:{}，强度:{}）",
                            STRATEGY_NAME, STRATEGY_WEIGHT, strength);

                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.SELL)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(reason)
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();

                } else if (trend.isBullishCross()) {
                    // EMA金叉：中期趋势偏多，即使价格暂时在EMA20下方（回调）
                    int strength = Math.min(50 + trendStrength / 3, 70);
                    String reason = String.format("EMA金叉偏多（价格回调中）- %s (强度:%d)", trendDesc, trendStrength);

                    log.info("[{}] 📈 EMA金叉，中期偏多，投BUY票防止逆势做空（权重:{}，强度:{}）",
                            STRATEGY_NAME, STRATEGY_WEIGHT, strength);

                    return TradingSignal.builder()
                            .type(TradingSignal.SignalType.BUY)
                            .strength(strength)
                            .score(STRATEGY_WEIGHT)
                            .strategyName(STRATEGY_NAME)
                            .reason(reason)
                            .symbol(context.getSymbol())
                            .currentPrice(context.getCurrentPrice())
                            .build();

                } else {
                    // EMA几乎重合，无方向偏好
                    log.info("[{}] ⚠️ 震荡整理，EMA无交叉偏好，观望", STRATEGY_NAME);
                    return createHoldSignal("震荡整理，无明确趋势");
                }
            }
            
        } catch (Exception e) {
            log.error("[{}] 生成信号时发生错误", STRATEGY_NAME, e);
            return createHoldSignal("策略执行异常");
        }
    }
    
    /**
     * 检查是否应该禁止做空（上升趋势中）
     * 其他策略可以调用此方法进行趋势确认
     */
    public boolean shouldBlockShort(MarketContext context) {
        EMACalculator.EMATrend trend = emaCalculator.calculateTrend(
                context.getKlines(), SHORT_PERIOD, LONG_PERIOD);
        
        if (trend != null && trend.isUpTrend()) {
            log.warn("[{}] ⚠️ 检测到上升趋势，禁止做空！", STRATEGY_NAME);
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查是否应该禁止做多（下降趋势中）
     * 其他策略可以调用此方法进行趋势确认
     */
    public boolean shouldBlockLong(MarketContext context) {
        EMACalculator.EMATrend trend = emaCalculator.calculateTrend(
                context.getKlines(), SHORT_PERIOD, LONG_PERIOD);
        
        if (trend != null && trend.isDownTrend()) {
            log.warn("[{}] ⚠️ 检测到下降趋势，禁止做多！", STRATEGY_NAME);
            return true;
        }
        
        return false;
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
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public String getDescription() {
        return String.format("趋势过滤器 - 基于EMA%d/%d识别趋势，只做顺势交易（权重:%d）", 
                SHORT_PERIOD, LONG_PERIOD, STRATEGY_WEIGHT);
    }
    
    /**
     * 设置启用/禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[{}] 策略{}",  STRATEGY_NAME, enabled ? "已启用" : "已禁用");
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .score(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}
