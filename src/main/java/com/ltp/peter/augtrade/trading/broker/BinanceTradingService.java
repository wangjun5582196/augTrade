package com.ltp.peter.augtrade.trading.broker;

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
 * 币安交易服务
 * 支持黄金PAXGUSDT交易（现货）
 * 
 * 官方文档: https://binance-docs.github.io/apidocs/spot/en/
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class BinanceTradingService {
    
    private final OkHttpClient httpClient;
    private final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    @Value("${binance.api.key:}")
    private String apiKey;
    
    @Value("${binance.api.secret:}")
    private String apiSecret;
    
    @Value("${binance.api.testnet:false}")
    private boolean testnet;
    
    @Value("${binance.api.enabled:false}")
    private boolean enabled;
    
    // 币安 API地址
    private static final String MAINNET_URL = "https://api.binance.com";
    private static final String TESTNET_URL = "https://testnet.binance.vision";
    private static final String RECV_WINDOW = "5000";
    
    public BinanceTradingService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 检查币安是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 获取API基础URL
     */
    private String getBaseUrl() {
        return testnet ? TESTNET_URL : MAINNET_URL;
    }
    
    /**
     * 生成签名（币安签名方法）
     */
    private String generateSignature(String queryString) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(
                apiSecret.getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
            sha256_HMAC.init(secret_key);
            
            byte[] hash = sha256_HMAC.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("币安签名生成失败", e);
            return "";
        }
    }
    
    /**
     * 获取账户余额
     */
    public JsonObject getAccountBalance() throws Exception {
        if (!isEnabled()) {
            throw new Exception("币安未启用");
        }
        
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("币安API Key或Secret未配置");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp + "&recvWindow=" + RECV_WINDOW;
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/api/v3/account?" + queryString + "&signature=" + signature;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                log.info("✅ 币安账户余额获取成功");
                return json;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("❌ 币安获取余额失败: {}", errorBody);
                throw new Exception("Failed to get balance: " + errorBody);
            }
        }
    }
    
    /**
     * 获取实时价格（公开接口，无需签名）
     * 
     * @param symbol 交易对（PAXGUSDT - Paxos Gold, XAUUSDT - Tether Gold）
     * @return 当前价格
     */
    public BigDecimal getCurrentPrice(String symbol) throws Exception {
        String url = getBaseUrl() + "/api/v3/ticker/price?symbol=" + symbol;
        
        log.debug("获取币安价格 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                log.debug("币安价格响应: {}", jsonData);
                
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.has("price")) {
                    String price = json.get("price").getAsString();
                    log.info("✅ 币安获取{}价格成功: ${}", symbol, price);
                    return new BigDecimal(price);
                } else {
                    String error = json.has("msg") ? json.get("msg").getAsString() : "Unknown error";
                    log.error("❌ 币安获取价格失败: {}", error);
                    throw new Exception("Failed to get price: " + error);
                }
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("❌ 币安获取价格失败: HTTP {}, {}", response.code(), errorBody);
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 市价下单
     * 
     * @param symbol 交易对（PAXGUSDT）
     * @param side BUY/SELL
     * @param quantity 数量
     * @return 订单ID
     */
    public String placeMarketOrder(String symbol, String side, String quantity) throws Exception {
        if (!isEnabled()) {
            throw new Exception("币安未启用");
        }
        
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("币安API Key或Secret未配置");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "symbol=%s&side=%s&type=MARKET&quantity=%s&timestamp=%d&recvWindow=%s",
            symbol, side, quantity, timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/api/v3/order?" + queryString + "&signature=" + signature;
        
        RequestBody body = RequestBody.create("", JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.has("orderId")) {
                    String orderId = json.get("orderId").getAsString();
                    log.info("✅ 币安下单成功 - OrderId: {}, Symbol: {}, Side: {}, Qty: {}", 
                            orderId, symbol, side, quantity);
                    return orderId;
                } else {
                    String error = json.has("msg") ? json.get("msg").getAsString() : "Unknown error";
                    log.error("❌ 币安下单失败 - {}", error);
                    throw new Exception("Order failed: " + error);
                }
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("❌ 币安下单失败: HTTP {}, {}", response.code(), errorBody);
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 获取K线数据（公开接口）
     * 
     * @param symbol 交易对，如 PAXGUSDT
     * @param interval K线周期：1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M
     * @param limit 数量限制，最大1000，默认500
     */
    public JsonArray getKlines(String symbol, String interval, int limit) throws Exception {
        String url = String.format(
            "%s/api/v3/klines?symbol=%s&interval=%s&limit=%d",
            getBaseUrl(), symbol, interval, limit
        );
        
        log.debug("获取币安K线 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                log.debug("币安K线响应: {}", jsonData.substring(0, Math.min(200, jsonData.length())));
                
                JsonArray klines = JsonParser.parseString(jsonData).getAsJsonArray();
                log.debug("成功获取{}条K线数据", klines.size());
                return klines;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("获取币安K线失败: HTTP {}, {}", response.code(), errorBody);
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 获取24小时价格变动统计
     * 
     * @param symbol 交易对
     * @return 24小时统计数据
     */
    public JsonObject get24hrTicker(String symbol) throws Exception {
        String url = getBaseUrl() + "/api/v3/ticker/24hr?symbol=" + symbol;
        
        log.debug("获取币安24h统计 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                log.info("✅ 币安获取{}的24h统计成功", symbol);
                return json;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("获取币安24h统计失败: HTTP {}, {}", response.code(), errorBody);
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 获取交易对信息
     * 
     * @param symbol 交易对
     * @return 交易对详细信息
     */
    public JsonObject getSymbolInfo(String symbol) throws Exception {
        String url = getBaseUrl() + "/api/v3/exchangeInfo?symbol=" + symbol;
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.has("symbols")) {
                    JsonArray symbols = json.getAsJsonArray("symbols");
                    if (symbols.size() > 0) {
                        log.info("✅ 币安获取{}交易对信息成功", symbol);
                        return symbols.get(0).getAsJsonObject();
                    }
                }
                throw new Exception("Symbol not found: " + symbol);
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 取消订单
     */
    public boolean cancelOrder(String symbol, String orderId) throws Exception {
        if (!isEnabled()) {
            throw new Exception("币安未启用");
        }
        
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("币安API Key或Secret未配置");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "symbol=%s&orderId=%s&timestamp=%d&recvWindow=%s",
            symbol, orderId, timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/api/v3/order?" + queryString + "&signature=" + signature;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .delete()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                log.info("✅ 币安取消订单成功: {}", orderId);
                return true;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("币安取消订单失败: {}", errorBody);
                return false;
            }
        }
    }
    
    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            String url = getBaseUrl() + "/api/v3/ping";
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("✅ 币安连接测试成功");
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("币安连接测试失败", e);
        }
        return false;
    }
}
