# TradingScheduler 内存变量问题分析

**发现时间**: 2026-01-16  
**问题严重性**: 🔴 P0 - 重启后数据丢失

---

## 🚨 问题描述

TradingScheduler中有3个关键变量存储在内存中，**应用重启后会丢失**：

### 1. ❌ `lastCloseTime` - 平仓冷却时间

```java
// 第114行
private LocalDateTime lastCloseTime = null;
private static final int CLOSE_COOLDOWN_SECONDS = 300; // 5分钟冷却
```

**问题**:
- 重启后丢失上次平仓时间
- 可能立即开新仓（无视冷却期）
- **风险**: 重启后可能在不合适时机开仓

---

### 2. ❌ `dailyTradeCount` - 每日交易次数

```java
// 第117-119行
private static final int MAX_DAILY_TRADES = 50;
private LocalDateTime dailyTradeResetTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
private int dailyTradeCount = 0;
```

**问题**:
- 重启后计数器归零
- 可能超过每日交易限制
- **风险**: 重启后可以绕过每日交易次数限制（50次）

**示例场景**:
```
10:00 - 已交易45次
11:00 - 重启应用
11:01 - 计数器归零，又可以交易50次！
总计: 可能交易95次（超过50次限制）
```

---

### 3. ✅ 持仓数据（已持久化到数据库）

持仓数据通过`PaperTradingService`存储在数据库中，重启后可以恢复。✅

---

## 💡 解决方案

### 方案A: 使用数据库持久化 ⭐ 推荐

创建一个状态表来存储这些变量：

```sql
CREATE TABLE IF NOT EXISTS trading_state (
    id INT PRIMARY KEY AUTO_INCREMENT,
    state_key VARCHAR(50) UNIQUE NOT NULL,
    state_value VARCHAR(255),
    last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 存储的状态
-- state_key = 'last_close_time', state_value = '2026-01-16T13:00:00'
-- state_key = 'daily_trade_count', state_value = '15'
-- state_key = 'daily_trade_reset_time', state_value = '2026-01-16T00:00:00'
```

**优点**:
- ✅ 重启安全
- ✅ 可以查询历史状态
- ✅ 支持分布式部署

**缺点**:
- ⚠️ 需要额外的数据库操作

---

### 方案B: 使用Redis缓存

```java
@Autowired
private RedisTemplate<String, String> redisTemplate;

// 保存
redisTemplate.opsForValue().set("trading:last_close_time", LocalDateTime.now().toString());
redisTemplate.opsForValue().set("trading:daily_trade_count", String.valueOf(dailyTradeCount));

// 读取
String lastCloseTimeStr = redisTemplate.opsForValue().get("trading:last_close_time");
if (lastCloseTimeStr != null) {
    lastCloseTime = LocalDateTime.parse(lastCloseTimeStr);
}
```

**优点**:
- ✅ 高性能
- ✅ 支持分布式
- ✅ 自动过期（可设置TTL）

**缺点**:
- ⚠️ 需要额外的Redis依赖
- ⚠️ 如果Redis挂了，数据也会丢

---

### 方案C: 使用文件持久化

```java
@Value("${trading.state.file:/tmp/trading_state.json}")
private String stateFilePath;

private void saveState() {
    TradingState state = new TradingState();
    state.setLastCloseTime(lastCloseTime);
    state.setDailyTradeCount(dailyTradeCount);
    state.setDailyTradeResetTime(dailyTradeResetTime);
    
    // 保存到文件
    ObjectMapper mapper = new ObjectMapper();
    mapper.writeValue(new File(stateFilePath), state);
}

@PostConstruct
private void loadState() {
    File file = new File(stateFilePath);
    if (file.exists()) {
        ObjectMapper mapper = new ObjectMapper();
        TradingState state = mapper.readValue(file, TradingState.class);
        lastCloseTime = state.getLastCloseTime();
        dailyTradeCount = state.getDailyTradeCount();
        dailyTradeResetTime = state.getDailyTradeResetTime();
    }
}
```

**优点**:
- ✅ 简单快速
- ✅ 无需额外依赖

**缺点**:
- ⚠️ 不支持分布式
- ⚠️ 文件可能损坏

---

## 📋 推荐实施方案

### 🎯 最佳方案：数据库持久化

