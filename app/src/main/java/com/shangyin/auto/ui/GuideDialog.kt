package com.shangyin.auto.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.shangyin.auto.databinding.DialogGuideBinding

/**
 * 使用说明弹窗：首次启动自动弹出一次，之后可从主界面「使用说明」再次打开。
 */
object GuideDialog {
    private const val PREF = "shangyin_prefs"
    private const val KEY_AUTO_SHOWN = "guide_shown_v200"

    /** 首次启动自动展示（只弹一次）。 */
    @JvmStatic
    fun showIfFirstLaunch(activity: Activity) {
        val sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_AUTO_SHOWN, false)) return
        sp.edit().putBoolean(KEY_AUTO_SHOWN, true).apply()
        show(activity)
    }

    /** 立即展示使用说明。 */
    @JvmStatic
    fun show(activity: Activity) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        val binding = DialogGuideBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity).setView(binding.root).create()
        dialog.setCanceledOnTouchOutside(true)
        binding.btnGuideClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
