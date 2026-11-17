package com.tanh.file_sharing.peer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.tanh.file_sharing.model.NetworkAddressSnapshot;

/**
 * Finds local IPv4 addresses for an active listener and preserves the
 * originating interface metadata for user-facing network selection.
 */
public final class NetworkAddressResolver {
    public static final String AUTO_BIND_ADDRESS = "0.0.0.0";

    private NetworkAddressResolver() {
    }

    public static List<String> addressesFor(String bindAddress) {
        return networkAddressesFor(bindAddress).stream()
                .map(NetworkAddressSnapshot::address)
                .toList();
    }

    public static List<NetworkAddressSnapshot> networkAddressesFor(String bindAddress) {
        if (bindAddress != null && !bindAddress.isBlank()
                && !AUTO_BIND_ADDRESS.equals(bindAddress.trim())
                && !"::".equals(bindAddress.trim())) {
            return addressForSpecificBind(bindAddress.trim());
        }

        Map<String, NetworkAddressSnapshot> addresses = new TreeMap<>();
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
                            addresses.putIfAbsent(address.getHostAddress(), describe(address, networkInterface));
                        }
                    }
                } catch (SocketException ignored) {
                    // An interface can disappear while its addresses are being inspected.
                }
            }
        } catch (SocketException ignored) {
            // The listener can still operate when interface discovery is unavailable.
        }
        return List.copyOf(addresses.values());
    }

    private static List<NetworkAddressSnapshot> addressForSpecificBind(String bindAddress) {
        try {
            InetAddress address = InetAddress.getByName(bindAddress);
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            if (networkInterface != null) {
                return List.of(describe(address, networkInterface));
            }
            return List.of(new NetworkAddressSnapshot(
                    address.getHostAddress(), "selected", "User-selected address", "Specific bind address", false));
        } catch (UnknownHostException | SocketException ignored) {
            // Keep the configured value visible even if metadata lookup fails.
            return List.of(new NetworkAddressSnapshot(
                    bindAddress, "selected", "User-selected address", "Specific bind address", false));
        }
    }

    private static NetworkAddressSnapshot describe(InetAddress address, NetworkInterface networkInterface)
            throws SocketException {
        String name = networkInterface.getName();
        String displayName = networkInterface.getDisplayName();
        boolean virtual = networkInterface.isVirtual();
        String category = classifyInterface(name, displayName, virtual);
        if ("LAN / Wi-Fi / Ethernet".equals(category) && isSharedCgnatAddress(address)) {
            category = "CGNAT / shared address range";
        }
        return new NetworkAddressSnapshot(
                address.getHostAddress(),
                name,
                displayName == null || displayName.isBlank() ? name : displayName,
                category,
                virtual);
    }

    static boolean isSharedCgnatAddress(InetAddress address) {
        byte[] octets = address.getAddress();
        return octets.length == 4
                && (octets[0] & 0xff) == 100
                && (octets[1] & 0xff) >= 64
                && (octets[1] & 0xff) <= 127;
    }

    static String classifyInterface(String interfaceName, String displayName, boolean virtual) {
        String name = interfaceName == null ? "" : interfaceName.toLowerCase(Locale.ROOT);
        String display = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String combined = name + " " + display;

        if (combined.contains("tailscale")) {
            return "VPN · Tailscale";
        }
        if (name.startsWith("wg") || name.startsWith("tun") || name.startsWith("tap")
                || name.startsWith("utun") || name.startsWith("ppp")
                || combined.contains("wireguard") || combined.contains("vpn")) {
            return "VPN / tunnel";
        }
        if (name.startsWith("virbr") || name.startsWith("vboxnet") || name.startsWith("vmnet")
                || combined.contains("virtualbox") || combined.contains("vmware")
                || combined.contains("libvirt")) {
            return "Virtual machine bridge";
        }
        if (name.startsWith("docker") || name.startsWith("podman") || name.startsWith("cni")
                || name.startsWith("veth") || name.startsWith("br-")
                || combined.contains("docker") || combined.contains("podman")
                || combined.contains("container")) {
            return "Container / Docker bridge";
        }
        if (virtual) {
            return "Virtual network interface";
        }
        return "LAN / Wi-Fi / Ethernet";
    }
}
