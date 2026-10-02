package com.sbai.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.sbai.R
import com.sbai.models.*
import com.sbai.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var loadBalanceManager: LoadBalanceManager
    private var showLoadBalancer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadBalanceManager = (requireActivity().applicationContext as com.sbai.SbAiApp).loadBalanceManager
        loadBalanceManager.load()
    }

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

        binding.switchLoadBalancer.setOnCheckedChangeListener { _, isChecked ->
            showLoadBalancer = isChecked
            binding.loadBalancerSection.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) updateLoadBalancerList()
        }

        binding.fabAddLb.setOnClickListener {
            showAddLbDialog()
        }

        updateVersionInfo()
    }

    private fun updateVersionInfo() {
        try {
            val versionName = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            binding.tvVersion.text = "版本: $versionName"
        } catch (e: Exception) {
            binding.tvVersion.text = "版本: 1.0.0"
        }
    }

    private fun updateLoadBalancerList() {
        val adapter = object : BaseAdapter() {
            override fun getCount() = loadBalanceManager.rules.size
            override fun getItem(position: Int) = loadBalanceManager.rules[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(parent?.context)
                    .inflate(R.layout.item_lb_rule, parent, false)
                val rule = loadBalanceManager.rules[position]
                view.findViewById<MaterialCheckBox>(R.id.cb_enabled).isChecked = rule.enabled
                view.findViewById<TextView>(R.id.tv_remarks).text = rule.remarks.ifEmpty { "未命名规则" }
                view.findViewById<TextView>(R.id.tv_mode).text = "模式: ${rule.mode.label}"
                view.findViewById<TextView>(R.id.tv_nodes).text = "节点数: ${rule.outbounds.size}"
                view.setOnClickListener { showEditLbDialog(rule) }
                return view
            }
        }
        binding.recyclerViewLb.adapter = adapter as RecyclerView.Adapter<*>
        binding.recyclerViewLb.layoutManager = LinearLayoutManager(requireContext())
        binding.emptyLbView.visibility = if (loadBalanceManager.rules.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerViewLb.visibility = if (loadBalanceManager.rules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddLbDialog() {
        val dialog = LbRuleEditorDialog()
        dialog.setOnSaveListener { rule ->
            loadBalanceManager.addRule(rule)
            updateLoadBalancerList()
        }
        dialog.show(parentFragmentManager, "add_lb_rule")
    }

    private fun showEditLbDialog(rule: LoadBalanceRule) {
        val dialog = LbRuleEditorDialog(rule)
        dialog.setOnSaveListener { updatedRule ->
            loadBalanceManager.updateRule(updatedRule)
            updateLoadBalancerList()
        }
        dialog.show(parentFragmentManager, "edit_lb_rule")
    }

    override fun onResume() {
        super.onResume()
        loadBalanceManager.load()
        if (showLoadBalancer) updateLoadBalancerList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 负载均衡规则编辑器对话框
 */
class LbRuleEditorDialog(private val editingRule: LoadBalanceRule? = null) : BottomSheetDialogFragment() {

    private lateinit var onSaveListener: (LoadBalanceRule) -> Unit

    fun setOnSaveListener(listener: (LoadBalanceRule) -> Unit) {
        this.onSaveListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_lb_rule_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val remarksInput = view.findViewById<TextInputEditText>(R.id.et_remarks)
        val modeRadioGroup = view.findViewById<android.widget.RadioGroup>(R.id.radio_group_mode)
        val urlInput = view.findViewById<TextInputEditText>(R.id.et_url)
        val intervalInput = view.findViewById<TextInputEditText>(R.id.et_interval)
        val toleranceInput = view.findViewById<TextInputEditText>(R.id.et_tolerance)
        val stickyHashInput = view.findViewById<TextInputEditText>(R.id.et_sticky_hash)
        val poolSizeInput = view.findViewById<TextInputEditText>(R.id.et_pool_size)
        val poolToleranceInput = view.findViewById<TextInputEditText>(R.id.et_pool_tolerance)
        val fallbackDirectSwitch = view.findViewById<SwitchMaterial>(R.id.switch_fallback_direct)
        val fallbackDefaultSwitch = view.findViewById<SwitchMaterial>(R.id.switch_fallback_default)

        // 设置初始值
        editingRule?.let { rule ->
            remarksInput.setText(rule.remarks)
            when (rule.mode) {
                LoadBalanceMode.ROUND_ROBIN -> modeRadioGroup.check(R.id.radio_round_robin)
                LoadBalanceMode.CONSISTENT_HASH -> modeRadioGroup.check(R.id.radio_consistent_hash)
                LoadBalanceMode.RANDOM -> modeRadioGroup.check(R.id.radio_random)
                LoadBalanceMode.PASSIVE_CHECK -> modeRadioGroup.check(R.id.radio_passive_check)
                LoadBalanceMode.URL_TEST -> modeRadioGroup.check(R.id.radio_url_test)
                LoadBalanceMode.AUTO -> modeRadioGroup.check(R.id.radio_auto)
            }
            urlInput.setText(rule.url ?: "")
            intervalInput.setText(rule.interval.toString())
            toleranceInput.setText(rule.tolerance.toString())
            stickyHashInput.setText(rule.stickyHash ?: "")
            poolSizeInput.setText(rule.poolSize.toString())
            poolToleranceInput.setText(rule.poolTolerance.toString())
            fallbackDirectSwitch.isChecked = rule.fallbackToDirect
            fallbackDefaultSwitch.isChecked = rule.fallbackToDefault
        }

        // 根据模式显示/隐藏参数
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId: Int ->
            val isUrlTest = checkedId == R.id.radio_url_test || checkedId == R.id.radio_passive_check
            val isConsistentHash = checkedId == R.id.radio_consistent_hash
            val groupUrlParams = view.findViewById<View>(R.id.group_url_params)
            val groupStickyHash = view.findViewById<View>(R.id.group_sticky_hash)
            val groupPoolParams = view.findViewById<View>(R.id.group_pool_params)
            
            groupUrlParams?.visibility = if (isUrlTest) View.VISIBLE else View.GONE
            groupStickyHash?.visibility = if (isConsistentHash) View.VISIBLE else View.GONE
            groupPoolParams?.visibility = if (isUrlTest) View.VISIBLE else View.GONE
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { _ : View ->
            val selectedMode = when (modeRadioGroup.checkedRadioButtonId) {
                R.id.radio_round_robin -> LoadBalanceMode.ROUND_ROBIN
                R.id.radio_consistent_hash -> LoadBalanceMode.CONSISTENT_HASH
                R.id.radio_random -> LoadBalanceMode.RANDOM
                R.id.radio_passive_check -> LoadBalanceMode.PASSIVE_CHECK
                R.id.radio_url_test -> LoadBalanceMode.URL_TEST
                R.id.radio_auto -> LoadBalanceMode.AUTO
                else -> LoadBalanceMode.ROUND_ROBIN
            }
            val interval = intervalInput.text?.toString()?.toIntOrNull() ?: 30
            val tolerance = toleranceInput.text?.toString()?.toIntOrNull() ?: 50
            val poolSize = poolSizeInput.text?.toString()?.toIntOrNull() ?: 3
            val poolTolerance = poolToleranceInput.text?.toString()?.toIntOrNull() ?: 50

            val rule = LoadBalanceRule(
                id = editingRule?.id ?: 0,
                remarks = remarksInput.text?.toString() ?: "",
                mode = selectedMode,
                url = urlInput.text?.toString()?.takeIf { it.isNotBlank() },
                interval = interval,
                tolerance = tolerance,
                stickyHash = stickyHashInput.text?.toString()?.takeIf { it.isNotBlank() },
                poolSize = poolSize,
                poolTolerance = poolTolerance,
                fallbackToDirect = fallbackDirectSwitch.isChecked,
                fallbackToDefault = fallbackDefaultSwitch.isChecked
            )
            onSaveListener(rule)
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }
    }
}
