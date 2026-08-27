package com.apkbuilder.studio.ui

import android.view.View
import android.view.animation.AnimationUtils
import com.apkbuilder.studio.R

/**
 * Utility class for applying press/release animations to views.
 * Usage: ButtonAnimation.applyTo(view)
 */
object ButtonAnimation {

    fun applyTo(view: View) {
        val pressAnim = AnimationUtils.loadAnimation(view.context, R.anim.button_press)
        val releaseAnim = AnimationUtils.loadAnimation(view.context, R.anim.button_release)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(pressAnim)
                    v.alpha = 0.85f
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.startAnimation(releaseAnim)
                    v.alpha = 1.0f
                    false
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.startAnimation(releaseAnim)
                    v.alpha = 1.0f
                    false
                }
                else -> false
            }
        }
    }

    fun applyToCard(view: View) {
        val pressAnim = AnimationUtils.loadAnimation(view.context, R.anim.card_press)
        val releaseAnim = AnimationUtils.loadAnimation(view.context, R.anim.card_release)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(pressAnim)
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.startAnimation(releaseAnim)
                    false
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.startAnimation(releaseAnim)
                    false
                }
                else -> false
            }
        }
    }
}
