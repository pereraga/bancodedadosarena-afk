package com.totem.screen;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP Local Embutido no Totem Android.
 * Resolve 100% dos problemas de reprodução de vídeo offline em WebViews Android (incluindo Android 6.0.1):
 * - Fornece suporte completo a cabeçalhos HTTP Range (HTTP 206 Partial Content) para o MediaPlayer/MediaCodec nativo.
 * - Serve os arquivos diretamente via loopback local (127.0.0.1:8888) sem restrições de CORS ou SSL.
 * - Extrai e serve o vídeo offline (offline_sample.mp4) e vídeos baixados em cache.
 */
public class LocalHttpServer {
    private static final String TAG = "LocalHttpServer";
    public static final int DEFAULT_PORT = 8888;

    private final Context context;
    private int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private ExecutorService threadPool;

    private File extractedSampleVideo;
    private File cachedVideo;

    public LocalHttpServer(Context context) {
        this(context, DEFAULT_PORT);
    }

    public LocalHttpServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        // Prepara os arquivos locais (extrai vídeo offline se necessário)
        prepareLocalFiles();

        // Vincula o socket imediatamente para garantir que requisições não sofram conexão recusada
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            Log.w(TAG, "Porta " + port + " ocupada, buscando porta livre...");
            try {
                serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                port = serverSocket.getLocalPort();
            } catch (IOException ex) {
                Log.e(TAG, "Falha crítica ao criar server socket local", ex);
            }
        }
        Log.i(TAG, "Servidor HTTP local do Totem rodando em " + getBaseUrl());

        threadPool = Executors.newCachedThreadPool();
        threadPool.execute(() -> {
            try {
                while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setSoTimeout(15000);
                        if (threadPool != null && !threadPool.isShutdown()) {
                            threadPool.execute(() -> handleClient(clientSocket));
                        }
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro no loop do servidor HTTP local", e);
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException ignored) {}
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    public void prepareLocalFiles() {
        try {
            File filesDir = context.getFilesDir();
            extractedSampleVideo = new File(filesDir, "offline_sample.mp4");
            cachedVideo = new File(filesDir, "cached_video.mp4");

            // Verifica se precisa extrair o vídeo de demonstração dos assets
            boolean needExtract = !extractedSampleVideo.exists() || extractedSampleVideo.length() < 100000;
            if (needExtract) {
                Log.i(TAG, "Extraindo offline_sample.mp4 dos assets para o disco interno...");
                try (InputStream in = context.getAssets().open("offline_sample.mp4");
                     FileOutputStream out = new FileOutputStream(extractedSampleVideo)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    Log.i(TAG, "Vídeo offline extraído com sucesso: " + extractedSampleVideo.length() + " bytes");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Falha ao preparar arquivos locais do Totem", e);
        }
    }

    public File getActiveVideoFile() {
        if (cachedVideo != null && cachedVideo.exists() && cachedVideo.length() > 50000) {
            return cachedVideo;
        }
        if (extractedSampleVideo != null && extractedSampleVideo.exists() && extractedSampleVideo.length() > 50000) {
            return extractedSampleVideo;
        }
        return null;
    }

    public File getCachedVideoFile() {
        return cachedVideo;
    }

    public File getSampleVideoFile() {
        return extractedSampleVideo;
    }

    private void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String uri = parts[1];

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // Remove query parameters para encontrar o arquivo
            String path = uri;
            int qIdx = path.indexOf('?');
            if (qIdx != -1) {
                path = path.substring(0, qIdx);
            }

            if (path.equals("/") || path.equals("/screen") || path.equals("/screen.html")) {
                serveAsset(out, "screen.html", "text/html; charset=utf-8");
            } else if (path.equals("/index.html")) {
                serveAsset(out, "index.html", "text/html; charset=utf-8");
            } else if (path.equals("/offline_sample.mp4") || path.equals("/video.mp4") || path.equals("/cached_video.mp4")) {
                serveVideo(out, path, headers);
            } else if (path.startsWith("/api/status")) {
                serveJsonStatus(out);
            } else {
                String assetPath = path.startsWith("/") ? path.substring(1) : path;
                String mime = getMimeType(assetPath);
                serveAsset(out, assetPath, mime);
            }

        } catch (Exception ignored) {
            // Conexão abortada ou fechada pelo cliente durante streaming
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void serveVideo(OutputStream out, String path, Map<String, String> headers) throws IOException {
        File videoFile;
        if (path.equals("/cached_video.mp4") && cachedVideo != null && cachedVideo.exists() && cachedVideo.length() > 50000) {
            videoFile = cachedVideo;
        } else {
            videoFile = getActiveVideoFile();
        }

        if (videoFile == null || !videoFile.exists()) {
            String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        long fileLength = videoFile.length();
        String rangeHeader = headers.get("range");

        long start = 0;
        long end = fileLength - 1;
        boolean isPartial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true;
            String rangeVal = rangeHeader.substring(6).trim();
            int dash = rangeVal.indexOf('-');
            if (dash != -1) {
                String sStart = rangeVal.substring(0, dash).trim();
                String sEnd = rangeVal.substring(dash + 1).trim();
                if (!sStart.isEmpty()) {
                    try { start = Long.parseLong(sStart); } catch (NumberFormatException ignored) {}
                }
                if (!sEnd.isEmpty()) {
                    try { end = Long.parseLong(sEnd); } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (start > end || start >= fileLength) {
            String resp = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */" + fileLength + "\r\n" +
                    "Content-Length: 0\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        if (end >= fileLength) {
            end = fileLength - 1;
        }

        long contentLength = end - start + 1;

        StringBuilder headerBuilder = new StringBuilder();
        if (isPartial) {
            headerBuilder.append("HTTP/1.1 206 Partial Content\r\n");
            headerBuilder.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n");
        } else {
            headerBuilder.append("HTTP/1.1 200 OK\r\n");
        }
        headerBuilder.append("Content-Type: video/mp4\r\n");
        headerBuilder.append("Accept-Ranges: bytes\r\n");
        headerBuilder.append("Content-Length: ").append(contentLength).append("\r\n");
        headerBuilder.append("Access-Control-Allow-Origin: *\r\n");
        headerBuilder.append("Connection: keep-alive\r\n");
        headerBuilder.append("\r\n");

        out.write(headerBuilder.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long bytesRemaining = contentLength;
            while (bytesRemaining > 0) {
                int toRead = (int) Math.min(buf.length, bytesRemaining);
                int bytesRead = raf.read(buf, 0, toRead);
                if (bytesRead <= 0) break;
                out.write(buf, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }
            out.flush();
        }
    }

    private void serveAsset(OutputStream out, String assetPath, String mimeType) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] data = baos.toByteArray();

            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + mimeType + "\r\n" +
                    "Content-Length: " + data.length + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n";

            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        } catch (Exception e) {
            String err = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
            out.write(err.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void serveJsonStatus(OutputStream out) throws IOException {
        String json = "{\"status\":\"ok\",\"videoReady\":" + (getActiveVideoFile() != null) + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
