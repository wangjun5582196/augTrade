# 回测系统更新：美元计价 + 固定10手黄金

## 更新概述

已将回测系统更新为美元计价，并将每次下单数量固定为10手黄金，与实盘交易参数保持一致。

## 修改文件

1. **src/main/resources/static/backtest.html** - 前端显示界面
2. **src/main/java/com/ltp/peter/augtrade/service/BacktestService.java** - 后端回测逻辑

## 详细变更

### 1. 货币符号更新（¥ → $）

在 `backtest.html` 中，将所有人民币符号（¥）替换为美元符号（$）：

#### 回测列表显示
```javascript
// 之前：
html += `<td>¥${r.initialCapital.toFixed(2)}</td>`;
html += `<td>¥${r.finalCapital ? r.finalCapital.toFixed(2) : 'N/A'}</td>`;

// 之后：
html += `<td>$${r.initialCapital.toFixed(2)}</td>`;
html += `<td>$${r.finalCapital ? r.finalCapital.toFixed(2) : 'N/A'}</td>`;
```

#### 回测结果统计
```javascript
// 之前：
<div class="stat-value">¥${r.totalProfit.toFixed(2)}</div>
<div class="stat-value">¥${r.totalFee.toFixed(2)}</div>

// 之后：
<div class="stat-value">$${r.totalProfit.toFixed(2)}</div>
<div class="stat-value">$${r.totalFee.toFixed(2)}</div>
```

#### 交易明细
```javascript
// 之前：
html += `<td>¥${t.entryPrice.toFixed(2)}</td>`;
html += `<td>¥${t.exitPrice.toFixed(2)}</td>`;
html += `<td style="color: ${profitColor}; font-weight: bold;">¥${t.profitLoss.toFixed(2)}</td>`;
html += `<td>¥${t.fee.toFixed(2)}</td>`;

// 之后：
html += `<td>$${t.entryPrice.toFixed(2)}</td>`;
html += `<td>$${t.exitPrice.toFixed(2)}</td>`;
html += `<td style="color: ${profitColor}; font-weight: bold;">$${t.profitLoss.toFixed(2)}</td>`;
html += `<td>$${t.fee.toFixed(2)}</td>`;
```

### 2. 固定交易数量为10手

在 `BacktestService.java` 的 `openPositionWithDollarStopLoss` 方法中：

```java
// 之前：基于资金百分比计算
// 计算交易数量（使用80%的资金）
BigDecimal tradeCapital = capital.multiply(new BigDecimal("0.8"));
BigDecimal quantity = tradeCapital.divide(price, 4, RoundingMode.DOWN);
trade.setQuantity(quantity);

// 之后：固定10手
// 固定交易数量：10手黄金（与实盘一致）
BigDecimal quantity = new BigDecimal("10");
trade.setQuantity(quantity);
```

### 3. 策略选项简化

在前端表单中，将策略选择简化为仅显示组合策略：

```html
<select id="strategyName" required>
    <option value="COMPOSITE">组合策略（多策略投票）⭐</option>
</select>
```

## 配置参数（与实盘一致）

- **交易数量**：固定10手黄金
- **止损金额**：$15
- **止盈金额**：$30
- **手续费率**：0.05%
- **货币单位**：美元（USD）

## 效果对比

### 之前（基于资金百分比）
- 初始资金：¥10,000
- 使用80%资金：¥8,000
- 如果黄金价格$2,600，则数量约：3.08盎司

### 之后（固定10手）
- 初始资金：$10,000
- 固定数量：10盎司
- 无论价格如何，每次都是10手

## 优势

1. **与实盘一致**：回测参数与实际交易参数完全相同
2. **简化计算**：固定数量避免了复杂的资金管理计算
3. **清晰对比**：美元计价便于与国际市场对比
4. **风险可控**：每次交易风险固定，便于风险管理

## 使用说明

1. 访问回测页面：`http://localhost:8080/backtest.html`
2. 选择交易对：XAUTUSDT (黄金/美元)
3. 选择K线周期：建议5分钟
4. 选择策略：组合策略
5. 设置初始资金：建议$10,000
6. 选择回测时间范围
7. 点击"开始回测"

## 回测结果解读

所有金额均以美元显示：
- **总收益**：总盈亏（$）
- **收益率**：百分比收益
- **胜率**：盈利交易占比
- **盈亏比**：平均盈利/平均亏损
- **最大回撤率**：最大回撤占最高资金的百分比
- **总手续费**：所有交易的手续费总和（$）

## 注意事项

1. 确保数据库中有足够的历史K线数据
2. 回测结果仅供参考，不构成投资建议
3. 实盘交易请谨慎操作，注意风险控制
4. 固定10手适合黄金交易，其他品种可能需要调整

## 更新时间

2026-01-09 22:21
