package com.example.wifispeaker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final String PREFS = "wifi_speaker_prefs";
    private static final String KEY_LAST_HOST = "last_host";

    private EditText hostInput;
    private TextView ipText;
    private TextView hintText;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Notifications.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshIpText();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("WiFi Speaker MVP");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("同一个 App：平板点“启动接收端”，手机填平板 IP 后点“启动发送端”。\n音频格式：48kHz / 16bit / stereo PCM，局域网 TCP 直传。");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle, matchWrap());

        ipText = new TextView(this);
        ipText.setTextSize(18);
        ipText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(ipText, matchWrap());

        Button startReceiver = new Button(this);
        startReceiver.setText("平板：启动接收端 / Speaker");
        startReceiver.setOnClickListener(v -> startReceiverService());
        root.addView(startReceiver, matchWrap());

        Button stopReceiver = new Button(this);
        stopReceiver.setText("停止接收端");
        stopReceiver.setOnClickListener(v -> stopService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_STOP)));
        root.addView(stopReceiver, matchWrap());

        addDivider(root);

        TextView senderTitle = new TextView(this);
        senderTitle.setText("发送端设置");
        senderTitle.setTextSize(19);
        senderTitle.setPadding(0, dp(8), 0, dp(6));
        root.addView(senderTitle, matchWrap());

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("输入平板 IP，例如 192.168.1.23");
        hostInput.setText(getPrefs().getString(KEY_LAST_HOST, ""));
        root.addView(hostInput, matchWrap());

        Button startSender = new Button(this);
        startSender.setText("手机：启动发送端 / Push audio");
        startSender.setOnClickListener(v -> requestProjectionAndStartSender());
        root.addView(startSender, matchWrap());

        Button stopSender = new Button(this);
        stopSender.setText("停止发送端");
        stopSender.setOnClickListener(v -> stopService(new Intent(this, SenderService.class).setAction(SenderService.ACTION_STOP)));
        root.addView(stopSender, matchWrap());

        addDivider(root);

        hintText = new TextView(this);
        hintText.setTextSize(14);
        hintText.setText("注意：发送端需要 Android 10+。某些 App 会禁止被录制，通话/DRM/受保护内容通常采不到。首次启动会弹出系统投屏/录音授权，这是 Android 对播放音频采集的要求。");
        root.addView(hintText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refreshIpText();
    }

    private void refreshIpText() {
        if (ipText != null) {
            String ip = WifiUtils.getLikelyLanIpAddress();
            ipText.setText("本机局域网 IP：" + ip + "\n接收端端口：" + Protocol.PORT);
        }
    }

    private void startReceiverService() {
        Intent intent = new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_START);
        startCompatService(intent);
        Toast.makeText(this, "接收端已启动，请在发送端填写这里显示的 IP", Toast.LENGTH_LONG).show();
    }

    private void requestProjectionAndStartSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "发送端需要 Android 10 / API 29 或更高版本", Toast.LENGTH_LONG).show();
            return;
        }
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "先输入平板接收端 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        getPrefs().edit().putString(KEY_LAST_HOST, host).apply();
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "未获得系统音频采集权限", Toast.LENGTH_LONG).show();
                return;
            }
            String host = hostInput.getText().toString().trim();
            Intent service = new Intent(this, SenderService.class).setAction(SenderService.ACTION_START);
            service.putExtra(SenderService.EXTRA_HOST, host);
            service.putExtra(SenderService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(SenderService.EXTRA_RESULT_DATA, data);
            startCompatService(service);
            Toast.makeText(this, "发送端已启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCompatService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void addDivider(LinearLayout root) {
        View divider = new View(this);
        divider.setBackgroundColor(0x22000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(16), 0, dp(10));
        root.addView(divider, lp);
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
