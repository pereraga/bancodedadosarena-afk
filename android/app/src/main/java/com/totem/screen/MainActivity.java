package com.totem.screen;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View mCustomView;

    private static final String TARGET_URL = "file:///android_asset/screen.html";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRetrying = false;

    // Tarefa periódica para liberar lixo de memória RAM em tablets de 1GB
    private final Runnable memoryCleanerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (webView != null) {
                    webView.clearCache(false);
                }
                System.gc();
            } catch (Exception ignored) {}
            handler.postDelayed(this, 20 * 60 * 1000); // Executa a cada 20 minutos
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Manter a tela do tablet/totem 100% acesa 24 horas por dia (NUNCA dorme)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // 2. Modo Imersivo Kiosk (Oculta botões de navegação e barra superior do Android)
        hideSystemUI();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                hideSystemUI();
            }
        });

        // 3. Layout Principal com Suporte a Tela Cheia Nativa
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.addView(webView);

        customViewContainer = new FrameLayout(this);
        customViewContainer.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        customViewContainer.setVisibility(View.GONE);
        rootLayout.addView(customViewContainer);

        setContentView(rootLayout);

        // 4. Configuração Otimizada de WebSettings para Android 6.0.1 (API 23) e 1GB de RAM
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

        // Permite autoplay sem exigir toque físico na tela
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Otimização de Cache e Memória (1GB RAM)
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Desativa recursos desnecessários para economizar memória e ciclos de CPU A83T
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSavePassword(false);

        // Suporte a conteúdo misto e cookies em Android 5.0+ (Lollipop / Marshmallow)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        // Para o processador Allwinner A83T e GPU PowerVR SGX544 com 1GB RAM:
        // Evita LAYER_TYPE_HARDWARE na View do WebView para não alocar texturas extras que causam OOM.
        // O Android já acelera a janela por padrão (hardwareAccelerated=true).
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 5. WebViewClient com Tratamento de Erros, SSL e Reconexão
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isRetrying = false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                scheduleRetry();
            }

            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    scheduleRetry();
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // CRUCIAL para Android 6.0.1: Os certificados raiz do Let's Encrypt (usados pela Vercel)
                // expiraram no Android 6 antigo. O proceed() garante que o app carregue normalmente!
                handler.proceed();
            }
        });

        // 6. WebChromeClient com Suporte Completo a Player de Vídeo
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                // Retorna bitmap transparente para não piscar caixa cinza na inicialização do vídeo
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

        // 7. Iniciar Limpeza Periódica de Memória e Carregar URL
        handler.postDelayed(memoryCleanerRunnable, 20 * 60 * 1000);
        webView.loadUrl(TARGET_URL);
    }

    private void scheduleRetry() {
        if (isRetrying) return;
        isRetrying = true;
        handler.postDelayed(() -> {
            if (isOnline()) {
                if (webView != null) {
                    webView.loadUrl(TARGET_URL);
                }
            } else {
                isRetrying = false;
                scheduleRetry();
            }
        }, 5000);
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

    // Gerenciamento de Memória para Dispositivos de 1GB de RAM
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (webView != null) {
            webView.clearCache(false);
        }
        System.gc();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (webView != null) {
            if (level >= TRIM_MEMORY_MODERATE) {
                webView.clearCache(false);
            }
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
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(memoryCleanerRunnable);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Bloqueia o botão voltar para o app nunca fechar por toque acidental no totem
    }
}
