import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * 金十数据黄金价格测试
 * 
 * 独立测试程序，不依赖Spring容器
 * 直接调用金十数据API验证功能
 */
public class Jin10PriceTest {
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("金十数据黄金价格测试");
        System.out.println("========================================\n");
        
        // 测试1：获取简单价格
        testSimplePrice();
        
        System.out.println();
        
        // 测试2：获取详细行情
        testDetailQuote();
        
        System.out.println("\n========================================");
        System.out.println("测试完成");
        System.out.println("========================================");
    }
    
    /**
     * 测试获取简单价格
     */
    private static void testSimplePrice() {
        System.out.println("【测试1】获取金十黄金价格");
        System.out.println("----------------------------------");
        
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            
            String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.jin10.com/")
                    .addHeader("Accept", "application/json")
                    .build();
            
            System.out.println("请求URL: " + url);
            System.out.println("正在连接金十数据...");
            
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    System.out.println("\n✅ 连接成功！");
                    System.out.println("原始响应: " + jsonData.substring(0, Math.min(200, jsonData.length())) + "...");
                    
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    if (jsonObject.has("code") && jsonObject.get("code").getAsInt() == 0) {
                        JsonObject data = jsonObject.getAsJsonObject("data");
                        
                        // 获取价格
                        String priceStr = null;
                        if (data.has("last_price")) {
                            priceStr = data.get("last_price").getAsString();
                        } else if (data.has("bid") && data.has("ask")) {
                            BigDecimal bid = new BigDecimal(data.get("bid").getAsString());
                            BigDecimal ask = new BigDecimal(data.get("ask").getAsString());
                            BigDecimal avgPrice = bid.add(ask).divide(new BigDecimal("2"), 2, BigDecimal.ROUND_HALF_UP);
                            priceStr = avgPrice.toString();
                        }
                        
                        if (priceStr != null) {
                            System.out.println("\n🎯 测试结果：成功");
                            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                            System.out.println("💰 黄金价格: $" + priceStr + " 美元/盎司");
                            System.out.println("📊 数据源: 金十数据");
                            System.out.println("🔗 品种代码: XAUUSD (现货黄金)");
                            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        } else {
                            System.out.println("\n⚠️ 警告：响应中未找到价格字段");
                        }
                    } else {
                        System.out.println("\n❌ 错误：金十数据返回错误状态");
                        System.out.println("响应内容: " + jsonData);
                    }
                } else {
                    System.out.println("\n❌ 连接失败！");
                    System.out.println("HTTP状态码: " + response.code());
                    System.out.println("响应消息: " + response.message());
                }
            }
            
        } catch (Exception e) {
            System.out.println("\n❌ 测试失败！");
            System.out.println("错误信息: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 测试获取详细行情
     */
    private static void testDetailQuote() {
        System.out.println("【测试2】获取金十黄金详细行情");
        System.out.println("----------------------------------");
        
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            
            String url = "https://flash-api.jin10.com/get_quote?symbol=XAUUSD";
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.jin10.com/")
                    .addHeader("Accept", "application/json")
                    .build();
            
            System.out.println("正在获取详细行情...");
            
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonData = response.body().string();
                    JsonObject jsonObject = JsonParser.parseString(jsonData).getAsJsonObject();
                    
                    if (jsonObject.has("code") && jsonObject.get("code").getAsInt() == 0) {
                        JsonObject data = jsonObject.getAsJsonObject("data");
                        
                        System.out.println("\n🎯 测试结果：成功");
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        System.out.println("📊 详细行情数据:");
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        
                        if (data.has("last_price")) {
                            System.out.println("当前价: $" + data.get("last_price").getAsString());
                        }
                        if (data.has("bid")) {
                            System.out.println("买入价: $" + data.get("bid").getAsString());
                        }
                        if (data.has("ask")) {
                            System.out.println("卖出价: $" + data.get("ask").getAsString());
                        }
                        if (data.has("change")) {
                            System.out.println("涨跌额: $" + data.get("change").getAsString());
                        }
                        if (data.has("change_percent")) {
                            System.out.println("涨跌幅: " + data.get("change_percent").getAsString() + "%");
                        }
                        if (data.has("high")) {
                            System.out.println("最高价: $" + data.get("high").getAsString());
                        }
                        if (data.has("low")) {
                            System.out.println("最低价: $" + data.get("low").getAsString());
                        }
                        if (data.has("open")) {
                            System.out.println("开盘价: $" + data.get("open").getAsString());
                        }
                        
                        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("\n❌ 测试失败！");
            System.out.println("错误信息: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
