package com.ltp.peter.augtrade.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import com.ltp.peter.augtrade.market.HistoricalDataFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据获取控制器
 * 用于获取历史K线数据
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/data")
@CrossOrigin(origins = "*")
public class DataFetchController {
    
    @Autowired
    private HistoricalDataFetcher historicalDataFetcher;
    
    @Autowired
    private KlineMapper klineMapper;
    
    /**
     * 获取历史K线数据
     * 
     * @param symbol 交易对，如 XAUUSD
     * @param interval K线周期，如 5 (5分钟)
     * @param startDate 开始日期，格式: 2025-06-01
     * @param endDate 结束日期，格式: 2025-12-31
     * @return 响应信息
     */
    @PostMapping("/fetch-historical")
    public Map<String, Object> fetchHistoricalData(
            @RequestParam(defaultValue = "XAUUSD") String symbol,
            @RequestParam(defaultValue = "5") String interval,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        
        log.info("收到历史数据获取请求: symbol={}, interval={}, startDate={}, endDate={}", 
                symbol, interval, startDate, endDate);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 解析日期并转换为毫秒时间戳
            LocalDateTime startDateTime = LocalDateTime.parse(startDate + "T00:00:00");
            LocalDateTime endDateTime = LocalDateTime.parse(endDate + "T23:59:59");
            
            long startTime = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endTime = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            
            // 开始获取数据
            int count = historicalDataFetcher.fetchHistoricalKlines(symbol, interval, startTime, endTime);
            
            response.put("success", true);
            response.put("message", "数据获取完成");
            response.put("count", count);
            response.put("symbol", symbol);
            response.put("interval", interval);
            response.put("startDate", startDate);
            response.put("endDate", endDate);
            
        } catch (Exception e) {
            log.error("获取历史数据失败", e);
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
        }
        
