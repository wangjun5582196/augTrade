-- 黄金短线量化交易平台数据库表结构
-- 数据库名称: aug_trade

-- 创建数据库
CREATE DATABASE IF NOT EXISTS aug_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE aug_trade;

-- K线数据表
CREATE TABLE IF NOT EXISTS t_kline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对符号',
    `interval` VARCHAR(10) NOT NULL COMMENT '时间周期',
    timestamp DATETIME NOT NULL COMMENT 'K线时间戳',
    open_price DECIMAL(18, 2) NOT NULL COMMENT '开盘价',
    high_price DECIMAL(18, 2) NOT NULL COMMENT '最高价',
    low_price DECIMAL(18, 2) NOT NULL COMMENT '最低价',
    close_price DECIMAL(18, 2) NOT NULL COMMENT '收盘价',
    volume DECIMAL(18, 4) DEFAULT 0 COMMENT '成交量',
    amount DECIMAL(18, 2) DEFAULT 0 COMMENT '成交额',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_symbol_interval (symbol, `interval`),
    INDEX idx_timestamp (timestamp),
    UNIQUE KEY uk_symbol_interval_timestamp (symbol, `interval`, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线数据表';

-- 交易订单表
CREATE TABLE IF NOT EXISTS t_trade_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    order_no VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对符号',
    order_type VARCHAR(20) NOT NULL COMMENT '订单类型：MARKET-市价单, LIMIT-限价单',
    side VARCHAR(10) NOT NULL COMMENT '交易方向：BUY-买入, SELL-卖出',
    price DECIMAL(18, 2) NOT NULL COMMENT '下单价格',
    quantity DECIMAL(18, 4) NOT NULL COMMENT '下单数量',
    executed_price DECIMAL(18, 2) DEFAULT NULL COMMENT '成交价格',
    executed_quantity DECIMAL(18, 4) DEFAULT NULL COMMENT '成交数量',
    status VARCHAR(20) NOT NULL COMMENT '订单状态：PENDING-待成交, FILLED-已成交, CANCELLED-已取消, FAILED-失败',
    strategy_name VARCHAR(50) DEFAULT NULL COMMENT '策略名称',
    take_profit_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止盈价格',
    stop_loss_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止损价格',
    profit_loss DECIMAL(18, 2) DEFAULT NULL COMMENT '盈亏金额',
    fee DECIMAL(18, 2) DEFAULT 0 COMMENT '手续费',
    remark VARCHAR(255) DEFAULT NULL COMMENT '订单备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    executed_time DATETIME DEFAULT NULL COMMENT '成交时间',
    INDEX idx_symbol (symbol),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time),
    INDEX idx_strategy (strategy_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单表';

-- 持仓信息表
CREATE TABLE IF NOT EXISTS t_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对符号',
    direction VARCHAR(10) NOT NULL COMMENT '持仓方向：LONG-多头, SHORT-空头',
    quantity DECIMAL(18, 4) NOT NULL COMMENT '持仓数量',
    avg_price DECIMAL(18, 2) NOT NULL COMMENT '开仓均价',
    current_price DECIMAL(18, 2) DEFAULT NULL COMMENT '当前价格',
    unrealized_pnl DECIMAL(18, 2) DEFAULT 0 COMMENT '未实现盈亏',
    margin DECIMAL(18, 2) DEFAULT 0 COMMENT '保证金',
    leverage INT DEFAULT 1 COMMENT '杠杆倍数',
    take_profit_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止盈价格',
    stop_loss_price DECIMAL(18, 2) DEFAULT NULL COMMENT '止损价格',
    status VARCHAR(20) NOT NULL COMMENT '持仓状态：OPEN-开仓中, CLOSED-已平仓',
    open_time DATETIME NOT NULL COMMENT '开仓时间',
    close_time DATETIME DEFAULT NULL COMMENT '平仓时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_symbol (symbol),
    INDEX idx_status (status),
    INDEX idx_direction (direction)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓信息表';

-- 插入初始测试数据（可选）
-- INSERT INTO t_kline (symbol, `interval`, timestamp, open_price, high_price, low_price, close_price, volume, amount)
-- VALUES ('XAUUSD', '5m', NOW(), 2650.00, 2655.00, 2648.00, 2652.00, 100, 265200.00);
