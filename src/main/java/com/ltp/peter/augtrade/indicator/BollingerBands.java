package com.ltp.peter.augtrade.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 布林带指标结果
 * 
 * 布林带 (Bollinger Bands) 由三条线组成：
 * - 上轨：中轨 + (标准差 × 倍数)
 * - 中轨：简单移动平均线(SMA)
 * - 下轨：中轨 - (标准差 × 倍数)
 * 
 * @author Peter Wang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BollingerBands {
    
    /**
     * 上轨
     */
    private Double upper;
    
    /**
     * 中轨（移动平均线）
     */
    private Double middle;
    
    /**
     * 下轨
     */
    private Double lower;
    
    /**
     * 带宽（上轨 - 下轨）
     */
    private Double bandwidth;
    
    /**
     * %B指标：价格在布林带中的相对位置
     * %B = (价格 - 下轨) / (上轨 - 下轨)
     * %B > 1: 价格在上轨之上（超买）
     * %B < 0: 价格在下轨之下（超卖）
     */
    private Double percentB;
    
    /**
     * 判断价格是否触及下轨（超卖信号）
     */
    public boolean isPriceTouchingLower(BigDecimal currentPrice) {
        if (currentPrice == null || lower == null) {
            return false;
        }
        double price = currentPrice.doubleValue();
        // 价格在下轨附近（允许1%的误差）
        return price <= lower * 1.01;
    }
    
    /**
     * 判断价格是否触及上轨（超买信号）
     */
    public boolean isPriceTouchingUpper(BigDecimal currentPrice) {
        if (currentPrice == null || upper == null) {
            return false;
        }
        double price = currentPrice.doubleValue();
        // 价格在上轨附近（允许1%的误差）
        return price >= upper * 0.99;
    }
    
    /**
     * 判断价格是否突破下轨（强烈超卖）
     */
    public boolean isPriceBelowLower(BigDecimal currentPrice) {
        if (currentPrice == null || lower == null) {
            return false;
        }
        return currentPrice.doubleValue() < lower;
    }
    
    /**
     * 判断价格是否突破上轨（强烈超买）
     */
    public boolean isPriceAboveUpper(BigDecimal currentPrice) {
        if (currentPrice == null || upper == null) {
            return false;
        }
        return currentPrice.doubleValue() > upper;
    }
    
    /**
     * 判断价格是否在中轨附近
     */
    public boolean isPriceNearMiddle(BigDecimal currentPrice) {
        if (currentPrice == null || middle == null || bandwidth == null) {
            return false;
        }
        double price = currentPrice.doubleValue();
        double threshold = bandwidth * 0.1; // 10%带宽作为阈值
        return Math.abs(price - middle) <= threshold;
    }
    
    /**
     * 计算%B指标
     */
    public double calculatePercentB(BigDecimal currentPrice) {
        if (currentPrice == null || upper == null || lower == null) {
            return 0.5; // 默认返回中间值
        }
        
        double price = currentPrice.doubleValue();
        if (upper.equals(lower)) {
            return 0.5;
        }
        
        return (price - lower) / (upper - lower);
    }
    
    /**
     * 判断是否超买（%B > 0.8）
     */
    public boolean isOverbought(BigDecimal currentPrice) {
        return calculatePercentB(currentPrice) > 0.8;
    }
    
    /**
     * 判断是否超卖（%B < 0.2）
     */
    public boolean isOversold(BigDecimal currentPrice) {
        return calculatePercentB(currentPrice) < 0.2;
    }
    
    /**
     * 获取带宽百分比（相对于中轨）
     */
    public double getBandwidthPercent() {
        if (middle == null || bandwidth == null || middle == 0) {
            return 0.0;
        }
        return (bandwidth / middle) * 100;
    }
    
    /**
     * 判断是否为窄幅震荡（带宽小于中轨的2%）
     */
    public boolean isSqueezing() {
        return getBandwidthPercent() < 2.0;
    }
    
    /**
     * 判断是否为宽幅震荡（带宽大于中轨的5%）
     */
    public boolean isExpanding() {
        return getBandwidthPercent() > 5.0;
    }
}
