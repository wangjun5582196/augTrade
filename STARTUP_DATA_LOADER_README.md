# 启动时自动加载K线数据功能说明

## 功能概述

每次重启应用后，系统会自动从Bybit获取当天的历史K线数据，并在数据加载完成后才开始执行交易策略，确保策略有足够的历史数据进行技术指标计算。

## 核心特性

### 1. 自动数据加载
- ✅ 应用启动时自动触发数据加载
- ✅ 从Bybit获取最近200条5分钟K线（约16.7小时数据）
- ✅ 自动去重，避免保存重复数据
- ✅ 实时显示加载进度和最新价格

### 2. 交易策略保护
- ✅ 所有交易策略在数据加载完成前暂停执行
- ✅ 数据采集任务等待启动数据加载完成
- ✅ 持仓监控等待数据加载完成
- ✅ 超时保护（60秒），避免无限等待

### 3. 智能容错处理
- ✅ 如果Bybit未启用，自动跳过数据加载
- ✅ 加载失败不影响系统启动
- ✅ 支持通过配置禁用启动加载功能

## 配置说明

### application.yml 配置

```yaml
trading:
  # 启动时数据加载配置
  startup:
    load-klines: true         # 是否启用启动时加载K线数据（默认：true）
    klines-count: 200         # 加载K线数量（默认：200条，建议100-300）
```

### 配置参数说明

| 参数 | 说明 | 默认值 | 建议值 |
|------|------|--------|--------|
| `load-klines` | 是否启用启动加载 | `true` | `true` |
| `klines-count` | 加载K线数量 | `200` | 100-300 |

**K线数量选择建议：**
- **100条**: 约8.3小时数据，适合短期策略
- **200条**: 约16.7小时数据，推荐（默认）
- **300条**: 约25小时数据，适合需要更多历史数据的策略

## 工作流程

```
应用启动
    ↓
检查配置（是否启用）
    ↓
检查Bybit状态
    ↓
从Bybit获取K线数据
    ↓
保存到数据库（自动去重）
    ↓
显示加载统计
    ↓
设置数据加载完成标志
    ↓
交易策略开始执行
```

## 启动日志示例

### 成功启动日志

```
========================================
🚀 开始加载启动数据...
========================================
📊 开始获取XAUTUSDT的历史K线数据（最近200条，5分钟周期）
📥 成功获取200条K线数据，开始保存到数据库...
进度: 10/200 - 最新价格: $2765.50
进度: 20/200 - 最新价格: $2766.20
...
进度: 200/200 - 最新价格: $2770.80
✅ K线数据保存完成！
   - 成功保存: 195 条
   - 跳过重复: 5 条
   - 总计处理: 200 条
💰 当前XAUTUSDT价格: $2770.80
📈 最近5条K线:
   📈 10:05 - O:$2769.50 H:$2770.90 L:$2769.20 C:$2770.80
   📉 10:00 - O:$2770.20 H:$2770.50 L:$2768.80 C:$2769.50
   ...
========================================
✅ 启动数据加载完成！系统已准备好开始交易
========================================
✅ 启动数据已加载完成，开始执行交易策略
【Bybit黄金交易策略】开始执行 - 交易品种: XAUTUSDT
```

### Bybit未启用时的日志

```
⚠️ Bybit未启用，跳过启动时数据加载
```

### 禁用启动加载时的日志

```
⏸️ 启动时K线加载已禁用
```

## 交易策略等待机制

### 1. 策略执行任务（每60秒）

```java
@Scheduled(fixedRate = 60000)
public void executeStrategy() {
    // 等待启动数据加载完成（最多等待60秒）
    if (!StartupDataLoader.isDataLoaded()) {
        if (!StartupDataLoader.awaitDataLoaded(60)) {
            log.warn("⏸️ 启动数据尚未加载完成，暂停交易策略执行");
            return;
        }
        log.info("✅ 启动数据已加载完成，开始执行交易策略");
    }
    
    // 执行交易策略...
}
```

