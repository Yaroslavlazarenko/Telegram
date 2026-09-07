package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AntiDeleteHelper;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class MessageEditHistoryBottomSheet extends BottomSheetWithRecyclerListView {

    private final MessageObject messageObject;
    private final ArrayList<AntiDeleteHelper.EditEntry> history;
    private UniversalAdapter adapter;

    public static void show(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getContext() == null || messageObject == null) {
            return;
        }
        MessageEditHistoryBottomSheet sheet = new MessageEditHistoryBottomSheet(fragment.getContext(), fragment, messageObject);
        fragment.showDialog(sheet);
    }

    public MessageEditHistoryBottomSheet(Context context, BaseFragment fragment, MessageObject messageObject) {
        super(context, fragment, false, false, false, false, ActionBarType.SLIDING, fragment != null ? fragment.getResourceProvider() : null);
        this.messageObject = messageObject;
        this.history = AntiDeleteHelper.getInstance().getEditHistory(messageObject.currentAccount, messageObject.getDialogId(), messageObject.getId());
        topPadding = 0.25f;
        setShowHandle(true);
        fixNavigationBar();
    }

    @Override
    protected CharSequence getTitle() {
        return "Edit History";
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, this::fillItems, resourcesProvider);
        return adapter;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Message Versions"));

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
        items.add(UItem.asCustom(new VersionCell(getContext(), "Current: " + currentDateStr, currentText, resourcesProvider)));

        // Previous Versions (reversed so most recent edit is top)
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AntiDeleteHelper.EditEntry entry = history.get(i);
                String dateStr = entry.date != 0 ? LocaleController.formatDateAudio(entry.date, true) : "Version " + (i + 1);
                items.add(UItem.asCustom(new VersionCell(getContext(), dateStr, entry.text, resourcesProvider)));
            }
        }

        items.add(UItem.asShadow("Tap any version to copy text to clipboard."));
    }

    private static class VersionCell extends FrameLayout {
        public VersionCell(@NonNull Context context, String headerText, CharSequence bodyText, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 8, 8));

            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);

            TextView header = new TextView(context);
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
            header.setTypeface(AndroidUtilities.bold());
            header.setText(headerText);
            layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            TextView body = new TextView(context);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            body.setText(bodyText);
            body.setTextIsSelectable(false);
            layout.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            setOnClickListener(v -> {
                if (AndroidUtilities.addToClipboard(bodyText)) {
                    BulletinFactory.global().createCopyBulletin("Text copied to clipboard").show();
                }
            });
        }
    }
}
