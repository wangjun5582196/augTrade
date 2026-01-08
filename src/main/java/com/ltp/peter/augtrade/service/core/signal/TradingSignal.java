package com.ltp.peter.augtrade.service.core.signal;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
