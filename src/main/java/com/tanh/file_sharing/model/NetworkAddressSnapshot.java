package com.tanh.file_sharing.model;

/**
 * An IPv4 address advertised by the local peer listener, including the
 * interface it belongs to so the desktop can distinguish LAN, VM, VPN and
 * container networks.
 */
public record NetworkAddressSnapshot(
        String address,
        String interfaceName,
        String displayName,
        String category,
        boolean virtual) {
}
