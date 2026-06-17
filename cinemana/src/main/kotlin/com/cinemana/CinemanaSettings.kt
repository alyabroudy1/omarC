package com.cinemana

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CinemanaSettings : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {
        private val TAG = "CinemanaSettings"

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            fun createSwitch(title: String, key: String, default: Boolean): SwitchPreferenceCompat {
                val pref = SwitchPreferenceCompat(context)
                pref.key = key
                pref.title = title
                pref.setDefaultValue(default)
                pref.setOnPreferenceChangeListener { preference, newValue ->

                    true
                }
                return pref
            }

            val generalCat = PreferenceCategory(context).apply { title = "عام" }
            screen.addPreference(generalCat)
            generalCat.addPreference(createSwitch("أحدث الإضافات", "cine_newly_added", true))

            val moviesCat = PreferenceCategory(context).apply { title = "الأفلام" }
            screen.addPreference(moviesCat)

            val movieSwitches = listOf(
                Triple("أفلام - تاريخ الرفع - الأحدث", "cine_mov_upload_desc", true),
                Triple("أفلام - تاريخ الرفع - الأقدم", "cine_mov_upload_asc", false),
                Triple("أفلام - تاريخ الإصدار - الأحدث", "cine_mov_release_desc", false),
                Triple("أفلام - تاريخ الإصدار - الأقدم", "cine_mov_release_asc", false),
                Triple("أفلام - الأكثر مشاهدة", "cine_mov_views_desc", true),
                Triple("أفلام - الأقل مشاهدة", "cine_mov_views_asc", false),
                Triple("أفلام - أعلى تقييم IMDb", "cine_mov_stars_desc", true),
                Triple("أفلام - أقل تقييم IMDb", "cine_mov_stars_asc", false),
                Triple("أفلام - أبجديًا (أ-ي)", "cine_mov_ar_asc", false),
                Triple("أفلام - أبجديًا (ب-أ)", "cine_mov_ar_desc", false),
                Triple("أفلام - أبجديًا (A-Z)", "cine_mov_en_asc", false),
                Triple("أفلام - أبجديًا (Z-A)", "cine_mov_en_desc", false)
            )

            movieSwitches.forEach { (title, key, def) ->
                moviesCat.addPreference(createSwitch(title, key, def))
            }

            val seriesCat = PreferenceCategory(context).apply { title = "المسلسلات" }
            screen.addPreference(seriesCat)

            val seriesSwitches = listOf(
                Triple("مسلسلات - تاريخ الرفع - الأحدث", "cine_ser_upload_desc", true),
                Triple("مسلسلات - تاريخ الرفع - الأقدم", "cine_ser_upload_asc", false),
                Triple("مسلسلات - تاريخ الإصدار - الأحدث", "cine_ser_release_desc", false),
                Triple("مسلسلات - تاريخ الإصدار - الأقدم", "cine_ser_release_asc", false),
                Triple("مسلسلات - الأكثر مشاهدة", "cine_ser_views_desc", true),
                Triple("مسلسلات - الأقل مشاهدة", "cine_ser_views_asc", false),
                Triple("مسلسلات - أعلى تقييم IMDb", "cine_ser_stars_desc", true),
                Triple("مسلسلات - أقل تقييم IMDb", "cine_ser_stars_asc", false),
                Triple("مسلسلات - أبجديًا (أ-ي)", "cine_ser_ar_asc", false),
                Triple("مسلسلات - أبجديًا (ي-أ)", "cine_ser_ar_desc", false),
                Triple("مسلسلات - أبجديًا (A-Z)", "cine_ser_en_asc", false),
                Triple("مسلسلات - أبجديًا (Z-A)", "cine_ser_en_desc", false)
            )

            seriesSwitches.forEach { (title, key, def) ->
                seriesCat.addPreference(createSwitch(title, key, def))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction().replace(view.id, PrefsFragment()).commit()
    }
}