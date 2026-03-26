package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.market.BinanceHistoricalDataFetcher;
import com.ltp.peter.augtrade.market.OkxKlineDataFetcher;
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

    @Autowired
    private OkxKlineDataFetcher okxFetcher;
    
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
     * 获取币安XAUUSDT所有历史数据（从上线开始到现在）
     * 币安XAUUSDT合约上线时间：2025-12-11
     * 
     * GET /api/historical/binance/xauusdt/all?interval=5m
     * 
     * @param interval K线周期，默认5m
     * @return 抓取结果
     */
    @GetMapping("/binance/xauusdt/all")
    public Map<String, Object> fetchBinanceXauusdtAll(
            @RequestParam(defaultValue = "5m") String interval) {
        
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 币安XAUUSDT合约上线时间：2025-12-11
            LocalDateTime start = LocalDateTime.parse("2025-12-11T00:00:00");
            LocalDateTime end = LocalDateTime.now();
            
            long startTime = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endTime = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            
            log.info("========================================");
            log.info("开始抓取币安XAUUSDT所有历史数据");
            log.info("开始时间: 2025-12-11（XAUUSDT合约上线日期）");
            log.info("结束时间: {}", end);
            log.info("K线周期: {}", interval);
            log.info("预计时间跨度: {}天", java.time.temporal.ChronoUnit.DAYS.between(start, end));
            log.info("========================================");
            
            long execStartTime = System.currentTimeMillis();
            int count = binanceFetcher.fetchHistoricalKlines("XAUUSDT", interval, startTime, endTime);
            long duration = (System.currentTimeMillis() - execStartTime) / 1000;
            
            result.put("success", true);
            result.put("symbol", "XAUUSDT");
            result.put("interval", interval);
            result.put("startDate", "2025-12-11");
            result.put("endDate", end.toLocalDate().toString());
            result.put("count", count);
            result.put("duration", duration + "秒");
            result.put("message", String.format("成功抓取从2025-12-11至今的%d条K线数据，耗时%d秒", count, duration));
            
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
     * 用 OKX 补缺最近 N 天的 K 线（推荐，境内访问更稳定）
     *
     * GET /api/historical/okx/xauusdt/recent?days=14
     */
    @GetMapping("/okx/xauusdt/recent")
    public Map<String, Object> fetchOkxRecent(
            @RequestParam(defaultValue = "14") int days) {

        Map<String, Object> result = new HashMap<>();
        try {
            long t0 = System.currentTimeMillis();
            int count = okxFetcher.fetchRecentDays("XAUUSDT", days);
            long duration = (System.currentTimeMillis() - t0) / 1000;
            result.put("success", true);
            result.put("source", "OKX");
            result.put("days", days);
            result.put("newBars", count);
            result.put("duration", duration + "s");
            result.put("message", String.format("OKX 补缺最近%d天，新增%d根K线，耗时%ds", days, count, duration));
            log.info("✅ OKX 补缺完成: 新增{}根, 耗时{}s", count, duration);
        } catch (Exception e) {
            log.error("OKX 补缺失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 用 OKX 补缺指定日期范围的 K 线
     *
     * GET /api/historical/okx/xauusdt/custom?startDate=2026-03-13&endDate=2026-03-26
     */
    @GetMapping("/okx/xauusdt/custom")
    public Map<String, Object> fetchOkxCustom(
            @RequestParam String startDate,
            @RequestParam String endDate) {

        Map<String, Object> result = new HashMap<>();
        try {
            LocalDateTime start = LocalDateTime.parse(startDate + "T00:00:00");
            LocalDateTime end   = LocalDateTime.parse(endDate   + "T23:59:59");
            long startMs = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endMs   = end  .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            long t0 = System.currentTimeMillis();
            int count = okxFetcher.fetchHistoricalKlines("XAUUSDT", startMs, endMs);
            long duration = (System.currentTimeMillis() - t0) / 1000;

            result.put("success", true);
            result.put("source", "OKX");
            result.put("startDate", startDate);
            result.put("endDate", endDate);
            result.put("newBars", count);
            result.put("duration", duration + "s");
            result.put("message", String.format("OKX 补缺 %s→%s，新增%d根K线，耗时%ds",
                    startDate, endDate, count, duration));
            log.info("✅ OKX 自定义补缺完成: {}→{} 新增{}根, 耗时{}s", startDate, endDate, count, duration);
        } catch (Exception e) {
            log.error("OKX 自定义补缺失败", e);
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
