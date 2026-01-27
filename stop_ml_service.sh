#!/bin/bash
# ML预测服务停止脚本

echo "============================================"
echo "  停止ML预测服务"
echo "============================================"

# 查找运行在5001端口的进程
PID=$(lsof -ti:5001)

if [ -z "$PID" ]; then
    echo "⚠️  ML预测服务未运行"
    exit 0
fi

echo ""
echo "找到ML预测服务进程: PID=$PID"
echo "停止服务..."

# 停止进程
kill $PID

# 等待进程结束
sleep 2

# 检查是否成功停止
PID_CHECK=$(lsof -ti:5001)
if [ -z "$PID_CHECK" ]; then
    echo ""
    echo "✅ ML预测服务已停止"
    echo ""
else
    echo ""
    echo "⚠️  进程未正常停止，尝试强制停止..."
    kill -9 $PID
    sleep 1
    echo "✅ ML预测服务已强制停止"
    echo ""
fi
