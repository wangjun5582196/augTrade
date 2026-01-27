#!/bin/bash
# 从数据库导出K线数据用于ML训练

echo "============================================"
echo "  导出K线数据用于ML训练"
echo "============================================"

# 加载环境变量
source ~/.bash_profile

# 导出全量数据（1年）
echo ""
echo "📊 从MySQL导出最近1年的K线数据..."
mysql -h localhost -P 3306 -u root -p'12345678' test -e "
SELECT 
    timestamp,
    open_price,
    high_price,
    low_price,
    close_price,
    volume
FROM t_kline
WHERE 
    symbol = 'XAUTUSDT'
    AND timestamp >= DATE_SUB(NOW(), INTERVAL 12 MONTH)
ORDER BY timestamp ASC
" > data/raw_klines.csv

# 检查是否成功
if [ -f "data/raw_klines.csv" ]; then
    line_count=$(wc -l < data/raw_klines.csv)
    echo "✅ 导出成功！共 $line_count 行"
    echo ""
    echo "📁 文件位置: data/raw_klines.csv"
    echo ""
    echo "📌 下一步:"
    echo "  cd ml"
    echo "  python train_model.py"
else
    echo "❌ 导出失败！"
    exit 1
fi
