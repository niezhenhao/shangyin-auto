package com.shangyin.auto.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.shangyin.auto.databinding.DialogGuideBinding
import com.shangyin.auto.util.UpdateChecker

/**
 * 使用说明弹窗：首次启动自动弹出一次，之后可从主界面「使用说明」再次打开。
 * 关闭后追加一次软件更新检测（短时缓存，网络失败静默跳过）。
 */
object GuideDialog {
    private const val PREF = "shangyin_prefs"
    private const val KEY_AUTO_SHOWN = "guide_shown_v200"

    @Volatile
    private var showing = false

    /** 使用说明弹窗是否正在显示：启动自动更新检测据此避让，避免两层弹窗叠加。 */
    @JvmStatic
    fun isShowing(): Boolean = showing

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
        // 说明弹窗关闭后再做更新检测：不影响首次使用，失败也不打扰
        dialog.setOnDismissListener {
            showing = false
            try {
                UpdateChecker.checkAfterGuide(activity)
            } catch (e: Exception) {
                // 静默忽略：更新检测永远不能影响正常使用
            }
        }
        showing = true
        dialog.show()
    }
}