1. **创建StateService**
```java
@Service
public class TradingStateService {
    @Autowired
    private TradingStateMapper stateMapper;
    
    public void saveLastCloseTime(LocalDateTime time) {
        stateMapper.saveState("last_close_time", time.toString());
    }
    
    public LocalDateTime getLastCloseTime() {
        String value = stateMapper.getStateValue("last_close_time");
        return value != null ? LocalDateTime.parse(value) : null;
    }
    
    public void incrementDailyTradeCount() {
        int current = getDailyTradeCount();
        stateMapper.saveState("daily_trade_count", String.valueOf(current + 1));
    }
    
    public int getDailyTradeCount() {
        String value = stateMapper.getStateValue("daily_trade_count");
        return value != null ? Integer.parseInt(value) : 0;
    }
    
    public void resetDailyTradeCount() {
        stateMapper.saveState("daily_trade_count", "0");
        stateMapper.saveState("daily_trade_reset_time", LocalDateTime.now().toString());
    }
}
```

2. **修改TradingScheduler**
```java
@Autowired
private TradingStateService stateService;

// 移除内存变量
// private LocalDateTime lastCloseTime = null;
// private int dailyTradeCount = 0;

// 使用数据库状态
private void checkCooldown() {
    LocalDateTime lastCloseTime = stateService.getLastCloseTime();
    if (lastCloseTime != null) {
        long secondsSinceClose = Duration.between(lastCloseTime, LocalDateTime.now()).getSeconds();
        if (secondsSinceClose < CLOSE_COOLDOWN_SECONDS) {
            // ... 冷却中
        }
    }
}

// 平仓后保存
private void afterClose() {
    stateService.saveLastCloseTime(LocalDateTime.now());
}

// 检查每日交易次数
private boolean checkDailyLimit() {
    int count = stateService.getDailyTradeCount();
    if (count >= MAX_DAILY_TRADES) {
        return false;
    }
    return true;
}

// 开仓后增加计数
private void afterOpen() {
    stateService.incrementDailyTradeCount();
}
```

---

## ⚠️ 临时缓解措施

在修复之前，可以通过以下方式减轻影响：

1. **避免频繁重启** - 只在必要时重启
2. **重启前记录状态** - 手动记录最后平仓时间和交易次数
3. **重启后等待5分钟** - 给冷却期一些缓冲时间
4. **监控每日交易次数** - 通过日志或数据库查询

---

## 🎯 实施优先级

| 变量 | 严重性 | 优先级 | 建议方案 |
|------|--------|--------|---------|
| `lastCloseTime` | 🟡 中 | P1 | 数据库持久化 |
| `dailyTradeCount` | 🔴 高 | P0 | 数据库持久化（必须） |
| `dailyTradeResetTime` | 🟡 中 | P1 | 数据库持久化 |

---

## 📝 实施步骤

### Step 1: 创建数据库表
```sql
CREATE TABLE IF NOT EXISTS trading_state (
    id INT PRIMARY KEY AUTO_INCREMENT,
    state_key VARCHAR(50) UNIQUE NOT NULL,
    state_value VARCHAR(255),
    last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_state_key (state_key)
);
```

### Step 2: 创建Mapper
```java
@Mapper
public interface TradingStateMapper {
    @Insert("INSERT INTO trading_state (state_key, state_value) " +
            "VALUES (#{key}, #{value}) " +
            "ON DUPLICATE KEY UPDATE state_value = #{value}, last_update = NOW()")
    void saveState(@Param("key") String key, @Param("value") String value);
    
    @Select("SELECT state_value FROM trading_state WHERE state_key = #{key}")
    String getStateValue(@Param("key") String key);
}
```

### Step 3: 创建Service
（见上面推荐实施方案）

### Step 4: 修改TradingScheduler
（见上面推荐实施方案）

### Step 5: 测试
1. 启动应用，交易几次
2. 记录状态（平仓时间、交易次数）
3. 重启应用
4. 验证状态是否恢复

---

## ✅ 验证清单

- [ ] 创建trading_state表
- [ ] 创建TradingStateMapper
- [ ] 创建TradingStateService
- [ ] 修改TradingScheduler使用数据库状态
- [ ] 测试重启后状态恢复
- [ ] 测试每日计数器重置逻辑
- [ ] 测试冷却期检查
- [ ] 更新文档

---

**修复完成后预期效果**:
- ✅ 重启安全
- ✅ 每日交易限制严格生效
- ✅ 冷却期正确工作
- ✅ 支持分布式部署（如果使用数据库或Redis）
