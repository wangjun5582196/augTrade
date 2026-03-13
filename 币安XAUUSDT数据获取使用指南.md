# 币安XAUUSDT历史数据获取使用指南

## 概述

本系统提供了完整的币安XAUUSDT历史K线数据获取功能，支持从2020-08-13（XAUUSDT合约上线日期）至今的所有历史数据。

## 可用接口

### 1. 获取所有历史数据（推荐）

**接口地址：** `GET /historical/binance/xauusdt/all`

**功能：** 获取从2020-08-13至今的所有K线数据

**参数：**
- `interval`（可选）：K线周期，默认5m

**示例：**
```bash
# 获取5分钟K线（默认）
curl 'http://localhost:8080/historical/binance/xauusdt/all'

# 获取1小时K线
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1h'

# 获取日线
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1d'
```

**响应示例：**
```json
{
  "success": true,
  "symbol": "XAUUSDT",
  "interval": "5m",
  "startDate": "2020-08-13",
  "endDate": "2026-03-13",
  "count": 580000,
  "duration": "120秒",
  "message": "成功抓取从2020-08-13至今的580000条K线数据，耗时120秒"
}
```

### 2. 获取最近N天数据

**接口地址：** `GET /historical/binance/xauusdt/recent`

**参数：**
- `days`（可选）：天数，默认30
- `interval`（可选）：K线周期，默认5m

**示例：**
```bash
# 获取最近30天数据
curl 'http://localhost:8080/historical/binance/xauusdt/recent?days=30&interval=5m'

# 获取最近7天数据
curl 'http://localhost:8080/historical/binance/xauusdt/recent?days=7&interval=1h'
```

### 3. 获取一年数据

**接口地址：** `GET /historical/binance/xauusdt/one-year`

**参数：**
- `interval`（可选）：K线周期，默认5m

**示例：**
```bash
curl 'http://localhost:8080/historical/binance/xauusdt/one-year?interval=5m'
```

### 4. 自定义时间范围

**接口地址：** `GET /historical/binance/xauusdt/custom`

**参数：**
- `startDate`（必填）：开始日期，格式：yyyy-MM-dd
- `endDate`（必填）：结束日期，格式：yyyy-MM-dd
- `interval`（可选）：K线周期，默认5m

**示例：**
```bash
# 获取2025年全年数据
curl 'http://localhost:8080/historical/binance/xauusdt/custom?startDate=2025-01-01&endDate=2025-12-31&interval=1h'

# 获取指定月份数据
curl 'http://localhost:8080/historical/binance/xauusdt/custom?startDate=2026-01-01&endDate=2026-01-31&interval=5m'
```

## 支持的K线周期

| 周期 | 说明 | 适用场景 |
|------|------|----------|
| 1m   | 1分钟 | 超短线交易、高频策略 |
| 3m   | 3分钟 | 短线交易 |
| 5m   | 5分钟 | 短线交易（推荐） |
| 15m  | 15分钟 | 日内交易 |
| 30m  | 30分钟 | 日内交易 |
| 1h   | 1小时 | 波段交易 |
| 2h   | 2小时 | 波段交易 |
| 4h   | 4小时 | 中期交易 |
| 6h   | 6小时 | 中期交易 |
| 8h   | 8小时 | 中期交易 |
| 12h  | 12小时 | 中期交易 |
| 1d   | 日线 | 长期交易、趋势分析 |
| 3d   | 3日线 | 长期交易 |
| 1w   | 周线 | 长期投资 |
| 1M   | 月线 | 长期投资 |

## 使用便捷脚本

### 方式一：使用Shell脚本（推荐）

```bash
# 使用默认参数（5分钟K线）
./fetch_xauusdt_all_data.sh

# 指定K线周期
./fetch_xauusdt_all_data.sh 1h

# 指定K线周期和端口
./fetch_xauusdt_all_data.sh 1h 8080
```

### 方式二：直接使用curl

```bash
# 获取所有5分钟K线数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=5m'

# 获取所有1小时K线数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1h'

# 获取所有日线数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1d'
```

## 数据查询

获取数据后，可以使用以下SQL查询数据库：

