package com.ltp.peter.augtrade.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltp.peter.augtrade.entity.Kline;
import com.ltp.peter.augtrade.indicator.BollingerBands;
import com.ltp.peter.augtrade.indicator.IndicatorService;
import com.ltp.peter.augtrade.indicator.MACDResult;
import com.ltp.peter.augtrade.strategy.core.MarketContext;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ML预测增强服务
 * 调用Python LightGBM模型API进行预测
 * 
 * 核心价值：震荡过滤器（精确率94%）
 * 
 * @author Peter Wang
 * @date 2026-01-27
 */
@Slf4j
@Service
public class MLPredictionEnhancedService {
    
    private static final String ML_API_URL = "http://localhost:5001/predict";
    private static final String ML_HEALTH_URL = "http://localhost:5001/health";
    
    @Autowired
    private IndicatorService indicatorService;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 服务可用性标记
    private boolean serviceAvailable = true;
    private long lastCheckTime = 0;
    private static final long CHECK_INTERVAL = 60000; // 1分钟检查一次
    
    /**
     * 获取ML预测
     * 
     * @param context 市场上下文
     * @return ML预测结果，如果服务不可用返回null
     */
    public MLPrediction predict(MarketContext context) {
        // 检查服务可用性
        if (!checkServiceHealth()) {
            log.warn("[ML] 预测服务不可用，跳过ML预测");
            return null;
        }
        
        try {
            // 1. 准备特征数据
            Map<String, Object> features = extractFeatures(context);
            
            if (features.isEmpty()) {
                log.warn("[ML] 特征提取失败，数据不足");
                return null;
            }
            
            // 2. 调用Python API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(features, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(
                    ML_API_URL, request, String.class);
            
            // 3. 解析响应
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            
            if (!jsonNode.get("success").asBoolean()) {
                log.error("[ML] 预测API返回失败: {}", jsonNode.get("error").asText());
                return null;
            }
            
            JsonNode pred = jsonNode.get("prediction");
            
            MLPrediction result = MLPrediction.builder()
                    .label(pred.get("label").asInt())
                    .labelName(pred.get("label_name").asText())
                    .probUp(pred.get("probability").get("up").asDouble())
                    .probHold(pred.get("probability").get("hold").asDouble())
                    .probDown(pred.get("probability").get("down").asDouble())
                    .confidence(pred.get("confidence").asDouble())
                    .build();
            
            log.info("[ML] 预测完成: {} (置信度:{}%, 概率-上:{}% 荡:{}% 跌:{}%)", 
                    result.getLabelName(), 
                    String.format("%.2f", result.getConfidence() * 100),
                    String.format("%.1f", result.getProbUp() * 100),
                    String.format("%.1f", result.getProbHold() * 100),
                    String.format("%.1f", result.getProbDown() * 100));
            
            return result;
            
        } catch (Exception e) {
            log.error("[ML] 预测请求失败", e);
            serviceAvailable = false; // 标记服务不可用
            return null;
        }
    }
    
    /**
     * 检查服务健康状态
     */
    private boolean checkServiceHealth() {
        long now = System.currentTimeMillis();
        
        // 避免频繁检查
        if (now - lastCheckTime < CHECK_INTERVAL && serviceAvailable) {
            return true;
        }
        
        lastCheckTime = now;
        
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    ML_HEALTH_URL, String.class);
            
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            serviceAvailable = "ok".equals(jsonNode.get("status").asText());
            
            if (serviceAvailable) {
                log.debug("[ML] 服务健康检查通过");
            } else {
                log.warn("[ML] 服务状态异常");
            }
            
            return serviceAvailable;
            
        } catch (Exception e) {
            log.error("[ML] 健康检查失败，服务可能未启动: {}", e.getMessage());
            serviceAvailable = false;
            return false;
        }
    }
    
    /**
     * 从MarketContext提取ML模型所需的32个特征
     */
    private Map<String, Object> extractFeatures(MarketContext context) {
        Map<String, Object> features = new HashMap<>();
        
        try {
            List<Kline> klines = context.getKlines();
            if (klines == null || klines.size() < 100) {
                log.warn("[ML] K线数据不足（需要至少100条）");
                return features;
            }
            
            Kline current = klines.get(0);
            BigDecimal closePrice = current.getClosePrice();
            BigDecimal openPrice = current.getOpenPrice();
            BigDecimal highPrice = current.getHighPrice();
            BigDecimal lowPrice = current.getLowPrice();
            
            // 时间特征
            LocalDateTime timestamp = current.getTimestamp();
            features.put("hour", timestamp.getHour());
            features.put("day_of_week", timestamp.getDayOfWeek().getValue() - 1); // 0-6
            features.put("is_trading_hour", (timestamp.getHour() >= 9 && timestamp.getHour() <= 16) ? 1 : 0);
            
            // 价格特征
            features.put("returns", calculateReturns(klines, 1));
            features.put("returns_5", calculateReturns(klines, 5));
            features.put("returns_10", calculateReturns(klines, 10));
            
            features.put("volatility_10", calculateVolatility(klines, 10));
            features.put("volatility_20", calculateVolatility(klines, 20));
            
            features.put("momentum_5", calculateMomentum(klines, 5));
            features.put("momentum_10", calculateMomentum(klines, 10));
            
            BigDecimal range = highPrice.subtract(lowPrice);
            features.put("amplitude", range.divide(openPrice, 6, RoundingMode.HALF_UP).doubleValue());
            features.put("body_size", closePrice.subtract(openPrice).abs()
                    .divide(openPrice, 6, RoundingMode.HALF_UP).doubleValue());
            
            BigDecimal upperShadow = highPrice.subtract(closePrice.max(openPrice));
            BigDecimal lowerShadow = closePrice.min(openPrice).subtract(lowPrice);
            features.put("upper_shadow", upperShadow.divide(openPrice, 6, RoundingMode.HALF_UP).doubleValue());
            features.put("lower_shadow", lowerShadow.divide(openPrice, 6, RoundingMode.HALF_UP).doubleValue());
            
            // 技术指标
            Double rsi = context.getIndicator("RSI");
            features.put("rsi_14", rsi != null ? rsi : 50.0);
            features.put("rsi_7", calculateRSI(klines, 7));
            
            MACDResult macd = context.getIndicator("MACD");
            if (macd != null && macd.getMacdLine() != null && macd.getSignalLine() != null) {
                features.put("macd", macd.getMacdLine());
                features.put("macd_signal", macd.getSignalLine());
                features.put("macd_diff", macd.getHistogram() != null ? macd.getHistogram() : 0.0);
            } else {
                features.put("macd", 0.0);
                features.put("macd_signal", 0.0);
                features.put("macd_diff", 0.0);
            }
            
            Double adx = context.getIndicator("ADX");
            features.put("adx", adx != null ? adx : 25.0);
            features.put("adx_pos", adx != null ? adx * 0.6 : 15.0); // 近似值
            features.put("adx_neg", adx != null ? adx * 0.4 : 10.0); // 近似值
            
            Double williamsR = context.getIndicator("WilliamsR");
            features.put("williams_r", williamsR != null ? williamsR : -50.0);
            
            BollingerBands bb = context.getIndicator("BollingerBands");
            if (bb != null) {
                double width = (bb.getUpper() - bb.getLower()) / bb.getMiddle();
                double position = (closePrice.doubleValue() - bb.getLower()) / 
                                 (bb.getUpper() - bb.getLower());
                features.put("bb_width", width);
                features.put("bb_position", position);
            } else {
                features.put("bb_width", 0.02);
                features.put("bb_position", 0.5);
            }
            
            Double atr = context.getIndicator("ATR");
            features.put("atr", atr != null ? atr : 2.0);
            
            // EMA差值
            BigDecimal ema20 = indicatorService.calculateEMA(klines, 20);
            BigDecimal ema50 = indicatorService.calculateEMA(klines, 50);
            features.put("ema_diff", ema20.subtract(ema50).doubleValue());
            
            // 成交量比率
            BigDecimal volumeMA10 = calculateVolumeMA(klines, 10);
            double volumeRatio = current.getVolume()
                    .divide(volumeMA10.add(BigDecimal.ONE), 6, RoundingMode.HALF_UP)
                    .doubleValue();
            features.put("volume_ratio", volumeRatio);
            
            // K线形态特征
            features.put("is_green", closePrice.compareTo(openPrice) > 0 ? 1 : 0);
            features.put("consecutive_green", calculateConsecutiveGreen(klines, 3));
            features.put("is_doji", checkDoji(current) ? 1 : 0);
            features.put("is_hammer", checkHammer(current) ? 1 : 0);
            
            log.debug("[ML] 特征提取完成，共{}个特征", features.size());
            
            return features;
            
        } catch (Exception e) {
            log.error("[ML] 特征提取失败", e);
            return new HashMap<>();
        }
    }
    
    // ========== 辅助计算方法 ==========
    
    private double calculateReturns(List<Kline> klines, int period) {
        if (klines.size() <= period) return 0.0;
        BigDecimal current = klines.get(0).getClosePrice();
        BigDecimal previous = klines.get(period).getClosePrice();
        return current.subtract(previous)
                .divide(previous, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }
    
    private double calculateVolatility(List<Kline> klines, int period) {
        if (klines.size() < period) return 0.0;
        
        double sum = 0.0;
        double sumSq = 0.0;
        
        for (int i = 0; i < period - 1 && i < klines.size() - 1; i++) {
            double ret = calculateReturns(klines.subList(i, klines.size()), 1);
            sum += ret;
            sumSq += ret * ret;
        }
        
        double mean = sum / (period - 1);
        double variance = (sumSq / (period - 1)) - (mean * mean);
        return Math.sqrt(Math.max(0, variance));
    }
    
    private double calculateMomentum(List<Kline> klines, int period) {
        if (klines.size() <= period) return 0.0;
        BigDecimal current = klines.get(0).getClosePrice();
        BigDecimal previous = klines.get(period).getClosePrice();
        return current.subtract(previous).doubleValue();
    }
    
    private double calculateRSI(List<Kline> klines, int period) {
        try {
            BigDecimal rsi = indicatorService.calculateRSI(klines, period);
            return rsi.doubleValue();
        } catch (Exception e) {
            return 50.0;
        }
    }
    
    private BigDecimal calculateVolumeMA(List<Kline> klines, int period) {
        if (klines.size() < period) return BigDecimal.ONE;
        
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(klines.get(i).getVolume());
        }
        return sum.divide(new BigDecimal(period), 6, RoundingMode.HALF_UP);
    }
    
    private int calculateConsecutiveGreen(List<Kline> klines, int lookback) {
        int count = 0;
        for (int i = 0; i < Math.min(lookback, klines.size()); i++) {
            Kline k = klines.get(i);
            if (k.getClosePrice().compareTo(k.getOpenPrice()) > 0) {
                count++;
            }
        }
        return count;
    }
    
    private boolean checkDoji(Kline kline) {
        BigDecimal body = kline.getClosePrice().subtract(kline.getOpenPrice()).abs();
        BigDecimal range = kline.getHighPrice().subtract(kline.getLowPrice());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double ratio = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        return ratio < 0.1;
    }
    
    private boolean checkHammer(Kline kline) {
        BigDecimal body = kline.getClosePrice().subtract(kline.getOpenPrice()).abs();
        BigDecimal lowerShadow = kline.getOpenPrice().min(kline.getClosePrice())
                .subtract(kline.getLowPrice());
        BigDecimal upperShadow = kline.getHighPrice()
                .subtract(kline.getOpenPrice().max(kline.getClosePrice()));
        
        return lowerShadow.compareTo(body.multiply(new BigDecimal("2"))) > 0 &&
               upperShadow.compareTo(body) < 0;
    }
}
