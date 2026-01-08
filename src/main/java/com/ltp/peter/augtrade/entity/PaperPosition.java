package com.ltp.peter.augtrade.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟持仓实体
 * 用于Paper Trading追踪持仓状态
 * 
 * @author Peter Wang
 */
@Data
public class PaperPosition {
    
    /**
     * 仓位ID
     */
    private String positionId;
    
    /**
     * 交易品种
     */
    private String symbol;
    
    /**
     * 交易方向：LONG-多头, SHORT-空头
     */
    private String side;
    
    /**
     * 开仓价格
     */
    private BigDecimal entryPrice;
    
    /**
     * 持仓数量
     */
    private BigDecimal quantity;
    
    /**
     * 止损价格
     */
    private BigDecimal stopLossPrice;
    
    /**
     * 止盈价格
     */
    private BigDecimal takeProfitPrice;
    
    /**
     * 当前价格（实时更新）
     */
    private BigDecimal currentPrice;
    
    /**
     * 未实现盈亏
     */
    private BigDecimal unrealizedPnL;
    
    /**
     * 策略名称
     */
    private String strategyName;
    
    /**
     * 开仓时间
     */
    private LocalDateTime openTime;
    
    /**
     * 仓位状态：OPEN-持仓中, CLOSED-已平仓
     */
    private String status;
    
    /**
     * 计算未实现盈亏
     */
    public void calculateUnrealizedPnL() {
        if (currentPrice == null || entryPrice == null) {
            unrealizedPnL = BigDecimal.ZERO;
            return;
        }
        
        BigDecimal priceDiff = currentPrice.subtract(entryPrice);
        
        // 空头方向相反
        if ("SHORT".equals(side)) {
            priceDiff = priceDiff.negate();
        }
        
        // 盈亏 = 价格差 × 数量
        unrealizedPnL = priceDiff.multiply(quantity);
    }
    
    /**
     * 检查是否触发止损
     */
    public boolean isStopLossTriggered() {
        if (currentPrice == null || stopLossPrice == null) {
            return false;
        }
        
        if ("LONG".equals(side)) {
            // 多头：当前价 <= 止损价
            return currentPrice.compareTo(stopLossPrice) <= 0;
        } else {
            // 空头：当前价 >= 止损价
            return currentPrice.compareTo(stopLossPrice) >= 0;
        }
    }
    
    /**
     * 检查是否触发止盈
     */
    public boolean isTakeProfitTriggered() {
        if (currentPrice == null || takeProfitPrice == null) {
            return false;
        }
        
        if ("LONG".equals(side)) {
            // 多头：当前价 >= 止盈价
            return currentPrice.compareTo(takeProfitPrice) >= 0;
        } else {
            // 空头：当前价 <= 止盈价
            return currentPrice.compareTo(takeProfitPrice) <= 0;
        }
    }
}
