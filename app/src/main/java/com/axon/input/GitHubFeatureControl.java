package com.axon.input;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GitHub 版本化远程功能控制。
 *
 * disable/<version>.json: 功能隐藏 / 禁用 / 强制开关 / 只读 / 快捷键阻断。
 * runtime/<version>.json: 入口密码、快捷键、云工坊、配置导入导出、外链等运行能力。
 * ui/<version>.json: 页面级 UI 元素显示控制。
 * local/<version>.json: 对 portable preference 白名单执行持久化云端覆盖。
 *
 * 这里不执行任意代码，不接受任意 SharedPreferences key；local override 只能落到
 * PortableConfigPreferenceSchema 已明确允许的字段，避免云端 JSON 演变成未审计代码执行入口。
 */
final class GitHubFeatureControl {
    private static volatile FeaturePolicy featurePolicy = FeaturePolicy.EMPTY;
    private static volatile RuntimePolicy runtimePolicy = RuntimePolicy.DEFAULT;
    private static volatile UiPolicy uiPolicy = UiPolicy.DEFAULT;
    private static volatile LocalPolicy localPolicy = LocalPolicy.EMPTY;

    private GitHubFeatureControl() {}

    static void installRequired(
            Context context,
            String expectedVersionName,
            long expectedVersionCode,
            JSONObject disableJson,
            JSONObject runtimeJson,
            JSONObject uiJson,
            JSONObject localJson) {
        FeaturePolicy features = FeaturePolicy.parse(disableJson, expectedVersionName, expectedVersionCode);
        RuntimePolicy runtime = RuntimePolicy.parse(runtimeJson, expectedVersionName, expectedVersionCode);
        UiPolicy ui = UiPolicy.parse(uiJson, expectedVersionName, expectedVersionCode);
        LocalPolicy local = LocalPolicy.parse(localJson, expectedVersionName, expectedVersionCode);

        featurePolicy = features;
        runtimePolicy = runtime;
        uiPolicy = ui;
        localPolicy = local;
        enforce(context);
    }

    static boolean isFeatureHidden(String id) {
        return id != null && featurePolicy.hidden.contains(id);
    }

    static boolean isFeatureBlocked(String id) {
        if (id == null) return false;
        FeaturePolicy p = featurePolicy;
        return p.disabled.contains(id)
                || p.forcedOff.contains(id)
                || p.forcedOn.contains(id)
                || p.readOnly.contains(id);
    }

    static boolean isFeatureDisabled(String id) {
        if (id == null) return false;
        FeaturePolicy p = featurePolicy;
        return p.disabled.contains(id) || p.forcedOff.contains(id);
    }

    static boolean isShortcutBlocked(String id) {
        if (!runtimePolicy.featureShortcutsEnabled) return true;
        if (id == null) return false;
        return featurePolicy.shortcutBlocked.contains(id) || isFeatureBlocked(id) || isFeatureHidden(id);
    }

    static boolean entryPasswordEnabled() { return runtimePolicy.entryPasswordEnabled; }
    static boolean cloudWorkshopEnabled() { return runtimePolicy.cloudWorkshopEnabled; }
    static boolean configImportEnabled() { return runtimePolicy.configImportEnabled; }
    static boolean configExportEnabled() { return runtimePolicy.configExportEnabled; }
    static boolean externalLinksEnabled() { return runtimePolicy.externalLinksEnabled; }
    static boolean featureShortcutsEnabled() { return runtimePolicy.featureShortcutsEnabled; }
    static boolean applyPolicyOnResume() { return runtimePolicy.applyPolicyOnResume; }

    static boolean isUiHidden(String id) {
        return id != null && uiPolicy.hiddenIds.contains(id);
    }

