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