```sql
-- 查看总数据量
SELECT COUNT(*) FROM klines 
WHERE symbol='XAUUSDT' AND `interval`='5m';

-- 查看时间范围
SELECT 
    MIN(timestamp) as start_time, 
    MAX(timestamp) as end_time,
    COUNT(*) as total_count
FROM klines 
WHERE symbol='XAUUSDT' AND `interval`='5m';

-- 查看最新10条数据
SELECT * FROM klines 
WHERE symbol='XAUUSDT' AND `interval`='5m' 
ORDER BY timestamp DESC 
LIMIT 10;

-- 按日期统计数据量
SELECT 
    DATE(timestamp) as date,
    COUNT(*) as count
FROM klines 
WHERE symbol='XAUUSDT' AND `interval`='5m'
GROUP BY DATE(timestamp)
ORDER BY date DESC
LIMIT 30;

-- 查看数据完整性（检查是否有缺失）
SELECT 
    DATE(timestamp) as date,
    COUNT(*) as count,
    CASE 
        WHEN COUNT(*) = 288 THEN '完整'  -- 5分钟K线，一天288条
        ELSE '不完整'
    END as status
FROM klines 
WHERE symbol='XAUUSDT' AND `interval`='5m'
GROUP BY DATE(timestamp)
ORDER BY date DESC;
```

## 注意事项

### 1. 数据量估算

不同K线周期的数据量差异很大：

- **1分钟K线**：约 280万条（2020-08-13至今）
- **5分钟K线**：约 58万条
- **1小时K线**：约 4.8万条
- **日线**：约 2000条

### 2. 获取时间

- 5分钟K线：约2-5分钟
- 1小时K线：约30-60秒
- 日线：约10秒

### 3. 币安API限制

- 每次最多返回1500条数据
- 请求频率限制：1200次/分钟
- 系统已自动处理分批获取和频率控制

### 4. 数据去重

系统会自动处理重复数据，重复的K线不会被插入数据库。

### 5. 数据存储

所有数据存储在MySQL数据库的`klines`表中，包含以下字段：
- symbol：交易对
- timestamp：时间戳
- open_price：开盘价
- high_price：最高价
- low_price：最低价
- close_price：收盘价
- volume：成交量
- amount：成交额
- interval：K线周期

## 常见问题

### Q1: 如何确认应用是否运行？

```bash
curl http://localhost:8080/actuator/health
```

如果返回`{"status":"UP"}`，说明应用正常运行。

### Q2: 获取数据失败怎么办？

1. 检查应用是否运行
2. 检查网络连接
3. 查看应用日志：`tail -f logs/application.log`
4. 确认币安API是否可访问

### Q3: 如何增量更新数据？

再次调用相同的接口即可，系统会自动跳过已存在的数据。

### Q4: 数据获取中断了怎么办？

重新运行相同的命令，系统会从中断的地方继续获取。

### Q5: 如何导出数据？

```bash
# 导出为CSV
mysql -u root -p -e "SELECT * FROM klines WHERE symbol='XAUUSDT' AND \`interval\`='5m' ORDER BY timestamp" augtrade > xauusdt_5m.csv

# 或使用现有脚本
./export_data.sh
```

## 技术实现

### 核心类

1. **BinanceHistoricalDataFetcher**：币安历史数据获取器
   - 位置：`src/main/java/com/ltp/peter/augtrade/market/BinanceHistoricalDataFetcher.java`
   - 功能：从币安API获取K线数据并存储到数据库

2. **HistoricalDataController**：历史数据控制器
   - 位置：`src/main/java/com/ltp/peter/augtrade/controller/HistoricalDataController.java`
   - 功能：提供RESTful API接口

### 数据流程

```
用户请求 → Controller → BinanceHistoricalDataFetcher → 币安API
                                    ↓
                              数据处理和去重
                                    ↓
                              KlineMapper → MySQL数据库
```

## 示例场景

### 场景1：首次获取所有数据

```bash
# 1. 启动应用
mvn spring-boot:run

# 2. 获取所有5分钟K线数据
./fetch_xauusdt_all_data.sh 5m

# 3. 验证数据
mysql -u root -p augtrade -e "SELECT COUNT(*) FROM klines WHERE symbol='XAUUSDT' AND \`interval\`='5m';"
```

### 场景2：获取多个周期的数据

```bash
# 获取5分钟数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=5m'

# 获取1小时数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1h'

# 获取日线数据
curl 'http://localhost:8080/historical/binance/xauusdt/all?interval=1d'
```

### 场景3：定期更新数据

```bash
# 每天定时获取最新数据（添加到crontab）
0 0 * * * cd /Users/peter/Documents/AugTrade && curl 'http://localhost:8080/historical/binance/xauusdt/recent?days=1&interval=5m'
```

## 总结

通过本系统，你可以轻松获取币安XAUUSDT从上线至今的所有历史K线数据，支持多种K线周期，适用于各种交易策略的回测和分析。
