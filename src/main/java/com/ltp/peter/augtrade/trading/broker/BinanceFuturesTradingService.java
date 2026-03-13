package com.ltp.peter.augtrade.trading.broker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ltp.peter.augtrade.entity.Position;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 币安合约交易服务（U本位合约）
 * 
 * API文档: https://binance-docs.github.io/apidocs/futures/cn/
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@Service
public class BinanceFuturesTradingService {
    
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
    
    // 币安合约API地址
    private static final String MAINNET_URL = "https://fapi.binance.com";
    private static final String TESTNET_URL = "https://testnet.binancefuture.com";
    private static final String RECV_WINDOW = "5000";
    
    public BinanceFuturesTradingService() {
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
            log.error("生成签名失败", e);
            return "";
        }
    }
    
    /**
     * 获取当前价格
     */
    public BigDecimal getCurrentPrice(String symbol) throws Exception {
        String url = getBaseUrl() + "/fapi/v1/ticker/price?symbol=" + symbol;
        
        log.debug("获取合约价格 URL: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.has("price")) {
                    String price = json.get("price").getAsString();
                    log.info("✅ 币安合约获取{}价格成功: ${}", symbol, price);
                    return new BigDecimal(price);
                } else {
                    String error = json.has("msg") ? json.get("msg").getAsString() : "Unknown error";
                    throw new Exception("获取价格失败: " + error);
                }
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 下市价单（合约）
     * 
     * @param symbol 交易对
     * @param side BUY/SELL
     * @param positionSide LONG/SHORT（双向持仓模式）
     * @param quantity 数量
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String placeMarketOrder(String symbol, String side, String positionSide, 
                                  String quantity, int leverage) throws Exception {
        if (!enabled) {
            throw new Exception("币安服务未启用");
        }
        
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            throw new Exception("币安API Key或Secret未配置");
        }
        
        // 1. 设置杠杆
        setLeverage(symbol, leverage);
        
        // 2. 设置双向持仓模式
        setDualSidePosition(true);
        
        // 3. 下单
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "symbol=%s&side=%s&positionSide=%s&type=MARKET&quantity=%s&timestamp=%d&recvWindow=%s",
            symbol, side, positionSide, quantity, timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;
        
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
                    log.info("✅ 币安合约下单成功 - OrderId: {}, Symbol: {}, Side: {}, PositionSide: {}, Qty: {}", 
                            orderId, symbol, side, positionSide, quantity);
                    return orderId;
                } else {
                    String error = json.has("msg") ? json.get("msg").getAsString() : "Unknown error";
                    throw new Exception("下单失败: " + error);
                }
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 设置杠杆倍数
     */
    private void setLeverage(String symbol, int leverage) throws Exception {
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "symbol=%s&leverage=%d&timestamp=%d&recvWindow=%s",
            symbol, leverage, timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/fapi/v1/leverage?" + queryString + "&signature=" + signature;
        
        RequestBody body = RequestBody.create("", JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("✅ 设置杠杆成功: {}x", leverage);
            } else {
                log.warn("设置杠杆失败，继续执行");
            }
        }
    }
    
    /**
     * 设置双向持仓模式
     */
    private void setDualSidePosition(boolean dualSidePosition) throws Exception {
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "dualSidePosition=%s&timestamp=%d&recvWindow=%s",
            dualSidePosition, timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/fapi/v1/positionSide/dual?" + queryString + "&signature=" + signature;
        
        RequestBody body = RequestBody.create("", JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("✅ 设置双向持仓模式成功");
            } else {
                log.warn("设置双向持仓模式失败（可能已经是双向模式）");
            }
        }
    }
    
    /**
     * 获取持仓信息
     */
    public List<Position> getOpenPositions(String symbol) throws Exception {
        if (!enabled) {
            throw new Exception("币安服务未启用");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "timestamp=%d&recvWindow=%s",
            timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/fapi/v2/positionRisk?" + queryString + "&signature=" + signature;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .get()
                .build();
        
        List<Position> positions = new ArrayList<>();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonArray array = JsonParser.parseString(jsonData).getAsJsonArray();
                
                for (var i = 0; i < array.size(); i++) {
                    JsonObject posJson = array.get(i).getAsJsonObject();
                    String posSymbol = posJson.get("symbol").getAsString();
                    
                    // 只返回指定交易对的持仓
                    if (symbol.equals(posSymbol)) {
                        BigDecimal positionAmt = new BigDecimal(posJson.get("positionAmt").getAsString());
                        
                        // 只返回有持仓的
                        if (positionAmt.compareTo(BigDecimal.ZERO) != 0) {
                            Position position = new Position();
                            position.setSymbol(posSymbol);
                            position.setQuantity(positionAmt.abs());
                            position.setDirection(positionAmt.compareTo(BigDecimal.ZERO) > 0 ? "LONG" : "SHORT");
                            position.setAvgPrice(new BigDecimal(posJson.get("entryPrice").getAsString()));
                            position.setUnrealizedPnl(new BigDecimal(posJson.get("unRealizedProfit").getAsString()));
                            position.setLeverage(posJson.get("leverage").getAsInt());
                            position.setStatus("OPEN");
                            position.setCreateTime(LocalDateTime.now());
                            
                            positions.add(position);
                        }
                    }
                }
                
                log.info("✅ 获取持仓成功: {} 个持仓", positions.size());
                return positions;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 平掉所有持仓
     * 逐仓处理，单仓失败不阻断其他仓位，最终汇总失败信息并抛出异常
     */
    public boolean closeAllPositions(String symbol) throws Exception {
        List<Position> positions = getOpenPositions(symbol);

        if (positions.isEmpty()) {
            log.info("无持仓需要平仓: {}", symbol);
            return true;
        }

        List<String> failures = new ArrayList<>();
        for (Position position : positions) {
            // 平多仓：SELL LONG
            // 平空仓：BUY SHORT
            String side = "LONG".equals(position.getDirection()) ? "SELL" : "BUY";
            String positionSide = position.getDirection();
            String quantity = position.getQuantity().toPlainString();

            try {
                placeMarketOrder(symbol, side, positionSide, quantity, position.getLeverage());
                log.info("✅ 平仓成功: {} {} {} 数量={}", symbol, side, positionSide, quantity);
            } catch (Exception e) {
                String errMsg = String.format("%s %s 数量=%s: %s", symbol, positionSide, quantity, e.getMessage());
                log.error("❌ 平仓失败: {} - 请手动检查！", errMsg, e);
                failures.add(errMsg);
            }
        }

        if (!failures.isEmpty()) {
            throw new Exception("以下持仓平仓失败，请立即手动处理！\n" + String.join("\n", failures));
        }
        return true;
    }
    
    /**
     * 获取账户余额（USDT）
     */
    public BigDecimal getAccountBalance() throws Exception {
        if (!enabled) {
            throw new Exception("币安服务未启用");
        }
        
        long timestamp = System.currentTimeMillis();
        String queryString = String.format(
            "timestamp=%d&recvWindow=%s",
            timestamp, RECV_WINDOW
        );
        String signature = generateSignature(queryString);
        
        String url = getBaseUrl() + "/fapi/v2/account?" + queryString + "&signature=" + signature;
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .get()
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String jsonData = response.body().string();
                JsonObject json = JsonParser.parseString(jsonData).getAsJsonObject();
                
                if (json.has("totalWalletBalance")) {
                    String balance = json.get("totalWalletBalance").getAsString();
                    BigDecimal usdtBalance = new BigDecimal(balance);
                    log.info("币安合约账户USDT余额: ${}", usdtBalance);
                    return usdtBalance;
                }
                
                return BigDecimal.ZERO;
            } else {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new Exception("HTTP " + response.code() + ": " + errorBody);
            }
        }
    }
    
    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            String url = getBaseUrl() + "/fapi/v1/ping";
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("✅ 币安合约连接测试成功");
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("币安合约连接测试失败", e);
        }
        return false;
    }
}
