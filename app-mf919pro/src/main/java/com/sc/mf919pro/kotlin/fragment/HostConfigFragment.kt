package com.sc.mf919pro.kotlin.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentHostConfigBinding
import com.sc.mf919pro.java.activity.ParameterValueEditor
import com.sc.mf919pro.kotlin.database.model.DbModelMerchantConfig
import com.sc.mf919pro.kotlin.database.repo.IsoBatchInfoRepo
import com.sc.mf919pro.kotlin.database.repo.MerchantConfigurationRepo
import com.sc.mf919pro.kotlin.helper_common.ServiceHolder
import enums.EnumLogFileName
import helpers.HelperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HostConfigFragment : BaseFragment() {
    lateinit var helperLog: HelperLog
    private val hostEditors = mutableListOf<ParameterValueEditor>()
    var hostInfo: DbModelMerchantConfig? = null
    private var tagHostInfo = arrayOf(
        "PrimaryHostIp","PrimaryHostPort","SecondaryHostIp","SecondaryHostPort","TPDU","NII","HostTimeoutMs"
    )

    private var rl: LinearLayout? = null

    private var _binding: FragmentHostConfigBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHostConfigBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Final boundary for this screen. Everything appended since the last flush is only
        // in the buffer until now, and a fragment can be torn down at any point (back-press,
        // navigation, process pressure). helperLog is a lateinit built in onViewCreated, so
        // guard it -- onDestroyView can run on paths where that never happened.
        if (this::helperLog.isInitialized) {
            helperLog.appendLine(this::class.simpleName.toString(), "HostConfig OnDestroyView :: screen ended")
            helperLog.logToFile(EnumLogFileName.TerminaLog)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = binding.toolbarHostCfg
        toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { customOnBackPress() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    customOnBackPress()
                }
            })

        rl = binding.hostConfigField
        hostInfo = ServiceHolder.getMerchantInfo()
        formLayout()
    }

    private fun formLayout() {
        rl!!.removeAllViews()
        hostEditors.clear()
        for (j in tagHostInfo.indices) {
            val pd = ParameterValueEditor(
                requireActivity(),
                j + 1,
                tagHostInfo[j],
                DbModelMerchantConfig.getSafeValue(hostInfo, tagHostInfo[j])
            )

            hostEditors.add(pd)
            rl?.addView(pd.view)
        }
    }

    fun customOnBackPress() {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            save(ctx)
        }
        findNavController().popBackStack()
    }

    private fun save(ctx: Context) {
        val updateMap = mutableMapOf<Any,Any>()
        hostEditors.forEach {
            updateMap[it.tag] = it.value
            when (it.tag) {
                "TPDU" -> {
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeader", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeader", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeaderTle", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "isoTpduHeaderTle", "mccs")
                }
                "NII" -> {
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "nii", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "nii", "mccs")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "niiTle", "visam")
                    IsoBatchInfoRepo.updateBatchInfo(ctx, DbModelMerchantConfig.setSafeValue(it.value), "niiTle", "mccs")
                }
            }
        }

        MerchantConfigurationRepo.updateMerchantConfig(ctx, updateMap)
        ServiceHolder.clearMerchantInformation()
    }
}