        return response;
    }
    
    /**
     * 快捷获取最近7天黄金数据
     */
    @PostMapping("/fetch-recent-gold")
    public Map<String, Object> fetchRecentGoldData() {
        // 获取最近7天的数据
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        
        String startDate = weekAgo.toLocalDate().toString();
        String endDate = now.toLocalDate().toString();
        
        return fetchHistoricalData("XAUUSD", "5", startDate, endDate);
    }
    
    /**
     * 快捷获取最近30天黄金数据
     */
    @PostMapping("/fetch-month-gold")
    public Map<String, Object> fetchMonthGoldData() {
        // 获取最近30天的数据
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthAgo = now.minusDays(30);
        
        String startDate = "2026-01-01";
        String endDate = "2026-01-12";
        
        return fetchHistoricalData("XAUUSD", "5", startDate, endDate);
    }
    
    /**
     * 分析最近一周的K线数据
     * 
     * @param symbol 交易对，默认XAUUSD
     * @return K线数据分析结果
     */
    @GetMapping("/analyze-weekly")
    public Map<String, Object> analyzeWeeklyKlines(
            @RequestParam(defaultValue = "XAUUSD") String symbol) {
        
        log.info("开始分析最近一周K线数据: symbol={}", symbol);
        
        Map<String, Object> response = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        try {
            // 查询最近7天的K线数据
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            
            QueryWrapper<Kline> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("symbol", symbol)
                       .ge("timestamp", weekAgo)
                       .orderByAsc("timestamp");
            
            List<Kline> klines = klineMapper.selectList(queryWrapper);
            
            if (klines.isEmpty()) {
                response.put("success", false);
                response.put("message", "未找到最近一周的K线数据");
                return response;
            }
            
            // 基础统计
            int totalCount = klines.size();
            LocalDateTime firstTime = klines.get(0).getTimestamp();
            LocalDateTime lastTime = klines.get(klines.size() - 1).getTimestamp();
            
            // 价格统计
            BigDecimal maxHigh = klines.stream()
                    .map(Kline::getHighPrice)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            
            BigDecimal minLow = klines.stream()
                    .map(Kline::getLowPrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            
            BigDecimal avgClose = klines.stream()
                    .map(Kline::getClosePrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
            
            BigDecimal firstOpen = klines.get(0).getOpenPrice();
            BigDecimal lastClose = klines.get(klines.size() - 1).getClosePrice();
            BigDecimal priceChange = lastClose.subtract(firstOpen);
            BigDecimal priceChangePercent = priceChange.divide(firstOpen, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            
            // 成交量统计
            BigDecimal totalVolume = klines.stream()
                    .map(Kline::getVolume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal avgVolume = totalVolume.divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
            
            // 波动性分析 - 计算每根K线的波动幅度
            List<BigDecimal> ranges = klines.stream()
                    .map(k -> k.getHighPrice().subtract(k.getLowPrice()))
                    .collect(Collectors.toList());
            
            BigDecimal avgRange = ranges.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
            
            BigDecimal maxRange = ranges.stream()
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            
            // 按天分组统计
            Map<String, List<Kline>> dailyGroups = klines.stream()
                    .collect(Collectors.groupingBy(k -> k.getTimestamp().toLocalDate().toString()));
            
            List<Map<String, Object>> dailyStats = new ArrayList<>();
            for (Map.Entry<String, List<Kline>> entry : dailyGroups.entrySet()) {
                List<Kline> dayKlines = entry.getValue();
                Map<String, Object> dayStat = new HashMap<>();
                
                dayStat.put("date", entry.getKey());
                dayStat.put("count", dayKlines.size());
                dayStat.put("open", dayKlines.get(0).getOpenPrice());
                dayStat.put("close", dayKlines.get(dayKlines.size() - 1).getClosePrice());
                dayStat.put("high", dayKlines.stream().map(Kline::getHighPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
                dayStat.put("low", dayKlines.stream().map(Kline::getLowPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
                dayStat.put("volume", dayKlines.stream().map(Kline::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add));
                
                dailyStats.add(dayStat);
            }
            
            // 最近10条K线数据
            List<Map<String, Object>> recentKlines = klines.stream()
                    .skip(Math.max(0, klines.size() - 10))
                    .map(k -> {
                        Map<String, Object> kMap = new HashMap<>();
                        kMap.put("timestamp", k.getTimestamp().format(formatter));
                        kMap.put("open", k.getOpenPrice());
                        kMap.put("high", k.getHighPrice());
                        kMap.put("low", k.getLowPrice());
                        kMap.put("close", k.getClosePrice());
                        kMap.put("volume", k.getVolume());
                        return kMap;
                    })
                    .collect(Collectors.toList());
            
            // 组装响应
            response.put("success", true);
            response.put("symbol", symbol);
            response.put("totalCount", totalCount);
            response.put("startTime", firstTime.format(formatter));
            response.put("endTime", lastTime.format(formatter));
            
            Map<String, Object> priceStats = new HashMap<>();
            priceStats.put("firstOpen", firstOpen);
            priceStats.put("lastClose", lastClose);
            priceStats.put("maxHigh", maxHigh);
            priceStats.put("minLow", minLow);
            priceStats.put("avgClose", avgClose);
            priceStats.put("priceChange", priceChange);
            priceStats.put("priceChangePercent", priceChangePercent + "%");
            priceStats.put("avgRange", avgRange);
            priceStats.put("maxRange", maxRange);
            response.put("priceStats", priceStats);
            
            Map<String, Object> volumeStats = new HashMap<>();
            volumeStats.put("total", totalVolume);
            volumeStats.put("average", avgVolume);
            response.put("volumeStats", volumeStats);
            
            response.put("dailyStats", dailyStats);
            response.put("recentKlines", recentKlines);
            
            log.info("K线数据分析完成: 总计{}条记录, 价格变化: {}", totalCount, priceChangePercent);
            
        } catch (Exception e) {
            log.error("分析K线数据失败", e);
            response.put("success", false);
            response.put("message", "分析失败: " + e.getMessage());
        }
        
        return response;
    }
}
