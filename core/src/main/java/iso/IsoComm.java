package iso;

import constants.TerminalConstants;

import android.content.Context;
import android.util.Log;

import utils.HexUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import helpers.HelperLog;

public class IsoComm {
    private final String logClassName = this.getClass().getSimpleName();
    private Socket Sc;
    private SSLSocket SSLSc;
    private boolean isConnected = false;
    private DataOutputStream DOS;
    private DataInputStream DIS;
    private String connectionStatus = "";
    private int RespLen = 0;
    private final int soTimeoutMs = 5000;

    protected Context tempContext;

    private static final String TAG = "ISOCOMM";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static void sysPrint(String log) {
        timber.log.Timber.tag(TAG).d(log);
    }

    private static void sysPrint(String message, byte[] data, int dataOffset, int dataLen) {
        timber.log.Timber.tag(TAG).d(message + HexUtil.bytesToHexString(data, dataOffset, dataLen));
    }

    // IP OR DNS CHECKING
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(([0-9]{1,3})\\.){3}([0-9]{1,3})$");

    public static boolean isIPv4(String input) {
        if (!IPV4_PATTERN.matcher(input).matches()) return false;
        String[] parts = input.split("\\.");
        for (String part : parts) {
            int num = Integer.parseInt(part);
            if (num < 0 || num > 255) return false;
        }
        return true;
    }

    public static boolean isIPv6(String input) {
        return input.contains(":"); // Simple check
    }
    // IP OR DNS CHECKING

    private void applySocketOptions(Socket s, int soTimeoutMs) throws IOException {
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSendBufferSize(64 * 1024);
        s.setReceiveBufferSize(64 * 1024);
        //s.setSoTimeout(soTimeoutMs);
    }

    private int tcpConnect(String ip1, int port1, int timeoutms) {
        boolean isConnection = false;
        int iConnectResp = -1;
        isConnected = false;

        InetSocketAddress sockAdr;
        try {
            sysPrint("Connecting with Connection(" + ip1 + ":" + port1 + ")...");
            sockAdr = new InetSocketAddress(ip1, port1);
            Sc = new Socket();
            Sc.connect(sockAdr, timeoutms);
            applySocketOptions(Sc, soTimeoutMs);
            isConnection = true;
        } catch (Exception e) {
            sysPrint("Connection Fail...(" + ip1 + ":" + port1 + ")...");
            e.printStackTrace();
        }

        if (isConnection) {
            iConnectResp = 0;
            isConnected = true;
        }

        return iConnectResp;
    }