    static String uiText(String id, String fallback) {
        if (id == null) return fallback;
        String value = uiPolicy.textOverrides.optString(id, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    /** 把云端强制状态和 local preference 覆盖应用到本地。 */
    static void enforce(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        localPolicy.apply(app);
        if (!runtimePolicy.entryPasswordEnabled) {
            OverlayState.setEntryAuthorized(app, true);
        }

        FeaturePolicy p = featurePolicy;
        for (String id : p.disabled) FeatureShortcutStore.forceSetFeatureEnabled(app, id, false);
        for (String id : p.forcedOff) FeatureShortcutStore.forceSetFeatureEnabled(app, id, false);
        for (String id : p.forcedOn) FeatureShortcutStore.forceSetFeatureEnabled(app, id, true);

        if (runtimePolicy.clearBlockedShortcutBindings) {
            for (String id : FeatureShortcutStore.allFeatureIds()) {
                if (isShortcutBlocked(id)) FeatureShortcutStore.clearBindingSilently(app, id);
            }
        }
    }

    static void applyToActivity(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        applyViewPolicy(activity, activity.getWindow().getDecorView());
    }

    private static void applyViewPolicy(Activity activity, View view) {
        if (view == null) return;
        Object tag = view.getTag();
        if (view instanceof Switch && tag instanceof Integer) {
            String id = FeatureShortcutStore.featureIdForLabelRes((Integer) tag);
            if (id != null) {
                if (isFeatureBlocked(id)) view.setEnabled(false);
                if (isFeatureHidden(id)) {
                    View target = view;
                    View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
                    if (parent != null && parent.getParent() instanceof View) target = (View) parent.getParent();
                    target.setVisibility(View.GONE);
                }
            }
        }
        if (tag instanceof String && ((String) tag).startsWith("axon_feature_shortcut:")) {
            String id = ((String) tag).substring("axon_feature_shortcut:".length());
            if (isShortcutBlocked(id)) view.setVisibility(View.GONE);
        }
        if (view instanceof TextView) {
            String text = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString();
            if ((!runtimePolicy.cloudWorkshopEnabled || isUiHidden("cloud_config_center"))
                    && text.equals(activity.getString(R.string.cloud_config_center))) {
                view.setVisibility(View.GONE);
            }
            if ((!runtimePolicy.configExportEnabled || isUiHidden("config_export"))
                    && text.equals(activity.getString(R.string.config_export))) {
                view.setVisibility(View.GONE);
            }
            if ((!runtimePolicy.configImportEnabled || isUiHidden("config_import"))
                    && text.equals(activity.getString(R.string.config_import))) {
                view.setVisibility(View.GONE);
            }
            if (isUiHidden("html_guide") && text.equals(activity.getString(R.string.html_guide_link))) {
                view.setVisibility(View.GONE);
            }
            if ((!runtimePolicy.externalLinksEnabled || isUiHidden("kook_join"))
                    && text.equals(activity.getString(R.string.kook_join_link))) {
                view.setVisibility(View.GONE);
            }
            if (isUiHidden("configuration_section")
                    && text.equals(activity.getString(R.string.section_configuration))) {
                view.setVisibility(View.GONE);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyViewPolicy(activity, group.getChildAt(i));
        }
    }

    private static Set<String> parseFeatureSet(JSONObject json, String key) {
        JSONArray array = json.optJSONArray(key);
        if (array == null || array.length() == 0) return Collections.emptySet();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "").trim();
            if (id.isEmpty() || !FeatureShortcutStore.isKnownFeatureId(id)) {
                throw new IllegalStateException("unknown cloud feature id: " + id);
            }
            out.add(id);
        }
        return Collections.unmodifiableSet(out);
    }

    private static void validateVersion(JSONObject json, String expectedName, long expectedCode, String kind) {
        if (json == null) throw new IllegalStateException(kind + " cloud policy missing");
        if (json.optInt("schema", 0) != 1 || json.optLong("revision", 0L) <= 0L) {
            throw new IllegalStateException("invalid " + kind + " cloud policy");
        }
        String name = json.optString("versionName", "").trim();
        long code = json.optLong("versionCode", -1L);
        if (!expectedName.equals(name) || expectedCode != code) {
            throw new IllegalStateException(kind + " cloud policy version mismatch");
        }
    }

    private static final class FeaturePolicy {
        static final FeaturePolicy EMPTY = new FeaturePolicy(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

        final Set<String> hidden;
        final Set<String> disabled;
        final Set<String> forcedOff;
        final Set<String> forcedOn;
        final Set<String> readOnly;
        final Set<String> shortcutBlocked;

        FeaturePolicy(Set<String> hidden, Set<String> disabled, Set<String> forcedOff,
                      Set<String> forcedOn, Set<String> readOnly, Set<String> shortcutBlocked) {
            this.hidden = hidden;
            this.disabled = disabled;
            this.forcedOff = forcedOff;
            this.forcedOn = forcedOn;
            this.readOnly = readOnly;
            this.shortcutBlocked = shortcutBlocked;
        }

        static FeaturePolicy parse(JSONObject json, String versionName, long versionCode) {
            validateVersion(json, versionName, versionCode, "feature-disable");
            FeaturePolicy policy = new FeaturePolicy(
                    parseFeatureSet(json, "hiddenFeatureIds"),
                    parseFeatureSet(json, "disabledFeatureIds"),
                    parseFeatureSet(json, "forcedOffFeatureIds"),
                    parseFeatureSet(json, "forcedOnFeatureIds"),
                    parseFeatureSet(json, "readOnlyFeatureIds"),
                    parseFeatureSet(json, "shortcutBlockedFeatureIds"));
            HashSet<String> conflict = new HashSet<>(policy.forcedOff);
            conflict.retainAll(policy.forcedOn);
            if (!conflict.isEmpty()) throw new IllegalStateException("cloud feature force conflict: " + conflict);
            return policy;
        }
    }

    private static final class RuntimePolicy {
        static final RuntimePolicy DEFAULT = new RuntimePolicy(true, true, true, true, true, true, true, true);
        final boolean entryPasswordEnabled;
        final boolean featureShortcutsEnabled;
        final boolean cloudWorkshopEnabled;
        final boolean configImportEnabled;
        final boolean configExportEnabled;
        final boolean externalLinksEnabled;
        final boolean applyPolicyOnResume;
        final boolean clearBlockedShortcutBindings;

        RuntimePolicy(boolean entryPasswordEnabled, boolean featureShortcutsEnabled, boolean cloudWorkshopEnabled,
                      boolean configImportEnabled, boolean configExportEnabled, boolean externalLinksEnabled,
                      boolean applyPolicyOnResume, boolean clearBlockedShortcutBindings) {
            this.entryPasswordEnabled = entryPasswordEnabled;
            this.featureShortcutsEnabled = featureShortcutsEnabled;
            this.cloudWorkshopEnabled = cloudWorkshopEnabled;
            this.configImportEnabled = configImportEnabled;
            this.configExportEnabled = configExportEnabled;
            this.externalLinksEnabled = externalLinksEnabled;
            this.applyPolicyOnResume = applyPolicyOnResume;
            this.clearBlockedShortcutBindings = clearBlockedShortcutBindings;
        }

        static RuntimePolicy parse(JSONObject json, String versionName, long versionCode) {
            validateVersion(json, versionName, versionCode, "runtime");
            return new RuntimePolicy(
                    json.optBoolean("entryPasswordEnabled", true),
                    json.optBoolean("featureShortcutsEnabled", true),
                    json.optBoolean("cloudWorkshopEnabled", true),
                    json.optBoolean("configImportEnabled", true),
                    json.optBoolean("configExportEnabled", true),
                    json.optBoolean("externalLinksEnabled", true),
                    json.optBoolean("applyPolicyOnResume", true),
                    json.optBoolean("clearBlockedShortcutBindings", true));
        }
    }

    private static final class UiPolicy {
        static final UiPolicy DEFAULT = new UiPolicy(Collections.emptySet(), new JSONObject());
        final Set<String> hiddenIds;
        final JSONObject textOverrides;

        UiPolicy(Set<String> hiddenIds, JSONObject textOverrides) {
            this.hiddenIds = hiddenIds;
            this.textOverrides = textOverrides;
        }

        static UiPolicy parse(JSONObject json, String versionName, long versionCode) {
            validateVersion(json, versionName, versionCode, "ui");
            JSONArray hidden = json.optJSONArray("hiddenUiIds");
            LinkedHashSet<String> hiddenIds = new LinkedHashSet<>();
            if (hidden != null) {
                for (int i = 0; i < hidden.length(); i++) {
                    String id = hidden.optString(i, "").trim();
                    if (!id.isEmpty()) hiddenIds.add(id);
                }
            }
            JSONObject texts = json.optJSONObject("textOverrides");
            return new UiPolicy(Collections.unmodifiableSet(hiddenIds), texts == null ? new JSONObject() : texts);
        }
    }

    private static final class LocalPolicy {
        static final LocalPolicy EMPTY = new LocalPolicy(new JSONObject(), Collections.emptySet(), false);
        final JSONObject preferences;
        final Set<String> removeKeys;
        final boolean commitSynchronously;

        LocalPolicy(JSONObject preferences, Set<String> removeKeys, boolean commitSynchronously) {
            this.preferences = preferences;
            this.removeKeys = removeKeys;
            this.commitSynchronously = commitSynchronously;
        }

        static LocalPolicy parse(JSONObject json, String versionName, long versionCode) {
            validateVersion(json, versionName, versionCode, "local");
            JSONObject preferences = json.optJSONObject("preferenceOverrides");
            if (preferences == null) preferences = new JSONObject();
            Iterator<String> keys = preferences.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (PortableConfigPreferenceSchema.expectedType(key) == null) {
                    throw new IllegalStateException("non-portable cloud preference: " + key);
                }
            }
            JSONArray remove = json.optJSONArray("removePreferenceKeys");
            LinkedHashSet<String> removeKeys = new LinkedHashSet<>();
            if (remove != null) {
                for (int i = 0; i < remove.length(); i++) {
                    String key = remove.optString(i, "").trim();
                    if (PortableConfigPreferenceSchema.expectedType(key) == null) {
                        throw new IllegalStateException("non-portable cloud remove key: " + key);
                    }
                    removeKeys.add(key);
                }
            }
            return new LocalPolicy(preferences, Collections.unmodifiableSet(removeKeys),
                    json.optBoolean("commitSynchronously", true));
        }

        void apply(Context context) {
            SharedPreferences.Editor editor = AppPreferences.get(context).edit();
            boolean changed = false;
            for (String key : removeKeys) {
                editor.remove(key);
                changed = true;
            }
            Iterator<String> keys = preferences.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String type = PortableConfigPreferenceSchema.expectedType(key);
                try {
                    JSONObject entry = new JSONObject();
                    entry.put("type", type);
                    entry.put("value", preferences.get(key));
                    PortableConfigPreferenceSchema.decodeInto(editor, key, entry, true);
                    changed = true;
                } catch (IOException | JSONException error) {
                    throw new IllegalStateException("invalid cloud local override: " + key, error);
                }
            }
            if (!changed) return;
            if (commitSynchronously) {
                if (!editor.commit()) throw new IllegalStateException("cloud local overrides failed to persist");
            } else {
                editor.apply();
            }
        }
    }
}
