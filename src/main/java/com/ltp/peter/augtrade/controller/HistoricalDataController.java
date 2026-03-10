package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.market.BinanceHistoricalDataFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 历史数据控制器
 * 用于手动触发历史数据抓取
 * 
 * @author Peter Wang
 * @since 2026-03-10
 */
@Slf4j
@RestController
@RequestMapping("/historical")
public class HistoricalDataController {
    
    @Autowired
    private BinanceHistoricalDataFetcher binanceFetcher;
    
    /**
     * 获取币安XAUUSDT一年的历史数据
     * 
     * GET /api/historical/binance/xauusdt/one-year?interval=5m
     * 
     * @param interval K线周期，默认5m
     * @return 抓取结果
     */
    @GetMapping("/binance/xauusdt/one-year")
    public Map<String, Object> fetchBinanceXauusdtOneYear(
            @RequestParam(defaultValue = "5m") String interval) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("========================================");
            log.info("开始抓取币安XAUUSDT一年历史数据");
            log.info("K线周期: {}", interval);
            log.info("========================================");
            
            long startTime = System.currentTimeMillis();
            int count = binanceFetcher.fetchOneYear("XAUUSDT", interval);
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            
            result.put("success", true);
            result.put("symbol", "XAUUSDT");
            result.put("interval", interval);
            result.put("count", count);
            result.put("duration", duration + "秒");
            result.put("message", String.format("成功抓取%d条K线数据，耗时%d秒", count, duration));
            
            log.info("========================================");
            log.info("✅ 抓取完成！共{}条数据，耗时{}秒", count, duration);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("抓取失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取币安XAUUSDT最近N天的历史数据
     * 
     * GET /api/historical/binance/xauusdt/recent?days=30&interval=5m
     * 
     * @param days 天数
     * @param interval K线周期
     * @return 抓取结果
     */
    @GetMapping("/binance/xauusdt/recent")
    public Map<String, Object> fetchBinanceXauusdtRecent(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5m") String interval) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("========================================");
            log.info("开始抓取币安XAUUSDT最近{}天历史数据", days);
            log.info("K线周期: {}", interval);
            log.info("========================================");
            
            long startTime = System.currentTimeMillis();
            int count = binanceFetcher.fetchRecentDays("XAUUSDT", interval, days);
            long duration = (System.currentTimeMillis() - startTime) / 1000;
            
            result.put("success", true);
            result.put("symbol", "XAUUSDT");
            result.put("interval", interval);
            result.put("days", days);
            result.put("count", count);
            result.put("duration", duration + "秒");
            result.put("message", String.format("成功抓取最近%d天的%d条K线数据，耗时%d秒", days, count, duration));
            
            log.info("========================================");
            log.info("✅ 抓取完成！共{}条数据，耗时{}秒", count, duration);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("抓取失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 自定义时间范围抓取
     * 
     * GET /api/historical/binance/xauusdt/custom?startDate=2025-01-01&endDate=2026-01-01&interval=1h
     * 
     * @param startDate 开始日期（yyyy-MM-dd）
     * @param endDate 结束日期（yyyy-MM-dd）
     * @param interval K线周期
     * @return 抓取结果
     */
    @GetMapping("/binance/xauusdt/custom")
    public Map<String, Object> fetchBinanceXauusdtCustom(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "5m") String interval) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 解析日期
            LocalDateTime start = LocalDateTime.parse(startDate + "T00:00:00");
            LocalDateTime end = LocalDateTime.parse(endDate + "T23:59:59");
            
            long startTime = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endTime = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            
            log.info("========================================");
            log.info("开始抓取币安XAUUSDT自定义时间范围历史数据");
            log.info("开始日期: {}", startDate);
            log.info("结束日期: {}", endDate);
            log.info("K线周期: {}", interval);
            log.info("========================================");
            
            long execStartTime = System.currentTimeMillis();
            int count = binanceFetcher.fetchHistoricalKlines("XAUUSDT", interval, startTime, endTime);
            long duration = (System.currentTimeMillis() - execStartTime) / 1000;
            
            result.put("success", true);
            result.put("symbol", "XAUUSDT");
            result.put("interval", interval);
            result.put("startDate", startDate);
            result.put("endDate", endDate);
            result.put("count", count);
            result.put("duration", duration + "秒");
            result.put("message", String.format("成功抓取%s至%s的%d条K线数据，耗时%d秒", 
                    startDate, endDate, count, duration));
            
            log.info("========================================");
            log.info("✅ 抓取完成！共{}条数据，耗时{}秒", count, duration);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("抓取失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
}
