package alin.android.alinos.adapter;

import alin.android.alinos.bean.ConfigBean;

public interface OnConfigOperationListener {
    void onEdit(ConfigBean config);
    void onSetDefault(ConfigBean config);
    void onDelete(ConfigBean config, int position);
}
