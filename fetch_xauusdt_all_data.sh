#!/bin/bash

# 币安XAUUSDT所有历史数据获取脚本
# 从2025-12-11（XAUUSDT合约上线日期）到现在的所有K线数据

echo "=========================================="
echo "币安XAUUSDT历史数据获取工具"
echo "=========================================="
echo ""

# 检查服务是否运行
if ! curl -s http://localhost:3131/actuator/health > /dev/null 2>&1; then
    echo "❌ 错误：应用未运行，请先启动应用"
    echo "提示：运行 mvn spring-boot:run 启动应用"
    exit 1
fi

echo "✅ 应用运行中"
echo ""

# 默认参数
INTERVAL=${1:-5m}
PORT=${2:-3131}

echo "📊 获取参数："
echo "   - K线周期: $INTERVAL"
echo "   - 服务端口: $PORT"
echo "   - 时间范围: 2025-12-11 至今"
echo ""

# 支持的K线周期
echo "💡 支持的K线周期："
echo "   1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M"
echo ""

read -p "确认开始获取数据？(y/n) " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消"
    exit 0
fi

echo ""
echo "=========================================="
echo "开始获取所有历史数据..."
echo "=========================================="
echo ""

# 调用API获取所有历史数据（注意：需要加上/api前缀）
RESPONSE=$(curl -s "http://localhost:$PORT/api/historical/binance/xauusdt/all?interval=$INTERVAL")

# 检查响应
if [ $? -ne 0 ]; then
    echo "❌ 请求失败"
    exit 1
fi

# 解析JSON响应
SUCCESS=$(echo $RESPONSE | grep -o '"success":[^,]*' | cut -d':' -f2)

if [ "$SUCCESS" = "true" ]; then
    echo ""
    echo "=========================================="
    echo "✅ 数据获取成功！"
    echo "=========================================="
    echo ""
    
    # 提取关键信息
    COUNT=$(echo $RESPONSE | grep -o '"count":[0-9]*' | cut -d':' -f2)
    DURATION=$(echo $RESPONSE | grep -o '"duration":"[^"]*"' | cut -d'"' -f4)
    MESSAGE=$(echo $RESPONSE | grep -o '"message":"[^"]*"' | cut -d'"' -f4)
    
    echo "📈 获取结果："
    echo "   - 交易对: XAUUSDT"
    echo "   - K线周期: $INTERVAL"
    echo "   - 数据条数: $COUNT"
    echo "   - 耗时: $DURATION"
    echo ""
    echo "💬 $MESSAGE"
    echo ""
    
    # 查询数据库中的总数据量
    echo "=========================================="
    echo "📊 数据库统计"
    echo "=========================================="
    echo ""
    echo "提示：可以使用以下SQL查询数据："
    echo ""
    echo "-- 查看总数据量"
    echo "SELECT COUNT(*) FROM klines WHERE symbol='XAUUSDT' AND \`interval\`='$INTERVAL';"
    echo ""
    echo "-- 查看时间范围"
    echo "SELECT MIN(timestamp) as start_time, MAX(timestamp) as end_time FROM klines WHERE symbol='XAUUSDT' AND \`interval\`='$INTERVAL';"
    echo ""
    echo "-- 查看最新10条数据"
    echo "SELECT * FROM klines WHERE symbol='XAUUSDT' AND \`interval\`='$INTERVAL' ORDER BY timestamp DESC LIMIT 10;"
    echo ""
else
    echo ""
    echo "=========================================="
    echo "❌ 数据获取失败"
    echo "=========================================="
    echo ""
    ERROR=$(echo $RESPONSE | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
    echo "错误信息: $ERROR"
    echo ""
    echo "完整响应："
    echo "$RESPONSE" | jq '.' 2>/dev/null || echo "$RESPONSE"
    echo ""
fi

echo "=========================================="
echo "其他可用接口："
echo "=========================================="
echo ""
echo "1. 获取最近N天数据："
echo "   curl 'http://localhost:$PORT/historical/binance/xauusdt/recent?days=30&interval=5m'"
echo ""
echo "2. 获取一年数据："
echo "   curl 'http://localhost:$PORT/historical/binance/xauusdt/one-year?interval=5m'"
echo ""
echo "3. 自定义时间范围："
echo "   curl 'http://localhost:$PORT/historical/binance/xauusdt/custom?startDate=2025-01-01&endDate=2026-01-01&interval=1h'"
echo ""
echo "4. 获取所有数据（本脚本使用的接口）："
echo "   curl 'http://localhost:$PORT/historical/binance/xauusdt/all?interval=5m'"
echo ""
