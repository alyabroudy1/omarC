@file:Suppress("unused")

package org.chromium.support_lib_boundary

import android.webkit.WebSettings
import java.lang.reflect.InvocationHandler

/**
 * Our copies of the three WebView support-library boundary interfaces we need.
 *
 * ## Why these live in `org.chromium.support_lib_boundary` and not in our own package
 *
 * This package name is load-bearing, not cargo-culted. Chromium's glue dispatches a boundary call
 * through `BoundaryInterfaceReflectionUtil.dupeMethod`, which does:
 *
 * ```java
 * Class<?> declaringClass = Class.forName(method.getDeclaringClass().getName(), true, classLoader);
 * return declaringClass.getDeclaredMethod(method.getName(), parameterClasses);
 * ```
 *
 * — i.e. it takes the **declaring class of the method on the proxy we hand it** and re-loads that class
 * *by name* from the WebView APK's own class loader. Declaring these in
 * `com.cloudstream.shared.webview` therefore failed exactly as it had to (2026-08-07, on-device):
 *
 * ```
 * Reflection failed for method public abstract java.lang.String[]
 *   com.cloudstream.shared.webview.RequestedWithHeaderControl$FactoryBoundary.getSupportedFeatures()
 * ```
 *
 * The WebView APK has no such class, so `Class.forName` threw and every call died before reaching the
 * delegate. Named as below, the same lookup resolves to the WebView's own copy of the interface, finds
 * the method, and invokes it. This is precisely why androidx.webkit ships its boundary interfaces under
 * `org.chromium.*` rather than under `androidx.*`.
 *
 * Method names and parameter types must match the WebView APK exactly; return types are not part of the
 * lookup but are declared correctly anyway. Nothing here is instantiated — these exist only to be
 * `Proxy`-backed by an `InvocationHandler` the glue supplies. Ours are loaded by the plugin's class
 * loader and the WebView's by its own, so the duplicate names never collide.
 */

/** `createWebViewProviderFactory()` returns an `InvocationHandler` for this. */
interface WebViewProviderFactoryBoundaryInterface {
    fun getSupportedFeatures(): Array<String>
    fun getWebkitToCompatConverter(): InvocationHandler
}

/** Converts framework objects into their boundary handlers. */
interface WebkitToCompatConverterBoundaryInterface {
    fun convertSettings(webSettings: WebSettings): InvocationHandler
}

/**
 * Only the `X-Requested-With` members are declared.
 *
 * The allow-list pair is the current API (WebView ~118+); the mode pair is its deprecated predecessor.
 * Declaring a method a given WebView lacks costs nothing — the lookup only happens when it is called,
 * and [com.cloudstream.shared.webview.RequestedWithHeaderControl] checks `getSupportedFeatures()` first
 * and catches per call.
 */
interface WebSettingsBoundaryInterface {
    fun setRequestedWithHeaderMode(mode: Int)
    fun getRequestedWithHeaderMode(): Int
    fun setRequestedWithHeaderOriginAllowList(allowList: Set<String>)
    fun getRequestedWithHeaderOriginAllowList(): Set<String>
}
