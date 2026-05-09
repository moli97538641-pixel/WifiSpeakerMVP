package com.example.wifispeaker;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_RECORD_AUDIO = 1003;
    private static final String PREFS = "wifi_speaker_prefs";
    private static final String KEY_LAST_HOST = "last_host";

    private enum ScreenMode {
        HOME,
        RECEIVER,
        SENDER
    }

    private ScreenMode screenMode = ScreenMode.HOME;
    private MediaProjectionManager projectionManager;
    private Handler mainHandler;

    private EditText hostInput;
    private TextView ipText;
    private TextView receiverStatusText;
    private TextView senderStatusText;
    private TextView homeStatusText;
    private TextView selectedDeviceText;
    private LinearLayout deviceListLayout;
    private Button receiverToggleButton;
    private Button senderToggleButton;
    private Button searchButton;

    private volatile boolean discovering = false;
    private boolean receiverRunning = ReceiverService.sRunning;
    private boolean receiverConnected = ReceiverService.sConnected;
    private String receiverMessage = ReceiverService.sMessage;
    private boolean senderRunning = SenderService.sRunning;
    private boolean senderStreaming = SenderService.sStreaming;
    private String senderHost = SenderService.sHost;
    private String senderMessage = SenderService.sMessage;
    private final List<Discovery.Device> discoveredDevices = new ArrayList<>();
    private boolean receiverRegistered = false;

    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (ReceiverService.ACTION_STATE.equals(action)) {
                receiverRunning = intent.getBooleanExtra(ReceiverService.EXTRA_RUNNING, false);
                receiverConnected = intent.getBooleanExtra(ReceiverService.EXTRA_CONNECTED, false);
                receiverMessage = intent.getStringExtra(ReceiverService.EXTRA_MESSAGE);
            } else if (SenderService.ACTION_STATE.equals(action)) {
                senderRunning = intent.getBooleanExtra(SenderService.EXTRA_RUNNING, false);
                senderStreaming = intent.getBooleanExtra(SenderService.EXTRA_STREAMING, false);
                senderHost = intent.getStringExtra(SenderService.EXTRA_HOST);
                senderMessage = intent.getStringExtra(SenderService.EXTRA_MESSAGE);
            }
            updateControls();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        configureSystemBars();
        Notifications.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        // Do not request RECORD_AUDIO on launch. Ask only when this device is used as sender,
        // so the home screen can always open cleanly and Android does not stack permission dialogs.
        showHomeScreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStateReceiver();
    }

    @Override
    protected void onStop() {
        unregisterStateReceiver();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncStateFromServices();
        updateControls();
    }

    @Override
    public void onBackPressed() {
        if (screenMode != ScreenMode.HOME) {
            showHomeScreen();
        } else {
            super.onBackPressed();
        }
    }

    private void configureSystemBars() {
        // v0.3.3: conservative system-bar handling.
        // Keep the app out of edge-to-edge mode so content starts below the status bar.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFFFFFFFF);
            getWindow().setNavigationBarColor(0xFFFFFFFF);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void registerStateReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ReceiverService.ACTION_STATE);
        filter.addAction(SenderService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceStateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterStateReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(serviceStateReceiver);
        } catch (Exception ignored) {}
        receiverRegistered = false;
    }

    private void syncStateFromServices() {
        receiverRunning = ReceiverService.sRunning;
        receiverConnected = ReceiverService.sConnected;
        receiverMessage = ReceiverService.sMessage;
        senderRunning = SenderService.sRunning;
        senderStreaming = SenderService.sStreaming;
        senderHost = SenderService.sHost;
        senderMessage = SenderService.sMessage;
    }

    private void showHomeScreen() {
        screenMode = ScreenMode.HOME;
        clearViewRefs();

        LinearLayout root = baseRoot();
        TextView title = titleText("WiFi Speaker MVP v0.3.3");
        root.addView(title, matchWrap());

        TextView subtitle = bodyText("请选择这台 Android 设备当前要扮演的角色：接收端负责播放收到的音频，发送端负责采集并推送本机播放音频。");
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle, matchWrap());

        Button receiverButton = new Button(this);
        receiverButton.setText("这台设备作为接收端 / 播放音频");
        receiverButton.setTextSize(17);
        receiverButton.setOnClickListener(v -> showReceiverScreen());
        root.addView(receiverButton, bigButtonParams());

        Button senderButton = new Button(this);
        senderButton.setText("这台设备作为发送端 / 推送音频");
        senderButton.setTextSize(17);
        senderButton.setOnClickListener(v -> showSenderScreen());
        root.addView(senderButton, bigButtonParams());

        addDivider(root);

        homeStatusText = bodyText("");
        root.addView(homeStatusText, matchWrap());

        TextView hint = bodyText("说明：发送端需要 Android 10+，并且首次推送时需要允许录音权限和系统投屏/录制授权。接收端和发送端必须在同一个 Wi-Fi 下。");
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint, matchWrap());

        setContentView(wrapScroll(root));
        updateControls();
    }

    private void showReceiverScreen() {
        screenMode = ScreenMode.RECEIVER;
        clearViewRefs();

        LinearLayout root = baseRoot();
        root.addView(backButton(), matchWrap());
        root.addView(titleText("接收端 / 播放音频"), matchWrap());

        TextView subtitle = bodyText("在任意一台 Android 设备上启动接收端后，另一台作为发送端的 Android 设备可以搜索到它，并把音频通过 Wi-Fi 推送过来播放。");
        root.addView(subtitle, matchWrap());

        ipText = bodyText("");
        ipText.setTextSize(17);
        ipText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(ipText, matchWrap());

        receiverStatusText = bodyText("");
        receiverStatusText.setTextSize(16);
        receiverStatusText.setPadding(0, dp(4), 0, dp(8));
        root.addView(receiverStatusText, matchWrap());

        receiverToggleButton = new Button(this);
        receiverToggleButton.setTextSize(17);
        receiverToggleButton.setOnClickListener(v -> toggleReceiver());
        root.addView(receiverToggleButton, bigButtonParams());

        addDivider(root);

        TextView hint = bodyText("启动后请保持 App 或通知栏服务运行。音频端口：" + Protocol.PORT + "；搜索发现端口：" + Discovery.DISCOVERY_PORT + "。如果发送端搜不到，请检查是否连接同一个 Wi-Fi，或者路由器是否开启了 AP 隔离/访客网络隔离。");
        root.addView(hint, matchWrap());

        setContentView(wrapScroll(root));
        updateControls();
    }

    private void showSenderScreen() {
        screenMode = ScreenMode.SENDER;
        clearViewRefs();

        LinearLayout root = baseRoot();
        root.addView(backButton(), matchWrap());
        root.addView(titleText("发送端 / 推送音频"), matchWrap());

        TextView subtitle = bodyText("先搜索接收端并从列表中选择目标设备，然后启动推送。也可以手动输入接收端 IP。");
        root.addView(subtitle, matchWrap());

        senderStatusText = bodyText("");
        senderStatusText.setTextSize(16);
        senderStatusText.setPadding(0, dp(4), 0, dp(6));
        root.addView(senderStatusText, matchWrap());

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("接收端 IP，例如 192.168.1.35");
        String lastHost = getPrefs().getString(KEY_LAST_HOST, "");
        if (senderHost != null && !senderHost.trim().isEmpty()) {
            hostInput.setText(senderHost.trim());
        } else {
            hostInput.setText(lastHost);
        }
        hostInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getPrefs().edit().putString(KEY_LAST_HOST, s == null ? "" : s.toString().trim()).apply();
                updateControls();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(hostInput, matchWrap());

        searchButton = new Button(this);
        searchButton.setOnClickListener(v -> startDiscovery(false));
        root.addView(searchButton, matchWrap());

        selectedDeviceText = bodyText("");
        selectedDeviceText.setPadding(0, dp(8), 0, dp(4));
        root.addView(selectedDeviceText, matchWrap());

        deviceListLayout = new LinearLayout(this);
        deviceListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceListLayout, matchWrap());

        senderToggleButton = new Button(this);
        senderToggleButton.setTextSize(17);
        senderToggleButton.setOnClickListener(v -> toggleSender());
        root.addView(senderToggleButton, bigButtonParams());

        addDivider(root);

        TextView hint = bodyText("提示：搜索结果会显示为列表，点列表中的设备会自动填入 IP。推送启动后按钮会切换为“停止推送”，输入框和搜索按钮会暂时禁用。某些 App 会禁止系统音频采集，测试时建议先用普通浏览器视频或普通音乐播放器。");
        root.addView(hint, matchWrap());

        setContentView(wrapScroll(root));
        renderDeviceList();
        updateControls();
    }

    private void clearViewRefs() {
        hostInput = null;
        ipText = null;
        receiverStatusText = null;
        senderStatusText = null;
        homeStatusText = null;
        selectedDeviceText = null;
        deviceListLayout = null;
        receiverToggleButton = null;
        senderToggleButton = null;
        searchButton = null;
    }

    private void toggleReceiver() {
        if (receiverRunning) {
            Intent intent = new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_STOP);
            startService(intent);
            receiverRunning = false;
            receiverConnected = false;
            receiverMessage = "正在停止接收端...";
            updateControls();
            Toast.makeText(this, "正在停止接收端", Toast.LENGTH_SHORT).show();
        } else {
            Intent intent = new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_START);
            startCompatService(intent);
            receiverRunning = true;
            receiverConnected = false;
            receiverMessage = "正在启动接收端...";
            updateControls();
            Toast.makeText(this, "接收端已启动", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleSender() {
        if (senderRunning) {
            Intent intent = new Intent(this, SenderService.class).setAction(SenderService.ACTION_STOP);
            startService(intent);
            senderRunning = false;
            senderStreaming = false;
            senderMessage = "正在停止推送...";
            updateControls();
            Toast.makeText(this, "正在停止推送", Toast.LENGTH_SHORT).show();
            return;
        }
        requestProjectionAndStartSender();
    }

    private void requestProjectionAndStartSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "发送端需要 Android 10 / API 29 或更高版本", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermissionIfNeeded();
            Toast.makeText(this, "请先允许麦克风/音频录制权限，然后再点一次启动推送", Toast.LENGTH_LONG).show();
            return;
        }
        String host = getHostText();
        if (host.isEmpty()) {
            Toast.makeText(this, "请先搜索并选择接收端，或手动输入 IP", Toast.LENGTH_LONG).show();
            startDiscovery(true);
            return;
        }
        beginProjectionRequest(host);
    }

    private void beginProjectionRequest(String host) {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "没有可用的接收端 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hostInput != null) hostInput.setText(host);
        getPrefs().edit().putString(KEY_LAST_HOST, host).apply();
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void startDiscovery(boolean startSenderAfterFound) {
        if (senderRunning) {
            Toast.makeText(this, "推送运行中，先停止推送后再搜索", Toast.LENGTH_SHORT).show();
            return;
        }
        if (discovering) {
            Toast.makeText(this, "正在搜索，请稍等", Toast.LENGTH_SHORT).show();
            return;
        }
        discovering = true;
        discoveredDevices.clear();
        renderDeviceList();
        updateControls();

        new Thread(() -> {
            List<Discovery.Device> devices = new ArrayList<>();
            Exception error = null;
            try {
                devices = Discovery.findReceivers(3500);
            } catch (Exception e) {
                error = e;
            }
            List<Discovery.Device> finalDevices = devices;
            Exception finalError = error;
            mainHandler.post(() -> {
                discovering = false;
                discoveredDevices.clear();
                discoveredDevices.addAll(finalDevices);
                renderDeviceList();
                if (!finalDevices.isEmpty()) {
                    Discovery.Device first = finalDevices.get(0);
                    setHostText(first.host);
                    getPrefs().edit().putString(KEY_LAST_HOST, first.host).apply();
                    senderMessage = "已发现 " + finalDevices.size() + " 台接收端，请从列表中选择；已默认选择第一台。";
                    Toast.makeText(this, "发现 " + finalDevices.size() + " 台接收端", Toast.LENGTH_SHORT).show();
                    if (startSenderAfterFound) beginProjectionRequest(first.host);
                } else {
                    String msg = "没有搜到接收端。请确认目标 Android 设备已启动接收端，并且两台设备在同一个 Wi-Fi。";
                    if (finalError != null) msg += " 错误：" + finalError.getClass().getSimpleName();
                    senderMessage = msg;
                    Toast.makeText(this, "没有搜到接收端", Toast.LENGTH_LONG).show();
                }
                updateControls();
            });
        }, "wifi-speaker-discovery-ui").start();
    }

    private void renderDeviceList() {
        if (deviceListLayout == null) return;
        deviceListLayout.removeAllViews();
        if (discovering) {
            TextView searchingText = bodyText("正在搜索同一 Wi-Fi 下的接收端...");
            deviceListLayout.addView(searchingText, matchWrap());
            return;
        }
        if (discoveredDevices.isEmpty()) {
            TextView emptyText = bodyText("搜索结果：暂无。请点“搜索接收端设备”，或者手动输入接收端 IP。");
            deviceListLayout.addView(emptyText, matchWrap());
            return;
        }
        TextView listTitle = bodyText("搜索结果：点击设备即可选择");
        deviceListLayout.addView(listTitle, matchWrap());
        for (int i = 0; i < discoveredDevices.size(); i++) {
            Discovery.Device device = discoveredDevices.get(i);
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setText((i + 1) + ". " + device.name + "\n" + device.host + ":" + Protocol.PORT);
            item.setOnClickListener(v -> {
                setHostText(device.host);
                getPrefs().edit().putString(KEY_LAST_HOST, device.host).apply();
                senderMessage = "已选择接收端：" + device.name + " / " + device.host;
                updateControls();
            });
            deviceListLayout.addView(item, matchWrap());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "未获得系统音频采集权限", Toast.LENGTH_LONG).show();
                return;
            }
            String host = getHostText();
            Intent service = new Intent(this, SenderService.class).setAction(SenderService.ACTION_START);
            service.putExtra(SenderService.EXTRA_HOST, host);
            service.putExtra(SenderService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(SenderService.EXTRA_RESULT_DATA, data);
            startCompatService(service);
            senderRunning = true;
            senderStreaming = false;
            senderHost = host;
            senderMessage = "发送端已启动，正在连接 " + host + "...";
            updateControls();
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

    private void updateControls() {
        if (ipText != null) {
            String ip = WifiUtils.getLikelyLanIpAddress();
            ipText.setText("本机局域网 IP：" + ip + "\n音频端口：" + Protocol.PORT + "    发现端口：" + Discovery.DISCOVERY_PORT);
        }

        if (homeStatusText != null) {
            String receiverLine = receiverRunning
                    ? (receiverConnected ? "接收端：运行中，已连接" : "接收端：运行中，等待连接")
                    : "接收端：未运行";
            String senderLine = senderRunning
                    ? (senderStreaming ? "发送端：推送中" : "发送端：运行中，等待连接/重连")
                    : "发送端：未运行";
            homeStatusText.setText(receiverLine + "\n" + senderLine);
        }

        if (receiverStatusText != null) {
            String state = receiverRunning
                    ? (receiverConnected ? "运行中 / 已连接" : "运行中 / 等待连接")
                    : "未启动";
            receiverStatusText.setText("当前状态：" + state + "\n" + safe(receiverMessage));
        }

        if (receiverToggleButton != null) {
            receiverToggleButton.setText(receiverRunning ? "停止接收端" : "启动接收端");
            receiverToggleButton.setEnabled(true);
        }

        if (senderStatusText != null) {
            String state = senderRunning
                    ? (senderStreaming ? "推送中" : "已启动 / 等待连接或重连")
                    : "未启动";
            senderStatusText.setText("当前状态：" + state + "\n" + safe(senderMessage));
        }

        if (searchButton != null) {
            searchButton.setText(discovering ? "正在搜索..." : "搜索接收端设备");
            searchButton.setEnabled(!discovering && !senderRunning);
        }

        if (hostInput != null) {
            hostInput.setEnabled(!senderRunning);
        }

        if (selectedDeviceText != null) {
            String host = getHostText();
            if (host.isEmpty()) {
                selectedDeviceText.setText("当前选择：未选择接收端");
            } else {
                selectedDeviceText.setText("当前选择：" + host + ":" + Protocol.PORT);
            }
        }

        if (senderToggleButton != null) {
            String host = getHostText();
            if (senderRunning) {
                senderToggleButton.setText("停止推送");
                senderToggleButton.setEnabled(true);
            } else if (host.isEmpty()) {
                senderToggleButton.setText("请选择设备或输入 IP 后启动推送");
                senderToggleButton.setEnabled(false);
            } else {
                senderToggleButton.setText("启动推送到 " + host);
                senderToggleButton.setEnabled(!discovering);
            }
        }
    }

    private String getHostText() {
        if (hostInput != null) return hostInput.getText().toString().trim();
        return getPrefs().getString(KEY_LAST_HOST, "").trim();
    }

    private void setHostText(String host) {
        if (hostInput != null) hostInput.setText(host == null ? "" : host.trim());
    }

    private String safe(String text) {
        return text == null || text.trim().isEmpty() ? "无状态消息" : text;
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private LinearLayout baseRoot() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFFFFFFFF);
        return root;
    }

    private ScrollView wrapScroll(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(root);
        return scroll;
    }

    private TextView titleText(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        return title;
    }

    private TextView bodyText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private Button backButton() {
        Button button = new Button(this);
        button.setText("返回主界面");
        button.setOnClickListener(v -> showHomeScreen());
        return button;
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

    private LinearLayout.LayoutParams bigButtonParams() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
