# 币安实盘交易 - 快速开始

> 5分钟快速配置币安实盘交易

## 📦 已创建的文件

```
src/main/java/com/ltp/peter/augtrade/
├── trading/broker/
│   ├── BinanceLiveAdapter.java          # 币安实盘适配器
│   └── LiveTradingService.java          # 实盘交易服务
└── controller/
    └── LiveTradingController.java       # 实盘交易API

src/main/resources/
└── application.yml                      # 配置文件（已更新）

根目录/
├── BINANCE_LIVE_TRADING_GUIDE.md       # 完整使用指南
├── test_live_trading.sh                 # 测试脚本
└── QUICK_START.md                       # 本文件
```

## 🚀 快速开始（3步）

### 第1步：配置API密钥（2分钟）

编辑 `src/main/resources/application.yml`:

```yaml
binance:
  api:
    enabled: true
    key: "YOUR_BINANCE_API_KEY"          # ⚠️ 填写您的币安API Key
    secret: "YOUR_BINANCE_API_SECRET"    # ⚠️ 填写您的币安API Secret
    testnet: false                        # 正式网
  
  live:
    mode: false                           # ⚠️ 先保持false测试
    max-order-amount: 0.01                # 单笔最大0.01 PAXG
    max-daily-trades: 20                  # 每日最多20笔
    max-daily-loss: 500.0                 # 日亏损上限$500
```

### 第2步：启动服务（1分钟）

```bash
# 重启服务
./restart.sh

# 等待服务启动（约10秒）
# 看到 "Started AugTradeApplication" 表示启动成功
```

### 第3步：测试连接（1分钟）

```bash
# 运行测试脚本
./test_live_trading.sh

# 或手动测试
curl http://localhost:3131/api/live-trading/status
```

## ✅ 预期结果

### 只读模式（live.mode: false）

```json
{
  "success": true,
  "liveModeEnabled": false,         ← 未启用实盘
  "connected": true,                ← 连接正常
  "status": "实盘模式未启用",
  "balance": 1234.56                ← 账户余额
}
```

### 实盘模式（live.mode: true）

```json
{
  "success": true,
  "liveModeEnabled": true,          ← 已启用实盘⚠️
  "connected": true,
  "status": "实盘模式已启用",
  "balance": 1234.56
}
```

## 📡 核心API

### 1. 查询状态
```bash
curl http://localhost:3131/api/live-trading/status
```

### 2. 查询余额
```bash
curl http://localhost:3131/api/live-trading/balance
```

### 3. 买入（⚠️实盘交易）
```bash
curl -X POST "http://localhost:3131/api/live-trading/buy?quantity=0.01&strategy=手动"
```

### 4. 卖出（⚠️实盘交易）
```bash
curl -X POST "http://localhost:3131/api/live-trading/sell?quantity=0.01&strategy=手动"
```

## 🔐 安全机制

| 机制 | 说明 |
|------|------|
| 实盘开关 | `live.mode` 默认false，必须手动启用 |
| 金额限制 | 单笔最大 `max-order-amount` |
| 次数限制 | 每日最多 `max-daily-trades` |
| 亏损限制 | 每日最大亏损 `max-daily-loss` |
| 持仓检查 | 卖出前检查是否有持仓 |
| 飞书通知 | 所有交易实时推送 |

## 📊 监控交易

### 查看订单
```bash
curl http://localhost:3131/api/trading/orders
```

### 查看持仓
```bash
curl http://localhost:3131/api/trading/positions/open
```

### 查看日志
```bash
tail -f logs/aug-trade.log | grep "实盘"
```

## ⚠️ 启用实盘前的检查清单

- [ ] 已在测试网充分测试
- [ ] API Key权限正确（现货交易+读取）
- [ ] 设置了合理的风险限额
- [ ] 飞书通知配置正常
- [ ] 账户有足够余额
- [ ] 理解所有风险
- [ ] 从小额开始（0.01 PAXG）

## 🆘 遇到问题？

### 常见错误

| 错误信息 | 解决方法 |
|---------|---------|
| "币安服务未启用" | 设置 `binance.api.enabled: true` |
| "实盘模式未启用" | 设置 `binance.live.mode: true` |
| "API Key未配置" | 填写 `key` 和 `secret` |
| "签名错误" | 检查Secret是否正确 |
| "时间不同步" | 运行 `sudo ntpdate -u time.apple.com` |

### 获取帮助

1. 查看完整文档: `BINANCE_LIVE_TRADING_GUIDE.md`
2. 查看日志: `logs/aug-trade.log`
3. 查看配置: `application.yml`

## 📈 下一步

1. **阅读完整文档** 
   - 详细了解所有功能
   - 学习风险管理策略

2. **测试网测试**
   - 设置 `testnet: true`
   - 充分测试所有功能

3. **小额实盘**
   - 设置最小限额
   - 执行1-2笔交易测试

4. **正式运行**
   - 调整到合适的参数
   - 持续监控

## ⚡ 一键命令

```bash
# 测试连接
./test_live_trading.sh

# 查看状态
curl http://localhost:3131/api/live-trading/status | jq

# 查看余额
curl http://localhost:3131/api/live-trading/balance | jq

# 查看日志（实时）
tail -f logs/aug-trade.log | grep "实盘"

# 重启服务
./restart.sh

# 紧急平仓所有持仓
curl -X POST http://localhost:3131/api/trading/close-all
```

## 📞 重要联系方式

- 技术支持：查看日志和文档
- 币安客服：https://www.binance.com/support
- 紧急情况：立即关闭实盘模式并重启服务

---

**记住：实盘交易有风险，投资需谨慎！** 🚀

祝交易顺利！
