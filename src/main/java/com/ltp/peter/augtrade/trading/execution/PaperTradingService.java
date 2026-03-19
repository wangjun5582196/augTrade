package com.ltp.peter.augtrade.trading.execution;

import com.ltp.peter.augtrade.entity.PaperPosition;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import com.ltp.peter.augtrade.ml.MLRecordService;
import com.ltp.peter.augtrade.notification.FeishuNotificationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模拟交易服务
 * 管理模拟持仓，追踪实际盈亏
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class PaperTradingService {
    
    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    
    @Autowired
    private com.ltp.peter.augtrade.mapper.PositionMapper positionMapper;
    
    @Autowired
    private FeishuNotificationService feishuNotificationService;
    
    @Autowired(required = false)
    private MLRecordService mlRecordService;
    
    // ✨ 移动止损配置参数
    @Value("${trading.risk.trailing-stop.enabled:true}")
    private boolean trailingStopEnabled;
    
    @Value("${trading.risk.trailing-stop.trigger-profit:30.0}")
    private BigDecimal trailingStopTriggerProfit;
    
    @Value("${trading.risk.trailing-stop.distance:10.0}")
    private BigDecimal trailingStopDistance;
    
    @Value("${trading.risk.trailing-stop.lock-profit-percent:70.0}")
    private BigDecimal trailingStopLockProfitPercent;
    
    // 使用线程安全的List存储持仓
    private final List<PaperPosition> openPositions = new CopyOnWriteArrayList<>();
    
    // 统计数据
    private int totalTrades = 0;
    private int winTrades = 0;
    private int lossTrades = 0;
    private double totalProfit = 0.0;
    
    /**
     * ✨ 应用启动时自动恢复持仓
     * 
     * 在Spring容器初始化完成后自动执行
     * 从数据库中查找所有OPEN状态的持仓并恢复到内存
     */
    @PostConstruct
    public void initializePositions() {
        log.info("🔄 系统启动 - 开始检查并恢复未关闭的持仓...");
        
        try {
            // 查询数据库中所有OPEN状态的持仓
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ltp.peter.augtrade.entity.Position> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.eq("status", "OPEN")
                 .orderByDesc("open_time");
            
            List<com.ltp.peter.augtrade.entity.Position> dbPositions = positionMapper.selectList(query);
            
            if (dbPositions.isEmpty()) {
                log.info("✅ 数据库中没有未关闭的持仓");
                return;
            }
            
            log.info("📦 发现 {} 笔未关闭的持仓，开始恢复...", dbPositions.size());
            
            int successCount = 0;
            int failCount = 0;
            
            for (com.ltp.peter.augtrade.entity.Position dbPosition : dbPositions) {
                PaperPosition recoveredPos = recoverPositionFromDb(dbPosition);
                
                if (recoveredPos != null) {
                    openPositions.add(recoveredPos);
                    successCount++;
                    
                    log.info("✅ 持仓已恢复: {}", recoveredPos.getPositionId());
                    log.info("   品种: {}, 方向: {}, 入场价: ${}, 数量: {}", 
                            recoveredPos.getSymbol(),
                            recoveredPos.getSide(),
                            recoveredPos.getEntryPrice(),
                            recoveredPos.getQuantity());
                    log.info("   止损: ${}, 止盈: ${}, 开仓时间: {}", 
                            recoveredPos.getStopLossPrice(),
                            recoveredPos.getTakeProfitPrice(),
                            recoveredPos.getOpenTime());
                } else {
                    failCount++;
                    log.error("❌ 持仓恢复失败: {} {}", dbPosition.getSymbol(), dbPosition.getDirection());
                }
            }
            
            log.info("🎯 持仓恢复完成: 成功{}笔, 失败{}笔", successCount, failCount);
            
            if (successCount > 0) {
                log.info("💼 当前内存中共有 {} 笔持仓", openPositions.size());
            }
            
        } catch (Exception e) {
            log.error("❌ 启动时恢复持仓失败", e);
        }
    }
    
    /**
     * 开仓 - 🔥 增强版：支持保存技术指标用于AI学习
     */
    public PaperPosition openPosition(String symbol, String side, BigDecimal entryPrice, 
                                      BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit,
                                      String strategyName,
                                      com.ltp.peter.augtrade.strategy.signal.TradingSignal signal,
                                      com.ltp.peter.augtrade.strategy.core.MarketContext context) {
        
        // ✨ 增强：双重检查持仓（内存 + 数据库）
        // 1. 检查内存中的持仓
        if (hasOpenPosition()) {
            log.warn("⚠️ 内存中已有持仓，不能重复开仓");
            return null;
        }
        
        // 2. ✨ 新增：检查数据库中的持仓（防止程序重启后重复开仓）
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ltp.peter.augtrade.entity.Position> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.eq("symbol", symbol)
                 .eq("status", "OPEN")
                 .orderByDesc("open_time");
            
            List<com.ltp.peter.augtrade.entity.Position> dbPositions = positionMapper.selectList(query);
            
            if (!dbPositions.isEmpty()) {
                com.ltp.peter.augtrade.entity.Position existingPos = dbPositions.get(0);
                log.warn("⚠️ 数据库中已有持仓，防止重复开仓");
                log.warn("   现有持仓: {} {} - 入场价: ${}, 开仓时间: {}", 
                        existingPos.getSymbol(), 
                        existingPos.getDirection(),
                        existingPos.getAvgPrice(),
                        existingPos.getOpenTime());
                
                // ✨ 关键：如果内存为空但数据库有记录，恢复到内存
                if (openPositions.isEmpty()) {
                    log.info("🔄 检测到内存数据丢失，从数据库恢复持仓");
                    PaperPosition recoveredPos = recoverPositionFromDb(existingPos);
                    if (recoveredPos != null) {
                        openPositions.add(recoveredPos);
                        log.info("✅ 持仓已恢复到内存: {}", recoveredPos.getPositionId());
                    }
                }
                
                return null;
            }
        } catch (Exception e) {
            log.error("检查数据库持仓失败", e);
            // 即使数据库检查失败，也不允许开仓（安全第一）
            return null;
        }
        
        PaperPosition position = new PaperPosition();
        position.setPositionId("PAPER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        position.setSymbol(symbol);
        position.setSide(side);
        position.setEntryPrice(entryPrice);
        position.setQuantity(quantity);
        position.setStopLossPrice(stopLoss);
        position.setTakeProfitPrice(takeProfit);
        position.setCurrentPrice(entryPrice);
        position.setStrategyName(strategyName);
        position.setOpenTime(LocalDateTime.now());
        position.setStatus("OPEN");
        position.calculateUnrealizedPnL();
        
        openPositions.add(position);
        totalTrades++;
        
        log.info("📝 [模拟开仓] {} - {} {}", 
                side.equals("LONG") ? "做多" : "做空", symbol, position.getPositionId());
        log.info("   入场价格: ${}", entryPrice);
        log.info("   止损价格: ${}", stopLoss);
        log.info("   止盈价格: ${}", takeProfit);
        log.info("   交易数量: {}", quantity);
        
        // 保存开仓记录到数据库（传递signal和context用于保存指标）
        saveOpenOrder(position, signal, context);
        
        // 🔔 发送飞书开仓通知
        try {
            log.info("📤 准备发送飞书开仓通知 - 订单ID: {}, {} {} @ ${}", 
                    position.getPositionId(), symbol, side, entryPrice);
            feishuNotificationService.notifyOpenPosition(
                position.getPositionId(), symbol, side, entryPrice, quantity, stopLoss, takeProfit, strategyName
            );
            log.info("✅ 飞书开仓通知已触发发送 - 订单ID: {}", position.getPositionId());
        } catch (Exception e) {
            log.error("❌ 发送飞书开仓通知异常 - 订单ID: {}, {} {} @ ${}: {}", 
                    position.getPositionId(), symbol, side, entryPrice, e.getMessage(), e);
        }
        
        return position;
    }
    
    /**
     * 更新持仓价格（增强版：支持移动止损）
     */
    public void updatePositions(BigDecimal currentPrice) {
        for (PaperPosition position : openPositions) {
            position.setCurrentPrice(currentPrice);
            position.calculateUnrealizedPnL();
            
            BigDecimal unrealizedPnL = position.getUnrealizedPnL();
            
            // 保本止损已移除：$15触发阈值过低，导致止损移到入场价后盈利单被过早平仓
            // 数据分析：11笔盈利单平均盈利$25，但TP距离平均$18（理论应赚$180）
            // 根因：$15保本触发后SL移至入场，价格小幅回调即平仓，实际盈利仅$1-3点
            // 移动止损（$30触发/$10跟踪）已足够保护利润，无需$15保本层

            // ✨ 移动止损逻辑：当盈利超过阈值时触发（$30+）
            if (trailingStopEnabled && unrealizedPnL.compareTo(trailingStopTriggerProfit) > 0) {
                if ("SHORT".equals(position.getSide())) {
                    updateShortTrailingStop(position, currentPrice, unrealizedPnL);
                } else if ("LONG".equals(position.getSide())) {
                    updateLongTrailingStop(position, currentPrice, unrealizedPnL);
                }
            }
            
            // 详细监控日志
            log.debug("💼 监控 {} - 入场: ${}, 当前: ${}, 止损: ${}, 止盈: ${}, 盈亏: ${}", 
                    position.getSide(),
                    position.getEntryPrice(),
                    currentPrice,
                    position.getStopLossPrice(),
                    position.getTakeProfitPrice(),
                    unrealizedPnL);
            
            // 检查止损
            if (position.isStopLossTriggered()) {
                boolean isTrailingStop = position.getTrailingStopEnabled() != null && 
                                        position.getTrailingStopEnabled();
                String stopType = isTrailingStop ? "移动止损" : "止损";
                
                log.warn("🛑 触及{}！当前价${} {} 止损价${}", 
                        stopType,
                        currentPrice,
                        "LONG".equals(position.getSide()) ? "<=" : ">=",
                        position.getStopLossPrice());
                closePosition(position, "STOP_LOSS", currentPrice);
            } 
            // 检查止盈
            else if (position.isTakeProfitTriggered()) {
                log.info("🎯 触及止盈！当前价${} {} 止盈价${}", 
                        currentPrice,
                        "LONG".equals(position.getSide()) ? ">=" : "<=",
                        position.getTakeProfitPrice());
                closePosition(position, "TAKE_PROFIT", currentPrice);
            }
        }
    }
    
    /**
     * 信号反转平仓（公开方法）
     */
    public void closePositionBySignalReversal(PaperPosition position, BigDecimal exitPrice) {
        closePosition(position, "SIGNAL_REVERSAL", exitPrice);
    }
    
    /**
     * 平仓
     */
    private void closePosition(PaperPosition position, String reason, BigDecimal exitPrice) {
        position.setStatus("CLOSED");
        position.setCurrentPrice(exitPrice);
        position.calculateUnrealizedPnL();
        
        BigDecimal realizedPnL = position.getUnrealizedPnL();
        boolean isWin = realizedPnL.compareTo(BigDecimal.ZERO) > 0;
        
        if (isWin) {
            winTrades++;
        } else {
            lossTrades++;
        }
        totalProfit += realizedPnL.doubleValue();
        
        String reasonText;
        if ("STOP_LOSS".equals(reason)) {
            reasonText = "止损";
        } else if ("TAKE_PROFIT".equals(reason)) {
            reasonText = "止盈";
        } else {
            reasonText = "信号反转";
        }
        
        log.info("💰 [模拟平仓] {} - {} {}", reasonText, position.getSymbol(), position.getPositionId());
        log.info("   开仓价格: ${}", position.getEntryPrice());
        log.info("   平仓价格: ${}", exitPrice);
        log.info("   {} 实际盈亏: ${}", isWin ? "✅ 盈利" : "❌ 亏损", realizedPnL);
        log.info("   持仓时长: {}秒", 
                java.time.Duration.between(position.getOpenTime(), LocalDateTime.now()).getSeconds());
        
        // 计算胜率
        double winRate = totalTrades > 0 ? (double) winTrades / totalTrades * 100 : 0;
        log.info("   📊 累计统计: 总{}单, 盈{}单, 亏{}单, 胜率{}%, 累计盈亏${}", 
                totalTrades, winTrades, lossTrades, String.format("%.1f", winRate), String.format("%.2f", totalProfit));
        
        // 保存平仓记录到数据库
        saveCloseOrder(position, reason, exitPrice, realizedPnL);
        
        // ✨ 更新ML预测结果
        updateMLPredictionResult(position.getPositionId(), reason, realizedPnL);
        
        // 🔔 发送飞书平仓通知
        try {
            long holdingTime = java.time.Duration.between(position.getOpenTime(), LocalDateTime.now()).getSeconds();
            
            log.info("📤 准备发送飞书平仓通知 - 订单ID: {}, {} {} @ ${}, 盈亏: ${}, 原因: {}", 
                    position.getPositionId(), position.getSymbol(), position.getSide(), 
                    exitPrice, realizedPnL, reasonText);
            
            if ("STOP_LOSS".equals(reason)) {
                feishuNotificationService.notifyStopLossOrTakeProfit(
                    position.getPositionId(), position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, "止损"
                );
                log.info("✅ 飞书止损通知已触发发送 - 订单ID: {}", position.getPositionId());
            } else if ("TAKE_PROFIT".equals(reason)) {
                feishuNotificationService.notifyStopLossOrTakeProfit(
                    position.getPositionId(), position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, "止盈"
                );
                log.info("✅ 飞书止盈通知已触发发送 - 订单ID: {}", position.getPositionId());
            } else if ("SIGNAL_REVERSAL".equals(reason)) {
                feishuNotificationService.notifySignalReversalClose(
                    position.getPositionId(), position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, realizedPnL
                );
                log.info("✅ 飞书信号反转通知已触发发送 - 订单ID: {}", position.getPositionId());
            } else {
                // 其他平仓原因使用通用平仓通知
                feishuNotificationService.notifyClosePosition(
                    position.getPositionId(), position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, holdingTime, reasonText
                );
                log.info("✅ 飞书通用平仓通知已触发发送 - 订单ID: {}", position.getPositionId());
            }
        } catch (Exception e) {
            log.error("❌ 发送飞书平仓通知异常 - 订单ID: {}, {} {} 盈亏${}: {}", 
                    position.getPositionId(), position.getSymbol(), reasonText, realizedPnL, e.getMessage(), e);
        }
        
        // 从持仓列表中移除
        openPositions.remove(position);
    }
    
    /**
     * 检查是否有持仓
     */
    public boolean hasOpenPosition() {
        return !openPositions.isEmpty();
    }
    
    /**
     * 获取当前持仓
     */
    public PaperPosition getCurrentPosition() {
        return openPositions.isEmpty() ? null : openPositions.get(0);
    }
    
    /**
     * 获取持仓列表
     */
    public List<PaperPosition> getOpenPositions() {
        return new ArrayList<>(openPositions);
    }
    
    /**
     * ✨ 从数据库获取今日统计数据（重启后数据仍然有效）
     */
    public String getStatistics() {
        try {
            // 从数据库实时查询今日数据
            int dbTotalTrades = getTotalTrades();
            int dbWinTrades = getWinTrades();
            int dbLossTrades = getLossTrades();
            double dbTotalProfit = getTotalProfit();
            
            double winRate = dbTotalTrades > 0 ? (double) dbWinTrades / dbTotalTrades * 100 : 0;
            return String.format("总交易: %d单 | 盈利: %d单 | 亏损: %d单 | 胜率: %.1f%% | 累计盈亏: $%.2f",
                    dbTotalTrades, dbWinTrades, dbLossTrades, winRate, dbTotalProfit);
        } catch (Exception e) {
            log.error("获取统计数据失败", e);
            return "统计数据获取失败";
        }
    }
    
    /**
     * ✨ 从数据库查询今日总交易数
     */
    public int getTotalTrades() {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.apply("DATE(create_time) = CURDATE()")
                 .ne("status", "OPEN");
            
            return tradeOrderMapper.selectCount(query).intValue();
        } catch (Exception e) {
            log.error("查询总交易数失败", e);
            return 0;
        }
    }
    
    /**
     * ✨ 从数据库查询今日盈利笔数
     */
    public int getWinTrades() {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.apply("DATE(create_time) = CURDATE()")
                 .ne("status", "OPEN")
                 .gt("profit_loss", 0);
            
            return tradeOrderMapper.selectCount(query).intValue();
        } catch (Exception e) {
            log.error("查询盈利笔数失败", e);
            return 0;
        }
    }
    
    /**
     * ✨ 从数据库查询今日亏损笔数
     */
    public int getLossTrades() {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.apply("DATE(create_time) = CURDATE()")
                 .ne("status", "OPEN")
                 .lt("profit_loss", 0);
            
            return tradeOrderMapper.selectCount(query).intValue();
        } catch (Exception e) {
            log.error("查询亏损笔数失败", e);
            return 0;
        }
    }
    
    /**
     * ✨ 从数据库查询今日总盈亏
     */
    public double getTotalProfit() {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> query = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.apply("DATE(create_time) = CURDATE()")
                 .ne("status", "OPEN");
            
            List<TradeOrder> orders = tradeOrderMapper.selectList(query);
            
            // 手动计算总和（更可靠）
            double total = 0.0;
            for (TradeOrder order : orders) {
                if (order.getProfitLoss() != null) {
                    total += order.getProfitLoss().doubleValue();
                }
            }
            
            log.debug("今日总盈亏: {} (共{}笔交易)", total, orders.size());
            return total;
            
        } catch (Exception e) {
            log.error("查询总盈亏失败", e);
            return 0.0;
        }
    }
    
    /**
     * ✨ 从数据库恢复持仓到内存（防止重启后丢失）
     */
    private PaperPosition recoverPositionFromDb(com.ltp.peter.augtrade.entity.Position dbPosition) {
        try {
            // 查找对应的订单记录获取positionId
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> orderQuery = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            orderQuery.eq("symbol", dbPosition.getSymbol())
                     .eq("status", "OPEN")
                     .orderByDesc("create_time")
                     .last("LIMIT 1");
            
            TradeOrder order = tradeOrderMapper.selectOne(orderQuery);
            if (order == null) {
                log.error("❌ 无法找到对应的订单记录");
                return null;
            }
            
            PaperPosition position = new PaperPosition();
            position.setPositionId(order.getOrderNo());
            position.setSymbol(dbPosition.getSymbol());
            position.setSide(dbPosition.getDirection());
            position.setEntryPrice(dbPosition.getAvgPrice());
            position.setQuantity(dbPosition.getQuantity());
            position.setStopLossPrice(dbPosition.getStopLossPrice());
            position.setTakeProfitPrice(dbPosition.getTakeProfitPrice());
            position.setCurrentPrice(dbPosition.getCurrentPrice());
            position.setStrategyName(order.getStrategyName());
            position.setOpenTime(dbPosition.getOpenTime());
            position.setStatus("OPEN");
            position.calculateUnrealizedPnL();
            
            return position;
            
        } catch (Exception e) {
            log.error("从数据库恢复持仓失败", e);
            return null;
        }
    }
    
    /**
     * 保存开仓记录到数据库 - 🔥 增强版：保存技术指标
     */
    private void saveOpenOrder(PaperPosition position,
                              com.ltp.peter.augtrade.strategy.signal.TradingSignal signal,
                              com.ltp.peter.augtrade.strategy.core.MarketContext context) {
        try {
            // 1. 保存到订单表
            TradeOrder order = new TradeOrder();
            order.setOrderNo(position.getPositionId());
            order.setSymbol(position.getSymbol());
            order.setOrderType("MARKET");
            order.setSide(position.getSide().equals("LONG") ? "BUY" : "SELL");
            order.setPrice(position.getEntryPrice());
            order.setQuantity(position.getQuantity());
            order.setExecutedPrice(position.getEntryPrice());
            order.setExecutedQuantity(position.getQuantity());
            order.setStatus("OPEN");
            order.setStrategyName(position.getStrategyName());
            order.setTakeProfitPrice(position.getTakeProfitPrice());
            order.setStopLossPrice(position.getStopLossPrice());
            order.setProfitLoss(BigDecimal.ZERO);
            order.setFee(BigDecimal.ZERO);
            order.setRemark("模拟开仓 - 持仓中");
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            order.setExecutedTime(LocalDateTime.now());
            
            // 🔥 保存技术指标到订单（用于AI学习）
            saveIndicatorsToOrder(order, signal, context);
            
            tradeOrderMapper.insert(order);
            log.debug("💾 开仓订单已保存到t_trade_order表");
            
            // 2. 保存到持仓表
            com.ltp.peter.augtrade.entity.Position positionEntity = new com.ltp.peter.augtrade.entity.Position();
            positionEntity.setSymbol(position.getSymbol());
            positionEntity.setDirection(position.getSide()); // LONG or SHORT
            positionEntity.setQuantity(position.getQuantity());
            positionEntity.setAvgPrice(position.getEntryPrice());
            positionEntity.setCurrentPrice(position.getEntryPrice());
            positionEntity.setUnrealizedPnl(BigDecimal.ZERO);
            positionEntity.setMargin(position.getEntryPrice().multiply(position.getQuantity()));
            positionEntity.setLeverage(1);
            positionEntity.setTakeProfitPrice(position.getTakeProfitPrice());
            positionEntity.setStopLossPrice(position.getStopLossPrice());
            positionEntity.setTrailingStopEnabled(false); // ✨ 初始未启用移动止损
            positionEntity.setStatus("OPEN");
            positionEntity.setOpenTime(LocalDateTime.now());
            positionEntity.setCreateTime(LocalDateTime.now());
            positionEntity.setUpdateTime(LocalDateTime.now());
            
            positionMapper.insert(positionEntity);
            log.debug("💾 持仓记录已保存到t_position表");
            
        } catch (Exception e) {
            log.error("保存开仓记录失败", e);
        }
    }
    
    /**
     * 保存平仓记录到数据库
     */
    private void saveCloseOrder(PaperPosition position, String reason, BigDecimal exitPrice, BigDecimal pnl) {
        String remarkText;
        if ("STOP_LOSS".equals(reason)) {
            remarkText = "止损";
        } else if ("TAKE_PROFIT".equals(reason)) {
            remarkText = "止盈";
        } else {
            remarkText = "信号反转";
        }

        // 1. 更新持仓表（优先，防止订单表失败导致持仓无法关闭）
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ltp.peter.augtrade.entity.Position> posQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            posQuery.eq("symbol", position.getSymbol())
                    .eq("direction", position.getSide())
                    .eq("status", "OPEN")
                    .orderByDesc("open_time")
                    .last("LIMIT 1");

            com.ltp.peter.augtrade.entity.Position positionEntity = positionMapper.selectOne(posQuery);

            if (positionEntity != null) {
                positionEntity.setStatus("CLOSED");
                positionEntity.setCurrentPrice(exitPrice);
                positionEntity.setUnrealizedPnl(pnl);
                positionEntity.setCloseTime(LocalDateTime.now());
                positionEntity.setUpdateTime(LocalDateTime.now());
                positionMapper.updateById(positionEntity);
                log.debug("💾 持仓表已更新: {} - {}", position.getSymbol(), position.getSide());
            } else {
                log.warn("⚠️ 未找到持仓记录: {} - {}", position.getSymbol(), position.getSide());
            }
        } catch (Exception e) {
            log.error("❌ 更新持仓表失败: {} - {}, 原因: {}", position.getSymbol(), position.getSide(), e.getMessage(), e);
        }

        // 2. 更新订单表
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> orderQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            orderQuery.eq("order_no", position.getPositionId());
            TradeOrder openOrder = tradeOrderMapper.selectOne(orderQuery);

            if (openOrder != null) {
                openOrder.setStatus("CLOSED_" + reason);
                openOrder.setProfitLoss(pnl);
                openOrder.setRemark(String.format("模拟平仓 - %s - 盈亏: $%.2f", remarkText, pnl.doubleValue()));
                openOrder.setUpdateTime(LocalDateTime.now());
                tradeOrderMapper.updateById(openOrder);
                log.debug("💾 订单表已更新: {}", position.getPositionId());
            } else {
                log.warn("⚠️ 未找到订单: {}", position.getPositionId());
            }
        } catch (Exception e) {
            log.error("❌ 更新订单表失败: {}, 原因: {}", position.getPositionId(), e.getMessage(), e);
        }
    }
    
    /**
     * 更新做空移动止损
     * 
     * @param position 持仓对象
     * @param currentPrice 当前价格
     * @param unrealizedPnL 未实现盈亏
     */
    private void updateShortTrailingStop(PaperPosition position, BigDecimal currentPrice, 
                                         BigDecimal unrealizedPnL) {
        // 首次触发移动止损
        if (position.getTrailingStopEnabled() == null || !position.getTrailingStopEnabled()) {
            position.setTrailingStopEnabled(true);
            
            // 锁定一定比例的利润
            BigDecimal lockedProfit = unrealizedPnL.multiply(trailingStopLockProfitPercent)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            
            // 计算新的止损价：入场价 - 锁定利润 / 数量
            BigDecimal newStopLoss = position.getEntryPrice()
                    .subtract(lockedProfit.divide(position.getQuantity(), 2, RoundingMode.HALF_UP));
            
            position.setStopLossPrice(newStopLoss);
            
            log.info("🔄 空头启用移动止损 - 当前价: ${}, 盈利: ${}, 锁定利润: ${}, 新止损价: ${}",
                    currentPrice, unrealizedPnL, lockedProfit, newStopLoss);
            
            // ✨ 同步更新数据库
            syncTrailingStopToDatabase(position);
            return;
        }
        
        // 已启用移动止损，继续更新
        BigDecimal newStopLoss = currentPrice.add(trailingStopDistance);
        BigDecimal oldStopLoss = position.getStopLossPrice();
        
        // 只在新止损价更优时更新（做空：止损价降低才是更优）
        if (newStopLoss.compareTo(oldStopLoss) < 0) {
            position.setStopLossPrice(newStopLoss);
            
            // 计算新的锁定利润
            BigDecimal newLockedProfit = position.getEntryPrice().subtract(newStopLoss)
                    .multiply(position.getQuantity());
            
            log.info("📉 空头移动止损更新 - 当前价: ${}, 盈利: ${}, 止损价: ${} -> ${}, 锁定利润: ${}",
                    currentPrice, unrealizedPnL, oldStopLoss, newStopLoss, newLockedProfit);
        }
    }
    
    /**
     * 更新做多移动止损
     * 
     * @param position 持仓对象
     * @param currentPrice 当前价格
     * @param unrealizedPnL 未实现盈亏
     */
    private void updateLongTrailingStop(PaperPosition position, BigDecimal currentPrice, 
                                        BigDecimal unrealizedPnL) {
        // 首次触发移动止损
        if (position.getTrailingStopEnabled() == null || !position.getTrailingStopEnabled()) {
            position.setTrailingStopEnabled(true);
            
            // 锁定一定比例的利润
            BigDecimal lockedProfit = unrealizedPnL.multiply(trailingStopLockProfitPercent)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            
            // 计算新的止损价：入场价 + 锁定利润 / 数量
            BigDecimal newStopLoss = position.getEntryPrice()
                    .add(lockedProfit.divide(position.getQuantity(), 2, RoundingMode.HALF_UP));
            
            position.setStopLossPrice(newStopLoss);
            
            log.info("🔄 多头启用移动止损 - 当前价: ${}, 盈利: ${}, 锁定利润: ${}, 新止损价: ${}",
                    currentPrice, unrealizedPnL, lockedProfit, newStopLoss);
            
            // ✨ 同步更新数据库
            syncTrailingStopToDatabase(position);
            return;
        }
        
        // 已启用移动止损，继续更新
        BigDecimal newStopLoss = currentPrice.subtract(trailingStopDistance);
        BigDecimal oldStopLoss = position.getStopLossPrice();
        
        // 只在新止损价更优时更新（做多：止损价提高才是更优）
        if (newStopLoss.compareTo(oldStopLoss) > 0) {
            position.setStopLossPrice(newStopLoss);
            
            // 计算新的锁定利润
            BigDecimal newLockedProfit = newStopLoss.subtract(position.getEntryPrice())
                    .multiply(position.getQuantity());
            
            log.info("📈 多头移动止损更新 - 当前价: ${}, 盈利: ${}, 止损价: ${} -> ${}, 锁定利润: ${}",
                    currentPrice, unrealizedPnL, oldStopLoss, newStopLoss, newLockedProfit);
        }
    }
    
    /**
     * ✨ 同步移动止损状态到数据库
     * 
     * @param position 持仓对象
     */
    private void syncTrailingStopToDatabase(PaperPosition position) {
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ltp.peter.augtrade.entity.Position> posQuery = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            posQuery.eq("symbol", position.getSymbol())
                    .eq("direction", position.getSide())
                    .eq("status", "OPEN")
                    .orderByDesc("open_time")
                    .last("LIMIT 1");
            
            com.ltp.peter.augtrade.entity.Position positionEntity = positionMapper.selectOne(posQuery);
            
            if (positionEntity != null) {
                positionEntity.setTrailingStopEnabled(position.getTrailingStopEnabled());
                positionEntity.setStopLossPrice(position.getStopLossPrice());
                positionEntity.setUpdateTime(LocalDateTime.now());
                positionMapper.updateById(positionEntity);
                log.debug("💾 移动止损状态已同步到数据库 - trailing_stop_enabled: {}, new_stop_loss: ${}", 
                        position.getTrailingStopEnabled(), position.getStopLossPrice());
            } else {
                log.warn("⚠️ 未找到持仓记录，无法同步移动止损状态");
            }
            
        } catch (Exception e) {
            log.error("同步移动止损状态到数据库失败", e);
        }
    }
    
    /**
     * 🔥 新增：保存技术指标到订单（用于AI学习）
     */
    private void saveIndicatorsToOrder(TradeOrder order,
                                      com.ltp.peter.augtrade.strategy.signal.TradingSignal signal,
                                      com.ltp.peter.augtrade.strategy.core.MarketContext context) {
        if (context == null) {
            return;
        }
        
        try {
            // 1. Williams %R
            Double williamsR = context.getIndicatorAsDouble("WilliamsR");
            if (williamsR != null) {
                order.setWilliamsR(BigDecimal.valueOf(williamsR));
            }
            
            // 2. ADX
            Double adx = context.getIndicatorAsDouble("ADX");
            if (adx != null) {
                order.setAdx(BigDecimal.valueOf(adx));
            }
            
            // 3. EMA趋势 (double → BigDecimal)
            com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend emaTrend = 
                    context.getIndicator("EMATrend", com.ltp.peter.augtrade.indicator.EMACalculator.EMATrend.class);
            if (emaTrend != null) {
                order.setEma20(new BigDecimal(String.valueOf(emaTrend.getEmaShort())));
                order.setEma50(new BigDecimal(String.valueOf(emaTrend.getEmaLong())));
            }
            
            // 4. K线形态
            com.ltp.peter.augtrade.indicator.CandlePattern pattern = 
                    context.getIndicator("CandlePattern", com.ltp.peter.augtrade.indicator.CandlePattern.class);
            if (pattern != null && pattern.hasPattern()) {
                order.setCandlePattern(pattern.getType().name());
                order.setCandlePatternStrength(pattern.getStrength());
            }
            
            // 5. 布林带（震荡市才有）
            com.ltp.peter.augtrade.indicator.BollingerBands bb = 
                    context.getIndicator("BollingerBands", com.ltp.peter.augtrade.indicator.BollingerBands.class);
            if (bb != null) {
                order.setBollingerUpper(bb.getUpper());
                order.setBollingerMiddle(bb.getMiddle());
                order.setBollingerLower(bb.getLower());
            }
            
            // 6. 信号相关
            if (signal != null) {
                order.setSignalStrength(signal.getStrength());
                order.setSignalScore(signal.getScore());

                // 🔥 P2修复-20260316: 保存V1.5字段（之前PaperTradingService未保存这些字段）
                // buyScore / sellScore
                order.setBuyScore(signal.getBuyScore());
                order.setSellScore(signal.getSellScore());

                // signalReasons
                if (signal.getBuyReasons() != null && !signal.getBuyReasons().isEmpty()) {
                    order.setSignalReasons(String.join(",", signal.getBuyReasons()));
                } else if (signal.getSellReasons() != null && !signal.getSellReasons().isEmpty()) {
                    order.setSignalReasons(String.join(",", signal.getSellReasons()));
                }

                // 动量指标
                if (signal.getMomentum2() != null) {
                    order.setMomentum2(signal.getMomentum2());
                }
                if (signal.getMomentum5() != null) {
                    order.setMomentum5(signal.getMomentum5());
                }

                // 成交量
                if (signal.getVolumeRatio() != null) {
                    order.setVolumeRatio(signal.getVolumeRatio());
                }
                if (signal.getCurrentVolume() != null) {
                    order.setCurrentVolume(signal.getCurrentVolume());
                }
                if (signal.getAvgVolume() != null) {
                    order.setAvgVolume(signal.getAvgVolume());
                }

                // 摆动点
                if (signal.getLastSwingHigh() != null) {
                    order.setSwingHigh(signal.getLastSwingHigh());
                }
                if (signal.getLastSwingLow() != null) {
                    order.setSwingLow(signal.getLastSwingLow());
                }

                // HMA
                if (signal.getHma20() != null) {
                    order.setHma20(signal.getHma20());
                }
                if (signal.getHma20Slope() != null) {
                    order.setHmaSlope(BigDecimal.valueOf(signal.getHma20Slope()));
                    // 从斜率推导趋势
                    if (signal.getHma20Slope() > 0.0001) {
                        order.setHmaTrend("UP");
                    } else if (signal.getHma20Slope() < -0.0001) {
                        order.setHmaTrend("DOWN");
                    } else {
                        order.setHmaTrend("FLAT");
                    }
                }

                // 价格位置和趋势确认
                if (signal.getPricePosition() != null) {
                    order.setPricePosition(signal.getPricePosition());
                }
                if (signal.getTrendConfirmed() != null) {
                    order.setTrendConfirmed(signal.getTrendConfirmed());
                }

                // 注: signal_generate_time / signal_to_order_delay 列已存在于DB
                // 但TradeOrder实体暂未映射这两个字段，后续可补充
            }
            
            // 🔥 新增-20260213: VWAP指标
            com.ltp.peter.augtrade.indicator.VWAPCalculator.VWAPResult vwap = 
                    context.getIndicator("VWAP");
            if (vwap != null) {
                order.setVwap(BigDecimal.valueOf(vwap.getVwap()));
                order.setVwapDeviation(BigDecimal.valueOf(vwap.getDeviationPercent()));
            }
            
            // 🔥 新增-20260213: Supertrend指标
            com.ltp.peter.augtrade.indicator.SupertrendCalculator.SupertrendResult supertrend = 
                    context.getIndicator("Supertrend");
            if (supertrend != null) {
                order.setSupertrendValue(BigDecimal.valueOf(supertrend.getSupertrendValue()));
                order.setSupertrendDirection(supertrend.isUpTrend() ? "UP" : "DOWN");
            }
            
            // 🔥 新增-20260213: OBV指标
            com.ltp.peter.augtrade.indicator.OBVCalculator.OBVResult obv = 
                    context.getIndicator("OBV");
            if (obv != null) {
                order.setObvTrend(BigDecimal.valueOf(obv.getObvTrend()));
                order.setObvVolumeConfirmed(obv.isVolumeConfirmed() ? 1 : 0);
            }
            
            // 7. ATR和市场状态
            if (!context.getKlines().isEmpty()) {
                // 计算ATR (Double → BigDecimal)
                com.ltp.peter.augtrade.indicator.ATRCalculator atrCalc = 
                        new com.ltp.peter.augtrade.indicator.ATRCalculator();
                Double atr = atrCalc.calculate(context.getKlines(), 14);
                if (atr != null) {
                    order.setAtr(BigDecimal.valueOf(atr));
                }
                
                // 判断市场状态
                if (adx != null) {
                    String marketRegime;
                    if (adx > 30) {
                        marketRegime = "STRONG_TREND";
                    } else if (adx >= 20) {
                        marketRegime = "WEAK_TREND";
                    } else {
                        marketRegime = "RANGING";
                    }
                    order.setMarketRegime(marketRegime);
                }
            }
            
            log.debug("✅ 技术指标已保存到订单: Williams={}, ADX={}, 信号强度={}, 市场状态={}", 
                    williamsR, adx, signal != null ? signal.getStrength() : "N/A", order.getMarketRegime());
            
            // 🔥 新增：保存ML预测记录到ml_prediction_record表
            if (mlRecordService != null && signal != null) {
                try {
                    // 从订单或信号中获取ML预测值（如果存在）
                    BigDecimal mlPrediction = order.getMlPrediction();
                    BigDecimal mlConfidence = order.getMlConfidence();
                    
                    // 如果订单中有ML预测值，则记录
                    if (mlPrediction != null && mlConfidence != null) {
                        String predictedSignal = "BUY".equals(order.getSide()) ? "BUY" : "SELL";
                        
                        mlRecordService.recordPrediction(
                            order.getSymbol(),
                            mlPrediction,
                            predictedSignal,
                            mlConfidence,
                            order.getWilliamsR(),
                            order.getPrice(),
                            true, // tradeTaken = true (已开仓)
                            order.getOrderNo()
                        );
                        
                        log.info("✅ ML预测已记录到ml_prediction_record表: 订单={}, 信号={}, 预测值={}, 置信度={}", 
                                order.getOrderNo(), predictedSignal, mlPrediction, mlConfidence);
                    } else {
                        // 即使没有ML预测值，也记录基础信息用于未来训练
                        String predictedSignal = "BUY".equals(order.getSide()) ? "BUY" : "SELL";
                        BigDecimal defaultPrediction = "BUY".equals(predictedSignal) ? new BigDecimal("0.6") : new BigDecimal("0.4");
                        BigDecimal defaultConfidence = new BigDecimal("0.5");
                        
                        mlRecordService.recordPrediction(
                            order.getSymbol(),
                            defaultPrediction,
                            predictedSignal,
                            defaultConfidence,
                            order.getWilliamsR(),
                            order.getPrice(),
                            true,
                            order.getOrderNo()
                        );
                        
                        log.debug("📝 ML基础记录已保存（使用默认值）: 订单={}, 信号={}", order.getOrderNo(), predictedSignal);
                    }
                } catch (Exception mlEx) {
                    log.warn("⚠️ 保存ML预测记录失败（不影响开仓）: {}", mlEx.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.warn("⚠️ 保存技术指标失败（不影响开仓）", e);
        }
    }
    
    /**
     * ✨ 新增：更新ML预测结果
     * 
     * 在平仓时更新ML预测记录的实际结果
     * 
     * @param orderNo 订单号（positionId）
     * @param closeReason 平仓原因（STOP_LOSS/TAKE_PROFIT/SIGNAL_REVERSAL）
     * @param profitLoss 实际盈亏
     */
    private void updateMLPredictionResult(String orderNo, String closeReason, BigDecimal profitLoss) {
        if (mlRecordService == null) {
            return;
        }
        
        try {
            String actualResult;
            if (profitLoss.compareTo(BigDecimal.ZERO) > 0) {
                actualResult = "PROFIT";
            } else if (profitLoss.compareTo(BigDecimal.ZERO) < 0) {
                actualResult = "LOSS";
            } else {
                actualResult = "BREAK_EVEN";
            }
            
            mlRecordService.updatePredictionResult(orderNo, actualResult, profitLoss);
            
            log.debug("✅ ML预测结果已更新: 订单={}, 结果={}, 盈亏=${}", 
                     orderNo, actualResult, profitLoss);
            
        } catch (Exception e) {
            log.warn("⚠️ 更新ML预测结果失败: 订单={}", orderNo, e);
        }
    }
}
