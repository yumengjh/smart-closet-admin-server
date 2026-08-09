package com.yumg.starter.modules.security.application;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/** Stateless exact-address and CIDR matcher for runtime access policies. */
public final class IpNetworkMatcher {
    private IpNetworkMatcher() { }

    public static boolean matches(String network, String address) {
        try {
            String[] parts = network.trim().split("/", 2);
            byte[] base = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = InetAddress.getByName(address).getAddress();
            if (base.length != candidate.length) return false;
            int prefix = parts.length == 1 ? base.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > base.length * 8) return false;
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) if (base[index] != candidate[index]) return false;
            if (remainingBits == 0) return true;
            int mask = 0xFF << (8 - remainingBits);
            return (base[fullBytes] & mask) == (candidate[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException ignored) {
            return false;
        }
    }

    public static Optional<String> normalize(String network) {
        try {
            String[] parts = network.trim().split("/", 2);
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) return Optional.empty();
            for (int bit = prefix; bit < address.length * 8; bit++) {
                int index = bit / 8;
                address[index] = (byte) (address[index] & ~(1 << (7 - bit % 8)));
            }
            String normalized = InetAddress.getByAddress(address).getHostAddress();
            return Optional.of(parts.length == 1 ? normalized : normalized + "/" + prefix);
        } catch (UnknownHostException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
