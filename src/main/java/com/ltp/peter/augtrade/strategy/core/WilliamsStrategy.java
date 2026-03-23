package com.ltp.peter.augtrade.strategy.core;

import com.ltp.peter.augtrade.indicator.WilliamsRCalculator;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
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
    // 🔥 P0修复-20260209: 权重从8降至4
    // 数据分析：信号强度越高（WR贡献大）的订单亏损越大，信号强度24的4笔反而赚$473
    // WR超卖做多与布林带上轨追高叠加，导致系统性亏损
    private static final int STRATEGY_WEIGHT = 4;
    
    // 🔥 P0修复-20260209: Williams %R 阈值 - 进一步严格化
    // 数据：WR<-80（超卖区）做多的9笔中，多数是在价格已经突破布林上轨后入场
    // 这说明WR在高波动市场中频繁给出假超卖信号
    private static final double STRONG_OVERSOLD = -90.0;  // 🔥 从-85提高到-90，只在极端超卖时做多
    private static final double OVERSOLD = -80.0;         // 🔥 从-70提高到-80，进一步收紧
    private static final double OVERBOUGHT = -30.0;       // 🔥 从-35收紧到-30
    private static final double STRONG_OVERBOUGHT = -15.0; // 🔥 从-20收紧到-15
    
    // 重构v3：Williams不再参与投票，WR极值判断已内化到 CompositeStrategy Layer3
    private boolean enabled = false;

    @Autowired
    private WilliamsRCalculator williamsCalculator;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        try {
            // 优先从Context读取（StrategyOrchestrator已计算过，避免重复计算）
            Double williamsR = context.getIndicator("WilliamsR");
            if (williamsR == null) {
                williamsR = williamsCalculator.calculate(context.getKlines());
            }
            
            if (williamsR == null) {
                log.warn("[{}] Williams %R 计算结果为空", STRATEGY_NAME);
                return createHoldSignal("指标计算失败");
            }
            
            // 获取EMA趋势信息（用于过滤逆势信号）
            com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend emaTrend = 
                    context.getIndicator("EMATrend", com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend.class);
            
            log.debug("[{}] Williams %R = {}", STRATEGY_NAME, williamsR);
            
            // 强烈超卖区域（< -80）
            if (williamsR < STRONG_OVERSOLD) {
                // ✨ 趋势过滤：下跌趋势中不做多
                if (emaTrend != null && emaTrend.isDownTrend()) {
                    log.debug("[{}] Williams强烈超卖但处于下跌趋势，观望 (Williams={})", STRATEGY_NAME, String.format("%.2f", williamsR));
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
                    log.debug("[{}] Williams超卖但处于下跌趋势，观望 (Williams={})", STRATEGY_NAME, String.format("%.2f", williamsR));
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
                    log.debug("[{}] Williams强烈超买但处于上涨趋势，观望 (Williams={})", STRATEGY_NAME, String.format("%.2f", williamsR));
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
                    log.debug("[{}] Williams超买但处于上涨趋势，观望 (Williams={})", STRATEGY_NAME, String.format("%.2f", williamsR));
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
    public String getName() { return STRATEGY_NAME; }

    @Override
    public int getWeight() { return STRATEGY_WEIGHT; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public String getDescription() {
        return "Williams %R — 重构v3已禁用投票，WR极值判断内化至CompositeStrategy Layer3";
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
