-- 回测功能数据库表结构
-- 数据库名称: test

USE test;

-- 回测结果表
CREATE TABLE IF NOT EXISTS t_backtest_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    backtest_id VARCHAR(50) NOT NULL UNIQUE COMMENT '回测任务ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对符号',
    strategy_name VARCHAR(50) NOT NULL COMMENT '策略名称',
    `interval` VARCHAR(10) NOT NULL COMMENT 'K线周期',
    start_time DATETIME NOT NULL COMMENT '回测开始时间',
    end_time DATETIME NOT NULL COMMENT '回测结束时间',
    initial_capital DECIMAL(18, 2) NOT NULL COMMENT '初始资金',
    final_capital DECIMAL(18, 2) DEFAULT NULL COMMENT '最终资金',
    total_profit DECIMAL(18, 2) DEFAULT NULL COMMENT '总收益',
    return_rate DECIMAL(10, 4) DEFAULT NULL COMMENT '收益率(%)',
    total_trades INT DEFAULT 0 COMMENT '总交易次数',
    profitable_trades INT DEFAULT 0 COMMENT '盈利交易次数',
    losing_trades INT DEFAULT 0 COMMENT '亏损交易次数',
    win_rate DECIMAL(10, 4) DEFAULT NULL COMMENT '胜率(%)',
    max_profit DECIMAL(18, 2) DEFAULT NULL COMMENT '最大收益',
    max_loss DECIMAL(18, 2) DEFAULT NULL COMMENT '最大亏损',
    max_drawdown DECIMAL(18, 2) DEFAULT NULL COMMENT '最大回撤',
    max_drawdown_rate DECIMAL(10, 4) DEFAULT NULL COMMENT '最大回撤率(%)',
    avg_profit DECIMAL(18, 2) DEFAULT NULL COMMENT '平均盈利',
    avg_loss DECIMAL(18, 2) DEFAULT NULL COMMENT '平均亏损',
    profit_loss_ratio DECIMAL(10, 2) DEFAULT NULL COMMENT '盈亏比',
    sharpe_ratio DECIMAL(10, 2) DEFAULT NULL COMMENT '夏普比率',
    total_fee DECIMAL(18, 2) DEFAULT 0 COMMENT '总手续费',
    status VARCHAR(20) NOT NULL COMMENT '回测状态：RUNNING-运行中, COMPLETED-已完成, FAILED-失败',
    remark VARCHAR(500) DEFAULT NULL COMMENT '回测备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_symbol (symbol),
    INDEX idx_strategy (strategy_name),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测结果表';

-- 回测交易记录表
CREATE TABLE IF NOT EXISTS t_backtest_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    backtest_id VARCHAR(50) NOT NULL COMMENT '回测任务ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对符号',
    side VARCHAR(10) NOT NULL COMMENT '交易方向：BUY-买入, SELL-卖出',
    entry_price DECIMAL(18, 2) NOT NULL COMMENT '开仓价格',
    entry_time DATETIME NOT NULL COMMENT '开仓时间',
    exit_price DECIMAL(18, 2) DEFAULT NULL COMMENT '平仓价格',
    exit_time DATETIME DEFAULT NULL COMMENT '平仓时间',
    quantity DECIMAL(18, 4) NOT NULL COMMENT '交易数量',
    profit_loss DECIMAL(18, 2) DEFAULT NULL COMMENT '盈亏金额',
    profit_loss_rate DECIMAL(10, 4) DEFAULT NULL COMMENT '盈亏率(%)',
    fee DECIMAL(18, 2) DEFAULT 0 COMMENT '手续费',
    holding_minutes INT DEFAULT NULL COMMENT '持仓时长（分钟）',
    take_profit_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止盈价格',
    stop_loss_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止损价格',
    exit_reason VARCHAR(50) DEFAULT NULL COMMENT '平仓原因：TAKE_PROFIT-止盈, STOP_LOSS-止损, SIGNAL-信号平仓, END_OF_BACKTEST-回测结束',
    signal_description VARCHAR(255) DEFAULT NULL COMMENT '交易信号描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_backtest_id (backtest_id),
    INDEX idx_symbol (symbol),
    INDEX idx_entry_time (entry_time),
    FOREIGN KEY (backtest_id) REFERENCES t_backtest_result(backtest_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测交易记录表';
