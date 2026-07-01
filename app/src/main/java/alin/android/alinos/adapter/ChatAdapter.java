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

import org.json.JSONObject;

import java.util.List;
import java.util.function.Consumer;

import alin.android.alinos.R;
import alin.android.alinos.bean.ChatMessage;

/**
 * 聊天消息适配器。
 * 支持流式增量更新、loading 动画、长按复制/重发/删除菜单。
 * 消息类型：用户 / AI文本 / Think思考块 / 工具调用卡片
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

    // -------------------- 流式增量更新 --------------------

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

    /** 更新 Think 块内容 + 切换折叠状态。 */
    public void updateThinkMessage(int position, String newContent, boolean isCollapsed) {
        if (position >= 0 && position < mMessageList.size()) {
            ChatMessage msg = mMessageList.get(position);
            if (msg.type == ChatMessage.TYPE_THINK) {
                msg.content = newContent;
                msg.isLoading = isCollapsed;
                notifyItemChanged(position);
            }
        }
    }

    /** 更新工具调用卡片的状态。 */
    public void updateToolCallMessage(int position, String newContent, boolean isExecuting) {
        if (position >= 0 && position < mMessageList.size()) {
            ChatMessage msg = mMessageList.get(position);
            if (msg.type == ChatMessage.TYPE_TOOL_CALL) {
                msg.content = newContent;
                msg.isLoading = isExecuting;
                notifyItemChanged(position);
            }
        }
    }

    // -------------------- ViewHolder 创建 --------------------

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        switch (viewType) {
            case ChatMessage.TYPE_USER:
                return new UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false));
            case ChatMessage.TYPE_THINK:
                return new ThinkViewHolder(inflater.inflate(R.layout.item_chat_think, parent, false));
            case ChatMessage.TYPE_TOOL_CALL:
                return new ToolCallViewHolder(inflater.inflate(R.layout.item_chat_tool_call, parent, false));
            default:
                return new AiViewHolder(inflater.inflate(R.layout.item_chat_ai, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = mMessageList.get(position);

        if (holder instanceof AiViewHolder) {
            bindAiMessage((AiViewHolder) holder, message);
        } else if (holder instanceof UserViewHolder) {
            bindUserMessage((UserViewHolder) holder, message);
        } else if (holder instanceof ThinkViewHolder) {
            bindThinkMessage((ThinkViewHolder) holder, message);
        } else if (holder instanceof ToolCallViewHolder) {
            bindToolCallMessage((ToolCallViewHolder) holder, message);
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

    // -------------------- 绑定逻辑 --------------------

    private void bindAiMessage(AiViewHolder holder, ChatMessage message) {
        holder.tvAiContent.setText(message.content);

        boolean isLoading = message.isLoading;
        holder.llAiLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        holder.pbLoadingCircle.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (!isLoading) {
            holder.pbLoadingCircle.clearAnimation();
        }
        if (isLoading) {
            holder.tvLoadingTips.setText("模型正在思考中...");
        }
    }

    private void bindUserMessage(UserViewHolder holder, ChatMessage message) {
        holder.tvUserContent.setText(message.content);

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

    private void bindThinkMessage(ThinkViewHolder holder, ChatMessage message) {
        holder.tvThinkContent.setText(message.content);

        // isLoading = true → 折叠状态（流式结束后自动折叠）
        boolean isCollapsed = message.isLoading;
        holder.tvThinkToggle.setText(isCollapsed ? "▸" : "▼");
        holder.tvThinkContent.setVisibility(isCollapsed ? View.GONE : View.VISIBLE);

        // 点击切换展开/折叠
        holder.llThinkHeader.setOnClickListener(v -> {
            boolean nowCollapsed = holder.tvThinkContent.getVisibility() != View.VISIBLE;
            message.isLoading = nowCollapsed; // 持久化状态
            holder.tvThinkToggle.setText(nowCollapsed ? "▸" : "▼");
            holder.tvThinkContent.setVisibility(nowCollapsed ? View.GONE : View.VISIBLE);
        });
    }

    private void bindToolCallMessage(ToolCallViewHolder holder, ChatMessage message) {
        boolean isExecuting = message.isLoading;
        String content = message.content;

        // 从 JSON 中解析工具调用数据
        String toolName = "unknown";
        String argsText = "";
        String requestJson = "";
        String responseJson = "";
        String logText = "";
        String status = isExecuting ? "⏳" : "✅";
        String duration = "";
        String preview = "";

        try {
            JSONObject json = new JSONObject(content);
            toolName = json.optString("toolName", "unknown");
            argsText = json.optString("args", "");
            requestJson = json.optString("request", "");
            responseJson = json.optString("response", "");
            logText = json.optString("log", "");
            String rawStatus = json.optString("status", "success");
            if ("error".equals(rawStatus)) status = "❌";
            else if ("timeout".equals(rawStatus)) status = "⏰";
            duration = json.optString("duration", "");

            // 从 response 中提取摘要前缀（收起时显示）
            String resp = json.optString("response", "");
            if (!resp.isEmpty()) {
                // 去掉 "status: success\n" 前缀
                String clean = resp.replaceAll("^status:\\s*\\w+\\s*", "").trim();
                if (clean.length() > 60) clean = clean.substring(0, 60) + "...";
                preview = clean;
            }
        } catch (Exception ignored) {}

        holder.tvToolName.setText("🔧 " + toolName + "()");
        holder.tvToolStatus.setText(status);
        holder.tvToolDuration.setText(duration);
        holder.tvToolPreview.setText(preview);
        holder.tvToolRequest.setText(requestJson);
        holder.tvToolResponse.setText(responseJson);
        holder.tvToolLog.setText(logText);

        // 点击切换展开/折叠详情
        boolean isExpanded = holder.llToolDetail.getVisibility() == View.VISIBLE;
        holder.llToolCall.setOnClickListener(v -> {
            boolean nowVisible = holder.llToolDetail.getVisibility() != View.VISIBLE;
            holder.llToolDetail.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            holder.tvExpandHint.setText(nowVisible ? "── 点击收起详情 ──" : "── 点击展开完整详情 ──");
        });
    }

    // -------------------- 工具方法 --------------------

    public void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    // ================================================================
    //  ViewHolder 定义
    // ================================================================

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

    // Think 思考块 ViewHolder
    public static class ThinkViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llThinkHeader;
        TextView tvThinkToggle;
        TextView tvThinkContent;

        public ThinkViewHolder(@NonNull View itemView) {
            super(itemView);
            llThinkHeader = itemView.findViewById(R.id.ll_think_header);
            tvThinkToggle = itemView.findViewById(R.id.tv_think_toggle);
            tvThinkContent = itemView.findViewById(R.id.tv_think_content);
        }
    }

    // 工具调用卡片 ViewHolder
    public static class ToolCallViewHolder extends RecyclerView.ViewHolder {
        LinearLayout llToolCall;
        TextView tvToolName, tvToolPreview, tvToolDuration, tvToolStatus;
        LinearLayout llToolDetail;
        TextView tvToolRequest, tvToolResponse, tvToolLog;
        TextView tvExpandHint;

        public ToolCallViewHolder(@NonNull View itemView) {
            super(itemView);
            llToolCall = itemView.findViewById(R.id.ll_tool_call);
            tvToolName = itemView.findViewById(R.id.tv_tool_name);
            tvToolPreview = itemView.findViewById(R.id.tv_tool_preview);
            tvToolDuration = itemView.findViewById(R.id.tv_tool_duration);
            tvToolStatus = itemView.findViewById(R.id.tv_tool_status);
            llToolDetail = itemView.findViewById(R.id.ll_tool_detail);
            tvToolRequest = itemView.findViewById(R.id.tv_tool_request);
            tvToolResponse = itemView.findViewById(R.id.tv_tool_response);
            tvToolLog = itemView.findViewById(R.id.tv_tool_log);
            tvExpandHint = itemView.findViewById(R.id.tv_expand_hint);
        }
    }
}
