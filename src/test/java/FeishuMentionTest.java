import com.ltp.peter.augtrade.notification.FeishuNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

/**
 * 飞书@提醒功能测试类
 * 
 * 用于测试开仓和平仓通知的@功能是否正常工作
 * 
 * @author Peter Wang
 */
@SpringBootTest(classes = FeishuNotificationService.class)
public class FeishuMentionTest {
    
    @Autowired
    private FeishuNotificationService feishuNotificationService;
    
    /**
     * 测试开仓通知（带@提醒）
     */
    @Test
    public void testOpenPositionNotificationWithMention() {
        System.out.println("=== 测试开仓通知（带@提醒） ===");
        
        feishuNotificationService.notifyOpenPosition(
            "TEST-OPEN-" + System.currentTimeMillis(),  // 持仓ID
            "XAUTUSDT",                                   // 交易品种
            "LONG",                                       // 方向
            new BigDecimal("2650.50"),                   // 开仓价
            new BigDecimal("10"),                        // 数量
            new BigDecimal("2635.50"),                   // 止损价
            new BigDecimal("2680.50"),                   // 止盈价
            "BalancedAggressiveStrategy (测试)"          // 策略
        );
        
        System.out.println("✅ 开仓通知已发送，请检查飞书群是否收到@提醒");
    }
    
    /**
     * 测试平仓通知（带@提醒） - 盈利场景
     */
    @Test
    public void testClosePositionNotificationWithMention_Profit() {
        System.out.println("=== 测试平仓通知（盈利场景，带@提醒） ===");
        
        feishuNotificationService.notifyClosePosition(
            "TEST-CLOSE-" + System.currentTimeMillis(), // 持仓ID
            "XAUTUSDT",                                   // 交易品种
            "LONG",                                       // 方向
            new BigDecimal("2650.50"),                   // 开仓价
            new BigDecimal("2680.50"),                   // 平仓价
            new BigDecimal("10"),                        // 数量
            new BigDecimal("300.00"),                    // 盈利$300
            1530L,                                        // 持仓时间25分30秒
            "止盈 (测试)"                                // 平仓原因
        );
        
        System.out.println("✅ 平仓通知（盈利）已发送，请检查飞书群是否收到@提醒");
    }
    
    /**
     * 测试平仓通知（带@提醒） - 亏损场景
     */
    @Test
    public void testClosePositionNotificationWithMention_Loss() {
        System.out.println("=== 测试平仓通知（亏损场景，带@提醒） ===");
        
        feishuNotificationService.notifyClosePosition(
            "TEST-CLOSE-LOSS-" + System.currentTimeMillis(), // 持仓ID
            "XAUTUSDT",                                        // 交易品种
            "SHORT",                                           // 方向
            new BigDecimal("2650.50"),                        // 开仓价
            new BigDecimal("2665.50"),                        // 平仓价
            new BigDecimal("10"),                             // 数量
            new BigDecimal("-150.00"),                        // 亏损$150
            600L,                                              // 持仓时间10分钟
            "止损 (测试)"                                     // 平仓原因
        );
        
        System.out.println("✅ 平仓通知（亏损）已发送，请检查飞书群是否收到@提醒");
    }
    
    /**
     * 测试所有通知场景
     */
    @Test
    public void testAllNotificationScenarios() throws InterruptedException {
        System.out.println("\n========================================");
        System.out.println("    飞书@提醒功能完整测试");
        System.out.println("========================================\n");
        
        // 1. 测试开仓通知（做多）
        System.out.println("📝 测试1: 开仓通知（做多）");
        feishuNotificationService.notifyOpenPosition(
            "TEST-ALL-LONG-" + System.currentTimeMillis(),
            "XAUTUSDT",
            "LONG",
            new BigDecimal("2650.50"),
            new BigDecimal("10"),
            new BigDecimal("2635.50"),
            new BigDecimal("2680.50"),
            "测试策略"
        );
        Thread.sleep(2000); // 等待2秒
        
        // 2. 测试开仓通知（做空）
        System.out.println("📝 测试2: 开仓通知（做空）");
        feishuNotificationService.notifyOpenPosition(
            "TEST-ALL-SHORT-" + System.currentTimeMillis(),
            "XAUTUSDT",
            "SHORT",
            new BigDecimal("2650.50"),
            new BigDecimal("10"),
            new BigDecimal("2665.50"),
            new BigDecimal("2620.50"),
            "测试策略"
        );
        Thread.sleep(2000);
        
        // 3. 测试平仓通知（盈利）
        System.out.println("📝 测试3: 平仓通知（盈利）");
        feishuNotificationService.notifyClosePosition(
            "TEST-ALL-PROFIT-" + System.currentTimeMillis(),
            "XAUTUSDT",
            "LONG",
            new BigDecimal("2650.50"),
            new BigDecimal("2680.50"),
            new BigDecimal("10"),
            new BigDecimal("300.00"),
            1530L,
            "止盈"
        );
        Thread.sleep(2000);
        
        // 4. 测试平仓通知（亏损）
        System.out.println("📝 测试4: 平仓通知（亏损）");
        feishuNotificationService.notifyClosePosition(
            "TEST-ALL-LOSS-" + System.currentTimeMillis(),
            "XAUTUSDT",
            "SHORT",
            new BigDecimal("2650.50"),
            new BigDecimal("2665.50"),
            new BigDecimal("10"),
            new BigDecimal("-150.00"),
            600L,
            "止损"
        );
        
        System.out.println("\n========================================");
        System.out.println("✅ 所有测试通知已发送完毕！");
        System.out.println("========================================");
        System.out.println("\n请检查飞书群：");
        System.out.println("1. 是否收到4条通知");
        System.out.println("2. 每条通知是否都有@提醒");
        System.out.println("3. 通知内容是否正确显示");
        System.out.println("4. 卡片颜色是否正确（绿色=盈利，红色=亏损/做多）");
    }
    
    /**
     * 单独测试基础通知功能（不带@）
     */
    @Test
    public void testBasicNotification() {
        System.out.println("=== 测试基础通知功能 ===");
        
        feishuNotificationService.testNotification();
        
        System.out.println("✅ 基础测试通知已发送");
        System.out.println("💡 注意：基础测试通知不会带@提醒");
    }
}
