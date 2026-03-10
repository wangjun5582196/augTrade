package com.ltp.peter.augtrade.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 币安历史数据获取工具
 * 用于从币安合约获取历史K线数据
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@Component
public class BinanceHistoricalDataFetcher {
    
    @Autowired
    private KlineMapper klineMapper;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 币安合约API地址
    private static final String BINANCE_FUTURES_API = "https://fapi.binance.com";
    
    /**
     * 获取历史K线数据（币安合约）
     * 
     * @param symbol 交易对，如 XAUUSDT
     * @param interval K线周期，如 5m, 1h, 1d
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 成功获取的K线数量
     */
    public int fetchHistoricalKlines(String symbol, String interval, long startTime, long endTime) {
        log.info("========================================");
        log.info("开始从币安获取历史K线数据");
        log.info("交易对: {}", symbol);
        log.info("周期: {}", interval);
        log.info("开始时间: {}", LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault()));
        log.info("结束时间: {}", LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.systemDefault()));
        log.info("========================================");
        
        int totalCount = 0;
        int batchCount = 0;
        long currentStart = startTime;
        
        // 币安每次最多返回1500条数据
        final int LIMIT = 1500;
        final long intervalMillis = getIntervalMillis(interval);
        
        try {
            while (currentStart < endTime) {
                batchCount++;
                
                // 计算本批次的结束时间
                long batchEnd = Math.min(endTime, currentStart + (LIMIT * intervalMillis));
                
                String url = String.format(
                    "%s/fapi/v1/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d",
                    BINANCE_FUTURES_API, symbol, interval, currentStart, batchEnd, LIMIT
                );
                
                log.info("第{}批 - 请求时间范围: {} 至 {}", 
                    batchCount,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(currentStart), ZoneId.systemDefault()),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(batchEnd), ZoneId.systemDefault()));
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.error("请求失败: HTTP {}", response.code());
                        if (response.body() != null) {
                            log.error("错误详情: {}", response.body().string());
                        }
                        break;
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode list = objectMapper.readTree(responseBody);
                    
                    if (!list.isArray() || list.size() == 0) {
                        log.info("没有更多数据");
                        break;
                    }
                    
                    List<Kline> klines = new ArrayList<>();
                    long latestTimestamp = currentStart;
                    
                    // 币安K线数据格式：
                    // [
                    //   [
                    //     1499040000000,      // 开盘时间
                    //     "0.01634790",       // 开盘价
                    //     "0.80000000",       // 最高价
                    //     "0.01575800",       // 最低价
                    //     "0.01577100",       // 收盘价
                    //     "148976.11427815",  // 成交量
                    //     1499644799999,      // 收盘时间
                    //     "2434.19055334",    // 成交额
                    //     308,                // 成交笔数
                    //     "1756.87402397",    // 主动买入成交量
                    //     "28.46694368",      // 主动买入成交额
                    //     "17928899.62484339" // 忽略
                    //   ]
                    // ]
                    
                    for (JsonNode item : list) {
                        long timestamp = item.get(0).asLong();
                        
                        Kline kline = new Kline();
                        LocalDateTime openTime = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
                        
                        kline.setSymbol(symbol);
                        kline.setTimestamp(openTime);
                        kline.setOpenPrice(new BigDecimal(item.get(1).asText()));
                        kline.setHighPrice(new BigDecimal(item.get(2).asText()));
                        kline.setLowPrice(new BigDecimal(item.get(3).asText()));
                        kline.setClosePrice(new BigDecimal(item.get(4).asText()));
                        kline.setVolume(new BigDecimal(item.get(5).asText()));
                        kline.setAmount(new BigDecimal(item.get(7).asText()));
                        kline.setInterval(interval);
                        kline.setCreateTime(LocalDateTime.now());
                        kline.setUpdateTime(LocalDateTime.now());
                        
                        klines.add(kline);
                        
                        // 记录最新的时间戳
                        if (timestamp > latestTimestamp) {
                            latestTimestamp = timestamp;
                        }
                    }
                    
                    // 批量插入数据库
                    if (!klines.isEmpty()) {
                        int inserted = 0;
                        int duplicates = 0;
                        
                        for (Kline kline : klines) {
                            try {
                                klineMapper.insert(kline);
                                inserted++;
                            } catch (Exception e) {
                                // 忽略重复数据错误
                                if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                                    duplicates++;
                                } else {
                                    log.warn("插入K线失败: {}", e.getMessage());
                                }
                            }
                        }
                        totalCount += inserted;
                        log.info("第{}批：获取{}条，插入{}条，重复{}条，累计{}条", 
                                batchCount, klines.size(), inserted, duplicates, totalCount);
                    }
                    
                    // 如果获取的数据量少于限制，说明已经到达数据终点
                    if (list.size() < LIMIT) {
                        log.info("已获取全部数据");
                        break;
                    }
                    
                    // 更新下次请求的开始时间为本批次最新的时间戳+1个周期
                    currentStart = latestTimestamp + intervalMillis;
                    
                    // 如果下一个开始时间已经超过结束时间，结束循环
                    if (currentStart >= endTime) {
                        log.info("已到达结束时间");
                        break;
                    }
                    
                    // 避免请求过快（币安限制：1200请求/分钟）
                    Thread.sleep(100);
                    
                } catch (Exception e) {
                    log.error("处理响应失败", e);
                    break;
                }
            }
            
            log.info("========================================");
            log.info("✅ 历史数据获取完成！");
            log.info("总共获取: {} 条K线数据", totalCount);
            log.info("批次数量: {} 批", batchCount);
            log.info("========================================");
            return totalCount;
            
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            return totalCount;
        }
    }
    
    /**
     * 根据K线周期获取毫秒数
     */
    private long getIntervalMillis(String interval) {
        // 币安支持的周期：1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M
        switch (interval) {
            case "1m":
                return 60 * 1000L;
            case "3m":
                return 3 * 60 * 1000L;
            case "5m":
                return 5 * 60 * 1000L;
            case "15m":
                return 15 * 60 * 1000L;
            case "30m":
                return 30 * 60 * 1000L;
            case "1h":
                return 60 * 60 * 1000L;
            case "2h":
                return 2 * 60 * 60 * 1000L;
            case "4h":
                return 4 * 60 * 60 * 1000L;
            case "6h":
                return 6 * 60 * 60 * 1000L;
            case "8h":
                return 8 * 60 * 60 * 1000L;
            case "12h":
                return 12 * 60 * 60 * 1000L;
            case "1d":
                return 24 * 60 * 60 * 1000L;
            case "3d":
                return 3 * 24 * 60 * 60 * 1000L;
            case "1w":
                return 7 * 24 * 60 * 60 * 1000L;
            case "1M":
                return 30 * 24 * 60 * 60 * 1000L; // 近似30天
            default:
                return 5 * 60 * 1000L; // 默认5分钟
        }
    }
    
    /**
     * 获取最近N天的历史数据
     * 
     * @param symbol 交易对
     * @param interval K线周期
     * @param days 天数
     * @return 获取的K线数量
     */
    public int fetchRecentDays(String symbol, String interval, int days) {
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (days * 24L * 60 * 60 * 1000);
        return fetchHistoricalKlines(symbol, interval, startTime, endTime);
    }
    
    /**
     * 获取一年的历史数据
     * 
     * @param symbol 交易对
     * @param interval K线周期
     * @return 获取的K线数量
     */
    public int fetchOneYear(String symbol, String interval) {
        return fetchRecentDays(symbol, interval, 365);
    }
}
