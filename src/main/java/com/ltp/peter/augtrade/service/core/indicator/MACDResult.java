package com.ltp.peter.augtrade.service.core.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MACD指标结果
 * 
 * MACD (Moving Average Convergence Divergence) 由三部分组成：
 * - MACD线：快速EMA - 慢速EMA
 * - 信号线：MACD线的EMA
 * - 柱状图：MACD线 - 信号线
 * 
 * @author Peter Wang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MACDResult {
    
    /**
     * MACD线值
     * 快速EMA(12) - 慢速EMA(26)
     */
    private Double macdLine;
    
    /**
     * 信号线值
     * MACD线的9期EMA
     */
    private Double signalLine;
    
    /**
     * 柱状图值
     * MACD线 - 信号线
     */
    private Double histogram;
    
    /**
     * 是否金叉（MACD线上穿信号线）
     * 做多信号
     */
    public boolean isBullishCrossover(MACDResult previous) {
        if (previous == null || this.macdLine == null || this.signalLine == null) {
            return false;
        }
        if (previous.getMacdLine() == null || previous.getSignalLine() == null) {
            return false;
        }
        
        // 当前MACD线大于信号线，且之前MACD线小于信号线
        return this.macdLine > this.signalLine && previous.getMacdLine() <= previous.getSignalLine();
    }
    
    /**
     * 是否死叉（MACD线下穿信号线）
     * 做空信号
     */
    public boolean isBearishCrossover(MACDResult previous) {
        if (previous == null || this.macdLine == null || this.signalLine == null) {
            return false;
        }
        if (previous.getMacdLine() == null || previous.getSignalLine() == null) {
            return false;
        }
        
        // 当前MACD线小于信号线，且之前MACD线大于信号线
        return this.macdLine < this.signalLine && previous.getMacdLine() >= previous.getSignalLine();
    }
    
    /**
     * 是否多头排列（MACD线和柱状图都为正）
     */
    public boolean isBullish() {
        return macdLine != null && signalLine != null && 
               macdLine > 0 && macdLine > signalLine;
    }
    
    /**
     * 是否空头排列（MACD线和柱状图都为负）
     */
    public boolean isBearish() {
        return macdLine != null && signalLine != null && 
               macdLine < 0 && macdLine < signalLine;
    }
    
    /**
     * 获取信号强度（0-100）
     */
    public int getSignalStrength() {
        if (histogram == null) {
            return 0;
        }
        
        // 根据柱状图的绝对值判断强度
        double absHistogram = Math.abs(histogram);
        if (absHistogram > 100) {
            return 100;
        }
        
        return (int) (absHistogram * 0.5); // 简化的强度计算
    }
}
