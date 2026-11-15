package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP-IGD port mapping ({@code --upnp}) — the Lite counterpart of native {@code upnp.rs} (igd-next): SSDP discovery,
 * then SOAP AddPortMapping on the router's WANIPConnection/WANPPPConnection service, removed again on exit.
 *
 * <p>Lite maps the TCP ports only (the game port, which also carries the embedded TURN, plus a Bukkit
 * {@code --turn-listen} port): libwebrtc picks a fresh UDP port for every connection, so there is no single UDP port to
 * forward. Hole punching (STUN) and TURN-over-TCP cover the rest.
 */
final class Upnp {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Pattern SERVICE = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String[] TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
    };

    record Mapped(URI control, String serviceType, InetAddress local, List<Integer> tcp, String external, Log log) {
        /** The lease is one hour: renewed every 50 minutes. */
        void renew() {
            for (int p : tcp) {
                try {
                    add(control, serviceType, local, p);
                } catch (IOException ignored) {
                }
            }
        }

        void close() {
            for (int p : tcp) {
                try {
                    soap(control, serviceType, "DeletePortMapping",
                            "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + p + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Upnp() {}

    /** Maps the TCP ports; Ok if at least one mapping worked. */
    static Mapped open(List<Integer> tcp, Log log) throws IOException {
        for (String location : discover(3000)) {
            URI loc = URI.create(location);
            String xml;
            try {
                xml = get(loc);
            } catch (IOException e) {
                continue;
            }
            String base = tag(xml, "URLBase");
            Matcher m = SERVICE.matcher(xml);
            while (m.find()) {
                String svc = m.group(1);
                String type = tag(svc, "serviceType");
                String ctl = tag(svc, "controlURL");
                if (type == null || ctl == null) continue;
                if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue;
                URI control = (base != null && !base.isBlank() ? URI.create(base.trim()) : loc).resolve(ctl.trim());
                InetAddress local = localFor(control.getHost());
                List<Integer> ok = new ArrayList<>();
                for (int p : tcp) {
                    try {
                        add(control, type, local, p);
                        ok.add(p);
                    } catch (IOException e) {
                        log.info("UPnP: cannot map TCP " + p + " (" + e.getMessage() + "); forward TCP " + p + " manually");
                    }
                }
                if (ok.isEmpty()) continue;
                String ext = null;
                try {
                    ext = tag(soap(control, type, "GetExternalIPAddress", ""), "NewExternalIPAddress");
                } catch (IOException ignored) {
                }
                log.info("UPnP: mapped TCP " + ok + " (external IP " + (ext == null ? "?" : ext) + ")");
                return new Mapped(control, type, local, ok, ext, log);
            }
        }
        throw new IOException("no gateway found");
    }

    private static void add(URI control, String type, InetAddress local, int port) throws IOException {
        soap(control, type, "AddPortMapping",
                "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>"
                        + "<NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>" + local.getHostAddress() + "</NewInternalClient>"
                        + "<NewEnabled>1</NewEnabled><NewPortMappingDescription>BlitzLink (TCP)</NewPortMappingDescription>"
                        + "<NewLeaseDuration>3600</NewLeaseDuration>");
    }

    private static Set<String> discover(int waitMs) {
        Set<String> out = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
            for (String st : TARGETS) {
                byte[] req = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(req, req.length, group));
            }
            long until = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[2048];
            while (true) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                s.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                for (String line : new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).split("\r\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("location:")) out.add(line.substring(9).trim());
                }
                if (!out.isEmpty() && System.currentTimeMillis() + 300 < until) until = System.currentTimeMillis() + 300;
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String get(URI uri) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String soap(URI control, String type, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + type + "\">"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(control)
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .header("SOAPAction", "\"" + type + "#" + action + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                String err = tag(r.body(), "errorDescription");
                throw new IOException(err != null ? err : "HTTP " + r.statusCode());
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + ">(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static InetAddress localFor(String gatewayHost) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(gatewayHost), 1900);
            return s.getLocalAddress();
        }
    }
}
