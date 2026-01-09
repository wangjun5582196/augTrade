#!/bin/bash

echo "======================================"
echo "安装回测功能"
echo "======================================"

# 数据库配置（从application.yml读取）
DB_NAME="test"
DB_USER="root"
DB_PASS="12345678"

echo ""
echo "步骤1: 创建数据库表..."
echo "--------------------------------------"

# 执行SQL脚本
cat create_backtest_tables.sql | docker exec -i mysql mysql -u${DB_USER} -p${DB_PASS} ${DB_NAME} 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✅ 数据库表创建成功"
else
    echo "⚠️  无法使用Docker执行，尝试直接连接..."
    # 如果docker命令失败，尝试直接连接
    if command -v mysql &> /dev/null; then
        mysql -u${DB_USER} -p${DB_PASS} ${DB_NAME} < create_backtest_tables.sql
        if [ $? -eq 0 ]; then
            echo "✅ 数据库表创建成功"
        else
            echo "❌ 数据库表创建失败，请手动执行 create_backtest_tables.sql"
            echo ""
            echo "手动执行方式："
            echo "  mysql -u${DB_USER} -p${DB_PASS} ${DB_NAME} < create_backtest_tables.sql"
            echo ""
            echo "或者复制以下SQL在数据库客户端中执行："
            echo "--------------------------------------"
            cat create_backtest_tables.sql
            echo "--------------------------------------"
        fi
    else
        echo "❌ MySQL客户端未安装"
        echo ""
        echo "请手动在数据库中执行以下SQL："
        echo "--------------------------------------"
        cat create_backtest_tables.sql
        echo "--------------------------------------"
    fi
fi

echo ""
echo "步骤2: 重启应用..."
echo "--------------------------------------"

# 检查是否有restart.sh脚本
if [ -f "./restart.sh" ]; then
    echo "使用restart.sh重启应用..."
    ./restart.sh
else
    echo "查找并停止现有Java进程..."
    pkill -f "aug-trade" || true
    sleep 2
    
    echo "启动应用..."
    if [ -f "target/aug-trade-1.0-SNAPSHOT.jar" ]; then
        nohup java -jar target/aug-trade-1.0-SNAPSHOT.jar > logs/app.log 2>&1 &
        echo "✅ 应用已启动"
    else
        echo "⚠️  JAR文件不存在，使用Maven启动..."
        mvn spring-boot:run &
    fi
fi

echo ""
echo "======================================"
echo "安装完成！"
echo "======================================"
echo ""
echo "访问回测页面: http://localhost:3131/backtest.html"
echo ""
echo "注意: 应用的context-path是/api，所以API地址为:"
echo "  http://localhost:3131/api/backtest/..."
echo ""
