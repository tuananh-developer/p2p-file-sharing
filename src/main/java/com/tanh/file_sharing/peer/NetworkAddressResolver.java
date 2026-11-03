package com.tanh.file_sharing.peer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;

/** Finds the local IPv4 addresses that can be advertised for an active listener. */
public final class NetworkAddressResolver {
    public static final String AUTO_BIND_ADDRESS = "0.0.0.0";

    private NetworkAddressResolver() {
    }

    public static List<String> addressesFor(String bindAddress) {
        if (bindAddress != null && !bindAddress.isBlank()
                && !AUTO_BIND_ADDRESS.equals(bindAddress.trim())
                && !"::".equals(bindAddress.trim())) {
            return List.of(bindAddress.trim());
        }

        TreeSet<String> addresses = new TreeSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return List.of();
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                        continue;
                    }
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        if (address instanceof Inet4Address
                                && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()
                                && !address.isAnyLocalAddress()) {
                            addresses.add(address.getHostAddress());
                        }
                    }
                } catch (SocketException ignored) {
                    // A network interface can disappear while its addresses are being inspected.
                }
            }
        } catch (SocketException ignored) {
            // The listener can still operate even when interface discovery is unavailable.
        }
        return List.copyOf(addresses);
    }
}
