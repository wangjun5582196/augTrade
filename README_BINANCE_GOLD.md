# 币安黄金报价功能 - 快速开始

## 🎯 功能说明

现在你可以同时获取 **Bybit** 和 **币安** 的黄金价格，并进行对比分析！

## ✅ 已完成的工作

1. ✅ 创建了 `BinanceTradingService` - 币安交易服务
2. ✅ 创建了 `GoldPriceController` - 黄金价格对比API
3. ✅ 更新了 `application.yml` 配置文件
4. ✅ 测试验证API连接成功

## 🚀 快速使用

### 测试API（无需启动应用）

直接运行测试脚本查看实时价格：

```bash
./test_binance_gold_price.sh
```

**测试结果示例：**
- 币安（PAXGUSDT）: $5,139.88
- Bybit（XAUTUSDT）: $5,099.00
- 价差: $40.88 (0.79%)

### 启动应用后使用

1. **启动应用**
   ```bash
   # 使用你的正常启动方式，例如：
   ./restart.sh
   # 或者在 IntelliJ IDEA 中运行
   ```

2. **访问API接口**

   **对比两个平台的价格：**
   ```bash
   curl http://localhost:3131/api/gold/price/compare
   ```
   
   **仅获取币安价格：**
   ```bash
   curl http://localhost:3131/api/gold/price/binance
   ```
   
   **获取Bybit价格：**
   ```bash
   curl http://localhost:3131/api/gold/price/bybit
   ```
   
   **获取币安24小时统计：**
   ```bash
   curl http://localhost:3131/api/gold/stats/binance
   ```

## 📊 API返回示例

### 价格对比接口

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

## 📝 配置说明

配置文件 `application.yml` 中已添加：

```yaml
# 币安 API配置
binance:
  api:
    enabled: true              # 已启用
    key: ""                    # 可选（获取价格无需API Key）
    secret: ""                 # 可选
    testnet: false             # 使用正式网
  gold:
    symbol: PAXGUSDT           # Paxos Gold（1 PAXG = 1盎司黄金）
```

**注意：** 
- 获取公开价格数据**不需要**配置 API Key
- 如果将来需要交易功能，才需要配置 API Key 和 Secret

## 🔍 价格差异说明

两个平台的价格会有差异，原因：

1. **交易品种不同**
   - 币安：PAXGUSDT（实物黄金支持的代币现货）
   - Bybit：XAUTUSDT（黄金永续合约/衍生品）

2. **流动性不同**
   - 不同交易所的交易深度和流动性不一样

3. **合约机制**
   - Bybit的永续合约有资金费率等因素影响

## 📁 新增文件

```
src/main/java/com/ltp/peter/augtrade/
├── trading/broker/
│   └── BinanceTradingService.java          # 币安交易服务
├── controller/
│   └── GoldPriceController.java            # 黄金价格API控制器
docs/
└── binance-gold-price-integration.md       # 详细集成文档
test_binance_gold_price.sh                  # API测试脚本
```

## 📚 更多信息

详细文档请查看：[币安黄金报价集成文档](docs/binance-gold-price-integration.md)

## 🎉 总结

现在你可以：
- ✅ 同时获取 Bybit 和币安的黄金价格
- ✅ 实时对比两个平台的价差
- ✅ 获取24小时价格统计数据
- ✅ 无需配置 API Key（仅查看价格）

---

**快速测试命令：**
```bash
# 1. 测试API连接（无需启动应用）
./test_binance_gold_price.sh

# 2. 启动应用后查看价格对比
curl http://localhost:3131/api/gold/price/compare | python3 -m json.tool
```

Have fun! 🚀
