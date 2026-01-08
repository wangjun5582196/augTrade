# 🟡 Bybit黄金交易集成指南

## 为什么选择Bybit？⭐⭐⭐⭐⭐

### 优势
- ✅ **完美支持Mac**（无需MT4/MT5）
- ✅ **官方REST API**（文档完善）
- ✅ **WebSocket实时行情**
- ✅ **支持黄金交易**（XAUUSDT）
- ✅ **低手续费**（Maker 0.02%, Taker 0.055%）
- ✅ **高杠杆**（最高50倍）
- ✅ **24/7交易**

### Bybit黄金产品
- **现货**：XAUUSDT（黄金/USDT）
- **合约**：XAUUSDT永续合约
- **最小下单**：0.001盎司

---

## 快速集成

### 1. 注册Bybit账号

**官网**：https://www.bybit.com/

**步骤**：
1. 注册账号（支持中文）
2. 完成KYC认证
3. 充值USDT
4. 开通合约交易

### 2. 获取API密钥

**路径**：
```
登录 → 账户与安全 → API管理 → 创建新密钥
```

**权限设置**：
- ✅ 读取权限
- ✅ 交易权限
- ❌ 提现权限（安全起见不开启）

**记录信息**：
```
API Key: your-api-key
API Secret: your-api-secret
```

---

## Java集成实现

### Maven依赖

无需额外依赖，使用现有的OkHttp和Gson即可。

### Bybit交易服务

创建 `BybitTradingService.java`：

