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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_RECORD_AUDIO = 1003;
    private static final String PREFS = "wifi_speaker_prefs";
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_SELECTED_HOSTS = "selected_hosts";
    private static final String KEY_RECEIVER_VOLUME = "receiver_volume_percent";

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
    private TextView receiverVolumeText;
    private SeekBar receiverVolumeSeekBar;
    private LinearLayout deviceListLayout;
    private Button receiverToggleButton;
    private Button senderToggleButton;
    private Button searchButton;

    private Runnable pendingVolumeSend;
    private volatile boolean discovering = false;
    private boolean receiverRunning = ReceiverService.sRunning;
    private boolean receiverConnected = ReceiverService.sConnected;
    private String receiverMessage = ReceiverService.sMessage;
    private int receiverVolumePercent = ReceiverService.sVolumePercent;
    private boolean senderRunning = SenderService.sRunning;
    private boolean senderStreaming = SenderService.sStreaming;
    private String senderHost = SenderService.sHost;
    private String senderMessage = SenderService.sMessage;
    private final List<Discovery.Device> discoveredDevices = new ArrayList<>();
    private final Set<String> selectedHosts = new LinkedHashSet<>();
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
                receiverVolumePercent = intent.getIntExtra(ReceiverService.EXTRA_VOLUME_PERCENT, ReceiverService.sVolumePercent);
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
        // v0.3.5: conservative system-bar handling.
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
        receiverVolumePercent = ReceiverService.sVolumePercent;
        senderRunning = SenderService.sRunning;
        senderStreaming = SenderService.sStreaming;
        senderHost = SenderService.sHost;
        senderMessage = SenderService.sMessage;
    }

    private void showHomeScreen() {
        screenMode = ScreenMode.HOME;
        clearViewRefs();

        LinearLayout root = baseRoot();
        TextView title = titleText("WiFi Speaker MVP v0.3.5");
        root.addView(title, matchWrap());

        TextView subtitle = bodyText("请选择这台设备当前要扮演的角色：Android 端可作为发送端或接收端；Windows 端可通过仓库 windows 目录中的程序作为发送端或接收端。");
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

        TextView hint = bodyText("说明：Android 发送端需要 Android 10+，首次推送时需要允许录音权限和系统投屏/录制授权。Android / Windows 接收端和发送端必须在同一个 Wi-Fi 下。v0.3.5 起支持一对多推送、10ms 时间戳音频帧和低延迟缓冲。");
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

        TextView subtitle = bodyText("在任意一台 Android 设备上启动接收端后，Android 或 Windows 发送端可以把音频通过 Wi-Fi 推送过来播放。");
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

        TextView hint = bodyText("启动后请保持 App 或通知栏服务运行。音频端口：" + Protocol.PORT + "；音量控制端口：" + Protocol.CONTROL_PORT + "；搜索发现端口：" + Discovery.DISCOVERY_PORT + "。如果发送端搜不到，请检查是否连接同一个 Wi-Fi，或者路由器是否开启了 AP 隔离/访客网络隔离。");
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

        TextView subtitle = bodyText("先搜索接收端并从列表中选择一个或多个目标设备，然后启动一对多推送。也可以手动输入多个接收端 IP，用逗号分隔。");
        root.addView(subtitle, matchWrap());

        senderStatusText = bodyText("");
        senderStatusText.setTextSize(16);
        senderStatusText.setPadding(0, dp(4), 0, dp(6));
        root.addView(senderStatusText, matchWrap());

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("接收端 IP，可多个，用逗号分隔，例如 192.168.1.35, 192.168.1.36");
        String savedHosts = getPrefs().getString(KEY_SELECTED_HOSTS, getPrefs().getString(KEY_LAST_HOST, ""));
        if (senderHost != null && !senderHost.trim().isEmpty()) {
            hostInput.setText(senderHost.trim());
        } else {
            hostInput.setText(savedHosts);
        }
        syncSelectedHostsFromInput();
        hostInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString().trim();
                getPrefs().edit()
                        .putString(KEY_LAST_HOST, firstHost(value))
                        .putString(KEY_SELECTED_HOSTS, Protocol.normalizeHosts(value))
                        .apply();
                syncSelectedHostsFromInput();
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

        receiverVolumeText = bodyText("");
        receiverVolumeText.setTextSize(16);
        receiverVolumeText.setPadding(0, dp(10), 0, 0);
        root.addView(receiverVolumeText, matchWrap());

        receiverVolumeSeekBar = new SeekBar(this);
        receiverVolumeSeekBar.setMax(100);
        receiverVolumeSeekBar.setProgress(getSavedReceiverVolumePercent());
        receiverVolumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int volume = Protocol.clampVolume(progress);
                getPrefs().edit().putInt(KEY_RECEIVER_VOLUME, volume).apply();
                updateReceiverVolumeUi();
                if (fromUser) scheduleReceiverVolumeSend(volume);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int volume = Protocol.clampVolume(seekBar.getProgress());
                sendReceiverVolumeSoon(volume, 0);
            }
        });
        root.addView(receiverVolumeSeekBar, matchWrap());

        deviceListLayout = new LinearLayout(this);
        deviceListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceListLayout, matchWrap());

        senderToggleButton = new Button(this);
        senderToggleButton.setTextSize(17);
        senderToggleButton.setOnClickListener(v -> toggleSender());
        root.addView(senderToggleButton, bigButtonParams());

        addDivider(root);

        TextView hint = bodyText("提示：搜索结果会显示为列表，点列表中的设备可以选择或取消选择。推送启动后按钮会切换为“停止推送”，输入框和搜索按钮会暂时禁用。音量滑条会控制所有已选择接收端的 App 内部播放增益，不会改变发送端系统音量。某些 App 会禁止系统音频采集，测试时建议先用普通浏览器视频或普通音乐播放器。");
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
        receiverVolumeText = null;
        receiverVolumeSeekBar = null;
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
        String hosts = getHostsText();
        if (Protocol.parseHosts(hosts).isEmpty()) {
            Toast.makeText(this, "请先搜索并选择至少一个接收端，或手动输入 IP", Toast.LENGTH_LONG).show();
            startDiscovery(true);
            return;
        }
        beginProjectionRequest(hosts);
    }

    private void beginProjectionRequest(String hostsText) {
        String normalized = Protocol.normalizeHosts(hostsText);
        if (Protocol.parseHosts(normalized).isEmpty()) {
            Toast.makeText(this, "没有可用的接收端 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        setHostsText(normalized);
        getPrefs().edit()
                .putString(KEY_LAST_HOST, firstHost(normalized))
                .putString(KEY_SELECTED_HOSTS, normalized)
                .apply();
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
                    if (getSelectedHostList().isEmpty()) {
                        for (Discovery.Device device : finalDevices) {
                            selectedHosts.add(device.host);
                        }
                        setHostsText(Protocol.joinHosts(new ArrayList<>(selectedHosts)));
                    }
                    renderDeviceList();
                    sendReceiverVolumeSoon(getSavedReceiverVolumePercent(), 200);
                    senderMessage = "已发现 " + finalDevices.size() + " 台接收端；已选择 " + getSelectedHostList().size() + " 台，可点列表增减。";
                    Toast.makeText(this, "发现 " + finalDevices.size() + " 台接收端", Toast.LENGTH_SHORT).show();
                    if (startSenderAfterFound) beginProjectionRequest(getHostsText());
                } else {
                    String msg = "没有搜到接收端。请确认目标 Android / Windows 设备已启动接收端，并且设备在同一个 Wi-Fi。";
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
        TextView listTitle = bodyText("搜索结果：点击设备可选择 / 取消选择");
        deviceListLayout.addView(listTitle, matchWrap());

        Button selectAllButton = new Button(this);
        selectAllButton.setText("选择全部搜索结果");
        selectAllButton.setEnabled(!senderRunning);
        selectAllButton.setOnClickListener(v -> {
            syncSelectedHostsFromInput();
            for (Discovery.Device device : discoveredDevices) {
                selectedHosts.add(device.host);
            }
            setHostsText(Protocol.joinHosts(new ArrayList<>(selectedHosts)));
            sendReceiverVolumeSoon(getSavedReceiverVolumePercent(), 200);
            senderMessage = "已选择全部搜索结果，共 " + getSelectedHostList().size() + " 台接收端。";
            renderDeviceList();
            updateControls();
        });
        deviceListLayout.addView(selectAllButton, matchWrap());

        Button clearButton = new Button(this);
        clearButton.setText("清空已选接收端");
        clearButton.setEnabled(!senderRunning);
        clearButton.setOnClickListener(v -> {
            setHostsText("");
            senderMessage = "已清空接收端选择。";
            renderDeviceList();
            updateControls();
        });
        deviceListLayout.addView(clearButton, matchWrap());

        for (int i = 0; i < discoveredDevices.size(); i++) {
            Discovery.Device device = discoveredDevices.get(i);
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            boolean selected = isHostSelected(device.host);
            item.setText((selected ? "[已选择] " : "[未选择] ") + (i + 1) + ". " + device.name + "\n" + device.host + ":" + Protocol.PORT);
            item.setEnabled(!senderRunning);
            item.setOnClickListener(v -> {
                toggleSelectedHost(device.host);
                sendReceiverVolumeSoon(getSavedReceiverVolumePercent(), 200);
                senderMessage = "当前已选择 " + getSelectedHostList().size() + " 台接收端。";
                renderDeviceList();
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
            String hosts = getHostsText();
            Intent service = new Intent(this, SenderService.class).setAction(SenderService.ACTION_START);
            service.putExtra(SenderService.EXTRA_HOST, firstHost(hosts));
            service.putExtra(SenderService.EXTRA_HOSTS, hosts);
            service.putExtra(SenderService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(SenderService.EXTRA_RESULT_DATA, data);
            startCompatService(service);
            senderRunning = true;
            senderStreaming = false;
            senderHost = hosts;
            sendReceiverVolumeSoon(getSavedReceiverVolumePercent(), 500);
            senderMessage = "发送端已启动，正在连接 " + Protocol.parseHosts(hosts).size() + " 台接收端...";
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
            ipText.setText("本机局域网 IP：" + ip + "\n音频端口：" + Protocol.PORT + "    音量控制端口：" + Protocol.CONTROL_PORT + "    发现端口：" + Discovery.DISCOVERY_PORT);
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
            receiverStatusText.setText("当前状态：" + state + "\n接收端应用内音量：" + receiverVolumePercent + "%\n" + safe(receiverMessage));
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
            List<String> hosts = getSelectedHostList();
            if (hosts.isEmpty()) {
                selectedDeviceText.setText("当前选择：未选择接收端");
            } else {
                selectedDeviceText.setText("当前选择：" + hosts.size() + " 台接收端\n" + Protocol.joinHosts(hosts));
            }
        }

        updateReceiverVolumeUi();

        if (senderToggleButton != null) {
            List<String> hosts = getSelectedHostList();
            if (senderRunning) {
                senderToggleButton.setText("停止推送");
                senderToggleButton.setEnabled(true);
            } else if (hosts.isEmpty()) {
                senderToggleButton.setText("请选择至少一台设备或输入 IP 后启动推送");
                senderToggleButton.setEnabled(false);
            } else if (hosts.size() == 1) {
                senderToggleButton.setText("启动推送到 " + hosts.get(0));
                senderToggleButton.setEnabled(!discovering);
            } else {
                senderToggleButton.setText("启动一对多推送（" + hosts.size() + " 台）");
                senderToggleButton.setEnabled(!discovering);
            }
        }
    }

    private int getSavedReceiverVolumePercent() {
        return Protocol.clampVolume(getPrefs().getInt(KEY_RECEIVER_VOLUME, 100));
    }

    private void updateReceiverVolumeUi() {
        if (receiverVolumeText == null && receiverVolumeSeekBar == null) return;
        int volume = getSavedReceiverVolumePercent();
        List<String> hosts = getSelectedHostList();
        if (receiverVolumeText != null) {
            String suffix = hosts.isEmpty()
                    ? "（请先选择接收端）"
                    : "（控制已选择的 " + hosts.size() + " 台接收端）";
            receiverVolumeText.setText("接收端音量：" + volume + "% " + suffix);
        }
        if (receiverVolumeSeekBar != null) {
            if (receiverVolumeSeekBar.getProgress() != volume) {
                receiverVolumeSeekBar.setProgress(volume);
            }
            receiverVolumeSeekBar.setEnabled(!hosts.isEmpty());
        }
    }

    private void scheduleReceiverVolumeSend(int volumePercent) {
        sendReceiverVolumeSoon(volumePercent, 250);
    }

    private void sendReceiverVolumeSoon(int volumePercent, long delayMs) {
        int volume = Protocol.clampVolume(volumePercent);
        if (pendingVolumeSend != null) {
            mainHandler.removeCallbacks(pendingVolumeSend);
            pendingVolumeSend = null;
        }
        pendingVolumeSend = () -> {
            pendingVolumeSend = null;
            sendReceiverVolumeCommand(volume);
        };
        mainHandler.postDelayed(pendingVolumeSend, delayMs);
    }

    private void sendReceiverVolumeCommand(int volumePercent) {
        List<String> hosts = getSelectedHostList();
        if (hosts.isEmpty()) {
            senderMessage = "请先选择接收端，再调节接收端音量。";
            updateControls();
            return;
        }
        int volume = Protocol.clampVolume(volumePercent);
        new Thread(() -> {
            int okCount = 0;
            int failCount = 0;
            for (String host : hosts) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, Protocol.CONTROL_PORT), 1200);
                    s.setTcpNoDelay(true);
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    Protocol.writeVolumeCommand(out, volume);
                    okCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }
            int finalOkCount = okCount;
            int finalFailCount = failCount;
            mainHandler.post(() -> {
                if (finalFailCount == 0) {
                    senderMessage = "已向 " + finalOkCount + " 台接收端发送音量：" + volume + "%";
                } else {
                    senderMessage = "音量命令完成：成功 " + finalOkCount + " 台，失败 " + finalFailCount + " 台。请确认接收端已启动且在同一 Wi-Fi。";
                }
                updateControls();
            });
        }, "wifi-speaker-volume-send").start();
    }

    private String getHostsText() {
        if (hostInput != null) return Protocol.normalizeHosts(hostInput.getText().toString());
        return Protocol.normalizeHosts(getPrefs().getString(KEY_SELECTED_HOSTS, getPrefs().getString(KEY_LAST_HOST, "")));
    }

    private List<String> getSelectedHostList() {
        return Protocol.parseHosts(getHostsText());
    }

    private void syncSelectedHostsFromInput() {
        selectedHosts.clear();
        selectedHosts.addAll(Protocol.parseHosts(hostInput == null ? getHostsText() : hostInput.getText().toString()));
    }

    private void setHostsText(String hosts) {
        String normalized = Protocol.normalizeHosts(hosts);
        selectedHosts.clear();
        selectedHosts.addAll(Protocol.parseHosts(normalized));
        if (hostInput != null) hostInput.setText(normalized);
        getPrefs().edit()
                .putString(KEY_LAST_HOST, firstHost(normalized))
                .putString(KEY_SELECTED_HOSTS, normalized)
                .apply();
    }

    private void toggleSelectedHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) return;
        syncSelectedHostsFromInput();
        if (selectedHosts.contains(value)) {
            selectedHosts.remove(value);
        } else {
            selectedHosts.add(value);
        }
        setHostsText(Protocol.joinHosts(new ArrayList<>(selectedHosts)));
    }

    private boolean isHostSelected(String host) {
        syncSelectedHostsFromInput();
        return selectedHosts.contains(host == null ? "" : host.trim());
    }

    private String firstHost(String hostsText) {
        List<String> hosts = Protocol.parseHosts(hostsText);
        return hosts.isEmpty() ? "" : hosts.get(0);
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
