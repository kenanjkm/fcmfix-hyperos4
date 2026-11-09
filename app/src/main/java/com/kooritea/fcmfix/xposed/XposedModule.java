package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    public static Set<String> allowList = null;
    static final String TAG = "FcmFix";
    private static final HashMap<String, Object> config = new HashMap<>();

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
    private static Thread loadConfigThread = null;

    /**
     * HyperOS: after GMS delivers an FCM message to an allowed app, the app gets a short
     * "delivery window" in which the MIUI freezer (Greeze) and auto-start checks let it run
     * (receiver, FirebaseMessagingService start/bind). Nothing is scheduled; the window just
     * expires. This keeps the bypass tied to a real FCM message instead of allowing the app
     * to start in the background at any time.
     */
    protected static final long FCM_WINDOW_MS = 20000L;
    private static final ConcurrentHashMap<String, Long> fcmWindowPackages = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> fcmWindowAppIds = new ConcurrentHashMap<>();

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    private static void callAllOnCanReadConfig() {
        initReceiver();
        if ("android".equals(getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        Log.d(TAG, text);
        if (isDiagnosticsLog) {
            Intent log = new Intent("com.kooritea.fcmfix.log");
            log.putExtra("text", "[" + getSelfPackageName() + "]" + text);

            try {
                context.sendBroadcast(log);
            } catch (Throwable e) {
                XposedBridge.log("[fcmfix] [" + getSelfPackageName() + "]" + text);
            }
        } else {
            XposedBridge.log("[fcmfix] [" + getSelfPackageName() + "]" + text);
        }
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if ("com.kooritea.fcmfix".equals(packageName)) {
            return true;
        }
        if (allowList != null) {
            return allowList.contains(packageName);
        }
        return false;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if (config.get("init") == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    protected static void onUpdateConfig() {
        if (loadConfigThread == null) {
            loadConfigThread = new Thread() {
                @Override
                public void run() {
                    super.run();
                    try {
                        SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
                        if (remotePreferences == null) {
                            throw new IllegalStateException("remotePreferences 不可用");
                        }
                        allowList = remotePreferences.getStringSet("allowList", allowList == null ? new HashSet<>() : allowList);
                        if (allowList != null && "android".equals(getSelfPackageName())) {
                            printLog("[Modern Xposed API]onUpdateConfig allowList size: " + allowList.size());
                        }
                        config.put("disableAutoCleanNotification", remotePreferences.getBoolean("disableAutoCleanNotification", false));
                        config.put("includeIceBoxDisableApp", remotePreferences.getBoolean("includeIceBoxDisableApp", false));
                        config.put("noResponseNotification", remotePreferences.getBoolean("noResponseNotification", false));
                        config.put("keepGmsAwake", remotePreferences.getBoolean("keepGmsAwake", true));
                        config.put("init", true);
                    } catch (Throwable e) {
                        printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static void onUninstallFcmfix() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("com.kooritea.fcmfix.update.config");
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                        }
                    }
                }, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && "com.kooritea.fcmfix".equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmfix();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmfixChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmfix") == null) {
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected static void openFcmWindow(String packageName, int uid) {
        long until = SystemClock.elapsedRealtime() + FCM_WINDOW_MS;
        if (packageName != null) {
            fcmWindowPackages.put(packageName, until);
        }
        if (uid >= 10000) {
            fcmWindowAppIds.put(uid % 100000, until);
        }
    }

    protected static boolean inFcmWindow(String packageName) {
        if (packageName == null) {
            return false;
        }
        Long until = fcmWindowPackages.get(packageName);
        if (until == null) {
            return false;
        }
        if (until > SystemClock.elapsedRealtime()) {
            return true;
        }
        fcmWindowPackages.remove(packageName, until);
        return false;
    }

    protected static boolean inFcmWindowUid(int uid) {
        if (uid < 10000) {
            return false;
        }
        Integer appId = uid % 100000;
        Long until = fcmWindowAppIds.get(appId);
        if (until == null) {
            return false;
        }
        if (until > SystemClock.elapsedRealtime()) {
            return true;
        }
        fcmWindowAppIds.remove(appId, until);
        return false;
    }

    protected static String getTargetPackage(Intent intent) {
        if (intent == null) {
            return null;
        }
        return intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
