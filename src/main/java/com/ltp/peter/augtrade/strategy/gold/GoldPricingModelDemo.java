package com.ltp.peter.augtrade.strategy.gold;

import java.time.LocalDate;
import java.util.*;

/**
 * 黄金多维度定价模型 - 使用示例
 *
 * 模拟博主文章中2026年2月的场景数据进行定价分析
 * 该示例展示了如何构建输入上下文并调用模型进行分析
 *
 * @author Peter Wang
 */
public class GoldPricingModelDemo {

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║    黄金多维度定价模型 - 场景模拟演示      ║");
        System.out.println("║    数据来源: 博主2026年2月分析文章        ║");
        System.out.println("╚═══════════════════════════════════════════╝\n");

        // 构建2026年2月场景的输入上下文
        GoldPricingContext context = buildFebruary2026Scenario();

        // 创建定价模型
        GoldMultiDimensionPricingModel model = new GoldMultiDimensionPricingModel();

        // 执行分析
        GoldPricingResult result = model.analyze(context);

        // 输出完整分析报告
        System.out.println(result.getAnalysisReport());

        // 输出关键结论
        printKeyConclusions(result);
    }

    /**
     * 构建2026年2月的场景数据
     * 数据来源于博主文章中的具体描述
     */
    private static GoldPricingContext buildFebruary2026Scenario() {

        // ---- 黄金期权Gamma敞口分布（模拟博主描述）----
        // 博主描述：4500-4700存在绝对支撑，尤其4700；上方5200为阻力
        Map<Double, Double> goldGamma = new LinkedHashMap<>();
        goldGamma.put(4400.0, 50.0);    // 支撑
        goldGamma.put(4500.0, 120.0);   // 较强支撑
        goldGamma.put(4600.0, 80.0);    // 支撑
        goldGamma.put(4700.0, 200.0);   // 最强支撑（博主强调）
        goldGamma.put(4800.0, 30.0);    // 弱支撑
        goldGamma.put(4900.0, -20.0);   // 轻微阻力
        goldGamma.put(5000.0, -50.0);   // 阻力
        goldGamma.put(5100.0, -80.0);   // 较强阻力
        goldGamma.put(5200.0, -150.0);  // 强阻力（博主描述的上方阻力）

        // ---- 白银期权Gamma敞口分布 ----
        // 博主描述：最大执行价在75美元（磁吸点），上方阻力80-85，下方支撑65
        Map<Double, Double> silverGamma = new LinkedHashMap<>();
        silverGamma.put(65.0, 180.0);   // 绝对支撑
        silverGamma.put(70.0, 100.0);   // 支撑
        silverGamma.put(75.0, 250.0);   // 最大磁吸点（博主强调）
        silverGamma.put(80.0, -100.0);  // 阻力
        silverGamma.put(85.0, -150.0);  // 较强阻力
        silverGamma.put(90.0, -80.0);   // 阻力

        // ---- 保证金历史数据（模拟）----
        // 博主描述：2026年2月白银保证金已达20%，为历史最高水平
        List<Double> historicalMarginRatios = Arrays.asList(
                5.0, 5.5, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5,  // 2005-2008
                10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0,  // 2008-2011
                8.0, 7.0, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 9.0,  // 2012-2020
                9.0, 9.5, 10.0, 11.0, 12.0, 13.0, 15.0, 18.0, 19.0   // 2020-2026.1
        );

        // ---- 历史最大回撤数据（模拟）----
        List<Double> historicalDrawdowns = Arrays.asList(
                2.0, 3.0, 2.5, 4.0, 3.5, 5.0, 6.0, 4.5, 3.0, 2.0,
                7.0, 5.5, 4.0, 3.0, 6.5, 8.0, 4.0, 3.5, 5.0, 6.0,
                10.0, 12.0, 5.0, 4.0, 3.0, 7.0, 6.5, 5.5, 4.0, 3.0
        );

        return GoldPricingContext.builder()
                // 基础信息
                .date(LocalDate.of(2026, 2, 7))
                .currentGoldPrice(4750.0)          // 博主描述周一跌至4400后回升
                .currentSilverPrice(76.0)           // 白银当前价格

                // 第一层：宏观流动性数据
                .globalLiquidity(180.0)             // 全球流动性约180万亿美元
                .previousGlobalLiquidity(165.0)     // 上期约165万亿
                .globalDebt(346.0)                  // 博主提到全球债务346万亿美元
                .globalGDP(105.0)                   // 全球GDP约105万亿美元
                .goldReserveShare(18.0)             // 黄金储备份额约18%
                .goldReserveMarketCap(40.0)         // 博主提到1月份黄金市值已远超美债38万亿
                .usTreasuryTotal(38.0)              // 美债总额38万亿

                // 第二层：微观期权/保证金数据
                .goldGammaExposure(goldGamma)
                .silverGammaExposure(silverGamma)
                .cmeGoldMarginRate(12.0)            // CME黄金保证金率
                .cmeSilverMarginRate(20.0)          // 博主明确提到白银保证金上调至20%
                .historicalMarginRatios(historicalMarginRatios)
                .callOptionVolume(15000)             // 当日成交量
                .callOptionVolume3dMA(18000)         // 3日MA已下降
                .callOptionVolumeRecentHigh(45000)   // 近期高点（博主说已大幅下降）
                .comexGoldDeliveryVolume(25000)      // 博主说交割量迅猛攀升
                .comexSilverDeliveryVolume(8000)
                .comexGoldDeliveryAvg(15000)          // 历史均值
                .comexSilverDeliveryAvg(6000)

                // 第三层：情绪与节奏数据
                .goldMarketStrength(22.0)            // 博主说市场强度遭遇极端值，正在回升
                .silverMarketStrength(12.0)          // 白银更极端，创2024年8月以来新低
                .goldTopBottomIndicator(0.15)        // 博主说2月2号创下自2024年12月24号以来新低
                .goldTopBottomRecentLow(0.14)        // 近期低点
                .retailShortIndex(12.0)              // 博主说处在2025年以来的绝对低位

                // ETF区域流向（博主数据）
                .etfFlowAsia(40.0)                   // 亚洲净流入40吨
                .etfFlowEurope(-6.0)                 // 欧洲净流出6吨
                .etfFlowNorthAmerica(10.0)           // 北美净流入10吨

                // 美元相关性
                .dxyIndex(103.5)                     // 美元指数
                .goldDxyCorrelation30d(-0.56)        // 博主提到30d相关性达-0.56
                .goldDxyCorrelation60d(-0.42)
                .goldDxyCorrelation90d(-0.35)

                // 回撤数据
                .goldPriceHistory(Arrays.asList(
                        4800.0, 4850.0, 4900.0, 4950.0, 5000.0,  // 近期高点
                        4900.0, 4700.0, 4400.0, 4600.0, 4750.0   // 暴跌后回升
                ))
                .goldRecentHigh(5000.0)
                .goldDrawdownPercent(12.0)            // 博主说回撤达2022年12月以来最大
                .historicalMaxDrawdowns(historicalDrawdowns)

                .build();
    }

    /**
     * 输出关键结论
     */
    private static void printKeyConclusions(GoldPricingResult result) {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("              关键结论汇总");
        System.out.println("═══════════════════════════════════════════\n");

        System.out.printf("🎯 综合评分: %.1f (方向: %s, 强度: %s)\n",
                result.getCompositeScore(),
                result.getDirection().getDescription(),
                result.getStrength().getDescription());
        System.out.printf("📊 置信度: %.1f%%\n\n", result.getConfidence());

        System.out.println("📈 定价区间:");
        System.out.printf("   年度目标: %.0f - %.0f 美元/盎司\n",
                result.getAnnualTargetLow(), result.getAnnualTargetHigh());
        System.out.printf("   月度区间: %.0f - %.0f 美元/盎司\n",
                result.getMonthlyRangeLow(), result.getMonthlyRangeHigh());
        System.out.printf("   周度区间: %.0f - %.0f 美元/盎司\n",
                result.getWeeklyRangeLow(), result.getWeeklyRangeHigh());
        System.out.printf("   Gamma磁吸点: %.0f | 绝对支撑: %.0f | 绝对阻力: %.0f\n\n",
                result.getGammaMagnetPrice(),
                result.getAbsoluteSupport(),
                result.getAbsoluteResistance());

        GoldPricingResult.TradingAdvice advice = result.getAdvice();
        System.out.println("💡 操作建议:");
        System.out.printf("   策略类型: %s\n", advice.getType().getDescription());
        System.out.printf("   操作说明: %s\n", advice.getDescription());
        if (advice.getEntryRangeLow() > 0) {
            System.out.printf("   入场区间: %.0f - %.0f\n",
                    advice.getEntryRangeLow(), advice.getEntryRangeHigh());
            System.out.printf("   止损: %.0f | 止盈: %.0f\n",
                    advice.getStopLoss(), advice.getTakeProfit());
        }
        System.out.printf("   建议仓位: %.0f%%\n", advice.getPositionSizePercent());
        System.out.printf("   风险等级: %d/5\n", advice.getRiskLevel());
        System.out.printf("   最低容错: %.0f 美元\n", advice.getMinToleranceRequired());

        if (advice.getScalingLevels() != null && !advice.getScalingLevels().isEmpty()) {
            System.out.print("   分批建仓价位: ");
            for (int i = 0; i < advice.getScalingLevels().size(); i++) {
                if (i > 0) System.out.print(", ");
                System.out.printf("%.0f", advice.getScalingLevels().get(i));
            }
            System.out.println();
        }

        // 极端信号汇总
        long extremeCount = result.getDimensionScores().stream()
                .filter(GoldPricingResult.DimensionScore::isExtremeTriggered)
                .count();
        if (extremeCount > 0) {
            System.out.println("\n⚡ 极端信号触发:");
            result.getDimensionScores().stream()
                    .filter(GoldPricingResult.DimensionScore::isExtremeTriggered)
                    .forEach(ds -> System.out.printf("   ⚡ [%s] 评分 %+.1f: %s\n",
                            ds.getDimensionName(), ds.getScore(), ds.getExplanation()));
        }

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("⚠️ 免责声明: 此模型仅供研究参考，不构成投资建议");
        System.out.println("═══════════════════════════════════════════");
    }
}
