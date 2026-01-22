package com.ltp.peter.augtrade.trading.broker;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单请求模型
 * 
 * @author Peter Wang
 */
@Data
@Builder
public class OrderRequest {
    
    /**
     * 交易品种
     */
    private String symbol;
    
    /**
     * 订单方向（BUY/SELL）
     */
    private String side;
    
    /**
     * 订单类型（MARKET/LIMIT）
     */
    private String orderType;
    
    /**
     * 数量
     */
    private BigDecimal quantity;
    
    /**
     * 价格（限价单使用）
     */
    private BigDecimal price;
    
    /**
     * 止损价格
     */
    private BigDecimal stopLoss;
    
    /**
     * 止盈价格
     */
    private BigDecimal takeProfit;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 策略名称
     */
    private String strategyName;
    
    /**
     * 备注
     */
    private String remark;
}
