# 🚀 Bybit黄金交易快速开始指南

## 为什么Bybit是Mac用户的最佳选择？

✅ **完美支持Mac** - 无需MT4/MT5或虚拟机  
✅ **官方REST API** - 简单易用，文档完善  
✅ **24/7交易** - 周末也可以交易黄金  
✅ **低手续费** - 0.055%（比MT4便宜一半）  
✅ **灵活杠杆** - 1-50倍可调  
✅ **最小0.001盎司** - 小资金也能参与  

---

## 📋 5分钟快速上手

### 步骤1：注册Bybit账号（5分钟）

```bash
# 访问官网
open https://www.bybit.com/

# 1. 点击"注册"
# 2. 输入邮箱和密码
# 3. 完成邮箱验证
# 4. 完成KYC身份认证（需要护照/身份证）
```

**测试网账号**（推荐先用测试网）：
```bash
open https://testnet.bybit.com/
# 测试网无需KYC，可以直接测试
```

---

### 步骤2：获取API密钥（2分钟）

**路径**：
```
登录 → 右上角头像 → 账户与安全 → API管理 → 创建新密钥
```

**权限设置**：
- ✅ 勾选"读取"
- ✅ 勾选"交易"
- ❌ 不要勾选"提现"（安全）

**记录下来**：
```
API Key: xxxxxxxxxxxxxxxx
API Secret: yyyyyyyyyyyyyyyy
```

⚠️ **API Secret只显示一次，务必保存！**

---

### 步骤3：充值USDT（可选）

如果用正式网：
```
资产 → 充值 → USDT → 选择网络（推荐TRC20手续费低）
```

如果用测试网：
```
# 测试网自动提供测试币，无需充值
```

---

### 步骤4：配置Java项目（2分钟）

编辑 `src/main/resources/application.yml`：

```yaml
# Bybit API配置
bybit:
  api:
    enabled: true                           # ✅ 改为true
    key: 你的API-Key                        # ✅ 填入步骤2获取的Key
    secret: 你的API-Secret                  # ✅ 填入步骤2获取的Secret
    testnet: true                           # 先用测试网（改为false则用正式网）
  gold:
    symbol: XAUUSDT                         # 黄金交易对
    min-qty: 0.01                           # 最小0.01盎司（约$27）
    max-qty: 0.1                            # 最大0.1盎司
    leverage: 5                             # 5倍杠杆
  risk:
    stop-loss-dollars: 15                   # 止损$15
    take-profit-dollars: 30                 # 止盈$30
```

---

### 步骤5：运行测试（1分钟）

```bash
# 在项目目录下
cd /Users/peterwang/IdeaProjects/AugTrade

# 启动应用
mvn spring-boot:run

# 查看日志（另开终端）
tail -f logs/aug-trade.log
```

---

## 🧪 测试API连接

### 方法1：使用Controller测试

创建测试接口：

```java
@RestController
@RequestMapping("/test")
public class BybitTestController {
    
    @Autowired
    private BybitTradingService bybitService;
    
    /**
     * 测试连接并获取价格
     * 访问: http://localhost:3131/api/test/bybit-price
     */
    @GetMapping("/bybit-price")
    public String testPrice() {
        try {
            BigDecimal price = bybitService.getCurrentPrice("XAUUSDT");
            return "当前黄金价格: $" + price;
        } catch (Exception e) {
            return "获取失败: " + e.getMessage();
        }
    }
    
    /**
     * 测试获取账户余额
     * 访问: http://localhost:3131/api/test/bybit-balance
     */
    @GetMapping("/bybit-balance")
    public String testBalance() {
        try {
            JsonObject balance = bybitService.getAccountBalance();
            return balance.toString();
        } catch (Exception e) {
            return "获取失败: " + e.getMessage();
        }
    }
    
    /**
     * 测试下单（小心！会真实下单）
     * 访问: http://localhost:3131/api/test/bybit-order
     */
    @GetMapping("/bybit-order")
    public String testOrder() {
        try {
            // 获取当前价格
            BigDecimal price = bybitService.getCurrentPrice("XAUUSDT");
            
            // 下单0.01盎司
            String orderId = bybitService.placeMarketOrder(
                "XAUUSDT", 
                "Buy", 
                "0.01",
                String.valueOf(price.subtract(new BigDecimal("15"))),  // 止损-$15
                String.valueOf(price.add(new BigDecimal("30")))        // 止盈+$30
            );
            
            return "下单成功 - OrderId: " + orderId;
        } catch (Exception e) {
            return "下单失败: " + e.getMessage();
        }
    }
}
```

