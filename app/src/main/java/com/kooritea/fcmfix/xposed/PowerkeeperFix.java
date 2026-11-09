package com.kooritea.fcmfix.xposed;

import android.content.Context;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Field;
import java.util.List;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class PowerkeeperFix extends XposedModule {
    public PowerkeeperFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHook();
    }

    // Each part is independent: HyperOS 4 PowerKeeper no longer has the millet package
    // (MilletConfig / MilletPolicy), and a missing class must not stop the other hooks.
    protected void startHook(){
        try {
            Class<?> MilletConfig = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletConfig", classLoader);
            if (MilletConfig != null) {
                XposedHelpers.setStaticBooleanField(MilletConfig, "isGlobal", true);
                printLog("Set com.miui.powerkeeper.millet.MilletConfig.isGlobal to true");
            } else {
                printLog("No Such Class com.miui.powerkeeper.millet.MilletConfig");
            }
        } catch (Throwable e) {
            printLog("MilletConfig.isGlobal: " + e);
        }

        try {
            // Nested class: binary name uses '$'.
            Class<?> Misc = XposedHelpers.findClass("com.miui.powerkeeper.provider.SimpleSettings$Misc", classLoader);
            printLog("[fcmfix] start hook com.miui.powerkeeper.provider.SimpleSettings$Misc.getBoolean");
            XposedUtils.findAndHookMethod(Misc, "getBoolean", 3, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    if("gms_control".equals((String) methodHookParam.args[1])) {
                        printLog("Success: Success: PowerKeeper GMS Limitation. ", true);
                        methodHookParam.setResult(false);
                    }
                }
            });
        } catch (Throwable e) {
            printLog("No Such Method com.miui.powerkeeper.provider.SimpleSettings$Misc.getBoolean: " + e);
        }

        try {
            Class<?> MilletPolicy = XposedHelpers.findClassIfExists("com.miui.powerkeeper.millet.MilletPolicy", classLoader);
            if (MilletPolicy == null) {
                printLog("No Such Class com.miui.powerkeeper.millet.MilletPolicy");
                return;
            }

            XC_MethodHook methodHook = new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    Field[] declaredFields = null;
                    super.afterHookedMethod(methodHookParam);
                    boolean mSystemBlackList = false;
                    boolean whiteApps = false;
                    boolean mDataWhiteList = false;

                    for (Field field : MilletPolicy.getDeclaredFields()) {
                        if (field.getName().equals("mSystemBlackList")) {
                            mSystemBlackList = true;
                        } else if (field.getName().equals("whiteApps")) {
                            whiteApps = true;
                        } else if (field.getName().equals("mDataWhiteList")) {
                            mDataWhiteList = true;
                        }
                    }

                    if (mSystemBlackList) {
                        List blackList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mSystemBlackList");
                        blackList.remove("com.google.android.gms");
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mSystemBlackList", blackList);
                        printLog("Success: MilletPolicy mSystemBlackList.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.mSystemBlackList");
                    }
                    if (whiteApps) {
                        List whiteAppList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "whiteApps");
                        whiteAppList.remove("com.google.android.gms");
                        whiteAppList.remove("com.google.android.ext.services");
                        XposedHelpers.setObjectField(methodHookParam.thisObject, "whiteApps", whiteAppList);
                        printLog("Success: MilletPolicy whiteApps.");
                    } else {
                        printLog("Error: MilletPolicy. Field not found: com.miui.powerkeeper.millet.MilletPolicy.whiteApps");
                    }
                    if (mDataWhiteList) {
                        List dataWhiteList = (List) XposedHelpers.getObjectField(methodHookParam.thisObject, "mDataWhiteList");
                        dataWhiteList.add("com.google.android.gms");

                        XposedHelpers.setObjectField(methodHookParam.thisObject, "mDataWhiteList", dataWhiteList);
                        printLog("Success: MilletPolicy mDataWhiteList.");
                    }

                }
            };
            printLog("[fcmfix] start hook com.miui.powerkeeper.millet.MilletPolicy constructor");
            XposedHelpers.findAndHookConstructor(MilletPolicy, new Object[] {Context.class, methodHook});

        } catch (Throwable e){
            printLog("No Such Method com.miui.powerkeeper.millet.MilletPolicy.<init>: " + e);
        }
    }
}
