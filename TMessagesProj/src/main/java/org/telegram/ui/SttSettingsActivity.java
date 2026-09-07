package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GeminiTranscribeHelper;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class SttSettingsActivity extends UniversalFragment {

    private static final int ITEM_BASE_URL = 1;
    private static final int ITEM_API_KEY = 2;
    private static final int ITEM_MODEL = 3;
    private static final int ITEM_RESET = 4;

    @Override
    protected CharSequence getTitle() {
        return "Speech Recognition (STT)";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("AI Voice Transcription"));

        String baseUrl = GeminiTranscribeHelper.getBaseUrl();
        items.add(UItem.asSettingsCell(ITEM_BASE_URL, "Base URL", baseUrl));

        String apiKey = GeminiTranscribeHelper.getApiKey();
        String maskedKey;
        if (TextUtils.isEmpty(apiKey)) {
            maskedKey = "Not configured";
        } else if (apiKey.length() <= 8) {
            maskedKey = "••••••••";
        } else {
            maskedKey = apiKey.substring(0, 4) + "••••" + apiKey.substring(apiKey.length() - 4);
        }
        items.add(UItem.asSettingsCell(ITEM_API_KEY, "API Key", maskedKey));

        String model = GeminiTranscribeHelper.getModel();
        items.add(UItem.asSettingsCell(ITEM_MODEL, "Model", model));

        items.add(UItem.asShadow("Configure the AI endpoint and API key used for transcribing and summarizing voice messages."));

        items.add(UItem.asButton(ITEM_RESET, "Reset to Defaults").red());
        items.add(UItem.asShadow("Reset Base URL and Model to default Google Gemini endpoints."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ITEM_BASE_URL) {
            showEditDialog("Base URL", "https://generativelanguage.googleapis.com", GeminiTranscribeHelper.getBaseUrl(), false, (value) -> {
                if (TextUtils.isEmpty(value)) {
                    value = GeminiTranscribeHelper.DEFAULT_BASE_URL;
                }
                GeminiTranscribeHelper.setBaseUrl(value);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        } else if (item.id == ITEM_API_KEY) {
            showEditDialog("API Key", "Enter Gemini API Key", GeminiTranscribeHelper.getApiKey(), true, (value) -> {
                GeminiTranscribeHelper.setApiKey(value);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        } else if (item.id == ITEM_MODEL) {
            showEditDialog("Model", GeminiTranscribeHelper.DEFAULT_MODEL, GeminiTranscribeHelper.getModel(), false, (value) -> {
                if (TextUtils.isEmpty(value)) {
                    value = GeminiTranscribeHelper.DEFAULT_MODEL;
                }
                GeminiTranscribeHelper.setModel(value);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        } else if (item.id == ITEM_RESET) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
            builder.setTitle("Reset Settings");
            builder.setMessage("Reset Base URL and Model to defaults?");
            builder.setPositiveButton(LocaleController.getString(R.string.Reset), (dialog, which) -> {
                GeminiTranscribeHelper.setBaseUrl(GeminiTranscribeHelper.DEFAULT_BASE_URL);
                GeminiTranscribeHelper.setModel(GeminiTranscribeHelper.DEFAULT_MODEL);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private interface OnValueSetListener {
        void onValueSet(String value);
    }

    private void showEditDialog(String title, String hint, String initialValue, boolean isKey, OnValueSetListener listener) {
        if (getContext() == null) {
            return;
        }

        final EditTextBoldCursor editText = new EditTextBoldCursor(getContext());
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setHint(hint);
        editText.setText(initialValue != null ? initialValue : "");
        if (initialValue != null && initialValue.length() > 0) {
            editText.setSelection(initialValue.length());
        }
        editText.setBackgroundDrawable(Theme.createEditTextDrawable(getContext(), true));
        editText.setSingleLine(true);
        if (isKey) {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(title);
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
            if (listener != null) {
                listener.onValueSet(editText.getText() != null ? editText.getText().toString().trim() : "");
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
        });

        AlertDialog dialog = builder.create();
        showDialog(dialog);
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }
}
