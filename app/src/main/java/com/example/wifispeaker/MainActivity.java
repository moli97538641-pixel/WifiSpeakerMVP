package com.example.wifispeaker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final int REQ_RECORD_AUDIO = 1003;
    private static final String PREFS = "wifi_speaker_prefs";
    private static final String KEY_LAST_HOST = "last_host";

    private EditText hostInput;
    private TextView ipText;
    private TextView statusText;
    private Button discoverButton;
    private MediaProjectionManager projectionManager;
    private Handler mainHandler;
    private volatile boolean discovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Notifications.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        requestRecordAudioPermissionIfNeeded();
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
        title.setText("WiFi Speaker MVP v0.2");
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("平板启动接收端后，手机可以点“搜索平板”自动填 IP；如果 Wi-Fi 断开后恢复，发送端会自动重连。\n音频格式：48kHz / 16bit / stereo PCM，局域网 TCP 直传。");
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
        hostInput.setHint("平板 IP，可手动填，也可点下面自动搜索");
        hostInput.setText(getPrefs().getString(KEY_LAST_HOST, ""));
        root.addView(hostInput, matchWrap());

        discoverButton = new Button(this);
        discoverButton.setText("搜索平板 / Auto discover");
        discoverButton.setOnClickListener(v -> startDiscovery(false));
        root.addView(discoverButton, matchWrap());

        Button startSender = new Button(this);
        startSender.setText("手机：启动发送端 / Push audio");
        startSender.setOnClickListener(v -> requestProjectionAndStartSender());
        root.addView(startSender, matchWrap());

        Button stopSender = new Button(this);
        stopSender.setText("停止发送端");
        stopSender.setOnClickListener(v -> stopService(new Intent(this, SenderService.class).setAction(SenderService.ACTION_STOP)));
        root.addView(stopSender, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(8), 0, dp(4));
        statusText.setText("状态：等待操作");
        root.addView(statusText, matchWrap());

        addDivider(root);

        TextView hintText = new TextView(this);
        hintText.setTextSize(14);
        hintText.setText("注意：发送端需要 Android 10+。某些 App 会禁止被录制，通话/DRM/受保护内容通常采不到。首次启动请允许“录音/麦克风”权限；点发送端后还会弹出系统投屏/录制授权，这是 Android 对播放音频采集的要求。自动搜索要求两台设备在同一个 Wi-Fi，部分访客网络/AP 隔离网络会搜不到。");
        root.addView(hintText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refreshIpText();
    }

    private void refreshIpText() {
        if (ipText != null) {
            String ip = WifiUtils.getLikelyLanIpAddress();
            ipText.setText("本机局域网 IP：" + ip + "\n音频端口：" + Protocol.PORT + "    发现端口：" + Discovery.DISCOVERY_PORT);
        }
    }

    private void startReceiverService() {
        Intent intent = new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_START);
        startCompatService(intent);
        setStatus("接收端已启动。手机端可点“搜索平板”自动发现，也可手动输入这里显示的 IP。");
        Toast.makeText(this, "接收端已启动", Toast.LENGTH_LONG).show();
    }

    private void requestProjectionAndStartSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "发送端需要 Android 10 / API 29 或更高版本", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermissionIfNeeded();
            Toast.makeText(this, "请先允许麦克风/音频录制权限，然后再点一次启动发送端", Toast.LENGTH_LONG).show();
            return;
        }
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            startDiscovery(true);
            return;
        }
        beginProjectionRequest(host);
    }

    private void beginProjectionRequest(String host) {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "没有可用的平板 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        hostInput.setText(host);
        getPrefs().edit().putString(KEY_LAST_HOST, host).apply();
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void startDiscovery(boolean startSenderAfterFound) {
        if (discovering) {
            Toast.makeText(this, "正在搜索，请稍等", Toast.LENGTH_SHORT).show();
            return;
        }
        discovering = true;
        if (discoverButton != null) discoverButton.setEnabled(false);
        setStatus("正在搜索同一 Wi-Fi 下的接收端...");

        new Thread(() -> {
            Discovery.Device device = null;
            Exception error = null;
            try {
                device = Discovery.findFirstReceiver(3000);
            } catch (Exception e) {
                error = e;
            }
            Discovery.Device finalDevice = device;
            Exception finalError = error;
            mainHandler.post(() -> {
                discovering = false;
                if (discoverButton != null) discoverButton.setEnabled(true);
                if (finalDevice != null) {
                    hostInput.setText(finalDevice.host);
                    getPrefs().edit().putString(KEY_LAST_HOST, finalDevice.host).apply();
                    setStatus("已发现接收端：" + finalDevice.name + " / " + finalDevice.host);
                    Toast.makeText(this, "找到平板：" + finalDevice.host, Toast.LENGTH_SHORT).show();
                    if (startSenderAfterFound) beginProjectionRequest(finalDevice.host);
                } else {
                    String msg = "没有搜到接收端。请确认平板已点“启动接收端”，并且两台设备在同一个 Wi-Fi。";
                    if (finalError != null) msg += " 错误：" + finalError.getClass().getSimpleName();
                    setStatus(msg);
                    Toast.makeText(this, "没有搜到平板", Toast.LENGTH_LONG).show();
                }
            });
        }, "wifi-speaker-discovery-ui").start();
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
            setStatus("发送端已启动：" + host + "。如果网络短暂断开，服务会自动重连。") ;
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private boolean hasRecordAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText("状态：" + text);
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
