package com.example.wifispeaker;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Discovery {
    static final int DISCOVERY_PORT = 45778;
    private static final String REQUEST = "WSPK_DISCOVER_1";
    private static final String RESPONSE_PREFIX = "WSPK_RESPONSE_1";

    private Discovery() {}

    static boolean isRequest(byte[] data, int length) {
        String text = new String(data, 0, length, StandardCharsets.UTF_8).trim();
        return REQUEST.equals(text);
    }

    static byte[] requestBytes() {
        return REQUEST.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] responseBytes(String host, String deviceName) {
        String safeName = deviceName == null ? "Android Speaker" : deviceName.replace('|', ' ');
        String text = RESPONSE_PREFIX + "|" + host + "|" + Protocol.PORT + "|" + safeName;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static Device parseResponse(byte[] data, int length) {
        String text = new String(data, 0, length, StandardCharsets.UTF_8).trim();
        String[] parts = text.split("\\|", -1);
        if (parts.length < 4 || !RESPONSE_PREFIX.equals(parts[0])) return null;
        String host = parts[1].trim();
        int port;
        try {
            port = Integer.parseInt(parts[2].trim());
        } catch (Exception e) {
            return null;
        }
        String name = parts[3].trim();
        if (host.isEmpty() || port != Protocol.PORT) return null;
        return new Device(host, name.isEmpty() ? "Android Speaker" : name);
    }

    static Device findFirstReceiver(int timeoutMs) throws Exception {
        List<Device> devices = findReceivers(timeoutMs);
        return devices.isEmpty() ? null : devices.get(0);
    }

    static List<Device> findReceivers(int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] req = requestBytes();
        byte[] buf = new byte[512];
        List<Device> results = new ArrayList<>();
        Set<String> seenHosts = new LinkedHashSet<>();

        DatagramSocket socket = new DatagramSocket(null);
        try {
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));

            for (InetAddress target : getBroadcastTargets()) {
                try {
                    DatagramPacket packet = new DatagramPacket(req, req.length, target, DISCOVERY_PORT);
                    socket.send(packet);
                } catch (Exception ignored) {
                    // Some networks reject global broadcast. Keep trying interface-specific broadcasts.
                }
            }

            while (System.currentTimeMillis() < deadline) {
                int wait = (int) Math.max(200, deadline - System.currentTimeMillis());
                socket.setSoTimeout(wait);
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Device device = parseResponse(reply.getData(), reply.getLength());
                if (device != null) {
                    String host = device.host;
                    if (host == null || host.length() == 0 || "0.0.0.0".equals(host)) {
                        host = reply.getAddress().getHostAddress();
                    }
                    if (seenHosts.add(host)) {
                        results.add(new Device(host, device.name));
                    }
                }
            }
        } finally {
            socket.close();
        }
        return results;
    }

    private static List<InetAddress> getBroadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast instanceof Inet4Address) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(targets);
    }

    static final class Device {
        final String host;
        final String name;

        Device(String host, String name) {
            this.host = host;
            this.name = name;
        }
    }
}
