package com.sc.mf919pro.java.utils;

import utils.Util;

import androidx.fragment.app.FragmentActivity;

import com.library.terminal.Utility;
import com.sc.mf919pro.R;
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

public class MessageSender {
    private SSLSocket sslSc;
    private Socket Sc;
    private byte[] message = null;
    private byte[] messageR = null;
    private boolean isNotDone = true;
    private boolean isConnection;
    private boolean isConnectionDone = false;
    private DataOutputStream DOS = null;
    private DataInputStream DIS = null;
    private boolean printed2Log;
    private Thread t1;
    private boolean SSLErrorType;
    private String IPAddress_1;
    private int Port_1;
    private String IPAddress_2;
    private int Port_2;
    private String msgStatus = "Connecting";
    private int timeout = 15000;
    private int rTimeout = 30;
    private FragmentActivity fragmentActivity;

    public MessageSender(String IPAddress_1, int Port_1, String IPAddress_2, int Port_2, int timeout, FragmentActivity fragmentActivity) {
        this.IPAddress_1 = IPAddress_1;
        this.Port_1 = Port_1;
        this.IPAddress_2 = IPAddress_2;
        this.Port_2 = Port_2;
        this.rTimeout = timeout;
        this.fragmentActivity = fragmentActivity;
    }

    public void SendMessage(byte[] message1, boolean isSSL) {
        this.printed2Log = false;
        this.message = message1;
        this.printLog("Send Msg: " + Utility.Bytes2HexString(this.message));
        System.out.println("SSL -> " + isSSL);
        if (isSSL) {
            SSLCommunicationWithHost mComm = new SSLCommunicationWithHost();
            this.t1 = new Thread(mComm);
            this.t1.start();
        } else {
            CommunicationWithHost mComm = new CommunicationWithHost();
            this.t1 = new Thread(mComm);
            this.t1.start();
        }

    }

    public void cancelSocket() {
        try {
            if (this.sslSc != null) {
                this.sslSc.shutdownOutput();
                this.sslSc.shutdownInput();
                this.sslSc.close();
                this.isNotDone = false;
            }
        } catch (Exception var2) {
            var2.printStackTrace();
        }

    }

    public byte[] getReturnMessage() {
        if (this.messageR != null && !this.printed2Log) {
            this.printed2Log = true;
            this.printLog("Receive Msg: " + Utility.Bytes2HexString(this.messageR));
        }

        return this.messageR;
    }

    public boolean getStatus() {
        return this.isNotDone;
    }

    public String getMsgStatus() {
        return this.msgStatus;
    }

    public boolean getConnectionStatus() {
        return this.isConnection;
    }

    public boolean connectionTest() {
        return this.isConnectionDone;
    }

    public boolean getSSLError() {
        return this.SSLErrorType;
    }

    private SSLSocketFactory getSSLSocketFactory() throws Exception {
        char[] passphrase = "mysecret".toCharArray();
        SSLContext sslContext;

        try{
            InputStream sslInputStream = fragmentActivity.getResources().openRawResource(R.raw.bsn_keystore);
            File sslFile = new File(ServiceHolder.Companion.getInternalFilesPaths(), "terminal_keystore.bks");
            if(sslFile.exists()) {
                //TODO Test Android 7
                sslInputStream = new FileInputStream(sslFile);
            }

            // Load the SSL certificate
            KeyStore keystore =  KeyStore.getInstance("BKS");
            //keystore.load(fragmentActivity.getResources().openRawResource(R.raw.bsn_keystore), passphrase);
            keystore.load(sslInputStream, passphrase);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            tmf.init(keystore);

            sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustManagers = tmf.getTrustManagers();
            sslContext.init(null, trustManagers, null);
            return sslContext.getSocketFactory();
        }catch (Exception e){
            this.printLog("Exception in loading ssl certificate");
            e.printStackTrace();
            throw new Exception("SSL Error");
        }
    }

//    private SSLSocketFactory getSSLSocketFactory() throws Exception {
//        try {
//            String keypass = "pass";
//            //FileInputStream fis = new FileInputStream(Util.getCertificate());
//            InputStream fis = fragmentActivity.getResources().openRawResource(R.raw.ssl);
//            Collection<?> col_crt1 = CertificateFactory.getInstance("X509").generateCertificates(fis);
//            int count = col_crt1.size();
//            Certificate crt1 = null;
//            boolean sslError = false;
//
//            while(count > 0) {
//                crt1 = (Certificate)col_crt1.iterator().next();
//
//                try {
//                    this.verifierCertificate(crt1);
//                    break;
//                } catch (CertificateNotYetValidException var12) {
//                    this.printLog("SSL Error : The certificate will be active after, " + var12.getMessage());
//                    sslError = true;
//                    --count;
//                } catch (CertificateExpiredException var13) {
//                    this.printLog("SSL Error : The certificate expired on, " + var13.getMessage());
//                    sslError = true;
//                    --count;
//                }
//            }
//
//            if (!sslError && crt1 != null) {
//                String alias1 = ((X509Certificate)crt1).getSubjectX500Principal().getName();
//                KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
//                ts.load((InputStream)null, keypass.toCharArray());
//                ts.setCertificateEntry(alias1, crt1);
//                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
//                tmf.init(ts);
//                SSLContext ctx = SSLContext.getInstance("TLS");
//                ctx.init((KeyManager[])null, tmf.getTrustManagers(), (SecureRandom)null);
//                SSLSocketFactory factory = ctx.getSocketFactory();
//                return factory;
//            } else {
//                throw new Exception("SSL Error");
//            }
//        } catch (Exception var14) {
//            var14.printStackTrace();
//            String errorMsg = var14.getMessage();
//            int index;
//            if (errorMsg.contains("NotBefore")) {
//                index = errorMsg.indexOf(": ");
//                this.printLog("SSL Error : The certificate will be active after, " + errorMsg.substring(index + 2));
//            } else if (errorMsg.contains("NotAfter")) {
//                index = errorMsg.indexOf(": ");
//                this.printLog("SSL Error : The certificate expired on, " + errorMsg.substring(index + 2));
//            } else {
//                this.printLog("SSL Error : " + errorMsg);
//            }
//
//            throw new Exception("SSL Error");
//        }
//    }

