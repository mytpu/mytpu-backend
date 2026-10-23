package org.mytpu.gateway.access;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Сеть CIDR (IPv4 или IPv6): проверка адреса и подсеть для учёта без полного адреса (IPv4 /24, IPv6 /48).
 */
record Cidr(byte[] network, int prefix) {

    static Cidr parse(String value) {
        String text = value.trim();
        int slash = text.indexOf('/');
        InetAddress address = InetAddress.ofLiteral(slash < 0 ? text : text.substring(0, slash));
        int bits = address.getAddress().length * 8;
        int prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1));
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("Неверная длина префикса: " + value);
        }
        return new Cidr(mask(address.getAddress(), prefix), prefix);
    }

    boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == network.length && java.util.Arrays.equals(mask(bytes, prefix), network);
    }

    boolean ipv4() {
        return network.length == 4;
    }

    /** Ключ лимита неудачных попыток: IPv4 — адрес целиком, IPv6 — /64 (у одного абонента обычно целая /64). */
    static String limiterKeyOf(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        try {
            return InetAddress.getByAddress(mask(address.getAddress(), 64)).getHostAddress() + "/64";
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Подсеть адреса для учёта: {@code 203.0.113.0/24}, {@code 2001:db8:1::/48}. */
    static String subnetOf(InetAddress address) {
        int prefix = address instanceof Inet4Address ? 24 : 48;
        try {
            return InetAddress.getByAddress(mask(address.getAddress(), prefix)).getHostAddress() + "/" + prefix;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int i = 0; i < result.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            result[i] = (byte) (result[i] & (0xFF << (8 - keep)));
        }
        return result;
    }
}
