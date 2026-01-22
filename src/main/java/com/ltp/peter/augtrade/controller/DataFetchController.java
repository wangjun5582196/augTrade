package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.market.HistoricalDataFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

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
}
