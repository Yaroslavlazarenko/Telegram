package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class AntiDeleteHelper {

    private static volatile AntiDeleteHelper Instance;

    public static AntiDeleteHelper getInstance() {
        AntiDeleteHelper localInstance = Instance;
        if (localInstance == null) {
            synchronized (AntiDeleteHelper.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new AntiDeleteHelper();
                }
            }
        }
        return localInstance;
    }

    public static class EditEntry {
        public final long date;
        public final String text;

        public EditEntry(long date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private final DatabaseHelper dbHelper;
    private final ConcurrentHashMap<String, Boolean> deletedCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayList<EditEntry>> editsCache = new ConcurrentHashMap<>();

    private AntiDeleteHelper() {
        dbHelper = new DatabaseHelper(ApplicationLoader.applicationContext);
        loadDeletedCache();
    }

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("anti_delete_settings", Context.MODE_PRIVATE);
    }

    public boolean isAntiDeleteEnabled() {
        return getPreferences().getBoolean("enabled", true);
    }

    public void setAntiDeleteEnabled(boolean enabled) {
        getPreferences().edit().putBoolean("enabled", enabled).apply();
    }

    public boolean isEditHistoryEnabled() {
        return getPreferences().getBoolean("edit_history_enabled", true);
    }

    public void setEditHistoryEnabled(boolean enabled) {
        getPreferences().edit().putBoolean("edit_history_enabled", enabled).apply();
    }

    public boolean isEditNotificationEnabled() {
        return getPreferences().getBoolean("edit_notification_enabled", true);
    }

    public void setEditNotificationEnabled(boolean enabled) {
        getPreferences().edit().putBoolean("edit_notification_enabled", enabled).apply();
    }

    private String getMessageKey(int account, long dialogId, int messageId) {
        return account + "_" + dialogId + "_" + messageId;
    }

    private void loadDeletedCache() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                Cursor cursor = db.query("deleted_messages", new String[]{"account", "dialog_id", "message_id"}, null, null, null, null, null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        int account = cursor.getInt(0);
                        long did = cursor.getLong(1);
                        int mid = cursor.getInt(2);
                        deletedCache.put(getMessageKey(account, did, mid), Boolean.TRUE);
                    }
                    cursor.close();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public boolean isDeleted(int account, long dialogId, int messageId) {
        if (!isAntiDeleteEnabled()) {
            return false;
        }
        return deletedCache.containsKey(getMessageKey(account, dialogId, messageId));
    }

    public void markAsDeleted(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        for (int mid : messageIds) {
            deletedCache.put(getMessageKey(account, dialogId, mid), Boolean.TRUE);
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    int currentTime = (int) (System.currentTimeMillis() / 1000);
                    ContentValues cv = new ContentValues();
                    for (int mid : messageIds) {
                        cv.clear();
                        cv.put("account", account);
                        cv.put("dialog_id", dialogId);
                        cv.put("message_id", mid);
                        cv.put("date", currentTime);
                        db.insertWithOnConflict("deleted_messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public void removeDeleted(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        for (int mid : messageIds) {
            deletedCache.remove(getMessageKey(account, dialogId, mid));
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                for (int mid : messageIds) {
                    db.delete("deleted_messages", "account = ? AND dialog_id = ? AND message_id = ?",
                            new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(mid)});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public void saveEditHistory(int account, long dialogId, int messageId, long date, String oldText) {
        if (!isEditHistoryEnabled() || TextUtils.isEmpty(oldText)) {
            return;
        }
        String key = getMessageKey(account, dialogId, messageId);
        ArrayList<EditEntry> list = editsCache.get(key);
        if (list == null) {
            list = new ArrayList<>();
            editsCache.put(key, list);
        }
        synchronized (list) {
            boolean alreadyHas = false;
            for (EditEntry entry : list) {
                if (TextUtils.equals(entry.text, oldText)) {
                    alreadyHas = true;
                    break;
                }
            }
            if (!alreadyHas) {
                list.add(new EditEntry(date, oldText));
            }
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("account", account);
                cv.put("dialog_id", dialogId);
                cv.put("message_id", messageId);
                cv.put("date", date);
                cv.put("text", oldText);
                db.insert("message_edits", null, cv);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public boolean hasEditHistory(int account, long dialogId, int messageId) {
        String key = getMessageKey(account, dialogId, messageId);
        ArrayList<EditEntry> list = editsCache.get(key);
        if (list != null && !list.isEmpty()) {
            return true;
        }
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT 1 FROM message_edits WHERE account = ? AND dialog_id = ? AND message_id = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
            boolean has = false;
            if (cursor != null) {
                has = cursor.moveToFirst();
                cursor.close();
            }
            return has;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    public ArrayList<EditEntry> getEditHistory(int account, long dialogId, int messageId) {
        String key = getMessageKey(account, dialogId, messageId);
        ArrayList<EditEntry> list = editsCache.get(key);
        if (list != null && !list.isEmpty()) {
            return new ArrayList<>(list);
        }

        ArrayList<EditEntry> result = new ArrayList<>();
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor cursor = db.query("message_edits", new String[]{"date", "text"},
                    "account = ? AND dialog_id = ? AND message_id = ?",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)},
                    null, null, "date ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long date = cursor.getLong(0);
                    String text = cursor.getString(1);
                    result.add(new EditEntry(date, text));
                }
                cursor.close();
            }
            if (!result.isEmpty()) {
                editsCache.put(key, new ArrayList<>(result));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "anti_delete.db";
        private static final int DATABASE_VERSION = 1;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (" +
                    "account INTEGER, dialog_id INTEGER, message_id INTEGER, date INTEGER, text TEXT, " +
                    "PRIMARY KEY(account, dialog_id, message_id))");

            db.execSQL("CREATE TABLE IF NOT EXISTS message_edits (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, account INTEGER, dialog_id INTEGER, message_id INTEGER, date INTEGER, text TEXT)");

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_edits ON message_edits (account, dialog_id, message_id)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
