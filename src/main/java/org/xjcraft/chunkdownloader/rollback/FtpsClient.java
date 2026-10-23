package org.xjcraft.chunkdownloader.rollback;

import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.ftp.FTPReply;
import org.xjcraft.chunkdownloader.config.AzureConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * FTPS 客户端：基于 Apache Commons Net 的 FTPSClient。
 *
 * 只实现回滚需要的目录列举与 RETR；每次操作新建连接。控制/数据通道的 TLS、
 * PASV、MLSD/LIST 回退、TYPE I 等全部交给 Commons Net。vsftpd 的
 * require_ssl_reuse=YES 需要在服务端关闭——Commons Net 不做数据连接的 TLS 会话
 * 复用（见 NET-408），这是业界已知限制，标准做法是服务端 require_ssl_reuse=NO。
 */
public class FtpsClient {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean trustAll;

    public FtpsClient(AzureConfig cfg) {
        this(pick(cfg.getFtpRollbackHost(), cfg.getFtpHost()),
                cfg.getFtpRollbackPort() > 0 ? cfg.getFtpRollbackPort() : cfg.getFtpPort(),
                cfg.getFtpRollbackUser(), cfg.getFtpRollbackPassword(),
                cfg.isFtpTrustAllCerts());
    }

    private static String pick(String preferred, String fallback) {
        return preferred != null && !preferred.isEmpty() ? preferred : fallback;
    }

    public FtpsClient(String host, int port, String user, String password,
                      boolean trustAll) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.trustAll = trustAll;
    }

    public boolean isConfigured() {
        return host != null && !host.isEmpty()
                && user != null && !user.isEmpty()
                && password != null && !password.isEmpty();
    }

    /** 列目录。直接用 LIST：该服务器 MLSD 会静默返回空，不可靠。 */
    public List<RemoteEntry> list(String remoteDir) throws IOException {
        FTPSClient ftp = connect();
        try {
            ftp.enterLocalPassiveMode();
            List<RemoteEntry> result = new ArrayList<RemoteEntry>();
            for (org.apache.commons.net.ftp.FTPFile f : ftp.listFiles(remoteDir)) {
                if (f == null || f.isDirectory()) {
                    continue;
                }
                result.add(new RemoteEntry(f.getName(), f.getSize(),
                        f.getTimestamp() == null ? null
                                : f.getTimestamp().getTimeInMillis()));
            }
            return result;
        } finally {
            quit(ftp);
        }
    }

    /** 下载到本地文件，返回实际写入字节数。 */
    public long download(String remotePath, File target) throws IOException {
        FTPSClient ftp = connect();
        FileOutputStream out = null;
        try {
            ftp.setBufferSize(64 * 1024);
            out = new FileOutputStream(target);
            if (!ftp.retrieveFile(remotePath, out)) {
                String reply = ftp.getReplyString();
                throw new IOException("RETR " + remotePath + " 失败: " + reply);
            }
            out.close();
            out = null;
            return target.length();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            quit(ftp);
        }
    }

    private FTPSClient connect() throws IOException {
        if (!isConfigured()) {
            throw new IOException("FTPS 未配置: 缺少 host/user/password");
        }
        FTPSClient ftp;
        if (trustAll) {
            ftp = new FTPSClient(false, trustAllContext());
        } else {
            ftp = new FTPSClient(false);
        }
        ftp.setDefaultTimeout(READ_TIMEOUT_MS);
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftp.setDefaultPort(port);
        ftp.setControlKeepAliveTimeout(0);
        try {
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                ftp.disconnect();
                throw new IOException("FTPS 服务器拒绝连接: " + ftp.getReplyString());
            }
            if (!ftp.login(user, password)) {
                String reply = ftp.getReplyString();
                quit(ftp);
                throw new IOException("FTPS 登录失败: " + reply);
            }
            ftp.execPBSZ(0);
            ftp.execPROT("P");
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
            ftp.enterLocalPassiveMode();
            // 保留控制连接超时；数据连接读超时通过 setDataTimeout 控制
            ftp.setDataTimeout(READ_TIMEOUT_MS);
            return ftp;
        } catch (IOException e) {
            try {
                ftp.disconnect();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void quit(FTPSClient ftp) {
        if (ftp == null || !ftp.isConnected()) {
            return;
        }
        try {
            ftp.logout();
        } catch (IOException ignored) {
        }
        try {
            ftp.disconnect();
        } catch (IOException ignored) {
        }
    }

    private static SSLContext trustAllContext() throws IOException {
        TrustManager[] trustAllManager = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAllManager, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS 初始化失败", e);
        }
    }
}
