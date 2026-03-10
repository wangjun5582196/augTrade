package com.ltp.peter.augtrade.trading.execution;

import com.ltp.peter.augtrade.entity.Position;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.PositionMapper;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.market.MarketDataService;
import com.ltp.peter.augtrade.strategy.signal.TradingSignal;
import com.ltp.peter.augtrade.trading.risk.RiskManagementService;
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
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.trailing-stop.enabled:true}")
    private boolean trailingStopEnabled;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.trailing-stop.trigger-profit:30.0}")
    private BigDecimal trailingStopTriggerProfit;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.trailing-stop.distance:10.0}")
    private BigDecimal trailingStopDistance;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.trailing-stop.lock-profit-percent:70.0}")
    private BigDecimal trailingStopLockProfitPercent;
    
    @org.springframework.beans.factory.annotation.Value("${trading.risk.max-single-loss:50.0}")
    private BigDecimal maxSingleLoss;
    
    /**
     * 🔥 新增-20260310: 执行买入交易（带信号追踪）
     */
    @Transactional
    public TradeOrder executeBuy(String symbol, BigDecimal quantity, String strategyName, TradingSignal signal) {
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
        
        // 🔥 新增-20260310: 填充信号追踪数据
        if (signal != null) {
            fillSignalDataToOrder(order, signal);
        }
        
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
     * 🔥 新增-20260310: 执行做空交易（带信号追踪）
     */
    @Transactional
    public TradeOrder executeSellShort(String symbol, BigDecimal quantity, String strategyName, TradingSignal signal) {
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
        
        // 🔥 新增-20260310: 填充信号追踪数据
        if (signal != null) {
            fillSignalDataToOrder(order, signal);
        }
        
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
     * 检查多头持仓止盈止损（增强版：支持移动止损）
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
        
        // 移动止损逻辑（在时间止损前执行）
        boolean trailingStopUpdated = false;
        if (trailingStopEnabled) {
            trailingStopUpdated = updateTrailingStopForLong(position, currentPrice, unrealizedPnl);
        }
        
        // 保存到数据库（包括移动止损的修改）
        positionMapper.updateById(position);
        
        // 如果移动止损刚刚启用，记录到日志
        if (trailingStopUpdated) {
            log.info("✅ 移动止损状态已保存到数据库 - 持仓ID: {}, trailing_stop_enabled: {}", 
                    position.getId(), position.getTrailingStopEnabled());
        }
        
        // 检查时间止损（持仓超过最大时间）
        long holdingMinutes = Duration.between(position.getOpenTime(), LocalDateTime.now()).toMinutes();
        if (holdingMinutes >= maxHoldingMinutes) {
            log.warn("⏰ 多头触发时间止损 - 持仓{}分钟，超过{}分钟限制，当前盈利: ${}", 
                    holdingMinutes, maxHoldingMinutes, unrealizedPnl);
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
        
        // 🔥 P0修复：最大亏损保护（最优先级）
        BigDecimal currentLoss = unrealizedPnl.abs();
        if (unrealizedPnl.compareTo(BigDecimal.ZERO) < 0 && currentLoss.compareTo(maxSingleLoss) > 0) {
            log.error("🚨 多头超过最大亏损限制 - 当前亏损: ${}, 限制: ${}, 强制平仓！", 
                    currentLoss, maxSingleLoss);
            executeSell(symbol, position.getQuantity(), "多头最大亏损保护");
            return;
        }
        
        // 检查止损（多头：价格下跌到止损价）
        if (currentPrice.compareTo(position.getStopLossPrice()) <= 0) {
            String stopType = position.getTrailingStopEnabled() != null && position.getTrailingStopEnabled() 
                    ? "多头移动止损" : "多头止损";
            log.warn("🛑 多头触发止损 - 当前: ${}, 止损: ${}, 盈亏: ${}", 
                    currentPrice, position.getStopLossPrice(), unrealizedPnl);
            executeSell(symbol, position.getQuantity(), stopType);
        }
    }
    
    /**
     * 检查空头持仓止盈止损（增强版：支持移动止损）
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
        
        // 移动止损逻辑（在时间止损前执行）
        boolean trailingStopUpdated = false;
        if (trailingStopEnabled) {
            trailingStopUpdated = updateTrailingStopForShort(position, currentPrice, unrealizedPnl);
        }
        
        // 保存到数据库（包括移动止损的修改）
        positionMapper.updateById(position);
        
        // 如果移动止损刚刚启用，记录到日志
        if (trailingStopUpdated) {
            log.info("✅ 移动止损状态已保存到数据库 - 持仓ID: {}, trailing_stop_enabled: {}", 
                    position.getId(), position.getTrailingStopEnabled());
        }
        
        // 检查时间止损（持仓超过最大时间）
        long holdingMinutes = Duration.between(position.getOpenTime(), LocalDateTime.now()).toMinutes();
        if (holdingMinutes >= maxHoldingMinutes) {
            log.warn("⏰ 空头触发时间止损 - 持仓{}分钟，超过{}分钟限制，当前盈利: ${}", 
                    holdingMinutes, maxHoldingMinutes, unrealizedPnl);
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
        
        // 🔥 P0修复：最大亏损保护（最优先级）
        BigDecimal currentLoss = unrealizedPnl.abs();
        if (unrealizedPnl.compareTo(BigDecimal.ZERO) < 0 && currentLoss.compareTo(maxSingleLoss) > 0) {
            log.error("🚨 空头超过最大亏损限制 - 当前亏损: ${}, 限制: ${}, 强制平仓！", 
                    currentLoss, maxSingleLoss);
            executeBuyToCover(symbol, position.getQuantity(), "空头最大亏损保护");
            return;
        }
        
        // 检查止损（空头：价格上涨到止损价）
        if (currentPrice.compareTo(position.getStopLossPrice()) >= 0) {
            String stopType = position.getTrailingStopEnabled() != null && position.getTrailingStopEnabled() 
                    ? "空头移动止损" : "空头止损";
            log.warn("🛑 空头触发止损 - 当前: ${}, 止损: ${}, 盈亏: ${}", 
                    currentPrice, position.getStopLossPrice(), unrealizedPnl);
            executeBuyToCover(symbol, position.getQuantity(), stopType);
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
    
    /**
     * 更新多头移动止损
     * 
     * @param position 持仓信息
     * @param currentPrice 当前价格
     * @param unrealizedPnl 未实现盈亏
     * @return 是否首次启用了移动止损
     */
    private boolean updateTrailingStopForLong(Position position, BigDecimal currentPrice, BigDecimal unrealizedPnl) {
        // 触发条件：盈利达到触发阈值
        if (unrealizedPnl.compareTo(trailingStopTriggerProfit) < 0) {
            return false;
        }
        
        // 首次触发移动止损
        if (position.getTrailingStopEnabled() == null || !position.getTrailingStopEnabled()) {
            // 锁定一定比例的利润
            BigDecimal lockedProfit = unrealizedPnl.multiply(trailingStopLockProfitPercent)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal newStopLoss = position.getAvgPrice().add(lockedProfit.divide(position.getQuantity(), 2, java.math.RoundingMode.HALF_UP));
            
            position.setStopLossPrice(newStopLoss);
            position.setTrailingStopEnabled(true);
            
            log.info("🔄 多头启用移动止损 - 当前价: ${}, 盈利: ${}, 锁定利润: ${}, 新止损价: ${}", 
                    currentPrice, unrealizedPnl, lockedProfit, newStopLoss);
            return true; // 返回true表示首次启用
        }
        
        // 计算新的移动止损价
        BigDecimal newStopLoss = currentPrice.subtract(trailingStopDistance);
        
        // 止损价只能上升，不能下降
        if (newStopLoss.compareTo(position.getStopLossPrice()) > 0) {
            BigDecimal oldStopLoss = position.getStopLossPrice();
            position.setStopLossPrice(newStopLoss);
            
            // 计算新的锁定利润
            BigDecimal newLockedProfit = newStopLoss.subtract(position.getAvgPrice())
                    .multiply(position.getQuantity());
            
            log.info("📈 多头移动止损更新 - 当前价: ${}, 盈利: ${}, 止损价: ${} -> ${}, 锁定利润: ${}", 
                    currentPrice, unrealizedPnl, oldStopLoss, newStopLoss, newLockedProfit);
        }
        
        return false;
    }
    
    /**
     * 更新空头移动止损
     * 
     * @param position 持仓信息
     * @param currentPrice 当前价格
     * @param unrealizedPnl 未实现盈亏
     * @return 是否首次启用了移动止损
     */
    private boolean updateTrailingStopForShort(Position position, BigDecimal currentPrice, BigDecimal unrealizedPnl) {
        // 触发条件：盈利达到触发阈值
        if (unrealizedPnl.compareTo(trailingStopTriggerProfit) < 0) {
            return false;
        }
        
        // 首次触发移动止损
        if (position.getTrailingStopEnabled() == null || !position.getTrailingStopEnabled()) {
            // 锁定一定比例的利润
            BigDecimal lockedProfit = unrealizedPnl.multiply(trailingStopLockProfitPercent)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal newStopLoss = position.getAvgPrice().subtract(lockedProfit.divide(position.getQuantity(), 2, java.math.RoundingMode.HALF_UP));
            
            position.setStopLossPrice(newStopLoss);
            position.setTrailingStopEnabled(true);
            
            log.info("🔄 空头启用移动止损 - 当前价: ${}, 盈利: ${}, 锁定利润: ${}, 新止损价: ${}", 
                    currentPrice, unrealizedPnl, lockedProfit, newStopLoss);
            return true; // 返回true表示首次启用
        }
        
        // 计算新的移动止损价
        BigDecimal newStopLoss = currentPrice.add(trailingStopDistance);
        
        // 空头：止损价只能下降，不能上升
        if (newStopLoss.compareTo(position.getStopLossPrice()) < 0) {
            BigDecimal oldStopLoss = position.getStopLossPrice();
            position.setStopLossPrice(newStopLoss);
            
            // 计算新的锁定利润
            BigDecimal newLockedProfit = position.getAvgPrice().subtract(newStopLoss)
                    .multiply(position.getQuantity());
            
            log.info("📉 空头移动止损更新 - 当前价: ${}, 盈利: ${}, 止损价: ${} -> ${}, 锁定利润: ${}", 
                    currentPrice, unrealizedPnl, oldStopLoss, newStopLoss, newLockedProfit);
        }
        
        return false;
    }
    
    /**
     * 🔥 新增-20260310: 填充信号追踪数据到订单（完整版）
     * 
     * 从TradingSignal中提取所有技术指标数据并填充到TradeOrder中
     * 
     * @param order 订单对象
     * @param signal 交易信号对象
     */
    private void fillSignalDataToOrder(TradeOrder order, TradingSignal signal) {
        try {
            // ========== 基础信号数据 ==========
            order.setBuyScore(signal.getBuyScore());
            order.setSellScore(signal.getSellScore());
            
            // 信号理由（转为字符串）
            if (signal.getBuyReasons() != null && !signal.getBuyReasons().isEmpty()) {
                order.setSignalReasons(String.join(", ", signal.getBuyReasons()));
            } else if (signal.getSellReasons() != null && !signal.getSellReasons().isEmpty()) {
                order.setSignalReasons(String.join(", ", signal.getSellReasons()));
            }
            
            // ========== Williams R & ADX ==========
            if (signal.getWilliamsR() != null) {
                order.setWilliamsR(BigDecimal.valueOf(signal.getWilliamsR()));
            }
            if (signal.getAdx() != null) {
                order.setAdx(BigDecimal.valueOf(signal.getAdx()));
            }
            
            // ========== EMA均线 ==========
            order.setEma20(signal.getEma20());
            order.setEma50(signal.getEma50());
            
            // ========== ATR波动率 ==========
            order.setAtr(signal.getAtr());
            
            // ========== K线形态 ==========
            order.setCandlePattern(signal.getCandlePattern());
            order.setCandlePatternStrength(signal.getCandlePatternStrength());
            
            // ========== 布林带 ==========
            order.setBollingerUpper(signal.getBollingerUpper());
            order.setBollingerMiddle(signal.getBollingerMiddle());
            order.setBollingerLower(signal.getBollingerLower());
            
            // ========== 信号强度 & 市场状态 ==========
            order.setSignalStrength(signal.getSignalStrength());
            order.setMarketRegime(signal.getMarketRegime());
            
            // ========== ML预测 ==========
            order.setMlPrediction(signal.getMlPrediction());
            order.setMlConfidence(signal.getMlConfidence());
            
            // ========== VWAP ==========
            order.setVwap(signal.getVwap());
            order.setVwapDeviation(signal.getVwapDeviation());
            
            // ========== Supertrend ==========
            order.setSupertrendValue(signal.getSupertrendValue());
            order.setSupertrendDirection(signal.getSupertrendDirection());
            
            // ========== OBV ==========
            order.setObvTrend(signal.getObvTrend());
            order.setObvVolumeConfirmed(signal.getObvVolumeConfirmed());
            
            // ========== 动量指标 ==========
            order.setMomentum2(signal.getMomentum2());
            order.setMomentum5(signal.getMomentum5());
            
            // ========== 成交量指标 ==========
            order.setVolumeRatio(signal.getVolumeRatio());
            order.setCurrentVolume(signal.getCurrentVolume());
            order.setAvgVolume(signal.getAvgVolume());
            
            // ========== 摆动点指标 ==========
            order.setSwingHigh(signal.getLastSwingHigh());
            order.setSwingLow(signal.getLastSwingLow());
            
            // 计算价格距离摆动点的距离
            if (signal.getCurrentPrice() != null) {
                if (signal.getLastSwingHigh() != null) {
                    order.setSwingHighDistance(signal.getCurrentPrice().subtract(signal.getLastSwingHigh()));
                }
                if (signal.getLastSwingLow() != null) {
                    order.setSwingLowDistance(signal.getCurrentPrice().subtract(signal.getLastSwingLow()));
                }
            }
            
            // ========== HMA指标 ==========
            order.setHma20(signal.getHma20());
            if (signal.getHma20Slope() != null) {
                order.setHmaSlope(BigDecimal.valueOf(signal.getHma20Slope()));
            }
            
            // HMA趋势方向（从斜率推导）
            if (signal.getHma20Slope() != null) {
                if (signal.getHma20Slope() > 0.1) {
                    order.setHmaTrend("UP");
                } else if (signal.getHma20Slope() < -0.1) {
                    order.setHmaTrend("DOWN");
                } else {
                    order.setHmaTrend("SIDEWAYS");
                }
            }
            
            // ========== 市场状态快照 ==========
            order.setPricePosition(signal.getPricePosition());
            order.setTrendConfirmed(signal.getTrendConfirmed());
            
            log.debug("✅ 信号追踪数据已填充 - Williams R:{}, ADX:{}, buyScore:{}, sellScore:{}", 
                    signal.getWilliamsR(), signal.getAdx(), signal.getBuyScore(), signal.getSellScore());
            
        } catch (Exception e) {
            log.error("❌ 填充信号追踪数据失败", e);
        }
    }
    
    /**
     * 🔥 兼容方法-20260310: 保留原有方法签名，内部调用新方法
     */
    @Transactional
    public TradeOrder executeBuy(String symbol, BigDecimal quantity, String strategyName) {
        return executeBuy(symbol, quantity, strategyName, null);
    }
    
    /**
     * 🔥 兼容方法-20260310: 保留原有方法签名，内部调用新方法
     */
    @Transactional
    public TradeOrder executeSellShort(String symbol, BigDecimal quantity, String strategyName) {
        return executeSellShort(symbol, quantity, strategyName, null);
    }
}
