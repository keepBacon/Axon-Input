package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * GitHub 远程安全策略控制面。
 *
 * 旧版根目录 version.json / notice.json 保持兼容，不参与本类逻辑。
 * 新版本只读取 cloud-control/ 下的版本化策略。当前版本必须同时拿到：
 * security.json、versions/<version>.json、notices/<version>.json，否则启动 fail-closed。
 */
final class GitHubCloudPolicy {
    private static final String RAW_ROOT =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/cloud-control/";
    private static final String STATE_PREFS = "axon_github_cloud_policy_state";
    private static final String KEY_AUTH_FINGERPRINT = "entry_auth_fingerprint";
    private static final long STARTUP_TIMEOUT_MS = 4200L;

    private static volatile Context appContext;
    private static volatile SecurityPolicy securityPolicy;
    private static volatile VersionPolicy versionPolicy;
    private static volatile NoticePolicy noticePolicy;

    private GitHubCloudPolicy() {}

    static void bootstrapRequired(Context context) {
        if (context == null) throw new IllegalStateException("cloud policy context missing");
        Context app = context.getApplicationContext();
        appContext = app;

        String versionName = AppVersion.name(app);
        String safeVersion = sanitizeVersionSegment(versionName);
        String securityUrl = RAW_ROOT + "security.json";
        String versionUrl = RAW_ROOT + "versions/" + safeVersion + ".json";
        String noticeUrl = RAW_ROOT + "notices/" + safeVersion + ".json";

        AtomicReference<JSONObject> securityJson = new AtomicReference<>();
        AtomicReference<JSONObject> versionJson = new AtomicReference<>();
        AtomicReference<JSONObject> noticeJson = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(3);

        fetchRequired(app, securityUrl, securityJson, latch, "AxonPolicySecurity");
        fetchRequired(app, versionUrl, versionJson, latch, "AxonPolicyVersion");
        fetchRequired(app, noticeUrl, noticeJson, latch, "AxonPolicyNotice");

        boolean completed;
        try {
            completed = latch.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cloud policy bootstrap interrupted", error);
        }
        if (!completed || securityJson.get() == null || versionJson.get() == null || noticeJson.get() == null) {
            throw new IllegalStateException("required GitHub cloud policy unavailable");
        }

        SecurityPolicy parsedSecurity = SecurityPolicy.parse(securityJson.get());
        VersionPolicy parsedVersion = VersionPolicy.parse(versionJson.get());
        NoticePolicy parsedNotice = NoticePolicy.parse(noticeJson.get());

        long currentCode = AppVersion.code(app);
        if (!versionName.equals(parsedVersion.versionName) || currentCode != parsedVersion.versionCode) {
            throw new IllegalStateException("cloud version policy does not match this APK");
        }
        if (!versionName.equals(parsedNotice.versionName) || currentCode != parsedNotice.versionCode) {
            throw new IllegalStateException("cloud notice policy does not match this APK");
        }
        if (!parsedSecurity.enabled) {
            throw new SecurityException(nonEmpty(parsedSecurity.disabledMessage, "Axon Input has been disabled remotely"));
        }
        if (parsedSecurity.isVersionDenied(currentCode, versionName)) {
            throw new SecurityException(nonEmpty(parsedSecurity.deniedVersionMessage, "This Axon Input version is disabled"));
        }
        if (!parsedVersion.enabled || parsedVersion.maintenanceMode) {
            throw new SecurityException(nonEmpty(parsedVersion.disabledMessage, "This Axon Input version is unavailable"));
        }

        securityPolicy = parsedSecurity;
        versionPolicy = parsedVersion;
        noticePolicy = parsedNotice;

        // 云端密码一旦变化，旧的本地授权立即失效。无需手工维护“密码版本号”。
        SharedPreferences state = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        String savedFingerprint = state.getString(KEY_AUTH_FINGERPRINT, "");
        if (OverlayState.isEntryAuthorized(app)
                && !MessageDigest.isEqual(
                        savedFingerprint.getBytes(StandardCharsets.UTF_8),
                        parsedSecurity.passwordFingerprint.getBytes(StandardCharsets.UTF_8))) {
            OverlayState.setEntryAuthorized(app, false);
        }
    }