### 方法2：使用curl命令测试

```bash
# 测试获取价格（公开接口，无需API密钥）
curl "http://localhost:3131/api/test/bybit-price"

# 测试获取余额（需要API密钥）
curl "http://localhost:3131/api/test/bybit-balance"
```

---

## 💡 实际交易示例

### 场景1：根据你的K线图做多

```java
// 假设价格跌到2710支撑位，出现反弹信号
if (signal == Signal.BUY) {
    try {
        BigDecimal currentPrice = bybitService.getCurrentPrice("XAUUSDT");
        
        // 做多0.01盎司黄金
        String orderId = bybitService.placeMarketOrder(
            "XAUUSDT",
            "Buy",
            "0.01",  // 0.01盎司 ≈ $27（5倍杠杆仅需$5.4保证金）
            String.valueOf(currentPrice.subtract(new BigDecimal("15"))),  // 止损2695
            String.valueOf(currentPrice.add(new BigDecimal("30")))        // 止盈2740
        );
        
        log.info("做多黄金成功 - OrderId: {}", orderId);
        
    } catch (Exception e) {
        log.error("交易失败", e);
    }
}
```

### 场景2：根据你的K线图做空

```java
// 假设价格反弹到2745阻力位，出现回落信号
if (signal == Signal.SELL) {
    try {
        BigDecimal currentPrice = bybitService.getCurrentPrice("XAUUSDT");
        
        // 做空0.01盎司黄金
        String orderId = bybitService.placeMarketOrder(
            "XAUUSDT",
            "Sell",
            "0.01",
            String.valueOf(currentPrice.add(new BigDecimal("15"))),       // 止损2760
            String.valueOf(currentPrice.subtract(new BigDecimal("30")))   // 止盈2715
        );
        
        log.info("做空黄金成功 - OrderId: {}", orderId);
        
    } catch (Exception e) {
        log.error("交易失败", e);
    }
}
```

---

## 📊 资金计算

### 黄金0.01盎司需要多少钱？

假设黄金价格$2700，使用5倍杠杆：

```
名义价值 = 0.01盎司 × $2700 = $27
保证金 = $27 ÷ 5 = $5.4

止损$15时最大亏损 = $15
止盈$30时最大盈利 = $30

盈亏比 = 30:15 = 2:1 ✅
```

**建议初始资金**：
- 测试网：随意（系统给测试币）
- 正式网：至少$100（留有余量）

---

## ⚠️ 风险提示

### 1. 先用测试网
```yaml
bybit:
  api:
    testnet: true  # ⭐ 必须先测试
```

**测试网地址**：https://testnet.bybit.com/

### 2. 小资金起步
```
第1天：0.01盎司（$27名义值）
第2-7天：如果盈利，继续0.01
第8天后：如果持续盈利，增加到0.02
```

### 3. 严格止损
```yaml
bybit:
  risk:
    stop-loss-dollars: 15   # 每单最多亏$15
```

### 4. 杠杆控制
```
建议：1-5倍（新手）
中级：5-10倍
高级：10-20倍
危险：20倍以上 ⚠️
```

---

## 🎯 交易策略建议（基于你的K线）

### 当前市场状态
- 价格：2735（从2750双顶回落）
- 支撑位：2710
- 阻力位：2750

### 策略A：等待支撑位做多
```
入场条件：
✅ 价格跌到2710附近
✅ 出现反弹K线（锤子线）
✅ Williams %R < -70
✅ ML预测 > 0.75

交易设置：
入场：2710
止损：2695（-$15）
止盈：2740（+$30）
手数：0.01盎司
```

### 策略B：等待反弹做空
```
入场条件：
✅ 价格反弹到2745附近
✅ 被阻力位压制
✅ Williams %R > -25
✅ ML预测 < 0.25

交易设置：
入场：2745
止损：2760（+$15）
止盈：2715（-$30）
手数：0.01盎司
```

---

## 📞 Bybit支持

**客服**：
- 在线客服：https://www.bybit.com/（右下角）
- Telegram：@BybitCN
- 邮箱：support@bybit.com

**API文档**：
- V5文档：https://bybit-exchange.github.io/docs/v5/intro
- 社区：https://www.bybit.com/en-US/help-center/

---

## ✅ 检查清单

开始交易前，确认：

