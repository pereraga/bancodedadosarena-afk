package com.totem.screen;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import android.graphics.Matrix;
import android.media.AudioManager;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.UUID;

public class MainActivity extends Activity {

    // Reprodutor de Vídeo Nativo de Hardware (Allwinner CedarX)
    private TextureView videoTextureView;
    private Surface videoSurface;
    private MediaPlayer mediaPlayer;
    private String currentVideoPath;
    private float currentVolume = 0.8f;
    private boolean isMuted = false;
    private String permanentDeviceId;

    private WebView webView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View mCustomView;

    private LinearLayout navBar;
    private EditText urlInput;
    private Button navBarTrigger;

    private LocalHttpServer localServer;

    private static final String DEFAULT_CLOUD_URL = "https://totemarena.vercel.app/screen.html";
    private static final String DEFAULT_OFFLINE_URL = "http://127.0.0.1:8888/screen.html";
    private static final String PREFS_NAME = "totem_browser_prefs";
    private static final String KEY_SAVED_URL = "saved_target_url";

    private String currentTargetUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoHideRunnable;
    private boolean isRetrying = false;

    // Limpeza periódica de memória RAM para estabilidade 24/7 em tablets
    private final Runnable memoryCleanerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (webView != null) {
                    webView.clearCache(false);
                }
                System.gc();
            } catch (Exception ignored) {}
            handler.postDelayed(this, 20 * 60 * 1000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Manter tela acesa 100% do tempo (Kiosk 24/7) e com brilho máximo nativo
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f; // 100% brilho máximo nativo no display
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // 2. Modo Imersivo Kiosk (Oculta barras do sistema)
        hideSystemUI();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                hideSystemUI();
            }
        });

        // 3. Inicializar Servidor HTTP Local (Loopback 127.0.0.1:8888 para vídeos offline e H.264 Range 206)
        localServer = new LocalHttpServer(this);
        localServer.start();

        // 4. Identificador Permanente e Fixo do Totem (evita duplicação no painel)
        permanentDeviceId = getPermanentDeviceId();

        // 5. Construção da Interface do Totem (Reprodutor de Vídeo Nativo + Overlay de Controle)
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Camada 0: Reprodutor de Vídeo Nativo por Hardware (TextureView + MediaPlayer)
        videoTextureView = new TextureView(this);
        FrameLayout.LayoutParams tvParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        );
        videoTextureView.setLayoutParams(tvParams);
        videoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                videoSurface = new Surface(surface);
                startOrUpdateNativePlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                adjustVideoAspectRatio();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                releaseMediaPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });
        rootLayout.addView(videoTextureView);

        // Camada 1: WebView Sobreposta Transparente (Interface Kiosk, Emparelhamento, OSD)
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.addView(webView);

        // Container para Vídeo em Tela Cheia Nativo
        customViewContainer = new FrameLayout(this);
        customViewContainer.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        customViewContainer.setVisibility(View.GONE);
        rootLayout.addView(customViewContainer);

        // Barra de Navegação Superior (Transparente e Retrátil)
        buildNavigationBar(rootLayout);

        // Botão Discreto de Disparo da Barra (Canto Superior Direito)
        buildTriggerButton(rootLayout);

        setContentView(rootLayout);

        // 6. Configuração Otimizada do WebView para Reprodução de Vídeos e Áudio
        setupWebSettings();

        // 7. Identificar se tem internet ao abrir e carregar modo online ou offline automaticamente
        detectInternetAndLoad();

        // 8. Agendar ocultação automática da barra após 7 segundos da inicialização
        scheduleAutoHideNavBar();
        handler.postDelayed(memoryCleanerRunnable, 20 * 60 * 1000);
    }

    private void setupWebSettings() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // CRUCIAL: Permite que vídeos e áudios toquem sozinhos sem bloqueio de gesto
        settings.setMediaPlaybackRequiresUserGesture(false);

        // User-Agent moderno para evitar que sites/players detectem navegador desatualizado
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 TotemScreen/2.0");

        // Modo de Cache
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        // Aceleração por Hardware e Fundo Transparente para deixar o vídeo nativo visível atrás
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Ponte JavaScript nativa para diagnósticos e modo offline
        webView.addJavascriptInterface(new TotemNativeBridge(), "AndroidTotem");

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (urlInput != null && !urlInput.hasFocus()) {
                    urlInput.setText(url);
                }
                isRetrying = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (urlInput != null && !urlInput.hasFocus()) {
                    urlInput.setText(url);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                handleLoadError(failingUrl);
            }

            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    handleLoadError(request.getUrl().toString());
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Permite certificados SSL modernos em Android 6 antigo
                handler.proceed();
            }
        });

        // WebChromeClient com suporte completo a vídeo HTML5
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mCustomView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                customViewContainer.addView(view);
                customViewContainer.setVisibility(View.VISIBLE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (mCustomView == null) return;
                customViewContainer.removeView(mCustomView);
                mCustomView = null;
                customViewContainer.setVisibility(View.GONE);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
                hideSystemUI();
            }
        });
    }

    private void detectInternetAndLoad() {
        Toast.makeText(this, "🔍 Verificando conexão de rede...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean hasInternet = checkRealInternetConnection();
            runOnUiThread(() -> {
                String baseUrl = (localServer != null ? localServer.getBaseUrl() : "http://127.0.0.1:8888");
                if (hasInternet) {
                    Toast.makeText(MainActivity.this, "🟢 Internet Detectada! Sincronizando e reproduzindo vídeo...", Toast.LENGTH_SHORT).show();
                    loadUrlWithFallback(baseUrl + "/screen.html?net=online");
                } else {
                    Toast.makeText(MainActivity.this, "🟡 Sem Internet! Modo Offline em reprodução local contínua...", Toast.LENGTH_LONG).show();
                    loadUrlWithFallback(baseUrl + "/screen.html?offline=1&net=offline");
                }
            });
        }).start();
    }

    private boolean checkRealInternetConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            if (netInfo == null || !netInfo.isConnected()) return false;

            // Teste rápido via socket com timeout curto de 2s para verificação instantânea
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("8.8.8.8", 53), 2000);
                return true;
            } catch (Exception ignored) {
                try {
                    URL u = new URL("https://totemarena.vercel.app/screen.html");
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setRequestMethod("HEAD");
                    int code = conn.getResponseCode();
                    return code >= 200 && code < 400;
                } catch (Exception e) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void handleLoadError(String failingUrl) {
        // Se falhou ao carregar a URL por falta de rede, carrega imediatamente o pacote offline local
        if (failingUrl != null && !failingUrl.contains("127.0.0.1")) {
            Toast.makeText(this, "Rede instável. Carregando modo offline embutido...", Toast.LENGTH_SHORT).show();
            if (webView != null) {
                String offlineUrl = (localServer != null ? localServer.getBaseUrl() : "http://127.0.0.1:8888") + "/screen.html?offline=1&net=offline";
                webView.loadUrl(offlineUrl);
            }
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (isRetrying) return;
        isRetrying = true;
        handler.postDelayed(() -> {
            new Thread(() -> {
                if (checkRealInternetConnection()) {
                    runOnUiThread(() -> {
                        isRetrying = false;
                        if (webView != null) {
                            Toast.makeText(MainActivity.this, "🟢 Conexão restabelecida! Sincronizando com a nuvem...", Toast.LENGTH_SHORT).show();
                            webView.loadUrl("javascript:if(window.onInternetRestored){window.onInternetRestored();}");
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        isRetrying = false;
                        scheduleRetry();
                    });
                }
            }).start();
        }, 8000);
    }

    private void loadUrlWithFallback(String url) {
        if (url == null || url.trim().isEmpty()) {
            url = (localServer != null ? localServer.getBaseUrl() : "http://127.0.0.1:8888") + "/screen.html";
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            url = "http://" + url;
        }

        currentTargetUrl = url;

        // Salvar nas preferências
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_SAVED_URL, currentTargetUrl).apply();

        if (urlInput != null) {
            urlInput.setText(currentTargetUrl);
        }

        if (webView != null) {
            webView.loadUrl(currentTargetUrl);
        }
    }

    private void buildNavigationBar(FrameLayout rootLayout) {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(Color.parseColor("#E60A1120")); // Azul escuro 90% opaco
        navBar.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));

        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        navParams.gravity = Gravity.TOP;
        navBar.setLayoutParams(navParams);

        // Linha 1: Input de Endereço + Botão IR + Botão Recarregar + Fechar
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Campo de Endereço URL
        urlInput = new EditText(this);
        urlInput.setText(currentTargetUrl);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setTextSize(13);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHint("Digite a URL do totem...");
        urlInput.setHintTextColor(Color.parseColor("#94A3B8"));
        urlInput.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#1E293B"));
        inputBg.setCornerRadius(dpToPx(8));
        inputBg.setStroke(dpToPx(1), Color.parseColor("#334155"));
        urlInput.setBackground(inputBg);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dpToPx(38), 1.0f);
        inputParams.setMargins(0, 0, dpToPx(6), 0);
        urlInput.setLayoutParams(inputParams);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            loadUrlWithFallback(urlInput.getText().toString());
            scheduleAutoHideNavBar();
            return true;
        });
        row1.addView(urlInput);

        // Botão IR
        Button btnGo = createNavButton("Ir", Color.parseColor("#2563EB"), Color.WHITE);
        btnGo.setOnClickListener(v -> {
            loadUrlWithFallback(urlInput.getText().toString());
            scheduleAutoHideNavBar();
        });
        row1.addView(btnGo);

        // Botão Recarregar
        Button btnReload = createNavButton("↻", Color.parseColor("#334155"), Color.WHITE);
        btnReload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
            scheduleAutoHideNavBar();
        });
        row1.addView(btnReload);

        // Botão Fechar Barra (Tela Cheia)
        Button btnClose = createNavButton("✕", Color.parseColor("#475569"), Color.WHITE);
        btnClose.setOnClickListener(v -> {
            navBar.setVisibility(View.GONE);
            hideSystemUI();
        });
        row1.addView(btnClose);

        navBar.addView(row1);

        // Linha 2: Atalhos Rápidos (Nuvem, Local PC, Offline)
        HorizontalScrollView scrollRow = new HorizontalScrollView(this);
        scrollRow.setHorizontalScrollBarEnabled(false);
        scrollRow.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dpToPx(4), 0, 0);

        Button btnCloud = createSmallPresetButton("🌐 Nuvem (totemarena.vercel.app)", v -> {
            loadUrlWithFallback(DEFAULT_CLOUD_URL);
            scheduleAutoHideNavBar();
        });
        row2.addView(btnCloud);

        Button btnLocal = createSmallPresetButton("💻 Local PC (localhost:3000)", v -> {
            loadUrlWithFallback("http://192.168.2.25:3000/screen.html");
            scheduleAutoHideNavBar();
        });
        row2.addView(btnLocal);

        Button btnOffline = createSmallPresetButton("💾 Offline (Vídeo Embutido)", v -> {
            loadUrlWithFallback(DEFAULT_OFFLINE_URL + "?offline=1");
            scheduleAutoHideNavBar();
        });
        row2.addView(btnOffline);

        Button btnDiag = createSmallPresetButton("🩺 Diagnóstico do Totem", v -> {
            String net = (isOnline() ? "🟢 Internet Conectada" : "🔴 Sem Internet (Offline)");
            String info = "STATUS DO TOTEM:\n" +
                          "• Rede: " + net + "\n" +
                          "• Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")\n" +
                          "• URL: " + (currentTargetUrl != null ? currentTargetUrl : "Nenhuma");
            Toast.makeText(this, info, Toast.LENGTH_LONG).show();
            scheduleAutoHideNavBar();
        });
        row2.addView(btnDiag);

        scrollRow.addView(row2);
        navBar.addView(scrollRow);

        rootLayout.addView(navBar);
    }

    private Button createNavButton(String text, int bgColor, int textColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(textColor);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dpToPx(10), 0, dpToPx(10), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpToPx(8));
        btn.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dpToPx(38)
        );
        params.setMargins(0, 0, dpToPx(6), 0);
        btn.setLayoutParams(params);
        return btn;
    }

    private Button createSmallPresetButton(String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(Color.parseColor("#CBD5E1"));
        btn.setPadding(dpToPx(8), 0, dpToPx(8), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(dpToPx(6));
        bg.setStroke(dpToPx(1), Color.parseColor("#334155"));
        btn.setBackground(bg);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dpToPx(28)
        );
        params.setMargins(0, 0, dpToPx(6), 0);
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void buildTriggerButton(FrameLayout rootLayout) {
        navBarTrigger = new Button(this);
        navBarTrigger.setText("⚙");
        navBarTrigger.setTextSize(14);
        navBarTrigger.setTextColor(Color.WHITE);
        navBarTrigger.setAlpha(0.20f); // Quase invisível para não atrapalhar o totem

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F172A"));
        bg.setCornerRadius(dpToPx(18));
        bg.setStroke(dpToPx(1), Color.parseColor("#475569"));
        navBarTrigger.setBackground(bg);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dpToPx(36), dpToPx(36));
        params.gravity = Gravity.TOP | Gravity.END;
        params.setMargins(0, dpToPx(6), dpToPx(6), 0);
        navBarTrigger.setLayoutParams(params);

        navBarTrigger.setOnClickListener(v -> {
            if (navBar.getVisibility() == View.VISIBLE) {
                navBar.setVisibility(View.GONE);
                hideSystemUI();
            } else {
                navBar.setVisibility(View.VISIBLE);
                scheduleAutoHideNavBar();
            }
        });

        rootLayout.addView(navBarTrigger);
    }

    private void scheduleAutoHideNavBar() {
        if (autoHideRunnable != null) {
            handler.removeCallbacks(autoHideRunnable);
        }
        autoHideRunnable = () -> {
            if (navBar != null) {
                navBar.setVisibility(View.GONE);
            }
            hideSystemUI();
        };
        handler.postDelayed(autoHideRunnable, 7000);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (webView != null) webView.clearCache(false);
        System.gc();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView != null && level >= TRIM_MEMORY_MODERATE) {
            webView.clearCache(false);
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            System.gc();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (webView != null) webView.onResume();
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            try { mediaPlayer.start(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(memoryCleanerRunnable);
        if (autoHideRunnable != null) handler.removeCallbacks(autoHideRunnable);
        releaseMediaPlayer();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // ========================================================
    // REPRODUTOR DE VÍDEO NATIVO POR HARDWARE (ALLWINNER A83T)
    // ========================================================
    public String getPermanentDeviceId() {
        if (permanentDeviceId != null && !permanentDeviceId.isEmpty()) {
            return permanentDeviceId;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String id = prefs.getString("fixed_totem_id", null);
        if (id != null && !id.trim().isEmpty()) {
            permanentDeviceId = id.trim();
            return permanentDeviceId;
        }

        String androidId = null;
        try {
            androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {}

        if (androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)) {
            id = "totem-" + androidId.substring(0, Math.min(8, androidId.length())).toLowerCase();
        } else {
            id = "totem-" + UUID.randomUUID().toString().substring(0, 8);
        }

        prefs.edit().putString("fixed_totem_id", id).apply();
        permanentDeviceId = id;
        return permanentDeviceId;
    }

    private synchronized void startOrUpdateNativePlayer() {
        if (videoSurface == null || !videoSurface.isValid()) return;
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                mediaPlayer.reset();
            }
            mediaPlayer.setSurface(videoSurface);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setLooping(true);

            String targetSource = currentVideoPath;

            // Se não houver vídeo específico definido, busca o arquivo local ou de amostra offline
            if (targetSource == null || targetSource.trim().isEmpty()) {
                File activeFile = localServer != null ? localServer.getActiveVideoFile() : null;
                if (activeFile == null || !activeFile.exists()) {
                    activeFile = new File(getFilesDir(), "offline_sample.mp4");
                }
                if (!activeFile.exists() || activeFile.length() < 1000) {
                    if (localServer != null) {
                        localServer.prepareLocalFiles();
                        activeFile = localServer.getActiveVideoFile();
                    }
                }
                if (activeFile != null && activeFile.exists()) {
                    targetSource = activeFile.getAbsolutePath();
                }
            }

            if (targetSource != null && !targetSource.trim().isEmpty()) {
                final String finalSource = targetSource;
                android.util.Log.i("TotemNativePlayer", "▶ Configurando fonte de mídia: " + finalSource);

                if (finalSource.startsWith("http://") || finalSource.startsWith("https://")) {
                    mediaPlayer.setDataSource(this, Uri.parse(finalSource));
                } else {
                    mediaPlayer.setDataSource(finalSource);
                }

                mediaPlayer.setOnPreparedListener(mp -> {
                    adjustVideoAspectRatio();
                    updatePlayerAudio();
                    mp.start();
                    android.util.Log.i("TotemNativePlayer", "▶ Vídeo nativo iniciado com sucesso: " + finalSource);
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    android.util.Log.e("TotemNativePlayer", "Erro no MediaPlayer (" + what + ", " + extra + ") para: " + finalSource);
                    // Se falhar URL remota, recua para arquivo offline local
                    if (finalSource.startsWith("http")) {
                        currentVideoPath = null;
                        handler.postDelayed(this::startOrUpdateNativePlayer, 1500);
                    } else {
                        handler.postDelayed(this::startOrUpdateNativePlayer, 3000);
                    }
                    return true;
                });
                mediaPlayer.prepareAsync();
            }
        } catch (Exception e) {
            android.util.Log.e("TotemNativePlayer", "Falha ao iniciar player nativo", e);
        }
    }

    private void adjustVideoAspectRatio() {
        if (mediaPlayer == null || videoTextureView == null) return;
        try {
            int videoWidth = mediaPlayer.getVideoWidth();
            int videoHeight = mediaPlayer.getVideoHeight();
            int viewWidth = videoTextureView.getWidth();
            int viewHeight = videoTextureView.getHeight();

            if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return;

            float sx = (float) viewWidth / (float) videoWidth;
            float sy = (float) viewHeight / (float) videoHeight;
            float maxScale = Math.max(sx, sy) * 1.04f; // 4% zoom para eliminar qualquer borda verde

            Matrix matrix = new Matrix();
            matrix.setScale(maxScale / sx, maxScale / sy, viewWidth / 2f, viewHeight / 2f);
            videoTextureView.setTransform(matrix);
        } catch (Exception ignored) {}
    }

    private void updatePlayerAudio() {
        if (mediaPlayer == null) return;
        try {
            if (isMuted) {
                mediaPlayer.setVolume(0f, 0f);
            } else {
                mediaPlayer.setVolume(currentVolume, currentVolume);
            }
        } catch (Exception ignored) {}
    }

    public void setNativeVolume(int volume, boolean muted) {
        currentVolume = Math.max(0, Math.min(100, volume)) / 100f;
        isMuted = muted;
        runOnUiThread(() -> {
            updatePlayerAudio();
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int target = muted ? 0 : Math.round((volume / 100f) * max);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                }
            } catch (Exception ignored) {}
        });
    }

    public void setNativeBrightness(final float percent) {
        runOnUiThread(() -> {
            try {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                float b = Math.max(0.05f, Math.min(1.0f, percent / 100.0f));
                lp.screenBrightness = b;
                getWindow().setAttributes(lp);
            } catch (Exception e) {
                android.util.Log.w("TotemBrightness", "Erro ao ajustar brilho de tela", e);
            }
        });
    }

    public void playNativeVideo(String videoUrl, String videoTitle) {
        if (videoUrl == null || videoUrl.trim().isEmpty()) return;
        final String cleanUrl = videoUrl.trim();
        android.util.Log.i("TotemNativePlayer", "▶ Comando playNativeVideo recebido: " + cleanUrl + " (" + videoTitle + ")");

        currentVideoPath = cleanUrl;
        runOnUiThread(() -> {
            if (videoTitle != null && !videoTitle.isEmpty()) {
                Toast.makeText(MainActivity.this, "🎬 Trocando vídeo: " + videoTitle, Toast.LENGTH_SHORT).show();
            }
            startOrUpdateNativePlayer();
        });

        // Se for URL remota na nuvem, inicia download em segundo plano para persistir como cache offline
        if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            startBackgroundDownload(cleanUrl);
        }
    }

    private void startBackgroundDownload(final String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                if (conn.getResponseCode() == 200) {
                    File tempFile = new File(getFilesDir(), "temp_download.mp4");
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, r);
                        }
                        fos.flush();
                    }
                    if (tempFile.length() > 50000) {
                        File targetFile = new File(getFilesDir(), "cached_video.mp4");
                        if (targetFile.exists()) targetFile.delete();
                        tempFile.renameTo(targetFile);
                        android.util.Log.i("TotemNativePlayer", "✔ Vídeo baixado e armazenado para cache offline.");
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TotemNativePlayer", "Aviso no download em background para cache: " + e.getMessage());
            }
        }).start();
    }

    private synchronized void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Bloqueia botão voltar para manter o Totem sempre em execução
    }

    // Ponte nativa JavaScript para a interface Kiosk controlar hardware e reprodução
    public class TotemNativeBridge {
        @android.webkit.JavascriptInterface
        public String getPermanentDeviceId() {
            return MainActivity.this.getPermanentDeviceId();
        }

        @android.webkit.JavascriptInterface
        public boolean isOnline() {
            return MainActivity.this.isOnline();
        }

        @android.webkit.JavascriptInterface
        public String getDeviceInfo() {
            return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")";
        }

        @android.webkit.JavascriptInterface
        public String getOfflineAssetPath() {
            return "offline_sample.mp4";
        }

        @android.webkit.JavascriptInterface
        public String getLocalServerUrl() {
            return localServer != null ? localServer.getBaseUrl() : "http://127.0.0.1:8888";
        }

        @android.webkit.JavascriptInterface
        public String getOfflineVideoUrl() {
            return (localServer != null ? localServer.getBaseUrl() : "http://127.0.0.1:8888") + "/offline_sample.mp4";
        }

        @android.webkit.JavascriptInterface
        public void playVideo(String url, String title) {
            MainActivity.this.playNativeVideo(url, title);
        }

        @android.webkit.JavascriptInterface
        public void cacheVideo(String videoUrl) {
            MainActivity.this.playNativeVideo(videoUrl, null);
        }

        @android.webkit.JavascriptInterface
        public void setVolume(int volume, boolean muted) {
            MainActivity.this.setNativeVolume(volume, muted);
        }

        @android.webkit.JavascriptInterface
        public void setBrightness(final float percent) {
            MainActivity.this.setNativeBrightness(percent);
        }
    }
}
