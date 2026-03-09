#!/bin/bash

# 测试币安黄金价格API
# 使用方式: ./test_binance_gold_price.sh

echo "=========================================="
echo "测试币安黄金价格获取"
echo "=========================================="
echo ""

# 1. 测试币安API连接（公开接口，无需API Key）
echo "1. 测试币安API连接..."
curl -s "https://api.binance.com/api/v3/ping"
echo ""
echo ""

# 2. 获取PAXGUSDT（Paxos Gold）价格
echo "2. 获取PAXGUSDT（Paxos Gold）价格..."
curl -s "https://api.binance.com/api/v3/ticker/price?symbol=PAXGUSDT" | python3 -m json.tool
echo ""
echo ""

# 3. 获取PAXGUSDT的24小时统计
echo "3. 获取PAXGUSDT的24小时统计..."
curl -s "https://api.binance.com/api/v3/ticker/24hr?symbol=PAXGUSDT" | python3 -m json.tool | head -20
echo ""
echo ""

# 4. 获取Bybit的XAUTUSDT价格（对比）
echo "4. 获取Bybit的XAUTUSDT价格（对比）..."
curl -s "https://api.bybit.com/v5/market/tickers?category=linear&symbol=XAUTUSDT" | python3 -m json.tool | head -30
echo ""
echo ""

echo "=========================================="
echo "测试完成！"
echo "=========================================="
echo ""
echo "说明："
echo "- PAXGUSDT: 币安的Paxos Gold（1 PAXG = 1盎司黄金）"
echo "- XAUTUSDT: Bybit的黄金永续合约"
echo "- 两者价格应该非常接近，但会有少量差异"
echo ""
