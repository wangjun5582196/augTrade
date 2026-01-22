package com.ltp.peter.augtrade.trading.risk;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.market.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风险管理服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class RiskManagementService {
    
    @Autowired
    private PositionMapper positionMapper;
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Value("${trading.risk.max-position-size:5.0}")
    private BigDecimal maxPositionSize;
    
    @Value("${trading.risk.max-daily-loss:1000.0}")
    private BigDecimal maxDailyLoss;
    
    @Value("${trading.risk.max-drawdown-percent:5.0}")
    private BigDecimal maxDrawdownPercent;
    
    /**
     * 交易前风控检查
     */
    public boolean checkRiskBeforeTrade(String symbol, BigDecimal quantity, boolean isBuy) {
        log.info("执行交易前风控检查 - 交易对: {}, 数量: {}, 方向: {}", symbol, quantity, isBuy ? "买入" : "卖出");
        
        // 1. 检查持仓规模
        if (!checkPositionSize(symbol, quantity)) {
            log.warn("持仓规模超限");
            return false;
        }
        
        // 2. 检查当日亏损
        if (!checkDailyLoss(symbol)) {
            log.warn("当日亏损超限");
            return false;
        }
        
        // 3. 检查最大回撤
        if (!checkMaxDrawdown()) {
            log.warn("最大回撤超限");
            return false;
        }
        
        // 4. 严格单仓位模式：必须先平掉现有仓位才能开新仓
        if (hasAnyOpenPosition(symbol)) {
            log.warn("⛔ 单仓位模式：已存在未平仓持仓，必须先止盈/止损后才能开新仓");
            return false;
        }
        
        log.info("✅ 风控检查通过 - 允许开仓");
        return true;
    }
    
    /**
     * 检查持仓规模
     */
    private boolean checkPositionSize(String symbol, BigDecimal quantity) {
        List<Position> openPositions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getStatus, "OPEN")
        );
        
        BigDecimal totalQuantity = quantity;
        for (Position position : openPositions) {
            totalQuantity = totalQuantity.add(position.getQuantity());
        }
        
        if (totalQuantity.compareTo(maxPositionSize) > 0) {
            log.warn("持仓规模 {} 超过限制 {}", totalQuantity, maxPositionSize);
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查当日亏损
     */
    private boolean checkDailyLoss(String symbol) {
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
        
        List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getSymbol, symbol)
                .ge(TradeOrder::getCreateTime, startOfDay)
                .le(TradeOrder::getCreateTime, endOfDay)
        );
        
        BigDecimal totalLoss = BigDecimal.ZERO;
        for (TradeOrder order : todayOrders) {
            if (order.getProfitLoss() != null && order.getProfitLoss().compareTo(BigDecimal.ZERO) < 0) {
                totalLoss = totalLoss.add(order.getProfitLoss().abs());
            }
        }
        
        if (totalLoss.compareTo(maxDailyLoss) > 0) {
            log.warn("当日亏损 {} 超过限制 {}", totalLoss, maxDailyLoss);
            return false;
        }
        
        log.debug("当日亏损: {}, 限制: {}", totalLoss, maxDailyLoss);
        return true;
    }
    
    /**
     * 检查最大回撤
     */
    private boolean checkMaxDrawdown() {
        List<Position> openPositions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, "OPEN")
                .orderByDesc(Position::getCreateTime)
        );
        
        if (openPositions.isEmpty()) {
            return true;
        }
        
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalMargin = BigDecimal.ZERO;
        
        for (Position position : openPositions) {
            BigDecimal currentPrice = marketDataService.getCurrentPrice(position.getSymbol());
            BigDecimal unrealizedPnl = currentPrice.subtract(position.getAvgPrice())
                    .multiply(position.getQuantity());
            
            totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
            totalMargin = totalMargin.add(position.getMargin());
        }
        
        if (totalMargin.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        
        // 计算回撤百分比
        BigDecimal drawdownPercent = totalUnrealizedPnl.abs()
                .divide(totalMargin, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        
        if (totalUnrealizedPnl.compareTo(BigDecimal.ZERO) < 0 && 
            drawdownPercent.compareTo(maxDrawdownPercent) > 0) {
            log.warn("最大回撤 {}% 超过限制 {}%", drawdownPercent, maxDrawdownPercent);
            return false;
        }
        
        log.debug("当前回撤: {}%, 限制: {}%", drawdownPercent, maxDrawdownPercent);
        return true;
    }
    
    /**
     * 检查是否有未平仓持仓（旧方法，保留兼容性）
     */
    private boolean hasOpenPosition(String symbol) {
        List<Position> openPositions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getStatus, "OPEN")
        );
        return !openPositions.isEmpty();
    }
    
    /**
     * 检查是否有任何未平仓持仓（包括多头和空头）
     * 单仓位模式专用：必须等现有仓位平掉才能开新仓
     */
    private boolean hasAnyOpenPosition(String symbol) {
        List<Position> openPositions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getStatus, "OPEN")
        );
        
        if (!openPositions.isEmpty()) {
            for (Position pos : openPositions) {
                log.info("📌 当前持仓：{} {} 手，入场价：{}，方向：{}", 
                        pos.getSymbol(), pos.getQuantity(), pos.getAvgPrice(), pos.getDirection());
            }
            return true;
        }
        return false;
    }
    
    /**
     * 获取风控统计信息
     */
    public String getRiskStatistics(String symbol) {
        List<Position> openPositions = positionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getStatus, "OPEN")
                .orderByDesc(Position::getCreateTime)
        );
        
        BigDecimal totalPositionValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        
        for (Position position : openPositions) {
            BigDecimal currentPrice = marketDataService.getCurrentPrice(position.getSymbol());
            
            // 修复：quantity已经是正确的金衡盎司数量，直接使用
            BigDecimal positionValue = position.getQuantity().multiply(currentPrice);
            
            // 修复：根据持仓方向计算未实现盈亏
            BigDecimal priceDiff = currentPrice.subtract(position.getAvgPrice());
            BigDecimal unrealizedPnl;
            if ("LONG".equals(position.getDirection())) {
                // 做多：当前价 - 开仓价
                unrealizedPnl = priceDiff.multiply(position.getQuantity());
            } else {
                // 做空：开仓价 - 当前价
                unrealizedPnl = priceDiff.negate().multiply(position.getQuantity());
            }
            
            log.debug("持仓价值计算: {} * ${} = ${}, 方向: {}, 未实现盈亏: ${}", 
                    position.getQuantity(), currentPrice, positionValue, position.getDirection(), unrealizedPnl);
            
            totalPositionValue = totalPositionValue.add(positionValue);
            totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
        }
        
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
        List<TradeOrder> todayOrders = tradeOrderMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getSymbol, symbol)
                .ge(TradeOrder::getCreateTime, startOfDay)
                .le(TradeOrder::getCreateTime, endOfDay)
        );
        
        BigDecimal todayProfit = BigDecimal.ZERO;
        for (TradeOrder order : todayOrders) {
            if (order.getProfitLoss() != null) {
                todayProfit = todayProfit.add(order.getProfitLoss());
            }
        }
        
        return String.format("风控统计 - 持仓市值: %.2f, 未实现盈亏: %.2f, 今日盈亏: %.2f", 
                totalPositionValue, totalUnrealizedPnl, todayProfit);
    }
}
