package dev.xigua.bilicc;

import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SettingsActivity extends Screen {
    private static final int URI_INPUT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;

    private static final class Row {
        final LinearLayout view;
        final TextView order;
        final EditText name;
        final EditText url;
        final EditText ip;
        final TextView connectionStatus;
        Button up;
        Button down;

        Row(SettingsActivity activity, RouteStore.Route route) {
            view = Ui.panel(activity);
            order = Ui.text(activity, "", 16, Ui.TEXT, true);
            name = Ui.field(activity, "名称，如 局域网", InputType.TYPE_CLASS_TEXT);
            url = Ui.field(activity, "https://nas.example.com/", URI_INPUT);
            ip = Ui.field(activity, "如 192.168.1.20", URI_INPUT);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            url.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});
            ip.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
            connectionStatus = Ui.text(activity, "", 14, Ui.MUTED, false);
            connectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            connectionStatus.setVisibility(View.GONE);
            if (route != null) {
                name.setText(route.name);
                url.setText(route.url.toString());
                ip.setText(route.ip());
            }
        }

        RouteStore.Route route() {
            return new RouteStore.Route(name.getText().toString(), url.getText().toString(), ip.getText().toString());
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private LinearLayout cards;
    private Switch background;
    private TextView status;
    private Button add;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = Ui.page(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.quiet(this, "返回", view -> finish()));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(Ui.button(this, "保存", true, view -> save()));
        Ui.add(page, top, 0);
        Ui.add(page, Ui.heading(this, "连接设置", 30), 16);

        status = Ui.text(this, "主用连接不可达时，会自动尝试下方的备用连接。", 15, Ui.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.add(page, status, 6);
        cards = Ui.column(this);
        Ui.add(page, cards, 5);
        ArrayList<String> draft = state == null ? null : state.getStringArrayList("routesDraft");
        if (draft == null) {
            for (RouteStore.Route route : app().settings.routes()) rows.add(card(route));
            if (rows.isEmpty()) rows.add(card(null));
        } else {
            for (int i = 0; i + 2 < draft.size(); i += 3) {
                Row row = card(null);
                row.name.setText(draft.get(i));
                row.url.setText(draft.get(i + 1));
                row.ip.setText(draft.get(i + 2));
                rows.add(row);
            }
        }
        add = Ui.button(this, "添加备用连接", false, view -> {
            if (rows.size() < RouteStore.MAX_ROUTES) {
                rows.add(card(null));
                showCards();
            }
        });
        Ui.add(page, add, 10);

        LinearLayout mode = Ui.panel(this);
        background = new Switch(this);
        background.setText("分享后后台提取");
        background.setTextColor(getColor(Ui.TEXT));
        background.setTextSize(16);
        background.setMinHeight(Ui.dp(this, 48));
        boolean backgroundShare = state == null ? app().settings.backgroundShare() : state.getBoolean("backgroundShare");
        background.setChecked(Build.VERSION.SDK_INT >= 31 && backgroundShare);
        background.setEnabled(Build.VERSION.SDK_INT >= 31);
        Ui.add(mode, background, 0);
        Ui.add(mode, Ui.text(this, Build.VERSION.SDK_INT >= 31
                ? "若系统限制后台任务或剪贴板，关闭此项可使用前台兼容模式。"
                : "此 Android 版本的分享使用前台兼容模式。", 14, Ui.MUTED, false), 5);
        Ui.add(page, Ui.heading(this, "分享与网络", 18), 24);
        Ui.add(page, mode, 8);
        Ui.add(page, Ui.quiet(this, "打开 Tailscale", view -> Ui.openTailscale(this)), 8);
        showCards();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> draft = new ArrayList<>();
        for (Row row : rows) {
            draft.add(row.name.getText().toString());
            draft.add(row.url.getText().toString());
            draft.add(row.ip.getText().toString());
        }
        out.putStringArrayList("routesDraft", draft);
        out.putBoolean("backgroundShare", background.isChecked());
    }

    private Row card(RouteStore.Route route) {
        Row row = new Row(this, route);
        Ui.add(row.view, row.order, 0);
        Ui.labeledField(row.view, "连接名称", row.name, 12);
        Ui.labeledField(row.view, "HTTPS 根地址", row.url, 10);
        Ui.labeledField(row.view, "连接 IP（可选）", row.ip, 10);
        LinearLayout actions = Ui.row(this);
        row.up = Ui.quiet(this, "上移", view -> move(row, -1));
        row.down = Ui.quiet(this, "下移", view -> move(row, 1));
        Ui.weight(actions, row.up, 0);
        Ui.weight(actions, row.down, 8);
        Ui.add(row.view, actions, 8);
        LinearLayout edits = Ui.row(this);
        Ui.weight(edits, Ui.quiet(this, "删除", view -> {
            rows.remove(row);
            showCards();
        }), 0);
        Ui.weight(edits, Ui.button(this, "测试连接", false, view -> test(row)), 8);
        Ui.add(row.view, edits, 4);
        Ui.add(row.view, row.connectionStatus, 8);
        return row;
    }

    private void move(Row row, int by) {
        int index = rows.indexOf(row);
        int position = index + by;
        if (position >= 0 && position < rows.size()) {
            Collections.swap(rows, index, position);
            showCards();
        }
    }

    private void showCards() {
        cards.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.order.setText(i == 0 ? "主用连接" : "备用连接 " + i);
            row.up.setEnabled(i > 0);
            row.down.setEnabled(i < rows.size() - 1);
            Ui.add(cards, row.view, 10);
        }
        add.setEnabled(rows.size() < RouteStore.MAX_ROUTES);
    }

    private void test(Row row) {
        try {
            RouteStore.Route route = row.route();
            connectionNotice(row, "正在测试 " + route.name + "…", Ui.MUTED);
            runTask(() -> app().api.call(Collections.singletonList(route), "/healthz", null),
                    reply -> connectionNotice(row, route.name + " 可用 · 服务 " + reply.data.optString("version"), Ui.SUCCESS),
                    error -> connectionNotice(row, route.name + " · " + Ui.message(error), Ui.ERROR));
        } catch (IllegalArgumentException error) {
            connectionNotice(row, "请输入有效的名称、HTTPS 根地址及可选的连接 IP。", Ui.ERROR);
        }
    }

    private void connectionNotice(Row row, String text, int color) {
        row.connectionStatus.setVisibility(View.VISIBLE);
        row.connectionStatus.setTextColor(getColor(color));
        row.connectionStatus.setText(text);
    }

    private void save() {
        try {
            if (rows.isEmpty()) {
                notice("请至少添加一条连接。", true);
                return;
            }
            ArrayList<RouteStore.Route> configured = new ArrayList<>();
            for (Row row : rows) configured.add(row.route());
            if (!app().settings.save(configured, background.isChecked())) {
                notice("无法保存连接，请重试。", true);
                return;
            }
            Ui.toast(this, "连接设置已保存");
            finish();
        } catch (Exception error) {
            notice("请检查每条连接的名称、HTTPS 根地址及 IP。", true);
        }
    }

    private void notice(String text, boolean error) {
        status.setTextColor(getColor(error ? Ui.ERROR : Ui.MUTED));
        status.setText(text);
    }
}
