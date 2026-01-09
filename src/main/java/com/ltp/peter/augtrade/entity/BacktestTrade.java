package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测交易记录实体
 * 
 * @author Peter Wang
 */
@Data
@TableName("t_backtest_trade")
public class BacktestTrade {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 回测任务ID
     */
    private String backtestId;
    
    /**
     * 交易对符号
     */
    private String symbol;
    
    /**
     * 交易方向：BUY-买入, SELL-卖出
     */
    private String side;
    
    /**
     * 开仓价格
     */
    private BigDecimal entryPrice;
    
    /**
     * 开仓时间
     */
    private LocalDateTime entryTime;
    
    /**
     * 平仓价格
     */
    private BigDecimal exitPrice;
    
    /**
     * 平仓时间
     */
    private LocalDateTime exitTime;
    
    /**
     * 交易数量
     */
    private BigDecimal quantity;
    
    /**
     * 盈亏金额
     */
    private BigDecimal profitLoss;
    
    /**
     * 盈亏率(%)
     */
    private BigDecimal profitLossRate;
    
    /**
     * 手续费
     */
    private BigDecimal fee;
    
    /**
     * 持仓时长（分钟）
     */
    private Integer holdingMinutes;
    
    /**
     * 止盈价格
     */
    private BigDecimal takeProfitPrice;
    
    /**
     * 止损价格
     */
    private BigDecimal stopLossPrice;
    
    /**
     * 平仓原因：TAKE_PROFIT-止盈, STOP_LOSS-止损, SIGNAL-信号平仓
     */
    private String exitReason;
    
    /**
     * 交易信号描述
     */
    private String signalDescription;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
