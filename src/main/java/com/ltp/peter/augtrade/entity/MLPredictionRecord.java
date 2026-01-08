package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ML预测记录实体
 * 用于跟踪每次ML预测的结果和准确性
 * 
 * @author Peter Wang
 */
@Data
@TableName("ml_prediction_record")
public class MLPredictionRecord {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易品种
     */
    private String symbol;
    
    /**
     * ML预测值（0-1概率）
     */
    private BigDecimal mlPrediction;
    
    /**
     * 预测方向（BUY/SELL/HOLD）
     */
    private String predictedSignal;
    
    /**
     * 置信度（0-1）
     */
    private BigDecimal confidence;
    
    /**
     * Williams %R值
     */
    private BigDecimal williamsR;
    
    /**
     * 预测时的价格
     */
    private BigDecimal priceAtPrediction;
    
    /**
     * 是否开仓
     */
    private Boolean tradeTaken;
    
    /**
     * 关联的订单号
     */
    private String orderNo;
    
    /**
     * 实际结果（PROFIT/LOSS/PENDING/NOT_TRADED）
     */
    private String actualResult;
    
    /**
     * 实际盈亏
     */
    private BigDecimal profitLoss;
    
    /**
     * 预测是否正确
     */
    private Boolean isCorrect;
    
    /**
     * 特征值JSON（可选，用于深度分析）
     */
    private String featuresJson;
    
    /**
     * 备注
     */
    private String remark;
    
    /**
     * 预测时间
     */
    private LocalDateTime predictionTime;
    
    /**
     * 结果确认时间
     */
    private LocalDateTime resultTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
