package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * K线数据实体
 * 
 * @author Peter Wang
 */
@Data
@TableName("t_kline")
public class Kline {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 交易对符号
     */
    private String symbol;
    
    /**
     * K线周期（使用反引号转义，因为interval是MySQL保留字）
     */
    @TableField(value = "`interval`")
    private String interval;
    
    /**
     * K线时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 开盘价
     */
    private BigDecimal openPrice;
    
    /**
     * 最高价
     */
    private BigDecimal highPrice;
    
    /**
     * 最低价
     */
    private BigDecimal lowPrice;
    
    /**
     * 收盘价
     */
    private BigDecimal closePrice;
    
    /**
     * 成交量
     */
    private BigDecimal volume;
    
    /**
     * 成交额
     */
    private BigDecimal amount;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
