package com.ltp.peter.augtrade.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 真实行情数据服务示例
 * 
 * 支持对接多个行情数据源：
 * 1. Alpha Vantage API (免费)
 * 2. Binance API (加密货币)
 * 3. IEX Cloud API
 * 4. Yahoo Finance API
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class RealMarketDataService {
    
    private final OkHttpClient httpClient;
    
    public RealMarketDataService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 方案1: 从Alpha Vantage获取黄金价格
     * 
     * 注册地址: https://www.alphavantage.co/support/#api-key
     * 免费版限制: 5 calls/minute, 500 calls/day
     */
    public BigDecimal getGoldPriceFromAlphaVantage(String apiKey) {
        try {
            // Alpha Vantage的黄金现货价格API
            String url = String.format(
                "https://www.alphavantage.co/query?function=CURRENCY_EXCHANGE_RATE&from_currency=XAU&to_currency=USD&apikey=%s",
                apiKey
            );
            
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    // 解析响应数据
                    JsonObject realtimeData = jsonObject.getAsJsonObject("Realtime Currency Exchange Rate");
                    String priceStr = realtimeData.get("5. Exchange Rate").getAsString();
                    
                    BigDecimal price = new BigDecimal(priceStr);
                    log.info("从Alpha Vantage获取黄金价格: {}", price);
                    return price;
                }
            }
        } catch (Exception e) {
            log.error("从Alpha Vantage获取价格失败", e);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * 方案2: 从Binance获取指定交易对价格
     * 
     * API文档: https://binance-docs.github.io/apidocs/spot/en/
     * 无需API Key即可获取行情数据
     * 
     * @param symbol 交易对符号，如BTCUSDT、PAXGUSDT等
     */
    public BigDecimal getPriceFromBinance(String symbol) {
        try {
            String url = String.format("https://api.binance.com/api/v3/ticker/price?symbol=%s", symbol);
            
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    String priceStr = jsonObject.get("price").getAsString();
                    BigDecimal price = new BigDecimal(priceStr);
                    
                    log.info("从Binance获取{}价格: {}", symbol, price);
                    return price;
                }
            }
        } catch (Exception e) {
            log.error("从Binance获取{}价格失败", symbol, e);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * 方案2(旧): 从Binance获取PAXG/USDT价格（代表黄金价格）
     * 保留用于兼容性
     * @deprecated 使用 getPriceFromBinance(String symbol) 代替
     */
    @Deprecated
    public BigDecimal getGoldPriceFromBinance() {
        return getPriceFromBinance("PAXGUSDT");
    }
    
    /**
     * 方案3: 从Binance获取K线数据
     */
    public Kline getKlineFromBinance(String symbol, String interval) {
        try {
            // interval: 1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M
            String url = String.format(
                "https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=1",
                symbol, interval
            );
            
            Request request = new Request.Builder()
                    .url(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    
                    // 解析K线数据 [时间戳, 开盘价, 最高价, 最低价, 收盘价, 成交量, ...]
                    String klineData = jsonData.substring(2, jsonData.length() - 2);
                    String[] values = klineData.split(",");
                    
                    Kline kline = new Kline();
                    kline.setSymbol(symbol);
                    kline.setInterval(interval);
                    kline.setTimestamp(LocalDateTime.now());
                    kline.setOpenPrice(new BigDecimal(values[1].replace("\"", "")));
                    kline.setHighPrice(new BigDecimal(values[2].replace("\"", "")));
                    kline.setLowPrice(new BigDecimal(values[3].replace("\"", "")));
                    kline.setClosePrice(new BigDecimal(values[4].replace("\"", "")));
                    kline.setVolume(new BigDecimal(values[5].replace("\"", "")));
                    kline.setCreateTime(LocalDateTime.now());
                    kline.setUpdateTime(LocalDateTime.now());
                    
                    log.info("从Binance获取K线数据: {} {} - 收盘价: {}", 
                            symbol, interval, kline.getClosePrice());
                    return kline;
                }
            }
        } catch (Exception e) {
            log.error("从Binance获取K线数据失败", e);
        }
        return null;
    }
    
    /**
     * 方案4: 从金十数据获取黄金价格（中文API）
     * 
     * 金十数据提供实时贵金属报价
     * 网站: https://www.jin10.com/
     * 
     * 支持获取：
     * - 现货黄金（XAU/USD）
     * - 伦敦金
     * - COMEX黄金期货
     * 
     * @return 黄金价格（美元/盎司），失败返回null
     */
    public BigDecimal getGoldPriceFromJin10() {
        try {
            // 金十数据实时行情API
            // 现货黄金代码：XAUUSD
            String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.jin10.com/")
                    .addHeader("Accept", "application/json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    log.debug("金十数据原始响应: {}", jsonData);
                    
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    // 检查返回状态
                    if (jsonObject.has("code") && jsonObject.get("code").getAsInt() == 0) {
                        JsonObject data = jsonObject.getAsJsonObject("data");
                        
                        // 获取当前价格（买入价和卖出价的平均值）
                        if (data.has("last_price")) {
                            String priceStr = data.get("last_price").getAsString();
                            BigDecimal price = new BigDecimal(priceStr);
                            log.info("✅ 从金十数据获取黄金价格: ${} (现货金)", price);
                            return price;
                        } else if (data.has("bid") && data.has("ask")) {
                            // 如果没有last_price，使用买卖价的中间价
                            BigDecimal bid = new BigDecimal(data.get("bid").getAsString());
                            BigDecimal ask = new BigDecimal(data.get("ask").getAsString());
                            BigDecimal price = bid.add(ask).divide(new BigDecimal("2"), 2, BigDecimal.ROUND_HALF_UP);
                            log.info("✅ 从金十数据获取黄金价格: ${} (买卖均价)", price);
                            return price;
                        }
                    } else {
                        log.warn("⚠️ 金十数据返回错误: {}", jsonData);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ 从金十数据获取价格失败", e);
        }
        return null;
    }
    
    /**
     * 从金十数据获取详细行情数据（包含涨跌幅等）
     * 
     * @return JsonObject包含完整行情数据，失败返回null
     */
    public JsonObject getGoldDetailFromJin10() {
        try {
            String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.jin10.com/")
                    .addHeader("Accept", "application/json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    if (jsonObject.has("code") && jsonObject.get("code").getAsInt() == 0) {
                        JsonObject data = jsonObject.getAsJsonObject("data");
                        
                        // 记录详细行情
                        log.info("📊 金十数据详细行情:");
                        log.info("   当前价: {}", data.has("last_price") ? data.get("last_price").getAsString() : "N/A");
                        log.info("   买入价: {}", data.has("bid") ? data.get("bid").getAsString() : "N/A");
                        log.info("   卖出价: {}", data.has("ask") ? data.get("ask").getAsString() : "N/A");
                        log.info("   涨跌额: {}", data.has("change") ? data.get("change").getAsString() : "N/A");
                        log.info("   涨跌幅: {}", data.has("change_percent") ? data.get("change_percent").getAsString() + "%" : "N/A");
                        log.info("   最高价: {}", data.has("high") ? data.get("high").getAsString() : "N/A");
                        log.info("   最低价: {}", data.has("low") ? data.get("low").getAsString() : "N/A");
                        log.info("   开盘价: {}", data.has("open") ? data.get("open").getAsString() : "N/A");
                        
                        return data;
                    }
                }
            }
        } catch (Exception e) {
            log.error("从金十数据获取详细行情失败", e);
        }
        return null;
    }
    
    /**
     * 通用行情获取方法
     * 可配置不同的数据源
     */
    public BigDecimal getCurrentPrice(String symbol, String source, String apiKey) {
        switch (source.toLowerCase()) {
            case "alphavantage":
                return getGoldPriceFromAlphaVantage(apiKey);
            case "binance":
                return getGoldPriceFromBinance();
            case "jin10":
                return getGoldPriceFromJin10();
            default:
                log.warn("未知的数据源: {}, 返回默认值", source);
                return BigDecimal.ZERO;
        }
    }
}
