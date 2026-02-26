package com.egydead;

import com.lagradost.cloudstream3.Episode;
import com.lagradost.cloudstream3.LoadResponse;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.MainPageData;
import com.lagradost.cloudstream3.MovieLoadResponse;
import com.lagradost.cloudstream3.MovieSearchResponse;
import com.lagradost.cloudstream3.SearchQuality;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.SubtitleFile;
import com.lagradost.cloudstream3.TvSeriesLoadResponse;
import com.lagradost.cloudstream3.TvType;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.nicehttp.NiceResponse;
import com.lagradost.nicehttp.Requests;
import com.lagradost.nicehttp.ResponseParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import kotlin.Metadata;
import kotlin.Pair;
import kotlin.ResultKt;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.SetsKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.Boxing;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import okhttp3.Interceptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Element;

/* JADX INFO: compiled from: EgyDeadProvider.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/streamPre-4.6/omarC/egydead_decompiled/classes.dex */
@Metadata(d1 = {"\u0000x\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\t\n\u0002\u0010\"\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\u0018\u00002\u00020\u0001B\u0005¢\u0006\u0002\u0010\u0002J5\u0010\u001f\u001a\u00020 2\u0006\u0010!\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010\"\u001a\u000e\u0012\u0004\u0012\u00020$\u0012\u0004\u0012\u00020 0#H\u0082@ø\u0001\u0000¢\u0006\u0002\u0010%J!\u0010\u0010\u001a\u00020&2\u0006\u0010'\u001a\u00020(2\u0006\u0010)\u001a\u00020*H\u0096@ø\u0001\u0000¢\u0006\u0002\u0010+J5\u0010,\u001a\u00020 2\u0006\u0010!\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010\"\u001a\u000e\u0012\u0004\u0012\u00020$\u0012\u0004\u0012\u00020 0#H\u0082@ø\u0001\u0000¢\u0006\u0002\u0010%J\u0019\u0010-\u001a\u00020.2\u0006\u0010/\u001a\u00020\bH\u0096@ø\u0001\u0000¢\u0006\u0002\u00100JI\u00101\u001a\u00020\u00042\u0006\u00102\u001a\u00020\b2\u0006\u00103\u001a\u00020\u00042\u0012\u00104\u001a\u000e\u0012\u0004\u0012\u000205\u0012\u0004\u0012\u00020 0#2\u0012\u0010\"\u001a\u000e\u0012\u0004\u0012\u00020$\u0012\u0004\u0012\u00020 0#H\u0096@ø\u0001\u0000¢\u0006\u0002\u00106J\u001f\u00107\u001a\b\u0012\u0004\u0012\u0002080\u000e2\u0006\u00109\u001a\u00020\bH\u0096@ø\u0001\u0000¢\u0006\u0002\u00100J\f\u0010:\u001a\u00020\b*\u00020\bH\u0002J\u0013\u0010;\u001a\u0004\u0018\u00010(*\u00020\bH\u0002¢\u0006\u0002\u0010<J\f\u0010=\u001a\u000208*\u00020>H\u0002R\u0014\u0010\u0003\u001a\u00020\u0004X\u0096D¢\u0006\b\n\u0000\u001a\u0004\b\u0005\u0010\u0006R\u001a\u0010\u0007\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\t\u0010\n\"\u0004\b\u000b\u0010\fR\u001a\u0010\r\u001a\b\u0012\u0004\u0012\u00020\u000f0\u000eX\u0096\u0004¢\u0006\b\n\u0000\u001a\u0004\b\u0010\u0010\u0011R\u001a\u0010\u0012\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0013\u0010\n\"\u0004\b\u0014\u0010\fR\u001a\u0010\u0015\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0016\u0010\n\"\u0004\b\u0017\u0010\fR\u001a\u0010\u0018\u001a\b\u0012\u0004\u0012\u00020\u001a0\u0019X\u0096\u0004¢\u0006\b\n\u0000\u001a\u0004\b\u001b\u0010\u001cR\u0014\u0010\u001d\u001a\u00020\u0004X\u0096D¢\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u0006\u0082\u0002\u0004\n\u0002\b\u0019¨\u0006?"}, d2 = {"Lcom/egydead/EgyDead;", "Lcom/lagradost/cloudstream3/MainAPI;", "()V", "hasMainPage", "", "getHasMainPage", "()Z", "lang", "", "getLang", "()Ljava/lang/String;", "setLang", "(Ljava/lang/String;)V", "mainPage", "", "Lcom/lagradost/cloudstream3/MainPageData;", "getMainPage", "()Ljava/util/List;", "mainUrl", "getMainUrl", "setMainUrl", "name", "getName", "setName", "supportedTypes", "", "Lcom/lagradost/cloudstream3/TvType;", "getSupportedTypes", "()Ljava/util/Set;", "usesWebView", "getUsesWebView", "Trgsfjll", "", "iframeUrl", "callback", "Lkotlin/Function1;", "Lcom/lagradost/cloudstream3/utils/ExtractorLink;", "(Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "Lcom/lagradost/cloudstream3/HomePageResponse;", "page", "", "request", "Lcom/lagradost/cloudstream3/MainPageRequest;", "(ILcom/lagradost/cloudstream3/MainPageRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "handlevidhide", "load", "Lcom/lagradost/cloudstream3/LoadResponse;", "url", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "loadLinks", "data", "isCasting", "subtitleCallback", "Lcom/lagradost/cloudstream3/SubtitleFile;", "(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "search", "Lcom/lagradost/cloudstream3/SearchResponse;", "query", "cleanTitle", "getIntFromText", "(Ljava/lang/String;)Ljava/lang/Integer;", "toSearchResponse", "Lorg/jsoup/nodes/Element;", "EgyDeadProvider_debug"}, k = 1, mv = {1, 7, 1}, xi = 48)
public final class EgyDead extends MainAPI {
    private final boolean usesWebView;

    @NotNull
    private String lang = "ar";

    @NotNull
    private String mainUrl = "https://egydead.space";

    @NotNull
    private String name = "EgyDead";
    private final boolean hasMainPage = true;

    @NotNull
    private final Set<TvType> supportedTypes = SetsKt.setOf(new TvType[]{TvType.TvSeries, TvType.Movie, TvType.Anime});

    @NotNull
    private final List<MainPageData> mainPage = MainAPIKt.mainPageOf(new Pair[]{TuplesKt.to(getMainUrl() + "/category/افلام-اجنبي/?page=", "English Movies"), TuplesKt.to(getMainUrl() + "/category/افلام-اسيوية/?page=", "Asian Movies"), TuplesKt.to(getMainUrl() + "/season/?page=", "Series")});

    /* JADX INFO: renamed from: com.egydead.EgyDead$Trgsfjll$1, reason: invalid class name */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0, 0, 0, 1, 1, 1, 1}, l = {134, 141}, m = "Trgsfjll", n = {"this", "name", "callback", "this", "name", "callback", "pattern"}, s = {"L$0", "L$1", "L$2", "L$0", "L$1", "L$2", "L$3"})
    static final class AnonymousClass1 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        int label;
        /* synthetic */ Object result;

        AnonymousClass1(Continuation<? super AnonymousClass1> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.Trgsfjll(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$getMainPage$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0, 0}, l = {49}, m = "getMainPage", n = {"this", "request"}, s = {"L$0", "L$1"})
    static final class C00001 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        int label;
        /* synthetic */ Object result;

        C00001(Continuation<? super C00001> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.getMainPage(0, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$handlevidhide$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0, 0, 0, 1, 1, 1, 2}, l = {155, 157, 176}, m = "handlevidhide", n = {"this", "name", "callback", "this", "name", "callback", "callback"}, s = {"L$0", "L$1", "L$2", "L$0", "L$1", "L$2", "L$0"})
    static final class C00011 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        int label;
        /* synthetic */ Object result;

        C00011(Continuation<? super C00011> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.handlevidhide(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$load$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0, 0}, l = {65, 79, 119}, m = "load", n = {"this", "url"}, s = {"L$0", "L$1"})
    static final class C00021 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        int label;
        /* synthetic */ Object result;

        C00021(Continuation<? super C00021> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.load(null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$loadLinks$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0, 0, 0, 0}, l = {192}, m = "loadLinks", n = {"this", "data", "subtitleCallback", "callback"}, s = {"L$0", "L$1", "L$2", "L$3"})
    static final class C00031 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        int label;
        /* synthetic */ Object result;

        C00031(Continuation<? super C00031> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.loadLinks(null, false, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$search$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead", f = "EgyDeadProvider.kt", i = {0}, l = {57}, m = "search", n = {"this"}, s = {"L$0"})
    static final class C00061 extends ContinuationImpl {
        Object L$0;
        int label;
        /* synthetic */ Object result;

        C00061(Continuation<? super C00061> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return EgyDead.this.search(null, (Continuation) this);
        }
    }

    @NotNull
    public String getLang() {
        return this.lang;
    }

    public void setLang(@NotNull String str) {
        this.lang = str;
    }

    @NotNull
    public String getMainUrl() {
        return this.mainUrl;
    }

    public void setMainUrl(@NotNull String str) {
        this.mainUrl = str;
    }

    @NotNull
    public String getName() {
        return this.name;
    }

    public void setName(@NotNull String str) {
        this.name = str;
    }

    public boolean getUsesWebView() {
        return this.usesWebView;
    }

    public boolean getHasMainPage() {
        return this.hasMainPage;
    }

    @NotNull
    public Set<TvType> getSupportedTypes() {
        return this.supportedTypes;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final Integer getIntFromText(String $this$getIntFromText) {
        List groupValues;
        String str;
        MatchResult matchResultFind$default = Regex.find$default(new Regex("\\d+"), $this$getIntFromText, 0, 2, (Object) null);
        if (matchResultFind$default == null || (groupValues = matchResultFind$default.getGroupValues()) == null || (str = (String) CollectionsKt.firstOrNull(groupValues)) == null) {
            return null;
        }
        return StringsKt.toIntOrNull(str);
    }

    private final String cleanTitle(String $this$cleanTitle) {
        return new Regex("جميع مواسم مسلسل|مترجم كامل|مشاهدة فيلم|مترجم|انمي|الموسم.*|مترجمة كاملة|مسلسل|كاملة").replace($this$cleanTitle, "");
    }

    private final SearchResponse toSearchResponse(Element $this$toSearchResponse) {
        String title = cleanTitle($this$toSearchResponse.select("h1.BottomTitle").text());
        String posterUrl = $this$toSearchResponse.select("img").attr("src");
        TvType tvType = StringsKt.contains$default($this$toSearchResponse.select("span.cat_name").text(), "افلام", false, 2, (Object) null) ? TvType.Movie : TvType.TvSeries;
        return new MovieSearchResponse(title, $this$toSearchResponse.select("a").attr("href"), getName(), tvType, posterUrl, (Integer) null, (Integer) null, (SearchQuality) null, (Map) null, 480, (DefaultConstructorMarker) null);
    }

    @NotNull
    public List<MainPageData> getMainPage() {
        return this.mainPage;
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object getMainPage(int r25, @org.jetbrains.annotations.NotNull com.lagradost.cloudstream3.MainPageRequest r26, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super com.lagradost.cloudstream3.HomePageResponse> r27) {
        /*
            Method dump skipped, instruction units count: 244
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.getMainPage(int, com.lagradost.cloudstream3.MainPageRequest, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object search(@org.jetbrains.annotations.NotNull java.lang.String r24, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super java.util.List<? extends com.lagradost.cloudstream3.SearchResponse>> r25) {
        /*
            Method dump skipped, instruction units count: 260
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.search(java.lang.String, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:22:0x0114 A[LOOP:0: B:20:0x010e->B:22:0x0114, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0148  */
    /* JADX WARN: Removed duplicated region for block: B:33:0x0177  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x01ae  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object load(@org.jetbrains.annotations.NotNull java.lang.String r48, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super com.lagradost.cloudstream3.LoadResponse> r49) {
        /*
            Method dump skipped, instruction units count: 668
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.load(java.lang.String, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$load$2, reason: invalid class name */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\u008a@"}, d2 = {"<anonymous>", "", "Lcom/lagradost/cloudstream3/MovieLoadResponse;"}, k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead$load$2", f = "EgyDeadProvider.kt", i = {}, l = {91}, m = "invokeSuspend", n = {}, s = {})
    static final class AnonymousClass2 extends SuspendLambda implements Function2<MovieLoadResponse, Continuation<? super Unit>, Object> {
        final /* synthetic */ String $posterUrl;
        final /* synthetic */ Integer $rating;
        final /* synthetic */ List<SearchResponse> $recommendations;
        final /* synthetic */ String $synopsis;
        final /* synthetic */ List<String> $tags;
        final /* synthetic */ Integer $year;
        final /* synthetic */ String $youtubeTrailer;
        private /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass2(String str, List<? extends SearchResponse> list, String str2, List<String> list2, Integer num, Integer num2, String str3, Continuation<? super AnonymousClass2> continuation) {
            super(2, continuation);
            this.$posterUrl = str;
            this.$recommendations = list;
            this.$synopsis = str2;
            this.$tags = list2;
            this.$rating = num;
            this.$year = num2;
            this.$youtubeTrailer = str3;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> anonymousClass2 = new AnonymousClass2(this.$posterUrl, this.$recommendations, this.$synopsis, this.$tags, this.$rating, this.$year, this.$youtubeTrailer, continuation);
            anonymousClass2.L$0 = obj;
            return anonymousClass2;
        }

        @Nullable
        public final Object invoke(@NotNull MovieLoadResponse movieLoadResponse, @Nullable Continuation<? super Unit> continuation) {
            return create(movieLoadResponse, continuation).invokeSuspend(Unit.INSTANCE);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object $result) {
            Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure($result);
                    LoadResponse loadResponse = (MovieLoadResponse) this.L$0;
                    loadResponse.setPosterUrl(this.$posterUrl);
                    loadResponse.setRecommendations(this.$recommendations);
                    loadResponse.setPlot(this.$synopsis);
                    loadResponse.setTags(this.$tags);
                    loadResponse.setRating(this.$rating);
                    loadResponse.setYear(this.$year);
                    this.label = 1;
                    if (LoadResponse.Companion.addTrailer$default(LoadResponse.Companion, loadResponse, this.$youtubeTrailer, (String) null, false, (Continuation) this, 6, (Object) null) == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    break;
                case 1:
                    ResultKt.throwOnFailure($result);
                    break;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            return Unit.INSTANCE;
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$load$3, reason: invalid class name */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(d1 = {"\u0000\u0018\n\u0000\n\u0002\u0010 \n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0000\u0010\u0000\u001a\b\u0012\u0004\u0012\u00020\u00020\u00012\u0006\u0010\u0003\u001a\u00020\u00042\u000e\u0010\u0005\u001a\n \u0007*\u0004\u0018\u00010\u00060\u0006H\u008a@"}, d2 = {"<anonymous>", "", "", "index", "", "season", "Lorg/jsoup/nodes/Element;", "kotlin.jvm.PlatformType"}, k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead$load$3", f = "EgyDeadProvider.kt", i = {0}, l = {98}, m = "invokeSuspend", n = {"index"}, s = {"I$0"})
    static final class AnonymousClass3 extends SuspendLambda implements Function3<Integer, Element, Continuation<? super List<? extends Boolean>>, Object> {
        final /* synthetic */ ArrayList<Episode> $episodes;
        /* synthetic */ int I$0;
        /* synthetic */ Object L$0;
        int label;
        final /* synthetic */ EgyDead this$0;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass3(ArrayList<Episode> arrayList, EgyDead egyDead, Continuation<? super AnonymousClass3> continuation) {
            super(3, continuation);
            this.$episodes = arrayList;
            this.this$0 = egyDead;
        }

        @Nullable
        public final Object invoke(int i, Element element, @Nullable Continuation<? super List<Boolean>> continuation) {
            AnonymousClass3 anonymousClass3 = new AnonymousClass3(this.$episodes, this.this$0, continuation);
            anonymousClass3.I$0 = i;
            anonymousClass3.L$0 = element;
            return anonymousClass3.invokeSuspend(Unit.INSTANCE);
        }

        public /* bridge */ /* synthetic */ Object invoke(Object obj, Object obj2, Object obj3) {
            return invoke(((Number) obj).intValue(), (Element) obj2, (Continuation<? super List<Boolean>>) obj3);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            int index;
            AnonymousClass3 anonymousClass3;
            Object $result;
            Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure(obj);
                    index = this.I$0;
                    Element season = (Element) this.L$0;
                    this.I$0 = index;
                    this.label = 1;
                    Object obj2 = Requests.get$default(MainActivityKt.getApp(), season.attr("href"), (Map) null, (String) null, (Map) null, (Map) null, false, 0, (TimeUnit) null, 0L, (Interceptor) null, false, (ResponseParser) null, (Continuation) this, 4094, (Object) null);
                    if (obj2 == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    anonymousClass3 = this;
                    $result = obj2;
                    break;
                    break;
                case 1:
                    anonymousClass3 = this;
                    $result = obj;
                    int index2 = anonymousClass3.I$0;
                    ResultKt.throwOnFailure($result);
                    index = index2;
                    break;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            Iterable $this$map$iv = ((NiceResponse) $result).getDocument().select("div.EpsList > li > a");
            ArrayList<Episode> arrayList = anonymousClass3.$episodes;
            EgyDead egyDead = anonymousClass3.this$0;
            Collection destination$iv$iv = new ArrayList(CollectionsKt.collectionSizeOrDefault($this$map$iv, 10));
            for (Object item$iv$iv : $this$map$iv) {
                Element it = (Element) item$iv$iv;
                destination$iv$iv.add(Boxing.boxBoolean(arrayList.add(new Episode(it.attr("href"), it.attr("title"), Boxing.boxInt(index + 1), egyDead.getIntFromText(it.text()), (String) null, (Integer) null, (String) null, (Long) null, 240, (DefaultConstructorMarker) null))));
                anonymousClass3 = anonymousClass3;
            }
            return (List) destination$iv$iv;
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$load$6, reason: invalid class name */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\u008a@"}, d2 = {"<anonymous>", "", "Lcom/lagradost/cloudstream3/TvSeriesLoadResponse;"}, k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead$load$6", f = "EgyDeadProvider.kt", i = {}, l = {126}, m = "invokeSuspend", n = {}, s = {})
    static final class AnonymousClass6 extends SuspendLambda implements Function2<TvSeriesLoadResponse, Continuation<? super Unit>, Object> {
        final /* synthetic */ String $posterUrl;
        final /* synthetic */ Integer $rating;
        final /* synthetic */ List<SearchResponse> $recommendations;
        final /* synthetic */ String $synopsis;
        final /* synthetic */ List<String> $tags;
        final /* synthetic */ Integer $year;
        final /* synthetic */ String $youtubeTrailer;
        private /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass6(String str, List<String> list, String str2, List<? extends SearchResponse> list2, Integer num, Integer num2, String str3, Continuation<? super AnonymousClass6> continuation) {
            super(2, continuation);
            this.$posterUrl = str;
            this.$tags = list;
            this.$synopsis = str2;
            this.$recommendations = list2;
            this.$rating = num;
            this.$year = num2;
            this.$youtubeTrailer = str3;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> anonymousClass6 = new AnonymousClass6(this.$posterUrl, this.$tags, this.$synopsis, this.$recommendations, this.$rating, this.$year, this.$youtubeTrailer, continuation);
            anonymousClass6.L$0 = obj;
            return anonymousClass6;
        }

        @Nullable
        public final Object invoke(@NotNull TvSeriesLoadResponse tvSeriesLoadResponse, @Nullable Continuation<? super Unit> continuation) {
            return create(tvSeriesLoadResponse, continuation).invokeSuspend(Unit.INSTANCE);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object $result) {
            Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure($result);
                    LoadResponse loadResponse = (TvSeriesLoadResponse) this.L$0;
                    loadResponse.setPosterUrl(this.$posterUrl);
                    loadResponse.setTags(this.$tags);
                    loadResponse.setPlot(this.$synopsis);
                    loadResponse.setRecommendations(this.$recommendations);
                    loadResponse.setRating(this.$rating);
                    loadResponse.setYear(this.$year);
                    this.label = 1;
                    if (LoadResponse.Companion.addTrailer$default(LoadResponse.Companion, loadResponse, this.$youtubeTrailer, (String) null, false, (Continuation) this, 6, (Object) null) == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                    break;
                case 1:
                    ResultKt.throwOnFailure($result);
                    break;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            return Unit.INSTANCE;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:21:0x0106 A[LOOP:1: B:19:0x0100->B:21:0x0106, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0134  */
    /* JADX WARN: Removed duplicated region for block: B:28:0x015a  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x01bc A[LOOP:0: B:33:0x01b6->B:35:0x01bc, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:37:0x01d0  */
    /* JADX WARN: Removed duplicated region for block: B:38:0x01db  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:25:0x0134 -> B:26:0x0154). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:31:0x019a -> B:32:0x01a8). Please report as a decompilation issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object Trgsfjll(java.lang.String r37, java.lang.String r38, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r39, kotlin.coroutines.Continuation<? super kotlin.Unit> r40) {
        /*
            Method dump skipped, instruction units count: 490
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.Trgsfjll(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:30:0x015e A[LOOP:1: B:28:0x0158->B:30:0x015e, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:34:0x0187  */
    /* JADX WARN: Removed duplicated region for block: B:40:0x019f  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x0208 A[LOOP:0: B:47:0x0202->B:49:0x0208, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0199 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handlevidhide(java.lang.String r27, java.lang.String r28, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r29, kotlin.coroutines.Continuation<? super kotlin.Unit> r30) {
        /*
            Method dump skipped, instruction units count: 544
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.handlevidhide(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object loadLinks(@org.jetbrains.annotations.NotNull java.lang.String r31, boolean r32, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.SubtitleFile, kotlin.Unit> r33, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r34, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super java.lang.Boolean> r35) {
        /*
            Method dump skipped, instruction units count: 250
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.loadLinks(java.lang.String, boolean, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$loadLinks$2, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(d1 = {"\u0000\u000e\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0000\u0010\u0000\u001a\u00020\u00012\u000e\u0010\u0002\u001a\n \u0004*\u0004\u0018\u00010\u00030\u0003H\u008a@"}, d2 = {"<anonymous>", "", "element", "Lorg/jsoup/nodes/Element;", "kotlin.jvm.PlatformType"}, k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead$loadLinks$2", f = "EgyDeadProvider.kt", i = {0, 1}, l = {196, 199, 201}, m = "invokeSuspend", n = {"url", "url"}, s = {"L$0", "L$0"})
    static final class C00042 extends SuspendLambda implements Function2<Element, Continuation<? super Boolean>, Object> {
        final /* synthetic */ Function1<ExtractorLink, Unit> $callback;
        final /* synthetic */ String $data;
        final /* synthetic */ Function1<SubtitleFile, Unit> $subtitleCallback;
        /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        C00042(Function1<? super ExtractorLink, Unit> function1, String str, Function1<? super SubtitleFile, Unit> function12, Continuation<? super C00042> continuation) {
            super(2, continuation);
            this.$callback = function1;
            this.$data = str;
            this.$subtitleCallback = function12;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> c00042 = EgyDead.this.new C00042(this.$callback, this.$data, this.$subtitleCallback, continuation);
            c00042.L$0 = obj;
            return c00042;
        }

        @Nullable
        public final Object invoke(Element element, @Nullable Continuation<? super Boolean> continuation) {
            return create(element, continuation).invokeSuspend(Unit.INSTANCE);
        }

        /* JADX WARN: Removed duplicated region for block: B:16:0x0071  */
        /* JADX WARN: Removed duplicated region for block: B:23:0x0099 A[RETURN] */
        /* JADX WARN: Removed duplicated region for block: B:24:0x009a  */
        @org.jetbrains.annotations.Nullable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public final java.lang.Object invokeSuspend(@org.jetbrains.annotations.NotNull java.lang.Object r11) {
            /*
                r10 = this;
                java.lang.Object r0 = kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                int r1 = r10.label
                r2 = 0
                r3 = 0
                r4 = 2
                switch(r1) {
                    case 0: goto L2d;
                    case 1: goto L24;
                    case 2: goto L1b;
                    case 3: goto L14;
                    default: goto Lc;
                }
            Lc:
                java.lang.IllegalStateException r11 = new java.lang.IllegalStateException
                java.lang.String r0 = "call to 'resume' before 'invoke' with coroutine"
                r11.<init>(r0)
                throw r11
            L14:
                r0 = r10
                kotlin.ResultKt.throwOnFailure(r11)
                r2 = r11
                goto L9b
            L1b:
                r1 = r10
                java.lang.Object r2 = r1.L$0
                java.lang.String r2 = (java.lang.String) r2
                kotlin.ResultKt.throwOnFailure(r11)
                goto L84
            L24:
                r1 = r10
                java.lang.Object r5 = r1.L$0
                java.lang.String r5 = (java.lang.String) r5
                kotlin.ResultKt.throwOnFailure(r11)
                goto L63
            L2d:
                kotlin.ResultKt.throwOnFailure(r11)
                r1 = r10
                java.lang.Object r5 = r1.L$0
                org.jsoup.nodes.Element r5 = (org.jsoup.nodes.Element) r5
                java.lang.String r6 = "a"
                org.jsoup.select.Elements r6 = r5.select(r6)
                java.lang.String r7 = "href"
                java.lang.String r5 = r6.attr(r7)
                r6 = r5
                java.lang.CharSequence r6 = (java.lang.CharSequence) r6
                java.lang.String r7 = "trgsfjll"
                java.lang.CharSequence r7 = (java.lang.CharSequence) r7
                boolean r6 = kotlin.text.StringsKt.contains$default(r6, r7, r2, r4, r3)
                if (r6 == 0) goto L63
                com.egydead.EgyDead r6 = com.egydead.EgyDead.this
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r7 = r1.$callback
                r8 = r1
                kotlin.coroutines.Continuation r8 = (kotlin.coroutines.Continuation) r8
                r1.L$0 = r5
                r9 = 1
                r1.label = r9
                java.lang.String r9 = "Trgsfjll"
                java.lang.Object r6 = com.egydead.EgyDead.access$Trgsfjll(r6, r5, r9, r7, r8)
                if (r6 != r0) goto L63
                return r0
            L63:
                r6 = r5
                java.lang.CharSequence r6 = (java.lang.CharSequence) r6
                java.lang.String r7 = "vidhide"
                r8 = r7
                java.lang.CharSequence r8 = (java.lang.CharSequence) r8
                boolean r2 = kotlin.text.StringsKt.contains$default(r6, r8, r2, r4, r3)
                if (r2 == 0) goto L85
                com.egydead.EgyDead r2 = com.egydead.EgyDead.this
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r6 = r1.$callback
                r8 = r1
                kotlin.coroutines.Continuation r8 = (kotlin.coroutines.Continuation) r8
                r1.L$0 = r5
                r1.label = r4
                java.lang.Object r2 = com.egydead.EgyDead.access$handlevidhide(r2, r5, r7, r6, r8)
                if (r2 != r0) goto L83
                return r0
            L83:
                r2 = r5
            L84:
                r5 = r2
            L85:
                java.lang.String r2 = r1.$data
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.SubtitleFile, kotlin.Unit> r4 = r1.$subtitleCallback
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r6 = r1.$callback
                r7 = r1
                kotlin.coroutines.Continuation r7 = (kotlin.coroutines.Continuation) r7
                r1.L$0 = r3
                r3 = 3
                r1.label = r3
                java.lang.Object r2 = com.lagradost.cloudstream3.utils.ExtractorApiKt.loadExtractor(r5, r2, r4, r6, r7)
                if (r2 != r0) goto L9a
                return r0
            L9a:
                r0 = r1
            L9b:
                return r2
            */
            throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.C00042.invokeSuspend(java.lang.Object):java.lang.Object");
        }
    }

    /* JADX INFO: renamed from: com.egydead.EgyDead$loadLinks$3, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: EgyDeadProvider.kt */
    @Metadata(d1 = {"\u0000\u000e\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0000\u0010\u0000\u001a\u00020\u00012\u000e\u0010\u0002\u001a\n \u0004*\u0004\u0018\u00010\u00030\u0003H\u008a@"}, d2 = {"<anonymous>", "", "li", "Lorg/jsoup/nodes/Element;", "kotlin.jvm.PlatformType"}, k = 3, mv = {1, 7, 1}, xi = 48)
    @DebugMetadata(c = "com.egydead.EgyDead$loadLinks$3", f = "EgyDeadProvider.kt", i = {0, 1}, l = {206, 209, 211}, m = "invokeSuspend", n = {"iframeUrl", "iframeUrl"}, s = {"L$0", "L$0"})
    static final class C00053 extends SuspendLambda implements Function2<Element, Continuation<? super Boolean>, Object> {
        final /* synthetic */ Function1<ExtractorLink, Unit> $callback;
        final /* synthetic */ String $data;
        final /* synthetic */ Function1<SubtitleFile, Unit> $subtitleCallback;
        /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        C00053(Function1<? super ExtractorLink, Unit> function1, String str, Function1<? super SubtitleFile, Unit> function12, Continuation<? super C00053> continuation) {
            super(2, continuation);
            this.$callback = function1;
            this.$data = str;
            this.$subtitleCallback = function12;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> c00053 = EgyDead.this.new C00053(this.$callback, this.$data, this.$subtitleCallback, continuation);
            c00053.L$0 = obj;
            return c00053;
        }

        @Nullable
        public final Object invoke(Element element, @Nullable Continuation<? super Boolean> continuation) {
            return create(element, continuation).invokeSuspend(Unit.INSTANCE);
        }

        /* JADX WARN: Removed duplicated region for block: B:16:0x006b  */
        /* JADX WARN: Removed duplicated region for block: B:23:0x0093 A[RETURN] */
        /* JADX WARN: Removed duplicated region for block: B:24:0x0094  */
        @org.jetbrains.annotations.Nullable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public final java.lang.Object invokeSuspend(@org.jetbrains.annotations.NotNull java.lang.Object r11) {
            /*
                r10 = this;
                java.lang.Object r0 = kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()
                int r1 = r10.label
                r2 = 0
                r3 = 0
                r4 = 2
                switch(r1) {
                    case 0: goto L2d;
                    case 1: goto L24;
                    case 2: goto L1b;
                    case 3: goto L14;
                    default: goto Lc;
                }
            Lc:
                java.lang.IllegalStateException r11 = new java.lang.IllegalStateException
                java.lang.String r0 = "call to 'resume' before 'invoke' with coroutine"
                r11.<init>(r0)
                throw r11
            L14:
                r0 = r10
                kotlin.ResultKt.throwOnFailure(r11)
                r2 = r11
                goto L95
            L1b:
                r1 = r10
                java.lang.Object r2 = r1.L$0
                java.lang.String r2 = (java.lang.String) r2
                kotlin.ResultKt.throwOnFailure(r11)
                goto L7e
            L24:
                r1 = r10
                java.lang.Object r5 = r1.L$0
                java.lang.String r5 = (java.lang.String) r5
                kotlin.ResultKt.throwOnFailure(r11)
                goto L5d
            L2d:
                kotlin.ResultKt.throwOnFailure(r11)
                r1 = r10
                java.lang.Object r5 = r1.L$0
                org.jsoup.nodes.Element r5 = (org.jsoup.nodes.Element) r5
                java.lang.String r6 = "data-link"
                java.lang.String r5 = r5.attr(r6)
                r6 = r5
                java.lang.CharSequence r6 = (java.lang.CharSequence) r6
                java.lang.String r7 = "trgsfjll"
                java.lang.CharSequence r7 = (java.lang.CharSequence) r7
                boolean r6 = kotlin.text.StringsKt.contains$default(r6, r7, r2, r4, r3)
                if (r6 == 0) goto L5d
                com.egydead.EgyDead r6 = com.egydead.EgyDead.this
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r7 = r1.$callback
                r8 = r1
                kotlin.coroutines.Continuation r8 = (kotlin.coroutines.Continuation) r8
                r1.L$0 = r5
                r9 = 1
                r1.label = r9
                java.lang.String r9 = "Trgsfjll"
                java.lang.Object r6 = com.egydead.EgyDead.access$Trgsfjll(r6, r5, r9, r7, r8)
                if (r6 != r0) goto L5d
                return r0
            L5d:
                r6 = r5
                java.lang.CharSequence r6 = (java.lang.CharSequence) r6
                java.lang.String r7 = "vidhide"
                r8 = r7
                java.lang.CharSequence r8 = (java.lang.CharSequence) r8
                boolean r2 = kotlin.text.StringsKt.contains$default(r6, r8, r2, r4, r3)
                if (r2 == 0) goto L7f
                com.egydead.EgyDead r2 = com.egydead.EgyDead.this
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r6 = r1.$callback
                r8 = r1
                kotlin.coroutines.Continuation r8 = (kotlin.coroutines.Continuation) r8
                r1.L$0 = r5
                r1.label = r4
                java.lang.Object r2 = com.egydead.EgyDead.access$handlevidhide(r2, r5, r7, r6, r8)
                if (r2 != r0) goto L7d
                return r0
            L7d:
                r2 = r5
            L7e:
                r5 = r2
            L7f:
                java.lang.String r2 = r1.$data
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.SubtitleFile, kotlin.Unit> r4 = r1.$subtitleCallback
                kotlin.jvm.functions.Function1<com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r6 = r1.$callback
                r7 = r1
                kotlin.coroutines.Continuation r7 = (kotlin.coroutines.Continuation) r7
                r1.L$0 = r3
                r3 = 3
                r1.label = r3
                java.lang.Object r2 = com.lagradost.cloudstream3.utils.ExtractorApiKt.loadExtractor(r5, r2, r4, r6, r7)
                if (r2 != r0) goto L94
                return r0
            L94:
                r0 = r1
            L95:
                return r2
            */
            throw new UnsupportedOperationException("Method not decompiled: com.egydead.EgyDead.C00053.invokeSuspend(java.lang.Object):java.lang.Object");
        }
    }
}
