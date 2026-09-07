package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AntiDeleteHelper;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class MessageEditHistoryBottomSheet extends BottomSheet {

    public static void show(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getContext() == null || messageObject == null) {
            return;
        }
        MessageEditHistoryBottomSheet sheet = new MessageEditHistoryBottomSheet(fragment.getContext(), fragment, messageObject);
        fragment.showDialog(sheet);
    }

    public MessageEditHistoryBottomSheet(Context context, BaseFragment fragment, MessageObject messageObject) {
        super(context, false, fragment != null ? fragment.getResourceProvider() : null);
        setTitle("Edit History", true);

        ArrayList<AntiDeleteHelper.EditEntry> history = AntiDeleteHelper.getInstance().getEditHistory(messageObject.currentAccount, messageObject.getDialogId(), messageObject.getId());

        NestedScrollView scrollView = new NestedScrollView(context);
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        // Current Version
        CharSequence currentText = messageObject.messageText;
        if (TextUtils.isEmpty(currentText) && messageObject.messageOwner != null) {
            currentText = messageObject.messageOwner.message;
        }
        if (TextUtils.isEmpty(currentText)) {
            currentText = "(empty / media)";
        }
        int editDate = messageObject.messageOwner != null ? messageObject.messageOwner.edit_date : 0;
        String currentDateStr = editDate != 0 ? LocaleController.formatDateAudio(editDate, true) : "Current";
        linearLayout.addView(createVersionCard(context, "Current: " + currentDateStr, currentText, fragment));

        // Previous Versions (reversed so most recent edit is top)
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AntiDeleteHelper.EditEntry entry = history.get(i);
                String dateStr = entry.date != 0 ? LocaleController.formatDateAudio(entry.date, true) : "Version " + (i + 1);
                linearLayout.addView(createVersionCard(context, dateStr, entry.text, fragment));
            }
        } else {
            TextView emptyText = new TextView(context);
            emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            emptyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            emptyText.setText("No previous edits recorded yet. Edits made after this point will be saved here.");
            emptyText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            linearLayout.addView(emptyText);
        }

        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        hint.setText("Tap any version to copy text to clipboard.");
        hint.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        linearLayout.addView(hint);

        scrollView.addView(linearLayout);
        setCustomView(scrollView);
    }

    private View createVersionCard(Context context, String headerText, CharSequence bodyText, BaseFragment fragment) {
        FrameLayout card = new FrameLayout(context);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        card.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 10, 10));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        header.setTypeface(AndroidUtilities.bold());
        header.setText(headerText);
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        body.setText(bodyText);
        body.setTextIsSelectable(false);
        content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        card.setOnClickListener(v -> {
            if (AndroidUtilities.addToClipboard(bodyText)) {
                if (fragment != null) {
                    BulletinFactory.of(fragment).createCopyBulletin("Text copied to clipboard").show();
                } else {
                    BulletinFactory.global().createCopyBulletin("Text copied to clipboard").show();
                }
            }
        });

        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8);
        card.setLayoutParams(lp);
        return card;
    }
}
