package uk.co.pzhang.autofill;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    private List<MainActivity.AppListItem> appList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemChanged(String packageName, boolean isChecked);
    }

    public AppAdapter(List<MainActivity.AppListItem> appList, OnItemClickListener listener) {
        this.appList = new ArrayList<>(appList);
        this.listener = listener;
    }

    public void updateData(List<MainActivity.AppListItem> newList) {
        this.appList = new ArrayList<>(newList);
        Log.d(AppId.DEBUG_TAG, "Adapter: notifyDataSetChanged called. Items in list: " + appList.size());
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MainActivity.AppListItem item = appList.get(position);
        holder.nameTv.setText(item.name);
        holder.pkgTv.setText(item.packageName);
        holder.iconIv.setImageDrawable(item.icon);

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(item.isSelected);
        holder.checkBox.setOnCheckedChangeListener((v, isChecked) -> {
            item.isSelected = isChecked;
            listener.onItemChanged(item.packageName, isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameTv, pkgTv;
        ImageView iconIv;
        CheckBox checkBox;

        public ViewHolder(View itemView) {
            super(itemView);
            nameTv = itemView.findViewById(R.id.tv_app_name);
            pkgTv = itemView.findViewById(R.id.tv_pkg_name);
            iconIv = itemView.findViewById(R.id.iv_app_icon);
            checkBox = itemView.findViewById(R.id.cb_app_select);
        }
    }
}