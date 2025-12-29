package alin.android.alinos.adapter;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import alin.android.alinos.R;
import alin.android.alinos.bean.ConfigBean;
import alin.android.alinos.db.ConfigDBHelper;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ConfigViewHolder> {
    private Context mContext;
    private List<ConfigBean> mConfigList;
    private ConfigDBHelper mDbHelper;
    private OnConfigOperationListener mListener;

    // 构造方法：传入 Context、列表数据、回调接口
    public ConfigAdapter(Context context, List<ConfigBean> configList, OnConfigOperationListener listener) {
        mContext = context;
        mConfigList = configList;
        mDbHelper = new ConfigDBHelper(context);
        mListener = listener;
    }

    @NonNull
    @Override
    public ConfigViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.item_config, parent, false);
        return new ConfigViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConfigViewHolder holder, int position) {
        ConfigBean config = mConfigList.get(position);

        // 1. 基础数据渲染（Ollama 密钥特殊处理）
        holder.tvType.setText("类型：" + config.getType());
        holder.tvServer.setText("地址：" + config.getServerUrl());
        // Ollama 显示「无需配置」，OpenAI 显示密钥（避免空指针）
        if (config.getType().equals("Ollama")) {
            holder.tvKey.setText("密钥：无需配置");
        } else {
            holder.tvKey.setText("密钥：" + (config.getApiKey() != null ? config.getApiKey() : "未设置"));
        }

        // 2. 「当前使用」标识优化（默认项绿色显示，非默认项隐藏）
        if (config.isDefault()) {
            holder.tvDefault.setVisibility(View.VISIBLE);
            holder.tvDefault.setText("当前使用");
            holder.tvDefault.setTextColor(Color.GREEN);
            holder.tvDefault.setTextSize(12); // 字体协调
        } else {
            holder.tvDefault.setVisibility(View.GONE); // 非默认项隐藏，节省空间
        }

        // 3. 长按弹出操作弹窗
        holder.itemView.setOnLongClickListener(v -> {
            showOperationDialog(config, position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mConfigList == null ? 0 : mConfigList.size();
    }

    // 长按操作弹窗（优化逻辑：已默认项隐藏「设为默认」选项）
    private void showOperationDialog(ConfigBean config, int position) {
        // 已默认的配置：隐藏「设为默认」选项，避免重复操作
        String[] items = config.isDefault()
                ? new String[]{"编辑配置", "删除配置"}
                : new String[]{"编辑配置", "设为默认", "删除配置"};

        new androidx.appcompat.app.AlertDialog.Builder(mContext)
                .setTitle("操作配置")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            // 编辑：回调给 Activity 处理
                            if (mListener != null) {
                                mListener.onEdit(config);
                            }
                            break;
                        // ConfigAdapter的showOperationDialog方法中，将「设为默认」分支改为回调：
                        case 1:
                            // 未默认：第二个选项是设为默认 → 回调给Activity
                            if (mListener != null) {
                                mListener.onSetDefault(config);
                            }
                            break;
                        case 2:
                            // 未默认：第三个选项是删除
                            if (mListener != null) {
                                mListener.onDelete(config, position);
                            }
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 列表项 ViewHolder
    static class ConfigViewHolder extends RecyclerView.ViewHolder {
        TextView tvType, tvServer, tvKey, tvDefault;

        public ConfigViewHolder(@NonNull View itemView) {
            super(itemView);
            tvType = itemView.findViewById(R.id.tv_type);
            tvServer = itemView.findViewById(R.id.tv_server);
            tvKey = itemView.findViewById(R.id.tv_key);
            tvDefault = itemView.findViewById(R.id.tv_default);
        }
    }
    // 在ConfigAdapter中新增方法：更新列表数据并全局刷新
    public void refreshData(List<ConfigBean> newConfigList) {
        mConfigList = newConfigList; // 替换为数据库最新数据
        notifyDataSetChanged(); // 全局刷新
    }
}