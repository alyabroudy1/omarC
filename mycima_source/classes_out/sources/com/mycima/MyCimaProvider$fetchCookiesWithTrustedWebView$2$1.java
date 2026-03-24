package com.mycima;

import android.R;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import kotlin.Metadata;
import kotlin.Pair;
import kotlin.Result;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.MapsKt;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.Ref;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CancellableContinuation;

/* JADX INFO: compiled from: MyCimaProvider.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/mycima_source/unzipped/classes.dex */
@Metadata(k = 3, mv = {2, 3, 0}, xi = 48)
final class MyCimaProvider$fetchCookiesWithTrustedWebView$2$1 implements Runnable {
    final /* synthetic */ CancellableContinuation<Boolean> $cont;
    final /* synthetic */ long $timeoutMs;
    final /* synthetic */ String $url;
    final /* synthetic */ MyCimaProvider this$0;

    /* JADX WARN: Multi-variable type inference failed */
    MyCimaProvider$fetchCookiesWithTrustedWebView$2$1(MyCimaProvider myCimaProvider, CancellableContinuation<? super Boolean> cancellableContinuation, String str, long j) {
        this.this$0 = myCimaProvider;
        this.$cont = cancellableContinuation;
        this.$url = str;
        this.$timeoutMs = j;
    }

