package com.mycima;

import android.util.Log;
import java.util.List;
import kotlin.Metadata;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.jvm.functions.Function1;
import kotlin.text.CharsKt;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.RegexOption;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/* JADX INFO: compiled from: ExternalEarnVidsExtractor.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/mycima_source/unzipped/classes.dex */
@Metadata(d1 = {"\u0000\u0014\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0007\bÆ\u0002\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003J \u0010\u0006\u001a\u0004\u0018\u00010\u00052\u0006\u0010\u0007\u001a\u00020\u00052\u0006\u0010\b\u001a\u00020\u0005H\u0086@¢\u0006\u0002\u0010\tJ\u001a\u0010\n\u001a\u0004\u0018\u00010\u00052\u0006\u0010\u000b\u001a\u00020\u00052\u0006\u0010\u0007\u001a\u00020\u0005H\u0002R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082T¢\u0006\u0002\n\u0000¨\u0006\f"}, d2 = {"Lcom/mycima/ExternalEarnVidsExtractor;", "", "<init>", "()V", "TAG", "", "extract", "pageUrl", "mainReferer", "(Ljava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "unpackPackerSimple", "js", "MyCimaProvider_debug"}, k = 1, mv = {2, 3, 0}, xi = 48)
public final class ExternalEarnVidsExtractor {

    @NotNull
    public static final ExternalEarnVidsExtractor INSTANCE = new ExternalEarnVidsExtractor();

    @NotNull
    private static final String TAG = "EarnVidsExtractor";

    /* JADX INFO: renamed from: com.mycima.ExternalEarnVidsExtractor$extract$1, reason: invalid class name */
    /* JADX INFO: compiled from: ExternalEarnVidsExtractor.kt */
    @Metadata(k = 3, mv = {2, 3, 0}, xi = 48)
    @DebugMetadata(c = "com.mycima.ExternalEarnVidsExtractor", f = "ExternalEarnVidsExtractor.kt", i = {0, 0, 0}, l = {33}, m = "extract", n = {"pageUrl", "mainReferer", "headers"}, nl = {34}, s = {"L$0", "L$1", "L$2"}, v = 2)
    static final class AnonymousClass1 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        int label;
        /* synthetic */ Object result;

        AnonymousClass1(Continuation<? super AnonymousClass1> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return ExternalEarnVidsExtractor.this.extract(null, null, (Continuation) this);
        }
    }

    private ExternalEarnVidsExtractor() {
    }

    /* JADX WARN: Removed duplicated region for block: B:36:0x011b  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0157 A[Catch: Exception -> 0x019c, TRY_LEAVE, TryCatch #3 {Exception -> 0x019c, blocks: (B:41:0x0151, B:43:0x0157), top: B:171:0x0151 }] */
    /* JADX WARN: Removed duplicated region for block: B:52:0x0199  */
    /* JADX WARN: Removed duplicated region for block: B:60:0x01cd A[Catch: Exception -> 0x0454, TRY_LEAVE, TryCatch #9 {Exception -> 0x0454, blocks: (B:33:0x0111, B:38:0x011d, B:57:0x01a5, B:58:0x01bf, B:60:0x01cd), top: B:183:0x0111 }] */
    /* JADX WARN: Removed duplicated region for block: B:63:0x01d5  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x001e  */
    /* JADX WARN: Type inference failed for: r10v18 */
    /* JADX WARN: Type inference failed for: r10v20 */
    /* JADX WARN: Type inference failed for: r10v3, types: [boolean, int] */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object extract(@org.jetbrains.annotations.NotNull java.lang.String r35, @org.jetbrains.annotations.NotNull java.lang.String r36, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super java.lang.String> r37) {
        /*
            Method dump skipped, instruction units count: 1172
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.mycima.ExternalEarnVidsExtractor.extract(java.lang.String, java.lang.String, kotlin.coroutines.Continuation):java.lang.Object");
    }

    private final String unpackPackerSimple(String js, String pageUrl) {
        try {
            Regex regex = new Regex("eval\\(function\\(p,a,c,k,e,d\\)\\{.*?\\}\\(\\s*['\"](.+?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*['\"](.+?)['\"]", RegexOption.DOT_MATCHES_ALL);
            MatchResult match = Regex.find$default(regex, js, 0, 2, (Object) null);
            if (match == null) {
                return null;
            }
            MatchResult.Destructured destructured = match.getDestructured();
            String payloadRaw = (String) destructured.getMatch().getGroupValues().get(1);
            String radixStr = (String) destructured.getMatch().getGroupValues().get(2);
            String sympipe = (String) destructured.getMatch().getGroupValues().get(3);
            Integer intOrNull = StringsKt.toIntOrNull(radixStr);
            final int radix = intOrNull != null ? intOrNull.intValue() : 36;
            final List symtab = StringsKt.split$default(sympipe, new String[]{"|"}, false, 0, 6, (Object) null);
            String payload = StringsKt.replace$default(StringsKt.replace$default(StringsKt.replace$default(StringsKt.replace$default(StringsKt.replace$default(payloadRaw, "location.href", '\'' + pageUrl + '\'', false, 4, (Object) null), "location", '\'' + pageUrl + '\'', false, 4, (Object) null), "document.cookie", "''", false, 4, (Object) null), "window.location", '\'' + pageUrl + '\'', false, 4, (Object) null), "window", "this", false, 4, (Object) null);
            Regex tokenRe = new Regex("\\b[0-9a-zA-Z]+\\b");
            String replaced = tokenRe.replace(payload, new Function1() { // from class: com.mycima.ExternalEarnVidsExtractor$$ExternalSyntheticLambda0
                public final Object invoke(Object obj) {
                    return ExternalEarnVidsExtractor.unpackPackerSimple$lambda$0(radix, symtab, (MatchResult) obj);
                }
            });
            return replaced;
        } catch (Exception e) {
            Log.w(TAG, "unpackPackerSimple failed: " + e.getMessage());
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final CharSequence unpackPackerSimple$lambda$0(int $radix, List $symtab, MatchResult mo) {
        String tok = mo.getValue();
        try {
            int idx = Integer.parseInt(tok, CharsKt.checkRadix($radix));
            boolean z = false;
            if (idx >= 0 && idx < $symtab.size()) {
                z = true;
            }
            return z ? (String) $symtab.get(idx) : tok;
        } catch (Exception e) {
            return tok;
        }
    }
}
