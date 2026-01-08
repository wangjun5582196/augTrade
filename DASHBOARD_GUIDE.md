# 📊 AugTrade 交易仪表板使用指南

## 概述

已为您创建了一个完整的交易仪表板系统，用于实时监控盈利情况和策略执行状态。

---

## 📁 已创建的文件

### 1. 后端控制器
**文件位置**: `src/main/java/com/ltp/peter/augtrade/controller/DashboardController.java`

提供以下API接口：

#### 核心API端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/dashboard/overview` | GET | 获取仪表板概览数据 |
| `/api/dashboard/positions` | GET | 获取当前持仓列表 |
| `/api/dashboard/recent-trades` | GET | 获取最近交易记录 |
| `/api/dashboard/strategy-stats` | GET | 获取策略执行统计 |
| `/api/dashboard/daily-pnl` | GET | 获取每日盈亏统计 |

#### API返回数据示例

**概览数据** (`/api/dashboard/overview`):
```json
{
  "success": true,
  "totalProfitLoss": 1234.56,
  "todayProfitLoss": 123.45,
  "unrealizedPnl": 45.67,
  "totalFees": 12.34,
  "totalTrades": 150,
  "todayTrades": 12,
  "openPositionsCount": 2,
  "winRate": "65.50",
  "timestamp": 1704712800000
}
```

**持仓数据** (`/api/dashboard/positions`):
```json
{
  "success": true,
  "count": 2,
  "data": [
    {
      "symbol": "XAUUSD",
      "direction": "LONG",
      "quantity": 0.01,
      "avgPrice": 2650.50,
      "currentPrice": 2655.30,
      "unrealizedPnl": 48.00
    }
  ]
}
```

**策略统计** (`/api/dashboard/strategy-stats`):
```json
{
  "success": true,
  "count": 3,
  "data": [
    {
      "strategyName": "RSI策略",
      "totalTrades": 50,
      "winTrades": 35,
      "lossTrades": 15,
      "winRate": "70.00",
      "totalPnl": 850.50
    }
  ]
}
```

---

### 2. 前端页面
**文件位置**: `src/main/resources/static/dashboard.html`

#### 功能特性

✨ **核心功能**:
- 📈 实时显示总盈亏、今日盈亏、未实现盈亏
- 🎯 显示交易胜率和交易次数统计
- 💼 展示当前所有持仓信息
- 📊 各策略表现对比
- 📉 近7日盈亏趋势图表
- 📋 最近交易记录列表

🔄 **自动刷新**:
- 默认每30秒自动刷新数据
- 可手动开关自动刷新功能
- 一键手动刷新按钮

🎨 **界面特性**:
- 响应式设计，支持移动端
- 渐变紫色主题，美观大方
- 盈利显示为绿色，亏损显示为红色
- 平滑动画过渡效果

---

## 🚀 使用方法

### 1. 启动应用

使用以下任一方式启动应用：

**方法1：使用IntelliJ IDEA**
```bash
# 在IDEA中直接运行 AugTradeApplication.java
```

**方法2：使用Maven（如果已安装）**
```bash
cd /Users/peterwang/IdeaProjects/AugTrade
mvn spring-boot:run
```

**方法3：使用已编译的JAR**
```bash
cd /Users/peterwang/IdeaProjects/AugTrade
java -jar target/aug-trade-*.jar
```

### 2. 访问仪表板

应用启动后，在浏览器中访问：

```
http://localhost:3131/api/dashboard.html
```

> 注意：由于应用配置了 `context-path: /api`，所以URL需要包含 `/api` 前缀。

### 3. 功能说明

#### 顶部统计卡片
- **总盈亏**: 所有已平仓交易的累计盈亏
- **今日盈亏**: 当日所有交易的盈亏总和
- **未实现盈亏**: 当前持仓的浮动盈亏
- **胜率**: 盈利交易占总交易的百分比

#### 当前持仓
显示所有开仓中的持仓：
- 交易对、方向（多/空）
- 持仓数量、平均开仓价
- 当前市场价格
- 未实现盈亏（实时更新）

#### 策略表现
按策略分组统计：
- 每个策略的交易次数
- 胜负场数和胜率
- 该策略的总盈亏

#### 近7日盈亏趋势
柱状图展示最近7天的每日盈亏情况：
- 绿色柱表示盈利
- 红色柱表示亏损
- 鼠标悬停查看详细数值

#### 最近交易记录
显示最近10笔已成交的交易：
- 交易时间、交易对
- 买入/卖出方向
- 成交数量和价格
- 使用的策略名称
- 该笔交易的盈亏

---

## 🎯 核心数据说明

### 盈亏计算

1. **总盈亏** = Σ(所有已平仓订单的profitLoss)
2. **今日盈亏** = Σ(今日已平仓订单的profitLoss)
3. **未实现盈亏** = Σ(当前持仓的未实现盈亏)
   - 多头: (当前价 - 开仓价) × 数量
   - 空头: (开仓价 - 当前价) × 数量

### 胜率计算

```
胜率 = (盈利交易数 / 总交易数) × 100%
```