```java
package com.ltp.peter.augtrade.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bybit交易服务
 * 支持黄金XAUUSDT交易
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class BybitTradingService {
    
    private final OkHttpClient httpClient;
    private final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    @Value("${bybit.api.key:}")
    private String apiKey;
    
    @Value("${bybit.api.secret:}")
    private String apiSecret;
    
    @Value("${bybit.api.testnet:false}")
    private boolean testnet;
    
    // Bybit API地址
    private static final String MAINNET_URL = "https://api.bybit.com";
    private static final String TESTNET_URL = "https://api-testnet.bybit.com";
    
    public BybitTradingService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 获取API基础URL
     */
    private String getBaseUrl() {
        return testnet ? TESTNET_URL : MAINNET_URL;
    }
    
    /**
     * 生成签名
     */
    private String generateSignature(String queryString, long timestamp) {
        try {
            String signStr = timestamp + apiKey + "5000" + queryString;
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            
            byte[] hash = sha256_HMAC.doFinal(signStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("签名生成失败", e);
            return "";
        }
    }
    
    /**
     * 获取账户余额
     */
    public JsonObject getAccountBalance() throws Exception {
        long timestamp = System.currentTimeMillis();
        String queryString = "";
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/account/wallet-balance?accountType=UNIFIED";
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    log.info("获取账户余额成功");
                    return json.getAsJsonObject("result");
                } else {
                    log.error("获取账户余额失败: {}", json.get("retMsg").getAsString());
                }
            }
        }
        throw new Exception("Failed to get account balance");
    }
    
    /**
     * 获取实时价格（公开接口，无需签名）
     */
    public BigDecimal getCurrentPrice(String symbol) throws Exception {
        String url = getBaseUrl() + "/v5/market/tickers?category=linear&symbol=" + symbol;
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonArray list = json.getAsJsonObject("result").getAsJsonArray("list");
                    if (list.size() > 0) {
                        JsonObject ticker = list.get(0).getAsJsonObject();
                        String lastPrice = ticker.get("lastPrice").getAsString();
                        
                        log.debug("Bybit获取{}价格: {}", symbol, lastPrice);
                        return new BigDecimal(lastPrice);
                    }
                }
            }
        }
        throw new Exception("Failed to get price for " + symbol);
    }
    
    /**
     * 下单（永续合约）
     * 
     * @param symbol 交易对（XAUUSDT）
     * @param side Buy/Sell
     * @param qty 数量（盎司）
     * @param stopLoss 止损价
     * @param takeProfit 止盈价
     */
    public String placeOrder(String symbol, String side, String qty, 
                           String stopLoss, String takeProfit) throws Exception {
        long timestamp = System.currentTimeMillis();
        
        JsonObject orderParams = new JsonObject();
        orderParams.addProperty("category", "linear");
        orderParams.addProperty("symbol", symbol);
        orderParams.addProperty("side", side);  // Buy/Sell
        orderParams.addProperty("orderType", "Market");
        orderParams.addProperty("qty", qty);
        orderParams.addProperty("timeInForce", "GTC");
        
        if (stopLoss != null) {
            orderParams.addProperty("stopLoss", stopLoss);
        }
        if (takeProfit != null) {
            orderParams.addProperty("takeProfit", takeProfit);
        }
        
        String queryString = orderParams.toString();
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/order/create";
        RequestBody body = RequestBody.create(queryString, JSON);
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    String orderId = json.getAsJsonObject("result").get("orderId").getAsString();
                    log.info("✅ Bybit下单成功 - OrderId: {}, Symbol: {}, Side: {}, Qty: {}", 
                            orderId, symbol, side, qty);
                    return orderId;
                } else {
                    String error = json.get("retMsg").getAsString();
                    log.error("❌ Bybit下单失败 - {}", error);
                    throw new Exception("Order failed: " + error);
                }
            }
        }
        throw new Exception("Failed to place order");
    }
    
    /**
     * 平仓
     */
    public boolean closePosition(String symbol, String side, String qty) throws Exception {
        // Bybit平仓就是反向开单
        String closeSide = side.equals("Buy") ? "Sell" : "Buy";
        String orderId = placeOrder(symbol, closeSide, qty, null, null);
        return orderId != null && !orderId.isEmpty();
    }
    
    /**
     * 获取持仓列表
     */
    public JsonArray getPositions(String symbol) throws Exception {
        long timestamp = System.currentTimeMillis();
        String queryString = "category=linear&symbol=" + symbol;
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/position/list?" + queryString;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonArray list = json.getAsJsonObject("result").getAsJsonArray("list");
                    log.info("Bybit当前持仓数量: {}", list.size());
                    return list;
                }
            }
        }
        throw new Exception("Failed to get positions");
    }
    
    /**
     * 设置杠杆
     */
    public boolean setLeverage(String symbol, int leverage) throws Exception {
        long timestamp = System.currentTimeMillis();
        
        JsonObject params = new JsonObject();
        params.addProperty("category", "linear");
        params.addProperty("symbol", symbol);
        params.addProperty("buyLeverage", String.valueOf(leverage));
        params.addProperty("sellLeverage", String.valueOf(leverage));
        
        String queryString = params.toString();
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/position/set-leverage";
        RequestBody body = RequestBody.create(queryString, JSON);
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", "5000")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    log.info("设置{}杠杆为{}倍成功", symbol, leverage);
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 获取K线数据
     */
    public JsonArray getKlines(String symbol, String interval, int limit) throws Exception {
        String url = String.format(
            "%s/v5/market/kline?category=linear&symbol=%s&interval=%s&limit=%d",
            getBaseUrl(), symbol, interval, limit
        );
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    return json.getAsJsonObject("result").getAsJsonArray("list");
                }
            }
        }
        throw new Exception("Failed to get klines");
    }
}
```

---

## 配置文件

### application.yml

```yaml
# Bybit API配置
bybit:
  api:
    key: your-api-key               # ✅ 替换为你的API Key
    secret: your-api-secret         # ✅ 替换为你的API Secret
    testnet: false                  # true=测试网, false=正式网
    
  # 黄金交易配置
  gold:
    symbol: XAUUSDT                 # 黄金/USDT
    min-qty: 0.001                  # 最小下单0.001盎司
    max-qty: 1.0                    # 最大下单1盎司
    leverage: 5                     # 杠杆倍数（1-50倍）
    
  # 风控配置
  risk:
    stop-loss-dollars: 15           # 止损$15
    take-profit-dollars: 30         # 止盈$30
```

