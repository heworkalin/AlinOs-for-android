package alin.android.alinos.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

import alin.android.alinos.ChatActivity;
import alin.android.alinos.ChatActivity.ChatMessage;
import alin.android.alinos.R;

// 聊天适配器
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private Context mContext;
    private List<ChatActivity.ChatMessage> mMessageList;

    // 构造方法
    public ChatAdapter(Context context, List<ChatActivity.ChatMessage> messageList) {
        this.mContext = context;
        this.mMessageList = messageList;
    }

    // 刷新单条AI消息（流式增量更新）
    public void updateAiMessage(int position, String newContent, boolean isLoading) {
        if (position >= 0 && position < mMessageList.size()) {
            ChatActivity.ChatMessage msg = mMessageList.get(position);
            if (msg.type == ChatMessage.TYPE_AI) {
                msg.content = newContent;
                msg.isLoading = isLoading;
                notifyItemChanged(position); // 局部刷新，避免闪烁
            }
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        if (viewType == ChatMessage.TYPE_USER) {
            // 用户消息布局
            View view = inflater.inflate(R.layout.item_chat_user, parent, false);
            return new UserViewHolder(view);
        } else {
            // AI消息布局（核心）
            View view = inflater.inflate(R.layout.item_chat_ai, parent, false);
            return new AiViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = mMessageList.get(position);
        if (holder instanceof AiViewHolder) {
            AiViewHolder aiHolder = (AiViewHolder) holder;
            // 设置AI消息内容
            aiHolder.tvAiContent.setText(message.content);
            // 控制loading显示/隐藏
            if (message.isLoading) {
                aiHolder.llAiLoading.setVisibility(View.VISIBLE);
            } else {
                aiHolder.llAiLoading.setVisibility(View.GONE);
            }
        } else if (holder instanceof UserViewHolder) {
            UserViewHolder userHolder = (UserViewHolder) holder;
            userHolder.tvUserContent.setText(message.content);
        }
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
    static class AiViewHolder extends RecyclerView.ViewHolder {
        TextView tvAiContent;
        LinearLayout llAiLoading;
        TextView tvLoadingTips;
        ProgressBar pbLoadingCircle;

        public AiViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAiContent = itemView.findViewById(R.id.tv_ai_content);
            llAiLoading = itemView.findViewById(R.id.ll_ai_loading);
            tvLoadingTips = itemView.findViewById(R.id.tv_loading_tips);
            pbLoadingCircle = itemView.findViewById(R.id.pb_loading_circle);
        }
    }

    // 用户消息ViewHolder
    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvUserContent;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUserContent = itemView.findViewById(R.id.tv_user_content);
        }
    }
}