    @Override // java.lang.Runnable
    public final void run() {
        Context context = this.this$0.context;
        Activity activity = context instanceof Activity ? (Activity) context : null;
        if (activity == null || activity.isFinishing()) {
            if (this.$cont.isActive()) {
                Continuation continuation = this.$cont;
                Result.Companion companion = Result.Companion;
                continuation.resumeWith(Result.constructor-impl(false));
                return;
            }
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(1);
        dialog.setCancelable(false);
        Window $this$run_u24lambda_u240 = dialog.getWindow();
        if ($this$run_u24lambda_u240 != null) {
            $this$run_u24lambda_u240.setBackgroundDrawableResource(R.color.transparent);
            $this$run_u24lambda_u240.clearFlags(2);
            $this$run_u24lambda_u240.addFlags(24);
            WindowManager.LayoutParams params = $this$run_u24lambda_u240.getAttributes();
            params.width = 1;
            params.height = 1;
            params.gravity = 8388659;
            params.x = -1000;
            params.y = -1000;
            $this$run_u24lambda_u240.setAttributes(params);
        }
        final WebView webView = new WebView(activity);
        webView.setVisibility(4);
        dialog.setContentView(webView, new ViewGroup.LayoutParams(1, 1));
        try {
            WebSettings $this$run_u24lambda_u241 = webView.getSettings();
            $this$run_u24lambda_u241.setJavaScriptEnabled(true);
            $this$run_u24lambda_u241.setDomStorageEnabled(true);
            $this$run_u24lambda_u241.setDatabaseEnabled(true);
            $this$run_u24lambda_u241.setUseWideViewPort(true);
            $this$run_u24lambda_u241.setLoadWithOverviewMode(true);
            $this$run_u24lambda_u241.setUserAgentString(MyCimaProvider.lastValidUserAgent);
            $this$run_u24lambda_u241.setCacheMode(-1);
        } catch (Exception e) {
        }
        CookieManager cookieManager = CookieManager.getInstance();
        try {
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        } catch (Exception e2) {
        }
        Ref.BooleanRef finished = new Ref.BooleanRef();
        final long startTime = System.currentTimeMillis();
        final Handler handler = new Handler(Looper.getMainLooper());
        final Ref.BooleanRef finished2 = finished;
        final CookieManager cookieManager2 = cookieManager;
        final String str = this.$url;
        final long j = this.$timeoutMs;
        final CancellableContinuation<Boolean> cancellableContinuation = this.$cont;
        handler.postDelayed(new Runnable() { // from class: com.mycima.MyCimaProvider$fetchCookiesWithTrustedWebView$2$1$checker$1
            @Override // java.lang.Runnable
            public void run() {
                String cookie;
                if (finished2.element) {
                    return;
                }
                try {
                    cookie = cookieManager2.getCookie(str);
                } catch (Exception e3) {
                    cookie = "";
                }
                String currentCookies = cookie != null ? cookie : "";
                if (!StringsKt.contains$default(currentCookies, "cf_clearance", false, 2, (Object) null)) {
                    if (System.currentTimeMillis() - startTime > j) {
                        Log.d(MyCimaProvider.TAG, "Hidden Cloudflare Bypass Timeout!");
                        MyCimaProvider$fetchCookiesWithTrustedWebView$2$1.run$finish(finished2, cookieManager2, dialog, webView, cancellableContinuation, false);
                        return;
                    } else {
                        handler.postDelayed(this, 1000L);
                        return;
                    }
                }
                Log.d(MyCimaProvider.TAG, "Hidden Cloudflare Bypass Successful!");
                Handler handler2 = handler;
                final Ref.BooleanRef booleanRef = finished2;
                final CookieManager cookieManager3 = cookieManager2;
                final Dialog dialog2 = dialog;
                final WebView webView2 = webView;
                final CancellableContinuation<Boolean> cancellableContinuation2 = cancellableContinuation;
                handler2.postDelayed(new Runnable() { // from class: com.mycima.MyCimaProvider$fetchCookiesWithTrustedWebView$2$1$checker$1$run$1
                    @Override // java.lang.Runnable
                    public final void run() {
                        MyCimaProvider$fetchCookiesWithTrustedWebView$2$1.run$finish(booleanRef, cookieManager3, dialog2, webView2, cancellableContinuation2, true);
                    }
                }, 1000L);
            }
        }, 1000L);
        webView.setWebViewClient(new WebViewClient() { // from class: com.mycima.MyCimaProvider$fetchCookiesWithTrustedWebView$2$1.5
        });
        try {
            dialog.show();
            webView.loadUrl(this.$url, MapsKt.mutableMapOf(new Pair[]{TuplesKt.to("Referer", this.this$0.getMainUrl())}));
        } catch (Exception e3) {
            run$finish(finished2, cookieManager2, dialog, webView, this.$cont, false);
            cookieManager2 = cookieManager2;
            finished2 = finished2;
        }
        CancellableContinuation<Boolean> cancellableContinuation2 = this.$cont;
        final CancellableContinuation<Boolean> cancellableContinuation3 = this.$cont;
        final CookieManager cookieManager3 = cookieManager2;
        final Ref.BooleanRef finished3 = finished2;
        cancellableContinuation2.invokeOnCancellation(new Function1<Throwable, Unit>() { // from class: com.mycima.MyCimaProvider$fetchCookiesWithTrustedWebView$2$1.6
            public /* bridge */ /* synthetic */ Object invoke(Object p1) {
                invoke((Throwable) p1);
                return Unit.INSTANCE;
            }

            public final void invoke(Throwable it) {
                Handler handler2 = handler;
                final Ref.BooleanRef booleanRef = finished3;
                final CookieManager cookieManager4 = cookieManager3;
                final Dialog dialog2 = dialog;
                final WebView webView2 = webView;
                final CancellableContinuation<Boolean> cancellableContinuation4 = cancellableContinuation3;
                handler2.post(new Runnable() { // from class: com.mycima.MyCimaProvider.fetchCookiesWithTrustedWebView.2.1.6.1
                    @Override // java.lang.Runnable
                    public final void run() {
                        MyCimaProvider$fetchCookiesWithTrustedWebView$2$1.run$finish(booleanRef, cookieManager4, dialog2, webView2, cancellableContinuation4, false);
                    }
                });
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void run$finish(Ref.BooleanRef finished, CookieManager cookieManager, Dialog dialog, WebView webView, CancellableContinuation<? super Boolean> cancellableContinuation, boolean success) {
        if (finished.element) {
            return;
        }
        finished.element = true;
        try {
            cookieManager.flush();
        } catch (Exception e) {
        }
        try {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception e2) {
        }
        try {
            webView.stopLoading();
        } catch (Exception e3) {
        }
        try {
            webView.destroy();
        } catch (Exception e4) {
        }
        if (cancellableContinuation.isActive()) {
            Result.Companion companion = Result.Companion;
            ((Continuation) cancellableContinuation).resumeWith(Result.constructor-impl(Boolean.valueOf(success)));
        }
    }
}
