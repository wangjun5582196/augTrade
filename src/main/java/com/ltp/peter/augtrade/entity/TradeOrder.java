package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易订单实体
 * 
 * @author Peter Wang
 */
@Data
@TableName("t_trade_order")
public class TradeOrder {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 订单号
     */
    private String orderNo;
    
    /**
     * 交易对符号
     */
    private String symbol;
    
    /**
     * 订单类型：MARKET-市价单, LIMIT-限价单
     */
    private String orderType;
    
    /**
     * 交易方向：BUY-买入, SELL-卖出
     */
    private String side;
    
    /**
     * 下单价格
     */
    private BigDecimal price;
    
    /**
     * 下单数量
     */
    private BigDecimal quantity;
    
    /**
     * 成交价格
     */
    private BigDecimal executedPrice;
    
    /**
     * 成交数量
     */
    private BigDecimal executedQuantity;
    
    /**
     * 订单状态：PENDING-待成交, FILLED-已成交, CANCELLED-已取消, FAILED-失败
     */
    private String status;
    
    /**
     * 策略名称
     */
    private String strategyName;
    
    /**
     * 止盈价格
     */
    private BigDecimal takeProfitPrice;
    
    /**
     * 止损价格
     */
    private BigDecimal stopLossPrice;
    
    /**
     * 盈亏金额
     */
    private BigDecimal profitLoss;
    
    /**
     * 手续费
     */
    private BigDecimal fee;
    
    /**
     * 订单备注
     */
    private String remark;
    
    // ==================== 🔥 新增：技术指标字段(用于AI学习) ====================
    
    /**
     * Williams %R指标值
     */
    private BigDecimal williamsR;
    
    /**
     * ADX趋势强度指标值
     */
    private BigDecimal adx;
    
    /**
     * EMA20均线值
     */
    private BigDecimal ema20;
    
    /**
     * EMA50均线值
     */
    private BigDecimal ema50;
    
    /**
     * ATR波动率指标值
     */
    private BigDecimal atr;
    
    /**
     * K线形态类型
     */
    private String candlePattern;
    
    /**
     * K线形态强度(0-10)
     */
    private Integer candlePatternStrength;
    
    /**
     * 布林带上轨
     */
    private Double bollingerUpper;
    
    /**
     * 布林带中轨
     */
    private Double bollingerMiddle;
    
    /**
     * 布林带下轨
     */
    private Double bollingerLower;
    
    /**
     * 信号强度(0-100)
     */
    private Integer signalStrength;
    
    /**
     * 信号得分
     */
    private Integer signalScore;
    
    /**
     * 市场状态: STRONG_TREND/WEAK_TREND/RANGING/CHOPPY
     */
    private String marketRegime;
    
    /**
     * ML预测值(0-1)
     */
    private BigDecimal mlPrediction;
    
    /**
     * ML置信度(0-1)
     */
    private BigDecimal mlConfidence;
    
    // ==================== 原有字段 ====================
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 成交时间
     */
    private LocalDateTime executedTime;
}
