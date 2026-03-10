# 币安 XAUUSDT 切换指南

## 概述

本项目已成功从 Bybit XAUTUSDT 切换到币安 XAUUSDT 永续合约。本文档说明已完成的修改和如何使用。

## ✅ 已完成的修改

### 1. 配置文件修改 (`application.yml`)

#### 禁用 Bybit
```yaml
bybit:
  api:
    enabled: false  # ❌ 已禁用Bybit
```

#### 启用币安
```yaml
binance:
  api:
    enabled: true   # ✅ 已启用币安
    
  # 合约交易配置（XAUUSDT永续合约）
  futures:
    symbol: XAUUSDT                         # ✅ 黄金永续合约
    live-mode: false                        # ⚠️ 合约实盘开关（false=只读，true=实盘）
    max-order-amount: 10                    # 单笔最大数量
    max-daily-trades: 20                    # 每日最大交易次数
    max-daily-loss: 500.0                   # 每日最大亏损（USD）
    leverage: 2                             # 杠杆倍数
  
  # 风控配置
  risk:
    mode: atr                               # 风控模式：fixed/atr
    stop-loss-dollars: 8                    # 固定止损金额
    take-profit-dollars: 15                 # 固定止盈金额
    atr-stop-loss-multiplier: 3.0           # ATR止损倍数
    atr-take-profit-multiplier: 4.0         # ATR止盈倍数
    atr-min-threshold: 1.0                  # ATR最小阈值
    atr-max-threshold: 15.0                 # ATR最大阈值
```

### 2. 代码修改

#### TradingScheduler.java
- ✅ 添加币安合约服务注入
- ✅ 添加币安相关配置参数
- ✅ 新增 `executeBinanceStrategy()` 方法处理币安交易策略
- ✅ 新增 `executeBinanceBuy()` 和 `executeBinanceSell()` 方法
- ✅ 新增 `collectBinanceData()` 方法采集币安K线数据
- ✅ 优先使用币安（如果启用），否则fallback到Bybit

#### StartupDataLoader.java
- ✅ 添加币安合约服务注入
- ✅ 添加币安相关配置参数
- ✅ 新增 `loadBinanceData()` 方法加载币安初始数据
- ✅ 优先使用币安（如果启用），否则fallback到Bybit

## 🚀 使用方法

### 当前配置（默认）

系统已配置为使用**币安XAUUSDT**，当前为**模拟交易模式**：

```yaml
binance:
  api:
    enabled: true                    # ✅ 币安已启用
    key: ""                          # 留空即可（仅获取价格不需要API Key）
    secret: ""                       # 留空即可
    
bybit:
  api:
    enabled: false                   # ❌ Bybit已禁用
    paper-trading: true              # 🎯 模拟交易模式
```

### 启动系统

```bash
# 启动应用
mvn spring-boot:run

# 或使用已编译的jar
java -jar target/aug-trade-*.jar
```

### 日志确认

启动后查看日志，确认使用币安：

```
========================================
🚀 开始加载币安启动数据...
========================================
💰 币安当前XAUUSDT价格: $2947.50
✅ 币安数据加载完成！系统已准备好开始交易
========================================
【币安黄金交易策略】开始执行 - 交易品种: XAUUSDT
当前黄金价格: $2947.50
```

## ⚙️ 配置选项

### 1. 数据源切换

#### 使用币安（推荐，当前配置）
```yaml
binance.api.enabled: true
bybit.api.enabled: false
```

#### 使用 Bybit（如需切换回）
```yaml
binance.api.enabled: false
bybit.api.enabled: true
```

### 2. 交易模式

#### 模拟交易（当前配置，推荐）
```yaml
bybit.api.paper-trading: true
binance.futures.live-mode: false
```

#### 实盘交易（⚠️ 谨慎使用）
```yaml
bybit.api.paper-trading: false
binance.futures.live-mode: true

# 必须配置API Key
binance:
  api:
    key: "your-api-key"
    secret: "your-api-secret"
```

### 3. 风控参数

```yaml
binance:
  risk:
    mode: atr                          # fixed: 固定止损止盈 / atr: 动态ATR
    
    # 固定模式参数
    stop-loss-dollars: 8               # 止损：每盎司$8
    take-profit-dollars: 15            # 止盈：每盎司$15
    
    # ATR动态模式参数（推荐）
    atr-stop-loss-multiplier: 3.0      # ATR倍数：3倍ATR作为止损
    atr-take-profit-multiplier: 4.0    # ATR倍数：4倍ATR作为止盈
    atr-min-threshold: 1.0             # 最小ATR：1.0（市场太平静时不交易）
    atr-max-threshold: 15.0            # 最大ATR：15.0（市场太波动时不交易）
```

