package com.ltp.peter.augtrade.strategy.core;


import com.ltp.peter.augtrade.strategy.signal.TradingSignal;

/**
 * 交易策略接口
 * 所有策略必须实现此接口
 * 
 * @author Peter Wang
 */
public interface Strategy {
    
    /**
     * 生成交易信号
     * 
     * @param context 市场上下文（包含K线数据、指标值等）
     * @return 交易信号
     */
    TradingSignal generateSignal(MarketContext context);
    
    /**
     * 获取策略名称
     * 
     * @return 策略名称
     */
    String getName();
    
    /**
     * 获取策略权重
     * 用于组合策略时的加权计算
     * 
     * @return 权重值（1-10）
     */
    int getWeight();
    
    /**
     * 获取策略描述
     * 
     * @return 策略描述
     */
    default String getDescription() {
        return getName() + " Strategy";
    }
    
    /**
     * 策略是否启用
     * 
     * @return 是否启用
     */
    default boolean isEnabled() {
        return true;
    }
}
