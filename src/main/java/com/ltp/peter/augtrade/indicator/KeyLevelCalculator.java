package com.ltp.peter.augtrade.indicator;

import com.ltp.peter.augtrade.entity.Kline;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 关键价格结构计算器
 *
 * 计算多层次支撑/阻力位，是新策略框架的 Layer 1（价格结构过滤器）。
 *
 * 支撑阻力来源（按强度排序）：
 *   Level STRONG：前日最高/最低价（PDH/PDL）、当日关键级别
 *   Level MEDIUM：多小时摆动高低点（12根K线窗口 = 1小时）、整数关口（每$25）
 *   Level WEAK：当日最新高低
 *
 * 入场判断：
 *   价格在支撑位 0.35% 以内 → isAtSupport = true（允许 Layer 2/3 继续判断）
 *   价格在阻力位 0.35% 以内 → isAtResistance = true
 *
 * @author Peter Wang
 */
@Slf4j
@Component
public class KeyLevelCalculator {

    /** 判定"在关键位附近"的价格距离阈值（百分比）*/
    private static final double AT_LEVEL_THRESHOLD_PCT = 0.45;

    /** 多小时摆动点的左右各看 N 根 K 线（8根 = 40分钟窗口）*/
    private static final int SWING_LOOKBACK = 8;

    /** 最多向前检索多少根 K 线来找摆动点 */
    private static final int SWING_SEARCH_LIMIT = 200;

    /** 整数关口的步长（黄金每 $25 一个档位）*/
    private static final double ROUND_NUMBER_STEP = 25.0;

    /** 整数关口搜索半径（当前价格上下各 $150）*/
    private static final double ROUND_NUMBER_RADIUS = 150.0;