    private void verifierCertificate(Certificate crt1) throws CertificateNotYetValidException, CertificateExpiredException {
        X509Certificate crt = (X509Certificate)crt1;
        crt.checkValidity();
    }

    private void printLog(String string) {
        System.out.println(string);
    }

    private class CommunicationWithHost implements Runnable {
        private CommunicationWithHost() {
        }

        public void run() {
            MessageSender.this.isConnection = true;

            InetSocketAddress sockAdr;
            try {
                MessageSender.this.msgStatus = "Connecting with Primary Connection...";
                MessageSender.this.printLog("Primary Connection");
                MessageSender.this.Sc = new Socket();
                sockAdr = new InetSocketAddress(MessageSender.this.IPAddress_1, MessageSender.this.Port_1);
                MessageSender.this.Sc.connect(sockAdr, MessageSender.this.timeout);
                MessageSender.this.Sc.setSoTimeout(MessageSender.this.rTimeout * 1000);
            } catch (Exception var7) {
                MessageSender.this.msgStatus = "Primary Connection Fail...";
                MessageSender.this.isConnection = false;
                MessageSender.this.printLog("Primary Connection Fail");
            }

            if (!MessageSender.this.isConnection) {
                MessageSender.this.isConnection = true;

                try {
                    MessageSender.this.msgStatus = "Connecting with Secondary Connection...";
                    MessageSender.this.printLog("Secondary Connection");
                    MessageSender.this.Sc = new Socket();
                    sockAdr = new InetSocketAddress(MessageSender.this.IPAddress_2, MessageSender.this.Port_2);
                    MessageSender.this.Sc.connect(sockAdr, MessageSender.this.timeout);
                    MessageSender.this.Sc.setSoTimeout(MessageSender.this.rTimeout * 1000);
                } catch (Exception var6) {
                    MessageSender.this.msgStatus = "Secondary Connection Fail...";
                    MessageSender.this.isConnection = false;
                    MessageSender.this.printLog("Secondary Connection Fail");
                }
            }

            MessageSender.this.printLog("Connected to ...." + MessageSender.this.Sc.getInetAddress());
            if (MessageSender.this.isConnection) {
                MessageSender.this.isConnectionDone = true;

                try {
                    byte[] messageR_1 = new byte[0];
                    MessageSender.this.msgStatus = "Connected...";
                    MessageSender.this.DOS = new DataOutputStream(MessageSender.this.Sc.getOutputStream());
                    MessageSender.this.msgStatus = "Sending...";
                    MessageSender.this.DOS.write(MessageSender.this.message);
                    MessageSender.this.Sc.isOutputShutdown();
                    MessageSender.this.msgStatus = "Receiving...";
                    MessageSender.this.DIS = new DataInputStream(MessageSender.this.Sc.getInputStream());
                    byte[] len1 = new byte[2];
                    MessageSender.this.DIS.read(len1, 0, 2);
                    int newLen = Integer.parseInt(Utility.Bytes2HexString(len1), 16);
                    messageR_1 = new byte[newLen];
                    int len = MessageSender.this.DIS.read(messageR_1, 0, messageR_1.length);
                    MessageSender.this.messageR = new byte[len + 2];
                    System.arraycopy(len1, 0, MessageSender.this.messageR, 0, len1.length);
                    System.arraycopy(messageR_1, 0, MessageSender.this.messageR, 2, len);
                    MessageSender.this.Sc.shutdownInput();
                    MessageSender.this.DOS.close();
                    MessageSender.this.DIS.close();
                    MessageSender.this.Sc.close();
                    MessageSender.this.isNotDone = false;
                } catch (Exception var5) {
                    var5.printStackTrace();
                    MessageSender.this.printLog("SendMessage:" + var5.getMessage());
                    MessageSender.this.isNotDone = false;
                }
            } else {
                MessageSender.this.isConnectionDone = true;
                MessageSender.this.printLog("SendMessage:Socket is not connected");
                MessageSender.this.isNotDone = false;
            }

        }
    }

