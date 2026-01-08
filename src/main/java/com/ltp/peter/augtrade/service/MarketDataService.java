package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.mapper.KlineMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 市场数据服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class MarketDataService {
    
    @Autowired
    private KlineMapper klineMapper;
    
    @Autowired
    private RealMarketDataService realMarketDataService;
    
    @Autowired(required = false)
    private BybitTradingService bybitTradingService;
    
    @org.springframework.beans.factory.annotation.Value("${trading.data-collector.source:mock}")
    private String dataSource;
    
    @org.springframework.beans.factory.annotation.Value("${trading.binance.symbol:PAXGUSDT}")
    private String binanceSymbol;
    
    @org.springframework.beans.factory.annotation.Value("${bybit.api.enabled:false}")
    private boolean bybitEnabled;
    
    private final Random random = new Random();
    private BigDecimal lastPrice = new BigDecimal("2650.00"); // 黄金初始价格
    
    /**
     * 获取最新的市场价格
     * 根据配置自动选择数据源（Bybit/币安/模拟）
     */
    public BigDecimal getCurrentPrice(String symbol) {
        // 优先从Bybit获取实时价格
        if (bybitEnabled && bybitTradingService != null) {
            try {
                BigDecimal price = bybitTradingService.getCurrentPrice(symbol);
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    lastPrice = price;
                    log.debug("从Bybit获取{}实时价格: {}", symbol, price);
                    return price;
                }
            } catch (Exception e) {
                log.warn("从Bybit获取{}价格失败: {}", symbol, e.getMessage());
            }
        }
        
        // 从币安获取真实价格
        if ("binance".equals(dataSource)) {
            try {
                BigDecimal price = realMarketDataService.getPriceFromBinance(symbol);
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    lastPrice = price;
                    log.debug("从币安获取{}价格: {}", symbol, price);
                    return price;
                }
            } catch (Exception e) {
                log.warn("从币安获取{}价格失败，使用模拟价格", symbol, e);
            }
        }
        
        // 使用模拟价格（回退方案）
        double change = (random.nextDouble() - 0.5) * 0.01;
        lastPrice = lastPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(change)))
                .setScale(2, RoundingMode.HALF_UP);
        
        log.debug("当前{}价格（模拟）: {}", symbol, lastPrice);
        return lastPrice;
    }
    
    /**
     * 生成模拟K线数据
     */
    public Kline generateMockKline(String symbol, String interval) {
        Kline kline = new Kline();
        kline.setSymbol(symbol);
        kline.setInterval(interval);
        kline.setTimestamp(LocalDateTime.now());
        
        BigDecimal currentPrice = getCurrentPrice(symbol);
        BigDecimal volatility = new BigDecimal("5.0"); // 波动幅度
        
        // 生成开高低收
        BigDecimal open = currentPrice.add(BigDecimal.valueOf((random.nextDouble() - 0.5) * 2)
                .multiply(volatility)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal close = currentPrice;
        
        BigDecimal high = open.max(close).add(BigDecimal.valueOf(random.nextDouble())
                .multiply(volatility)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal low = open.min(close).subtract(BigDecimal.valueOf(random.nextDouble())
                .multiply(volatility)).setScale(2, RoundingMode.HALF_UP);
        
        kline.setOpenPrice(open);
        kline.setHighPrice(high);
        kline.setLowPrice(low);
        kline.setClosePrice(close);
        kline.setVolume(BigDecimal.valueOf(random.nextInt(1000) + 100));
        kline.setAmount(close.multiply(kline.getVolume()));
        kline.setCreateTime(LocalDateTime.now());
        kline.setUpdateTime(LocalDateTime.now());
        
        return kline;
    }
    
    /**
     * 保存K线数据
     */
    public void saveKline(Kline kline) {
        try {
            klineMapper.insert(kline);
            log.info("保存K线数据成功: {} {} {}", kline.getSymbol(), kline.getInterval(), kline.getClosePrice());
        } catch (Exception e) {
            log.error("保存K线数据失败", e);
        }
    }
    
    /**
     * 获取最新的N条K线数据
     */
    public List<Kline> getLatestKlines(String symbol, String interval, int limit) {
        try {
            return klineMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Kline>()
                    .eq(Kline::getSymbol, symbol)
                    .eq(Kline::getInterval, interval)
                    .orderByDesc(Kline::getTimestamp)
                    .last("LIMIT " + limit)
            );
        } catch (Exception e) {
            log.error("查询K线数据失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取指定时间范围的K线数据
     */
    public List<Kline> getKlinesByTimeRange(String symbol, String interval, 
                                            LocalDateTime startTime, LocalDateTime endTime) {
        try {
            return klineMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Kline>()
                    .eq(Kline::getSymbol, symbol)
                    .eq(Kline::getInterval, interval)
                    .ge(Kline::getTimestamp, startTime)
                    .le(Kline::getTimestamp, endTime)
                    .orderByAsc(Kline::getTimestamp)
            );
        } catch (Exception e) {
            log.error("查询K线数据失败", e);
            return new ArrayList<>();
        }
    }
}
