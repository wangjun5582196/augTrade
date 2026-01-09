package com.ltp.peter.augtrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测结果实体
 * 
 * @author Peter Wang
 */
@Data
@TableName("t_backtest_result")
public class BacktestResult {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 回测任务ID
     */
    private String backtestId;
    
    /**
     * 交易对符号
     */
    private String symbol;
    
    /**
     * 策略名称
     */
    private String strategyName;
    
    /**
     * K线周期 (interval是MySQL保留字，需要用反引号)
     */
    @TableField("`interval`")
    private String interval;
    
    /**
     * 回测开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 回测结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 初始资金
     */
    private BigDecimal initialCapital;
    
    /**
     * 最终资金
     */
    private BigDecimal finalCapital;
    
    /**
     * 总收益
     */
    private BigDecimal totalProfit;
    
    /**
     * 收益率(%)
     */
    private BigDecimal returnRate;
    
    /**
     * 总交易次数
     */
    private Integer totalTrades;
    
    /**
     * 盈利交易次数
     */
    private Integer profitableTrades;
    
    /**
     * 亏损交易次数
     */
    private Integer losingTrades;
    
    /**
     * 胜率(%)
     */
    private BigDecimal winRate;
    
    /**
     * 最大收益
     */
    private BigDecimal maxProfit;
    
    /**
     * 最大亏损
     */
    private BigDecimal maxLoss;
    
    /**
     * 最大回撤
     */
    private BigDecimal maxDrawdown;
    
    /**
     * 最大回撤率(%)
     */
    private BigDecimal maxDrawdownRate;
    
    /**
     * 平均盈利
     */
    private BigDecimal avgProfit;
    
    /**
     * 平均亏损
     */
    private BigDecimal avgLoss;
    
    /**
     * 盈亏比
     */
    private BigDecimal profitLossRatio;
    
    /**
     * 夏普比率
     */
    private BigDecimal sharpeRatio;
    
    /**
     * 总手续费
     */
    private BigDecimal totalFee;
    
    /**
     * 回测状态：RUNNING-运行中, COMPLETED-已完成, FAILED-失败
     */
    private String status;
    
    /**
     * 回测备注
     */
    private String remark;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