## 📊 主要功能

### 1. 数据采集
- ✅ 每5分钟自动从币安获取XAUUSDT价格
- ✅ 保存到数据库供策略分析使用

### 2. 策略执行
- ✅ 每60秒执行一次交易策略
- ✅ 支持多种技术指标（EMA, RSI, MACD, Supertrend等）
- ✅ 动态市场状态识别（强趋势/弱趋势/震荡）
- ✅ 智能信号过滤（根据市场状态调整开仓门槛）

### 3. 风险控制
- ✅ ATR动态止损止盈
- ✅ 每日交易次数限制（最多20笔）
- ✅ 开仓冷却期（防止频繁交易）
- ✅ 最小开仓间隔（30分钟）
- ✅ 趋势反转检测（EMA死叉时禁止做多）

### 4. 持仓管理
- ✅ 实时监控持仓盈亏
- ✅ 自动止损止盈
- ✅ 移动止损（保护利润）
- ✅ 最大持仓时间（30分钟强制平仓）

## 🔄 如何切换回 Bybit

如果需要切换回Bybit，只需修改配置：

```yaml
# 禁用币安
binance:
  api:
    enabled: false

# 启用Bybit
bybit:
  api:
    enabled: true
    key: "your-bybit-api-key"      # 如果需要实盘交易
    secret: "your-bybit-api-secret"
    paper-trading: true             # true=模拟, false=实盘
```

重启应用即可。

## ⚠️ 注意事项

### 1. API Key 配置
- **模拟交易**：无需配置API Key，留空即可
- **实盘交易**：必须配置币安API Key和Secret，且需要开通合约交易权限

### 2. 交易权限
- 币安API Key需要开通**U本位合约交易权限**
- 建议先在测试网测试：`binance.api.testnet: true`

### 3. 杠杆设置
- 当前配置：2倍杠杆（保守）
- 币安支持1-125倍，建议不超过5倍

### 4. 交易品种
- 币安：`XAUUSDT`（黄金永续合约）
- Bybit：`XAUTUSDT`（注意拼写差异）

### 5. 最小交易数量
- 币安XAUUSDT：最小1盎司（整数）
- 当前配置：10盎司/单

## 📈 数据对比

| 项目 | Bybit XAUTUSDT | 币安 XAUUSDT |
|------|----------------|--------------|
| 交易对 | XAUTUSDT | XAUUSDT |
| 最小数量 | 1盎司 | 1盎司 |
| 杠杆范围 | 1-50倍 | 1-125倍 |
| 手续费 | Maker: 0.02%, Taker: 0.055% | Maker: 0.02%, Taker: 0.04% |
| API限制 | 120请求/分钟 | 1200请求/分钟 |

## 🎯 推荐配置

### 保守配置（推荐新手）
```yaml
binance:
  futures:
    leverage: 2                      # 2倍杠杆
    max-order-amount: 10             # 最大10盎司
  risk:
    mode: atr                        # ATR动态风控
    atr-stop-loss-multiplier: 3.0    # 3倍ATR止损
```

### 激进配置（有经验者）
```yaml
binance:
  futures:
    leverage: 5                      # 5倍杠杆
    max-order-amount: 20             # 最大20盎司
  risk:
    mode: atr
    atr-stop-loss-multiplier: 2.0    # 2倍ATR止损
```

## 🐛 常见问题

### Q1: 启动后提示"币安服务未启用"
**A:** 检查配置文件 `binance.api.enabled` 是否为 `true`

### Q2: 获取价格失败
**A:** 检查网络连接，确保可以访问币安API（`https://fapi.binance.com`）

### Q3: 如何查看当前使用的数据源？
**A:** 查看启动日志，会显示"币安黄金交易策略"或"Bybit黄金交易策略"

### Q4: 可以同时使用币安和Bybit吗？
**A:** 不可以，系统会优先使用币安（如果启用），否则使用Bybit

## 📞 技术支持

如有问题，请查看日志文件：
```bash
tail -f logs/aug-trade.log
```

或提交Issue到项目仓库。

---

**最后更新**: 2026-03-10
**版本**: v1.0
