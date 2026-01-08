package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓信息实体
 * 
 * @author Peter Wang
 */
@Data
@TableName("t_position")
public class Position {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易对符号
     */
    private String symbol;
    
    /**
     * 持仓方向：LONG-多头, SHORT-空头
     */
    private String direction;
    
    /**
     * 持仓数量
     */
    private BigDecimal quantity;
    
    /**
     * 开仓均价
     */
    private BigDecimal avgPrice;
    
    /**
     * 当前价格
     */
    private BigDecimal currentPrice;
    
    /**
     * 未实现盈亏
     */
    private BigDecimal unrealizedPnl;
    
    /**
     * 保证金
     */
    private BigDecimal margin;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 止盈价格
     */
    private BigDecimal takeProfitPrice;
    
    /**
     * 止损价格
     */
    private BigDecimal stopLossPrice;
    
    /**
     * 是否启用移动止损
     */
    private Boolean trailingStopEnabled;
    
    /**
     * 持仓状态：OPEN-开仓中, CLOSED-已平仓
     */
    private String status;
    
    /**
     * 开仓时间
     */
    private LocalDateTime openTime;
    
    /**
     * 平仓时间
     */
    private LocalDateTime closeTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
