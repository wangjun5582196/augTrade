#!/bin/bash

# 币安XAUUSDT历史数据抓取脚本
# 使用方法：
#   ./fetch_binance_data.sh one-year        # 抓取一年数据（5分钟周期）
#   ./fetch_binance_data.sh recent 30       # 抓取最近30天数据
#   ./fetch_binance_data.sh custom 2025-01-01 2026-01-01  # 自定义时间范围

# API基础URL
API_BASE="http://localhost:3131/api"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================"
echo "币安XAUUSDT历史数据抓取工具"
echo "========================================"
echo ""

# 检查参数
if [ $# -eq 0 ]; then
    echo -e "${YELLOW}使用方法:${NC}"
    echo "  $0 one-year [interval]                      # 抓取一年数据"
    echo "  $0 recent <days> [interval]                 # 抓取最近N天数据"
    echo "  $0 custom <start_date> <end_date> [interval] # 自定义时间范围"
    echo ""
    echo -e "${YELLOW}示例:${NC}"
    echo "  $0 one-year              # 抓取一年5分钟K线"
    echo "  $0 one-year 1h           # 抓取一年1小时K线"
    echo "  $0 recent 30             # 抓取最近30天5分钟K线"
    echo "  $0 recent 7 15m          # 抓取最近7天15分钟K线"
    echo "  $0 custom 2025-01-01 2025-12-31  # 抓取2025年全年数据"
    echo ""
    echo -e "${YELLOW}支持的K线周期:${NC}"
    echo "  1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w"
    echo ""
    exit 1
fi

# 检查服务是否运行
echo -e "${YELLOW}检查服务状态...${NC}"
if ! curl -s "${API_BASE}/gold/price/compare" > /dev/null 2>&1; then
    echo -e "${RED}❌ 服务未启动或无法连接！${NC}"
    echo "请先启动应用: mvn spring-boot:run"
    exit 1
fi
echo -e "${GREEN}✅ 服务运行正常${NC}"
echo ""

# 根据命令类型执行
case "$1" in
    "one-year")
        INTERVAL=${2:-5m}
        echo -e "${YELLOW}开始抓取币安XAUUSDT一年历史数据...${NC}"
        echo "K线周期: ${INTERVAL}"
        echo ""
        
        RESPONSE=$(curl -s "${API_BASE}/historical/binance/xauusdt/one-year?interval=${INTERVAL}")
        ;;
        
    "recent")
        if [ -z "$2" ]; then
            echo -e "${RED}❌ 请指定天数！${NC}"
            echo "示例: $0 recent 30"
            exit 1
        fi
        
        DAYS=$2
        INTERVAL=${3:-5m}
        echo -e "${YELLOW}开始抓取币安XAUUSDT最近${DAYS}天历史数据...${NC}"
        echo "K线周期: ${INTERVAL}"
        echo ""
        
        RESPONSE=$(curl -s "${API_BASE}/historical/binance/xauusdt/recent?days=${DAYS}&interval=${INTERVAL}")
        ;;
        
    "custom")
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo -e "${RED}❌ 请指定开始日期和结束日期！${NC}"
            echo "示例: $0 custom 2025-01-01 2025-12-31"
            exit 1
        fi
        
        START_DATE=$2
        END_DATE=$3
        INTERVAL=${4:-5m}
        echo -e "${YELLOW}开始抓取币安XAUUSDT自定义时间范围历史数据...${NC}"
        echo "开始日期: ${START_DATE}"
        echo "结束日期: ${END_DATE}"
        echo "K线周期: ${INTERVAL}"
        echo ""
        
        RESPONSE=$(curl -s "${API_BASE}/historical/binance/xauusdt/custom?startDate=${START_DATE}&endDate=${END_DATE}&interval=${INTERVAL}")
        ;;
        
    *)
        echo -e "${RED}❌ 未知命令: $1${NC}"
        echo "请使用: one-year, recent, 或 custom"
        exit 1
        ;;
esac

# 解析响应
SUCCESS=$(echo $RESPONSE | grep -o '"success"[[:space:]]*:[[:space:]]*true')

if [ -n "$SUCCESS" ]; then
    echo ""
    echo "========================================"
    echo -e "${GREEN}✅ 抓取成功！${NC}"
    echo "========================================"
    
    # 提取并显示结果
    COUNT=$(echo $RESPONSE | grep -o '"count"[[:space:]]*:[[:space:]]*[0-9]*' | grep -o '[0-9]*')
    DURATION=$(echo $RESPONSE | grep -o '"duration"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)".*/\1/')
    MESSAGE=$(echo $RESPONSE | grep -o '"message"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)".*/\1/')
    
    echo "数据条数: ${COUNT}"
    echo "耗时: ${DURATION}"
    echo "说明: ${MESSAGE}"
    echo ""
    echo -e "${GREEN}数据已保存到数据库！${NC}"
else
    echo ""
    echo "========================================"
    echo -e "${RED}❌ 抓取失败！${NC}"
    echo "========================================"
    
    ERROR=$(echo $RESPONSE | grep -o '"error"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*"\([^"]*\)".*/\1/')
    if [ -n "$ERROR" ]; then
        echo "错误信息: ${ERROR}"
    else
        echo "响应: ${RESPONSE}"
    fi
fi

echo ""
