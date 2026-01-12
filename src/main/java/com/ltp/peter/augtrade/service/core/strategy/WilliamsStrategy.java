package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.core.indicator.WilliamsRCalculator;
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Williams %R 策略
 * 
 * 使用Williams %R指标判断超买超卖：
 * - Williams %R < -80: 强烈超卖，做多信号（强度90）
 * - Williams %R < -60: 超卖，做多信号（强度70）
 * - Williams %R > -20: 强烈超买，做空信号（强度90）
 * - Williams %R > -40: 超买，做空信号（强度70）
 * - 其他：观望
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class WilliamsStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "Williams";
    private static final int STRATEGY_WEIGHT = 8;
    
    // Williams %R 阈值
    private static final double STRONG_OVERSOLD = -80.0;
    private static final double OVERSOLD = -60.0;
    private static final double OVERBOUGHT = -40.0;
    private static final double STRONG_OVERBOUGHT = -20.0;
    
    @Autowired
    private WilliamsRCalculator williamsCalculator;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        try {
            // 计算Williams %R
            Double williamsR = williamsCalculator.calculate(context.getKlines());
            
            if (williamsR == null) {
                log.warn("[{}] Williams %R 计算结果为空", STRATEGY_NAME);
                return createHoldSignal("指标计算失败");
            }
            
            // 获取EMA趋势信息（用于过滤逆势信号）
            com.ltp.peter.augtrade.service.core.indicator.EMACalculator.EMATrend emaTrend = 
                    context.getIndicator("EMATrend", com.ltp.peter.augtrade.service.core.indicator.EMACalculator.EMATrend.class);
            
            log.debug("[{}] Williams %R = {}", STRATEGY_NAME, williamsR);
            
            // 强烈超卖区域（< -80）
            if (williamsR < STRONG_OVERSOLD) {
                // ✨ 趋势过滤：下跌趋势中不做多
                if (emaTrend != null && emaTrend.isDownTrend()) {
                    log.debug("[{}] Williams强烈超卖但处于下跌趋势，观望 (Williams={:.2f})", STRATEGY_NAME, williamsR);
                    return createHoldSignal(String.format("Williams强烈超卖但下跌趋势 (%.2f)", williamsR));
                }
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(90)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("Williams %%R强烈超卖 (%.2f)", williamsR))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 超卖区域（< -60）
            if (williamsR < OVERSOLD) {
                // ✨ 趋势过滤：下跌趋势中不做多
                if (emaTrend != null && emaTrend.isDownTrend()) {
                    log.debug("[{}] Williams超卖但处于下跌趋势，观望 (Williams={:.2f})", STRATEGY_NAME, williamsR);
                    return createHoldSignal(String.format("Williams超卖但下跌趋势 (%.2f)", williamsR));
                }
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(70)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("Williams %%R超卖 (%.2f)", williamsR))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 强烈超买区域（> -20）
            if (williamsR > STRONG_OVERBOUGHT) {
                // ✨ 趋势过滤：上涨趋势中不做空（关键修复！）
                if (emaTrend != null && emaTrend.isUpTrend()) {
                    log.debug("[{}] Williams强烈超买但处于上涨趋势，观望 (Williams={:.2f})", STRATEGY_NAME, williamsR);
                    return createHoldSignal(String.format("Williams强烈超买但上涨趋势 (%.2f)", williamsR));
                }
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(90)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("Williams %%R强烈超买 (%.2f)", williamsR))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 超买区域（> -40）
            if (williamsR > OVERBOUGHT) {
                // ✨ 趋势过滤：上涨趋势中不做空（关键修复！）
                if (emaTrend != null && emaTrend.isUpTrend()) {
                    log.debug("[{}] Williams超买但处于上涨趋势，观望 (Williams={:.2f})", STRATEGY_NAME, williamsR);
                    return createHoldSignal(String.format("Williams超买但上涨趋势 (%.2f)", williamsR));
                }
                
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(70)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("Williams %%R超买 (%.2f)", williamsR))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 中性区域，观望
            return createHoldSignal(String.format("Williams %%R中性 (%.2f)", williamsR));
            
        } catch (Exception e) {
            log.error("[{}] 生成交易信号时发生错误", STRATEGY_NAME, e);
            return createHoldSignal("策略执行异常");
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
        return "Williams %R 动量策略 - 基于超买超卖判断";
    }
    
    /**
     * 创建观望信号
     */
    private TradingSignal createHoldSignal(String reason) {
        return TradingSignal.builder()
                .type(TradingSignal.SignalType.HOLD)
                .strength(0)
                .strategyName(STRATEGY_NAME)
                .reason(reason)
                .build();
    }
}
