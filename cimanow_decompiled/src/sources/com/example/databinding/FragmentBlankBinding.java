package com.example.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import com.example.R;

/* JADX INFO: loaded from: /Users/mohammad/AndroidStudioProjects/streamPre-4.6/omarC/cimanow_decompiled/classes.dex */
public final class FragmentBlankBinding implements ViewBinding {

    @NonNull
    public final ImageView imageView;

    @NonNull
    public final ImageView imageView2;

    @NonNull
    public final LinearLayout mainFragmentLayout;

    @NonNull
    private final LinearLayout rootView;

    @NonNull
    public final TextView textView;

    @NonNull
    public final TextView textView2;

    private FragmentBlankBinding(@NonNull LinearLayout rootView, @NonNull ImageView imageView, @NonNull ImageView imageView2, @NonNull LinearLayout mainFragmentLayout, @NonNull TextView textView, @NonNull TextView textView2) {
        this.rootView = rootView;
        this.imageView = imageView;
        this.imageView2 = imageView2;
        this.mainFragmentLayout = mainFragmentLayout;
        this.textView = textView;
        this.textView2 = textView2;
    }

    @NonNull
    public LinearLayout getRoot() {
        return this.rootView;
    }

    @NonNull
    public static FragmentBlankBinding inflate(@NonNull LayoutInflater inflater) {
        return inflate(inflater, null, false);
    }

    @NonNull
    public static FragmentBlankBinding inflate(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, boolean attachToParent) {
        View root = inflater.inflate(R.layout.fragment_blank, parent, false);
        if (attachToParent) {
            parent.addView(root);
        }
        return bind(root);
    }

    @NonNull
    public static FragmentBlankBinding bind(@NonNull View rootView) {
        int id = R.id.imageView;
        ImageView imageView = (ImageView) ViewBindings.findChildViewById(rootView, id);
        if (imageView != null) {
            id = R.id.imageView2;
            ImageView imageView2 = (ImageView) ViewBindings.findChildViewById(rootView, id);
            if (imageView2 != null) {
                LinearLayout mainFragmentLayout = (LinearLayout) rootView;
                id = R.id.textView;
                TextView textView = (TextView) ViewBindings.findChildViewById(rootView, id);
                if (textView != null) {
                    id = R.id.textView2;
                    TextView textView2 = (TextView) ViewBindings.findChildViewById(rootView, id);
                    if (textView2 != null) {
                        return new FragmentBlankBinding((LinearLayout) rootView, imageView, imageView2, mainFragmentLayout, textView, textView2);
                    }
                }
            }
        }
        String missingId = rootView.getResources().getResourceName(id);
        throw new NullPointerException("Missing required view with ID: ".concat(missingId));
    }
}
