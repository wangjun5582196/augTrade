package com.ltp.peter.augtrade.service;

import com.ltp.peter.augtrade.entity.PaperPosition;
import com.ltp.peter.augtrade.entity.TradeOrder;
import com.ltp.peter.augtrade.mapper.TradeOrderMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * 开仓
     */
    public PaperPosition openPosition(String symbol, String side, BigDecimal entryPrice, 
                                      BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit,
                                      String strategyName) {
        
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
        
        // 保存开仓记录到数据库
        saveOpenOrder(position);
        
        // 🔔 发送飞书开仓通知
        try {
            feishuNotificationService.notifyOpenPosition(
                symbol, side, entryPrice, quantity, stopLoss, takeProfit, strategyName
            );
        } catch (Exception e) {
            log.error("发送飞书开仓通知失败", e);
        }
        
        return position;
    }
    
    /**
     * 更新持仓价格
     */
    public void updatePositions(BigDecimal currentPrice) {
        for (PaperPosition position : openPositions) {
            position.setCurrentPrice(currentPrice);
            position.calculateUnrealizedPnL();
            
            // ✨ 增强：添加详细的监控日志
            log.debug("💼 监控 {} - 入场: ${}, 当前: ${}, 止损: ${}, 止盈: ${}, 盈亏: ${}", 
                    position.getSide(),
                    position.getEntryPrice(),
                    currentPrice,
                    position.getStopLossPrice(),
                    position.getTakeProfitPrice(),
                    position.getUnrealizedPnL());
            
            // 检查止损
            if (position.isStopLossTriggered()) {
                log.warn("🛑 触及止损！当前价${} {} 止损价${}", 
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
        
        // 🔔 发送飞书平仓通知
        try {
            long holdingTime = java.time.Duration.between(position.getOpenTime(), LocalDateTime.now()).getSeconds();
            
            if ("STOP_LOSS".equals(reason)) {
                feishuNotificationService.notifyStopLossOrTakeProfit(
                    position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, "止损"
                );
            } else if ("TAKE_PROFIT".equals(reason)) {
                feishuNotificationService.notifyStopLossOrTakeProfit(
                    position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, "止盈"
                );
            } else if ("SIGNAL_REVERSAL".equals(reason)) {
                feishuNotificationService.notifySignalReversalClose(
                    position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, realizedPnL
                );
            } else {
                // 其他平仓原因使用通用平仓通知
                feishuNotificationService.notifyClosePosition(
                    position.getSymbol(), position.getSide(), position.getEntryPrice(),
                    exitPrice, position.getQuantity(), realizedPnL, holdingTime, reasonText
                );
            }
        } catch (Exception e) {
            log.error("发送飞书平仓通知失败", e);
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
     * 获取统计数据
     */
    public String getStatistics() {
        double winRate = totalTrades > 0 ? (double) winTrades / totalTrades * 100 : 0;
        return String.format("总交易: %d单 | 盈利: %d单 | 亏损: %d单 | 胜率: %.1f%% | 累计盈亏: $%.2f",
                totalTrades, winTrades, lossTrades, winRate, totalProfit);
    }
    
    public int getTotalTrades() {
        return totalTrades;
    }
    
    public int getWinTrades() {
        return winTrades;
    }
    
    public int getLossTrades() {
        return lossTrades;
    }
    
    public double getTotalProfit() {
        return totalProfit;
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
     * 保存开仓记录到数据库
     */
    private void saveOpenOrder(PaperPosition position) {
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
        try {
            // 1. 更新订单表 - 使用QueryWrapper高效查询
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TradeOrder> orderQuery = 
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            orderQuery.eq("order_no", position.getPositionId());
            TradeOrder openOrder = tradeOrderMapper.selectOne(orderQuery);
            
            if (openOrder != null) {
                openOrder.setStatus("CLOSED_" + reason);
                openOrder.setProfitLoss(pnl);
                
                String remarkText;
                if ("STOP_LOSS".equals(reason)) {
                    remarkText = "止损";
                } else if ("TAKE_PROFIT".equals(reason)) {
                    remarkText = "止盈";
                } else {
                    remarkText = "信号反转";
                }
                
                openOrder.setRemark(String.format("模拟平仓 - %s - 盈亏: $%.2f", remarkText, pnl.doubleValue()));
                openOrder.setUpdateTime(LocalDateTime.now());
                tradeOrderMapper.updateById(openOrder);
                log.debug("💾 订单表已更新: {}", position.getPositionId());
            } else {
                log.warn("⚠️ 未找到订单: {}", position.getPositionId());
            }
            
            // 2. 更新持仓表
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
            log.error("保存平仓记录失败: {}", e.getMessage(), e);
        }
    }
}
