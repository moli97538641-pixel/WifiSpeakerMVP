using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Drawing;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Windows.Forms;
using NAudio.Wave;

namespace WifiSpeaker.Win;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainForm());
    }
}

internal sealed class MainForm : Form
{
    private readonly TabControl _tabs = new();
    private readonly Label _title = new();
    private readonly TextBox _logBox = new();

    private Button _receiverStartButton = null!;
    private Button _receiverStopButton = null!;
    private Label _receiverStatusLabel = null!;
    private Label _receiverIpLabel = null!;

    private Button _discoverButton = null!;
    private CheckedListBox _deviceList = null!;
    private TextBox _manualHostsBox = null!;
    private Button _selectAllButton = null!;
    private Button _clearSelectionButton = null!;
    private Button _senderStartButton = null!;
    private Button _senderStopButton = null!;
    private Label _senderStatusLabel = null!;
    private TrackBar _volumeTrackBar = null!;
    private Label _volumeLabel = null!;
    private System.Windows.Forms.Timer _volumeDebounceTimer = null!;

    private CancellationTokenSource? _receiverCts;
    private Task? _receiverTask;
    private Receiver? _receiver;

    private CancellationTokenSource? _senderCts;
    private Task? _senderTask;
    private Sender? _sender;

    public MainForm()
    {
        Text = "WifiSpeakerMVP v0.3.5";
        MinimumSize = new Size(860, 620);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);