---

## 使用示例

### 基础操作

```java
@Autowired
private BybitTradingService bybitService;

// 1. 获取黄金价格
BigDecimal goldPrice = bybitService.getCurrentPrice("XAUUSDT");
log.info("当前黄金价格: ${}", goldPrice);

// 2. 设置杠杆（5倍）
bybitService.setLeverage("XAUUSDT", 5);

// 3. 做多黄金
String buyOrderId = bybitService.placeOrder(
    "XAUUSDT",      // 黄金
    "Buy",          // 做多
    "0.01",         // 0.01盎司
    "2650.0",       // 止损
    "2680.0"        // 止盈
);

// 4. 做空黄金
String sellOrderId = bybitService.placeOrder(
    "XAUUSDT",      // 黄金
    "Sell",         // 做空
    "0.01",         // 0.01盎司
    "2680.0",       // 止损
    "2650.0"        // 止盈
);

// 5. 查询持仓
JsonArray positions = bybitService.getPositions("XAUUSDT");

// 6. 平仓
bybitService.closePosition("XAUUSDT", "Buy", "0.01");
```

---

## 与现有系统集成

### 修改TradingScheduler使用Bybit

```java
@Autowired
private BybitTradingService bybitService;

@Value("${bybit.gold.symbol:XAUUSDT}")
private String bybitSymbol;

// 在策略执行中使用Bybit
public void executeStrategyWithBybit() {
    try {
        // 获取价格
        BigDecimal price = bybitService.getCurrentPrice(bybitSymbol);
        
        // 执行策略
        Signal signal = advancedStrategyService.mlEnhancedWilliamsStrategy(bybitSymbol);
        
        if (signal == Signal.BUY) {
            // 通过Bybit做多
            bybitService.placeOrder(bybitSymbol, "Buy", "0.01", 
                    String.valueOf(price.subtract(new BigDecimal("15"))),
                    String.valueOf(price.add(new BigDecimal("30"))));
        }
    } catch (Exception e) {
        log.error("Bybit交易失败", e);
    }
}
```

---

## Bybit vs MT4/MT5

| 特性 | Bybit | MT4/MT5 |
|------|-------|---------|
| **Mac支持** | ✅ 完美 | ❌ 需虚拟机/MT5 |
| **API易用性** | ✅ 简单 | ⚠️ 复杂 |
| **手续费** | 0.055% | 0.1%+ |
| **杠杆** | 1-50倍 | 1-100倍 |
| **最小下单** | 0.001盎司 | 0.01手(1盎司) |
| **7×24交易** | ✅ 是 | ❌ 周末休市 |
| **文档** | ✅ 完善 | ⚠️ 一般 |

---

## 注意事项

### 1. 测试网先行
```yaml
bybit:
  api:
    testnet: true  # 先用测试网
```

测试网地址：https://testnet.bybit.com/

### 2. 风险控制
- 建议杠杆不超过5倍
- 设置合理的止损止盈
- 从小资金开始测试

### 3. 手续费
- Maker: 0.02%
- Taker: 0.055%
- 比MT4便宜很多！

### 4. API限流
- 每秒最多10个请求
- 建议加延迟控制

---

## 快速开始

```bash
# 1. 注册Bybit账号并获取API密钥
# https://www.bybit.com/

# 2. 修改配置文件
vim src/main/resources/application.yml
# 填入你的API Key和Secret

# 3. 运行测试
mvn spring-boot:run

# 4. 查看日志
tail -f logs/aug-trade.log
```

---

**Bybit是Mac用户的最佳选择！** 🎯

无需MT4/MT5，完全API驱动，支持完美！
