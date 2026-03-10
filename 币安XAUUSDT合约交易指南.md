# 币安XAUUSDT合约交易指南

> 币安支持XAUUSDT永续合约交易（TradeFi永续合约）

## ✅ 验证结果

```bash
# 当前价格
curl "https://fapi.binance.com/fapi/v1/ticker/price?symbol=XAUUSDT"
# 返回: {"symbol":"XAUUSDT","price":"5178.25","time":1773130762682}

# 合约信息
{
  "symbol": "XAUUSDT",
  "contractType": "TRADIFI_PERPETUAL",  # TradeFi永续合约
  "status": "TRADING"                    # 交易中
}
```

## 📦 已创建的组件

### 1. 核心文件
- **BinanceFuturesAdapter.java** - 币安合约交易适配器
- **BinanceFuturesTradingService.java** - 合约API服务
- **OrderRequest.java** - 订单请求模型（新增positionSide字段）

### 2. 配置文件
`application.yml` 新增合约交易配置：
```yaml
binance:
  futures:
    symbol: XAUUSDT                # 黄金永续合约
    live-mode: false               # 实盘开关
    max-order-amount: 10           # 单笔最大10盎司
    max-daily-trades: 20           # 每日最多20笔
    max-daily-loss: 500.0          # 日亏损上限$500
    leverage: 2                    # 杠杆2倍
```

## 🔥 合约 vs 现货对比

| 特性 | 现货PAXGUSDT | 合约XAUUSDT |
|------|-------------|------------|
| **交易类型** | 现货代币 | 永续合约 |
| **支持状态** | ✅ 支持 | ✅ 支持 |
| **杠杆** | 无杠杆 | 1-125倍（建议2-5倍）|
| **最小交易量** | 0.001 PAXG | 1盎司（整数）|
| **做空** | ❌ 不支持 | ✅ 支持双向持仓 |
| **资金费率** | 无 | 有（每8小时）|
| **交易成本** | 较低 | 较低+资金费率 |
| **适合场景** | 长期持有 | 短期交易/对冲 |

## 🚀 快速开始

### 第1步：配置API密钥

编辑 `application.yml`:
```yaml
binance:
  api:
    enabled: true
    key: "YOUR_API_KEY"           # 填写API Key
    secret: "YOUR_SECRET"         # 填写Secret
    testnet: false                # 正式网
  
  futures:
    symbol: XAUUSDT               # 合约交易对
    live-mode: false              # 先保持false测试
    max-order-amount: 10          # 建议从小额开始
    leverage: 2                   # 建议2-3倍杠杆
```

⚠️ **重要**：API Key需要开通**合约交易权限**！

### 第2步：测试连接

```bash
# 获取当前价格
curl "https://fapi.binance.com/fapi/v1/ticker/price?symbol=XAUUSDT"

# 预期返回
{"symbol":"XAUUSDT","price":"5178.25","time":1773130762682}
```

### 第3步：使用适配器

在代码中使用：
```java
@Autowired
@Qualifier("binanceFuturesAdapter")
private BrokerAdapter futuresAdapter;

// 获取价格
BigDecimal price = futuresAdapter.getCurrentPrice("XAUUSDT");

// 开多仓（需先启用live-mode）
OrderRequest request = OrderRequest.builder()
    .symbol("XAUUSDT")
    .side("BUY")
    .positionSide("LONG")        // 多头持仓
    .quantity(new BigDecimal("10"))
    .leverage(2)
    .build();
String orderId = futuresAdapter.placeMarketOrder(request);

// 平多仓
request.setSide("SELL");
request.setPositionSide("LONG");
futuresAdapter.placeMarketOrder(request);
```

## 📊 合约交易参数说明

### 交易对信息
- **Symbol**: XAUUSDT
- **合约类型**: TradeFi永续合约
- **最小数量**: 1盎司（整数）
- **价格精度**: 2位小数
- **当前价格**: ~$5,178

### 杠杆倍数
- **范围**: 1-125倍
- **推荐**: 
  - 初学者: 1-2倍
  - 中级: 2-5倍
  - 高级: 5-10倍
  - ⚠️ 不建议超过10倍

### 持仓模式
币安合约支持**双向持仓**：
- **LONG**: 做多持仓
- **SHORT**: 做空持仓
- 可同时持有多空仓位

### 交易方向
| 操作 | Side | PositionSide | 说明 |
|------|------|--------------|------|
| 开多仓 | BUY | LONG | 买入做多 |
| 平多仓 | SELL | LONG | 卖出平多 |
| 开空仓 | SELL | SHORT | 卖出做空 |
| 平空仓 | BUY | SHORT | 买入平空 |

