package com.ltp.peter.augtrade.service.core.strategy;

import com.ltp.peter.augtrade.service.core.indicator.RSICalculator;
import com.ltp.peter.augtrade.service.core.signal.TradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * RSI (Relative Strength Index) 策略
 * 
 * 使用RSI指标判断超买超卖：
 * - RSI < 20: 强烈超卖，做多信号（强度95）
 * - RSI < 30: 超卖，做多信号（强度75）
 * - RSI > 80: 强烈超买，做空信号（强度95）
 * - RSI > 70: 超买，做空信号（强度75）
 * - 30 <= RSI <= 70: 中性区域，观望
 * 
 * RSI策略特点：
 * - 权重较高（9），因为RSI是最可靠的动量指标之一
 * - 使用更严格的超买超卖阈值（20/80）来捕捉极端情况
 * - 传统阈值（30/70）仍然有效但强度较低
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class RSIStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "RSI";
    private static final int STRATEGY_WEIGHT = 9;
    
    // RSI 阈值
    private static final double EXTREME_OVERSOLD = 20.0;
    private static final double OVERSOLD = 30.0;
    private static final double OVERBOUGHT = 70.0;
    private static final double EXTREME_OVERBOUGHT = 80.0;
    
    @Autowired
    private RSICalculator rsiCalculator;
    
    @Override
    public TradingSignal generateSignal(MarketContext context) {
        if (context == null || context.getKlines() == null || context.getKlines().isEmpty()) {
            log.warn("[{}] 市场上下文为空或无K线数据", STRATEGY_NAME);
            return createHoldSignal("数据不足");
        }
        
        try {
            // 计算RSI
            Double rsi = rsiCalculator.calculate(context.getKlines());
            
            if (rsi == null) {
                log.warn("[{}] RSI 计算结果为空", STRATEGY_NAME);
                return createHoldSignal("指标计算失败");
            }
            
            log.debug("[{}] RSI = {}", STRATEGY_NAME, rsi);
            
            // 极度超卖区域（< 20）
            if (rsi < EXTREME_OVERSOLD) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(95)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("RSI极度超卖 (%.2f)", rsi))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 超卖区域（< 30）
            if (rsi < OVERSOLD) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.BUY)
                        .strength(75)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("RSI超卖 (%.2f)", rsi))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 极度超买区域（> 80）
            if (rsi > EXTREME_OVERBOUGHT) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(95)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("RSI极度超买 (%.2f)", rsi))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 超买区域（> 70）
            if (rsi > OVERBOUGHT) {
                return TradingSignal.builder()
                        .type(TradingSignal.SignalType.SELL)
                        .strength(75)
                        .strategyName(STRATEGY_NAME)
                        .reason(String.format("RSI超买 (%.2f)", rsi))
                        .symbol(context.getSymbol())
                        .currentPrice(context.getCurrentPrice())
                        .build();
            }
            
            // 中性区域（30-70），观望
            return createHoldSignal(String.format("RSI中性 (%.2f)", rsi));
            
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
        return "RSI 相对强弱指数策略 - 识别超买超卖状态";
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
