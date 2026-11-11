package com.codex.asmrplayer;

import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地 HTTP 流代理：把 net:// 文件转成 http://127.0.0.1/... 供 MediaPlayer /
 * MediaExtractor 播放，支持 Range 请求（seek 需要）。
 */
final class StreamProxy {
    private static final String TAG = "StreamProxy";

    private static final StreamProxy INSTANCE = new StreamProxy();

    static StreamProxy get() {
        return INSTANCE;
    }

    private ServerSocket serverSocket;
    private volatile int port = -1;
    private final Map<String, NetSource> sources = new HashMap<>();

    private StreamProxy() {
    }

    synchronized void setSources(Map<String, NetSource> map) {
        sources.clear();
        sources.putAll(map);
    }

    synchronized void start() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }
        try {
            serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
            Thread acceptThread = new Thread(this::acceptLoop, "StreamProxy-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "StreamProxy started on 127.0.0.1:" + port);
        } catch (Exception ex) {
            port = -1;
            Log.e(TAG, "StreamProxy start failed", ex);
        }
    }

    int getPort() {
        return port;
    }

    boolean isRunning() {
        return port > 0 && serverSocket != null && !serverSocket.isClosed();
    }

    /** net://<sourceId>/<path> → 本地代理 http 地址。 */
    String urlFor(String netUri) {
        Uri uri = Uri.parse(netUri);
        String id = uri.getAuthority();
        String path = uri.getPath();
        if (path == null) {
            path = "";
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return "http://127.0.0.1:" + port + "/s/" + id + "/" + Uri.encode(path, "/");
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> handle(socket), "StreamProxy-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception ex) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    Log.w(TAG, "accept failed: " + ex);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            String requestLine = reader.readLine();
            if (requestLine == null || !requestLine.startsWith("GET")) {
                return;
            }
            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                }
            }
            serve(socket, requestLine, rangeHeader);
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void serve(Socket socket, String requestLine, String rangeHeader) {
        try {
            String target = requestLine.split(" ")[1];
            if (!target.startsWith("/s/")) {
                respondError(socket, 404);
                return;
            }
            String rest = target.substring(3);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                respondError(socket, 404);
                return;
            }
            String sourceId = rest.substring(0, slash);
            String relPath = Uri.decode(rest.substring(slash + 1));
            while (relPath.startsWith("/")) {
                relPath = relPath.substring(1);
            }
            NetSource source;
            synchronized (sources) {
                source = sources.get(sourceId);
            }
            if (source == null) {
                respondError(socket, 404);
                return;
            }
            long size = NetClient.sizeOf(source, relPath);
            long start = 0;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                try {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    partial = true;
                } catch (Exception ignored) {
                }
                if (start < 0) {
                    start = 0;
                }
                if (size > 0 && start >= size) {
                    respondError(socket, 416);
                    return;
                }
            }
            Log.d(TAG, "GET " + relPath + " range=" + rangeHeader + " start=" + start + " size=" + size);

            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK").append("\r\n");
            head.append("Content-Type: ").append(mimeFor(relPath)).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (size > 0) {
                head.append("Content-Length: ").append(size - start).append("\r\n");
                if (partial) {
                    head.append("Content-Range: bytes ").append(start).append('-')
                            .append(size - 1).append('/').append(size).append("\r\n");
                }
            }
            head.append("Connection: close\r\n\r\n");

            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            out.write(head.toString().getBytes("ISO-8859-1"));
            out.flush();

            InputStream in = NetClient.openRange(source, relPath, start);
            byte[] buffer = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                out.flush();
            } catch (Exception ignored) {
                // 客户端断开（seek 场景常见），静默结束
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "serve failed: " + ex);
            try {
                respondError(socket, 500);
            } catch (Exception ignored) {
            }
        }
    }

    private void respondError(Socket socket, int code) throws Exception {
        String reason = code == 404 ? "Not Found" : code == 416 ? "Range Not Satisfiable" : "Internal Server Error";
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + code + " " + reason
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.flush();
    }

    private static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("mp3")) {
                return "audio/mpeg";
            }
            if (ext.equals("wav")) {
                return "audio/wav";
            }
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }
}
