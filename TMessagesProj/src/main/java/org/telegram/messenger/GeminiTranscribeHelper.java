package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class GeminiTranscribeHelper {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gemini_stt_settings", Context.MODE_PRIVATE);
    }

    public static String getBaseUrl() {
        String url = getPreferences().getString("base_url", DEFAULT_BASE_URL);
        if (TextUtils.isEmpty(url)) {
            return DEFAULT_BASE_URL;
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static void setBaseUrl(String url) {
        if (url != null) {
            url = url.trim();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        getPreferences().edit().putString("base_url", url).apply();
    }

    public static String getApiKey() {
        return getPreferences().getString("api_key", "");
    }

    public static void setApiKey(String key) {
        getPreferences().edit().putString("api_key", key != null ? key.trim() : "").apply();
    }

    public static String getModel() {
        String model = getPreferences().getString("model", DEFAULT_MODEL);
        return TextUtils.isEmpty(model) ? DEFAULT_MODEL : model.trim();
    }

    public static void setModel(String model) {
        getPreferences().edit().putString("model", model != null ? model.trim() : "").apply();
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(getApiKey());
    }

    public interface Callback {
        void onResult(String text);
        void onError(String error);
    }

    public interface SummaryCallback {
        void onSummaryUpdated();
        void onError(String error);
    }

    private static final ConcurrentHashMap<String, Callback> pendingCallbacks = new ConcurrentHashMap<>();
    private static final HashSet<String> activeRequests = new HashSet<>();
    private static final ConcurrentHashMap<String, String> summaries = new ConcurrentHashMap<>();
    private static final HashSet<String> openSummaries = new HashSet<>();
    private static final HashSet<String> loadingSummaries = new HashSet<>();

    public static boolean isSummaryOpen(MessageObject messageObject) {
        if (messageObject == null) return false;
        String key = getMessageKey(messageObject);
        synchronized (openSummaries) {
            return openSummaries.contains(key);
        }
    }

    public static boolean isSummaryLoading(MessageObject messageObject) {
        if (messageObject == null) return false;
        String key = getMessageKey(messageObject);
        synchronized (loadingSummaries) {
            return loadingSummaries.contains(key);
        }
    }

    public static String getVoiceSummary(MessageObject messageObject) {
        if (messageObject == null) return null;
        String key = getMessageKey(messageObject);
        return summaries.get(key);
    }

    public static void setSummaryOpen(MessageObject messageObject, boolean open) {
        if (messageObject == null) return;
        String key = getMessageKey(messageObject);
        synchronized (openSummaries) {
            if (open) {
                openSummaries.add(key);
            } else {
                openSummaries.remove(key);
            }
        }
    }

    public static void toggleSummary(MessageObject messageObject, SummaryCallback callback) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        String key = getMessageKey(messageObject);
        boolean isOpen;
        synchronized (openSummaries) {
            isOpen = openSummaries.contains(key);
            if (isOpen) {
                openSummaries.remove(key);
            }
        }
        if (isOpen) {
            messageObject.forceUpdate = true;
            int account = messageObject.currentAccount;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, true, true);
            if (callback != null) {
                callback.onSummaryUpdated();
            }
            return;
        }

        String cached = summaries.get(key);
        if (!TextUtils.isEmpty(cached)) {
            synchronized (openSummaries) {
                openSummaries.add(key);
            }
            messageObject.forceUpdate = true;
            int account = messageObject.currentAccount;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, true, true);
            if (callback != null) {
                callback.onSummaryUpdated();
            }
            return;
        }

        synchronized (loadingSummaries) {
            if (loadingSummaries.contains(key)) {
                return;
            }
            loadingSummaries.add(key);
        }
        if (callback != null) {
            callback.onSummaryUpdated();
        }

        String originalText = messageObject.messageOwner.voiceTranscription;
        if (TextUtils.isEmpty(originalText)) {
            synchronized (loadingSummaries) {
                loadingSummaries.remove(key);
            }
            if (callback != null) {
                callback.onError("No transcription text");
            }
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            String apiKey = getApiKey();
            if (TextUtils.isEmpty(apiKey)) {
                handleSummaryError(key, "STT API Key not configured. Go to Settings -> Speech Recognition (STT)");
                return;
            }
            String baseUrl = getBaseUrl();
            String model = getModel();

            try {
                JSONObject root = new JSONObject();

                JSONObject sysInstruction = new JSONObject();
                JSONArray sysParts = new JSONArray();
                JSONObject sysPart = new JSONObject();
                sysPart.put("text", "You are an expert summarization engine. Summarize the user text concisely in 1-3 short bullet points in the original spoken language. Do not add conversational intro, headers, or quotes.");
                sysParts.put(sysPart);
                sysInstruction.put("parts", sysParts);
                root.put("systemInstruction", sysInstruction);

                JSONArray contents = new JSONArray();
                JSONObject contentObj = new JSONObject();
                JSONArray parts = new JSONArray();

                JSONObject textPart = new JSONObject();
                textPart.put("text", "Summarize this voice message into 1-3 concise bullet points in the original spoken language. Output ONLY the bullet points without any intro, markdown headers, or conversational text:\n\n" + originalText);
                parts.put(textPart);

                contentObj.put("parts", parts);
                contents.put(contentObj);
                root.put("contents", contents);

                byte[] postBytes = root.toString().getBytes("UTF-8");

                String endpoint = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(45000);
                conn.setFixedLengthStreamingMode(postBytes.length);

                OutputStream os = conn.getOutputStream();
                os.write(postBytes);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] temp = new byte[4096];
                int r;
                while ((r = is.read(temp)) != -1) {
                    baos.write(temp, 0, r);
                }
                is.close();
                conn.disconnect();

                String respStr = baos.toString("UTF-8");
                if (code >= 200 && code < 300) {
                    JSONObject respJson = new JSONObject(respStr);
                    JSONArray candidates = respJson.optJSONArray("candidates");
                    String summaryResult = "";
                    if (candidates != null && candidates.length() > 0) {
                        JSONObject candidate = candidates.getJSONObject(0);
                        JSONObject content = candidate.optJSONObject("content");
                        if (content != null) {
                            JSONArray respParts = content.optJSONArray("parts");
                            if (respParts != null) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < respParts.length(); i++) {
                                    JSONObject p = respParts.getJSONObject(i);
                                    if (p.has("text")) {
                                        sb.append(p.getString("text"));
                                    }
                                }
                                summaryResult = sb.toString().trim();
                            }
                        }
                    }

                    final String finalSummary = summaryResult;
                    AndroidUtilities.runOnUIThread(() -> {
                        synchronized (loadingSummaries) {
                            loadingSummaries.remove(key);
                        }
                        if (!TextUtils.isEmpty(finalSummary)) {
                            summaries.put(key, finalSummary);
                            synchronized (openSummaries) {
                                openSummaries.add(key);
                            }
                            messageObject.forceUpdate = true;
                            int account = messageObject.currentAccount;
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject, null, null, true, true);
                            if (callback != null) {
                                callback.onSummaryUpdated();
                            }
                        } else {
                            if (callback != null) {
                                callback.onError("Empty summary");
                            }
                        }
                    });
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        synchronized (loadingSummaries) {
                            loadingSummaries.remove(key);
                        }
                        if (callback != null) {
                            callback.onError("HTTP " + code);
                        }
                    });
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (loadingSummaries) {
                        loadingSummaries.remove(key);
                    }
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                });
            }
        });
    }

    private static String getMessageKey(MessageObject messageObject) {
        if (messageObject == null) {
            return "";
        }
        return messageObject.currentAccount + "_" + messageObject.getDialogId() + "_" + messageObject.getId();
    }

    public static void cancel(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        String key = getMessageKey(messageObject);
        synchronized (activeRequests) {
            activeRequests.remove(key);
            pendingCallbacks.remove(key);
        }
    }

    public static boolean isTranscribing(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        String key = getMessageKey(messageObject);
        synchronized (activeRequests) {
            return activeRequests.contains(key);
        }
    }

    public static void transcribe(MessageObject messageObject, Callback callback) {
        if (messageObject == null || messageObject.messageOwner == null) {
            if (callback != null) {
                callback.onError("Invalid message");
            }
            return;
        }

        String key = getMessageKey(messageObject);
        synchronized (activeRequests) {
            activeRequests.add(key);
            if (callback != null) {
                pendingCallbacks.put(key, callback);
            }
        }

        int account = messageObject.currentAccount;
        File file = getAudioFile(messageObject);

        if (file != null && file.exists() && file.length() > 0) {
            sendAudioToGemini(key, file, messageObject);
        } else {
            TLRPC.Document document = messageObject.getDocument();
            if (document == null) {
                handleError(key, "No audio document");
                return;
            }

            String attachFileName = FileLoader.getAttachFileName(document);
            NotificationCenter.NotificationCenterDelegate delegate = new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int accountId, Object... args) {
                    if (args == null || args.length == 0) {
                        return;
                    }
                    if (id == NotificationCenter.fileLoaded) {
                        String name = args[0] instanceof String ? (String) args[0] : null;
                        if (TextUtils.equals(name, attachFileName)) {
                            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
                            File downloaded = args.length > 1 && args[1] instanceof File ? (File) args[1] : null;
                            if (downloaded == null || !downloaded.exists() || downloaded.length() == 0) {
                                downloaded = getAudioFile(messageObject);
                            }
                            if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
                                sendAudioToGemini(key, downloaded, messageObject);
                            } else {
                                handleError(key, "Downloaded file not found");
                            }
                        }
                    } else if (id == NotificationCenter.fileLoadFailed) {
                        String name = args[0] instanceof String ? (String) args[0] : null;
                        if (TextUtils.equals(name, attachFileName)) {
                            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
                            handleError(key, "File download failed");
                        }
                    }
                }
            };

            NotificationCenter.getInstance(account).addObserver(delegate, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(delegate, NotificationCenter.fileLoadFailed);
            FileLoader.getInstance(account).loadFile(document, messageObject, 1, 0);

            // Timeout after 60s
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (activeRequests) {
                    if (activeRequests.contains(key)) {
                        NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoaded);
                        NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoadFailed);
                        handleError(key, "Download timeout");
                    }
                }
            }, 60000);
        }
    }

    public static File getAudioFile(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        int account = messageObject.currentAccount;
        if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
            File f = new File(messageObject.messageOwner.attachPath);
            if (f.exists() && f.length() > 0) {
                return f;
            }
        }
        File f = FileLoader.getInstance(account).getPathToMessage(messageObject.messageOwner, true);
        if (f != null && f.exists() && f.length() > 0) {
            return f;
        }
        if (messageObject.getDocument() != null) {
            f = FileLoader.getInstance(account).getPathToAttach(messageObject.getDocument(), null, false, true);
            if (f != null && f.exists() && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    private static void sendAudioToGemini(String key, File file, MessageObject messageObject) {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (activeRequests) {
                if (!activeRequests.contains(key)) {
                    return;
                }
            }

            String apiKey = getApiKey();
            if (TextUtils.isEmpty(apiKey)) {
                handleError(key, "STT API Key not configured. Go to Settings -> Speech Recognition (STT)");
                return;
            }
            String baseUrl = getBaseUrl();
            String model = getModel();

            try {
                if (file == null || !file.exists() || file.length() == 0) {
                    handleSuccess(key, "");
                    return;
                }

                if (file.length() > 25 * 1024 * 1024) {
                    handleError(key, "Audio file is too large (>25MB)");
                    return;
                }

                String mimeType = "audio/ogg";
                if (messageObject.isRoundVideo() || messageObject.isVideo()) {
                    mimeType = "video/mp4";
                } else if (messageObject.isVoice()) {
                    mimeType = "audio/ogg";
                } else {
                    String docMime = messageObject.getMimeType();
                    if (!TextUtils.isEmpty(docMime)) {
                        mimeType = docMime;
                    }
                }

                byte[] buffer = new byte[(int) file.length()];
                FileInputStream fis = new FileInputStream(file);
                int offset = 0;
                int read;
                while (offset < buffer.length && (read = fis.read(buffer, offset, buffer.length - offset)) >= 0) {
                    offset += read;
                }
                fis.close();

                String base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);

                JSONObject root = new JSONObject();

                // systemInstruction
                JSONObject sysInstruction = new JSONObject();
                JSONArray sysParts = new JSONArray();
                JSONObject sysPart = new JSONObject();
                sysPart.put("text", "You are an expert speech-to-text transcription engine. Accurately transcribe speech from the audio file word-for-word in the original spoken language. Output ONLY the raw transcribed text. Do not add quotes, markdown formatting, explanations, or introductory text. If the audio has no intelligible speech or contains only silence/noise/music, output nothing.");
                sysParts.put(sysPart);
                sysInstruction.put("parts", sysParts);
                root.put("systemInstruction", sysInstruction);

                // contents
                JSONArray contents = new JSONArray();
                JSONObject contentItem = new JSONObject();
                JSONArray parts = new JSONArray();

                JSONObject inlineDataPart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", mimeType);
                inlineData.put("data", base64Data);
                inlineDataPart.put("inline_data", inlineData);
                parts.put(inlineDataPart);

                contentItem.put("parts", parts);
                contents.put(contentItem);
                root.put("contents", contents);

                byte[] postBytes = root.toString().getBytes("UTF-8");

                String endpoint = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(45000);
                conn.setReadTimeout(90000);
                conn.setFixedLengthStreamingMode(postBytes.length);

                OutputStream os = conn.getOutputStream();
                os.write(postBytes);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] temp = new byte[8192];
                int r;
                while ((r = is.read(temp)) != -1) {
                    baos.write(temp, 0, r);
                }
                is.close();
                conn.disconnect();

                String respStr = baos.toString("UTF-8");

                synchronized (activeRequests) {
                    if (!activeRequests.contains(key)) {
                        return;
                    }
                }

                if (code >= 200 && code < 300) {
                    JSONObject respJson = new JSONObject(respStr);
                    JSONArray candidates = respJson.optJSONArray("candidates");
                    String transcribedText = "";
                    if (candidates != null && candidates.length() > 0) {
                        JSONObject candidate = candidates.getJSONObject(0);
                        JSONObject content = candidate.optJSONObject("content");
                        if (content != null) {
                            JSONArray respParts = content.optJSONArray("parts");
                            if (respParts != null) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < respParts.length(); i++) {
                                    JSONObject p = respParts.getJSONObject(i);
                                    if (p.has("text")) {
                                        sb.append(p.getString("text"));
                                    }
                                }
                                transcribedText = sb.toString().trim();
                            }
                        }
                    }
                    handleSuccess(key, transcribedText);
                } else {
                    FileLog.e("Gemini STT error HTTP " + code + ": " + respStr);
                    handleError(key, "HTTP " + code);
                }
            } catch (Exception e) {
                FileLog.e(e);
                handleError(key, e.getMessage());
            }
        });
    }

    private static void handleSuccess(String key, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            Callback cb;
            synchronized (activeRequests) {
                activeRequests.remove(key);
                cb = pendingCallbacks.remove(key);
            }
            if (cb != null) {
                cb.onResult(text);
            }
        });
    }

    private static void handleError(String key, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            Callback cb;
            synchronized (activeRequests) {
                activeRequests.remove(key);
                cb = pendingCallbacks.remove(key);
            }
            if (cb != null) {
                cb.onError(error);
            }
        });
    }
}
