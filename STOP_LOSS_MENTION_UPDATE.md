# 止损通知@提醒功能更新

## 更新内容

已为止损通知添加@提醒功能，当触发止损时会@指定用户。

## 修改文件

- `src/main/java/com/ltp/peter/augtrade/service/FeishuNotificationService.java`

## 具体变更

在 `notifyStopLossOrTakeProfit` 方法中，为止损和止盈通知都启用了@提醒功能：

```java
String message = buildCardMessage(
    emoji + " " + type + "通知",
    color,
    String.format(...),
    true  // ✅ 启用@提醒
);
```

## 功能说明

现在以下通知都会@您：

1. ✅ **开仓通知** - 已支持@提醒
2. ✅ **平仓通知** - 已支持@提醒
3. ✅ **止损通知** - 新增@提醒支持 ⭐
4. ✅ **止盈通知** - 新增@提醒支持 ⭐
5. ✅ **信号反转平仓通知** - 已支持@提醒

## 配置要求

确保在 `application.yml` 中配置了以下参数：

```yaml
feishu:
  notification:
    enabled: true
  mention:
    enabled: true      # 启用@提醒
    user-id: "your_user_id"  # 您的飞书用户ID
```

## 工作原理

- 当 `feishu.mention.enabled=true` 时，系统会在通知消息中添加 `<at id=用户ID></at>` 标签
- 这将会在飞书群中@指定的用户，确保重要通知不会错过
- 止损通知使用橙色卡片（`orange`），止盈通知使用绿色卡片（`green`）

## 测试建议

1. 确认配置文件中的 `feishu.mention.user-id` 正确
2. 重启应用使配置生效
3. 等待触发止损或止盈，验证是否收到@提醒

## 注意事项

- 如果 `feishu.mention.enabled=false`，则不会发送@提醒，但仍会发送通知消息
- 确保飞书机器人有权限在群中@用户
- 用户ID可以在飞书个人信息中查看

## 更新时间

2026-01-09 22:16
