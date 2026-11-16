package com.checkdoor.app;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpSender {
    private static final String TAG = "CheckDoor_UDP";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void sendPacket(final String targetIp, final int port, final String message, final int repeatCount) {
        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                InetAddress address = InetAddress.getByName(targetIp);
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);

                for (int i = 0; i < repeatCount; i++) {
                    socket.send(packet);
                    if (i < repeatCount - 1) {
                        try {
                            Thread.sleep(15);
                        } catch (InterruptedException ignored) {}
                    }
                }
                Log.d(TAG, "Sent [" + repeatCount + " times]: " + message + " to " + targetIp + ":" + port);
            } catch (Exception e) {
                Log.e(TAG, "Error sending UDP packet: " + e.getMessage(), e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
    }

    public static void sendCommand(String targetIp, int port, String cmd, float angle) {
        String json = "{\"cmd\":\"" + cmd + "\",\"angle\":" + String.format("%.2f", angle) + ",\"time\":" + System.currentTimeMillis() + "}";
        int repeat = "DOOR_OPEN".equals(cmd) ? 4 : ("DOOR_CLOSED".equals(cmd) ? 3 : 1);
        sendPacket(targetIp, port, json, repeat);
    }
}
