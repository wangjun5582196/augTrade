#!/bin/bash

# 代码重组后的Import修复脚本
# 自动更新所有package声明和import引用

echo "🔧 开始修复package声明和import引用..."

BASE_DIR="src/main/java/com/ltp/peter/augtrade"

# 1. 修复strategy模块的package声明
echo "📦 修复strategy模块..."
find $BASE_DIR/strategy -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.strategy;/g' {} \;
find $BASE_DIR/strategy/core -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service\.core\.strategy;/package com.ltp.peter.augtrade.strategy.core;/g' {} \;
find $BASE_DIR/strategy/signal -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service\.core\.signal;/package com.ltp.peter.augtrade.strategy.signal;/g' {} \;

# 2. 修复indicator模块的package声明
echo "📊 修复indicator模块..."
find $BASE_DIR/indicator -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.indicator;/g' {} \;
find $BASE_DIR/indicator -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service\.core\.indicator;/package com.ltp.peter.augtrade.indicator;/g' {} \;

# 3. 修复trading模块的package声明
echo "💼 修复trading模块..."
find $BASE_DIR/trading/execution -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.trading.execution;/g' {} \;
find $BASE_DIR/trading/broker -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.trading.broker;/g' {} \;
find $BASE_DIR/trading/broker -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service\.infrastructure\.broker;/package com.ltp.peter.augtrade.trading.broker;/g' {} \;
find $BASE_DIR/trading/risk -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.trading.risk;/g' {} \;

# 4. 修复market模块的package声明
echo "📈 修复market模块..."
find $BASE_DIR/market -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.market;/g' {} \;
find $BASE_DIR/market -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.util;/package com.ltp.peter.augtrade.market;/g' {} \;

# 5. 修复其他模块的package声明
echo "🔧 修复其他模块..."
find $BASE_DIR/backtest -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.backtest;/g' {} \;
find $BASE_DIR/ml -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.ml;/g' {} \;
find $BASE_DIR/notification -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.notification;/g' {} \;
find $BASE_DIR/state -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.state;/g' {} \;
find $BASE_DIR/scheduler -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.task;/package com.ltp.peter.augtrade.scheduler;/g' {} \;
find $BASE_DIR/scheduler -name "*.java" -type f -exec sed -i '' 's/package com\.ltp\.peter\.augtrade\.service;/package com.ltp.peter.augtrade.scheduler;/g' {} \;

# 6. 全局更新import引用 - 策略模块
echo "🔄 更新import引用 - 策略模块..."
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.TradingStrategyFactory/import com.ltp.peter.augtrade.strategy.TradingStrategyFactory/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.SimplifiedTrendStrategy/import com.ltp.peter.augtrade.strategy.SimplifiedTrendStrategy/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.AggressiveScalpingStrategy/import com.ltp.peter.augtrade.strategy.AggressiveScalpingStrategy/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.TradingStrategyService/import com.ltp.peter.augtrade.strategy.TradingStrategyService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.core\.strategy\./import com.ltp.peter.augtrade.strategy.core./g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.core\.signal\./import com.ltp.peter.augtrade.strategy.signal./g' {} \;

# 7. 全局更新import引用 - 指标模块
echo "🔄 更新import引用 - 指标模块..."
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.IndicatorService/import com.ltp.peter.augtrade.indicator.IndicatorService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.core\.indicator\./import com.ltp.peter.augtrade.indicator./g' {} \;

# 8. 全局更新import引用 - 交易模块
echo "🔄 更新import引用 - 交易模块..."
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.TradeExecutionService/import com.ltp.peter.augtrade.trading.execution.TradeExecutionService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.PaperTradingService/import com.ltp.peter.augtrade.trading.execution.PaperTradingService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.BybitTradingService/import com.ltp.peter.augtrade.trading.broker.BybitTradingService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.RiskManagementService/import com.ltp.peter.augtrade.trading.risk.RiskManagementService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.infrastructure\.broker\./import com.ltp.peter.augtrade.trading.broker./g' {} \;

# 9. 全局更新import引用 - 市场数据模块
echo "🔄 更新import引用 - 市场数据模块..."
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.MarketDataService/import com.ltp.peter.augtrade.market.MarketDataService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.RealMarketDataService/import com.ltp.peter.augtrade.market.RealMarketDataService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.util\.HistoricalDataFetcher/import com.ltp.peter.augtrade.market.HistoricalDataFetcher/g' {} \;

# 10. 全局更新import引用 - 其他模块
echo "🔄 更新import引用 - 其他模块..."
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.BacktestService/import com.ltp.peter.augtrade.backtest.BacktestService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.MLPredictionService/import com.ltp.peter.augtrade.ml.MLPredictionService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.MLRecordService/import com.ltp.peter.augtrade.ml.MLRecordService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.FeishuNotificationService/import com.ltp.peter.augtrade.notification.FeishuNotificationService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.TradingStateService/import com.ltp.peter.augtrade.state.TradingStateService/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.task\.TradingScheduler/import com.ltp.peter.augtrade.scheduler.TradingScheduler/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/import com\.ltp\.peter\.augtrade\.service\.StartupDataLoader/import com.ltp.peter.augtrade.scheduler.StartupDataLoader/g' {} \;

# 11. 修复完全限定类名引用（不是import语句）
echo "🔄 修复完全限定类名..."
find src -name "*.java" -type f -exec sed -i '' 's/com\.ltp\.peter\.augtrade\.service\.StartupDataLoader/com.ltp.peter.augtrade.scheduler.StartupDataLoader/g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/com\.ltp\.peter\.augtrade\.service\.core\.strategy\./com.ltp.peter.augtrade.strategy.core./g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/com\.ltp\.peter\.augtrade\.service\.core\.signal\./com.ltp.peter.augtrade.strategy.signal./g' {} \;
find src -name "*.java" -type f -exec sed -i '' 's/com\.ltp\.peter\.augtrade\.service\.core\.indicator\./com.ltp.peter.augtrade.indicator./g' {} \;

echo "✅ Import修复完成！"
echo ""
echo "📋 下一步："
echo "1. 在IntelliJ IDEA中刷新项目（Cmd+Shift+A → Reload Project）"
echo "2. 检查是否还有编译错误"
echo "3. 使用 Optimize Imports（Cmd+Option+O）清理未使用的import"
echo "4. 编译项目验证：mvn clean compile"
