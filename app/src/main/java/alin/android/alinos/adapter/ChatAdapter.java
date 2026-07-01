package alin.android.alinos.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Consumer;

import alin.android.alinos.R;
import alin.android.alinos.bean.ChatMessage;

/**
 * 聊天消息适配器。
 * 支持流式增量更新、loading 动画、长按复制/重发/删除菜单。
 */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final Context mContext;
    private final List<ChatMessage> mMessageList;
    private final RecyclerView mRecyclerView;
    private final Consumer<String> mResendListener;

    public ChatAdapter(Context context, List<ChatMessage> messageList,
                       RecyclerView recyclerView) {
        this(context, messageList, recyclerView, null);
    }

    public ChatAdapter(Context context, List<ChatMessage> messageList,
                       RecyclerView recyclerView, Consumer<String> resendListener) {
        this.mContext = context;
        this.mMessageList = messageList;
        this.mRecyclerView = recyclerView;
        this.mResendListener = resendListener;
    }

    // 刷新单条AI消息（流式增量更新）
    public void updateAiMessage(int position, String newContent, boolean isLoading) {
        if (position >= 0 && position < mMessageList.size()) {
            ChatMessage msg = mMessageList.get(position);
            if (msg.type == ChatMessage.TYPE_AI) {
                msg.content = newContent;
                msg.isLoading = isLoading;
                notifyItemChanged(position);

                RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
                if (holder instanceof AiViewHolder) {
                    AiViewHolder aiHolder = (AiViewHolder) holder;
                    aiHolder.llAiLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
                    if (!isLoading) {
                        aiHolder.pbLoadingCircle.clearAnimation();
                        aiHolder.pbLoadingCircle.setVisibility(View.GONE);
                    } else {
                        aiHolder.pbLoadingCircle.setVisibility(View.VISIBLE);
                    }
                }
            }
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        if (viewType == ChatMessage.TYPE_USER) {
            View view = inflater.inflate(R.layout.item_chat_user, parent, false);
            return new UserViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_chat_ai, parent, false);
            return new AiViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = mMessageList.get(position);
        if (holder instanceof AiViewHolder) {
            AiViewHolder aiHolder = (AiViewHolder) holder;
            aiHolder.tvAiContent.setText(message.content);

            boolean isLoading = message.isLoading;
            aiHolder.llAiLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            aiHolder.pbLoadingCircle.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            if (!isLoading) {
                aiHolder.pbLoadingCircle.clearAnimation();
            }
            if (isLoading) {
                aiHolder.tvLoadingTips.setText("模型正在思考中...");
            }
        } else if (holder instanceof UserViewHolder) {
            UserViewHolder userHolder = (UserViewHolder) holder;
            userHolder.tvUserContent.setText(message.content);

            holder.itemView.setOnLongClickListener(v -> {
                PopupMenu popupMenu = new PopupMenu(mContext, v);
                popupMenu.getMenuInflater().inflate(R.menu.user_message_menu, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.menu_copy) {
                        copyToClipboard(mContext, message.content);
                        return true;
                    } else if (itemId == R.id.menu_resend) {
                        if (mResendListener != null) {
                            mResendListener.accept(message.content);
                        }
                        return true;
                    } else if (itemId == R.id.menu_delete) {
                        Toast.makeText(mContext, "删除消息功能待实现", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                });
                popupMenu.show();
                return true;
            });
        }
    }

    public void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    @Override
    public int getItemViewType(int position) {
        return mMessageList.get(position).type;
    }

    @Override
    public int getItemCount() {
        return mMessageList.size();
    }

    // AI消息ViewHolder
    public static class AiViewHolder extends RecyclerView.ViewHolder {
        public TextView tvAiContent;
        public LinearLayout llAiLoading;
        public TextView tvLoadingTips;
        public ProgressBar pbLoadingCircle;

        public AiViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAiContent = itemView.findViewById(R.id.tv_ai_content);
            llAiLoading = itemView.findViewById(R.id.ll_ai_loading);
            tvLoadingTips = itemView.findViewById(R.id.tv_loading_tips);
            pbLoadingCircle = itemView.findViewById(R.id.pb_loading_circle);

            itemView.setOnLongClickListener(v -> {
                Context context = itemView.getContext();
                PopupMenu popupMenu = new PopupMenu(context, v);
                popupMenu.getMenuInflater().inflate(R.menu.ai_message_menu, popupMenu.getMenu());
                popupMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.menu_copy) {
                        copyToClipboard(context, tvAiContent.getText().toString());
                        return true;
                    } else if (itemId == R.id.menu_regenerate) {
                        Toast.makeText(context, "重新生成中...", Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (itemId == R.id.menu_delete) {
                        Toast.makeText(context, "删除消息功能待实现", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                });
                popupMenu.show();
                return true;
            });
        }

        private void copyToClipboard(Context context, String text) {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("聊天消息", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    // 用户消息ViewHolder
    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvUserContent;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUserContent = itemView.findViewById(R.id.tv_user_content);
        }
    }
}
