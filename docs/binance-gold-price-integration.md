# 币安黄金报价集成文档

## 📋 概述

本项目已成功集成币安（Binance）黄金报价功能，可以同时获取 Bybit 和币安的黄金价格，进行对比分析。

## 🎯 功能特性

### 1. 双平台价格获取
- **Bybit**: XAUTUSDT（黄金永续合约）
- **币安**: PAXGUSDT（Paxos Gold 现货）

### 2. 提供的API接口

#### 2.1 价格对比接口
```
GET /api/gold/price/compare
```
同时获取 Bybit 和币安的黄金价格，并计算价差。

**返回示例：**
```json
{
  "success": true,
  "bybit": {
    "success": true,
    "symbol": "XAUTUSDT",
    "price": 5099.00,
    "source": "Bybit",
    "type": "永续合约"
  },
  "binance": {
    "success": true,
    "symbol": "PAXGUSDT",
    "price": 5139.88,
    "source": "币安",
    "type": "现货"
  },
  "comparison": {
    "priceDiff": -40.88,
    "priceDiffPercent": -0.7952,
    "description": "Bybit价格 低于 币安价格 $40.88 (0.80%)"
  },
  "timestamp": 1772764042123
}
```

#### 2.2 获取 Bybit 价格
```
GET /api/gold/price/bybit
```

#### 2.3 获取币安价格
```
GET /api/gold/price/binance
```

#### 2.4 获取币安24小时统计
```
GET /api/gold/stats/binance
```

**返回数据包括：**
- 最新价格
- 24小时涨跌幅
- 24小时最高/最低价
- 24小时交易量
- 等等

#### 2.5 测试币安连接
```
GET /api/gold/test/binance
```

## 📝 配置说明

### application.yml 配置

```yaml
# 币安 API配置（获取黄金报价）
binance:
  api:
    enabled: true                           # ✅ 已启用币安
    key: ""                                 # 可选：获取价格无需API Key
    secret: ""                              # 可选：获取价格无需API Secret
    testnet: false                          # ✅ 正式网（获取真实价格）
  gold:
    symbol: PAXGUSDT                        # ✅ Paxos黄金代币（1 PAXG = 1盎司黄金）
```

### 说明
- **无需 API Key**：获取公开市场数据（价格、K线等）不需要 API Key 和 Secret
- **如需交易**：如果将来需要通过币安进行交易，则需要配置有效的 API Key 和 Secret

## 🚀 使用方法

### 方法1：通过浏览器访问

启动应用后，在浏览器中访问：
```
http://localhost:3131/api/gold/price/compare
```

### 方法2：使用 curl 命令

```bash
# 对比两个平台价格
curl http://localhost:3131/api/gold/price/compare

# 仅获取币安价格
curl http://localhost:3131/api/gold/price/binance

# 获取币安24小时统计
curl http://localhost:3131/api/gold/stats/binance

# 测试币安连接
curl http://localhost:3131/api/gold/test/binance
```

### 方法3：使用测试脚本

项目提供了一个测试脚本，可以直接测试币安和Bybit的API（无需启动应用）：

```bash
./test_binance_gold_price.sh
```

## 📊 价格差异说明

测试结果显示（2026-03-06）：
- **币安（PAXGUSDT）**: $5,139.88
- **Bybit（XAUTUSDT）**: $5,099.00
- **价差**: 约 $40.88（0.79%）

### 价差原因分析

1. **交易品种差异**
   - 币安：PAXGUSDT 是实物黄金支持的代币现货
   - Bybit：XAUTUSDT 是黄金永续合约（衍生品）

2. **流动性差异**
   - 不同平台的流动性和交易深度不同

3. **资金费率影响**
   - Bybit 的永续合约有资金费率机制

4. **套利机会**
   - 价差可能提供跨平台套利机会（需考虑手续费和滑点）

## 🔧 技术实现

### 新增文件

1. **BinanceTradingService.java**
   - 路径：`src/main/java/com/ltp/peter/augtrade/trading/broker/`
   - 功能：封装币安API调用

2. **GoldPriceController.java**
   - 路径：`src/main/java/com/ltp/peter/augtrade/controller/`
   - 功能：提供黄金价格对比的REST API

### 主要特性

- ✅ 无需 API Key 即可获取价格
- ✅ 支持公开市场数据获取
- ✅ 自动计算价差和百分比
- ✅ 错误处理和日志记录
- ✅ 支持扩展为交易功能

## 📈 后续扩展建议

1. **价格监控**
   - 定时采集两个平台的价格
   - 当价差超过阈值时发送通知

2. **套利策略**
   - 自动识别套利机会
   - 计算最优交易时机

3. **历史数据分析**
   - 存储历史价格数据
   - 分析价差变化趋势

4. **多交易对支持**
   - 除了 PAXGUSDT，还可以添加其他黄金相关交易对
   - 如 XAUUSDT（Tether Gold）等

## 🔍 其他黄金交易对

币安支持的黄金相关交易对：

| 交易对 | 说明 | 特点 |
|--------|------|------|
| PAXGUSDT | Paxos Gold | 最主流，流动性好 |
| XAUUSDT | Tether Gold | 可能不支持，需验证 |

## ⚠️ 注意事项

1. **价格延迟**
   - API 返回的价格可能有轻微延迟
   - 实际交易价格以交易所实时报价为准

2. **交易限制**
   - 本项目目前仅获取价格，不进行实际交易
   - 如需交易功能，需要配置 API Key 并完成认证

3. **风险提示**
   - 加密货币和衍生品交易存在风险
   - 价格波动可能很大，请谨慎决策

## 📞 技术支持

如有问题，请查看日志文件：
```
logs/aug-trade.log
```

---

**文档版本**: v1.0  
**更新日期**: 2026-03-06  
**作者**: Peter Wang