## 🛡 风险管理

### 1. 杠杆风险
- 2倍杠杆：价格波动2%即可能爆仓
- 5倍杠杆：价格波动0.8%即可能爆仓
- 10倍杠杆：价格波动0.4%即可能爆仓

建议：**从2倍杠杆开始**，熟悉后再提高

### 2. 资金费率
- 每8小时收取一次
- 费率根据市场多空比动态调整
- 通常在-0.01%到0.01%之间

### 3. 持仓限额
```yaml
binance:
  futures:
    max-order-amount: 10          # 单笔最大10盎司
    max-daily-trades: 20          # 每日最多20笔
    max-daily-loss: 500.0         # 日亏损上限$500
```

### 4. 安全建议
- ✅ 设置止损止盈
- ✅ 控制仓位大小
- ✅ 避免过高杠杆
- ✅ 监控资金费率
- ✅ 分散风险

## 📡 API接口

### 现有接口（使用现货适配器）
```
LiveTradingController - 现货交易
```

### 合约交易（需添加新控制器）
建议创建 `FuturesTradingController`:
```
GET  /api/futures-trading/status      - 查询状态
GET  /api/futures-trading/balance     - 查询余额
POST /api/futures-trading/open-long   - 开多仓
POST /api/futures-trading/close-long  - 平多仓
POST /api/futures-trading/open-short  - 开空仓
POST /api/futures-trading/close-short - 平空仓
GET  /api/futures-trading/positions   - 查询持仓
```

## ⚠️ 重要提示

### 合约交易特点
1. **高风险高收益** - 杠杆放大盈亏
2. **支持做空** - 可在下跌行情中获利
3. **资金费率** - 需关注持仓成本
4. **强制平仓** - 亏损达到保证金会爆仓

### API Key权限
确保API Key开通了以下权限：
- ✅ **合约交易** （必须）
- ✅ **读取** （必须）
- ❌ 现货交易（不需要）
- ❌ 提币（不需要）

### 测试建议
1. **先测试网** - 使用testnet充分测试
2. **小额实盘** - 最小数量（1盎司）
3. **低杠杆** - 从1-2倍开始
4. **设止损** - 严格止损纪律

## 🔄 现货 vs 合约选择

### 使用现货（PAXGUSDT）当
- 长期持有黄金
- 不需要杠杆
- 规避资金费率
- 保守投资

### 使用合约（XAUUSDT）当
- 短期交易
- 需要杠杆放大收益
- 需要做空对冲
- 日内交易

## 📚 相关文档

- **完整指南**: `BINANCE_LIVE_TRADING_GUIDE.md`
- **快速开始**: `QUICK_START.md`
- **交易对说明**: `币安交易对说明.md`

## 🆘 常见问题

### Q: 为什么说现货不支持XAUUSDT？
A: 币安**现货**不支持XAUUSDT，但**合约**支持。现货只能交易PAXGUSDT（黄金代币）。

### Q: 合约和现货的XAUUSDT价格一样吗？
A: 基本一致，但合约价格可能略有溢价/折价（由资金费率影响）。

### Q: 可以同时交易现货和合约吗？
A: 可以！它们是独立的账户和系统。

### Q: 杠杆倍数可以调整吗？
A: 可以，在配置文件中修改`leverage`参数，或通过API动态调整。

### Q: 爆仓了怎么办？
A: 爆仓后该仓位清零，亏损全部保证金。**务必设置止损**，控制风险！

## 💡 实用技巧

### 1. 测试流程
```bash
# 1. 测试连接
curl "https://fapi.binance.com/fapi/v1/ping"

# 2. 获取价格
curl "https://fapi.binance.com/fapi/v1/ticker/price?symbol=XAUUSDT"

# 3. 查看合约信息
curl "https://fapi.binance.com/fapi/v1/exchangeInfo" | jq '.symbols[] | select(.symbol=="XAUUSDT")'
```

### 2. 计算保证金
```
保证金 = (价格 × 数量) / 杠杆

示例：
价格: $5,178
数量: 10盎司
杠杆: 2倍
保证金 = (5178 × 10) / 2 = $25,890
```

### 3. 监控要点
- 未实现盈亏
- 保证金率
- 资金费率
- 持仓时长

---

**祝您交易顺利！记住：合约有风险，投资需谨慎！** 🚀
