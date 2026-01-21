package com.ltp.peter.augtrade.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ltp.peter.augtrade.entity.Kline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 启动时数据加载服务
 * 在应用启动后自动获取当天的K线数据
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class StartupDataLoader implements ApplicationRunner {
    
    @Autowired
    private BybitTradingService bybitTradingService;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Value("${bybit.api.enabled:false}")
    private boolean bybitEnabled;
    
    @Value("${bybit.gold.symbol:XAUTUSDT}")
    private String bybitSymbol;
    
    @Value("${trading.startup.load-klines:true}")
    private boolean loadKlinesOnStartup;
    
    @Value("${trading.startup.klines-count:200}")
    private int klinesCount;
    
    // 数据加载完成标志
    private static final CountDownLatch dataLoadedLatch = new CountDownLatch(1);
    private static volatile boolean dataLoaded = false;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!loadKlinesOnStartup) {
            log.info("⏸️ 启动时K线加载已禁用");
            dataLoaded = true;
            dataLoadedLatch.countDown();
            return;
        }
        
        if (!bybitEnabled || !bybitTradingService.isEnabled()) {
            log.warn("⚠️ Bybit未启用，跳过启动时数据加载");
            dataLoaded = true;
            dataLoadedLatch.countDown();
            return;
        }
        
        log.info("========================================");
        log.info("🚀 开始加载启动数据...");
        log.info("========================================");
        
        try {
            loadHistoricalKlines();
            dataLoaded = true;
            log.info("========================================");
            log.info("✅ 启动数据加载完成！系统已准备好开始交易");
            log.info("========================================");
        } catch (Exception e) {
            log.error("❌ 启动数据加载失败", e);
            dataLoaded = false;
        } finally {
            dataLoadedLatch.countDown();
        }
    }
    
    /**
     * 加载历史K线数据（只加载当天的数据）
     */
    private void loadHistoricalKlines() throws Exception {
        // 计算当天的K线数量（从0点到现在）
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime now = LocalDateTime.now();
        long minutesSinceMidnight = java.time.Duration.between(todayStart, now).toMinutes();
        int todayKlineCount = (int) (minutesSinceMidnight / 5) + 10; // 5分钟一条，额外加10条确保覆盖
        
        log.info("📊 开始获取{}当天的K线数据（约{}条，5分钟周期）", bybitSymbol, todayKlineCount);
        
        try {
            // 🔥 先删除当天的K线数据，确保数据是最新的
            log.info("🗑️ 清理当天旧数据...");
            int deletedCount = marketDataService.deleteTodayKlines(bybitSymbol, "5m");
            if (deletedCount > 0) {
                log.info("✅ 已删除当天旧K线数据：{} 条", deletedCount);
            } else {
                log.info("ℹ️ 当天暂无旧数据需要删除");
            }
            
            // 从Bybit获取K线数据（只获取当天的数据）
            JsonArray klines = bybitTradingService.getKlines(bybitSymbol, "5", todayKlineCount);
            
            if (klines == null || klines.size() == 0) {
                log.warn("⚠️ 未获取到K线数据");
                return;
            }
            
            log.info("📥 成功获取{}条K线数据，开始保存到数据库...", klines.size());
            
            int savedCount = 0;
            int skippedCount = 0;
            
            // 遍历K线数据并保存（Bybit返回的数据是从新到旧排序）
            for (int i = klines.size() - 1; i >= 0; i--) {
                JsonArray klineData = klines.get(i).getAsJsonArray();
                
                try {
                    // 解析K线数据
                    // [0] startTime (timestamp in milliseconds)
                    // [1] openPrice
                    // [2] highPrice
                    // [3] lowPrice
                    // [4] closePrice
                    // [5] volume
                    // [6] turnover
                    long timestamp = klineData.get(0).getAsLong();
                    LocalDateTime klineTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(timestamp),
                        ZoneId.systemDefault()
                    );
                    
                    Kline kline = new Kline();
                    kline.setSymbol(bybitSymbol);
                    kline.setInterval("5m");
                    kline.setTimestamp(klineTime);
                    kline.setOpenPrice(new BigDecimal(klineData.get(1).getAsString()));
                    kline.setHighPrice(new BigDecimal(klineData.get(2).getAsString()));
                    kline.setLowPrice(new BigDecimal(klineData.get(3).getAsString()));
                    kline.setClosePrice(new BigDecimal(klineData.get(4).getAsString()));
                    kline.setVolume(new BigDecimal(klineData.get(5).getAsString()));
                    kline.setAmount(kline.getClosePrice().multiply(kline.getVolume()));
                    kline.setCreateTime(LocalDateTime.now());
                    kline.setUpdateTime(LocalDateTime.now());
                    
                    // 保存K线（如果已存在会被忽略）
                    marketDataService.saveKline(kline);
                    savedCount++;
                    
                    // 每10条显示一次进度
                    if (savedCount % 10 == 0) {
                        log.info("进度: {}/{} - 最新价格: ${}", savedCount, klines.size(), kline.getClosePrice());
                    }
                    
                } catch (Exception e) {
                    skippedCount++;
                    log.debug("跳过重复或无效K线数据: {}", e.getMessage());
                }
            }
            
            log.info("✅ K线数据保存完成！");
            log.info("   - 成功保存: {} 条", savedCount);
            log.info("   - 跳过重复: {} 条", skippedCount);
            log.info("   - 总计处理: {} 条", klines.size());
            
            // 显示最新的价格信息
            displayLatestPrice();
            
        } catch (Exception e) {
            log.error("❌ 获取K线数据失败", e);
            throw e;
        }
    }
    
    /**
     * 显示最新价格信息
     */
    private void displayLatestPrice() {
        try {
            BigDecimal currentPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
            log.info("💰 当前{}价格: ${}", bybitSymbol, currentPrice);
            
            // 获取最新的几条K线显示趋势
            java.util.List<Kline> latestKlines = marketDataService.getLatestKlines(bybitSymbol, "5m", 5);
            if (latestKlines != null && !latestKlines.isEmpty()) {
                log.info("📈 最近5条K线:");
                for (int i = latestKlines.size() - 1; i >= 0; i--) {
                    Kline k = latestKlines.get(i);
                    String trend = k.getClosePrice().compareTo(k.getOpenPrice()) > 0 ? "📈" : "📉";
                    log.info("   {} {} - O:${} H:${} L:${} C:${}", 
                            trend,
                            k.getTimestamp().toString().substring(11, 16),
                            k.getOpenPrice(),
                            k.getHighPrice(),
                            k.getLowPrice(),
                            k.getClosePrice());
                }
            }
        } catch (Exception e) {
            log.debug("显示价格信息失败: {}", e.getMessage());
        }
    }
    
    /**
     * 等待数据加载完成
     * 
     * @param timeout 超时时间（秒）
     * @return 是否加载完成
     */
    public static boolean awaitDataLoaded(long timeout) {
        try {
            return dataLoadedLatch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 检查数据是否已加载
     */
    public static boolean isDataLoaded() {
        return dataLoaded;
    }
}
