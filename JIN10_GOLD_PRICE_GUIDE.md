# 金十数据黄金价格接口使用指南

**功能：** 从金十数据（jin10.com）实时获取黄金（XAU/USD）价格  
**实现日期：** 2026-01-08  
**状态：** ✅ 已完成并可用  

---

## 📋 功能概述

系统已集成金十数据API，支持实时获取：
- 现货黄金价格（XAU/USD）
- 买入价/卖出价
- 涨跌幅、最高价、最低价等详细行情
- 多数据源价格对比（金十 vs Binance）

---

## 🚀 使用方法

### 方法1：通过HTTP接口调用（推荐）

启动系统后，可以通过以下接口获取金十黄金价格：

#### 1️⃣ 获取黄金当前价格

```bash
# 请求
GET http://localhost:3131/api/market/jin10/gold/price

# 响应示例
{
  "success": true,
  "price": 2687.50,
  "currency": "USD",
  "unit": "美元/盎司",
  "source": "金十数据",
  "timestamp": 1704730091234
}
```

**使用curl测试：**
```bash
curl http://localhost:3131/api/market/jin10/gold/price
```

---

#### 2️⃣ 获取黄金详细行情

```bash
# 请求
GET http://localhost:3131/api/market/jin10/gold/detail

# 响应示例
{
  "success": true,
  "data": {
    "currentPrice": "2687.50",
    "bidPrice": "2687.30",
    "askPrice": "2687.70",
    "change": "+12.80",
    "changePercent": "0.48",
    "highPrice": "2692.10",
    "lowPrice": "2674.20",
    "openPrice": "2674.70"
  },
  "source": "金十数据",
  "symbol": "XAUUSD",
  "timestamp": 1704730091234
}
```

**使用curl测试：**
```bash
curl http://localhost:3131/api/market/jin10/gold/detail
```

---

#### 3️⃣ 多数据源价格对比

```bash
# 请求
GET http://localhost:3131/api/market/gold/compare

# 响应示例
{
  "success": true,
  "prices": {
    "jin10": 2687.50,
    "binance_paxg": 2688.12
  },
  "timestamp": 1704730091234
}
```

**使用curl测试：**
```bash
curl http://localhost:3131/api/market/gold/compare
```

---

### 方法2：在代码中直接调用

```java
@Autowired
private RealMarketDataService realMarketDataService;

// 获取金十黄金价格
BigDecimal goldPrice = realMarketDataService.getGoldPriceFromJin10();
System.out.println("当前黄金价格: $" + goldPrice);

// 获取详细行情
JsonObject detail = realMarketDataService.getGoldDetailFromJin10();
if (detail != null) {
    String currentPrice = detail.get("last_price").getAsString();
    String changePercent = detail.get("change_percent").getAsString();
    System.out.println("价格: $" + currentPrice + ", 涨跌幅: " + changePercent + "%");
}
```

---

## 📊 返回数据说明

### 价格接口字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| success | boolean | 是否成功 | true |
| price | number | 当前价格 | 2687.50 |
| currency | string | 货币单位 | "USD" |
| unit | string | 计量单位 | "美元/盎司" |
| source | string | 数据源 | "金十数据" |
| timestamp | long | 时间戳（毫秒） | 1704730091234 |

### 详细行情字段

| 字段 | 说明 | 示例 |
|------|------|------|
| currentPrice | 当前价格 | "2687.50" |
| bidPrice | 买入价 | "2687.30" |
| askPrice | 卖出价 | "2687.70" |
| change | 涨跌额 | "+12.80" |
| changePercent | 涨跌幅 | "0.48" |
| highPrice | 最高价 | "2692.10" |
| lowPrice | 最低价 | "2674.20" |
| openPrice | 开盘价 | "2674.70" |

---

## 🔧 技术实现

### 核心代码

**服务类：** `RealMarketDataService.java`
```java
public BigDecimal getGoldPriceFromJin10() {
    String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
    // ... API调用逻辑
    return price;
}

public JsonObject getGoldDetailFromJin10() {
    String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
    // ... 详细数据获取
    return data;
}
```

**控制器：** `MarketDataController.java`
- `/api/market/jin10/gold/price` - 简单价格
- `/api/market/jin10/gold/detail` - 详细行情
- `/api/market/gold/compare` - 多源对比

---

## ⚠️ 注意事项

### 1. API调用频率
- 金十数据API有频率限制（具体限制未公开）
- 建议：每5-10秒调用一次
- 避免短时间内大量请求

