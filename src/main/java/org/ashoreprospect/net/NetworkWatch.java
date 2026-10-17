/** Online/offline flag so saves can skip Node when the network is down. */
package org.ashoreprospect.net;

import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.ashoreprospect.SchedulerApp;

/** Polls NIC up/down so login and the main window can toast Online / Offline. */
public final class NetworkWatch {

    private static volatile boolean running;
    private static volatile Boolean last;
    private static final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

    private NetworkWatch() {}

    public static boolean isOnline() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return false;
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void start() {
        if (running) return;
        running = true;
        last = isOnline();
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
                boolean now = isOnline();
                Boolean prev = last;
                last = now;
                if (prev != null && prev != now) {
                    SchedulerApp.notifyNetwork(now);
                    for (Consumer<Boolean> c : listeners) {
                        try {
                            c.accept(now);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "network-watch");
        t.setDaemon(true);
        t.start();
    }

    public static void addListener(Consumer<Boolean> listener) {
        if (listener != null) listeners.add(listener);
    }
}
