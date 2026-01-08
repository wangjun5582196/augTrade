package com.ltp.peter.augtrade.service.core.indicator;

import com.ltp.peter.augtrade.entity.Kline;

import java.util.List;

/**
 * 技术指标接口
 * 所有技术指标计算器必须实现此接口
 * 
 * @param <T> 指标值类型
 * @author Peter Wang
 */
public interface TechnicalIndicator<T> {
    
    /**
     * 计算指标值
     * 
     * @param klines K线数据列表
     * @return 指标值
     */
    T calculate(List<Kline> klines);
    
    /**
     * 获取指标名称
     * 
     * @return 指标名称（如：RSI、MACD、ADX）
     */
    String getName();
    
    /**
     * 获取所需的K线数量
     * 例如：计算14期RSI需要至少14根K线
     * 
     * @return 所需K线数量
     */
    int getRequiredPeriods();
    
    /**
     * 获取指标描述
     * 
     * @return 指标描述
     */
    default String getDescription() {
        return getName() + " Technical Indicator";
    }
    
    /**
     * 验证K线数据是否足够
     * 
     * @param klines K线数据
     * @return 是否足够
     */
    default boolean hasEnoughData(List<Kline> klines) {
        return klines != null && klines.size() >= getRequiredPeriods();
    }
}
