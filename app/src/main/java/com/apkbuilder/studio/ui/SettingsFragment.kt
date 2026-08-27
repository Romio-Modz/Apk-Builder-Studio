package com.apkbuilder.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.apkbuilder.studio.R
import com.apkbuilder.studio.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("apk_builder_prefs", android.content.Context.MODE_PRIVATE)
        val currentMode = prefs.getInt("theme_mode", 0)

        binding.themeModeGroup.check(when(currentMode) {
            1 -> R.id.rbLight
            2 -> R.id.rbNight
            else -> R.id.rbDefault
        })

        binding.themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when(checkedId) {
                R.id.rbLight -> 1
                R.id.rbNight -> 2
                else -> 0
            }
            prefs.edit().putInt("theme_mode", mode).apply()
            when (mode) {
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        binding.btnTelegram.setOnClickListener {
            openTelegram()
        }

        binding.footerTelegram.setOnClickListener {
            openTelegram()
        }

        binding.aboutCard.setOnClickListener {
            if (binding.aboutContent.visibility == View.VISIBLE) {
                binding.aboutContent.visibility = View.GONE
                binding.aboutArrow.rotation = 0f
            } else {
                binding.aboutContent.visibility = View.VISIBLE
                binding.aboutArrow.rotation = 180f
            }
        }
    }

    private fun openTelegram() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/romio_modz"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
