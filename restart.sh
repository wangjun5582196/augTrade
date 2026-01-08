#!/bin/bash

# AugTrade Bybit黄金交易重启脚本

echo "============================================"
echo "🔄 重启AugTrade - Bybit黄金交易模式"
echo "============================================"
echo ""

# 查找并停止现有进程
echo "📍 步骤1：停止现有进程..."
PID=$(ps aux | grep 'AugTradeApplication' | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "发现运行中的进程: PID=$PID"
    kill -9 $PID
    echo "✅ 已停止旧进程"
    sleep 2
else
    echo "✅ 没有运行中的进程"
fi

echo ""
echo "📍 步骤2：清理并重新编译..."
mvn clean package -DskipTests

echo ""
echo "📍 步骤3：启动应用（Bybit模式）..."
echo ""
echo "⚠️  重要提示："
echo "   - 确认 bybit.api.enabled = true"
echo "   - 确认已填入API密钥"
echo "   - 当前使用测试网（testnet: true）"
echo ""
echo "============================================"
echo "🚀 启动中..."
echo "============================================"
echo ""

mvn spring-boot:run

# 等待启动
sleep 5

echo ""
echo "============================================"
echo "✅ 启动完成！"
echo "============================================"
echo ""
echo "📊 监控方式："
echo "   1. 当前终端会显示日志"
echo "   2. 或在新终端运行: tail -f logs/aug-trade.log"
echo "   3. 或访问测试网: https://testnet.bybit.com/"
echo ""
echo "🔍 检查日志中是否出现："
echo "   【Bybit黄金交易策略】开始执行"
echo ""
echo "如果还是显示BTC，请确认："
echo "   1. bybit.api.enabled 是否为 true"
echo "   2. API密钥是否正确填写"
echo "   3. 是否重启了应用"
echo ""