        BuildUi();
        UpdateReceiverUi(false, "未启动");
        UpdateSenderUi(false, "未启动");
        Log("Windows 图形端已启动。请选择接收端或发送端。", false);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        StopSender();
        StopReceiver();
        base.OnFormClosing(e);
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(14),
        };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 72));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 28));
        Controls.Add(root);

        _title.Text = "WifiSpeakerMVP v0.3.5  |  Windows 图形端";
        _title.Font = new Font(Font.FontFamily, 14, FontStyle.Bold);
        _title.Dock = DockStyle.Fill;
        _title.TextAlign = ContentAlignment.MiddleLeft;
        root.Controls.Add(_title, 0, 0);

        _tabs.Dock = DockStyle.Fill;
        _tabs.TabPages.Add(BuildReceiverTab());
        _tabs.TabPages.Add(BuildSenderTab());
        root.Controls.Add(_tabs, 0, 1);

        _logBox.Dock = DockStyle.Fill;
        _logBox.Multiline = true;
        _logBox.ReadOnly = true;
        _logBox.ScrollBars = ScrollBars.Vertical;
        _logBox.BackColor = Color.White;
        root.Controls.Add(_logBox, 0, 2);
    }

    private TabPage BuildReceiverTab()
    {
        var page = new TabPage("接收端");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 8,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var i = 0; i < 8; i++) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, i == 7 ? 120 : 42));
        page.Controls.Add(layout);

        AddLabel(layout, "模式：", 0, 0);
        AddValueLabel(layout, "当前 Windows 设备作为接收端，接收 Android / Windows 发送端音频并播放。", 1, 0);

        AddLabel(layout, "本机 IP：", 0, 1);
        _receiverIpLabel = AddValueLabel(layout, WifiNet.GetLocalIpAddress(), 1, 1);

        AddLabel(layout, "音频端口：", 0, 2);
        AddValueLabel(layout, $"{Protocol.Port}，发现端口 {Discovery.Port}，控制端口 {Protocol.ControlPort}", 1, 2);

        AddLabel(layout, "状态：", 0, 3);
        _receiverStatusLabel = AddValueLabel(layout, "未启动", 1, 3);

        var buttonPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        _receiverStartButton = new Button { Text = "启动接收端", AutoSize = true, Height = 32 };
        _receiverStopButton = new Button { Text = "停止接收端", AutoSize = true, Height = 32 };
        _receiverStartButton.Click += (_, _) => StartReceiver();
        _receiverStopButton.Click += (_, _) => StopReceiver();
        buttonPanel.Controls.Add(_receiverStartButton);
        buttonPanel.Controls.Add(_receiverStopButton);
        layout.Controls.Add(buttonPanel, 1, 4);

        var hint = new Label
        {
            Text = "说明：启动后，同一 Wi-Fi 下的发送端可以搜索到这台 Windows 设备。接收端音量由发送端滑条控制，作用于本程序播放音量，不改系统音量。",
            Dock = DockStyle.Fill,
            AutoSize = false,
        };
        layout.SetColumnSpan(hint, 2);
        layout.Controls.Add(hint, 0, 6);

        return page;
    }

    private TabPage BuildSenderTab()
    {
        var page = new TabPage("发送端");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 10,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 45));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 70));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 20));
        page.Controls.Add(layout);

        AddLabel(layout, "模式：", 0, 0);
        AddValueLabel(layout, "当前 Windows 设备作为发送端，采集本机系统播放音频，一对多推送到已选择接收端。", 1, 0);

        _discoverButton = new Button { Text = "搜索接收端设备", AutoSize = true, Height = 32 };
        _discoverButton.Click += async (_, _) => await DiscoverAsync();
        layout.Controls.Add(_discoverButton, 1, 1);

        AddLabel(layout, "搜索结果：", 0, 2);
        _deviceList = new CheckedListBox
        {
            Dock = DockStyle.Fill,
            CheckOnClick = true,
            IntegralHeight = false,
        };
        layout.Controls.Add(_deviceList, 1, 2);

        var selectionPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        _selectAllButton = new Button { Text = "选择全部搜索结果", AutoSize = true, Height = 32 };
        _clearSelectionButton = new Button { Text = "清空已选接收端", AutoSize = true, Height = 32 };
        _selectAllButton.Click += (_, _) =>
        {
            for (var i = 0; i < _deviceList.Items.Count; i++) _deviceList.SetItemChecked(i, true);
            UpdateStartButtonText();
        };
        _clearSelectionButton.Click += (_, _) =>
        {
            for (var i = 0; i < _deviceList.Items.Count; i++) _deviceList.SetItemChecked(i, false);
            _manualHostsBox.Clear();
            UpdateStartButtonText();
        };
        selectionPanel.Controls.Add(_selectAllButton);
        selectionPanel.Controls.Add(_clearSelectionButton);
        layout.Controls.Add(selectionPanel, 1, 3);

        AddLabel(layout, "手动地址：", 0, 4);
        _manualHostsBox = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            PlaceholderText = "可选：多个 IP 用逗号、空格或换行分隔，例如 192.168.1.35, 192.168.1.36",
        };
        _manualHostsBox.TextChanged += (_, _) => UpdateStartButtonText();
        _deviceList.ItemCheck += (_, _) => BeginInvoke(new Action(UpdateStartButtonText));
        layout.Controls.Add(_manualHostsBox, 1, 4);

        AddLabel(layout, "接收端音量：", 0, 5);
        var volumePanel = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2 };
        volumePanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        volumePanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 70));
        _volumeTrackBar = new TrackBar { Dock = DockStyle.Fill, Minimum = 0, Maximum = 100, Value = 80, TickFrequency = 10 };
        _volumeLabel = new Label { Text = "80%", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
        _volumeTrackBar.Scroll += (_, _) =>
        {
            _volumeLabel.Text = _volumeTrackBar.Value + "%";
            _volumeDebounceTimer.Stop();
            _volumeDebounceTimer.Start();
        };
        _volumeDebounceTimer = new System.Windows.Forms.Timer { Interval = 250 };
        _volumeDebounceTimer.Tick += async (_, _) =>
        {
            _volumeDebounceTimer.Stop();
            await SendVolumeToSelectedAsync();
        };
        volumePanel.Controls.Add(_volumeTrackBar, 0, 0);
        volumePanel.Controls.Add(_volumeLabel, 1, 0);
        layout.Controls.Add(volumePanel, 1, 5);

        var sendPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        _senderStartButton = new Button { Text = "启动推送", AutoSize = true, Height = 32 };
        _senderStopButton = new Button { Text = "停止推送", AutoSize = true, Height = 32 };
        _senderStartButton.Click += (_, _) => StartSender();
        _senderStopButton.Click += (_, _) => StopSender();
        sendPanel.Controls.Add(_senderStartButton);
        sendPanel.Controls.Add(_senderStopButton);
        layout.Controls.Add(sendPanel, 1, 6);

        AddLabel(layout, "状态：", 0, 7);
        _senderStatusLabel = AddValueLabel(layout, "未启动", 1, 7);

        return page;
    }

    private static void AddLabel(TableLayoutPanel layout, string text, int col, int row)
    {
        layout.Controls.Add(new Label { Text = text, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleRight, Font = new Font(SystemFonts.DefaultFont, FontStyle.Bold) }, col, row);
    }

    private static Label AddValueLabel(TableLayoutPanel layout, string text, int col, int row)
    {
        var label = new Label { Text = text, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft, AutoEllipsis = true };
        layout.Controls.Add(label, col, row);
        return label;
    }

    private void StartReceiver()
    {
        if (_receiverCts != null) return;
        _receiverCts = new CancellationTokenSource();
        _receiver = new Receiver(msg => Log(msg), status => SafeUi(() => UpdateReceiverUi(true, status)));
        UpdateReceiverUi(true, "启动中");
        _receiverTask = Task.Run(async () =>
        {
            try { await _receiver.RunAsync(_receiverCts.Token); }
            catch (OperationCanceledException) { }
            catch (Exception ex) { Log("接收端异常：" + ex.Message); }
            finally { SafeUi(() => { _receiverCts = null; UpdateReceiverUi(false, "已停止"); }); }
        });
    }

    private void StopReceiver()
    {
        var cts = _receiverCts;
        if (cts == null) return;
        cts.Cancel();
        _receiverCts = null;
        UpdateReceiverUi(false, "正在停止");
    }

    private async Task DiscoverAsync()
    {
        _discoverButton.Enabled = false;
        Log("开始搜索接收端设备...");
        try
        {
            var devices = await Discovery.FindReceiversAsync(TimeSpan.FromSeconds(2), CancellationToken.None);
            foreach (var device in devices)
            {
                if (!DeviceItemExists(device.Host))
                {
                    _deviceList.Items.Add(new DeviceListItem(device.Host, device.Name), true);
                }
            }
            Log(devices.Count == 0 ? "未发现接收端。可手动输入 IP。" : $"发现 {devices.Count} 个接收端。", false);
        }
        catch (Exception ex)
        {
            Log("搜索失败：" + ex.Message);
        }
        finally
        {
            _discoverButton.Enabled = _senderCts == null;
            UpdateStartButtonText();
        }
    }

    private bool DeviceItemExists(string host)
    {
        foreach (var item in _deviceList.Items)
        {
            if (item is DeviceListItem d && string.Equals(d.Host, host, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private void StartSender()
    {
        if (_senderCts != null) return;
        var hosts = GetSelectedHosts();
        if (hosts.Count == 0)
        {
            MessageBox.Show(this, "请先搜索并选择接收端，或手动输入接收端 IP。", "没有接收端", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        _senderCts = new CancellationTokenSource();
        _sender = new Sender(hosts, msg => Log(msg), status => SafeUi(() => UpdateSenderUi(true, status)));
        UpdateSenderUi(true, "启动中");
        _senderTask = Task.Run(async () =>
        {
            try { await _sender.RunAsync(_senderCts.Token); }
            catch (OperationCanceledException) { }
            catch (Exception ex) { Log("发送端异常：" + ex.Message); }
            finally { SafeUi(() => { _senderCts = null; UpdateSenderUi(false, "已停止"); }); }
        });
        _ = SendVolumeToSelectedAsync();
    }

    private void StopSender()
    {
        var cts = _senderCts;
        if (cts == null) return;
        cts.Cancel();
        _sender?.Stop();
        _senderCts = null;
        UpdateSenderUi(false, "正在停止");
    }

    private async Task SendVolumeToSelectedAsync()
    {
        var hosts = GetSelectedHosts();
        if (hosts.Count == 0) return;
        var volume = _volumeTrackBar.Value;
        foreach (var host in hosts)
        {
            try { await Protocol.SendVolumeAsync(host, volume, CancellationToken.None); }
            catch (Exception ex) { Log($"音量控制失败 {host}：{ex.Message}"); }
        }
    }

    private List<string> GetSelectedHosts()
    {
        var hosts = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in _deviceList.CheckedItems)
        {
            if (item is DeviceListItem d && !string.IsNullOrWhiteSpace(d.Host)) hosts.Add(d.Host);
        }
        foreach (var host in Protocol.ParseHosts(_manualHostsBox.Text)) hosts.Add(host);
        return hosts.ToList();
    }

    private void UpdateReceiverUi(bool running, string status)
    {
        _receiverStatusLabel.Text = status;
        _receiverIpLabel.Text = WifiNet.GetLocalIpAddress();
        _receiverStartButton.Enabled = !running;
        _receiverStopButton.Enabled = running;
    }

    private void UpdateSenderUi(bool running, string status)
    {
        _senderStatusLabel.Text = status;
        _discoverButton.Enabled = !running;
        _deviceList.Enabled = !running;
        _manualHostsBox.Enabled = !running;
        _selectAllButton.Enabled = !running;
        _clearSelectionButton.Enabled = !running;
        _senderStartButton.Enabled = !running && GetSelectedHosts().Count > 0;
        _senderStopButton.Enabled = running;
        UpdateStartButtonText();
    }

    private void UpdateStartButtonText()
    {
        if (_senderStartButton == null) return;
        var count = GetSelectedHosts().Count;
        _senderStartButton.Text = count > 0 ? $"启动推送到 {count} 个接收端" : "启动推送";
        if (_senderCts == null) _senderStartButton.Enabled = count > 0;
    }

    private void Log(string message, bool timestamp = true)
    {
        SafeUi(() =>
        {
            var line = timestamp ? $"[{DateTime.Now:HH:mm:ss}] {message}" : message;
            _logBox.AppendText(line + Environment.NewLine);
        });
    }

    private void SafeUi(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(action);
        else action();
    }

    private sealed record DeviceListItem(string Host, string Name)
    {
        public override string ToString() => string.IsNullOrWhiteSpace(Name) ? Host : $"{Name}  ({Host})";
    }
}

internal sealed class Sender
{
    private readonly List<string> _hosts;
    private readonly Action<string> _log;
    private readonly Action<string> _status;
    private readonly List<ClientSender> _clients = new();
    private const int FrameMs = Protocol.DefaultFrameMs;

    public Sender(List<string> hosts, Action<string> log, Action<string> status)
    {
        _hosts = hosts.Where(h => !string.IsNullOrWhiteSpace(h)).Distinct().ToList();
        _log = log;
        _status = status;
        if (_hosts.Count == 0) throw new ArgumentException("没有有效接收端地址");
    }

    public async Task RunAsync(CancellationToken token)
    {
        using var capture = new WasapiLoopbackCapture();
        var format = capture.WaveFormat;
        if (!IsFormatSupported(format))
        {
            throw new NotSupportedException($"当前 Windows 输出格式暂不支持：{format.Encoding}, {format.SampleRate}Hz, {format.Channels}ch, {format.BitsPerSample}bit");
        }

        _log($"Windows 发送端启动：{format.SampleRate}Hz, {format.Channels}ch, {format.BitsPerSample}bit -> PCM16 stereo");
        _log("目标接收端：" + string.Join(", ", _hosts));
        _log("提示：若没有采集到声音，请先在 Windows 上播放一段音频。");

        foreach (var host in _hosts)
        {
            var client = new ClientSender(host, format.SampleRate, 2, FrameMs, _log);
            _clients.Add(client);
            _ = client.RunAsync(token);
        }

        var packetizer = new PcmPacketizer(format.SampleRate, 2, FrameMs);
        capture.DataAvailable += (_, e) =>
        {
            try
            {
                var pcm = ConvertCaptureToStereoPcm16(e.Buffer, e.BytesRecorded, format);
                foreach (var packet in packetizer.PushPcm(pcm))
                {
                    foreach (var client in _clients) client.Offer(packet);
                }
            }
            catch (Exception ex)
            {
                _log("采集转换异常：" + ex.Message);
            }
        };

        capture.RecordingStopped += (_, e) =>
        {
            if (e.Exception != null) _log("采集停止：" + e.Exception.Message);
        };

        capture.StartRecording();
        try
        {
            while (!token.IsCancellationRequested)
            {
                var connected = _clients.Count(c => c.Connected);
                _status($"推送中：{connected}/{_clients.Count} 已连接");
                await Task.Delay(1000, token);
            }
        }
        finally
        {
            capture.StopRecording();
            Stop();
            _log("发送端已停止");
        }
    }

    public void Stop()
    {
        foreach (var client in _clients) client.Stop();
    }

    private static bool IsFormatSupported(WaveFormat format)
    {
        return format.BitsPerSample is 16 or 24 or 32;
    }

    private static byte[] ConvertCaptureToStereoPcm16(byte[] buffer, int bytesRecorded, WaveFormat format)
    {
        var sourceChannels = Math.Max(1, format.Channels);
        var sourceBytesPerSample = Math.Max(1, format.BitsPerSample / 8);
        var sourceFrameBytes = sourceBytesPerSample * sourceChannels;
        var frames = bytesRecorded / sourceFrameBytes;
        var output = new byte[frames * 2 * 2];
        var outOffset = 0;

        for (var frame = 0; frame < frames; frame++)
        {
            var frameOffset = frame * sourceFrameBytes;
            var left = ReadSampleAsPcm16(buffer, frameOffset, format);
            var right = sourceChannels > 1 ? ReadSampleAsPcm16(buffer, frameOffset + sourceBytesPerSample, format) : left;
            BinaryPrimitives.WriteInt16LittleEndian(output.AsSpan(outOffset, 2), left);
            BinaryPrimitives.WriteInt16LittleEndian(output.AsSpan(outOffset + 2, 2), right);
            outOffset += 4;
        }

        return output;
    }

    private static short ReadSampleAsPcm16(byte[] buffer, int offset, WaveFormat format)
    {
        if (format.BitsPerSample == 32 && format.Encoding == WaveFormatEncoding.IeeeFloat)
        {
            return FloatToPcm16(BitConverter.ToSingle(buffer, offset));
        }
        if (format.BitsPerSample == 32)
        {
            var v = BinaryPrimitives.ReadInt32LittleEndian(buffer.AsSpan(offset, 4));
            return (short)(v >> 16);
        }
        if (format.BitsPerSample == 24)
        {
            var v = buffer[offset] | (buffer[offset + 1] << 8) | (buffer[offset + 2] << 16);
            if ((v & 0x800000) != 0) v |= unchecked((int)0xFF000000);
            return (short)(v >> 8);
        }
        return BinaryPrimitives.ReadInt16LittleEndian(buffer.AsSpan(offset, 2));
    }

    private static short FloatToPcm16(float value)
    {
        if (float.IsNaN(value)) return 0;
        value = Math.Clamp(value, -1.0f, 1.0f);
        return (short)Math.Round(value * short.MaxValue);
    }
}

internal sealed class ClientSender
{
    private readonly string _host;
    private readonly int _sampleRate;
    private readonly int _channels;
    private readonly int _frameMs;
    private readonly Action<string> _log;
    private readonly BlockingCollection<byte[]> _queue = new(new ConcurrentQueue<byte[]>(), 6);
    private volatile bool _active = true;
    public bool Connected { get; private set; }

    public ClientSender(string host, int sampleRate, int channels, int frameMs, Action<string> log)
    {
        _host = host;
        _sampleRate = sampleRate;
        _channels = channels;
        _frameMs = frameMs;
        _log = log;
    }

    public void Offer(byte[] packet)
    {
        if (!_active) return;
        while (_queue.Count >= 6 && _queue.TryTake(out _)) { }
        _queue.TryAdd(packet);
    }

    public void Stop()
    {
        _active = false;
        _queue.CompleteAdding();
    }

    public async Task RunAsync(CancellationToken token)
    {
        while (_active && !token.IsCancellationRequested)
        {
            try
            {
                using var client = new TcpClient { NoDelay = true, SendBufferSize = 24 * 1024 };
                await client.ConnectAsync(_host, Protocol.Port, token);
                await using var stream = client.GetStream();
                await Protocol.WriteHeaderAsync(stream, _sampleRate, _channels, 16, _frameMs, token);
                Connected = true;
                _log($"已连接接收端：{_host}");

                foreach (var packet in _queue.GetConsumingEnumerable(token))
                {
                    await stream.WriteAsync(packet.AsMemory(0, packet.Length), token);
                }
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Connected = false;
                _log($"连接/发送失败 {_host}：{ex.Message}，稍后重试");
                try { await Task.Delay(800, token); } catch { }
            }
            finally
            {
                Connected = false;
            }
        }
    }
}

internal sealed class PcmPacketizer
{
    private readonly int _sampleRate;
    private readonly int _channels;
    private readonly int _chunkBytes;
    private readonly List<byte> _pending = new();
    private long _sequence;
    private long _framesSent;

    public PcmPacketizer(int sampleRate, int channels, int frameMs)
    {
        _sampleRate = sampleRate;
        _channels = channels;
        _chunkBytes = sampleRate * channels * 2 * frameMs / 1000;
    }

    public IEnumerable<byte[]> PushPcm(byte[] pcm)
    {
        _pending.AddRange(pcm);
        while (_pending.Count >= _chunkBytes)
        {
            var payload = _pending.GetRange(0, _chunkBytes).ToArray();
            _pending.RemoveRange(0, _chunkBytes);
            var frames = payload.Length / (_channels * 2);
            var timestampNs = _framesSent * 1_000_000_000L / _sampleRate;
            _framesSent += frames;
            yield return Protocol.BuildAudioFrame(_sequence++, timestampNs, frames, payload);
        }
    }
}

internal sealed class Receiver
{
    private readonly Action<string> _log;
    private readonly Action<string> _status;
    private volatile int _volumePercent = 80;
    private WaveOutEvent? _waveOut;

    public Receiver(Action<string> log, Action<string> status)
    {
        _log = log;
        _status = status;
    }

    public async Task RunAsync(CancellationToken token)
    {
        _log($"Windows 接收端启动：音频端口 {Protocol.Port}，发现端口 {Discovery.Port}，控制端口 {Protocol.ControlPort}");
        _ = RunDiscoveryResponderAsync(token);
        _ = RunControlServerAsync(token);

        var listener = new TcpListener(IPAddress.Any, Protocol.Port);
        listener.Start();
        _status("等待连接");
        try
        {
            while (!token.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(token);
                client.NoDelay = true;
                _log("已连接发送端：" + client.Client.RemoteEndPoint);
                _status("已连接，正在播放");
                try { await HandleClientAsync(client, token); }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex) when (!token.IsCancellationRequested)
                {
                    _log("音频连接结束：" + ex.Message);
                    _status("等待连接");
                }
            }
        }
        finally
        {
            listener.Stop();
            _waveOut?.Stop();
            _waveOut?.Dispose();
            _status("已停止");
            _log("接收端已停止");
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken token)
    {
        await using var stream = client.GetStream();
        var header = await Protocol.ReadHeaderAsync(stream, token);
        var waveFormat = new WaveFormat(header.SampleRate, header.BitsPerSample, header.Channels);
        var provider = new BufferedWaveProvider(waveFormat)
        {
            BufferDuration = TimeSpan.FromMilliseconds(100),
            DiscardOnBufferOverflow = true
        };

        _waveOut?.Stop();
        _waveOut?.Dispose();
        _waveOut = new WaveOutEvent { DesiredLatency = 45, NumberOfBuffers = 2, Volume = _volumePercent / 100.0f };
        _waveOut.Init(provider);
        _waveOut.Play();

        while (!token.IsCancellationRequested)
        {
            var frame = await Protocol.ReadAudioFrameAsync(stream, token);
            if (provider.BufferedDuration.TotalMilliseconds > 150)
            {
                continue;
            }
            provider.AddSamples(frame.Payload, 0, frame.Payload.Length);
        }
    }

    private async Task RunControlServerAsync(CancellationToken token)
    {
        var listener = new TcpListener(IPAddress.Any, Protocol.ControlPort);
        listener.Start();
        try
        {
            while (!token.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(token);
                try
                {
                    var volume = await Protocol.ReadVolumeCommandAsync(client.GetStream(), token);
                    _volumePercent = Math.Clamp(volume, 0, 100);
                    if (_waveOut != null) _waveOut.Volume = _volumePercent / 100.0f;
                    _log($"接收端应用内音量：{_volumePercent}%");
                }
                catch (Exception ex) when (!token.IsCancellationRequested)
                {
                    _log("控制命令异常：" + ex.Message);
                }
            }
        }
        catch (OperationCanceledException) { }
        finally { listener.Stop(); }
    }

    private async Task RunDiscoveryResponderAsync(CancellationToken token)
    {
        using var udp = new UdpClient(Discovery.Port) { EnableBroadcast = true };
        try
        {
            while (!token.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(token);
                if (!Discovery.IsRequest(result.Buffer)) continue;
                var response = Discovery.BuildResponse(WifiNet.GetLocalIpAddress(), Environment.MachineName + " Windows");
                await udp.SendAsync(response, response.Length, result.RemoteEndPoint);
            }
        }
        catch (OperationCanceledException) { }
    }
}

internal static class Discovery
{
    public const int Port = 45778;
    private const string Request = "WSPK_DISCOVER_1";
    private const string ResponsePrefix = "WSPK_RESPONSE_1";

    public static bool IsRequest(byte[] data) => Encoding.UTF8.GetString(data).Trim() == Request;

    public static byte[] BuildResponse(string host, string name)
    {
        return Encoding.UTF8.GetBytes($"{ResponsePrefix}|{host}|{Protocol.Port}|{name.Replace('|', ' ')}");
    }

    public static async Task<List<Device>> FindReceiversAsync(TimeSpan timeout, CancellationToken token)
    {
        using var udp = new UdpClient(0) { EnableBroadcast = true };
        var request = Encoding.UTF8.GetBytes(Request);
        await udp.SendAsync(request, request.Length, new IPEndPoint(IPAddress.Broadcast, Port));
        var deadline = DateTimeOffset.UtcNow + timeout;
        var result = new List<Device>();
        var seen = new HashSet<string>();
        while (DateTimeOffset.UtcNow < deadline && !token.IsCancellationRequested)
        {
            using var receiveCts = CancellationTokenSource.CreateLinkedTokenSource(token);
            receiveCts.CancelAfter(deadline - DateTimeOffset.UtcNow);
            try
            {
                var packet = await udp.ReceiveAsync(receiveCts.Token);
                var text = Encoding.UTF8.GetString(packet.Buffer).Trim();
                var parts = text.Split('|');
                if (parts.Length >= 4 && parts[0] == ResponsePrefix && int.TryParse(parts[2], out var port) && port == Protocol.Port)
                {
                    var host = string.IsNullOrWhiteSpace(parts[1]) || parts[1] == "0.0.0.0" ? packet.RemoteEndPoint.Address.ToString() : parts[1];
                    if (seen.Add(host)) result.Add(new Device(host, parts[3]));
                }
            }
            catch (OperationCanceledException) { break; }
        }
        return result;
    }

    public sealed record Device(string Host, string Name);
}

internal static class Protocol
{
    public const int Port = 45777;
    public const int ControlPort = 45779;
    public const int DefaultFrameMs = 10;
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("WSPK0003");
    private static readonly byte[] FrameMagic = Encoding.ASCII.GetBytes("AFRM");
    private static readonly byte[] ControlMagic = Encoding.ASCII.GetBytes("WSPKCTRL");

    public static List<string> ParseHosts(string text)
    {
        return text.Split(new[] { ',', ';', ' ', '\t', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public static async Task WriteHeaderAsync(Stream stream, int sampleRate, int channels, int bits, int frameMs, CancellationToken token)
    {
        var buffer = new byte[Magic.Length + 20];
        Magic.AsSpan().CopyTo(buffer.AsSpan(0, Magic.Length));
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(8, 4), sampleRate);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(12, 4), channels);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(16, 4), bits);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(20, 4), 1);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(24, 4), frameMs);
        await stream.WriteAsync(buffer.AsMemory(0, buffer.Length), token);
    }

    public static async Task<Header> ReadHeaderAsync(Stream stream, CancellationToken token)
    {
        var buffer = await ReadExactAsync(stream, Magic.Length + 20, token);
        if (!buffer.AsSpan(0, Magic.Length).SequenceEqual(Magic)) throw new IOException("Bad stream magic");
        var sampleRate = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(8, 4));
        var channels = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(12, 4));
        var bits = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(16, 4));
        var format = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(20, 4));
        var frameMs = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(24, 4));
        if (format != 1 || bits != 16) throw new IOException("Only PCM S16LE is supported");
        return new Header(sampleRate, channels, bits, frameMs);
    }

    public static byte[] BuildAudioFrame(long sequence, long presentationTimeNs, int durationFrames, byte[] payload)
    {
        var buffer = new byte[FrameMagic.Length + 4 + 8 + 8 + 4 + payload.Length];
        FrameMagic.AsSpan().CopyTo(buffer.AsSpan(0, FrameMagic.Length));
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(4, 4), payload.Length);
        BinaryPrimitives.WriteInt64BigEndian(buffer.AsSpan(8, 8), sequence);
        BinaryPrimitives.WriteInt64BigEndian(buffer.AsSpan(16, 8), presentationTimeNs);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(24, 4), durationFrames);
        payload.AsSpan().CopyTo(buffer.AsSpan(28));
        return buffer;
    }

    public static async Task<AudioFrame> ReadAudioFrameAsync(Stream stream, CancellationToken token)
    {
        var prefix = await ReadExactAsync(stream, FrameMagic.Length + 4 + 8 + 8 + 4, token);
        if (!prefix.AsSpan(0, FrameMagic.Length).SequenceEqual(FrameMagic)) throw new IOException("Bad frame magic");
        var size = BinaryPrimitives.ReadInt32BigEndian(prefix.AsSpan(4, 4));
        if (size <= 0 || size > 256 * 1024) throw new IOException("Bad frame size");
        var sequence = BinaryPrimitives.ReadInt64BigEndian(prefix.AsSpan(8, 8));
        var time = BinaryPrimitives.ReadInt64BigEndian(prefix.AsSpan(16, 8));
        var frames = BinaryPrimitives.ReadInt32BigEndian(prefix.AsSpan(24, 4));
        var payload = await ReadExactAsync(stream, size, token);
        return new AudioFrame(sequence, time, frames, payload);
    }

    public static async Task SendVolumeAsync(string host, int volume, CancellationToken token)
    {
        using var client = new TcpClient { NoDelay = true };
        await client.ConnectAsync(host, ControlPort, token);
        await using var stream = client.GetStream();
        var buffer = new byte[ControlMagic.Length + 8];
        ControlMagic.AsSpan().CopyTo(buffer.AsSpan(0, ControlMagic.Length));
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(ControlMagic.Length, 4), 1);
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(ControlMagic.Length + 4, 4), Math.Clamp(volume, 0, 100));
        await stream.WriteAsync(buffer.AsMemory(0, buffer.Length), token);
    }

    public static async Task<int> ReadVolumeCommandAsync(Stream stream, CancellationToken token)
    {
        var buffer = await ReadExactAsync(stream, ControlMagic.Length + 8, token);
        if (!buffer.AsSpan(0, ControlMagic.Length).SequenceEqual(ControlMagic)) throw new IOException("Bad control magic");
        var type = BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(ControlMagic.Length, 4));
        if (type != 1) throw new IOException("Unsupported control type");
        return Math.Clamp(BinaryPrimitives.ReadInt32BigEndian(buffer.AsSpan(ControlMagic.Length + 4, 4)), 0, 100);
    }

    private static async Task<byte[]> ReadExactAsync(Stream stream, int size, CancellationToken token)
    {
        var buffer = new byte[size];
        var offset = 0;
        while (offset < size)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(offset, size - offset), token);
            if (n <= 0) throw new EndOfStreamException();
            offset += n;
        }
        return buffer;
    }

    public sealed record Header(int SampleRate, int Channels, int BitsPerSample, int FrameMs);
    public sealed record AudioFrame(long Sequence, long PresentationTimeNs, int DurationFrames, byte[] Payload);
}

internal static class WifiNet
{
    public static string GetLocalIpAddress()
    {
        try
        {
            foreach (var address in Dns.GetHostAddresses(Dns.GetHostName()))
            {
                if (address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                {
                    return address.ToString();
                }
            }
        }
        catch { }
        return "0.0.0.0";
    }
}
