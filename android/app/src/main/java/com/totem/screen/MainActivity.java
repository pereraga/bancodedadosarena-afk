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
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
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

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View mCustomView;

    private LinearLayout navBar;
    private EditText urlInput;
    private Button navBarTrigger;

    private static final String DEFAULT_CLOUD_URL = "https://totemarena.vercel.app/screen.html";
    private static final String DEFAULT_OFFLINE_URL = "file:///android_asset/screen.html";
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

        // 1. Manter tela acesa 100% do tempo (Kiosk 24/7)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        // 3. Recuperar URL salva das preferências (padrão: Site oficial nuvem)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTargetUrl = prefs.getString(KEY_SAVED_URL, DEFAULT_CLOUD_URL);

        // 4. Construção da Interface do Navegador Totem
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // WebView Principal
        webView = new WebView(this);
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

        // 5. Configuração Otimizada do WebView para Reprodução de Vídeos e Áudio
        setupWebSettings();

        // 6. Iniciar carregamento
        loadUrlWithFallback(currentTargetUrl);

        // 7. Agendar ocultação automática da barra após 7 segundos da inicialização
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

        // Aceleração por Hardware para reprodução fluida de vídeo
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

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

    private void handleLoadError(String failingUrl) {
        // Se falhou ao carregar a URL da nuvem por falta de internet, carrega imediatamente o pacote offline
        if (failingUrl != null && !failingUrl.startsWith("file://")) {
            Toast.makeText(this, "Sem internet. Carregando modo offline embutido...", Toast.LENGTH_SHORT).show();
            if (webView != null) {
                webView.loadUrl(DEFAULT_OFFLINE_URL);
            }
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (isRetrying) return;
        isRetrying = true;
        handler.postDelayed(() -> {
            if (isOnline()) {
                if (webView != null && currentTargetUrl != null && !currentTargetUrl.startsWith("file://")) {
                    webView.loadUrl(currentTargetUrl);
                }
            } else {
                isRetrying = false;
                scheduleRetry();
            }
        }, 8000);
    }

    private void loadUrlWithFallback(String url) {
        if (url == null || url.trim().isEmpty()) {
            url = DEFAULT_CLOUD_URL;
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            url = "https://" + url;
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

        Button btnOffline = createSmallPresetButton("💾 Offline (Assets Internos)", v -> {
            loadUrlWithFallback(DEFAULT_OFFLINE_URL);
            scheduleAutoHideNavBar();
        });
        row2.addView(btnOffline);

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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(memoryCleanerRunnable);
        if (autoHideRunnable != null) handler.removeCallbacks(autoHideRunnable);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Bloqueia botão voltar para manter o Totem sempre em execução
    }
}
