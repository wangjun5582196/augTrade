# 交易回测功能使用指南

## 功能概述

交易回测功能允许你使用历史K线数据来测试交易策略的有效性，帮助你在实盘交易前验证策略表现。

## 主要特性

### ✅ 支持的策略

1. **SHORT_TERM（短线趋势策略）**
   - 综合使用SMA、RSI、MACD等多个技术指标
   - 需要至少2个买入/卖出信号确认
   - 适合5分钟-15分钟周期

2. **BREAKOUT（突破策略）**
   - 基于布林带突破
   - 捕捉价格突破上轨的机会
   - 适合波动较大的市场

3. **RSI（RSI策略）**
   - 基于相对强弱指标
   - RSI < 30 时买入（超卖）
   - RSI > 70 时卖出（超买）
   - 适合震荡市场

### 📊 回测指标

系统会计算以下关键指标：

- **收益指标**
  - 总收益金额
  - 收益率（%）
  - 最大单笔收益
  - 最大单笔亏损
  - 平均盈利
  - 平均亏损

- **交易统计**
  - 总交易次数
  - 盈利交易次数
  - 亏损交易次数
  - 胜率（%）

- **风险指标**
  - 最大回撤
  - 最大回撤率（%）
  - 盈亏比
  - 夏普比率

- **成本统计**
  - 总手续费

## 安装步骤

### 1. 创建数据库表

执行SQL脚本创建回测相关表：

```bash
mysql -u root -p aug_trade < create_backtest_tables.sql
```

或者手动执行：

```sql
USE aug_trade;

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

### 2. 启动应用

确保应用正常启动：

```bash
./restart.sh
# 或
mvn spring-boot:run
```

### 3. 访问回测页面

打开浏览器访问：

```
http://localhost:8080/backtest.html
```

## 使用方法

### 通过Web界面使用

1. **配置回测参数**
   - 选择交易对（如XAUUSD）
   - 选择K线周期（建议5m或15m）
   - 选择交易策略
   - 设置初始资金（建议10000以上）
   - 设置回测时间范围（建议7-30天）

2. **执行回测**
   - 点击"开始回测"按钮
   - 等待系统处理（通常几秒到几分钟）
   - 查看执行结果

3. **查看结果**
   - 在"历史回测记录"中查看所有回测
   - 点击任意回测ID查看详细结果
   - 查看关键指标和交易明细

### 通过API使用

#### 1. 执行回测

```bash
curl -X POST http://localhost:8080/api/backtest/execute \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "XAUUSD",
    "interval": "5m",
    "strategyName": "SHORT_TERM",
    "initialCapital": 10000,
    "startTime": "2026-01-01 00:00:00",
    "endTime": "2026-01-08 23:59:59"
  }'
```

响应：
```json
{
  "success": true,
  "backtestId": "abc123...",
  "message": "回测执行成功"
}
```

#### 2. 获取回测结果

```bash
curl http://localhost:8080/api/backtest/result/{backtestId}
```

#### 3. 获取交易记录

```bash
curl http://localhost:8080/api/backtest/trades/{backtestId}
```

#### 4. 获取所有回测列表

```bash
curl http://localhost:8080/api/backtest/list
```

## 回测参数说明

### 时间周期选择建议

- **1分钟**: 超短线，数据量大，适合高频策略
- **5分钟**: 短线交易，推荐用于日内交易策略
- **15分钟**: 中短线，较为稳定
- **30分钟/1小时**: 中线交易，信号较少但质量较高

### 资金配置建议

- **初始资金**: 建议10000以上，与实盘资金规模相近
- **仓位管理**: 系统默认使用80%资金进行交易
- **手续费率**: 0.05%（开仓+平仓）

### 止盈止损设置

系统默认设置：
- **止盈**: 2%
- **止损**: 1%

这些参数可以在`BacktestService.java`中修改。

## 结果解读

### 好的回测结果指标

- **收益率**: > 10%
- **胜率**: > 50%
- **盈亏比**: > 1.5
- **最大回撤率**: < 20%
- **夏普比率**: > 1.0

### 注意事项

⚠️ **过拟合风险**
- 回测表现优异不代表实盘一定能盈利
- 避免过度优化参数以适应历史数据
- 建议在不同时间段进行多次回测验证

⚠️ **数据质量**
- 确保K线数据完整且准确
- 至少需要50条K线数据才能开始回测
- 缺失数据可能影响回测准确性

⚠️ **策略局限性**
- 回测不考虑滑点影响
- 回测假设所有订单都能立即成交
- 实盘交易可能面临流动性问题

## 优化建议

### 1. 策略优化

- 根据回测结果调整策略参数
- 测试不同的指标组合
- 对比不同策略的表现

### 2. 风险管理优化

- 调整止盈止损比例
- 优化仓位管理
- 设置最大回撤限制

### 3. 多市场验证

- 在不同市场状态下测试（牛市/熊市/震荡）
- 测试不同交易对
- 验证策略稳定性

## 常见问题

### Q1: 回测失败怎么办？

检查以下几点：
1. 是否有足够的K线数据
2. 时间范围是否合理
3. 数据库连接是否正常
4. 查看应用日志获取详细错误信息

### Q2: 回测速度慢怎么办？

- 减少回测时间范围
- 使用较大的K线周期
- 优化数据库索引

### Q3: 如何添加自定义策略？

1. 在`BacktestService.java`中添加新的策略方法
2. 在`executeBacktest`方法中添加策略分支
3. 实现策略信号评估逻辑
4. 在前端下拉菜单中添加选项

## 技术架构

### 核心组件

- **BacktestService**: 回测核心逻辑
- **BacktestController**: REST API接口
- **BacktestResult**: 回测结果实体
- **BacktestTrade**: 交易记录实体

### 回测流程

1. 加载历史K线数据
2. 遍历每根K线，评估交易信号
3. 模拟开仓/平仓操作
4. 计算盈亏和手续费
5. 更新持仓和资金
6. 统计回测指标
7. 保存结果到数据库

## 未来改进方向

- [ ] 支持多策略组合回测
- [ ] 添加参数优化功能
- [ ] 生成回测报告PDF
- [ ] 添加资金曲线图表
- [ ] 支持自定义止盈止损规则
- [ ] 添加滑点模拟
- [ ] 支持跨周期策略

## 联系与支持

如有问题或建议，请通过以下方式联系：

- 提交Issue到项目仓库
- 查看项目文档
- 联系开发团队

---

**祝你回测愉快，策略优化顺利！** 🚀
