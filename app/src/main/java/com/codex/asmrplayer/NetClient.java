package com.codex.asmrplayer;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbRandomAccessFile;

/** SMB / FTP 的目录列举与流式读取封装。所有方法均为阻塞 IO，须在后台线程调用。 */
final class NetClient {
    static final int MAX_SCAN_DEPTH = 8;

    /** 目录条目。dir 为相对根路径的父目录，空串表示根目录。 */
    static final class Entry {
        final String name;
        final String dir;
        final boolean isDir;
        final long size;

        Entry(String name, String dir, boolean isDir, long size) {
            this.name = name;
            this.dir = dir;
            this.isDir = isDir;
            this.size = size;
        }
    }

    /** 文件大小缓存，扫描时填充，供本地代理构造 Content-Length。 */
    private static final Map<String, Long> SIZE_CACHE = new ConcurrentHashMap<>();

    /**
     * jcifs 上下文缓存：BaseContext/PropertyConfiguration 构造开销大，且每个上下文
     * 有独立连接池。每次开流都新建会强制重新协商 SMB 会话，图片逐张加载时非常慢。
     * key 含凭据，改密码后自动使用新上下文。
     */
    private static final Map<String, CIFSContext> SMB_CONTEXTS = new ConcurrentHashMap<>();

    private NetClient() {
    }

    /** 递归列举源内全部文件。maxDepth 限制目录深度。 */
    static List<Entry> list(NetSource src, int maxDepth) throws Exception {
        List<Entry> out = new ArrayList<>();
        if ("ftp".equals(src.type)) {
            FTPClient ftp = connectFtp(src);
            try {
                listFtp(src, ftp, ftpPath(src, ""), "", 0, maxDepth, out);
            } finally {
                closeFtp(ftp);
            }
        } else {
            listSmb(smbContext(src), src, "", 0, maxDepth, out);
        }
        return out;
    }

    // ---------------- SMB ----------------

    private static CIFSContext smbContext(NetSource src) throws Exception {
        String cacheKey = src.id + "|" + (src.user == null ? "" : src.user)
                + "|" + (src.pass == null ? "" : src.pass);
        CIFSContext cached = SMB_CONTEXTS.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (SMB_CONTEXTS) {
            cached = SMB_CONTEXTS.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.connTimeout", "10000");
            props.setProperty("jcifs.smb.client.responseTimeout", "15000");
            props.setProperty("jcifs.smb.client.soTimeout", "15000");
            CIFSContext base = new BaseContext(new PropertyConfiguration(props));
            CIFSContext result;
            if (src.user == null || src.user.trim().isEmpty()) {
                result = base.withAnonymousCredentials();
            } else {
                String domain = "";
                String user = src.user.trim();
                int backslash = user.indexOf('\\');
                if (backslash > 0) {
                    domain = user.substring(0, backslash);
                    user = user.substring(backslash + 1);
                }
                result = base.withCredentials(new NtlmPasswordAuthenticator(
                        domain, user, src.pass == null ? "" : src.pass));
            }
            SMB_CONTEXTS.put(cacheKey, result);
            return result;
        }
    }

    private static String smbUrl(NetSource src, String relPath, boolean trailingSlash) {
        StringBuilder builder = new StringBuilder("smb://");
        builder.append(src.host);
        if (src.port > 0) {
            builder.append(':').append(src.port);
        }
        String base = src.path == null ? "" : src.path.trim();
        while (base.startsWith("/")) {
            base = base.substring(1);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.isEmpty()) {
            builder.append('/').append(base);
        }
        if (relPath != null && !relPath.isEmpty()) {
            builder.append('/').append(relPath);
        }
        if (trailingSlash) {
            builder.append('/');
        }
        return builder.toString();
    }

    private static void listSmb(CIFSContext ctx, NetSource src, String relDir, int depth, int maxDepth,
                                List<Entry> out) throws IOException {
        if (depth > maxDepth) {
            return;
        }
        SmbFile dir = new SmbFile(smbUrl(src, relDir, true), ctx);
        SmbFile[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (SmbFile child : children) {
            String name = child.getName();
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.isEmpty() || name.equals(".") || name.equals("..") || isSystemEntry(name)) {
                continue;
            }
            String rel = relDir.isEmpty() ? name : relDir + "/" + name;
            if (child.isDirectory()) {
                listSmb(ctx, src, rel, depth + 1, maxDepth, out);
            } else {
                long size = child.length();
                if (size > 0) {
                    SIZE_CACHE.put(netUri(src, rel), size);
                }
                out.add(new Entry(name, relDir, false, size));
            }
        }
    }

    private static String netUri(NetSource src, String relPath) {
        return "net://" + src.id + "/" + relPath;
    }

    // ---------------- FTP ----------------

