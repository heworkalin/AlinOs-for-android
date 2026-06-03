package alin.android.alinos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import alin.android.alinos.R;
import alin.android.alinos.bean.SshConfigBean;

public class SshConfigAdapter extends RecyclerView.Adapter<SshConfigAdapter.ViewHolder> {

    private List<SshConfigBean> mList = new ArrayList<>();
    private final OnSshConfigListener mListener;

    public interface OnSshConfigListener {
        void onClick(SshConfigBean config);
        void onEdit(SshConfigBean config);
        void onDelete(SshConfigBean config, int position);
    }

    public SshConfigAdapter(List<SshConfigBean> list, OnSshConfigListener listener) {
        this.mList = sortWithLocalFirst(list);
        this.mListener = listener;
    }

    public void refreshData(List<SshConfigBean> list) {
        this.mList = sortWithLocalFirst(list);
        notifyDataSetChanged();
    }

    /** local_termux 置顶，其余按原序。 */
    private List<SshConfigBean> sortWithLocalFirst(List<SshConfigBean> list) {
        List<SshConfigBean> sorted = new ArrayList<>();
        for (SshConfigBean c : list) {
            if ("local_termux".equals(c.getConfigType())) {
                continue;
            }
            sorted.add(c);
        }
        for (SshConfigBean c : list) {
            if ("local_termux".equals(c.getConfigType())) {
                sorted.add(0, c);
                break;
            }
        }
        return sorted;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ssh_config, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SshConfigBean config = mList.get(position);

        // 本地标识
        boolean isLocal = "local_termux".equals(config.getConfigType());
        String tag = isLocal ? "[本地] " : "";

        String display = tag + config.getHost() + ":" + config.getPort() + "  " + config.getUsername();
        if (config.getName() != null && !config.getName().isEmpty()) {
            display = tag + config.getName() + "  (" + config.getHost() + ":" + config.getPort() + ")";
        }
        holder.tvDisplay.setText(display);
        holder.tvDisplay.setTextColor(isLocal ? 0xFF2D8CF0 : 0xFF303133);

        holder.itemView.setOnClickListener(v -> mListener.onClick(config));

        holder.itemView.setOnLongClickListener(v -> {
            showLongPressMenu(v, config, holder.getAdapterPosition());
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mList != null ? mList.size() : 0;
    }

    private void showLongPressMenu(View view, SshConfigBean config, int position) {
        new AlertDialog.Builder(view.getContext())
                .setItems(new String[]{"编辑", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        mListener.onEdit(config);
                    } else if (which == 1) {
                        mListener.onDelete(config, position);
                    }
                })
                .show();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDisplay;

        ViewHolder(View itemView) {
            super(itemView);
            tvDisplay = itemView.findViewById(R.id.tv_type);
        }
    }
}
