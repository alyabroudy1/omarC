package com.egydead;

import android.content.Context;
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin;
import com.lagradost.cloudstream3.plugins.Plugin;
import kotlin.Metadata;
import org.jetbrains.annotations.NotNull;

/* JADX INFO: compiled from: EgyDeadPlugin.kt */
/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/streamPre-4.6/omarC/egydead_decompiled/classes.dex */
@CloudstreamPlugin
@Metadata(d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u0007\u0018\u00002\u00020\u0001B\u0005¢\u0006\u0002\u0010\u0002J\u0010\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u0006H\u0016¨\u0006\u0007"}, d2 = {"Lcom/egydead/EgyDeadPlugin;", "Lcom/lagradost/cloudstream3/plugins/Plugin;", "()V", "load", "", "context", "Landroid/content/Context;", "EgyDeadProvider_debug"}, k = 1, mv = {1, 7, 1}, xi = 48)
public final class EgyDeadPlugin extends Plugin {
    public void load(@NotNull Context context) {
        registerMainAPI(new EgyDead());
    }
}
