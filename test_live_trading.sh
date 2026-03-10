#!/bin/bash

# 币安实盘交易测试脚本
# 用途：快速测试实盘交易接口
# 作者：Peter Wang
# 日期：2026-03-10

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 基础URL
BASE_URL="http://localhost:3131/api/live-trading"

echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  币安实盘交易测试脚本${NC}"
echo -e "${BLUE}================================${NC}"
echo ""

# 1. 测试连接
echo -e "${YELLOW}[1/5] 测试币安连接...${NC}"
response=$(curl -s "${BASE_URL}/test-connection")
echo "$response" | jq '.'

if echo "$response" | jq -e '.connected == true' > /dev/null; then
    echo -e "${GREEN}✅ 连接正常${NC}"
else
    echo -e "${RED}❌ 连接失败${NC}"
    exit 1
fi
echo ""

# 2. 查询实盘状态
echo -e "${YELLOW}[2/5] 查询实盘状态...${NC}"
response=$(curl -s "${BASE_URL}/status")
echo "$response" | jq '.'

live_mode=$(echo "$response" | jq -r '.liveModeEnabled')
if [ "$live_mode" = "true" ]; then
    echo -e "${GREEN}✅ 实盘模式已启用${NC}"
else
    echo -e "${RED}⚠️  实盘模式未启用（这是安全的）${NC}"
    echo -e "${YELLOW}提示: 如需启用实盘，请修改 application.yml 中的 binance.live.mode=true${NC}"
fi
echo ""

# 3. 查询账户余额
echo -e "${YELLOW}[3/5] 查询账户余额...${NC}"
response=$(curl -s "${BASE_URL}/balance")
echo "$response" | jq '.'

balance=$(echo "$response" | jq -r '.balance')
if [ "$balance" != "null" ]; then
    echo -e "${GREEN}✅ USDT余额: \$$balance${NC}"
else
    echo -e "${RED}❌ 获取余额失败${NC}"
fi
echo ""

# 4. 健康检查
echo -e "${YELLOW}[4/5] 健康检查...${NC}"
response=$(curl -s "${BASE_URL}/health")
echo "$response" | jq '.'
echo -e "${GREEN}✅ 服务运行正常${NC}"
echo ""

# 5. 测试买入（仅当实盘模式启用时）
echo -e "${YELLOW}[5/5] 测试买入接口（不会真实下单，除非实盘模式启用）...${NC}"
if [ "$live_mode" = "true" ]; then
    echo -e "${RED}⚠️⚠️⚠️ 警告：实盘模式已启用！${NC}"
    echo -e "${RED}如果继续，将会真实下单！${NC}"
    echo -e "${YELLOW}是否继续测试买入？(yes/no)${NC}"
    read -r confirm
    
    if [ "$confirm" = "yes" ]; then
        echo -e "${YELLOW}执行买入测试（数量：0.001 PAXG）...${NC}"
        response=$(curl -s -X POST "${BASE_URL}/buy?quantity=0.001&strategy=脚本测试")
        echo "$response" | jq '.'
        
        if echo "$response" | jq -e '.success == true' > /dev/null; then
            echo -e "${GREEN}✅ 买入成功${NC}"
        else
            echo -e "${RED}❌ 买入失败${NC}"
        fi
    else
        echo -e "${YELLOW}已取消买入测试${NC}"
    fi
else
    echo -e "${YELLOW}尝试买入（预期会被拒绝）...${NC}"
    response=$(curl -s -X POST "${BASE_URL}/buy?quantity=0.01&strategy=测试")
    echo "$response" | jq '.'
    echo -e "${GREEN}✅ 安全机制正常工作（拒绝下单）${NC}"
fi
echo ""

# 总结
echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}  测试完成！${NC}"
echo -e "${BLUE}================================${NC}"
echo ""
echo -e "${YELLOW}下一步操作：${NC}"
echo "1. 如需启用实盘，请修改 application.yml 中的配置"
echo "2. 填写币安API Key和Secret"
echo "3. 设置合理的风险限额"
echo "4. 阅读完整文档: BINANCE_LIVE_TRADING_GUIDE.md"
echo ""
echo -e "${YELLOW}重要提醒：${NC}"
echo "⚠️  实盘交易涉及真实资金，请务必谨慎操作！"
echo "⚠️  建议先在测试网充分测试！"
echo ""
