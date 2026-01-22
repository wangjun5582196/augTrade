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
 * 历史数据获取工具
 * 用于从Bybit获取历史K线数据
 * 
 * @author Peter Wang
 */
@Slf4j
@Component
public class HistoricalDataFetcher {
    
    @Autowired
    private KlineMapper klineMapper;
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 获取历史K线数据
     * 
     * @param symbol 交易对，如 XAUUSD
     * @param interval K线周期，如 5 (5分钟)
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 成功获取的K线数量
     */
    public int fetchHistoricalKlines(String symbol, String interval, long startTime, long endTime) {
        log.info("开始获取历史K线数据: symbol={}, interval={}, 时间范围: {} - {}", 
                symbol, interval, 
                LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault()),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneId.systemDefault()));
        
        int totalCount = 0;
        int batchCount = 0;
        long currentEnd = endTime; // 从结束时间向前获取
        
        // Bybit每次最多返回200条数据
        final int LIMIT = 200;
        final long intervalMillis = getIntervalMillis(interval);
        
        // 构建完整的symbol（XAUUSD -> XAUTUSDT）
        String fullSymbol = "XAUTUSDT";
        
        try {
            while (currentEnd > startTime) {
                batchCount++;
                
                // 计算本批次的开始时间（向前推200个K线周期）
                long batchStart = Math.max(startTime, currentEnd - (LIMIT * intervalMillis));
                
                String url = String.format(
                    "https://api.bybit.com/v5/market/kline?category=linear&symbol=%s&interval=%s&start=%d&end=%d&limit=%d",
                    fullSymbol, interval, batchStart, currentEnd, LIMIT
                );
                
                log.info("第{}批 - 请求时间范围: {} 至 {}", 
                    batchCount,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(batchStart), ZoneId.systemDefault()),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(currentEnd), ZoneId.systemDefault()));
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.error("请求失败: HTTP {}", response.code());
                        break;
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode root = objectMapper.readTree(responseBody);
                    
                    if (root.get("retCode").asInt() != 0) {
                        log.error("API返回错误: {}", root.get("retMsg").asText());
                        break;
                    }
                    
                    JsonNode list = root.path("result").path("list");
                    if (!list.isArray() || list.size() == 0) {
                        log.info("没有更多数据");
                        break;
                    }
                    
                    List<Kline> klines = new ArrayList<>();
                    long earliestTimestamp = currentEnd;
                    
                    // Bybit返回的数据是倒序的（最新的在前）
                    for (JsonNode item : list) {
                        long timestamp = item.get(0).asLong();
                        
                        // 只保存在目标时间范围内的数据
                        if (timestamp >= startTime && timestamp <= endTime) {
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
                            kline.setInterval(interval);
                            
                            klines.add(kline);
                        }
                        
                        // 记录最早的时间戳
                        if (timestamp < earliestTimestamp) {
                            earliestTimestamp = timestamp;
                        }
                    }
                    
                    // 批量插入数据库
                    if (!klines.isEmpty()) {
                        int inserted = 0;
                        for (Kline kline : klines) {
                            try {
                                klineMapper.insert(kline);
                                inserted++;
                            } catch (Exception e) {
                                // 忽略重复数据错误
                                if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                                    // 跳过重复数据
                                } else {
                                    log.warn("插入K线失败: {}", e.getMessage());
                                }
                            }
                        }
                        totalCount += inserted;
                        log.info("第{}批：获取{}条，插入{}条，累计{}条", 
                                batchCount, klines.size(), inserted, totalCount);
                    }
                    
                    // 如果获取的数据量少于限制，说明已经到达数据起点
                    if (list.size() < LIMIT) {
                        log.info("已获取全部数据");
                        break;
                    }
                    
                    // 更新下次请求的结束时间为本批次最早的时间戳
                    currentEnd = earliestTimestamp - intervalMillis;
                    
                    // 如果下一个结束时间已经小于起始时间，结束循环
                    if (currentEnd <= startTime) {
                        log.info("已到达起始时间");
                        break;
                    }
                    
                    // 避免请求过快
                    Thread.sleep(150);
                    
                } catch (Exception e) {
                    log.error("处理响应失败", e);
                    break;
                }
            }
            
            log.info("历史数据获取完成！总共获取 {} 条K线数据（共{}批次）", totalCount, batchCount);
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
        try {
            // 尝试直接解析为数字（如 "5"）
            int minutes = Integer.parseInt(interval);
            return minutes * 60 * 1000L;
        } catch (NumberFormatException e) {
            // 包含单位的情况（如 "5m", "1h"）
            String numStr = interval.replaceAll("[^0-9]", "");
            String unit = interval.replaceAll("[0-9]", "").toLowerCase();
            
            if (numStr.isEmpty()) {
                return 5 * 60 * 1000L; // 默认5分钟
            }
            
            int value = Integer.parseInt(numStr);
            
            if (unit.isEmpty() || unit.equals("m")) {
                return value * 60 * 1000L;
            } else if (unit.equals("h")) {
                return value * 60 * 60 * 1000L;
            } else if (unit.equals("d")) {
                return value * 24 * 60 * 60 * 1000L;
            } else if (unit.equals("w")) {
                return value * 7 * 24 * 60 * 60 * 1000L;
            } else {
                return value * 60 * 1000L; // 默认分钟
            }
        }
    }
}