    public KeyLevelResult calculate(List<Kline> klines) {
        if (klines == null || klines.size() < SWING_LOOKBACK * 2 + 1) {
            log.warn("[KeyLevel] K线不足，无法计算关键位");
            return null;
        }

        double currentPrice = klines.get(0).getClosePrice().doubleValue();
        List<KeyLevel> allLevels = new ArrayList<>();

        // 1. 前日最高/最低价（PDH / PDL）
        addPreviousDayLevels(klines, allLevels);

        // 2. 整数关口（每$25）
        addRoundNumberLevels(currentPrice, allLevels);

        // 3. 多小时摆动高低点（12根窗口）
        addSwingLevels(klines, allLevels);

        // 去重合并：距离 < 0.2% 的相邻位合并取中间值，强度取最高
        allLevels = mergeLevels(allLevels, currentPrice);

        // 找最近支撑（当前价格下方，且类型为SUPPORT）
        // 严格按 LevelType 过滤：SwingLow=SUPPORT，SwingHigh=RESISTANCE
        // 不允许 SwingLow 出现在阻力位（牛市中会制造大量假做空信号）
        KeyLevel nearestSupport = allLevels.stream()
                .filter(l -> l.getPrice() < currentPrice)
                .filter(l -> l.getType() == LevelType.SUPPORT)
                .max(Comparator.comparingDouble(KeyLevel::getPrice))
                .orElse(null);

        KeyLevel nearestResistance = allLevels.stream()
                .filter(l -> l.getPrice() > currentPrice)
                .filter(l -> l.getType() == LevelType.RESISTANCE)
                .min(Comparator.comparingDouble(KeyLevel::getPrice))
                .orElse(null);

        double distToSupportPct = nearestSupport != null
                ? (currentPrice - nearestSupport.getPrice()) / currentPrice * 100 : 999;
        double distToResistancePct = nearestResistance != null
                ? (nearestResistance.getPrice() - currentPrice) / currentPrice * 100 : 999;

        boolean isAtSupport = distToSupportPct <= AT_LEVEL_THRESHOLD_PCT;
        boolean isAtResistance = distToResistancePct <= AT_LEVEL_THRESHOLD_PCT;

        // 找第二近支撑/阻力（用于 TP 计算），同样严格按 LevelType 过滤
        KeyLevel secondSupport = allLevels.stream()
                .filter(l -> nearestSupport == null || l.getPrice() < nearestSupport.getPrice() - 0.5)
                .filter(l -> l.getPrice() < currentPrice)
                .filter(l -> l.getType() == LevelType.SUPPORT)
                .max(Comparator.comparingDouble(KeyLevel::getPrice))
                .orElse(null);

        KeyLevel secondResistance = allLevels.stream()
                .filter(l -> nearestResistance == null || l.getPrice() > nearestResistance.getPrice() + 0.5)
                .filter(l -> l.getPrice() > currentPrice)
                .filter(l -> l.getType() == LevelType.RESISTANCE)
                .min(Comparator.comparingDouble(KeyLevel::getPrice))
                .orElse(null);

        log.info("[KeyLevel] 当前价:{} | 最近支撑:{} ({}%) {} | 最近阻力:{} ({}%) {}",
                String.format("%.2f", currentPrice),
                nearestSupport != null ? String.format("%.2f", nearestSupport.getPrice()) : "无",
                String.format("%.3f", distToSupportPct),
                isAtSupport ? "✅在支撑区" : "",
                nearestResistance != null ? String.format("%.2f", nearestResistance.getPrice()) : "无",
                String.format("%.3f", distToResistancePct),
                isAtResistance ? "✅在阻力区" : "");

        return KeyLevelResult.builder()
                .allLevels(allLevels)
                .nearestSupport(nearestSupport)
                .nearestResistance(nearestResistance)
                .secondSupport(secondSupport)
                .secondResistance(secondResistance)
                .isAtSupport(isAtSupport)
                .isAtResistance(isAtResistance)
                .distanceToSupportPercent(distToSupportPct)
                .distanceToResistancePercent(distToResistancePct)
                .currentPrice(currentPrice)
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // 1. 前日最高/最低价
    // ─────────────────────────────────────────────────────────

    private void addPreviousDayLevels(List<Kline> klines, List<KeyLevel> levels) {
        if (klines.get(0).getTimestamp() == null) return;

        LocalDate today = klines.get(0).getTimestamp().toLocalDate();

        // 按日期分组
        Map<LocalDate, List<Kline>> byDay = klines.stream()
                .filter(k -> k.getTimestamp() != null)
                .collect(Collectors.groupingBy(k -> k.getTimestamp().toLocalDate()));

        // 找前一个交易日
        LocalDate prevDay = byDay.keySet().stream()
                .filter(d -> d.isBefore(today))
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (prevDay == null) {
            log.debug("[KeyLevel] 无前日数据，跳过PDH/PDL计算");
            return;
        }

        List<Kline> prevDayKlines = byDay.get(prevDay);
        if (prevDayKlines == null || prevDayKlines.size() < 20) {
            log.debug("[KeyLevel] 前日K线不足20根（{}根），跳过PDH/PDL", prevDayKlines == null ? 0 : prevDayKlines.size());
            return;
        }

        double pdh = prevDayKlines.stream()
                .mapToDouble(k -> k.getHighPrice().doubleValue()).max().orElse(0);
        double pdl = prevDayKlines.stream()
                .mapToDouble(k -> k.getLowPrice().doubleValue()).min().orElse(0);

        if (pdh > 0) levels.add(KeyLevel.builder().price(pdh).type(LevelType.RESISTANCE)
                .strength(LevelStrength.STRONG).source("PDH").build());
        if (pdl > 0) levels.add(KeyLevel.builder().price(pdl).type(LevelType.SUPPORT)
                .strength(LevelStrength.STRONG).source("PDL").build());

        // 当日最高/最低
        List<Kline> todayKlines = byDay.getOrDefault(today, Collections.emptyList());
        if (todayKlines.size() >= 10) {
            double todayHigh = todayKlines.stream()
                    .mapToDouble(k -> k.getHighPrice().doubleValue()).max().orElse(0);
            double todayLow = todayKlines.stream()
                    .mapToDouble(k -> k.getLowPrice().doubleValue()).min().orElse(0);

            if (todayHigh > 0) levels.add(KeyLevel.builder().price(todayHigh).type(LevelType.RESISTANCE)
                    .strength(LevelStrength.MEDIUM).source("TodayHigh").build());
            if (todayLow > 0) levels.add(KeyLevel.builder().price(todayLow).type(LevelType.SUPPORT)
                    .strength(LevelStrength.MEDIUM).source("TodayLow").build());

            log.info("[KeyLevel] PDH={} PDL={} | 今日高={} 今日低={}",
                    String.format("%.2f", pdh), String.format("%.2f", pdl),
                    String.format("%.2f", todayHigh), String.format("%.2f", todayLow));
        }
    }

    // ─────────────────────────────────────────────────────────
    // 2. 整数关口（每$25）
    // ─────────────────────────────────────────────────────────

    private void addRoundNumberLevels(double currentPrice, List<KeyLevel> levels) {
        double base = Math.floor(currentPrice / ROUND_NUMBER_STEP) * ROUND_NUMBER_STEP;
        double start = base - ROUND_NUMBER_RADIUS;
        double end = base + ROUND_NUMBER_RADIUS;

        for (double level = start; level <= end; level += ROUND_NUMBER_STEP) {
            double roundedLevel = Math.round(level / ROUND_NUMBER_STEP) * ROUND_NUMBER_STEP;
            LevelStrength strength = (roundedLevel % 50 == 0) ? LevelStrength.STRONG : LevelStrength.MEDIUM;
            String source = roundedLevel % 50 == 0 ? "Round50" : "Round25";
            // 整数关口同时注册为支撑和阻力（双向有效），避免仅按当前价格分类导致合并后类型错误
            levels.add(KeyLevel.builder().price(roundedLevel).type(LevelType.SUPPORT)
                    .strength(strength).source(source).build());
            levels.add(KeyLevel.builder().price(roundedLevel).type(LevelType.RESISTANCE)
                    .strength(strength).source(source).build());
        }
    }

    // ─────────────────────────────────────────────────────────
    // 3. 多小时摆动高低点（12根 K 线窗口 = 约 1 小时）
    // ─────────────────────────────────────────────────────────

    private void addSwingLevels(List<Kline> klines, List<KeyLevel> levels) {
        int limit = Math.min(SWING_SEARCH_LIMIT, klines.size() - SWING_LOOKBACK);

        for (int i = SWING_LOOKBACK; i < limit; i++) {
            Kline cur = klines.get(i);
            boolean isSwingHigh = true;
            boolean isSwingLow = true;

            for (int j = 1; j <= SWING_LOOKBACK; j++) {
                if (i - j < 0 || i + j >= klines.size()) {
                    isSwingHigh = isSwingLow = false;
                    break;
                }
                if (cur.getHighPrice().compareTo(klines.get(i - j).getHighPrice()) <= 0 ||
                    cur.getHighPrice().compareTo(klines.get(i + j).getHighPrice()) <= 0) {
                    isSwingHigh = false;
                }
                if (cur.getLowPrice().compareTo(klines.get(i - j).getLowPrice()) >= 0 ||
                    cur.getLowPrice().compareTo(klines.get(i + j).getLowPrice()) >= 0) {
                    isSwingLow = false;
                }
            }

            if (isSwingHigh) {
                levels.add(KeyLevel.builder()
                        .price(cur.getHighPrice().doubleValue())
                        .type(LevelType.RESISTANCE)
                        .strength(LevelStrength.MEDIUM)
                        .source("SwingH@" + i)
                        .build());
            }
            if (isSwingLow) {
                levels.add(KeyLevel.builder()
                        .price(cur.getLowPrice().doubleValue())
                        .type(LevelType.SUPPORT)
                        .strength(LevelStrength.MEDIUM)
                        .source("SwingL@" + i)
                        .build());
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 合并相近价位（距离 < 0.2%）
    // ─────────────────────────────────────────────────────────

    private List<KeyLevel> mergeLevels(List<KeyLevel> levels, double currentPrice) {
        if (levels.isEmpty()) return levels;

        levels.sort(Comparator.comparingDouble(KeyLevel::getPrice)
                .thenComparing(l -> l.getType().ordinal()));

        List<KeyLevel> merged = new ArrayList<>();
        KeyLevel current = levels.get(0);

        for (int i = 1; i < levels.size(); i++) {
            KeyLevel next = levels.get(i);
            double distPct = (next.getPrice() - current.getPrice()) / currentPrice * 100;

            // 只合并相同类型的相邻位，禁止将 RESISTANCE 合并进 SUPPORT（反之亦然）
            if (distPct < 0.2 && current.getType() == next.getType()) {
                // 合并：取中间价，强度取高
                double midPrice = (current.getPrice() + next.getPrice()) / 2;
                LevelStrength stronger = current.getStrength().ordinal() <= next.getStrength().ordinal()
                        ? current.getStrength() : next.getStrength();
                String mergedSource = current.getSource() + "+" + next.getSource();
                current = KeyLevel.builder()
                        .price(midPrice)
                        .type(current.getType())
                        .strength(stronger)
                        .source(mergedSource)
                        .build();
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    // ─────────────────────────────────────────────────────────
    // 数据模型
    // ─────────────────────────────────────────────────────────

    public enum LevelType { SUPPORT, RESISTANCE }

    public enum LevelStrength {
        STRONG,  // PDH/PDL、$50整数关口
        MEDIUM,  // 摆动点、$25整数关口、当日高低
        WEAK     // 预留
    }

    @Data
    @Builder
    public static class KeyLevel {
        private double price;
        private LevelType type;
        private LevelStrength strength;
        private String source;
    }

    @Data
    @Builder
    public static class KeyLevelResult {
        /** 所有检测到的关键位（合并去重后） */
        private List<KeyLevel> allLevels;
        /** 当前价格下方最近支撑 */
        private KeyLevel nearestSupport;
        /** 当前价格上方最近阻力 */
        private KeyLevel nearestResistance;
        /** 第二近支撑（用于做空 TP 目标） */
        private KeyLevel secondSupport;
        /** 第二近阻力（用于做多 TP 目标） */
        private KeyLevel secondResistance;
        /** 价格是否在支撑区附近（阈值 0.35%） */
        private boolean isAtSupport;
        /** 价格是否在阻力区附近（阈值 0.35%） */
        private boolean isAtResistance;
        /** 距最近支撑的百分比距离 */
        private double distanceToSupportPercent;
        /** 距最近阻力的百分比距离 */
        private double distanceToResistancePercent;
        /** 当前价格 */
        private double currentPrice;
    }
}