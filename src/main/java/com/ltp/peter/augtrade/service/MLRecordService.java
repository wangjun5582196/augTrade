package com.ltp.peter.augtrade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ltp.peter.augtrade.entity.MLPredictionRecord;
import com.ltp.peter.augtrade.mapper.MLPredictionRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * ML预测记录服务
 * 用于跟踪和分析ML模型的表现
 * 
 * @author Peter Wang
 */
@Slf4j
@Service
public class MLRecordService {
    
    @Autowired
    private MLPredictionRecordMapper predictionRecordMapper;
    
    /**
     * 记录ML预测
     */
    public void recordPrediction(String symbol, BigDecimal mlPrediction, String predictedSignal,
                                  BigDecimal confidence, BigDecimal williamsR, BigDecimal currentPrice,
                                  boolean tradeTaken, String orderNo) {
        MLPredictionRecord record = new MLPredictionRecord();
        record.setSymbol(symbol);
        record.setMlPrediction(mlPrediction);
        record.setPredictedSignal(predictedSignal);
        record.setConfidence(confidence);
        record.setWilliamsR(williamsR);
        record.setPriceAtPrediction(currentPrice);
        record.setTradeTaken(tradeTaken);
        record.setOrderNo(orderNo);
        record.setActualResult("PENDING");
        record.setPredictionTime(LocalDateTime.now());
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        
        predictionRecordMapper.insert(record);
        
        log.debug("记录ML预测：信号={}, 预测值={}, 置信度={}, 是否交易={}", 
                predictedSignal, mlPrediction, confidence, tradeTaken);
    }
    
    /**
     * 更新预测结果
     */
    public void updatePredictionResult(String orderNo, String actualResult, BigDecimal profitLoss) {
        LambdaQueryWrapper<MLPredictionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MLPredictionRecord::getOrderNo, orderNo)
               .eq(MLPredictionRecord::getActualResult, "PENDING");
        
        MLPredictionRecord record = predictionRecordMapper.selectOne(wrapper);
        if (record != null) {
            record.setActualResult(actualResult);
            record.setProfitLoss(profitLoss);
            record.setResultTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            
            // 判断预测是否正确
            boolean isCorrect = false;
            if ("PROFIT".equals(actualResult)) {
                isCorrect = "BUY".equals(record.getPredictedSignal()) || "SELL".equals(record.getPredictedSignal());
            }
            record.setIsCorrect(isCorrect);
            
            predictionRecordMapper.updateById(record);
            
            log.info("更新预测结果：订单={}, 结果={}, 盈亏={}, 正确={}", 
                    orderNo, actualResult, profitLoss, isCorrect);
        }
    }
    
    /**
     * 获取今日统计
     */
    public String getTodayStatistics() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        
        // 查询今日所有预测
        LambdaQueryWrapper<MLPredictionRecord> allWrapper = new LambdaQueryWrapper<>();
        allWrapper.between(MLPredictionRecord::getPredictionTime, startOfDay, endOfDay);
        List<MLPredictionRecord> allRecords = predictionRecordMapper.selectList(allWrapper);
        
        // 查询今日交易记录
        LambdaQueryWrapper<MLPredictionRecord> tradeWrapper = new LambdaQueryWrapper<>();
        tradeWrapper.eq(MLPredictionRecord::getTradeTaken, true)
                   .between(MLPredictionRecord::getPredictionTime, startOfDay, endOfDay);
        List<MLPredictionRecord> tradedRecords = predictionRecordMapper.selectList(tradeWrapper);
        
        // 查询已确认结果的记录
        LambdaQueryWrapper<MLPredictionRecord> resultWrapper = new LambdaQueryWrapper<>();
        resultWrapper.eq(MLPredictionRecord::getTradeTaken, true)
                    .ne(MLPredictionRecord::getActualResult, "PENDING")
                    .between(MLPredictionRecord::getPredictionTime, startOfDay, endOfDay);
        List<MLPredictionRecord> completedRecords = predictionRecordMapper.selectList(resultWrapper);
        
        // 统计
        int totalPredictions = allRecords.size();
        int totalTrades = tradedRecords.size();
        int completedTrades = completedRecords.size();
        
        int correctPredictions = 0;
        int profitTrades = 0;
        BigDecimal totalProfitLoss = BigDecimal.ZERO;
        
        for (MLPredictionRecord record : completedRecords) {
            if (Boolean.TRUE.equals(record.getIsCorrect())) {
                correctPredictions++;
            }
            if ("PROFIT".equals(record.getActualResult())) {
                profitTrades++;
            }
            if (record.getProfitLoss() != null) {
                totalProfitLoss = totalProfitLoss.add(record.getProfitLoss());
            }
        }
        
        // 计算百分比
        double predictionAccuracy = completedTrades > 0 
                ? (correctPredictions * 100.0 / completedTrades) : 0.0;
        double winRate = completedTrades > 0 
                ? (profitTrades * 100.0 / completedTrades) : 0.0;
        BigDecimal avgProfitLoss = completedTrades > 0 
                ? totalProfitLoss.divide(BigDecimal.valueOf(completedTrades), 2, RoundingMode.HALF_UP) 
                : BigDecimal.ZERO;
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔════════════════════════════════════════╗\n");
        sb.append("║       📊 今日ML模型表现统计           ║\n");
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append(String.format("║ 总预测次数：%-25d║\n", totalPredictions));
        sb.append(String.format("║ 触发交易次数：%-23d║\n", totalTrades));
        sb.append(String.format("║ 已完成交易：%-25d║\n", completedTrades));
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append(String.format("║ ML预测准确率：%.1f%%%-22s║\n", predictionAccuracy, ""));
        sb.append(String.format("║ 实际交易胜率：%.1f%%%-22s║\n", winRate, ""));
        sb.append(String.format("║ 平均盈亏：$%-27s║\n", avgProfitLoss));
        sb.append(String.format("║ 总盈亏：$%-29s║\n", totalProfitLoss));
        sb.append("╚════════════════════════════════════════╝");
        
        return sb.toString();
    }
    
    /**
     * 获取最近N条预测记录
     */
    public List<MLPredictionRecord> getRecentPredictions(int limit) {
        LambdaQueryWrapper<MLPredictionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(MLPredictionRecord::getPredictionTime)
               .last("LIMIT " + limit);
        return predictionRecordMapper.selectList(wrapper);
    }
    
    /**
     * 获取预测准确率趋势（最近N笔交易）
     */
    public double getRecentAccuracy(int recentTrades) {
        LambdaQueryWrapper<MLPredictionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MLPredictionRecord::getTradeTaken, true)
               .ne(MLPredictionRecord::getActualResult, "PENDING")
               .orderByDesc(MLPredictionRecord::getPredictionTime)
               .last("LIMIT " + recentTrades);
        
        List<MLPredictionRecord> records = predictionRecordMapper.selectList(wrapper);
        
        if (records.isEmpty()) {
            return 0.0;
        }
        
        long correctCount = records.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsCorrect()))
                .count();
        
        return (correctCount * 100.0) / records.size();
    }
}
