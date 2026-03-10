package com.ltp.peter.augtrade.strategy.signal;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易信号模型
 * 
 * @author Peter Wang
 */
@Data
@Builder
public class TradingSignal {
    
    /**
     * 信号类型
     */
    private SignalType type;
    
    /**
     * 信号强度（0-100）
     */
    private int strength;
    
    /**
     * 信号评分
     */
    private int score;
    
    // ==================== 🔥 新增-20260309：评分和指标字段 ====================
    
    /**
     * 做多总分
     */
    private int buyScore;
    
    /**
     * 做空总分
     */
    private int sellScore;
    
    /**
     * 做多理由列表
     */
    private List<String> buyReasons;
    
    /**
     * 做空理由列表
     */
    private List<String> sellReasons;
    
    /**
     * 2根K线动量
     */
    private BigDecimal momentum2;
    
    /**
     * 5根K线动量
     */
    private BigDecimal momentum5;
    
    /**
     * 成交量比率
     */
    private Double volumeRatio;
    
    /**
     * 最近摆动高点
     */
    private BigDecimal lastSwingHigh;
    
    /**
     * 最近摆动低点
     */
    private BigDecimal lastSwingLow;
    
    /**
     * HMA20值
     */
    private BigDecimal hma20;
    
    /**
     * HMA斜率
     */
    private Double hma20Slope;
    
    /**
     * 价格位置
     */
    private String pricePosition;
    
    /**
     * 趋势确认
     */
    private Boolean trendConfirmed;
    
    /**
     * 信号生成时间
     */
    private LocalDateTime signalGenerateTime;
    
    // ==================== 🔥 新增-20260310：完整技术指标字段 ====================
    
    /**
     * Williams %R指标值
     */
    private Double williamsR;
    
    /**
     * ADX趋势强度指标值
     */
    private Double adx;
    
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
    
    /**
     * 当前成交量
     */
    private BigDecimal currentVolume;
    
    /**
     * 20周期平均成交量
     */
    private BigDecimal avgVolume;
    
    // ==================== 原有字段 ====================
    
    /**
     * 交易品种
     */
    private String symbol;
    
    /**
     * 当前价格
     */
    private BigDecimal currentPrice;
    
    /**
     * 建议止损价格
     */
    private BigDecimal suggestedStopLoss;
    
    /**
     * 建议止盈价格
     */
    private BigDecimal suggestedTakeProfit;
    
    /**
     * 策略名称
     */
    private String strategyName;
    
    /**
     * 信号原因/依据
     */
    private String reason;
    
    /**
     * 生成时间
     */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    /**
     * 是否有效信号
     */
    public boolean isValid() {
        return type != null && type != SignalType.HOLD && strength > 0;
    }
    
    /**
     * 是否做多信号
     */
    public boolean isBuy() {
        return type == SignalType.BUY;
    }
    
    /**
     * 是否做空信号
     */
    public boolean isSell() {
        return type == SignalType.SELL;
    }
    
    /**
     * 是否观望信号
     */
    public boolean isHold() {
        return type == SignalType.HOLD;
    }
    
    /**
     * 信号类型枚举
     */
    public enum SignalType {
        BUY,    // 做多
        SELL,   // 做空
        HOLD    // 观望
    }
}