### 2. 数据采集任务（每5分钟）

```java
@Scheduled(fixedRate = 300000)
public void collectMarketData() {
    // 等待启动数据加载完成（最多等待30秒）
    if (!StartupDataLoader.isDataLoaded()) {
        if (!StartupDataLoader.awaitDataLoaded(30)) {
            log.warn("⏸️ 等待启动数据加载超时，跳过本次数据采集");
            return;
        }
    }
    
    // 采集市场数据...
}
```

### 3. 持仓监控任务（每5秒）

```java
@Scheduled(fixedRate = 5000)
public void monitorPaperPositions() {
    // 等待启动数据加载完成
    if (!StartupDataLoader.isDataLoaded()) {
        return;
    }
    
    // 监控持仓...
}
```

## 技术实现

### 核心类：StartupDataLoader

```java
@Service
public class StartupDataLoader implements ApplicationRunner {
    
    // 实现 ApplicationRunner 接口，在应用启动后自动执行
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 检查配置
        // 加载K线数据
        // 设置加载完成标志
    }
    
    // 提供静态方法供其他组件检查数据加载状态
    public static boolean isDataLoaded() { ... }
    public static boolean awaitDataLoaded(long timeout) { ... }
}
```

### 数据加载流程

1. **获取K线数据**: 调用 `BybitTradingService.getKlines()`
2. **解析数据**: 将JSON数据转换为Kline实体
3. **保存数据**: 调用 `MarketDataService.saveKline()`（自动去重）
4. **统计信息**: 显示成功、跳过、总计数量
5. **价格展示**: 显示当前价格和最近5条K线

## 常见问题

### Q1: 如何禁用启动加载功能？

**A**: 在 `application.yml` 中设置：

```yaml
trading:
  startup:
    load-klines: false
```

### Q2: 启动加载失败会影响系统启动吗？

**A**: 不会。即使加载失败，系统仍会正常启动，但交易策略可能因为缺少历史数据而无法正常工作。

### Q3: 重复启动会重复加载数据吗？

**A**: 数据库保存时会自动去重，重复的K线数据会被跳过，只保存新数据。

### Q4: 启动加载会阻塞应用启动吗？

**A**: 不会。数据加载是异步执行的，不会阻塞Spring Boot应用的启动过程。

### Q5: 如何调整加载的K线数量？

**A**: 修改配置文件中的 `klines-count` 参数：

```yaml
trading:
  startup:
    klines-count: 300  # 加载最近300条K线
```

## 优化建议

### 1. 数据库索引

确保 `kline` 表有适当的索引以提高查询性能：

```sql
CREATE INDEX idx_kline_symbol_interval_timestamp 
ON kline(symbol, `interval`, timestamp DESC);
```

### 2. 网络超时处理

如果网络环境不稳定，可以考虑增加重试机制：

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        klines = bybitTradingService.getKlines(bybitSymbol, "5", klinesCount);
        break;
    } catch (Exception e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(2000); // 等待2秒后重试
    }
}
```

### 3. 数据完整性校验

可以在加载完成后检查数据的连续性和完整性。

## 监控和日志

### 关键日志级别

- **INFO**: 正常的加载进度和结果
- **WARN**: 配置禁用、Bybit未启用等警告
- **ERROR**: 加载失败、网络错误等异常

### 推荐监控指标

1. **加载耗时**: 从开始到完成的时间
2. **成功率**: 加载成功次数 / 总启动次数
3. **数据量**: 每次加载的K线数量
4. **重复率**: 跳过的重复数据比例

## 版本历史

- **v1.0.0** (2026-01-19): 初始版本，支持启动时自动加载K线数据

## 相关文件

- `StartupDataLoader.java` - 启动数据加载服务
- `TradingScheduler.java` - 交易调度器（已集成等待机制）
- `application.yml` - 配置文件
- `MarketDataService.java` - 市场数据服务
- `BybitTradingService.java` - Bybit交易服务

## 支持

如有问题或建议，请联系开发团队。
