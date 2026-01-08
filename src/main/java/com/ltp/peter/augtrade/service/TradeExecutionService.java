package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 交易执行服务
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class TradeExecutionService {
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private PositionMapper positionMapper;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.fixed-stop-loss:10.0}")
    private BigDecimal fixedStopLoss;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.fixed-take-profit:15.0}")
    private BigDecimal fixedTakeProfit;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.max-holding-minutes:5}")
    private int maxHoldingMinutes;
    
    /**
     * 执行买入交易（高波动版本：使用固定止损止盈）
     */
    @Transactional
    public TradeOrder executeBuy(String symbol, BigDecimal quantity, String strategyName) {
        log.info("执行买入交易 - 交易对: {}, 数量: {}, 策略: {}", symbol, quantity, strategyName);
        
        // 风控检查
        if (!riskManagementService.checkRiskBeforeTrade(symbol, quantity, true)) {
            log.warn("风控检查未通过，取消交易");
            return null;
        }
        
        // 获取当前价格
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        
        // 创建订单
        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setSymbol(symbol);
        order.setOrderType("MARKET");
        order.setSide("BUY");
        order.setPrice(currentPrice);
        order.setQuantity(quantity);
        order.setExecutedPrice(currentPrice);
        order.setExecutedQuantity(quantity);
        order.setStatus("FILLED");
        order.setStrategyName(strategyName);
        
        // 计算手续费 (0.1%)
        BigDecimal fee = currentPrice.multiply(quantity).multiply(new BigDecimal("0.001"));
        order.setFee(fee);
        
        // 计算止盈止损
        BigDecimal[] stopLevels = calculateStopLevels(currentPrice, true);
        order.setTakeProfitPrice(stopLevels[0]);
        order.setStopLossPrice(stopLevels[1]);
        
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setExecutedTime(LocalDateTime.now());
        
        tradeOrderMapper.insert(order);
        
        // 创建持仓
        Position position = new Position();
        position.setSymbol(symbol);
        position.setDirection("LONG");
        position.setQuantity(quantity);
        position.setAvgPrice(currentPrice);
        position.setCurrentPrice(currentPrice);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setMargin(currentPrice.multiply(quantity));
        position.setLeverage(1);
        position.setTakeProfitPrice(stopLevels[0]);
        position.setStopLossPrice(stopLevels[1]);
        position.setStatus("OPEN");
        position.setOpenTime(LocalDateTime.now());
        position.setCreateTime(LocalDateTime.now());
        position.setUpdateTime(LocalDateTime.now());
        
        positionMapper.insert(position);
        
        log.info("买入交易执行成功 - 订单号: {}, 价格: {}, 数量: {}", order.getOrderNo(), currentPrice, quantity);
        return order;
    }
    
    /**
     * 执行做空交易（开空仓）
     */
    @Transactional
    public TradeOrder executeSellShort(String symbol, BigDecimal quantity, String strategyName) {
        log.info("执行做空交易（开空仓） - 交易对: {}, 数量: {}, 策略: {}", symbol, quantity, strategyName);
        
        // 风控检查
        if (!riskManagementService.checkRiskBeforeTrade(symbol, quantity, false)) {
            log.warn("风控检查未通过，取消交易");
            return null;
        }
        
        // 获取当前价格
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        
        // 创建订单
        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setSymbol(symbol);
        order.setOrderType("MARKET");
        order.setSide("SELL"); // 做空
        order.setPrice(currentPrice);
        order.setQuantity(quantity);
        order.setExecutedPrice(currentPrice);
        order.setExecutedQuantity(quantity);
        order.setStatus("FILLED");
        order.setStrategyName(strategyName);
        
        // 计算手续费
        BigDecimal fee = currentPrice.multiply(quantity).multiply(new BigDecimal("0.001"));
        order.setFee(fee);
        
        // 计算止盈止损（做空：止盈在下方，止损在上方）
        BigDecimal[] stopLevels = calculateStopLevels(currentPrice, false);
        order.setTakeProfitPrice(stopLevels[0]);
        order.setStopLossPrice(stopLevels[1]);
        
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setExecutedTime(LocalDateTime.now());
        
        tradeOrderMapper.insert(order);
        
        // 创建空头持仓
        Position position = new Position();
        position.setSymbol(symbol);
        position.setDirection("SHORT"); // 空头
        position.setQuantity(quantity);
        position.setAvgPrice(currentPrice);
        position.setCurrentPrice(currentPrice);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setMargin(currentPrice.multiply(quantity));
        position.setLeverage(1);
        position.setTakeProfitPrice(stopLevels[0]);
        position.setStopLossPrice(stopLevels[1]);
        position.setStatus("OPEN");
        position.setOpenTime(LocalDateTime.now());
        position.setCreateTime(LocalDateTime.now());
        position.setUpdateTime(LocalDateTime.now());
        
        positionMapper.insert(position);
        
        log.info("做空交易执行成功 - 订单号: {}, 价格: {}, 数量: {}", order.getOrderNo(), currentPrice, quantity);
        return order;
    }
    
    /**
     * 执行平多仓交易（卖出平仓）
     */
    @Transactional
    public TradeOrder executeSell(String symbol, BigDecimal quantity, String strategyName) {
        log.info("执行平多仓交易 - 交易对: {}, 数量: {}, 策略: {}", symbol, quantity, strategyName);
        
        // 检查是否有持仓
        Position position = positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, "LONG")
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
        if (position == null) {
            log.warn("没有可平仓的多头持仓");
            return null;
        }
        
        // 获取当前价格
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        
        // 创建订单
        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setSymbol(symbol);
        order.setOrderType("MARKET");
        order.setSide("SELL");
        order.setPrice(currentPrice);
        order.setQuantity(quantity);
        order.setExecutedPrice(currentPrice);
        order.setExecutedQuantity(quantity);
        order.setStatus("FILLED");
        order.setStrategyName(strategyName);
        
        // 计算手续费
        BigDecimal fee = currentPrice.multiply(quantity).multiply(new BigDecimal("0.001"));
        order.setFee(fee);
        
        // 计算盈亏
        BigDecimal profitLoss = currentPrice.subtract(position.getAvgPrice())
                .multiply(quantity).subtract(fee);
        order.setProfitLoss(profitLoss);
        
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setExecutedTime(LocalDateTime.now());
        
        tradeOrderMapper.insert(order);
        
        // 更新持仓状态
        position.setStatus("CLOSED");
        position.setCloseTime(LocalDateTime.now());
        position.setUpdateTime(LocalDateTime.now());
        positionMapper.updateById(position);
        
        log.info("卖出交易执行成功 - 订单号: {}, 价格: {}, 盈亏: {}", order.getOrderNo(), currentPrice, profitLoss);
        return order;
    }
    
    /**
     * 执行平空仓交易（买入平仓）
     */
    @Transactional
    public TradeOrder executeBuyToCover(String symbol, BigDecimal quantity, String strategyName) {
        log.info("执行平空仓交易 - 交易对: {}, 数量: {}, 策略: {}", symbol, quantity, strategyName);
        
        // 检查是否有空头持仓
        Position position = positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, "SHORT")
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
        if (position == null) {
            log.warn("没有可平仓的空头持仓");
            return null;
        }
        
        // 获取当前价格
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        
        // 创建订单
        TradeOrder order = new TradeOrder();
        order.setOrderNo(generateOrderNo());
        order.setSymbol(symbol);
        order.setOrderType("MARKET");
        order.setSide("BUY"); // 买入平空
        order.setPrice(currentPrice);
        order.setQuantity(quantity);
        order.setExecutedPrice(currentPrice);
        order.setExecutedQuantity(quantity);
        order.setStatus("FILLED");
        order.setStrategyName(strategyName);
        
        // 计算手续费
        BigDecimal fee = currentPrice.multiply(quantity).multiply(new BigDecimal("0.001"));
        order.setFee(fee);
        
        // 计算盈亏（做空：入场价 - 平仓价）
        BigDecimal profitLoss = position.getAvgPrice().subtract(currentPrice)
                .multiply(quantity).subtract(fee);
        order.setProfitLoss(profitLoss);
        
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setExecutedTime(LocalDateTime.now());
        
        tradeOrderMapper.insert(order);
        
        // 更新持仓状态
        position.setStatus("CLOSED");
        position.setCloseTime(LocalDateTime.now());
        position.setUpdateTime(LocalDateTime.now());
        positionMapper.updateById(position);
        
        log.info("平空仓交易执行成功 - 订单号: {}, 价格: {}, 盈亏: {}", order.getOrderNo(), currentPrice, profitLoss);
        return order;
    }
    
    /**
     * 检查并执行止盈止损（支持双向持仓）
     */
    @Transactional
    public void checkAndExecuteStopLoss(String symbol) {
        // 检查多头持仓
        checkLongPosition(symbol);
        // 检查空头持仓
        checkShortPosition(symbol);
    }
    
    /**
     * 检查多头持仓止盈止损
     */
    private void checkLongPosition(String symbol) {
        Position position = positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, "LONG")
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
        if (position == null) {
            return;
        }
        
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        position.setCurrentPrice(currentPrice);
        
        // 计算未实现盈亏
        BigDecimal unrealizedPnl = currentPrice.subtract(position.getAvgPrice())
                .multiply(position.getQuantity());
        position.setUnrealizedPnl(unrealizedPnl);
        positionMapper.updateById(position);
        
        // 检查时间止损（持仓超过最大时间）
        long holdingMinutes = Duration.between(position.getOpenTime(), LocalDateTime.now()).toMinutes();
        if (holdingMinutes >= maxHoldingMinutes) {
            log.warn("⏰ 多头触发时间止损 - 持仓{}分钟，超过{}分钟限制", holdingMinutes, maxHoldingMinutes);
            executeSell(symbol, position.getQuantity(), "多头时间止损");
            return;
        }
        
        // 检查止盈（多头：价格上涨到止盈价）
        if (currentPrice.compareTo(position.getTakeProfitPrice()) >= 0) {
            log.info("💰 多头触发止盈 - 当前: ${}, 止盈: ${}, 盈利: ${}", 
                    currentPrice, position.getTakeProfitPrice(), unrealizedPnl);
            executeSell(symbol, position.getQuantity(), "多头止盈");
            return;
        }
        
        // 检查止损（多头：价格下跌到止损价）
        if (currentPrice.compareTo(position.getStopLossPrice()) <= 0) {
            log.warn("🛑 多头触发止损 - 当前: ${}, 止损: ${}, 亏损: ${}", 
                    currentPrice, position.getStopLossPrice(), unrealizedPnl);
            executeSell(symbol, position.getQuantity(), "多头止损");
        }
    }
    
    /**
     * 检查空头持仓止盈止损
     */
    private void checkShortPosition(String symbol) {
        Position position = positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, "SHORT")
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
        if (position == null) {
            return;
        }
        
        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol);
        position.setCurrentPrice(currentPrice);
        
        // 计算未实现盈亏（空头：入场价 - 当前价）
        BigDecimal unrealizedPnl = position.getAvgPrice().subtract(currentPrice)
                .multiply(position.getQuantity());
        position.setUnrealizedPnl(unrealizedPnl);
        positionMapper.updateById(position);
        
        // 检查时间止损（持仓超过最大时间）
        long holdingMinutes = Duration.between(position.getOpenTime(), LocalDateTime.now()).toMinutes();
        if (holdingMinutes >= maxHoldingMinutes) {
            log.warn("⏰ 空头触发时间止损 - 持仓{}分钟，超过{}分钟限制", holdingMinutes, maxHoldingMinutes);
            executeBuyToCover(symbol, position.getQuantity(), "空头时间止损");
            return;
        }
        
        // 检查止盈（空头：价格下跌到止盈价）
        if (currentPrice.compareTo(position.getTakeProfitPrice()) <= 0) {
            log.info("💰 空头触发止盈 - 当前: ${}, 止盈: ${}, 盈利: ${}", 
                    currentPrice, position.getTakeProfitPrice(), unrealizedPnl);
            executeBuyToCover(symbol, position.getQuantity(), "空头止盈");
            return;
        }
        
        // 检查止损（空头：价格上涨到止损价）
        if (currentPrice.compareTo(position.getStopLossPrice()) >= 0) {
            log.warn("🛑 空头触发止损 - 当前: ${}, 止损: ${}, 亏损: ${}", 
                    currentPrice, position.getStopLossPrice(), unrealizedPnl);
            executeBuyToCover(symbol, position.getQuantity(), "空头止损");
        }
    }
    
    /**
     * 获取指定方向的持仓
     */
    public Position getOpenPosition(String symbol, String direction) {
        return positionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Position>()
                .eq(Position::getSymbol, symbol)
                .eq(Position::getDirection, direction)
                .eq(Position::getStatus, "OPEN")
                .last("LIMIT 1")
        );
    }
    
    /**
     * 生成订单号
     */
    private String generateOrderNo() {
        return "ORD" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    /**
     * 计算止盈止损价格（高波动版本：使用固定金额）
     */
    private BigDecimal[] calculateStopLevels(BigDecimal entryPrice, boolean isBuy) {
        // 高波动市场使用固定止损止盈金额
        // 止损: $10, 止盈: $15
        log.info("使用固定止损止盈 - 止损: ${}, 止盈: ${}", fixedStopLoss, fixedTakeProfit);
        
        BigDecimal takeProfit;
        BigDecimal stopLoss;
        
        if (isBuy) {
            // 买入：止盈在上方，止损在下方
            takeProfit = entryPrice.add(fixedTakeProfit);
            stopLoss = entryPrice.subtract(fixedStopLoss);
        } else {
            // 卖出：止盈在下方，止损在上方
            takeProfit = entryPrice.subtract(fixedTakeProfit);
            stopLoss = entryPrice.add(fixedStopLoss);
        }
        
        log.info("入场价: ${}, 止盈价: ${}, 止损价: ${}, 盈亏比: {}:1", 
                entryPrice, takeProfit, stopLoss, 
                fixedTakeProfit.divide(fixedStopLoss, 2, java.math.RoundingMode.HALF_UP));
        
        return new BigDecimal[]{takeProfit, stopLoss};
    }
}
