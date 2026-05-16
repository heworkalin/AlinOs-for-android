package alin.android.alinos.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import alin.android.alinos.R;
import alin.android.alinos.bean.ChatMessage;
import alin.android.alinos.tools.LatexPreprocessor;
import io.noties.markwon.Markwon;

public class ChatDevAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<ChatMessage> messages;
    private final Markwon markwon;
    private boolean isDarkMode = false;

    // 亮色 / 暗色 调色板
    private static final int LIGHT_USER_BG   = 0xFF000000;
    private static final int LIGHT_USER_TEXT  = 0xFFFFFFFF;
    private static final int LIGHT_AI_BG     = 0xFFF0F0F0;
    private static final int LIGHT_AI_TEXT   = 0xFF000000;
    private static final int LIGHT_PAGE_BG   = 0xFFF5F5F5;

    private static final int DARK_USER_BG    = 0xFF1976D2;
    private static final int DARK_USER_TEXT   = 0xFFFFFFFF;
    private static final int DARK_AI_BG      = 0xFF2D2D2D;
    private static final int DARK_AI_TEXT    = 0xFFE0E0E0;
    private static final int DARK_PAGE_BG    = 0xFF121212;

    public ChatDevAdapter(List<ChatMessage> messages, Markwon markwon) {
        this.messages = messages;
        this.markwon = markwon;
    }

    public void setDarkMode(boolean dark) {
        isDarkMode = dark;
        notifyDataSetChanged();
    }

    public boolean isDarkMode() {
        return isDarkMode;
    }

    public int getPageBackgroundColor() {
        return isDarkMode ? DARK_PAGE_BG : LIGHT_PAGE_BG;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == ChatMessage.TYPE_USER) {
            View v = inflater.inflate(R.layout.item_chat_dev_user, parent, false);
            return new UserViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_chat_dev_ai, parent, false);
            return new AiViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        if (holder instanceof UserViewHolder) {
            UserViewHolder uh = (UserViewHolder) holder;
            uh.tvContent.setText(msg.getContent());
            uh.tvContent.setTextColor(isDarkMode ? DARK_USER_TEXT : LIGHT_USER_TEXT);
            uh.tvContent.setBackgroundColor(isDarkMode ? DARK_USER_BG : LIGHT_USER_BG);
            uh.tvContent.setOnLongClickListener(v -> showCopyDialog(v.getContext(), msg.getContent()));
        } else if (holder instanceof AiViewHolder) {
            AiViewHolder ah = (AiViewHolder) holder;

            // 先设置样式，确保颜色不被 Markwon 覆盖
            ah.tvContent.setTextColor(isDarkMode ? DARK_AI_TEXT : LIGHT_AI_TEXT);
            ah.tvContent.setBackgroundColor(isDarkMode ? DARK_AI_BG : LIGHT_AI_BG);

            if (TextUtils.isEmpty(msg.getContent())) {
                ah.tvContent.setText("");
            } else {
                // 如果宽度已确定，直接渲染；否则 post 延迟等待布局完成
                String preprocessed = LatexPreprocessor.preprocess(msg.getContent());
                if (ah.tvContent.getWidth() > 0) {
                    markwon.setMarkdown(ah.tvContent, preprocessed);
                } else {
                    ah.tvContent.post(() ->
                            markwon.setMarkdown(ah.tvContent, preprocessed));
                }
            }
            ah.tvContent.setOnLongClickListener(v -> showCopyDialog(v.getContext(), msg.getContent()));
        }
    }

    private boolean showCopyDialog(Context context, String content) {
        new AlertDialog.Builder(context)
                .setTitle("复制内容")
                .setMessage("将原始内容复制到剪贴板？")
                .setPositiveButton("复制", (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("chat_content", content));
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        return true;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvContent;
        UserViewHolder(View v) {
            super(v);
            tvContent = v.findViewById(R.id.tv_user_content);
        }
    }

    static class AiViewHolder extends RecyclerView.ViewHolder {
        TextView tvContent;
        AiViewHolder(View v) {
            super(v);
            tvContent = v.findViewById(R.id.tv_ai_content);
        }
    }
}
