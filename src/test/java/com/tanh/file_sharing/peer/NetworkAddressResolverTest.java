package com.tanh.file_sharing.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NetworkAddressResolverTest {

    @Test
    void identifiesTailscaleInterfaceSeparatelyFromOtherVpnInterfaces() {
        assertEquals("VPN · Tailscale",
                NetworkAddressResolver.classifyInterface("tailscale0", "Tailscale", true));
    }

    @Test
    void identifiesWireGuardAndTunnelInterfaces() {
        assertEquals("VPN / tunnel",
                NetworkAddressResolver.classifyInterface("wg0", "WireGuard", true));
        assertEquals("VPN / tunnel",
                NetworkAddressResolver.classifyInterface("tun0", "tun0", true));
    }

    @Test
    void identifiesLibvirtAndDesktopVirtualMachineBridges() {
        assertEquals("Virtual machine bridge",
                NetworkAddressResolver.classifyInterface("virbr0", "virbr0", true));
        assertEquals("Virtual machine bridge",
                NetworkAddressResolver.classifyInterface("vboxnet0", "VirtualBox Host-Only", true));
    }

    @Test
    void identifiesDockerAndContainerBridges() {
        assertEquals("Container / Docker bridge",
                NetworkAddressResolver.classifyInterface("docker0", "docker0", true));
        assertEquals("Container / Docker bridge",
                NetworkAddressResolver.classifyInterface("br-a1b2c3d4", "br-a1b2c3d4", true));
        assertEquals("Container / Docker bridge",
                NetworkAddressResolver.classifyInterface("cni0", "cni0", true));
    }

    @Test
    void recognizesTheSharedCgnatAddressRangeUsedByTailscale() throws Exception {
        assertTrue(NetworkAddressResolver.isSharedCgnatAddress(
                java.net.InetAddress.getByName("100.64.0.1")));
        assertEquals(true, NetworkAddressResolver.isSharedCgnatAddress(
                java.net.InetAddress.getByName("100.127.255.255")));
        assertFalse(NetworkAddressResolver.isSharedCgnatAddress(
                java.net.InetAddress.getByName("100.63.255.255")));
        assertEquals(false, NetworkAddressResolver.isSharedCgnatAddress(
                java.net.InetAddress.getByName("100.128.0.1")));
    }

    @Test
    void fallsBackToLanOrGenericVirtualInterfaceLabels() {
        assertEquals("LAN / Wi-Fi / Ethernet",
                NetworkAddressResolver.classifyInterface("wlp2s0", "Intel Wi-Fi", false));
        assertEquals("Virtual network interface",
                NetworkAddressResolver.classifyInterface("vnet0", "vnet0", true));
    }
}
