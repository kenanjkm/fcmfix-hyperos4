package com.kooritea.fcmfix.xposed;

import android.content.Intent;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * HyperOS (tested against HyperOS 4 / Android 17 miui-services.jar): Greeze is the MIUI app freezer.
 * A frozen app cannot run its FCM receiver or FirebaseMessagingService, and on CN ROMs Greeze also
 * quick-freezes GMS itself while the screen is off.
 *
 * Deep sleep: nothing here acquires wakelocks or schedules alarms. Thawing only happens as a direct
 * reaction to an FCM broadcast that GMS is already delivering, and the delivery window simply expires.
 */
public class GreezeFix extends XposedModule {
    private static final String GREEZE = "com.miui.server.greeze.GreezeManagerService";
    private static final String IMMOBULUS = "com.miui.server.greeze.AurogonImmobulusMode";
    private static final String GMS = "com.google.android.gms";

    private static Class<?> greezeClass;
    private static volatile int gmsAppId = -1;

    public GreezeFix(ClassLoader classLoader) {
        super(classLoader);
        greezeClass = XposedHelpers.findClassIfExists(GREEZE, classLoader);
        if (greezeClass == null) {
            printLog("No Such Class " + GREEZE);
            return;
        }
        tryHook("isRestrictReceiver", this::hookIsRestrictReceiver);
        tryHook("isRestrictBackgroundAction", this::hookIsRestrictBackgroundAction);
        tryHook("freezeAction", this::hookFreezeAction);
        tryHook("freezeUids", this::hookFreezeUids);
        tryHook("updateGmsNetStatus", this::hookUpdateGmsNetStatus);
        tryHook("triggerQuickFreezeAction", this::hookQuickFreeze);
    }

    private interface Hook {
        void run() throws Throwable;
    }

    private void tryHook(String name, Hook hook) {
        try {
            hook.run();
            printLog("[fcmfix] hooked " + GREEZE + "." + name);
        } catch (Throwable e) {
            printLog("No Such Method Greeze." + name + ": " + e);
        }
    }

    /** Called from BroadcastFix when GMS sends an FCM message to an allowed app. */
    public static void unfreeze(String target) {
        int uid = getUid(target);
        openFcmWindow(target, uid);
        thaw(uid);
    }

