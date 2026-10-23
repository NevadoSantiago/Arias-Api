package com.arias.printing;

/** Strict private-IPv4 literal check (10/8, 172.16/12, 192.168/16). Never resolves names. */
final class PrivateIpv4 {

    private PrivateIpv4() {}

    static boolean isPrivate(String host) {
        if (host == null) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            // 1-3 ASCII digits, no leading zeros (some stacks read those as octal).
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false;
            octets[i] = Integer.parseInt(p);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 10
            || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
            || (octets[0] == 192 && octets[1] == 168);
    }
}