### 2. 网络要求
- 需要能访问金十数据的API（flash-api.jin10.com）
- 如果无法访问，会返回null或失败响应
- 建议配置备用数据源（Bybit、Binance等）

### 3. 数据准确性
- 金十数据提供的是现货黄金（XAU/USD）实时报价
- 与期货价格可能略有差异
- 建议多源对比验证

### 4. 错误处理
```java
BigDecimal price = realMarketDataService.getGoldPriceFromJin10();
if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
    // 获取失败，使用备用数据源
    price = realMarketDataService.getPriceFromBinance("PAXGUSDT");
}
```

---

## 🔍 测试验证

### 1. 启动系统
```bash
cd /Users/peterwang/IdeaProjects/AugTrade
./restart.sh
```

### 2. 测试金十价格接口
```bash
# 测试简单价格
curl http://localhost:3131/api/market/jin10/gold/price

# 测试详细行情
curl http://localhost:3131/api/market/jin10/gold/detail

# 测试价格对比
curl http://localhost:3131/api/market/gold/compare
```

### 3. 查看日志
```bash
tail -f logs/aug-trade.log | grep "金十"
```

**成功日志示例：**
```
2026-01-08 23:48:00 INFO  - 📊 接收到获取金十黄金价格请求
2026-01-08 23:48:01 INFO  - ✅ 从金十数据获取黄金价格: $2687.50 (现货金)
2026-01-08 23:48:01 INFO  - ✅ 成功获取金十黄金价格: $2687.50
```

---

## 🌐 浏览器测试

打开浏览器访问：

1. **简单价格：**
   ```
   http://localhost:3131/api/market/jin10/gold/price
   ```

2. **详细行情：**
   ```
   http://localhost:3131/api/market/jin10/gold/detail
   ```

3. **价格对比：**
   ```
   http://localhost:3131/api/market/gold/compare
   ```

---

## 📈 集成到交易系统

### 替换Bybit价格源

如果想使用金十数据作为主要价格源，可以修改配置：

```yaml
# application.yml
bybit:
  api:
    enabled: false  # 关闭Bybit
    
trading:
  data-collector:
    source: jin10  # 使用金十数据
```

然后在 `TradingScheduler` 中调用：

```java
// 使用金十数据获取价格
BigDecimal currentPrice = realMarketDataService.getGoldPriceFromJin10();
if (currentPrice == null) {
    // 备用：使用Bybit
    currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
}
```

---

## 🎯 使用场景

### 1. 实时价格监控
定期获取金十价格，与其他数据源对比验证

### 2. 策略回测
使用金十历史数据进行策略验证（需要额外实现历史数据接口）

### 3. 多源价格仲裁
金十 vs Bybit vs Binance，选择最合理的价格

### 4. 价格预警
监控金十价格突破关键位，触发交易信号

---

## 📝 示例代码

### 完整示例：获取并显示金十黄金价格

```java
@Service
public class GoldPriceMonitor {
    
    @Autowired
    private RealMarketDataService marketDataService;
    
    @Scheduled(fixedRate = 10000) // 每10秒执行
    public void monitorGoldPrice() {
        // 获取金十价格
        BigDecimal jin10Price = marketDataService.getGoldPriceFromJin10();
        
        if (jin10Price != null && jin10Price.compareTo(BigDecimal.ZERO) > 0) {
            log.info("金十黄金价格: ${}", jin10Price);
            
            // 获取详细行情
            JsonObject detail = marketDataService.getGoldDetailFromJin10();
            if (detail != null) {
                String changePercent = detail.get("change_percent").getAsString();
                log.info("今日涨跌: {}%", changePercent);
            }
        } else {
            log.warn("无法获取金十数据，使用备用源");
        }
    }
}
```

---

## 🔗 相关资源

- **金十数据官网：** https://www.jin10.com/
- **金十数据行情页：** https://www.jin10.com/quote
- **现货黄金实时图：** https://quote.jin10.com/detail?code=XAUUSD

---

## ✅ 总结

金十数据黄金价格接口已完整实现，支持：
- ✅ 实时价格获取
- ✅ 详细行情数据
- ✅ HTTP REST API调用
- ✅ 代码直接调用
- ✅ 多数据源对比
- ✅ 完善的错误处理

**现在就可以使用！重启系统后访问接口即可获取金十黄金价格。**

---

**文档生成：** 2026-01-08 23:48  
**作者：** AI系统  
**状态：** ✅ 功能完整可用
