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
 * Bybit交易服务
 * 支持黄金XAUUSDT交易（永续合约）
 * 
 * 官方文档: https://bybit-exchange.github.io/docs/v5/intro
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
    
    @Value("${bybit.api.enabled:false}")
    private boolean enabled;
    
    // Bybit API地址
    private static final String MAINNET_URL = "https://api.bybit.com";
    private static final String TESTNET_URL = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";
    
    public BybitTradingService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 检查Bybit是否启用
     */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isEmpty();
    }
    
    /**
     * 获取API基础URL
     */
    private String getBaseUrl() {
        return testnet ? TESTNET_URL : MAINNET_URL;
    }
    
    /**
     * 生成签名（Bybit V5 API签名方法）
     */
    private String generateSignature(String queryString, long timestamp) {
        try {
            String signStr = timestamp + apiKey + RECV_WINDOW + queryString;
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(
                apiSecret.getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
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
            log.error("Bybit签名生成失败", e);
            return "";
        }
    }
    
    /**
     * 获取账户余额
     */
    public JsonObject getAccountBalance() throws Exception {
        if (!isEnabled()) {
            throw new Exception("Bybit未启用");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = "accountType=UNIFIED";
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/account/wallet-balance?" + queryString;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .addHeader("Content-Type", "application/json")
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonObject result = json.getAsJsonObject("result");
                    log.info("✅ Bybit账户余额获取成功");
                    return result;
                } else {
                    String error = json.get("retMsg").getAsString();
                    log.error("❌ Bybit获取余额失败: {}", error);
                    throw new Exception("Failed to get balance: " + error);
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
        
        log.debug("获取价格 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                log.debug("价格响应: {}", jsonData);
                
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonArray list = json.getAsJsonObject("result").getAsJsonArray("list");
                    if (list.size() > 0) {
                        JsonObject ticker = list.get(0).getAsJsonObject();
                        String lastPrice = ticker.get("lastPrice").getAsString();
                        
                        log.info("✅ Bybit获取{}价格成功: ${}", symbol, lastPrice);
                        return new BigDecimal(lastPrice);
                    } else {
                        log.error("❌ {}没有价格数据，可能测试网不支持此交易对", symbol);
                        throw new Exception(symbol + " not available on testnet");
                    }
                } else {
                    String error = json.get("retMsg").getAsString();
                    log.error("❌ 获取价格失败: {} - 可能测试网不支持{}", error, symbol);
                    throw new Exception("Failed to get price: " + error);
                }
            }
        }
        throw new Exception("Failed to get price for " + symbol);
    }
    
    /**
     * 市价下单
     * 
     * @param symbol 交易对（XAUUSDT）
     * @param side Buy/Sell
     * @param qty 数量（盎司）
     * @param stopLoss 止损价（可选）
     * @param takeProfit 止盈价（可选）
     * @return 订单ID
     */
    public String placeMarketOrder(String symbol, String side, String qty, 
                                  String stopLoss, String takeProfit) throws Exception {
        if (!isEnabled()) {
            throw new Exception("Bybit未启用");
        }
        
        long timestamp = System.currentTimeMillis();
        
        JsonObject orderParams = new JsonObject();
        orderParams.addProperty("category", "linear");
        orderParams.addProperty("symbol", symbol);
        orderParams.addProperty("side", side);
        orderParams.addProperty("orderType", "Market");
        orderParams.addProperty("qty", qty);
        orderParams.addProperty("timeInForce", "GTC");
        
        if (stopLoss != null && !stopLoss.isEmpty()) {
            orderParams.addProperty("stopLoss", stopLoss);
        }
        if (takeProfit != null && !takeProfit.isEmpty()) {
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
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .addHeader("Content-Type", "application/json")
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
     * 平仓（通过反向开单）
     */
    public boolean closePosition(String symbol, String side, String qty) throws Exception {
        String closeSide = side.equals("Buy") ? "Sell" : "Buy";
        String orderId = placeMarketOrder(symbol, closeSide, qty, null, null);
        return orderId != null && !orderId.isEmpty();
    }
    
    /**
     * 获取持仓列表
     */
    public JsonArray getPositions(String symbol) throws Exception {
        if (!isEnabled()) {
            throw new Exception("Bybit未启用");
        }
        
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
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonArray list = json.getAsJsonObject("result").getAsJsonArray("list");
                    log.info("Bybit当前{}持仓数量: {}", symbol, list.size());
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
        if (!isEnabled()) {
            throw new Exception("Bybit未启用");
        }
        
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
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    log.info("✅ 设置{}杠杆为{}倍成功", symbol, leverage);
                    return true;
                } else {
                    log.warn("设置杠杆失败: {}", json.get("retMsg").getAsString());
                }
            }
        }
        return false;
    }
    
    /**
     * 获取K线数据（公开接口）
     * 
     * @param symbol 交易对，如 XAUUSDT
     * @param interval K线周期：1 3 5 15 30 60 120 240 360 720（分钟）, D（日）, W（周）, M（月）
     * @param limit 数量限制，最大200
     */
    public JsonArray getKlines(String symbol, String interval, int limit) throws Exception {
        // Bybit API需要的interval格式：1, 5, 15, 30, 60等（分钟数字）
        String url = String.format(
            "%s/v5/market/kline?category=linear&symbol=%s&interval=%s&limit=%d",
            getBaseUrl(), symbol, interval, limit
        );
        
        log.debug("获取K线 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                log.debug("K线响应: {}", jsonData);
                
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.get("retCode").getAsInt() == 0) {
                    JsonArray list = json.getAsJsonObject("result").getAsJsonArray("list");
                    log.debug("成功获取{}条K线数据", list.size());
                    return list;
                } else {
                    String error = json.get("retMsg").getAsString();
                    log.error("获取K线失败: {}", error);
                    throw new Exception("Failed to get klines: " + error);
                }
            } else {
                log.error("HTTP请求失败: {}", response.code());
            }
        }
        throw new Exception("Failed to get klines for " + symbol);
    }
    
    /**
     * 取消订单
     */
    public boolean cancelOrder(String symbol, String orderId) throws Exception {
        if (!isEnabled()) {
            throw new Exception("Bybit未启用");
        }
        
        long timestamp = System.currentTimeMillis();
        
        JsonObject params = new JsonObject();
        params.addProperty("category", "linear");
        params.addProperty("symbol", symbol);
        params.addProperty("orderId", orderId);
        
        String queryString = params.toString();
        String signature = generateSignature(queryString, timestamp);
        
        String url = getBaseUrl() + "/v5/order/cancel";
        RequestBody body = RequestBody.create(queryString, JSON);
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-BAPI-API-KEY", apiKey)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .addHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                return json.get("retCode").getAsInt() == 0;
            }
        }
        return false;
    }

    public static void main(String[] args) {

    }
}
