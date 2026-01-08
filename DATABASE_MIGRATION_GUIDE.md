# 数据库迁移指南 - 添加移动止损字段

## ⚠️ 重要提示

在启动服务之前，**必须先执行数据库迁移**，否则会因为缺少`trailing_stop_enabled`字段而启动失败。

---

## 🔧 执行步骤

### 方法1：使用数据库客户端（推荐）

1. **打开您的MySQL客户端**
   - Navicat
   - DataGrip
   - MySQL Workbench
   - Sequel Pro
   - 或其他任何MySQL管理工具

2. **连接到数据库**
   - Host: localhost
   - Port: 3306
   - Database: test
   - Username: root
   - Password: 12345678

3. **执行以下SQL语句**

```sql
-- 添加移动止损字段
ALTER TABLE t_position 
ADD COLUMN trailing_stop_enabled TINYINT(1) DEFAULT 0 
COMMENT '是否启用移动止损：0-未启用，1-已启用' 
AFTER stop_loss_price;

-- 验证字段已添加
DESC t_position;
```

4. **确认结果**

执行`DESC t_position;`后，您应该看到类似输出：

```
+------------------------+--------------+------+-----+---------+-------+
| Field                  | Type         | Null | Key | Default | Extra |
+------------------------+--------------+------+-----+---------+-------+
| id                     | bigint       | NO   | PRI | NULL    | auto_increment |
| symbol                 | varchar(50)  | YES  |     | NULL    |       |
| direction              | varchar(10)  | YES  |     | NULL    |       |
| quantity               | decimal(20,8)| YES  |     | NULL    |       |
| avg_price              | decimal(20,2)| YES  |     | NULL    |       |
| current_price          | decimal(20,2)| YES  |     | NULL    |       |
| unrealized_pnl         | decimal(20,2)| YES  |     | NULL    |       |
| margin                 | decimal(20,2)| YES  |     | NULL    |       |
| leverage               | int          | YES  |     | NULL    |       |
| take_profit_price      | decimal(20,2)| YES  |     | NULL    |       |
| stop_loss_price        | decimal(20,2)| YES  |     | NULL    |       |
| trailing_stop_enabled  | tinyint(1)   | YES  |     | 0       |       | ✅ 新字段
| status                 | varchar(20)  | YES  |     | NULL    |       |
| open_time              | datetime     | YES  |     | NULL    |       |
| close_time             | datetime     | YES  |     | NULL    |       |
| create_time            | datetime     | YES  |     | NULL    |       |
| update_time            | datetime     | YES  |     | NULL    |       |
+------------------------+--------------+------+-----+---------+-------+
```

---

### 方法2：使用命令行

如果您的系统中安装了MySQL命令行工具：

```bash
# 查找MySQL命令位置
which mysql

# 或者
/usr/local/mysql/bin/mysql -u root -p

# 输入密码后执行
USE test;

ALTER TABLE t_position 
ADD COLUMN trailing_stop_enabled TINYINT(1) DEFAULT 0 
COMMENT '是否启用移动止损：0-未启用，1-已启用' 
AFTER stop_loss_price;

DESC t_position;
```

---

### 方法3：使用SQL文件

1. 找到项目根目录中的 `add_trailing_stop_field.sql` 文件
2. 在数据库客户端中选择"执行SQL文件"或"导入SQL"
3. 选择 `add_trailing_stop_field.sql` 文件
4. 执行

---

## ✅ 验证迁移成功

执行以下查询确认字段已添加：

```sql
SELECT COLUMN_NAME, DATA_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT 
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_SCHEMA = 'test' 
  AND TABLE_NAME = 't_position' 
  AND COLUMN_NAME = 'trailing_stop_enabled';
```

**预期结果**：

```
+------------------------+-----------+----------------+------------------------------------------+
| COLUMN_NAME            | DATA_TYPE | COLUMN_DEFAULT | COLUMN_COMMENT                           |
+------------------------+-----------+----------------+------------------------------------------+
| trailing_stop_enabled  | tinyint   | 0              | 是否启用移动止损：0-未启用，1-已启用      |
+------------------------+-----------+----------------+------------------------------------------+
```

---

## 🚀 迁移完成后

确认字段添加成功后，执行以下命令重启服务：

```bash
cd /Users/peterwang/IdeaProjects/AugTrade
./restart.sh
```

---

## 🔍 故障排除

### 问题1：字段已存在错误

```
Error: Duplicate column name 'trailing_stop_enabled'
```

**解决方案**：字段已经存在，无需重复添加，直接启动服务即可。

验证命令：
```sql
DESC t_position;
```

---

### 问题2：表不存在

```
Error: Table 'test.t_position' doesn't exist
```

**解决方案**：需要先创建表结构。执行：

```sql
-- 检查数据库是否存在
SHOW DATABASES LIKE 'test';

-- 如果不存在，创建数据库
CREATE DATABASE IF NOT EXISTS test;

-- 使用数据库
USE test;

-- 查看所有表
SHOW TABLES;
```

如果表确实不存在，请查看 `src/main/resources/sql/schema.sql` 文件先创建完整表结构。

---

### 问题3：权限不足

```
Error: Access denied for user 'root'@'localhost'
```

**解决方案**：
1. 确认密码正确
2. 确认用户有ALTER权限
3. 如果需要，授予权限：

```sql
GRANT ALL PRIVILEGES ON test.* TO 'root'@'localhost';
FLUSH PRIVILEGES;
```

---

## 📊 迁移后的效果

迁移成功后，移动止损功能将自动启用：

### 对于当前持仓（盈利$41）

系统将自动：
1. ✅ 检测盈利≥$30
2. ✅ 启用移动止损
3. ✅ 锁定70%利润（$28.7）
4. ✅ 跟踪价格上涨
5. ✅ 自动调整止损价

### 日志输出示例

```
2026-01-08 22:30:00 [TradingScheduler] 🔄 多头启用移动止损 - 
  当前价: $2641, 盈利: $41, 锁定利润: $28.7, 新止损价: $2628.7

2026-01-08 22:35:00 [TradingScheduler] 📈 多头移动止损更新 - 
  当前价: $2655, 盈利: $55, 止损价: $2628.7 -> $2645, 锁定利润: $45
```

---

## 🎯 下一步

迁移完成并重启服务后：

1. 查看日志确认移动止损启用
2. 监控持仓盈亏变化
3. 观察止损价自动调整
4. 等待达到$100目标或触发移动止损

---

**如有任何问题，请查看项目根目录下的 `TRAILING_STOP_USAGE.md` 文件获取详细使用说明。**