只统计已平仓的交易，盈利交易指 profitLoss > 0 的交易。

---

## 📱 界面截图说明

### 统计卡片区域
- 4个大卡片显示核心指标
- 数值根据盈亏自动变色
- 悬停时卡片上浮效果

### 持仓和策略区域
- 左右分栏布局
- 表格清晰展示数据
- 方向标签带颜色区分

### 趋势图表
- 动态柱状图
- 响应鼠标交互
- 自适应容器大小

### 交易记录
- 全宽度展示
- 最新交易在最上方
- 支持滚动查看更多

---

## 🔧 自定义配置

### 修改刷新间隔

在 `dashboard.html` 中找到以下代码：

```javascript
// 修改自动刷新间隔（毫秒）
autoRefreshInterval = setInterval(loadAllData, 30000); // 默认30秒
```

### 修改显示交易数量

在 `dashboard.html` 中找到以下代码：

```javascript
// 修改显示的交易记录数量
const response = await fetch('/dashboard/recent-trades?limit=10'); // 默认10条
```

### 修改统计天数

在 `dashboard.html` 中找到以下代码：

```javascript
// 修改每日盈亏统计的天数
const response = await fetch('/dashboard/daily-pnl?days=7'); // 默认7天
```

---

## 🐛 故障排查

### 1. 页面显示"加载中..."

**可能原因**:
- 应用未启动或启动失败
- 数据库未连接
- 端口被占用

**解决方法**:
```bash
# 检查应用是否运行
ps aux | grep AugTradeApplication

# 查看应用日志
tail -f logs/aug-trade.log

# 检查端口是否被占用
lsof -i :3131
```

### 2. 显示"暂无数据"

**可能原因**:
- 数据库中没有交易数据
- 查询条件过滤掉了所有数据

**解决方法**:
- 执行一些测试交易
- 检查数据库表 `t_trade_order` 和 `t_position` 中的数据

### 3. 数据不更新

**可能原因**:
- 自动刷新被关闭
- 网络请求失败

**解决方法**:
- 检查"自动刷新"复选框是否勾选
- 打开浏览器开发者工具(F12)查看Console和Network标签
- 点击"刷新数据"按钮手动刷新

### 4. 样式显示异常

**可能原因**:
- 浏览器缓存问题
- CSS未加载

**解决方法**:
```bash
# 强制刷新浏览器
# Mac: Cmd + Shift + R
# Windows: Ctrl + Shift + R

# 或清除浏览器缓存后重新访问
```

---

## 📊 数据库表结构要求

仪表板依赖以下数据库表：

### t_trade_order (交易订单表)
必需字段：
- `status`: 订单状态 (FILLED表示已成交)
- `profit_loss`: 盈亏金额
- `fee`: 手续费
- `executed_time`: 成交时间
- `strategy_name`: 策略名称

### t_position (持仓表)
必需字段：
- `status`: 持仓状态 (OPEN表示持仓中)
- `symbol`: 交易对
- `direction`: 方向 (LONG/SHORT)
- `quantity`: 数量
- `avg_price`: 平均价格
- `unrealized_pnl`: 未实现盈亏

---

## 🎨 界面定制

### 修改主题颜色

在 `dashboard.html` 的 `<style>` 标签中修改：

```css
/* 主背景渐变 */
body {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

/* 盈利颜色 */
.value.positive, .profit {
    color: #10b981; /* 绿色 */
}

/* 亏损颜色 */
.value.negative, .loss {
    color: #ef4444; /* 红色 */
}

/* 按钮颜色 */
.refresh-btn {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
```

---

## 🔐 安全建议

1. **生产环境部署**:
   - 添加用户认证（Spring Security）
   - 使用HTTPS协议
   - 限制API访问频率

2. **数据保护**:
   - 敏感数据加密存储
   - 定期备份数据库
   - 设置适当的访问权限

3. **监控告警**:
   - 配置异常告警通知
   - 监控系统性能指标
   - 记录用户访问日志

---

## 📈 后续扩展建议

1. **功能增强**:
   - 添加日期范围筛选
   - 导出报表功能（Excel/PDF）
   - 更多图表类型（饼图、折线图）
   - 实时WebSocket推送

2. **性能优化**:
   - 添加Redis缓存
   - 数据分页加载
   - 图表数据聚合

3. **用户体验**:
   - 添加暗色主题切换
   - 自定义仪表板布局
   - 移动端App版本

---

## 📞 技术支持

如有问题，请查看：
- 应用日志: `logs/aug-trade.log`
- 浏览器控制台(F12)
- 项目其他文档

---

## ✅ 快速检查清单

启动仪表板前，请确认：

- [ ] MySQL数据库正常运行
- [ ] 应用配置文件正确(application.yml)
- [ ] 应用成功启动(检查日志无报错)
- [ ] 端口3131未被占用
- [ ] 浏览器支持现代Web标准
- [ ] 数据库中有测试数据

---

**创建日期**: 2026-01-08  
**版本**: 1.0  
**作者**: Peter Wang

祝您交易顺利！🚀
