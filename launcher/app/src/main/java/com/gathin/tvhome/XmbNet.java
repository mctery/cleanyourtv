package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;

/**
 * The connection the box uses, for the status row's network icon and the Network category: the LAN, Wi-Fi (with its
 * name and signal) or another way out, its address and the Private DNS in use. A VPN is looked through to the network
 * it runs over. One read of Android's connectivity, made where it is shown.
 */
final class XmbNet {
    static final int NONE = 0, WIFI = 1, LAN = 2, OTHER = 3;

    final int kind;
    /** The Wi-Fi signal in dBm; 0 when not on Wi-Fi or not known. */
    final int rssi;
    /** The Wi-Fi network's name; null when not on Wi-Fi or hidden from apps (no location permission). */
    final String ssid;
    /** The IPv4 address, or null. */
    final String ip;
    /** The Private DNS host in use, or null. */
    final String privateDns;

    private XmbNet(int kind, int rssi, String ssid, String ip, String privateDns) {
        this.kind = kind;
        this.rssi = rssi;
        this.ssid = ssid;
        this.ip = ip;
        this.privateDns = privateDns;
    }

    /** The best way out the box has: the LAN over Wi-Fi over anything else; NONE without one. */
    static XmbNet read(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null) return none();
            Network best = null;
            int kind = NONE;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                int k = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? LAN
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? WIFI : OTHER;
                if (rank(k) > rank(kind)) {
                    kind = k;
                    best = net;
                }
            }
            if (best == null) return none();
            LinkProperties lp = cm.getLinkProperties(best);
            String dns = null;
            if (lp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp.isPrivateDnsActive()) {
                dns = lp.getPrivateDnsServerName();
            }
            int rssi = 0;
            String ssid = null;
            if (kind == WIFI) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                if (info != null) {
                    rssi = info.getRssi();
                    ssid = cleanSsid(info.getSSID());
                }
            }
            return new XmbNet(kind, rssi, ssid, ipv4(lp), dns == null || dns.isEmpty() ? null : dns);
        } catch (RuntimeException e) {
            return none();
        }
    }

    private static XmbNet none() {
        return new XmbNet(NONE, 0, null, null, null);
    }

    private static int rank(int kind) {
        return kind == LAN ? 3 : kind == WIFI ? 2 : kind == OTHER ? 1 : 0;
    }

    /** The name without quotes, or null for Android's placeholders ("<unknown ssid>", "0x"). */
    static String cleanSsid(String ssid) {
        if (ssid == null) return null;
        String s = ssid.replace("\"", "").trim();
        return s.isEmpty() || s.contains("unknown") || s.equals("0x") ? null : s;
    }

    private static String ipv4(LinkProperties lp) {
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }
}
