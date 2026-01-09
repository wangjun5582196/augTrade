# 回测功能安装说明

## ⚠️ 重要：需要手动创建数据库表

由于MySQL客户端未在命令行中配置，请按照以下步骤手动创建数据库表：

## 方法1：使用数据库客户端工具

如果你使用 **DataGrip**、**MySQL Workbench**、**Navicat** 等数据库客户端工具：

1. 连接到你的MySQL数据库
2. 选择 `test` 数据库
3. 打开 `create_backtest_tables.sql` 文件
4. 执行整个SQL脚本

## 方法2：复制SQL语句手动执行

在你的数据库客户端中，连接到 `test` 数据库后，执行以下SQL：

```sql
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
    status VARCHAR(20) NOT NULL COMMENT '回测状态',
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
    side VARCHAR(10) NOT NULL COMMENT '交易方向',
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
    exit_reason VARCHAR(50) DEFAULT NULL COMMENT '平仓原因',
    signal_description VARCHAR(255) DEFAULT NULL COMMENT '交易信号描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_backtest_id (backtest_id),
    INDEX idx_symbol (symbol),
    INDEX idx_entry_time (entry_time),
    FOREIGN KEY (backtest_id) REFERENCES t_backtest_result(backtest_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测交易记录表';
```

## 验证安装

执行以下SQL查询验证表已创建成功：

```sql
USE test;
SHOW TABLES LIKE 't_backtest%';
```

你应该看到两个表：
- `t_backtest_result`
- `t_backtest_trade`

## 访问回测系统

数据库表创建完成后，访问以下地址：

```
http://localhost:3131/backtest.html
```

## 测试回测功能

1. 确保你的数据库中有足够的历史K线数据（`t_kline` 表）
2. 在回测页面配置参数：
   - 交易对：XAUUSD
   - K线周期：5m
   - 策略：SHORT_TERM
   - 初始资金：10000
   - 时间范围：选择有K线数据的日期范围
3. 点击"开始回测"按钮
4. 查看回测结果和交易明细

## 如果遇到问题

1. 检查数据库表是否创建成功
2. 检查应用日志：`logs/aug-trade.log`
3. 确认数据库中有足够的K线数据
4. 查看浏览器控制台的错误信息

---

**所有代码已就绪，应用已重启，只需创建数据库表即可使用！** 🚀
