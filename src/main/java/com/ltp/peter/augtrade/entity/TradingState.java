package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易状态持久化实体
 * 用于存储系统重启后需要恢复的关键状态
 * 
 * @author Peter Wang
 */
@Data
@TableName("trading_state")
public class TradingState {
    
    @TableId(type = IdType.AUTO)
    private Integer id;
    
    /**
     * 状态键（唯一）
     * 如：last_close_time, daily_trade_count, daily_trade_reset_time
     */
    private String stateKey;
    
    /**
     * 状态值（字符串格式）
     * LocalDateTime格式：2026-01-16T13:45:30
     * Integer格式：10
     */
    private String stateValue;
    
    /**
     * 最后更新时间（自动更新）
     */
    private LocalDateTime lastUpdate;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
