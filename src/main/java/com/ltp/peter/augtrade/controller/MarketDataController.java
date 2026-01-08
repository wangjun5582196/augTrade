package com.ltp.peter.augtrade.controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ltp.peter.augtrade.service.RealMarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 行情数据接口
 * 
 * @author Peter Wang
 */
@Slf4j
@RestController
@RequestMapping("/market")
public class MarketDataController {
    
    @Autowired
    private RealMarketDataService realMarketDataService;
    
    private final Gson gson = new Gson();
    
    /**
     * 从金十数据获取黄金价格
     * 
     * GET /api/market/jin10/gold/price
     * 
     * @return 黄金当前价格
     */
    @GetMapping("/jin10/gold/price")
    public Map<String, Object> getGoldPriceFromJin10() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("📊 接收到获取金十黄金价格请求");
            
            BigDecimal price = realMarketDataService.getGoldPriceFromJin10();
            
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                result.put("success", true);
                result.put("price", price);
                result.put("currency", "USD");
                result.put("unit", "美元/盎司");
                result.put("source", "金十数据");
                result.put("timestamp", System.currentTimeMillis());
                
                log.info("✅ 成功获取金十黄金价格: ${}", price);
            } else {
                result.put("success", false);
                result.put("message", "获取金十数据失败，请检查网络或稍后重试");
                
                log.warn("⚠️ 获取金十黄金价格失败");
            }
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "服务异常: " + e.getMessage());
            log.error("❌ 获取金十黄金价格异常", e);
        }
        
        return result;
    }
    
    /**
     * 从金十数据获取黄金详细行情
     * 
     * GET /api/market/jin10/gold/detail
     * 
     * @return 黄金详细行情数据
     */
    @GetMapping("/jin10/gold/detail")
    public Map<String, Object> getGoldDetailFromJin10() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("📊 接收到获取金十黄金详细行情请求");
            
            JsonObject detail = realMarketDataService.getGoldDetailFromJin10();
            
            if (detail != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("currentPrice", detail.has("last_price") ? detail.get("last_price").getAsString() : null);
                data.put("bidPrice", detail.has("bid") ? detail.get("bid").getAsString() : null);
                data.put("askPrice", detail.has("ask") ? detail.get("ask").getAsString() : null);
                data.put("change", detail.has("change") ? detail.get("change").getAsString() : null);
                data.put("changePercent", detail.has("change_percent") ? detail.get("change_percent").getAsString() : null);
                data.put("highPrice", detail.has("high") ? detail.get("high").getAsString() : null);
                data.put("lowPrice", detail.has("low") ? detail.get("low").getAsString() : null);
                data.put("openPrice", detail.has("open") ? detail.get("open").getAsString() : null);
                
                result.put("success", true);
                result.put("data", data);
                result.put("source", "金十数据");
                result.put("symbol", "XAUUSD");
                result.put("timestamp", System.currentTimeMillis());
                
                log.info("✅ 成功获取金十黄金详细行情");
            } else {
                result.put("success", false);
                result.put("message", "获取金十详细数据失败");
                
                log.warn("⚠️ 获取金十黄金详细行情失败");
            }
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "服务异常: " + e.getMessage());
            log.error("❌ 获取金十黄金详细行情异常", e);
        }
        
        return result;
    }
    
    /**
     * 对比多个数据源的黄金价格
     * 
     * GET /api/market/gold/compare
     * 
     * @return 多个数据源的价格对比
     */
    @GetMapping("/gold/compare")
    public Map<String, Object> compareGoldPrices() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> prices = new HashMap<>();
        
        try {
            log.info("📊 接收到黄金价格对比请求");
            
            // 获取金十价格
            BigDecimal jin10Price = realMarketDataService.getGoldPriceFromJin10();
            if (jin10Price != null && jin10Price.compareTo(BigDecimal.ZERO) > 0) {
                prices.put("jin10", jin10Price);
            }
            
            // 获取Binance PAXG价格（黄金代币）
            BigDecimal binancePrice = realMarketDataService.getGoldPriceFromBinance();
            if (binancePrice != null && binancePrice.compareTo(BigDecimal.ZERO) > 0) {
                prices.put("binance_paxg", binancePrice);
            }
            
            result.put("success", true);
            result.put("prices", prices);
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("✅ 成功获取多源黄金价格对比");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "服务异常: " + e.getMessage());
            log.error("❌ 黄金价格对比异常", e);
        }
        
        return result;
    }
}