    private static FTPClient connectFtp(NetSource src) throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(15000);
        ftp.setControlEncoding("UTF-8");
        ftp.connect(src.host, src.port > 0 ? src.port : 21);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            try {
                ftp.disconnect();
            } catch (Exception ignored) {
            }
            throw new IOException("FTP 连接被拒绝 (" + reply + ")");
        }
        boolean loggedIn;
        if (src.user == null || src.user.trim().isEmpty()) {
            loggedIn = ftp.login("anonymous", "baimo@anonymous");
        } else {
            loggedIn = ftp.login(src.user.trim(), src.pass == null ? "" : src.pass);
        }
        if (!loggedIn) {
            try {
                ftp.disconnect();
            } catch (Exception ignored) {
            }
            throw new IOException("FTP 登录失败，请检查用户名/密码");
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        try {
            ftp.sendCommand("OPTS UTF8", "ON");
        } catch (Exception ignored) {
        }
        return ftp;
    }

    private static void closeFtp(FTPClient ftp) {
        if (ftp == null) {
            return;
        }
        try {
            ftp.logout();
        } catch (Exception ignored) {
        }
        try {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        } catch (Exception ignored) {
        }
    }

    private static String ftpPath(NetSource src, String relPath) {
        String base = src.path == null ? "" : src.path.trim();
        while (base.startsWith("/")) {
            base = base.substring(1);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        StringBuilder builder = new StringBuilder();
        if (!base.isEmpty()) {
            builder.append('/').append(base);
        }
        if (relPath != null && !relPath.isEmpty()) {
            builder.append('/').append(relPath);
        }
        return builder.length() == 0 ? "." : builder.toString();
    }

    private static void listFtp(NetSource src, FTPClient ftp, String remoteDir, String relDir, int depth, int maxDepth,
                                List<Entry> out) throws IOException {
        if (depth > maxDepth) {
            return;
        }
        FTPFile[] files = ftp.listFiles(remoteDir);
        if (files == null) {
            return;
        }
        for (FTPFile file : files) {
            String name = file.getName();
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                    || isSystemEntry(name)) {
                continue;
            }
            String rel = relDir.isEmpty() ? name : relDir + "/" + name;
            if (file.isDirectory()) {
                listFtp(src, ftp, joinRemote(remoteDir, name), rel, depth + 1, maxDepth, out);
            } else if (file.isFile()) {
                long size = file.getSize();
                if (size > 0) {
                    SIZE_CACHE.put(netUri(src, rel), size);
                }
                out.add(new Entry(name, relDir, false, size));
            }
        }
    }

    /** 跳过 NAS 回收站、索引目录等系统条目，避免大库无谓扫描。 */
    private static boolean isSystemEntry(String name) {
        if (name.startsWith("#") || name.startsWith("@") || name.startsWith(".")) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("system volume information")
                || lower.equals("$recycle.bin")
                || lower.equals("recycler");
    }

    private static String joinRemote(String remoteDir, String name) {
        if (remoteDir == null || remoteDir.isEmpty() || remoteDir.equals(".")) {
            return name;
        }
        return remoteDir.endsWith("/") ? remoteDir + name : remoteDir + "/" + name;
    }

    // ---------------- 流式读取 ----------------

    /** 顺序读整个文件（图片/字幕等小文件用）。 */
    static InputStream open(NetSource src, String relPath) throws Exception {
        if ("ftp".equals(src.type)) {
            FTPClient ftp = connectFtp(src);
            InputStream in = ftp.retrieveFileStream(ftpPath(src, relPath));
            if (in == null) {
                int reply = ftp.getReplyCode();
                closeFtp(ftp);
                throw new IOException("FTP 打开文件失败 (" + reply + ")");
            }
            return ftpStream(ftp, in);
        }
        SmbFile file = new SmbFile(smbUrl(src, relPath, false), smbContext(src));
        return new SmbFileInputStream(file);
    }

    /** 从 offset 开始读，供本地代理响应 Range 请求（MediaPlayer seek 用）。 */
    static InputStream openRange(NetSource src, String relPath, long offset) throws Exception {
        if ("ftp".equals(src.type)) {
            FTPClient ftp = connectFtp(src);
            if (offset > 0) {
                ftp.setRestartOffset(offset);
            }
            InputStream in = ftp.retrieveFileStream(ftpPath(src, relPath));
            if (in == null) {
                int reply = ftp.getReplyCode();
                closeFtp(ftp);
                throw new IOException("FTP 打开文件失败 (" + reply + ")");
            }
            return ftpStream(ftp, in);
        }
        SmbRandomAccessFile raf = new SmbRandomAccessFile(
                new SmbFile(smbUrl(src, relPath, false), smbContext(src)), "r");
        if (offset > 0) {
            raf.seek(offset);
        }
        return randomAccessStream(raf);
    }

    /** 取文件大小：优先扫描缓存，否则直连查询。 */
    static long sizeOf(NetSource src, String relPath) throws Exception {
        Long cached = SIZE_CACHE.get(netUri(src, relPath));
        if (cached != null && cached > 0) {
            return cached;
        }
        if ("ftp".equals(src.type)) {
            FTPClient ftp = connectFtp(src);
            try {
                int reply = ftp.sendCommand("SIZE", ftpPath(src, relPath));
                if (reply == 213) {
                    String text = ftp.getReplyString().trim();
                    int space = text.lastIndexOf(' ');
                    if (space >= 0) {
                        long size = Long.parseLong(text.substring(space + 1).trim());
                        SIZE_CACHE.put(netUri(src, relPath), size);
                        return size;
                    }
                }
            } finally {
                closeFtp(ftp);
            }
            return 0;
        }
        SmbFile file = new SmbFile(smbUrl(src, relPath, false), smbContext(src));
        long size = file.length();
        if (size > 0) {
            SIZE_CACHE.put(netUri(src, relPath), size);
        }
        return size;
    }

    private static InputStream ftpStream(final FTPClient ftp, InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                super.close();
                try {
                    ftp.completePendingCommand();
                } catch (Exception ignored) {
                }
                closeFtp(ftp);
            }
        };
    }

    private static InputStream randomAccessStream(final SmbRandomAccessFile raf) {
        return new InputStream() {
            private final byte[] single = new byte[1];

            @Override
            public int read() throws IOException {
                int n = raf.read(single, 0, 1);
                return n < 0 ? -1 : (single[0] & 0xFF);
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                return raf.read(buffer, off, len);
            }

            @Override
            public void close() throws IOException {
                raf.close();
            }
        };
    }
}
