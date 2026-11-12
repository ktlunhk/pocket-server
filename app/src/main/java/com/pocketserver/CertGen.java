package com.pocketserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class CertGen {

    private static final byte[] OID_SHA256_RSA = { 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B };
    private static final byte[] OID_CN = { 0x55, 0x04, 0x03 };
    private static final byte[] OID_SAN = { 0x55, 0x1D, 0x11 };
    private static final byte[] OID_BASIC = { 0x55, 0x1D, 0x13 };
    private static final byte[] OID_KEYUSAGE = { 0x55, 0x1D, 0x0F };
    private static final byte[] OID_EXTKEY = { 0x55, 0x1D, 0x25 };
    private static final byte[] OID_SERVER_AUTH = { 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01 };

    public static void generate(File certFile, File keyFile, List<String> dnsNames, List<String> ipv4) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x7F) | 0x01);

        byte[] sigAlg = seq(tlv(0x06, OID_SHA256_RSA), new byte[] { 0x05, 0x00 });
        byte[] name = seq(set(seq(tlv(0x06, OID_CN), tlv(0x0C, "localhost".getBytes("UTF-8")))));

        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] from = f.format(new Date(now - 86400000L)).getBytes("US-ASCII");
        byte[] to = f.format(new Date(now + 3650L * 86400000L)).getBytes("US-ASCII");
        byte[] validity = seq(tlv(0x17, from), tlv(0x17, to));

        ByteArrayOutputStream gn = new ByteArrayOutputStream();
        for (int i = 0; i < dnsNames.size(); i++) {
            byte[] d = tlv(0x82, dnsNames.get(i).getBytes("US-ASCII"));
            gn.write(d, 0, d.length);
        }
        for (int i = 0; i < ipv4.size(); i++) {
            byte[] ip = parseIp(ipv4.get(i));
            if (ip != null) {
                byte[] d = tlv(0x87, ip);
                gn.write(d, 0, d.length);
            }
        }
        byte[] san = seq(tlv(0x06, OID_SAN), tlv(0x04, tlv(0x30, gn.toByteArray())));
        byte[] basic = seq(tlv(0x06, OID_BASIC), tlv(0x01, new byte[] { (byte) 0xFF }), tlv(0x04, tlv(0x30, new byte[0])));
        byte[] ku = seq(tlv(0x06, OID_KEYUSAGE), tlv(0x01, new byte[] { (byte) 0xFF }),
                tlv(0x04, new byte[] { 0x03, 0x02, 0x05, (byte) 0xA0 }));
        byte[] eku = seq(tlv(0x06, OID_EXTKEY), tlv(0x04, seq(tlv(0x06, OID_SERVER_AUTH))));
        byte[] exts = tlv(0xA3, seq(san, basic, ku, eku));

        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[] { 0x02 })),
                tlv(0x02, serial),
                sigAlg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),
                exts);

        Signature sg = Signature.getInstance("SHA256withRSA");
        sg.initSign(kp.getPrivate());
        sg.update(tbs);
        byte[] sig = sg.sign();

        byte[] sigBits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, sigBits, 1, sig.length);
        byte[] cert = seq(tbs, sigAlg, tlv(0x03, sigBits));

        File dir = certFile.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        writeText(certFile, pem("CERTIFICATE", cert));
        writeText(keyFile, pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        keyFile.setReadable(false, false);
        keyFile.setReadable(true, true);
    }

    private static byte[] parseIp(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] b = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }

    private static byte[] tlv(int tag, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = body.length;
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else if (len < 65536) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len & 0xFF);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        }
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[][] parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            o.write(parts[i], 0, parts[i].length);
        }
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, cat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, cat(parts));
    }

    private static String pem(String label, byte[] der) {
        String b64 = base64(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(b64.length(), i + 64)).append("\n");
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    private static String base64(byte[] in) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xFF;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xFF : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xFF : 0;
            sb.append(abc.charAt(b0 >> 2));
            sb.append(abc.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < in.length ? abc.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < in.length ? abc.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    private static void writeText(File f, String text) throws IOException {
        FileWriter w = new FileWriter(f);
        w.write(text);
        w.close();
    }
}
