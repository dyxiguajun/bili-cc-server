package dev.xigua.bilicc;

import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
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
        Button up;
        Button down;

        Row(SettingsActivity activity, RouteStore.Route route) {
            view = Ui.panel(activity);
            order = Ui.text(activity, "", 16, Ui.TEXT, true);
            name = Ui.field(activity, "名称，如 局域网", InputType.TYPE_CLASS_TEXT);
            url = Ui.field(activity, "HTTPS 根地址", URI_INPUT);
            ip = Ui.field(activity, "连接 IP（可留空）", URI_INPUT);
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
        Ui.add(page, Ui.text(this, "连接设置", 26, Ui.TEXT, true), 0);
        LinearLayout top = Ui.row(this);
        Ui.weight(top, Ui.button(this, "取消", false, view -> finish()), 0);
        Ui.weight(top, Ui.button(this, "保存", true, view -> save()), 8);
        Ui.add(page, top, 7);

        status = Ui.text(this, "主用连接不可达时，会自动尝试下方的备用连接。", 15, Ui.MUTED, false);
        Ui.add(page, status, 8);
        cards = Ui.column(this);
        Ui.add(page, cards, 5);
        for (RouteStore.Route route : app().settings.routes()) rows.add(card(route));
        if (rows.isEmpty()) rows.add(card(null));
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
        background.setTextColor(Ui.TEXT);
        background.setTextSize(16);
        background.setMinHeight(Ui.dp(this, 48));
        background.setChecked(Build.VERSION.SDK_INT >= 31 && app().settings.backgroundShare());
        background.setEnabled(Build.VERSION.SDK_INT >= 31);
        Ui.add(mode, background, 0);
        Ui.add(mode, Ui.text(this, Build.VERSION.SDK_INT >= 31
                ? "若系统限制后台任务或剪贴板，关闭此项可使用兼容弹层。"
                : "此 Android 版本的分享会使用兼容弹层。", 14, Ui.MUTED, false), 5);
        Ui.add(page, mode, 12);
        Ui.add(page, Ui.button(this, "打开 Tailscale", false, view -> Ui.openTailscale(this)), 10);
        showCards();
    }

    private Row card(RouteStore.Route route) {
        Row row = new Row(this, route);
        Ui.add(row.view, row.order, 0);
        Ui.add(row.view, row.name, 8);
        Ui.add(row.view, row.url, 8);
        Ui.add(row.view, row.ip, 8);
        LinearLayout actions = Ui.row(this);
        row.up = Ui.button(this, "上移", false, view -> move(row, -1));
        row.down = Ui.button(this, "下移", false, view -> move(row, 1));
        Ui.weight(actions, row.up, 0);
        Ui.weight(actions, row.down, 5);
        Ui.weight(actions, Ui.button(this, "删除", false, view -> {
            rows.remove(row);
            showCards();
        }), 5);
        Ui.weight(actions, Ui.button(this, "测试", false, view -> test(row)), 5);
        Ui.add(row.view, actions, 7);
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
            notice("正在测试 " + route.name + "…", false);
            runTask(() -> app().api.call(Collections.singletonList(route), "/healthz", null),
                    reply -> notice(route.name + " 可用 · 服务 " + reply.data.optString("version"), false),
                    error -> notice(route.name + " · " + Ui.message(error), true));
        } catch (IllegalArgumentException error) {
            notice("请输入有效的名称、HTTPS 根地址及可选的连接 IP。", true);
        }
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
        status.setTextColor(error ? Ui.ERROR : Ui.MUTED);
        status.setText(text);
    }
}
