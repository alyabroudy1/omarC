package com.braflix;

import android.util.Log;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagradost.cloudstream3.ActorData;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.MainPageData;
import com.lagradost.cloudstream3.MovieLoadResponse;
import com.lagradost.cloudstream3.SearchResponse;
import com.lagradost.cloudstream3.TvSeriesLoadResponse;
import com.lagradost.cloudstream3.TvType;
import com.lagradost.cloudstream3.utils.AppUtils;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.Qualities;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Metadata;
import kotlin.Pair;
import kotlin.ResultKt;
import kotlin.TuplesKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.SetsKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import kotlin.ranges.IntProgression;
import kotlin.ranges.RangesKt;
import kotlin.sequences.SequencesKt;
import kotlin.text.Charsets;
import kotlin.text.MatchResult;
import kotlin.text.Regex;
import kotlin.text.StringsKt;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

/* JADX INFO: compiled from: BraflixProvider.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/streamPre-4.6/omarC/cimanow_decompiled/classes.dex */
@Metadata(d1 = {"\u0000\u0084\u0001\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\t\n\u0002\u0010\"\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\b\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\t\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\b\u0002\u0018\u00002\u00020\u0001:\u0001MB\u0005¢\u0006\u0002\u0010\u0002J\u000e\u0010\u001f\u001a\u00020 2\u0006\u0010!\u001a\u00020 J\u0016\u0010\"\u001a\u00020\b2\u0006\u0010#\u001a\u00020\b2\u0006\u0010$\u001a\u00020%J\u001e\u0010\u0010\u001a\u00020&2\u0006\u0010'\u001a\u00020%2\u0006\u0010(\u001a\u00020)H\u0096@¢\u0006\u0002\u0010*J2\u0010+\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00102\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00103\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00104\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00105\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00106\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J2\u00107\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0082@¢\u0006\u0002\u00101J,\u00108\u001a\u00020,2\u0006\u0010-\u001a\u00020\b2\u0006\u0010\u0015\u001a\u00020\b2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0002J\u0016\u00109\u001a\u00020:2\u0006\u0010;\u001a\u00020\bH\u0096@¢\u0006\u0002\u0010<JF\u0010=\u001a\u00020\u00042\u0006\u0010>\u001a\u00020\b2\u0006\u0010?\u001a\u00020\u00042\u0012\u0010@\u001a\u000e\u0012\u0004\u0012\u00020A\u0012\u0004\u0012\u00020,0/2\u0012\u0010.\u001a\u000e\u0012\u0004\u0012\u000200\u0012\u0004\u0012\u00020,0/H\u0096@¢\u0006\u0002\u0010BJ\u000e\u0010C\u001a\u00020\b2\u0006\u0010D\u001a\u00020\bJ\u001c\u0010E\u001a\b\u0012\u0004\u0012\u00020F0\u000e2\u0006\u0010G\u001a\u00020\bH\u0096@¢\u0006\u0002\u0010<J\u000e\u0010H\u001a\u00020\b2\u0006\u0010;\u001a\u00020\bJ\u0013\u0010I\u001a\u0004\u0018\u00010%*\u00020\bH\u0002¢\u0006\u0002\u0010JJ\u000e\u0010K\u001a\u0004\u0018\u00010F*\u00020LH\u0002R\u0014\u0010\u0003\u001a\u00020\u0004X\u0096D¢\u0006\b\n\u0000\u001a\u0004\b\u0005\u0010\u0006R\u001a\u0010\u0007\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\t\u0010\n\"\u0004\b\u000b\u0010\fR\u001a\u0010\r\u001a\b\u0012\u0004\u0012\u00020\u000f0\u000eX\u0096\u0004¢\u0006\b\n\u0000\u001a\u0004\b\u0010\u0010\u0011R\u001a\u0010\u0012\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0013\u0010\n\"\u0004\b\u0014\u0010\fR\u001a\u0010\u0015\u001a\u00020\bX\u0096\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0016\u0010\n\"\u0004\b\u0017\u0010\fR\u001a\u0010\u0018\u001a\b\u0012\u0004\u0012\u00020\u001a0\u0019X\u0096\u0004¢\u0006\b\n\u0000\u001a\u0004\b\u001b\u0010\u001cR\u0014\u0010\u001d\u001a\u00020\u0004X\u0096D¢\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u0006¨\u0006N"}, d2 = {"Lcom/braflix/Braflix;", "Lcom/lagradost/cloudstream3/MainAPI;", "()V", "hasMainPage", "", "getHasMainPage", "()Z", "lang", "", "getLang", "()Ljava/lang/String;", "setLang", "(Ljava/lang/String;)V", "mainPage", "", "Lcom/lagradost/cloudstream3/MainPageData;", "getMainPage", "()Ljava/util/List;", "mainUrl", "getMainUrl", "setMainUrl", "name", "getName", "setName", "supportedTypes", "", "Lcom/lagradost/cloudstream3/TvType;", "getSupportedTypes", "()Ljava/util/Set;", "usesWebView", "getUsesWebView", "decodeHtml", "Lorg/jsoup/nodes/Document;", "doc", "decodeObfuscatedString", "concatenated", "lastNumber", "", "Lcom/lagradost/cloudstream3/HomePageResponse;", "page", "request", "Lcom/lagradost/cloudstream3/MainPageRequest;", "(ILcom/lagradost/cloudstream3/MainPageRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "handleGovid", "", "iframeUrl", "callback", "Lkotlin/Function1;", "Lcom/lagradost/cloudstream3/utils/ExtractorLink;", "(Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "handleStreamfileAndLuluvid", "handleStreamwish", "handleVadbamAndViidshare", "handleVidPro", "handleVidlook", "handlecima", "handlenet", "load", "Lcom/lagradost/cloudstream3/LoadResponse;", "url", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "loadLinks", "data", "isCasting", "subtitleCallback", "Lcom/lagradost/cloudstream3/SubtitleFile;", "(Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "runJS2", "hideMyHtmlContent", "search", "Lcom/lagradost/cloudstream3/SearchResponse;", "query", "sigDecode", "getIntFromText", "(Ljava/lang/String;)Ljava/lang/Integer;", "toSearchResponse", "Lorg/jsoup/nodes/Element;", "SvgObject", "Braflix_debug"}, k = 1, mv = {1, 9, 0}, xi = 48)
@SourceDebugExtension({"SMAP\nBraflixProvider.kt\nKotlin\n*S Kotlin\n*F\n+ 1 BraflixProvider.kt\ncom/braflix/Braflix\n+ 2 AppUtils.kt\ncom/lagradost/cloudstream3/utils/AppUtils\n+ 3 Extensions.kt\ncom/fasterxml/jackson/module/kotlin/ExtensionsKt\n+ 4 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n+ 5 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 6 _Sequences.kt\nkotlin/sequences/SequencesKt___SequencesKt\n+ 7 _Strings.kt\nkotlin/text/StringsKt___StringsKt\n*L\n1#1,999:1\n14#2:1000\n50#3:1001\n43#3:1002\n1549#4:1003\n1620#4,3:1004\n1549#4:1010\n1620#4,3:1011\n1855#4:1014\n1856#4:1017\n1549#4:1018\n1620#4,3:1019\n1549#4:1024\n1620#4,3:1025\n1855#4:1028\n1855#4,2:1030\n1856#4:1033\n1549#4:1034\n1620#4,3:1035\n1855#4,2:1038\n1549#4:1040\n1620#4,3:1041\n1855#4:1044\n1855#4,2:1046\n1856#4:1049\n1549#4:1050\n1620#4,3:1051\n1603#4,9:1054\n1855#4:1063\n1856#4:1065\n1612#4:1066\n1603#4,9:1067\n1855#4:1076\n1856#4:1078\n1612#4:1079\n819#4:1080\n847#4,2:1081\n1855#4:1083\n1549#4:1084\n1620#4,3:1085\n1856#4:1088\n1603#4,9:1089\n1855#4:1098\n1856#4:1100\n1612#4:1101\n1603#4,9:1102\n1855#4:1111\n1856#4:1113\n1612#4:1114\n1864#4,2:1115\n1864#4,3:1117\n1866#4:1120\n1054#4:1121\n1855#4,2:1122\n1855#4:1124\n1747#4,3:1125\n1549#4:1128\n1620#4,3:1129\n1856#4:1132\n1#5:1007\n1#5:1064\n1#5:1077\n1#5:1099\n1#5:1112\n1313#6,2:1008\n1313#6,2:1015\n1313#6,2:1022\n1313#6:1029\n1314#6:1032\n1313#6:1045\n1314#6:1048\n429#7:1133\n502#7,5:1134\n*S KotlinDebug\n*F\n+ 1 BraflixProvider.kt\ncom/braflix/Braflix\n*L\n146#1:1000\n146#1:1001\n146#1:1002\n174#1:1003\n174#1:1004,3\n219#1:1010\n219#1:1011,3\n222#1:1014\n222#1:1017\n249#1:1018\n249#1:1019,3\n294#1:1024\n294#1:1025,3\n297#1:1028\n304#1:1030,2\n297#1:1033\n323#1:1034\n323#1:1035,3\n343#1:1038,2\n357#1:1040\n357#1:1041,3\n360#1:1044\n372#1:1046,2\n360#1:1049\n413#1:1050\n413#1:1051,3\n655#1:1054,9\n655#1:1063\n655#1:1065\n655#1:1066\n667#1:1067,9\n667#1:1076\n667#1:1078\n667#1:1079\n697#1:1080\n697#1:1081,2\n702#1:1083\n706#1:1084\n706#1:1085,3\n702#1:1088\n712#1:1089,9\n712#1:1098\n712#1:1100\n712#1:1101\n722#1:1102,9\n722#1:1111\n722#1:1113\n722#1:1114\n754#1:1115,2\n761#1:1117,3\n754#1:1120\n779#1:1121\n805#1:1122,2\n837#1:1124\n865#1:1125,3\n965#1:1128\n965#1:1129,3\n837#1:1132\n655#1:1064\n667#1:1077\n712#1:1099\n722#1:1112\n187#1:1008,2\n223#1:1015,2\n262#1:1022,2\n298#1:1029\n298#1:1032\n361#1:1045\n361#1:1048\n469#1:1133\n469#1:1134,5\n*E\n"})
public final class Braflix extends MainAPI {
    private final boolean usesWebView;

    @NotNull
    private String lang = "ar";

    @NotNull
    private String mainUrl = "https://cimanow.cc";

    @NotNull
    private String name = "Cimanow";
    private final boolean hasMainPage = true;

    @NotNull
    private final Set<TvType> supportedTypes = SetsKt.setOf(new TvType[]{TvType.TvSeries, TvType.Movie});

    @NotNull
    private final List<MainPageData> mainPage = MainAPIKt.mainPageOf(new Pair[]{TuplesKt.to("https://cimanow.cc/%D8%A7%D9%84%D8%A7%D8%AD%D8%AF%D8%AB/page/", "احدث الاضافات"), TuplesKt.to("https://cimanow.cc/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/page/", "افلام اجنبية"), TuplesKt.to("https://cimanow.cc/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A%D8%A9/page/", "مسلسلات اجنبية"), TuplesKt.to("https://cimanow.cc/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D9%86%D8%AA%D9%81%D9%84%D9%8A%D9%83%D8%B3/page/", "افلام نتفليكس"), TuplesKt.to("https://cimanow.cc/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D9%86%D8%AA%D9%81%D9%84%D9%8A%D9%83%D8%B3/page/", "مسلسلات نتفليكس"), TuplesKt.to("https://cimanow.cc/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D9%85%D8%A7%D8%B1%D9%81%D9%84/page/", "افلام مارفل"), TuplesKt.to("https://cimanow.cc/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%D8%A7%D8%AA-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9/page/", "مسلسلات عربية"), TuplesKt.to("https://cimanow.cc/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9/page/", "افلام عربية")});

    /* JADX INFO: renamed from: com.braflix.Braflix$getMainPage$1, reason: invalid class name */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0}, l = {654}, m = "getMainPage", n = {"this", "request"}, s = {"L$0", "L$1"})
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
            return Braflix.this.getMainPage(0, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleGovid$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0}, l = {218}, m = "handleGovid", n = {"this", "name", "callback"}, s = {"L$0", "L$1", "L$2"})
    static final class C00001 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        int label;
        /* synthetic */ Object result;

        C00001(Continuation<? super C00001> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handleGovid(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleStreamfileAndLuluvid$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0, 1, 1, 1, 2}, l = {318, 320, 339}, m = "handleStreamfileAndLuluvid", n = {"this", "name", "callback", "this", "name", "callback", "callback"}, s = {"L$0", "L$1", "L$2", "L$0", "L$1", "L$2", "L$0"})
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
            return Braflix.this.handleStreamfileAndLuluvid(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleStreamwish$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0, 1, 1, 1, 1}, l = {293, 300}, m = "handleStreamwish", n = {"this", "name", "callback", "this", "name", "callback", "pattern"}, s = {"L$0", "L$1", "L$2", "L$0", "L$1", "L$2", "L$3"})
    static final class C00021 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        int label;
        /* synthetic */ Object result;

        C00021(Continuation<? super C00021> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handleStreamwish(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleVadbamAndViidshare$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0, 1, 1, 1, 1}, l = {356, 368}, m = "handleVadbamAndViidshare", n = {"this", "name", "callback", "this", "name", "callback", "pattern"}, s = {"L$0", "L$1", "L$2", "L$0", "L$1", "L$2", "L$3"})
    static final class C00031 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        int label;
        /* synthetic */ Object result;

        C00031(Continuation<? super C00031> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handleVadbamAndViidshare(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleVidPro$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0}, l = {173}, m = "handleVidPro", n = {"this", "name", "callback"}, s = {"L$0", "L$1", "L$2"})
    static final class C00041 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        int label;
        /* synthetic */ Object result;

        C00041(Continuation<? super C00041> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handleVidPro(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handleVidlook$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0}, l = {248}, m = "handleVidlook", n = {"this", "name", "callback"}, s = {"L$0", "L$1", "L$2"})
    static final class C00051 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        int label;
        /* synthetic */ Object result;

        C00051(Continuation<? super C00051> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handleVidlook(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$handlecima$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0, 0, 0}, l = {803}, m = "handlecima", n = {"this", "iframeUrl", "name", "callback", "domain"}, s = {"L$0", "L$1", "L$2", "L$3", "L$4"})
    static final class C00061 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        int label;
        /* synthetic */ Object result;

        C00061(Continuation<? super C00061> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.handlecima(null, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$load$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3}, l = {674, 731, 750, 758, 775}, m = "load", n = {"this", "url", "this", "url", "posterUrl", "year", "title", "tags", "recommendations", "synopsis", "actors", "episodes", "this", "url", "posterUrl", "year", "title", "tags", "recommendations", "synopsis", "actors", "episodes", "index$iv", "seasonNumber"}, s = {"L$0", "L$1", "L$0", "L$1", "L$2", "L$3", "L$4", "L$5", "L$6", "L$7", "L$8", "L$9", "L$0", "L$1", "L$2", "L$3", "L$4", "L$5", "L$6", "L$7", "L$8", "L$9", "I$0", "I$1"})
    static final class C00071 extends ContinuationImpl {
        int I$0;
        int I$1;
        Object L$0;
        Object L$1;
        Object L$10;
        Object L$11;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        Object L$6;
        Object L$7;
        Object L$8;
        Object L$9;
        int label;
        /* synthetic */ Object result;

        C00071(Continuation<? super C00071> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.load(null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$loadLinks$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11}, l = {833, 844, 876, 887, 897, 907, 913, 923, 933, 943, 953, 979}, m = "loadLinks", n = {"this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "element", "name", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl", "this", "subtitleCallback", "callback", "watchUrl"}, s = {"L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$5", "L$6", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3", "L$0", "L$1", "L$2", "L$3"})
    static final class C00081 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        Object L$2;
        Object L$3;
        Object L$4;
        Object L$5;
        Object L$6;
        int label;
        /* synthetic */ Object result;

        C00081(Continuation<? super C00081> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.loadLinks(null, false, null, null, (Continuation) this);
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$search$1, reason: invalid class name and case insensitive filesystem */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix", f = "BraflixProvider.kt", i = {0}, l = {664}, m = "search", n = {"this"}, s = {"L$0"})
    static final class C00091 extends ContinuationImpl {
        Object L$0;
        Object L$1;
        int label;
        /* synthetic */ Object result;

        C00091(Continuation<? super C00091> continuation) {
            super(continuation);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            this.result = obj;
            this.label |= Integer.MIN_VALUE;
            return Braflix.this.search(null, (Continuation) this);
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

    private final Integer getIntFromText(String $this$getIntFromText) {
        List groupValues;
        String str;
        MatchResult matchResultFind$default = Regex.find$default(new Regex("\\d+"), $this$getIntFromText, 0, 2, (Object) null);
        if (matchResultFind$default == null || (groupValues = matchResultFind$default.getGroupValues()) == null || (str = (String) CollectionsKt.firstOrNull(groupValues)) == null) {
            return null;
        }
        return StringsKt.toIntOrNull(str);
    }

    @NotNull
    public final String sigDecode(@NotNull String url) {
        String str;
        String sig = (String) StringsKt.split$default((CharSequence) StringsKt.split$default(url, new String[]{"sig="}, false, 0, 6, (Object) null).get(1), new String[]{"&"}, false, 0, 6, (Object) null).get(0);
        String t = "";
        for (String v : StringsKt.chunked(sig, 2)) {
            int byteValue = Integer.parseInt(v, 16) ^ 2;
            t = t + ((char) byteValue);
        }
        switch (t.length() % 4) {
            case 2:
                str = "==";
                break;
            case 3:
                str = "=";
                break;
            default:
                str = "";
                break;
        }
        String padding = str;
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] bytes = (t + padding).getBytes(Charsets.UTF_8);
        Intrinsics.checkNotNullExpressionValue(bytes, "getBytes(...)");
        byte[] decoded = decoder.decode(bytes);
        String t2 = StringsKt.reversed(StringsKt.dropLast(new String(decoded, Charsets.UTF_8), 5)).toString();
        char[] charArray = t2.toCharArray();
        Intrinsics.checkNotNullExpressionValue(charArray, "toCharArray(...)");
        IntProgression intProgressionStep = RangesKt.step(RangesKt.until(0, charArray.length - 1), 2);
        int i = intProgressionStep.getFirst();
        int last = intProgressionStep.getLast();
        int step = intProgressionStep.getStep();
        if ((step > 0 && i <= last) || (step < 0 && last <= i)) {
            while (true) {
                char temp = charArray[i];
                charArray[i] = charArray[i + 1];
                charArray[i + 1] = temp;
                if (i != last) {
                    i += step;
                }
            }
        }
        String modifiedSig = StringsKt.dropLast(new String(charArray), 5);
        return StringsKt.replace$default(url, sig, modifiedSig, false, 4, (Object) null);
    }

    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(d1 = {"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\t\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\b\u0086\b\u0018\u00002\u00020\u0001B\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003¢\u0006\u0002\u0010\u0005J\t\u0010\t\u001a\u00020\u0003HÆ\u0003J\t\u0010\n\u001a\u00020\u0003HÆ\u0003J\u001d\u0010\u000b\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u0003HÆ\u0001J\u0013\u0010\f\u001a\u00020\r2\b\u0010\u000e\u001a\u0004\u0018\u00010\u0001HÖ\u0003J\t\u0010\u000f\u001a\u00020\u0010HÖ\u0001J\t\u0010\u0011\u001a\u00020\u0003HÖ\u0001R\u0011\u0010\u0004\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0006\u0010\u0007R\u0011\u0010\u0002\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\b\u0010\u0007¨\u0006\u0012"}, d2 = {"Lcom/braflix/Braflix$SvgObject;", "", "stream", "", "hash", "(Ljava/lang/String;Ljava/lang/String;)V", "getHash", "()Ljava/lang/String;", "getStream", "component1", "component2", "copy", "equals", "", "other", "hashCode", "", "toString", "Braflix_debug"}, k = 1, mv = {1, 9, 0}, xi = 48)
    public static final /* data */ class SvgObject {

        @NotNull
        private final String hash;

        @NotNull
        private final String stream;

        public static /* synthetic */ SvgObject copy$default(SvgObject svgObject, String str, String str2, int i, Object obj) {
            if ((i & 1) != 0) {
                str = svgObject.stream;
            }
            if ((i & 2) != 0) {
                str2 = svgObject.hash;
            }
            return svgObject.copy(str, str2);
        }

        @NotNull
        /* JADX INFO: renamed from: component1, reason: from getter */
        public final String getStream() {
            return this.stream;
        }

        @NotNull
        /* JADX INFO: renamed from: component2, reason: from getter */
        public final String getHash() {
            return this.hash;
        }

        @NotNull
        public final SvgObject copy(@NotNull String stream, @NotNull String hash) {
            return new SvgObject(stream, hash);
        }

        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SvgObject)) {
                return false;
            }
            SvgObject svgObject = (SvgObject) other;
            return Intrinsics.areEqual(this.stream, svgObject.stream) && Intrinsics.areEqual(this.hash, svgObject.hash);
        }

        public int hashCode() {
            return (this.stream.hashCode() * 31) + this.hash.hashCode();
        }

        @NotNull
        public String toString() {
            return "SvgObject(stream=" + this.stream + ", hash=" + this.hash + ')';
        }

        public SvgObject(@NotNull String stream, @NotNull String hash) {
            this.stream = stream;
            this.hash = hash;
        }

        @NotNull
        public final String getStream() {
            return this.stream;
        }

        @NotNull
        public final String getHash() {
            return this.hash;
        }
    }

    @NotNull
    public final String runJS2(@NotNull String hideMyHtmlContent) {
        Log.d("runJS", "start");
        Context rhino = Context.enter();
        rhino.initSafeStandardObjects();
        rhino.setOptimizationLevel(-1);
        Scriptable scope = rhino.initSafeStandardObjects();
        scope.put("window", scope, scope);
        String script = '\n' + hideMyHtmlContent + '\n';
        String result = "";
        try {
            try {
                Log.d("runJS", "Executing JavaScript: " + script);
                rhino.evaluateString(scope, script, "JavaScript", 1, (Object) null);
                Object svgObject = scope.get("svg", scope);
                if (svgObject instanceof NativeObject) {
                    result = NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, (Object) null, (Object) null).toString();
                } else {
                    String result2 = Context.toString(svgObject);
                    result = result2;
                }
                Log.d("runJS", "Result: " + result);
            } catch (Exception e) {
                Log.e("runJS", "Error executing JavaScript " + e);
            }
            return result;
        } finally {
            Context.exit();
        }
    }

    private final void handlenet(String iframeUrl, String name, Function1<? super ExtractorLink, Unit> callback) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(iframeUrl).build();
        Response response = client.newCall(request).execute();
        Document result = Jsoup.parse(response.body().string());
        Element elementFirst = result.select("script:containsData(eval)").first();
        String resc = elementFirst != null ? elementFirst.data() : null;
        AppUtils appUtils = AppUtils.INSTANCE;
        Intrinsics.checkNotNull(resc);
        String value$iv = runJS2(resc);
        ObjectMapper $this$readValue$iv$iv = MainAPIKt.getMapper();
        SvgObject jsonStr2 = (SvgObject) $this$readValue$iv$iv.readValue(value$iv, new TypeReference<SvgObject>() { // from class: com.braflix.Braflix$handlenet$$inlined$parseJson$1
        });
        String watchlink = sigDecode(jsonStr2.getStream());
        callback.invoke(new ExtractorLink(getName(), name, watchlink, getMainUrl(), Qualities.Unknown.getValue(), true, (Map) null, (String) null, 192, (DefaultConstructorMarker) null));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    /* JADX WARN: Removed duplicated region for block: B:51:0x019f  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handleVidPro(java.lang.String r27, java.lang.String r28, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r29, kotlin.coroutines.Continuation<? super kotlin.Unit> r30) {
        /*
            Method dump skipped, instruction units count: 512
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleVidPro(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handleGovid(java.lang.String r37, java.lang.String r38, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r39, kotlin.coroutines.Continuation<? super kotlin.Unit> r40) {
        /*
            Method dump skipped, instruction units count: 406
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleGovid(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Failed to restore switch over string. Please report as a decompilation issue */
    /* JADX WARN: Removed duplicated region for block: B:51:0x019f  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handleVidlook(java.lang.String r27, java.lang.String r28, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r29, kotlin.coroutines.Continuation<? super kotlin.Unit> r30) {
        /*
            Method dump skipped, instruction units count: 514
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleVidlook(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:21:0x0106 A[LOOP:1: B:19:0x0100->B:21:0x0106, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0134  */
    /* JADX WARN: Removed duplicated region for block: B:28:0x015a  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x01bc A[LOOP:0: B:33:0x01b6->B:35:0x01bc, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:37:0x01d1  */
    /* JADX WARN: Removed duplicated region for block: B:38:0x01dd  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:25:0x0134 -> B:26:0x0154). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:31:0x019a -> B:32:0x01a8). Please report as a decompilation issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handleStreamwish(java.lang.String r37, java.lang.String r38, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r39, kotlin.coroutines.Continuation<? super kotlin.Unit> r40) {
        /*
            Method dump skipped, instruction units count: 492
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleStreamwish(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
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
    public final java.lang.Object handleStreamfileAndLuluvid(java.lang.String r27, java.lang.String r28, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r29, kotlin.coroutines.Continuation<? super kotlin.Unit> r30) {
        /*
            Method dump skipped, instruction units count: 544
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleStreamfileAndLuluvid(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:21:0x0116 A[LOOP:1: B:19:0x0110->B:21:0x0116, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0144  */
    /* JADX WARN: Removed duplicated region for block: B:28:0x016a  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x01ff A[LOOP:0: B:35:0x01f9->B:37:0x01ff, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:41:0x0259  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x0278  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:25:0x0144 -> B:26:0x0164). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:33:0x01dc -> B:34:0x01eb). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:39:0x020e -> B:40:0x0243). Please report as a decompilation issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handleVadbamAndViidshare(java.lang.String r40, java.lang.String r41, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r42, kotlin.coroutines.Continuation<? super kotlin.Unit> r43) {
        /*
            Method dump skipped, instruction units count: 646
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handleVadbamAndViidshare(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:12:0x0062  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private final com.lagradost.cloudstream3.SearchResponse toSearchResponse(org.jsoup.nodes.Element r33) {
        /*
            Method dump skipped, instruction units count: 350
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.toSearchResponse(org.jsoup.nodes.Element):com.lagradost.cloudstream3.SearchResponse");
    }

    @NotNull
    public final Document decodeHtml(@NotNull Document doc) throws IOException {
        String scriptData;
        String value;
        Integer intOrNull;
        int lastNumber = 0;
        if (!StringsKt.contains$default(doc.toString(), "hide_my_HTML_", false, 2, (Object) null)) {
            Log.d("decodedHtml", "No decoding needed");
            return doc;
        }
        Element script = doc.select("script").first();
        if (script == null || (scriptData = script.data()) == null) {
            return doc;
        }
        String strSubstring = StringsKt.substringAfter$default(scriptData, "var hide_my_HTML_", (String) null, 2, (Object) null).substring(3);
        Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
        String hideMyHtmlContent = new Regex("['+\\n\" ]").replace(StringsKt.trim(StringsKt.substringBeforeLast$default(StringsKt.substringAfter$default(strSubstring, " =", (String) null, 2, (Object) null), "';", (String) null, 2, (Object) null)).toString(), "");
        MatchResult matchResult = (MatchResult) SequencesKt.lastOrNull(Regex.findAll$default(new Regex("-\\d+"), scriptData, 0, 2, (Object) null));
        if (matchResult != null && (value = matchResult.getValue()) != null && (intOrNull = StringsKt.toIntOrNull(value)) != null) {
            lastNumber = intOrNull.intValue();
        }
        String decodedHtml1 = decodeObfuscatedString(hideMyHtmlContent, lastNumber);
        byte[] bytes = decodedHtml1.getBytes(Charsets.ISO_8859_1);
        Intrinsics.checkNotNullExpressionValue(bytes, "getBytes(...)");
        String encodedHtml = new String(bytes, Charsets.UTF_8);
        return Jsoup.parse(encodedHtml);
    }

    @NotNull
    public final String decodeObfuscatedString(@NotNull String concatenated, int lastNumber) throws IOException {
        StringBuilder output = new StringBuilder();
        int start = 0;
        int length = concatenated.length();
        for (int i = 0; i < length; i++) {
            if (concatenated.charAt(i) == '.') {
                String strSubstring = concatenated.substring(start, i);
                Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
                decodeObfuscatedString$decodeAndAppend(output, lastNumber, strSubstring);
                start = i + 1;
            }
        }
        String strSubstring2 = concatenated.substring(start);
        Intrinsics.checkNotNullExpressionValue(strSubstring2, "substring(...)");
        decodeObfuscatedString$decodeAndAppend(output, lastNumber, strSubstring2);
        return output.toString();
    }

    private static final void decodeObfuscatedString$decodeAndAppend(StringBuilder output, int $lastNumber, String segment) throws IOException {
        CharSequence decoded = new String(Base64.getDecoder().decode(segment), Charsets.UTF_8);
        CharSequence $this$filterTo$iv$iv = decoded;
        Appendable destination$iv$iv = new StringBuilder();
        int length = $this$filterTo$iv$iv.length();
        for (int index$iv$iv = 0; index$iv$iv < length; index$iv$iv++) {
            char element$iv$iv = $this$filterTo$iv$iv.charAt(index$iv$iv);
            if (Character.isDigit(element$iv$iv)) {
                destination$iv$iv.append(element$iv$iv);
            }
        }
        String it = ((StringBuilder) destination$iv$iv).toString();
        Intrinsics.checkNotNullExpressionValue(it, "toString(...)");
        if (it.length() > 0) {
            output.append((char) (Integer.parseInt(it) + $lastNumber));
        }
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
    public java.lang.Object getMainPage(int r25, @org.jetbrains.annotations.NotNull com.lagradost.cloudstream3.MainPageRequest r26, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super com.lagradost.cloudstream3.HomePageResponse> r27) throws java.io.IOException {
        /*
            Method dump skipped, instruction units count: 260
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.getMainPage(int, com.lagradost.cloudstream3.MainPageRequest, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object search(@org.jetbrains.annotations.NotNull java.lang.String r24, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super java.util.List<? extends com.lagradost.cloudstream3.SearchResponse>> r25) throws java.io.IOException {
        /*
            Method dump skipped, instruction units count: 246
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.search(java.lang.String, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Removed duplicated region for block: B:24:0x01db  */
    /* JADX WARN: Removed duplicated region for block: B:31:0x021d  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x02c1  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x032d  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x0386  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x03c2  */
    /* JADX WARN: Removed duplicated region for block: B:69:0x048f  */
    /* JADX WARN: Removed duplicated region for block: B:79:0x0545  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /* JADX WARN: Removed duplicated region for block: B:84:0x05d5  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:75:0x050f -> B:76:0x051f). Please report as a decompilation issue!!! */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object load(@org.jetbrains.annotations.NotNull java.lang.String r55, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super com.lagradost.cloudstream3.LoadResponse> r56) throws java.io.IOException {
        /*
            Method dump skipped, instruction units count: 1618
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.load(java.lang.String, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$load$3, reason: invalid class name */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\u008a@"}, d2 = {"<anonymous>", "", "Lcom/lagradost/cloudstream3/MovieLoadResponse;"}, k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix$load$3", f = "BraflixProvider.kt", i = {}, l = {}, m = "invokeSuspend", n = {}, s = {})
    static final class AnonymousClass3 extends SuspendLambda implements Function2<MovieLoadResponse, Continuation<? super Unit>, Object> {
        final /* synthetic */ List<ActorData> $actors;
        final /* synthetic */ String $posterUrl;
        final /* synthetic */ List<SearchResponse> $recommendations;
        final /* synthetic */ String $synopsis;
        final /* synthetic */ List<String> $tags;
        final /* synthetic */ Integer $year;
        private /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass3(String str, Integer num, String str2, List<String> list, List<? extends SearchResponse> list2, List<ActorData> list3, Continuation<? super AnonymousClass3> continuation) {
            super(2, continuation);
            this.$posterUrl = str;
            this.$year = num;
            this.$synopsis = str2;
            this.$tags = list;
            this.$recommendations = list2;
            this.$actors = list3;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> anonymousClass3 = new AnonymousClass3(this.$posterUrl, this.$year, this.$synopsis, this.$tags, this.$recommendations, this.$actors, continuation);
            anonymousClass3.L$0 = obj;
            return anonymousClass3;
        }

        @Nullable
        public final Object invoke(@NotNull MovieLoadResponse movieLoadResponse, @Nullable Continuation<? super Unit> continuation) {
            return create(movieLoadResponse, continuation).invokeSuspend(Unit.INSTANCE);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure(obj);
                    MovieLoadResponse $this$newMovieLoadResponse = (MovieLoadResponse) this.L$0;
                    $this$newMovieLoadResponse.setPosterUrl(this.$posterUrl);
                    $this$newMovieLoadResponse.setYear(this.$year);
                    $this$newMovieLoadResponse.setPlot(this.$synopsis);
                    $this$newMovieLoadResponse.setTags(this.$tags);
                    $this$newMovieLoadResponse.setRecommendations(this.$recommendations);
                    $this$newMovieLoadResponse.setActors(this.$actors);
                    return Unit.INSTANCE;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
        }
    }

    /* JADX INFO: renamed from: com.braflix.Braflix$load$6, reason: invalid class name */
    /* JADX INFO: compiled from: BraflixProvider.kt */
    @Metadata(d1 = {"\u0000\n\n\u0000\n\u0002\u0010\u0002\n\u0002\u0018\u0002\u0010\u0000\u001a\u00020\u0001*\u00020\u0002H\u008a@"}, d2 = {"<anonymous>", "", "Lcom/lagradost/cloudstream3/TvSeriesLoadResponse;"}, k = 3, mv = {1, 9, 0}, xi = 48)
    @DebugMetadata(c = "com.braflix.Braflix$load$6", f = "BraflixProvider.kt", i = {}, l = {}, m = "invokeSuspend", n = {}, s = {})
    static final class AnonymousClass6 extends SuspendLambda implements Function2<TvSeriesLoadResponse, Continuation<? super Unit>, Object> {
        final /* synthetic */ List<ActorData> $actors;
        final /* synthetic */ String $posterUrl;
        final /* synthetic */ List<SearchResponse> $recommendations;
        final /* synthetic */ String $synopsis;
        final /* synthetic */ List<String> $tags;
        final /* synthetic */ Integer $year;
        private /* synthetic */ Object L$0;
        int label;

        /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
        AnonymousClass6(String str, Integer num, String str2, List<String> list, List<? extends SearchResponse> list2, List<ActorData> list3, Continuation<? super AnonymousClass6> continuation) {
            super(2, continuation);
            this.$posterUrl = str;
            this.$year = num;
            this.$synopsis = str2;
            this.$tags = list;
            this.$recommendations = list2;
            this.$actors = list3;
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            Continuation<Unit> anonymousClass6 = new AnonymousClass6(this.$posterUrl, this.$year, this.$synopsis, this.$tags, this.$recommendations, this.$actors, continuation);
            anonymousClass6.L$0 = obj;
            return anonymousClass6;
        }

        @Nullable
        public final Object invoke(@NotNull TvSeriesLoadResponse tvSeriesLoadResponse, @Nullable Continuation<? super Unit> continuation) {
            return create(tvSeriesLoadResponse, continuation).invokeSuspend(Unit.INSTANCE);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            IntrinsicsKt.getCOROUTINE_SUSPENDED();
            switch (this.label) {
                case 0:
                    ResultKt.throwOnFailure(obj);
                    TvSeriesLoadResponse $this$newTvSeriesLoadResponse = (TvSeriesLoadResponse) this.L$0;
                    $this$newTvSeriesLoadResponse.setPosterUrl(this.$posterUrl);
                    $this$newTvSeriesLoadResponse.setYear(this.$year);
                    $this$newTvSeriesLoadResponse.setPlot(this.$synopsis);
                    $this$newTvSeriesLoadResponse.setTags(this.$tags);
                    $this$newTvSeriesLoadResponse.setRecommendations(this.$recommendations);
                    $this$newTvSeriesLoadResponse.setActors(this.$actors);
                    return Unit.INSTANCE;
                default:
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:29:0x011a A[Catch: Exception -> 0x017f, TryCatch #0 {Exception -> 0x017f, blocks: (B:26:0x0102, B:27:0x0114, B:29:0x011a, B:31:0x0137, B:33:0x0144, B:32:0x013d), top: B:45:0x0102 }] */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0019  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final java.lang.Object handlecima(java.lang.String r34, java.lang.String r35, kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r36, kotlin.coroutines.Continuation<? super kotlin.Unit> r37) {
        /*
            Method dump skipped, instruction units count: 426
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.handlecima(java.lang.String, java.lang.String, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }

    /* JADX WARN: Path cross not found for [B:90:0x051f, B:93:0x052f], limit reached: 323 */
    /* JADX WARN: Removed duplicated region for block: B:107:0x05a6  */
    /* JADX WARN: Removed duplicated region for block: B:251:0x0b3b  */
    /* JADX WARN: Removed duplicated region for block: B:309:0x056f A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:329:0x0565 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0021  */
    /* JADX WARN: Removed duplicated region for block: B:80:0x03fb A[Catch: Exception -> 0x0b40, TryCatch #19 {Exception -> 0x0b40, blocks: (B:78:0x03f5, B:80:0x03fb, B:84:0x049c, B:86:0x04ef, B:88:0x0510, B:90:0x051f, B:109:0x05aa, B:120:0x0620, B:130:0x0693, B:140:0x0705, B:142:0x0712, B:145:0x0724, B:155:0x0796, B:165:0x0809, B:167:0x0816, B:170:0x0828, B:172:0x0835, B:175:0x0847, B:177:0x0854, B:164:0x07e4, B:154:0x0771, B:139:0x06e0, B:129:0x066e, B:118:0x05f9, B:105:0x0583, B:93:0x052f, B:94:0x0533, B:96:0x0539, B:77:0x03d2, B:73:0x03c4, B:102:0x056f), top: B:297:0x03c4, inners: #25 }] */
    /* JADX WARN: Removed duplicated region for block: B:86:0x04ef A[Catch: Exception -> 0x0b40, TryCatch #19 {Exception -> 0x0b40, blocks: (B:78:0x03f5, B:80:0x03fb, B:84:0x049c, B:86:0x04ef, B:88:0x0510, B:90:0x051f, B:109:0x05aa, B:120:0x0620, B:130:0x0693, B:140:0x0705, B:142:0x0712, B:145:0x0724, B:155:0x0796, B:165:0x0809, B:167:0x0816, B:170:0x0828, B:172:0x0835, B:175:0x0847, B:177:0x0854, B:164:0x07e4, B:154:0x0771, B:139:0x06e0, B:129:0x066e, B:118:0x05f9, B:105:0x0583, B:93:0x052f, B:94:0x0533, B:96:0x0539, B:77:0x03d2, B:73:0x03c4, B:102:0x056f), top: B:297:0x03c4, inners: #25 }] */
    /* JADX WARN: Removed duplicated region for block: B:87:0x050d  */
    /* JADX WARN: Removed duplicated region for block: B:96:0x0539 A[Catch: Exception -> 0x0b40, TRY_LEAVE, TryCatch #19 {Exception -> 0x0b40, blocks: (B:78:0x03f5, B:80:0x03fb, B:84:0x049c, B:86:0x04ef, B:88:0x0510, B:90:0x051f, B:109:0x05aa, B:120:0x0620, B:130:0x0693, B:140:0x0705, B:142:0x0712, B:145:0x0724, B:155:0x0796, B:165:0x0809, B:167:0x0816, B:170:0x0828, B:172:0x0835, B:175:0x0847, B:177:0x0854, B:164:0x07e4, B:154:0x0771, B:139:0x06e0, B:129:0x066e, B:118:0x05f9, B:105:0x0583, B:93:0x052f, B:94:0x0533, B:96:0x0539, B:77:0x03d2, B:73:0x03c4, B:102:0x056f), top: B:297:0x03c4, inners: #25 }] */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:197:0x0932 -> B:198:0x093d). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:206:0x0972 -> B:250:0x0b2d). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:217:0x09df -> B:250:0x0b2d). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:228:0x0a4c -> B:250:0x0b2d). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:239:0x0ab9 -> B:250:0x0b2d). Please report as a decompilation issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:249:0x0b20 -> B:250:0x0b2d). Please report as a decompilation issue!!! */
    @org.jetbrains.annotations.Nullable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.Object loadLinks(@org.jetbrains.annotations.NotNull java.lang.String r51, boolean r52, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.SubtitleFile, kotlin.Unit> r53, @org.jetbrains.annotations.NotNull kotlin.jvm.functions.Function1<? super com.lagradost.cloudstream3.utils.ExtractorLink, kotlin.Unit> r54, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation<? super java.lang.Boolean> r55) {
        /*
            Method dump skipped, instruction units count: 2950
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.braflix.Braflix.loadLinks(java.lang.String, boolean, kotlin.jvm.functions.Function1, kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation):java.lang.Object");
    }
}
