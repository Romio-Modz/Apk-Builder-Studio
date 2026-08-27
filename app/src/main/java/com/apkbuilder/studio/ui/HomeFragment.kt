package com.apkbuilder.studio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.apkbuilder.studio.R
import com.apkbuilder.studio.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ButtonAnimation.applyToCard(binding.cardNewBuild)
        ButtonAnimation.applyToCard(binding.cardHistory)

        binding.cardNewBuild.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_build)
        }

        binding.cardHistory.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_history)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.jobs.collect { jobs ->
                    val total = jobs.size
                    val success = jobs.count { it.status.name == "SUCCESS" }
                    binding.tvTotalBuilds.text = total.toString()
                    binding.tvSuccessBuilds.text = success.toString()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
