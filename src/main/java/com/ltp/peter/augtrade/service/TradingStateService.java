package com.ltp.peter.augtrade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ltp.peter.augtrade.entity.TradingState;
import com.ltp.peter.augtrade.mapper.TradingStateMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 交易状态持久化服务
 * 使用MyBatis-Plus管理关键状态，确保重启后状态不丢失
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class TradingStateService {
    
    private static final String KEY_LAST_CLOSE_TIME = "last_close_time";
    private static final String KEY_DAILY_TRADE_COUNT = "daily_trade_count";
    private static final String KEY_DAILY_TRADE_RESET_TIME = "daily_trade_reset_time";
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
    @Autowired
    private TradingStateMapper stateMapper;
    
    /**
     * 启动时自动恢复状态和重置计数器
     */
    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("【交易状态恢复】启动初始化");
        
        try {
            // 检查是否需要重置每日计数器
            LocalDateTime resetTime = getDailyTradeResetTime();
            LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
            
            if (resetTime == null || resetTime.isBefore(todayStart)) {
                resetDailyTradeCount();
                log.info("✅ 检测到新的一天，已重置每日交易计数器");
            } else {
                int count = getDailyTradeCount();
                log.info("✅ 恢复每日交易计数: {} 笔", count);
            }
            
            // 恢复冷却期状态
            LocalDateTime lastClose = getLastCloseTime();
            if (lastClose != null) {
                long seconds = Duration.between(lastClose, LocalDateTime.now()).getSeconds();
                if (seconds < 300) {
                    log.info("✅ 恢复冷却期状态 - 距离上次平仓: {}秒，剩余冷却: {}秒", 
                            seconds, 300 - seconds);
                } else {
                    log.info("✅ 恢复冷却期状态 - 距离上次平仓: {}秒（已过冷却期）", seconds);
                }
            } else {
                log.info("✅ 无需恢复冷却期（未有平仓记录）");
            }
            
        } catch (Exception e) {
            log.error("❌ 状态恢复失败", e);
        }
        
        log.info("========================================");
    }
    
    /**
     * 保存最后平仓时间
     */
    public void saveLastCloseTime(LocalDateTime time) {
        if (time == null) {
            return;
        }
        String value = time.format(FORMATTER);
        saveOrUpdate(KEY_LAST_CLOSE_TIME, value);
        log.debug("💾 保存最后平仓时间: {}", value);
    }
    
    /**
     * 获取最后平仓时间
     */
    public LocalDateTime getLastCloseTime() {
        String value = getStateValue(KEY_LAST_CLOSE_TIME);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (Exception e) {
            log.error("解析最后平仓时间失败: {}", value, e);
            return null;
        }
    }
    
    /**
     * 增加每日交易计数
     */
    public void incrementDailyTradeCount() {
        int currentCount = getDailyTradeCount();
        int newCount = currentCount + 1;
        saveOrUpdate(KEY_DAILY_TRADE_COUNT, String.valueOf(newCount));
        log.debug("💾 增加交易计数: {} → {}", currentCount, newCount);
    }
    
    /**
     * 获取每日交易计数
     */
    public int getDailyTradeCount() {
        String value = getStateValue(KEY_DAILY_TRADE_COUNT);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            log.error("解析交易计数失败: {}", value, e);
            return 0;
        }
    }
    
    /**
     * 重置每日交易计数
     */
    public void resetDailyTradeCount() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.withHour(0).withMinute(0).withSecond(0);
        
        saveOrUpdate(KEY_DAILY_TRADE_COUNT, "0");
        saveOrUpdate(KEY_DAILY_TRADE_RESET_TIME, todayStart.format(FORMATTER));
        
        log.info("📅 重置每日交易计数器 - 重置时间: {}", todayStart.format(FORMATTER));
    }
    
    /**
     * 获取每日重置时间
     */
    public LocalDateTime getDailyTradeResetTime() {
        String value = getStateValue(KEY_DAILY_TRADE_RESET_TIME);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (Exception e) {
            log.error("解析重置时间失败: {}", value, e);
            return null;
        }
    }
    
    /**
     * 保存或更新状态（MyBatis-Plus方式）
     */
    private void saveOrUpdate(String key, String value) {
        try {
            // 先查询是否存在
            LambdaQueryWrapper<TradingState> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(TradingState::getStateKey, key);
            TradingState existing = stateMapper.selectOne(queryWrapper);
            
            if (existing != null) {
                // 更新现有记录
                LambdaUpdateWrapper<TradingState> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(TradingState::getStateKey, key)
                            .set(TradingState::getStateValue, value)
                            .set(TradingState::getLastUpdate, LocalDateTime.now());
                stateMapper.update(null, updateWrapper);
            } else {
                // 插入新记录
                TradingState state = new TradingState();
                state.setStateKey(key);
                state.setStateValue(value);
                state.setCreateTime(LocalDateTime.now());
                state.setLastUpdate(LocalDateTime.now());
                stateMapper.insert(state);
            }
        } catch (Exception e) {
            log.error("保存状态失败: key={}, value={}", key, value, e);
        }
    }
    
    /**
     * 获取状态值（MyBatis-Plus方式）
     */
    private String getStateValue(String key) {
        try {
            LambdaQueryWrapper<TradingState> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(TradingState::getStateKey, key);
            TradingState state = stateMapper.selectOne(queryWrapper);
            return state != null ? state.getStateValue() : null;
        } catch (Exception e) {
            log.error("获取状态失败: key={}", key, e);
            return null;
        }
    }
}