    private int sslTcpConnect(int certId, String ip, int port, int timeoutms) {
        boolean isConnection = false;
        int iConnectResp = -1;
        isConnected = false;

        char[] passphrase = "mysecret".toCharArray();

        // A commented-out first attempt at the SSL setup lived here. It was superseded by the
        // try-with-resources block below and removed on the move to :core -- it referenced the
        // app's ServiceHolder, which no longer exists from this module.

        try {
            File sslFile = new File(CurrentCertStore.INSTANCE.keystoreDir(), "terminal_keystore.bks");

            // Try-with-resources ensures the stream is closed in all cases
            try (InputStream sslInputStream = sslFile.exists()
                    ? new FileInputStream(sslFile)
                    : CurrentCertStore.INSTANCE.openCertificate(certId)) {

                KeyStore keystore = KeyStore.getInstance("BKS");
                keystore.load(sslInputStream, passphrase);

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keystore);

                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, tmf.getTrustManagers(), null);
                SSLSocketFactory sf = sslContext.getSocketFactory();

                // Create + connect SSLSocket
                SSLSocket ssl = (SSLSocket) sf.createSocket();
                ssl.connect(new InetSocketAddress(ip, port), timeoutms);

                applySocketOptions(ssl, soTimeoutMs);
                ssl.startHandshake();

                SSLSc = ssl;
                Sc = ssl;
                isConnection = true;
            }
        } catch (Exception e) {
            sysPrint("SSL Connection Fail...(" + ip + ":" + port + ")...");
            e.printStackTrace();
        }

        if (isConnection) {
            iConnectResp = 0;
            isConnected = true;
        }

        return iConnectResp;
    }

    private int tcpTransmit(byte[] datain, int datainoffset, int datainlen, byte[] resp, int respoffset, int respMaxsize, int timeoutms, HelperLog helperLog) {
        int iReceivedLen = -1;
        //boolean exceptionOccurred = false;
        long start = System.nanoTime();

        helperLog.appendLine(logClassName, "DataOut :: " + HexUtil.bytesToHexString(datain, datainoffset, datainlen));
        try {
            Sc.setSoTimeout(timeoutms);
            DOS = new DataOutputStream(Sc.getOutputStream());
            DIS = new DataInputStream(Sc.getInputStream());

            helperLog.appendLine(logClassName, "Sending...");
            connectionStatus = "Sending...";

            long t0 = System.nanoTime();
            DOS.write(datain, datainoffset, datainlen);
            long t1 = System.nanoTime();
            DOS.flush();
            long t2 = System.nanoTime();

            helperLog.appendLine(logClassName, "Receiving...");
            connectionStatus = "Receiving...";
            //iReceivedLen = DIS.read(resp, respoffset, respMaxsize);
            iReceivedLen = readLen2AndFully(DIS, resp, respoffset, respMaxsize);
            long t3 = System.nanoTime();

            helperLog.appendLine(logClassName, "TIMING write=" + ((t1-t0)/1_000_000.0) + "ms"
                    + " flush=" + ((t2-t1)/1_000_000.0) + "ms"
                    + " waitResp=" + ((t3-t2)/1_000_000.0) + "ms");

            helperLog.appendLine(logClassName, "Received Msg Length: " + iReceivedLen);
            helperLog.appendLine(logClassName, "Received...");
            helperLog.appendLine(logClassName, "DataIn >> " + HexUtil.bytesToHexString(resp, respoffset, iReceivedLen));
        } catch (Exception e) {
            //exceptionOccurred = true;
            helperLog.appendLine(logClassName, "Timeout...");
            System.out.println("tcpTransmit exception !!!!");
            connectionStatus = "Timeout...";
            e.printStackTrace();
        }

        long end = System.nanoTime();
        double elapsedTimeInSecond = (double) (end - start) / 1_000_000_000;
        helperLog.appendLine(logClassName, "TimeTaken:" + elapsedTimeInSecond + "(S)");
        helperLog.appendLine(logClassName, "tcpTransmit: " + iReceivedLen);

        /*if(exceptionOccurred && elapsedTimeInSecond < 1) {
            return -2;
        }*/
        return iReceivedLen == 0 ? -1 : iReceivedLen;
    }

    private int readLen2AndFully(DataInputStream in, byte[] resp, int respOff, int respMax) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) return -1;

        int len = ((b1 & 0xFF) << 8) | (b2 & 0xFF);
        if (len <= 0) return -1;

        int totalLen = len + 2;
        if (totalLen > respMax) throw new IOException("Response too large: " + totalLen);

        // store length header
        resp[respOff]     = (byte) b1;
        resp[respOff + 1] = (byte) b2;

        in.readFully(resp, respOff + 2, len);
        //in.readFully(resp, respOff + 2, totalLen);
        return totalLen;
    }

    private void tcpDisconnect() {
        try {
            /*if (!isConnected) {
                timber.log.Timber.tag(TAG).d("socket not created");
                return;
            }*/

            if (DOS != null) {
                try {
                    DOS.close();
                } catch (IOException ignored) {}
                DOS = null;
            }

            if (DIS != null) {
                try {
                    DIS.close();
                } catch (IOException ignored) {}
                DIS = null;
            }

            if (Sc != null) {
                try {
                    Sc.close();
                } catch (IOException ignored) {}
                Sc = null;
                SSLSc = null;
            }

        } catch (Exception ex) {
            Log.e(TAG, "channel disconnect failed: " + ex);
        } finally {
            isConnected = false;
        }
    }

    private void abortiveClose() {
        // Close streams first
        if (DOS != null) { try { DOS.close(); } catch (IOException ignored) {} DOS = null; }
        if (DIS != null) { try { DIS.close(); } catch (IOException ignored) {} DIS = null; }

        Socket s = Sc;
        Sc = null;
        SSLSc = null;
        isConnected = false;

        if (s == null) return;
        try { s.setSoLinger(true, 0); } catch (Exception ignored) {}
        try { s.close(); } catch (Exception ignored) {}
    }

    private int tryConnectPrimarySecondary(boolean useSSL, int certID, String ip1, String port1, Boolean port1SSL, String ip2, String port2, Boolean port2SSL, HelperLog helperLog) {
        int iPort = utils.ByteOps.atoi(port1);
        connectionStatus = "Connecting with Primary connection";
        helperLog.appendLine(logClassName, connectionStatus);
        int iSid = -1;
        if (!isIPv4(ip1)) {
            iSid = tryConnectWithDns(ip1, port1, port1SSL, certID, helperLog);
        } else {
            iSid = port1SSL ? sslTcpConnect(certID, ip1, iPort, 5000) : tcpConnect(ip1, iPort, 5000);
        }

        if (iSid < 0) {
            connectionStatus = "Fail to connect with Primary connection";
            helperLog.appendLine(logClassName, connectionStatus);

            iPort = utils.ByteOps.atoi(port2);
            connectionStatus = "Connecting with Secondary connection";
            helperLog.appendLine(logClassName, connectionStatus);
            if (!isIPv4(ip2)) {
                iSid = tryConnectWithDns(ip2, port2, port2SSL, certID, helperLog);
            } else {
                iSid = port2SSL ? sslTcpConnect(certID, ip2, iPort, 5000) : tcpConnect(ip2, iPort, 5000);
            }

            if (iSid < 0) {
                connectionStatus = "Fail to connect with Secondary connection";
                helperLog.appendLine(logClassName, connectionStatus);
                return TerminalConstants.iso.err.connectionFailed;
            } else {
                connectionStatus = "Connected with Secondary connection";
                helperLog.appendLine(logClassName, connectionStatus);
            }
        } else {
            // Primary connected -- the expected path. connectionStatus still feeds the UI, but it
            // is deliberately NOT logged: silence on the happy path, a line only on a failover.
            // "Connected to IP" in tryConnectWithDns still records which address actually won.
            connectionStatus = "Connected with Primary connection";
        }

        return iSid;
    }

    private int tryConnectWithDns(String hostname, String portStr, boolean useSSL, int certID, HelperLog log) {
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            log.appendLine(logClassName, "Invalid port for " + hostname + ": " + portStr);
            return -1;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            log.appendLine(logClassName, "DNS resolution failed for (" + hostname + "): " + e.getMessage());
            return -1;
        }

        log.appendLine(logClassName, "Attempting connection : " + hostname + " ( " + addresses.length + " )");
        for (InetAddress addr : addresses) {
            String ip = addr.getHostAddress();
            log.appendLine(logClassName, "Trying IP : " + ip + ":" + port);

            int iSid = useSSL ? sslTcpConnect(certID, ip, port, 5000) : tcpConnect(ip, port, 5000);
            if (iSid >= 0) {
                log.appendLine(logClassName, "Connected to IP : " + ip + ":" + port);
                return iSid;
            } else {
                log.appendLine(logClassName, "Failed to connect to IP: " + ip + ":" + port);
            }
        }

        log.appendLine(logClassName, "All IPs failed for " + hostname);
        return -1;
    }



    public int sendToHost(HelperLog helperLog, String ip1, String port1, String ip2, String port2, int connectTimeoutms, int timeoutms, byte[] data, int dataoffset, int dataLen, byte[] resp, int respOffset, int respMaxsize) {
        connectionStatus = "";
        helperLog.appendLine(logClassName, "SendToHost[" + ip1 + ", " + port1 + ", " + timeoutms + "]");

        int iSid = tryConnectPrimarySecondary(false, 0, ip1, port1, false, ip2, port2, false, helperLog);
        if (iSid < 0) return TerminalConstants.iso.err.connectionFailed;
        Future<Integer> future = executorService.submit(() -> tcpTransmit(data, dataoffset, dataLen, resp, respOffset, respMaxsize, timeoutms, helperLog));

        // Manual tick loop logging during wait
        int tickTimeMs = (timeoutms / 1000);
        String sTmp = "Receiving...";
        //connectionStatus = sTmp; // initialize connection status

        while (tickTimeMs > 0) {
            if (connectionStatus.contains(sTmp)) {
                helperLog.appendLine(logClassName, "sendToHost: " + tickTimeMs);
                connectionStatus = sTmp + "(" + tickTimeMs + "s)";
                tickTimeMs--;
            }

            // Check if the Future has completed
            if (future.isDone()) {
                helperLog.appendLine(logClassName, "close check future done");
                break;
            }

            utils.Util.DelayMili(1000); // delay 1 second
        }

        RespLen = 0;
        try {
            RespLen = future.get(timeoutms + 1000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            abortiveClose();
            connectionStatus = "Timeout...";
            helperLog.appendLine(logClassName, "Timeout...");
            RespLen = -1;
            return TerminalConstants.iso.err.communicationTimeout;
        } catch (Exception e) {
            helperLog.appendLine(logClassName, "Transmit exception: " + e.getMessage());
            RespLen = -1;
        }

        tcpDisconnect();

        if (RespLen < 0) {
            connectionStatus = "Timeout...";
            return TerminalConstants.iso.err.communicationTimeout;
        }

        return RespLen;
    }

    public int sendToHostWithSSL(HelperLog helperLog, int certID, String ip1, String port1, Boolean port1SSL, String ip2, String port2, Boolean port2SSL, int connectTimeoutms, int timeoutms, byte[] data, int dataoffset, int dataLen, byte[] resp, int respOffset, int respMaxsize) {
        connectionStatus = "";
        helperLog.appendLine(logClassName, "SendToHost[" + ip1 + ", " + port1 + ", " + timeoutms + "] (SSL :: " + port1SSL + " )");

        int iSid = tryConnectPrimarySecondary(true, certID, ip1, port1, port1SSL, ip2, port2, port2SSL, helperLog);
        if (iSid < 0) return TerminalConstants.iso.err.connectionFailed;
        Future<Integer> future = executorService.submit(() -> tcpTransmit(data, dataoffset, dataLen, resp, respOffset, respMaxsize, timeoutms, helperLog));

        // Manual tick loop logging during wait
        int tickTimeMs = (timeoutms / 1000);
        String sTmp = "Receiving...";
        //connectionStatus = sTmp; // initialize connection status

        while (tickTimeMs > 0) {
            if (connectionStatus.contains(sTmp)) {
                helperLog.appendLine(logClassName, "sendToHost: " + tickTimeMs);
                connectionStatus = sTmp + "(" + tickTimeMs + "s)";
                tickTimeMs--;
            }

            // Check if the Future has completed
            if (future.isDone()) {
                helperLog.appendLine(logClassName, "close check future done");
                break;
            }

            utils.Util.DelayMili(1000); // delay 1 second
        }

        RespLen = 0;
        try {
            RespLen = future.get(timeoutms + 1000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            abortiveClose();
            connectionStatus = "Timeout...";
            helperLog.appendLine(logClassName, "Timeout...");
            RespLen = -1;
            return TerminalConstants.iso.err.communicationTimeout;
        } catch (Exception e) {
            helperLog.appendLine(logClassName, "Transmit exception: " + e.getMessage());
            RespLen = -1;
        }

        //System.out.println("Response length: " + RespLen);
        /*if(RespLen == -2) {
            helperLog.appendLine(logClassName, "Fall Back Plan ???");
            future.cancel(true);
            abortiveClose();
            connectionStatus = "";

            iSid = tryConnectPrimarySecondary(true, certID, ip2, port2, port2SSL, ip1, port1, port1SSL,helperLog);
            if (iSid < 0) return TerminalConstants.iso.err.connectionFailed;
            future = executorService.submit(() -> tcpTransmit(data, dataoffset, dataLen, resp, respOffset, respMaxsize, timeoutms, helperLog));

            // Manual tick loop logging during wait
            tickTimeMs = (timeoutms / 1000);
            sTmp = "Receiving...";
            //connectionStatus = sTmp; // initialize connection status

            while (tickTimeMs > 0) {
                if (connectionStatus.contains(sTmp)) {
                    helperLog.appendLine(logClassName, "sendToHost: " + tickTimeMs);
                    connectionStatus = sTmp + "(" + tickTimeMs + "s)";
                    tickTimeMs--;
                }

                // Check if the Future has completed
                if (future.isDone()) {
                    helperLog.appendLine(logClassName, "close check future done");
                    break;
                }

                utils.Util.DelayMili(1000); // delay 1 second
            }

            RespLen = 0;
            try {
                RespLen = future.get(timeoutms + 1000L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                abortiveClose();
                connectionStatus = "Timeout...";
                helperLog.appendLine(logClassName, "Timeout...");
                RespLen = -1;
                return TerminalConstants.iso.err.communicationTimeout;
            } catch (Exception e) {
                helperLog.appendLine(logClassName, "Transmit exception: " + e.getMessage());
                RespLen = -1;
            }
            System.out.println("Response length: " + RespLen);
            System.out.println("Fall Back End ???");
        }*/

        System.out.println("Response length final: " + RespLen);
        tcpDisconnect();
        if (RespLen < 0) {
            connectionStatus = "Timeout...";
            return TerminalConstants.iso.err.communicationTimeout;
        }

        return RespLen;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }
}