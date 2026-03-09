package com.ltp.peter.augtrade.controller;

import com.ltp.peter.augtrade.trading.broker.BinanceTradingService;
import com.ltp.peter.augtrade.trading.broker.BybitTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 黄金价格控制器
 * 用于对比Bybit和币安的黄金报价
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/gold")
@CrossOrigin(origins = "*")
public class GoldPriceController {
    
    @Autowired(required = false)
    private BybitTradingService bybitTradingService;
    
    @Autowired(required = false)
    private BinanceTradingService binanceTradingService;
    
    @Value("${bybit.gold.symbol:XAUTUSDT}")
    private String bybitSymbol;
    
    @Value("${binance.gold.symbol:PAXGUSDT}")
    private String binanceSymbol;
    
    /**
     * 获取黄金价格对比
     * 
     * GET /api/gold/price/compare
     * 
     * @return 返回Bybit和币安的黄金价格对比
     */
    @GetMapping("/price/compare")
    public Map<String, Object> compareGoldPrice() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("========================================");
            log.info("【黄金价格对比】开始获取...");
            
            // 获取Bybit价格
            Map<String, Object> bybitData = new HashMap<>();
            if (bybitTradingService != null && bybitTradingService.isEnabled()) {
                try {
                    BigDecimal bybitPrice = bybitTradingService.getCurrentPrice(bybitSymbol);
                    bybitData.put("success", true);
                    bybitData.put("symbol", bybitSymbol);
                    bybitData.put("price", bybitPrice);
                    bybitData.put("source", "Bybit");
                    bybitData.put("type", "永续合约");
                    log.info("✅ Bybit价格: ${}", bybitPrice);
                } catch (Exception e) {
                    bybitData.put("success", false);
                    bybitData.put("error", e.getMessage());
                    log.error("❌ Bybit获取价格失败: {}", e.getMessage());
                }
            } else {
                bybitData.put("success", false);
                bybitData.put("error", "Bybit服务未启用");
            }
            result.put("bybit", bybitData);
            
            // 获取币安价格
            Map<String, Object> binanceData = new HashMap<>();
            if (binanceTradingService != null && binanceTradingService.isEnabled()) {
                try {
                    BigDecimal binancePrice = binanceTradingService.getCurrentPrice(binanceSymbol);
                    binanceData.put("success", true);
                    binanceData.put("symbol", binanceSymbol);
                    binanceData.put("price", binancePrice);
                    binanceData.put("source", "币安");
                    binanceData.put("type", "现货");
                    log.info("✅ 币安价格: ${}", binancePrice);
                } catch (Exception e) {
                    binanceData.put("success", false);
                    binanceData.put("error", e.getMessage());
                    log.error("❌ 币安获取价格失败: {}", e.getMessage());
                }
            } else {
                binanceData.put("success", false);
                binanceData.put("error", "币安服务未启用");
            }
            result.put("binance", binanceData);
            
            // 计算价差
            if (bybitData.containsKey("price") && binanceData.containsKey("price")) {
                BigDecimal bybitPrice = (BigDecimal) bybitData.get("price");
                BigDecimal binancePrice = (BigDecimal) binanceData.get("price");
                
                BigDecimal priceDiff = bybitPrice.subtract(binancePrice);
                BigDecimal priceDiffPercent = priceDiff.divide(binancePrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                
                Map<String, Object> comparison = new HashMap<>();
                comparison.put("priceDiff", priceDiff);
                comparison.put("priceDiffPercent", priceDiffPercent);
                comparison.put("description", String.format(
                    "Bybit价格 %s 币安价格 $%.2f (%.2f%%)",
                    priceDiff.compareTo(BigDecimal.ZERO) > 0 ? "高于" : "低于",
                    priceDiff.abs(),
                    priceDiffPercent.abs()
                ));
                
                result.put("comparison", comparison);
                
                log.info("💰 价差: ${} ({} %)", priceDiff, priceDiffPercent);
            }
            
            result.put("success", true);
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("获取黄金价格对比失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取Bybit黄金价格
     * 
     * GET /api/gold/price/bybit
     */
    @GetMapping("/price/bybit")
    public Map<String, Object> getBybitPrice() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (bybitTradingService != null && bybitTradingService.isEnabled()) {
                BigDecimal price = bybitTradingService.getCurrentPrice(bybitSymbol);
                result.put("success", true);
                result.put("source", "Bybit");
                result.put("symbol", bybitSymbol);
                result.put("price", price);
                result.put("timestamp", System.currentTimeMillis());
            } else {
                result.put("success", false);
                result.put("error", "Bybit服务未启用");
            }
        } catch (Exception e) {
            log.error("获取Bybit价格失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取币安黄金价格
     * 
     * GET /api/gold/price/binance
     */
    @GetMapping("/price/binance")
    public Map<String, Object> getBinancePrice() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (binanceTradingService != null && binanceTradingService.isEnabled()) {
                BigDecimal price = binanceTradingService.getCurrentPrice(binanceSymbol);
                result.put("success", true);
                result.put("source", "币安");
                result.put("symbol", binanceSymbol);
                result.put("price", price);
                result.put("timestamp", System.currentTimeMillis());
            } else {
                result.put("success", false);
                result.put("error", "币安服务未启用");
            }
        } catch (Exception e) {
            log.error("获取币安价格失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取币安黄金24小时统计
     * 
     * GET /api/gold/stats/binance
     */
    @GetMapping("/stats/binance")
    public Map<String, Object> getBinance24hrStats() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (binanceTradingService != null && binanceTradingService.isEnabled()) {
                com.google.gson.JsonObject stats = binanceTradingService.get24hrTicker(binanceSymbol);
                
                result.put("success", true);
                result.put("source", "币安");
                result.put("symbol", binanceSymbol);
                result.put("lastPrice", stats.get("lastPrice").getAsString());
                result.put("priceChange", stats.get("priceChange").getAsString());
                result.put("priceChangePercent", stats.get("priceChangePercent").getAsString());
                result.put("highPrice", stats.get("highPrice").getAsString());
                result.put("lowPrice", stats.get("lowPrice").getAsString());
                result.put("volume", stats.get("volume").getAsString());
                result.put("quoteVolume", stats.get("quoteVolume").getAsString());
                result.put("timestamp", System.currentTimeMillis());
            } else {
                result.put("success", false);
                result.put("error", "币安服务未启用");
            }
        } catch (Exception e) {
            log.error("获取币安24h统计失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 测试币安连接
     * 
     * GET /api/gold/test/binance
     */
    @GetMapping("/test/binance")
    public Map<String, Object> testBinanceConnection() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (binanceTradingService != null) {
                boolean connected = binanceTradingService.testConnection();
                result.put("success", connected);
                result.put("message", connected ? "币安连接成功" : "币安连接失败");
                result.put("enabled", binanceTradingService.isEnabled());
            } else {
                result.put("success", false);
                result.put("message", "币安服务未注入");
            }
        } catch (Exception e) {
            log.error("测试币安连接失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
}
