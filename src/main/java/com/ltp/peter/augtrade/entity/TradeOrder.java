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
    
    // ==================== 🔥 新增-20260213：VWAP/Supertrend/OBV指标字段 ====================
    
    /**
     * VWAP值
     */
    private BigDecimal vwap;
    
    /**
     * 价格偏离VWAP百分比
     */
    private BigDecimal vwapDeviation;
    
    /**
     * Supertrend线的值
     */
    private BigDecimal supertrendValue;
    
    /**
     * Supertrend趋势方向：UP/DOWN
     */
    private String supertrendDirection;
    
    /**
     * OBV趋势方向（正=看涨，负=看跌）
     */
    private BigDecimal obvTrend;
    
    /**
     * OBV是否量价确认：1=确认，0=未确认
     */
    private Integer obvVolumeConfirmed;
    
    // ==================== 🔥 新增-20260309：信号追踪字段(方案C优化版) ====================
    
    /**
     * 做多信号总分
     */
    private Integer buyScore;
    
    /**
     * 做空信号总分
     */
    private Integer sellScore;
    
    /**
     * 信号理由列表(JSON格式)
     */
    private String signalReasons;
    
    /**
     * 2根K线动量
     */
    private BigDecimal momentum2;
    
    /**
     * 5根K线动量
     */
    private BigDecimal momentum5;
    
    /**
     * 成交量比率(当前/20周期平均)
     */
    private Double volumeRatio;
    
    /**
     * 当前成交量
     */
    private BigDecimal currentVolume;
    
    /**
     * 20周期平均成交量
     */
    private BigDecimal avgVolume;
    
    /**
     * 最近摆动高点
     */
    private BigDecimal swingHigh;
    
    /**
     * 最近摆动低点
     */
    private BigDecimal swingLow;
    
    /**
     * 价格距离摆动高点
     */
    private BigDecimal swingHighDistance;
    
    /**
     * 价格距离摆动低点
     */
    private BigDecimal swingLowDistance;
    
    /**
     * Hull Moving Average 20
     */
    private BigDecimal hma20;
    
    /**
     * HMA斜率(判断趋势强度)
     */
    private BigDecimal hmaSlope;
    
    /**
     * HMA趋势方向(UP/DOWN/SIDEWAYS)
     */
    private String hmaTrend;
    
    /**
     * 价格位置(ABOVE_SWING_HIGH/BELOW_SWING_LOW/BETWEEN)
     */
    private String pricePosition;
    
    /**
     * 趋势是否确认(1=是,0=否)
     */
    private Boolean trendConfirmed;
    
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
