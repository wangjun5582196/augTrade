package com.ltp.peter.augtrade.indicator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K线形态结果
 * 
 * 包含识别到的K线形态类型和方向
 * 
 * @author Peter Wang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandlePattern {
    
    /**
     * 形态类型
     */
    private PatternType type;
    
    /**
     * 形态方向
     */
    private Direction direction;
    
    /**
     * 形态强度（1-10）
     */
    private int strength;
    
    /**
     * 形态描述
     */
    private String description;
    
    /**
     * K线形态类型
     */
    public enum PatternType {
        NONE("无明显形态"),
        DOJI("十字星"),
        BULLISH_ENGULFING("看涨吞没"),
        BEARISH_ENGULFING("看跌吞没"),
        HAMMER("锤子线"),
        SHOOTING_STAR("射击之星"),
        MORNING_STAR("早晨之星"),
        EVENING_STAR("黄昏之星"),
        PIERCING("启明星"),
        DARK_CLOUD("乌云盖顶");
        
        private final String description;
        
        PatternType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 形态方向
     */
    public enum Direction {
        BULLISH("看涨"),
        BEARISH("看跌"),
        NEUTRAL("中性");
        
        private final String description;
        
        Direction(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 创建无形态
     */
    public static CandlePattern none() {
        return CandlePattern.builder()
                .type(PatternType.NONE)
                .direction(Direction.NEUTRAL)
                .strength(0)
                .description("无明显K线形态")
                .build();
    }
    
    /**
     * 判断是否为看涨形态
     */
    public boolean isBullish() {
        return direction == Direction.BULLISH;
    }
    
    /**
     * 判断是否为看跌形态
     */
    public boolean isBearish() {
        return direction == Direction.BEARISH;
    }
    
    /**
     * 判断是否有明显形态
     */
    public boolean hasPattern() {
        return type != PatternType.NONE;
    }
}
