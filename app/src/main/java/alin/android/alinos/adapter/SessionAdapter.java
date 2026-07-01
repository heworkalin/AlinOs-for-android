package alin.android.alinos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Function;

import alin.android.alinos.R;
import alin.android.alinos.bean.ChatSessionBean;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {
    private final List<ChatSessionBean> mList;
    private final OnSessionSelectListener mSelectListener;
    private final OnSessionLongClickListener mLongClickListener;
    private final Function<Integer, String> mConfigTypeProvider;
    private final Function<Integer, String> mModelNameProvider;

    public SessionAdapter(List<ChatSessionBean> list,
                          OnSessionSelectListener selectListener,
                          OnSessionLongClickListener longClickListener,
                          Function<Integer, String> configTypeProvider,
                          Function<Integer, String> modelNameProvider) {
        mList = list;
        mSelectListener = selectListener;
        mLongClickListener = longClickListener;
        mConfigTypeProvider = configTypeProvider;
        mModelNameProvider = modelNameProvider;
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        ChatSessionBean session = mList.get(position);
        holder.tvSessionName.setText(session.getSessionName());

        String configType = mConfigTypeProvider.apply(session.getConfigId());
        String modelName = mModelNameProvider.apply(session.getConfigId());
        holder.tvConfigType.setText("AI类型：" + configType + " | 模型：" + modelName);

        holder.itemView.setOnClickListener(v -> mSelectListener.onSelect(session));

        final int pos = holder.getAdapterPosition();
        holder.itemView.setOnLongClickListener(v -> {
            if (pos != RecyclerView.NO_POSITION) {
                mLongClickListener.onLongClick(session, pos);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mList == null ? 0 : mList.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        TextView tvSessionName, tvConfigType;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSessionName = itemView.findViewById(R.id.tv_session_name);
            tvConfigType = itemView.findViewById(R.id.tv_config_type);
        }
    }
}