    private static void fetchRequired(
            Context context,
            String url,
            AtomicReference<JSONObject> out,
            CountDownLatch latch,
            String threadName) {
        Thread worker = new Thread(() -> {
            try {
                out.set(RemoteJson.get(context, url, true));
            } finally {
                latch.countDown();
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    static boolean matchesEntryPassword(String value) {
        SecurityPolicy policy = requireSecurity();
        boolean matched = policy.matchesPassword(value == null ? "" : value);
        if (matched) markPasswordAccepted();
        return matched;
    }

    private static void markPasswordAccepted() {
        Context app = appContext;
        SecurityPolicy policy = securityPolicy;
        if (app == null || policy == null) return;
        app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AUTH_FINGERPRINT, policy.passwordFingerprint)
                .apply();
    }

    static VersionPolicy version() {
        VersionPolicy policy = versionPolicy;
        if (policy == null) throw new IllegalStateException("version cloud policy not bootstrapped");
        return policy;
    }

    static NoticePolicy notice() {
        NoticePolicy policy = noticePolicy;
        if (policy == null) throw new IllegalStateException("notice cloud policy not bootstrapped");
        return policy;
    }

    private static SecurityPolicy requireSecurity() {
        SecurityPolicy policy = securityPolicy;
        if (policy == null) throw new IllegalStateException("security cloud policy not bootstrapped");
        return policy;
    }

    private static String sanitizeVersionSegment(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || !text.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("unsupported versionName for cloud-control path");
        }
        return text;
    }

    private static String nonEmpty(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }

    static final class VersionPolicy {
        final int schema;
        final long revision;
        final long versionCode;
        final String versionName;
        final boolean enabled;
        final boolean maintenanceMode;
        final String disabledMessage;
        final boolean forceUpdate;
        final long minSupportedVersionCode;
        final long latestVersionCode;
        final String latestVersionName;
        final String downloadUrl;
        final String updateTitle;
        final String updateMessage;
        final String changelog;
        final String updateLaterText;
        final String updateNowText;
        final String forceUpdateExitText;

        private VersionPolicy(JSONObject json) {
            schema = json.optInt("schema", 0);
            revision = json.optLong("revision", 0L);
            versionCode = json.optLong("versionCode", -1L);
            versionName = json.optString("versionName", "").trim();
            enabled = json.optBoolean("enabled", true);
            maintenanceMode = json.optBoolean("maintenanceMode", false);
            disabledMessage = json.optString("disabledMessage", "").trim();
            forceUpdate = json.optBoolean("forceUpdate", false);
            minSupportedVersionCode = json.optLong("minSupportedVersionCode", versionCode);
            latestVersionCode = json.optLong("latestVersionCode", versionCode);
            latestVersionName = json.optString("latestVersionName", versionName).trim();
            downloadUrl = json.optString("downloadUrl", "https://github.com/keepBacon/Axon-Input").trim();
            updateTitle = json.optString("updateTitle", "").trim();
            updateMessage = json.optString("updateMessage", "").trim();
            changelog = json.optString("changelog", "").trim();
            updateLaterText = json.optString("updateLaterText", "").trim();
            updateNowText = json.optString("updateNowText", "").trim();
            forceUpdateExitText = json.optString("forceUpdateExitText", "退出").trim();
        }

        static VersionPolicy parse(JSONObject json) {
            if (json == null) throw new IllegalStateException("version policy missing");
            VersionPolicy policy = new VersionPolicy(json);
            if (policy.schema != 1 || policy.revision <= 0 || policy.versionCode < 0 || policy.versionName.isEmpty()) {
                throw new IllegalStateException("invalid version policy");
            }
            if (policy.latestVersionCode < policy.versionCode || policy.latestVersionName.isEmpty()) {
                throw new IllegalStateException("invalid latest version policy");
            }
            return policy;
        }

        boolean updateAvailable(long currentCode) {
            return latestVersionCode > currentCode;
        }

        boolean updateIsForced(long currentCode) {
            return updateAvailable(currentCode) && (forceUpdate || currentCode < minSupportedVersionCode);
        }
    }

    static final class NoticePolicy {
        final int schema;
        final long revision;
        final long versionCode;
        final String versionName;
        final String id;
        final boolean enabled;
        final boolean showOnce;
        final String title;
        final String message;
        final String joinUrl;
        final String joinText;
        final String confirmText;
        final int waitSeconds;

        private NoticePolicy(JSONObject json) {
            schema = json.optInt("schema", 0);
            revision = json.optLong("revision", 0L);
            versionCode = json.optLong("versionCode", -1L);
            versionName = json.optString("versionName", "").trim();
            id = json.optString("id", "").trim();
            enabled = json.optBoolean("enabled", false);
            showOnce = json.optBoolean("showOnce", true);
            title = json.optString("title", "").trim();
            message = json.optString("message", "").trim();
            joinUrl = json.optString("joinUrl", "").trim();
            joinText = json.optString("joinText", "").trim();
            confirmText = json.optString("confirmText", "").trim();
            waitSeconds = Math.max(0, Math.min(30, json.optInt("waitSeconds", 3)));
        }

        static NoticePolicy parse(JSONObject json) {
            if (json == null) throw new IllegalStateException("notice policy missing");
            NoticePolicy policy = new NoticePolicy(json);
            if (policy.schema != 1 || policy.revision <= 0 || policy.versionCode < 0 || policy.versionName.isEmpty()) {
                throw new IllegalStateException("invalid notice policy");
            }
            if (policy.enabled && (policy.id.isEmpty() || policy.message.isEmpty())) {
                throw new IllegalStateException("enabled notice requires id and message");
            }
            return policy;
        }

        String durableId() {
            return versionName + ":" + id;
        }
    }

    private static final class SecurityPolicy {
        final int schema;
        final long revision;
        final boolean enabled;
        final String disabledMessage;
        final String deniedVersionMessage;
        final JSONArray deniedVersionCodes;
        final JSONArray deniedVersionNames;
        final PasswordPolicy password;
        final String passwordFingerprint;

        private SecurityPolicy(JSONObject json) {
            schema = json.optInt("schema", 0);
            revision = json.optLong("revision", 0L);
            enabled = json.optBoolean("enabled", true);
            disabledMessage = json.optString("disabledMessage", "").trim();
            deniedVersionMessage = json.optString("deniedVersionMessage", "").trim();
            deniedVersionCodes = json.optJSONArray("deniedVersionCodes");
            deniedVersionNames = json.optJSONArray("deniedVersionNames");
            password = PasswordPolicy.parse(json.optJSONObject("password"));
            passwordFingerprint = password.fingerprint();
        }

        static SecurityPolicy parse(JSONObject json) {
            if (json == null) throw new IllegalStateException("security policy missing");
            SecurityPolicy policy = new SecurityPolicy(json);
            if (policy.schema != 1 || policy.revision <= 0) {
                throw new IllegalStateException("invalid security policy");
            }
            return policy;
        }

        boolean isVersionDenied(long code, String name) {
            if (deniedVersionCodes != null) {
                for (int i = 0; i < deniedVersionCodes.length(); i++) {
                    if (deniedVersionCodes.optLong(i, Long.MIN_VALUE) == code) return true;
                }
            }
            if (deniedVersionNames != null) {
                for (int i = 0; i < deniedVersionNames.length(); i++) {
                    if (name.equals(deniedVersionNames.optString(i, "").trim())) return true;
                }
            }
            return false;
        }

        boolean matchesPassword(String value) {
            return password.matches(value);
        }
    }

    private static final class PasswordPolicy {
        final String algorithm;
        final String hashHex;
        final String saltHex;
        final int iterations;

        private PasswordPolicy(String algorithm, String hashHex, String saltHex, int iterations) {
            this.algorithm = algorithm;
            this.hashHex = hashHex;
            this.saltHex = saltHex;
            this.iterations = iterations;
        }

        static PasswordPolicy parse(JSONObject json) {
            if (json == null) throw new IllegalStateException("password policy missing");
            String algorithm = json.optString("algorithm", "sha256").trim().toLowerCase(Locale.ROOT);
            String hash = json.optString("hash", "").trim().toLowerCase(Locale.ROOT);
            String salt = json.optString("salt", "").trim().toLowerCase(Locale.ROOT);
            int iterations = json.optInt("iterations", 210000);
            if (!isHex(hash) || hash.length() != 64) throw new IllegalStateException("invalid password hash");
            if ("sha256".equals(algorithm)) {
                return new PasswordPolicy(algorithm, hash, "", 0);
            }
            if ("pbkdf2-sha256".equals(algorithm)) {
                if (!isHex(salt) || salt.length() < 16 || iterations < 100000 || iterations > 2000000) {
                    throw new IllegalStateException("invalid PBKDF2 password policy");
                }
                return new PasswordPolicy(algorithm, hash, salt, iterations);
            }
            throw new IllegalStateException("unsupported password algorithm");
        }

        boolean matches(String value) {
            try {
                byte[] actual;
                if ("sha256".equals(algorithm)) {
                    actual = MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
                } else {
                    KeySpec spec = new PBEKeySpec(value.toCharArray(), hex(saltHex), iterations, 256);
                    actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            .generateSecret(spec).getEncoded();
                }
                return MessageDigest.isEqual(hex(hashHex), actual);
            } catch (Throwable ignored) {
                return false;
            }
        }

        String fingerprint() {
            try {
                String material = algorithm + ':' + saltHex + ':' + iterations + ':' + hashHex;
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(material.getBytes(StandardCharsets.UTF_8));
                return toHex(digest);
            } catch (Throwable error) {
                throw new IllegalStateException("cannot fingerprint password policy", error);
            }
        }
    }

    private static boolean isHex(String value) {
        if (value == null || value.isEmpty() || (value.length() & 1) != 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static byte[] hex(String value) {
        int count = value.length();
        byte[] out = new byte[count / 2];
        for (int i = 0; i < count; i += 2) {
            int hi = Character.digit(value.charAt(i), 16);
            int lo = Character.digit(value.charAt(i + 1), 16);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
