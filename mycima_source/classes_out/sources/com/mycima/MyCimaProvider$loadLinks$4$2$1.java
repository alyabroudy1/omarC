package com.mycima;

import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import kotlin.Metadata;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.CoroutineScope;

/* JADX INFO: compiled from: MyCimaProvider.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/cloudstream-standard-v2/omarC/mycima_source/unzipped/classes.dex */
@Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\n"}, d2 = {"<anonymous>", "", "Lkotlinx/coroutines/CoroutineScope;"}, k = 3, mv = {2, 3, 0}, xi = 48)
@DebugMetadata(c = "com.mycima.MyCimaProvider$loadLinks$4$2$1", f = "MyCimaProvider.kt", i = {1, 2, 3, 3, 3}, l = {655, 672, 677, 679}, m = "invokeSuspend", n = {"finalUrl", "finalUrl", "finalUrl", "customLink", "$i$a$-let-MyCimaProvider$loadLinks$4$2$1$1"}, nl = {656, 675, 678, 678}, s = {"L$0", "L$0", "L$0", "L$1", "I$0"}, v = 2)
final class MyCimaProvider$loadLinks$4$2$1 extends SuspendLambda implements Function2<CoroutineScope, Continuation<? super Unit>, Object> {
    final /* synthetic */ Function1<ExtractorLink, Unit> $callback;
    final /* synthetic */ String $data;
    final /* synthetic */ String $link;
    final /* synthetic */ String $serverName;
    final /* synthetic */ Function1<SubtitleFile, Unit> $subtitleCallback;
    int I$0;
    Object L$0;
    Object L$1;
    Object L$2;
    int label;
    final /* synthetic */ MyCimaProvider this$0;

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    MyCimaProvider$loadLinks$4$2$1(String str, MyCimaProvider myCimaProvider, String str2, Function1<? super SubtitleFile, Unit> function1, Function1<? super ExtractorLink, Unit> function12, String str3, Continuation<? super MyCimaProvider$loadLinks$4$2$1> continuation) {
        super(2, continuation);
        this.$link = str;
        this.this$0 = myCimaProvider;
        this.$data = str2;
        this.$subtitleCallback = function1;
        this.$callback = function12;
        this.$serverName = str3;
    }

    public final Continuation<Unit> create(Object obj, Continuation<?> continuation) {
        return new MyCimaProvider$loadLinks$4$2$1(this.$link, this.this$0, this.$data, this.$subtitleCallback, this.$callback, this.$serverName, continuation);
    }

    public final Object invoke(CoroutineScope coroutineScope, Continuation<? super Unit> continuation) {
        return create(coroutineScope, continuation).invokeSuspend(Unit.INSTANCE);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0082 A[Catch: Exception -> 0x004d, TRY_LEAVE, TryCatch #1 {Exception -> 0x004d, blocks: (B:15:0x0047, B:24:0x0078, B:26:0x0082, B:21:0x0061), top: B:76:0x000c }] */
    /* JADX WARN: Removed duplicated region for block: B:28:0x0089  */
    /* JADX WARN: Removed duplicated region for block: B:41:0x00b6  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x00bc  */
    /* JADX WARN: Removed duplicated region for block: B:45:0x00bf  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x0107 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:59:0x0108  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x010e A[Catch: Exception -> 0x015e, TRY_LEAVE, TryCatch #0 {Exception -> 0x015e, blocks: (B:60:0x0109, B:62:0x010e), top: B:74:0x0109 }] */
    /* JADX WARN: Removed duplicated region for block: B:68:0x015c  */
    /* JADX WARN: Type inference failed for: r3v0 */
    /* JADX WARN: Type inference failed for: r3v1 */
    /* JADX WARN: Type inference failed for: r3v13 */
    /* JADX WARN: Type inference failed for: r3v14 */
    /* JADX WARN: Type inference failed for: r3v20 */
    /* JADX WARN: Type inference failed for: r3v4 */
    /* JADX WARN: Type inference failed for: r3v5 */
    /* JADX WARN: Type inference failed for: r6v5 */
    /* JADX WARN: Type inference failed for: r6v8 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object invokeSuspend(java.lang.Object r18) {
        /*
            Method dump skipped, instruction units count: 370
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.mycima.MyCimaProvider$loadLinks$4$2$1.invokeSuspend(java.lang.Object):java.lang.Object");
    }
}
