# 币安实盘交易接入指南

> **⚠️ 重要提示**: 实盘交易涉及真实资金，请务必谨慎操作！建议先在测试网充分测试后再切换到实盘。

## 📋 目录

1. [系统架构](#系统架构)
2. [前置准备](#前置准备)
3. [配置步骤](#配置步骤)
4. [API接口说明](#api接口说明)
5. [安全机制](#安全机制)
6. [测试流程](#测试流程)
7. [常见问题](#常见问题)
8. [风险提示](#风险提示)

---

## 🏗 系统架构

### 核心组件

```
┌─────────────────────────────────────────────────────┐
│              LiveTradingController                   │  ← REST API层
│          (实盘交易控制器 - 对外接口)                  │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│             LiveTradingService                       │  ← 业务逻辑层
│     (实盘交易服务 - 安全检查、风控、通知)              │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│            BinanceLiveAdapter                        │  ← 适配器层
│       (币安适配器 - 对接币安API)                      │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│          BinanceTradingService                       │  ← API调用层
│    (币安交易服务 - HTTP请求、签名)                     │
└─────────────────┬───────────────────────────────────┘
                  │
                  ▼
           [ 币安 API ]
```

### 文件清单

| 文件 | 用途 |
|------|------|
| `BinanceLiveAdapter.java` | 币安实盘交易适配器 |
| `LiveTradingService.java` | 实盘交易管理服务 |
| `LiveTradingController.java` | 实盘交易REST接口 |
| `BinanceTradingService.java` | 币安API基础服务（已存在）|
| `application.yml` | 配置文件（新增实盘配置）|

---

## 🔧 前置准备

### 1. 币安账户设置

#### 1.1 注册币安账户
- 访问: https://www.binance.com
- 完成实名认证（KYC）
- 充值USDT到现货账户

**重要说明：币安的黄金交易对**
- ✅ **PAXGUSDT** - Paxos Gold（币安支持，推荐使用）
  - 1 PAXG = 1盎司实物黄金
  - 流动性好，24小时交易
  - 当前价格约$5179
- ❌ **XAUUSDT** - 币安现货不支持此交易对
  - 如果您想交易XAUUSDT，请使用Bybit等其他平台

#### 1.2 创建API Key

1. 登录币安账户
2. 进入【API管理】页面
3. 创建新的API Key
   - 名称: `AugTrade实盘`
   - 权限设置:
     - ✅ **启用现货交易** (必须)
     - ✅ **启用读取** (必须)
     - ❌ 不需要合约、钱包等其他权限
4. 设置IP白名单（强烈建议）
5. 保存API Key和Secret（仅显示一次）

⚠️ **安全提示**:
- API Key务必妥善保管，不要泄露
- 建议设置IP白名单限制
- 定期更换API Key
- 不要给予不必要的权限

### 2. 测试网测试（强烈推荐）

在正式实盘前，建议先在测试网测试：

1. 访问测试网: https://testnet.binance.vision/
2. 创建测试账户并获取测试API Key
3. 修改配置使用测试网:
   ```yaml
   binance:
     api:
       testnet: true  # 使用测试网
   ```
4. 充分测试所有功能
5. 确认无误后再切换到正式网

---

## ⚙️ 配置步骤

### 第一步：配置API密钥

编辑 `src/main/resources/application.yml`:

```yaml
# 币安 API配置
binance:
  api:
    enabled: true                           # 启用币安
    key: "YOUR_BINANCE_API_KEY"            # 填写您的API Key
    secret: "YOUR_BINANCE_API_SECRET"      # 填写您的API Secret
    testnet: false                          # false=正式网, true=测试网
  
  gold:
    symbol: PAXGUSDT                        # 黄金代币交易对
  
  # 实盘交易配置
  live:
    mode: false                             # 实盘开关（先保持false测试）
    max-order-amount: 0.01                  # 单笔最大数量（PAXG）
    max-daily-trades: 20                    # 每日最大交易次数
    max-daily-loss: 500.0                   # 每日最大亏损（USD）
```

### 第二步：风险参数配置

根据您的风险承受能力调整以下参数：

```yaml
binance:
  live:
    max-order-amount: 0.01      # 建议从小额开始（0.01 PAXG ≈ $30）
    max-daily-trades: 20        # 限制交易频率
    max-daily-loss: 500.0       # 日亏损上限，超过后停止交易
```

**推荐配置**:
- 初学者: `max-order-amount: 0.01`, `max-daily-loss: 100`
- 中级: `max-order-amount: 0.05`, `max-daily-loss: 300`
- 高级: `max-order-amount: 0.1`, `max-daily-loss: 500`

### 第三步：启动服务

```bash
# 编译项目
mvn clean package -DskipTests

# 启动服务
java -jar target/aug-trade-*.jar

# 或使用脚本
./restart.sh
```

### 第四步：验证连接

```bash
# 测试币安连接
curl http://localhost:3131/api/live-trading/test-connection

# 查看实盘状态
curl http://localhost:3131/api/live-trading/status

# 查询账户余额
curl http://localhost:3131/api/live-trading/balance
```

---

## 📡 API接口说明

### 基础URL
```
http://localhost:3131/api/live-trading
```

### 1. 查询实盘状态

```http
GET /status
```

**响应示例**:
```json
{
  "success": true,
  "liveModeEnabled": false,
  "connected": true,
  "status": "实盘模式未启用",
  "balance": 1000.50
}
```

### 2. 查询账户余额

```http
GET /balance
```

**响应示例**:
```json
{
  "success": true,
  "balance": 1000.50,
  "currency": "USDT",
  "timestamp": 1710058320000
}
```

### 3. 实盘买入 ⚠️

```http
POST /buy
```

**参数**:
- `symbol` (可选): 交易对，默认PAXGUSDT
- `quantity` (必填): 数量，如0.01
- `strategy` (可选): 策略名称，默认"手动交易"

**示例**:
```bash
curl -X POST "http://localhost:3131/api/live-trading/buy?quantity=0.01&strategy=手动测试"
```

**响应示例**:
```json
{
  "success": true,
  "message": "实盘买入成功",
  "order": {
    "orderNo": "LIVE-1710058320-ABC123",
    "symbol": "PAXGUSDT",
    "side": "BUY",
    "quantity": 0.01,
    "price": 2850.50,
    "status": "FILLED"
  }
}
```

### 4. 实盘卖出 ⚠️

```http
POST /sell
```

**参数**:
- `symbol` (可选): 交易对，默认PAXGUSDT
- `quantity` (必填): 数量，如0.01
- `strategy` (可选): 策略名称，默认"手动平仓"

**示例**:
```bash
curl -X POST "http://localhost:3131/api/live-trading/sell?quantity=0.01&strategy=手动平仓"
```

**响应示例**:
```json
{
  "success": true,
  "message": "实盘卖出成功",
  "order": {
    "orderNo": "LIVE-1710058420-DEF456",
    "symbol": "PAXGUSDT",
    "side": "SELL",
    "quantity": 0.01,
    "price": 2855.20,
    "profitLoss": 0.47,
    "status": "FILLED"
  }
}
```

### 5. 测试连接

```http
GET /test-connection
```

**响应示例**:
```json
{
  "success": true,
  "connected": true,
  "message": "连接正常"
}
```

---

## 🛡 安全机制

### 多层安全防护

1. **实盘模式开关**
   - 默认关闭 (`live.mode: false`)
   - 必须手动启用才能下单
   - 防止误操作

2. **订单金额限制**
   - 单笔最大数量: `max-order-amount`
   - 最小数量: 0.001 PAXG
   - 超出范围拒绝下单

3. **每日交易限制**
   - 最大交易次数: `max-daily-trades`
   - 最大亏损限制: `max-daily-loss`
   - 达到限制自动停止

4. **二次确认机制**
   - 下单前日志记录
   - 参数验证
   - 异常捕获

5. **持仓检查**
   - 卖出前检查是否有持仓
   - 防止空头卖出

6. **飞书通知**
   - 所有交易实时通知
   - 错误及时告警
   - @提醒关键人员

### 日志记录

所有操作都有完整的日志记录：

```
logs/aug-trade.log
```

关键操作带有明显标记：
- `🔴 [实盘]` - 实盘操作
- `✅` - 成功
- `❌` - 失败
- `⚠️` - 警告

---

## 🧪 测试流程

### 阶段一：只读模式测试（推荐先做）

保持 `live.mode: false`，测试以下功能：

```bash
# 1. 测试连接
curl http://localhost:3131/api/live-trading/test-connection

# 2. 查询余额
curl http://localhost:3131/api/live-trading/balance

# 3. 查询状态
curl http://localhost:3131/api/live-trading/status

# 4. 尝试买入（会被拒绝，这是正常的）
curl -X POST "http://localhost:3131/api/live-trading/buy?quantity=0.01"
# 预期错误: "实盘模式未启用，无法下单"
```

### 阶段二：测试网实盘测试

1. 切换到测试网:
   ```yaml
   binance:
     api:
       testnet: true
   ```

2. 启用实盘模式:
   ```yaml
   binance:
     live:
       mode: true
   ```

3. 执行小额测试:
   ```bash
   # 买入
   curl -X POST "http://localhost:3131/api/live-trading/buy?quantity=0.01"
   
   # 等待几分钟
   
   # 卖出
   curl -X POST "http://localhost:3131/api/live-trading/sell?quantity=0.01"
   ```

4. 检查日志和数据库记录

### 阶段三：正式网小额测试

1. 切换到正式网:
   ```yaml
   binance:
     api:
       testnet: false
   ```

2. 设置极小的限额:
   ```yaml
   binance:
     live:
       mode: true
       max-order-amount: 0.01      # 最小金额
       max-daily-loss: 50.0        # 最小亏损限制
   ```

3. 执行一次完整的买卖循环

4. 确认：
   - 订单在币安账户中显示
   - 余额变化正确
   - 数据库记录准确
   - 飞书通知正常

### 阶段四：正式运行

确认一切正常后，可以调整到实际参数：

```yaml
binance:
  live:
    mode: true
    max-order-amount: 0.05          # 根据实际情况调整
    max-daily-trades: 20
    max-daily-loss: 300.0
```

---

## ❓ 常见问题

### Q1: 为什么提示"币安服务未启用"？

**A**: 检查配置文件:
```yaml
binance:
  api:
    enabled: true  # 确保为true
```

### Q2: 为什么提示"实盘模式未启用"？

**A**: 这是安全机制，需要手动启用:
```yaml
binance:
  live:
    mode: true  # 改为true
```

### Q3: 为什么提示"币安API Key或Secret未配置"？

**A**: 检查API密钥配置:
```yaml
binance:
  api:
    key: "YOUR_API_KEY"      # 不能为空
    secret: "YOUR_SECRET"    # 不能为空
```

### Q4: 下单失败，提示签名错误？

**A**: 可能的原因:
1. API Secret错误
2. 系统时间不准确（币安要求时间误差<5秒）
3. API Key权限不足

解决方法:
```bash
# 同步系统时间
sudo ntpdate -u time.apple.com

# 检查API Key权限（需要"现货交易"权限）
```

### Q5: 提示"订单数量超过限制"？

**A**: 检查配置的限额:
```yaml
binance:
  live:
    max-order-amount: 0.01  # 调大这个值
```

### Q6: 今日无法交易，提示达到限制？

**A**: 检查是否触发每日限制:
- 交易次数达到 `max-daily-trades`
- 亏损达到 `max-daily-loss`

等待第二天自动重置，或手动调整配置。

### Q7: 卖出失败，提示"没有可平仓的持仓"？

**A**: 确保:
1. 有未平仓的LONG持仓
2. 持仓状态为OPEN
3. 查询数据库确认持仓记录

### Q8: 如何查看订单记录？

**A**: 
```bash
# 查询所有订单
curl http://localhost:3131/api/trading/orders

# 查询持仓
curl http://localhost:3131/api/trading/positions/open
```

### Q9: 飞书通知没收到？

**A**: 检查飞书配置:
```yaml
feishu:
  notification:
    enabled: true
  webhook:
    url: "YOUR_WEBHOOK_URL"  # 确保URL正确
```

### Q10: 如何紧急停止所有交易？

**A**: 立即执行以下操作:
1. 修改配置关闭实盘模式:
   ```yaml
   binance:
     live:
       mode: false
   ```
2. 重启服务:
   ```bash
   ./restart.sh
   ```
3. 一键平仓所有持仓:
   ```bash
   curl -X POST http://localhost:3131/api/trading/close-all
   ```

---

## ⚠️ 风险提示

### 重要声明

1. **实盘交易风险**
   - 涉及真实资金，可能造成损失
   - 过往表现不代表未来收益
   - 请根据自身风险承受能力操作

2. **技术风险**
   - 网络中断可能导致交易失败
   - 系统故障可能影响订单执行
   - 建议设置合理的止损机制

3. **市场风险**
   - 加密货币市场波动较大
   - 黄金价格受多种因素影响
   - 可能出现滑点和流动性不足

4. **操作建议**
   - 从小额开始测试
   - 设置合理的风险限额
   - 定期检查系统日志
   - 保持API Key安全
   - 建议使用IP白名单
   - 定期备份数据库

### 免责声明

本系统仅供学习和研究使用，不构成任何投资建议。使用本系统进行实盘交易的一切后果由用户自行承担。开发者不对任何直接或间接损失承担责任。

---

## 📞 技术支持

遇到问题？

1. 查看日志: `logs/aug-trade.log`
2. 检查配置: `application.yml`
3. 查看数据库: `trading.db`
4. 联系开发者

---

## 📝 更新日志

### 2026-03-10
- ✅ 完成币安实盘交易接入
- ✅ 实现BinanceLiveAdapter适配器
- ✅ 实现LiveTradingService服务
- ✅ 实现LiveTradingController控制器
- ✅ 添加多层安全机制
- ✅ 支持飞书实时通知
- ✅ 完善配置文件和文档

---

**祝您交易顺利！🚀**