    // boolean isRestrictReceiver(Intent intent, int callerUid, String callerPkg, int calleeUid, String calleePkg)
    private void hookIsRestrictReceiver() throws Throwable {
        Method m = XposedHelpers.findMethodExact(greezeClass, "isRestrictReceiver",
                Intent.class, int.class, String.class, int.class, String.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.args[0];
                int callerUid = (int) param.args[1];
                String callerPkg = (String) param.args[2];
                int calleeUid = (int) param.args[3];
                String calleePkg = (String) param.args[4];
                boolean fcm = intent != null && isFCMIntent(intent) && isGms(callerUid, callerPkg) && targetIsAllow(calleePkg);
                if (fcm) {
                    openFcmWindow(calleePkg, calleeUid);
                }
                if (fcm || inFcmWindow(calleePkg) || inFcmWindowUid(calleeUid)) {
                    thaw(calleeUid);
                    printLog("Greeze receiver unrestricted: " + calleePkg, true);
                    param.setResult(false);
                }
            }
        });
    }

    // boolean isRestrictBackgroundAction(String what, int callerUid, String callerPkg, int calleeUid, String calleePkg)
    // Despite the name it returns true when the action is ALLOWED (Greeze then thaws the callee).
    private void hookIsRestrictBackgroundAction() throws Throwable {
        Method m = XposedHelpers.findMethodExact(greezeClass, "isRestrictBackgroundAction",
                String.class, int.class, String.class, int.class, String.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int callerUid = (int) param.args[1];
                String callerPkg = (String) param.args[2];
                int calleeUid = (int) param.args[3];
                String calleePkg = (String) param.args[4];
                if (inFcmWindow(calleePkg) || inFcmWindowUid(calleeUid)
                        || (isGms(callerUid, callerPkg) && targetIsAllow(calleePkg))) {
                    thaw(calleeUid);
                    printLog("Greeze " + param.args[0] + " allowed: " + calleePkg, true);
                    param.setResult(true);
                }
            }
        });
    }

    // boolean freezeAction(int uid, int fromWho, String reason, boolean, boolean)
    private void hookFreezeAction() throws Throwable {
        Method m = XposedHelpers.findMethodExact(greezeClass, "freezeAction",
                int.class, int.class, String.class, boolean.class, boolean.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (shouldNotFreeze((int) param.args[0])) {
                    param.setResult(false);
                }
            }
        });
    }

    // List<Integer> freezeUids(int[] uids, long timeout, int fromWho, String reason, boolean checkAudioGps)
    private void hookFreezeUids() throws Throwable {
        Method m = XposedHelpers.findMethodExact(greezeClass, "freezeUids",
                int[].class, long.class, int.class, String.class, boolean.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int[] uids = (int[]) param.args[0];
                if (uids == null) {
                    return;
                }
                int keep = 0;
                for (int uid : uids) {
                    if (!shouldNotFreeze(uid)) keep++;
                }
                if (keep == uids.length) {
                    return;
                }
                int[] filtered = new int[keep];
                int i = 0;
                for (int uid : uids) {
                    if (!shouldNotFreeze(uid)) filtered[i++] = uid;
                }
                param.args[0] = filtered;
            }
        });
    }

    // CN ROM: PowerKeeper's GmsObserver calls updateGmsNetStatus(true) when Google is unreachable,
    // which makes Greeze quick-freeze GMS while the screen is off.
    private void hookUpdateGmsNetStatus() throws Throwable {
        Method m = XposedHelpers.findMethodExact(greezeClass, "updateGmsNetStatus", boolean.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.args[0]) && getBooleanConfig("keepGmsAwake", true)) {
                    printLog("Ignore Greeze GMS network limit", true);
                    param.args[0] = false;
                }
            }
        });
    }

    // void AurogonImmobulusMode.triggerQuickFreezeAction(int uid)
    private void hookQuickFreeze() throws Throwable {
        Class<?> immobulus = XposedHelpers.findClass(IMMOBULUS, classLoader);
        Method m = XposedHelpers.findMethodExact(immobulus, "triggerQuickFreezeAction", int.class);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isGmsUid((int) param.args[0]) && getBooleanConfig("keepGmsAwake", true)) {
                    param.setResult(null);
                }
            }
        });
    }

    private boolean shouldNotFreeze(int uid) {
        return inFcmWindowUid(uid) || (isGmsUid(uid) && getBooleanConfig("keepGmsAwake", true));
    }

    private static boolean isGms(int uid, String packageName) {
        return GMS.equals(packageName) || isGmsUid(uid);
    }

    private static boolean isGmsUid(int uid) {
        if (uid < 10000) {
            return false;
        }
        if (gmsAppId < 0) {
            int gmsUid = getUid(GMS);
            if (gmsUid >= 10000) {
                gmsAppId = gmsUid % 100000;
            }
        }
        return gmsAppId > 0 && uid % 100000 == gmsAppId;
    }

    private static int getUid(String packageName) {
        if (packageName == null || context == null) {
            return -1;
        }
        try {
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Uses Greeze's own async thaw so it is safe to call while ActivityManagerService locks are held. */
    private static void thaw(int uid) {
        if (uid < 10000 || greezeClass == null) {
            return;
        }
        try {
            Object service = XposedHelpers.callStaticMethod(greezeClass, "getService");
            if (service != null) {
                XposedHelpers.callMethod(service, "thawUidAsync", uid, 1000, "fcmfix");
            }
        } catch (Throwable e) {
            printLog("Greeze thaw failed: " + e);
        }
    }
}