- [ ] Bybit账号已注册（测试网或正式网）
- [ ] API密钥已创建并保存
- [ ] application.yml已正确配置
- [ ] BybitTradingService.java已添加到项目
- [ ] 已在测试网测试过连接
- [ ] 已在测试网测试过下单
- [ ] 理解风险和止损设置
- [ ] 准备好初始资金（测试网免费）

---

## 🎓 下一步学习

1. **完成测试网交易**
   - 测试下单、平仓
   - 验证止损止盈
   - 运行24小时观察

2. **优化策略参数**
   - 根据测试结果调整
   - 优化ML阈值
   - 调整止损止盈

3. **转入正式网**
   - 小资金开始
   - 持续监控
   - 逐步增加

---

## 🔥 立即开始

```bash
# 1. 注册测试网账号
open https://testnet.bybit.com/

# 2. 获取API密钥

# 3. 修改配置
vim src/main/resources/application.yml

# 4. 启动项目
mvn spring-boot:run

# 5. 测试连接
curl "http://localhost:3131/api/test/bybit-price"

# 6. 查看黄金价格
# 应该看到：当前黄金价格: $2735.xx
```

---

## 💰 成本对比

| 项目 | Bybit | MT4+虚拟机 | MetaApi |
|------|-------|------------|---------|
| **初始成本** | 免费 | $100/年 | $49/月 |
| **交易手续费** | 0.055% | 0.1%+ | 0.1%+ |
| **最小下单** | 0.001盎司($2.7) | 0.01手($27) | 0.01手 |
| **Mac支持** | ✅ 原生 | ⚠️ 需VM | ✅ 原生 |
| **难度** | ⭐ 简单 | ⭐⭐⭐ 复杂 | ⭐⭐ 中等 |

**Bybit明显最优！**

---

## ⚡ 关键优势总结

### 对比MT4的优势

| 特性 | Bybit | MT4 |
|------|-------|-----|
| Mac兼容性 | ✅ 完美 | ❌ 需虚拟机 |
| API集成 | ✅ 官方REST API | ⚠️ 需第三方 |
| 周末交易 | ✅ 24/7 | ❌ 周末休市 |
| 手续费 | 0.055% | 0.1%+ |
| 最小下单 | 0.001盎司 | 0.01手(10倍) |
| 杠杆 | 1-50倍 | 1-100倍 |
| 开发成本 | ✅ 零成本 | ❌ 需购买VM |

---

## 🎯 推荐配置（黄金交易）

### 保守配置（新手）
```yaml
bybit:
  gold:
    min-qty: 0.01      # 0.01盎司
    leverage: 2        # 2倍杠杆
  risk:
    stop-loss-dollars: 10
    take-profit-dollars: 20
```

**风险**：每单最多亏$10

### 中等配置（有经验）
```yaml
bybit:
  gold:
    min-qty: 0.05      # 0.05盎司
    leverage: 5        # 5倍杠杆
  risk:
    stop-loss-dollars: 15
    take-profit-dollars: 30
```

**风险**：每单最多亏$15

### 激进配置（专业）
```yaml
bybit:
  gold:
    min-qty: 0.1       # 0.1盎司
    leverage: 10       # 10倍杠杆
  risk:
    stop-loss-dollars: 20
    take-profit-dollars: 50
```

**风险**：每单最多亏$20

---

## 📱 监控和管理

### Bybit App
```
下载Bybit手机App
可以随时查看持仓和盈亏
支持手动平仓
```

### Web界面
```
https://www.bybit.com/trade/usdt/XAUUSDT
实时查看K线和持仓
```

---

## 🛡️ 安全建议

1. **API密钥安全**
   - ❌ 不要分享给任何人
   - ❌ 不要上传到GitHub
   - ✅ 使用环境变量存储

2. **权限最小化**
   - ✅ 只开启必要权限
   - ❌ 不要开启提现权限

3. **IP白名单**
   - 建议在Bybit设置IP白名单
   - 只允许你的IP访问

4. **定期更换**
   - 建议每3个月更换一次API密钥

---

## 🎓 学习资源

**Bybit学院**：
- https://learn.bybit.com/zh-TW/

**API文档**：
- https://bybit-exchange.github.io/docs/v5/intro

**社区**：
- Telegram: @BybitCN
- Discord: https://discord.gg/bybit

---

**现在你可以在Mac上完美运行黄金量化交易了！** 🚀

所有代码已就绪，只需：
1. 注册Bybit测试网账号
2. 获取API密钥
3. 修改配置文件
4. 启动项目测试

有任何问题随时问我！