    private class SSLCommunicationWithHost implements Runnable {
        private SSLCommunicationWithHost() {
        }

        public void run() {
            SSLSocketFactory factory;
            try {
                factory = MessageSender.this.getSSLSocketFactory();
            } catch (Exception var9) {
                MessageSender.this.isConnectionDone = true;
                MessageSender.this.SSLErrorType = true;
                MessageSender.this.isNotDone = false;
                return;
            }

            MessageSender.this.isConnection = true;

            try {
                MessageSender.this.msgStatus = "Connecting with Primary Connection...";
                MessageSender.this.printLog("Primary Connection");
                MessageSender.this.sslSc = (SSLSocket)factory.createSocket();
                InetSocketAddress sockAdr = new InetSocketAddress(MessageSender.this.IPAddress_1, MessageSender.this.Port_1);
                MessageSender.this.sslSc.connect(sockAdr, MessageSender.this.timeout);
                MessageSender.this.sslSc.setSoTimeout(MessageSender.this.rTimeout * 1000);
                MessageSender.this.sslSc.setEnabledCipherSuites(MessageSender.this.sslSc.getSupportedCipherSuites());
                MessageSender.this.sslSc.startHandshake();
            } catch (Exception var8) {
                MessageSender.this.msgStatus = "Primary Connection Fail...";
                MessageSender.this.isConnection = false;
                MessageSender.this.printLog("Primary Connection Fail");
            }

            if (!MessageSender.this.isConnection) {
                MessageSender.this.isConnection = true;

                try {
                    MessageSender.this.msgStatus = "Connecting with Secondary Connection...";
                    MessageSender.this.printLog("Secondary Connection");
                    MessageSender.this.sslSc = (SSLSocket)factory.createSocket(MessageSender.this.IPAddress_2, MessageSender.this.Port_2);
                    MessageSender.this.sslSc.setSoTimeout(MessageSender.this.rTimeout * 1000);
                    MessageSender.this.sslSc.setEnabledCipherSuites(MessageSender.this.sslSc.getSupportedCipherSuites());
                    MessageSender.this.sslSc.startHandshake();
                } catch (Exception var7) {
                    MessageSender.this.msgStatus = "Secondary Connection Fail...";
                    MessageSender.this.isConnection = false;
                    MessageSender.this.printLog("Secondary Connection Fail");
                }
            }

            MessageSender.this.printLog("Connected to ...." + MessageSender.this.sslSc.getInetAddress());
            if (MessageSender.this.isConnection) {
                MessageSender.this.isConnectionDone = true;

                try {
                    byte[] messageR_1 = new byte[0];
                    MessageSender.this.msgStatus = "Connected...";
                    MessageSender.this.DOS = new DataOutputStream(MessageSender.this.sslSc.getOutputStream());
                    MessageSender.this.msgStatus = "Sending...";
                    MessageSender.this.DOS.write(MessageSender.this.message);
                    MessageSender.this.msgStatus = "Receiving...";
                    MessageSender.this.DIS = new DataInputStream(MessageSender.this.sslSc.getInputStream());
                    byte[] len1 = new byte[2];
                    MessageSender.this.DIS.read(len1, 0, 2);
                    int newLen = Integer.parseInt(Utility.Bytes2HexString(len1), 16);
                    messageR_1 = new byte[newLen];
                    int len = MessageSender.this.DIS.read(messageR_1, 0, messageR_1.length);
                    MessageSender.this.messageR = new byte[len + 2];
                    System.arraycopy(len1, 0, MessageSender.this.messageR, 0, len1.length);
                    System.arraycopy(messageR_1, 0, MessageSender.this.messageR, 2, len);
                    MessageSender.this.DOS.close();
                    MessageSender.this.DIS.close();
                    MessageSender.this.sslSc.close();
                    MessageSender.this.isNotDone = false;
                } catch (Exception var6) {
                    MessageSender.this.printLog("Exception: " + var6.getMessage());
                    MessageSender.this.isNotDone = false;
                }
            } else {
                MessageSender.this.isConnectionDone = true;
                MessageSender.this.printLog("SendMessage:Socket is not connected");
                MessageSender.this.isNotDone = false;
            }

        }
    }
}

