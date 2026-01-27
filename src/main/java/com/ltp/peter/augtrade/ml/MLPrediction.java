package com.ltp.peter.augtrade.ml;

import lombok.Builder;
import lombok.Data;

/**
 * ML预测结果
 * 
 * @author Peter Wang
 * @date 2026-01-27
 */
@Data
@Builder
public class MLPrediction {
    private int label;           // -1=下跌, 0=震荡, 1=上涨
    private String labelName;    // "下跌"/"震荡"/"上涨"
    private double probUp;       // 上涨概率 (0-1)
    private double probHold;     // 震荡概率 (0-1)
    private double probDown;     // 下跌概率 (0-1)
    private double confidence;   // 置信度 (最大概率)
    
    /**
     * 是否高置信度识别为震荡
     * 震荡精确率94%，这是模型最可靠的能力
     */
    public boolean isHighConfidenceRanging() {
        return probHold > 0.80;  // 阈值0.80，精确率94%
    }
    
    /**
     * 是否高置信度看涨
     * 上涨精确率仅14%，需要更高阈值
     */
    public boolean isHighConfidenceUp() {
        return probUp > 0.75;  // 阈值0.75
    }
    
    /**
     * 是否高置信度看跌
     * 下跌精确率仅11%，需要更高阈值
     */
    public boolean isHighConfidenceDown() {
        return probDown > 0.70;  // 阈值0.70
    }
    
    /**
     * 是否倾向震荡（中等置信度）
     */
    public boolean isTrendingRanging() {
        return probHold > 0.60;
    }
}
