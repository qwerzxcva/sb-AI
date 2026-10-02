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
import com.sbai.databinding.FragmentRoutingBinding

class RoutingFragment : Fragment() {

    private var _binding: FragmentRoutingBinding? = null
    private val binding get() = _binding!!

    private lateinit var ruleManager: RouteRuleManager
    private lateinit var adapter: RouteRuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ruleManager = (requireActivity().applicationContext as com.sbai.SbAiApp).routeRuleManager
        ruleManager.load()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoutingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RouteRuleAdapter(ruleManager.rules) { rule ->
            showEditRuleDialog(rule)
        }
        binding.recyclerView.adapter = adapter as RecyclerView.Adapter<*>
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.fabAdd.setOnClickListener {
            showEditRuleDialog(null)
        }

        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        ruleManager.load()
        adapter.updateData(ruleManager.rules)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        binding.emptyView.visibility = if (ruleManager.rules.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (ruleManager.rules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditRuleDialog(rule: RouteRule?) {
        val dialog = RuleEditorBottomSheet()
        dialog.setRule(rule)
        dialog.setOnSaveListener { savedRule ->
            if (rule == null) {
                ruleManager.addRule(savedRule)
            } else {
                ruleManager.updateRule(savedRule)
            }
            adapter.updateData(ruleManager.rules)
            updateEmptyState()
        }
        dialog.show(parentFragmentManager, "rule_editor")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 路由规则适配器
 */
class RouteRuleAdapter(
    private var rules: List<RouteRule>,
    private val onItemClick: (RouteRule) -> Unit
) : BaseAdapter() {
    fun updateData(newRules: List<RouteRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    override fun getCount() = rules.size
    override fun getItem(position: Int) = rules[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_route_rule, parent, false)
        val rule = rules[position]
        view.findViewById<MaterialCheckBox>(R.id.cb_enabled).isChecked = rule.enabled
        view.findViewById<TextView>(R.id.tv_remarks).text = rule.remarks.ifEmpty { "未命名规则" }
        view.findViewById<TextView>(R.id.tv_summary).text = buildRuleSummary(rule)
        view.setOnClickListener { onItemClick(rule) }
        return view
    }

    private fun buildRuleSummary(rule: RouteRule): String {
        val parts = mutableListOf<String>()
        if (rule.networks == listOf("all")) {
            parts.add("网络: 全部")
        } else {
            parts.add("网络: ${rule.networks.joinToString()}")
        }
        if (rule.protocols == listOf("all")) {
            parts.add("协议: 全部")
        } else {
            parts.add("协议: ${rule.protocols.joinToString()}")
        }
        if (rule.logicalMode != LogicalMode.NONE.value) {
            parts.add("逻辑: ${LogicalMode.fromValue(rule.logicalMode).label}")
        }
        if (rule.invert) parts.add("反转")
        parts.add("动作: ${RouteAction.fromValue(rule.action).label}")
        if (rule.action == RouteAction.ROUTE.value && rule.outbound.isNotEmpty()) {
            parts.add("→ ${rule.outbound}")
        }
        if (rule.dnsTag != null) parts.add("DNS: ${rule.dnsTag}")
        if (rule.ruleSetTag != null) parts.add("规则集: ${rule.ruleSetTag}")
        return parts.joinToString(" | ")
    }
}

/**
 * 路由规则编辑器底部抽屉
 */
class RuleEditorBottomSheet : BottomSheetDialogFragment() {

    private var editingRule: RouteRule? = null
    private lateinit var onSaveListener: (RouteRule) -> Unit

    fun setRule(rule: RouteRule?) {
        this.editingRule = rule
    }

    fun setOnSaveListener(listener: (RouteRule) -> Unit) {
        this.onSaveListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_rule_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val remarksInput = view.findViewById<TextInputEditText>(R.id.et_remarks)
        val domainsInput = view.findViewById<TextInputEditText>(R.id.et_domains)
        val ipsInput = view.findViewById<TextInputEditText>(R.id.et_ips)
        val ipCidrsInput = view.findViewById<TextInputEditText>(R.id.et_ip_cidrs)
        val downloadDomainInput = view.findViewById<TextInputEditText>(R.id.et_download_domain)
        val networkChipGroup = view.findViewById<ChipGroup>(R.id.chip_group_network)
        val protocolChipGroup = view.findViewById<ChipGroup>(R.id.chip_group_protocol)
        val logicalModeRadioGroup = view.findViewById<android.widget.RadioGroup>(R.id.radio_group_logical_mode)
        val actionRadioGroup = view.findViewById<android.widget.RadioGroup>(R.id.radio_group_action)
        val dnsStrategySpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_dns_strategy)
        val dnsTagSpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_dns_tag)
        val ruleSetSpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_rule_set)
        val invertSwitch = view.findViewById<SwitchMaterial>(R.id.switch_invert)

        // 填充网络类型 Chips
        NetworkType.valuesList.forEach { networkType ->
            val chip = Chip(requireContext()).apply {
                text = networkType.label
                isCheckable = true
                isChecked = editingRule?.networks?.contains(networkType.value) ?: false
                setOnClickListener {
                    if (!isChecked) {
                        if (networkChipGroup.checkedChipId.toInt() == -1) {
                            networkChipGroup.clearCheck()
                        }
                        isChecked = true
                    }
                }
            }
            networkChipGroup.addView(chip)
        }

        // 填充协议类型 Chips
        ProtocolType.valuesList.forEach { protocolType ->
            val chip = Chip(requireContext()).apply {
                text = protocolType.label
                isCheckable = true
                isChecked = editingRule?.protocols?.contains(protocolType.value) ?: false
                setOnClickListener {
                    if (!isChecked) {
                        if (protocolChipGroup.checkedChipId.toInt() == -1) {
                            protocolChipGroup.clearCheck()
                        }
                        isChecked = true
                    }
                }
            }
            protocolChipGroup.addView(chip)
        }

        // 填充 DNS 策略选项
        DnsStrategy.valuesList.forEach { dnsStrategy ->
            (dnsStrategySpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(dnsStrategy.label)
        }
        editingRule?.dnsStrategy?.let { dnsStrategy ->
            val index = DnsStrategy.valuesList.indexOfFirst { it.value == dnsStrategy }
            if (index >= 0) dnsStrategySpinner.setText(DnsStrategy.valuesList[index].label, false)
        }

        // 填充 DNS Tag 选项
        val app = requireContext().applicationContext as com.sbai.SbAiApp
        val dnsManager = app.dnsRuleManager
        dnsManager.load()
        val dnsTags = dnsManager.getAvailableServerTags()
        dnsTags.forEach { tag ->
            (dnsTagSpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(tag)
        }
        editingRule?.dnsTag?.let { tag ->
            val index = dnsTags.indexOf(tag)
            if (index >= 0) dnsTagSpinner.setText(dnsTags[index], false)
        }

        // 填充规则集选项
        val ruleSets = app.routeRuleManager.availableRuleSets
        ruleSets.forEach { ruleSet ->
            (ruleSetSpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(ruleSet)
        }
        editingRule?.ruleSetTag?.let { tag ->
            val index = ruleSets.indexOf(tag)
            if (index >= 0) ruleSetSpinner.setText(ruleSets[index], false)
        }

        // 设置初始值
        remarksInput.setText(editingRule?.remarks)
        domainsInput.setText(editingRule?.domains?.joinToString("\n"))
        ipsInput.setText(editingRule?.ips?.joinToString("\n"))
        ipCidrsInput.setText(editingRule?.ipCidrs?.joinToString("\n"))
        downloadDomainInput.setText(editingRule?.downloadDomain ?: "")
        invertSwitch.isChecked = editingRule?.invert == true

        // 设置逻辑运算和动作的初始选中状态
        if (editingRule != null) {
            when (editingRule!!.logicalMode) {
                LogicalMode.AND.value -> logicalModeRadioGroup.check(R.id.radio_and)
                LogicalMode.OR.value -> logicalModeRadioGroup.check(R.id.radio_or)
                LogicalMode.INVERT.value -> logicalModeRadioGroup.check(R.id.radio_invert)
                else -> logicalModeRadioGroup.check(R.id.radio_none)
            }
            when (editingRule!!.action) {
                RouteAction.ROUTE.value -> actionRadioGroup.check(R.id.radio_route)
                RouteAction.DIRECT.value -> actionRadioGroup.check(R.id.radio_direct)
                RouteAction.REJECT.value -> actionRadioGroup.check(R.id.radio_reject)
                RouteAction.LOAD_BALANCE.value -> actionRadioGroup.check(R.id.radio_load_balance)
                else -> actionRadioGroup.check(R.id.radio_route)
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { _: View ->
            val selectedNetworks = networkChipGroup.checkedChipIds.mapNotNull { id ->
                networkChipGroup.findViewById<Chip>(id.toInt())?.text?.toString()?.let { label: String ->
                    NetworkType.valuesList.find { it.label == label }?.value
                }
            }
            val selectedProtocols = protocolChipGroup.checkedChipIds.mapNotNull { id ->
                protocolChipGroup.findViewById<Chip>(id.toInt())?.text?.toString()?.let { label: String ->
                    ProtocolType.valuesList.find { it.label == label }?.value
                }
            }
            val selectedLogicalMode = when (logicalModeRadioGroup.checkedRadioButtonId) {
                R.id.radio_and -> LogicalMode.AND.value
                R.id.radio_or -> LogicalMode.OR.value
                R.id.radio_invert -> LogicalMode.INVERT.value
                else -> LogicalMode.NONE.value
            }
            val selectedAction = when (actionRadioGroup.checkedRadioButtonId) {
                R.id.radio_route -> RouteAction.ROUTE.value
                R.id.radio_direct -> RouteAction.DIRECT.value
                R.id.radio_reject -> RouteAction.REJECT.value
                R.id.radio_load_balance -> RouteAction.LOAD_BALANCE.value
                else -> RouteAction.ROUTE.value
            }
            val dnsStrategyText = dnsStrategySpinner.text.toString()
            val selectedDnsStrategy = DnsStrategy.valuesList.find { it.label == dnsStrategyText }?.value

            val rule = RouteRule(
                id = editingRule?.id ?: 0,
                remarks = remarksInput.text?.toString() ?: "",
                domains = domainsInput.text?.toString()?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                ips = ipsInput.text?.toString()?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                ipCidrs = ipCidrsInput.text?.toString()?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                networks = selectedNetworks.ifEmpty { listOf("all") },
                protocols = selectedProtocols.ifEmpty { listOf("all") },
                logicalMode = selectedLogicalMode,
                action = selectedAction,
                dnsStrategy = selectedDnsStrategy,
                dnsTag = dnsTagSpinner.text.toString().takeIf { it.isNotEmpty() },
                ruleSetTag = ruleSetSpinner.text.toString().takeIf { it.isNotEmpty() },
                invert = invertSwitch.isChecked,
                downloadDomain = downloadDomainInput.text?.toString()?.takeIf { it.isNotBlank() }
            )

            onSaveListener(rule)
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }
    }
}
