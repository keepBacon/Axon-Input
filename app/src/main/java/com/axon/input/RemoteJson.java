package com.axon.input;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 小型 JSON 请求工具。仅用于版本和公告检查。 */
final class RemoteJson {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private RemoteJson() {}

    static JSONObject get(Context context, String url, boolean bypassCache) {
        if (url == null || url.trim().isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            String requestUrl = bypassCache ? withTimestamp(url.trim()) : url.trim();
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(!bypassCache);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", AppVersion.userAgent(context));
            if (bypassCache) connection.setRequestProperty("Cache-Control", "no-cache");
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            byte[] data;
            try (InputStream input = connection.getInputStream()) {
                data = readLimited(input);
            }
            if (data == null || data.length == 0) return null;
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String withTimestamp(String url) {
        char separator = url.indexOf('?') >= 0 ? '&' : '?';
        return url + separator + "t=" + System.currentTimeMillis();
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
