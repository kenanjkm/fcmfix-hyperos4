# fcmfix — HyperOS 4 fork

Fork of [kooritea/fcmfix](https://github.com/kooritea/fcmfix) adapted to **HyperOS 4 (Android 17, CN ROM)**.
Hook targets were checked against HyperOS 4 `services.jar`, `miui-services.jar` and PowerKeeper 4.2.00.

**Türkçe özet:** HyperOS 4'te dondurulmuş (Greeze) veya durdurulmuş uygulamalara FCM bildirimlerinin
ulaşması için düzenlenmiş sürüm. Arayüz Türkçe / English / 中文.

Changes compared to upstream:
- **GreezeFix (new):** the MIUI freezer no longer blocks FCM. When GMS sends an FCM message to an allowed
  app, the app is thawed and gets a 20 s delivery window (receiver + FirebaseMessagingService). Optional
  (default on): GMS itself is not quick-frozen / network-limited by Greeze when the screen is off.
- **AutoStartFix:** on HyperOS only FCM sent by GMS bypasses auto-start (upstream let every broadcast and
  service start of allowed apps through). Added the process-alive path `checkReceiverAppDealBroadcast`.
- **PowerkeeperFix:** fixed the nested class name (`SimpleSettings$Misc`) and a crash when the `millet`
  package is missing (HyperOS 4), which stopped all PowerKeeper hooks.
- **UI:** strings moved to resources; added English and Turkish.

Deep sleep: no wakelocks, alarms or periodic work are added. Thawing only happens in reaction to an FCM
broadcast GMS is already delivering, and the delivery window just expires.

LSPosed scope: **System Framework** (required). Battery and performance (`com.miui.powerkeeper`) is optional.

---

# fcmfix(Android 10-15 )

[![Android CI](https://github.com/kooritea/fcmfix/workflows/Android%20CI/badge.svg)](https://github.com/kooritea/fcmfix/actions)

让fcm/gcm唤醒未启动的应用进行发送通知  

### 附加功能

- 阻止Android系统在应用停止时自动移除通知栏的通知
- 在miui/hyperos(?)/OxygenOS15(?)/ColorOS15(?)上动态解除来自fcm的自启动限制
- 移除miui/hyperos对后台应用的通知限制
- 没有预期唤醒目标应用时发送提示通知

### lsposed作用域
- 在miui/hyperos上如果推送没有问题，就不需要勾选电量和性能

### 关于fcm

fcm是在Android中由google维护的一条介于google服务器与gms应用之间用于推送通知的长链接。  
一般的工作流程为应用服务器将消息发送到google服务器，google服务器将消息推送给gms应用，gms应用通过广播传递给应用，应用通过接收到的fcm消息决定是否发送通知和通知内容。  
其中gms通过fcm广播通知应用时，如果应用处于非运行状态，就会出现`Failed to broadcast to stopped app`，fcmfix主要就是解决这个问题。

### 已知问题

- 非miui/hyperos/OxygenOS15/ColorOS15系统可能需要给予目标应用类似允许自启动的权限，以及电池选项设置为不优化。
