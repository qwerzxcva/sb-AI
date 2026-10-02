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
import com.sbai.databinding.FragmentDnsBinding

class DnsFragment : Fragment() {

    private var _binding: FragmentDnsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dnsManager: DnsRuleManager
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dnsManager = (requireActivity().applicationContext as com.sbai.SbAiApp).dnsRuleManager
        dnsManager.load()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("DNS 服务器"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("DNS 策略组"))
        
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                currentTab = tab.position
                updateTabContent()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        binding.fabAdd.setOnClickListener {
            when (currentTab) {
                0 -> showAddServerDialog()
                1 -> showAddGroupDialog()
            }
        }

        updateTabContent()
    }

    private fun updateTabContent() {
        when (currentTab) {
            0 -> {
                binding.serverRecyclerView.visibility = View.VISIBLE
                binding.groupRecyclerView.visibility = View.GONE
                binding.emptyServerView.visibility = if (dnsManager.servers.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyGroupView.visibility = View.GONE
                updateServerList()
            }
            1 -> {
                binding.serverRecyclerView.visibility = View.GONE
                binding.groupRecyclerView.visibility = View.VISIBLE
                binding.emptyServerView.visibility = View.GONE
                binding.emptyGroupView.visibility = if (dnsManager.groups.isEmpty()) View.VISIBLE else View.GONE
                updateGroupList()
            }
        }
    }

    private fun updateServerList() {
        val adapter = object : BaseAdapter() {
            override fun getCount() = dnsManager.servers.size
            override fun getItem(position: Int) = dnsManager.servers[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(parent?.context)
                    .inflate(R.layout.item_dns_server, parent, false)
                val server = dnsManager.servers[position]
                view.findViewById<MaterialCheckBox>(R.id.cb_enabled).isChecked = server.enabled
                view.findViewById<TextView>(R.id.tv_remarks).text = server.remarks.ifEmpty { "未命名" }
                view.findViewById<TextView>(R.id.tv_address).text = server.address
                view.findViewById<TextView>(R.id.tv_tag).text = "Tag: ${server.tag ?: "自动"}"
                view.setOnClickListener { showEditServerDialog(server) }
                return view
            }
        }
        binding.serverRecyclerView.adapter = adapter as RecyclerView.Adapter<*>
        binding.serverRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateGroupList() {
        val adapter = object : BaseAdapter() {
            override fun getCount() = dnsManager.groups.size
            override fun getItem(position: Int) = dnsManager.groups[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(parent?.context)
                    .inflate(R.layout.item_dns_group, parent, false)
                val group = dnsManager.groups[position]
                view.findViewById<MaterialCheckBox>(R.id.cb_enabled).isChecked = group.enabled
                view.findViewById<TextView>(R.id.tv_name).text = group.name.ifEmpty { "未命名" }
                view.findViewById<TextView>(R.id.tv_strategy).text = "策略: ${DnsStrategy.valuesList.find { it.value == group.strategy }?.label ?: group.strategy}"
                view.findViewById<TextView>(R.id.tv_servers_count).text = "服务器: ${group.servers.size}"
                view.setOnClickListener { showEditGroupDialog(group) }
                return view
            }
        }
        binding.groupRecyclerView.adapter = adapter as RecyclerView.Adapter<*>
        binding.groupRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun showAddServerDialog() {
        val dialog = ServerEditorDialog()
        dialog.setOnSaveListener { server ->
            dnsManager.addServer(server)
            updateServerList()
        }
        dialog.show(parentFragmentManager, "add_server")
    }

    private fun showEditServerDialog(server: DnsServer) {
        val dialog = ServerEditorDialog(server)
        dialog.setOnSaveListener { updatedServer ->
            dnsManager.updateServer(updatedServer)
            updateServerList()
        }
        dialog.show(parentFragmentManager, "edit_server")
    }

    private fun showAddGroupDialog() {
        val dialog = GroupEditorDialog()
        dialog.setOnSaveListener { group ->
            dnsManager.addGroup(group)
            updateGroupList()
        }
        dialog.show(parentFragmentManager, "add_group")
    }

    private fun showEditGroupDialog(group: DnsGroup) {
        val dialog = GroupEditorDialog(group)
        dialog.setOnSaveListener { updatedGroup ->
            dnsManager.updateGroup(updatedGroup)
            updateGroupList()
        }
        dialog.show(parentFragmentManager, "edit_group")
    }

    override fun onResume() {
        super.onResume()
        dnsManager.load()
        updateTabContent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * DNS 服务器编辑器对话框
 */
class ServerEditorDialog(private val editingServer: DnsServer? = null) : BottomSheetDialogFragment() {

    private lateinit var onSaveListener: (DnsServer) -> Unit

    fun setOnSaveListener(listener: (DnsServer) -> Unit) {
        this.onSaveListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dns_server_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val remarksInput = view.findViewById<TextInputEditText>(R.id.et_remarks)
        val addressInput = view.findViewById<TextInputEditText>(R.id.et_address)
        val clientSubnetInput = view.findViewById<TextInputEditText>(R.id.et_client_subnet)
        val typeSpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_type)
        val tagInput = view.findViewById<TextInputEditText>(R.id.et_tag)
        val preferIpv4Switch = view.findViewById<SwitchMaterial>(R.id.switch_prefer_ipv4)
        val preferIpv6Switch = view.findViewById<SwitchMaterial>(R.id.switch_prefer_ipv6)
        val ruleSetSpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_rule_set)

        // 填充类型选项
        DnsServerType.valuesList.forEach { type ->
            (typeSpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(type.label)
        }
        editingServer?.let { server ->
            val typeIndex = DnsServerType.valuesList.indexOfFirst { it.value == server.type }
            if (typeIndex >= 0) typeSpinner.setText(DnsServerType.valuesList[typeIndex].label, false)
            remarksInput.setText(server.remarks)
            addressInput.setText(server.address)
            clientSubnetInput.setText(server.clientSubnet.joinToString("\n"))
            tagInput.setText(server.tag)
            preferIpv4Switch.isChecked = server.preferIpv4
            preferIpv6Switch.isChecked = server.preferIpv6
        }

        // 填充规则集选项
        val app = requireContext().applicationContext as com.sbai.SbAiApp
        val ruleSets = app.routeRuleManager.availableRuleSets
        ruleSets.forEach { ruleSet ->
            (ruleSetSpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(ruleSet)
        }
        editingServer?.ruleSetTag?.let { tag ->
            val index = ruleSets.indexOf(tag)
            if (index >= 0) ruleSetSpinner.setText(ruleSets[index], false)
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { _: View ->
            val selectedType = DnsServerType.valuesList.find { it.label == typeSpinner.text.toString() } ?: DnsServerType.REMOTE
            val server = DnsServer(
                id = editingServer?.id ?: 0,
                remarks = remarksInput.text?.toString() ?: "",
                address = addressInput.text?.toString() ?: "",
                type = selectedType.value,
                tag = tagInput.text?.toString()?.takeIf { it.isNotBlank() },
                preferIpv4 = preferIpv4Switch.isChecked,
                preferIpv6 = preferIpv6Switch.isChecked,
                clientSubnet = clientSubnetInput.text?.toString()?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                ruleSetTag = ruleSetSpinner.text.toString().takeIf { it.isNotEmpty() }
            )
            onSaveListener(server)
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }
    }
}

/**
 * DNS 策略组编辑器对话框
 */
class GroupEditorDialog(private val editingGroup: DnsGroup? = null) : BottomSheetDialogFragment() {

    private lateinit var onSaveListener: (DnsGroup) -> Unit

    fun setOnSaveListener(listener: (DnsGroup) -> Unit) {
        this.onSaveListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dns_group_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameInput = view.findViewById<TextInputEditText>(R.id.et_name)
        val strategySpinner = view.findViewById<MaterialAutoCompleteTextView>(R.id.sp_strategy)
        val preferIPv4Switch = view.findViewById<SwitchMaterial>(R.id.switch_prefer_ipv4)
        val preferIPv6Switch = view.findViewById<SwitchMaterial>(R.id.switch_prefer_ipv6)
        val serversChipGroup = view.findViewById<ChipGroup>(R.id.chip_group_servers)

        // 填充策略选项
        DnsStrategy.valuesList.forEach { strategy ->
            (strategySpinner.adapter as? android.widget.ArrayAdapter<String>)?.add(strategy.label)
        }
        editingGroup?.let { group ->
            nameInput.setText(group.name)
            val strategyIndex = DnsStrategy.valuesList.indexOfFirst { it.value == group.strategy }
            if (strategyIndex >= 0) strategySpinner.setText(DnsStrategy.valuesList[strategyIndex].label, false)
            preferIPv4Switch.isChecked = group.preferIPv4
            preferIPv6Switch.isChecked = group.preferIPv6
        }

        // 填充服务器 Chips
        val app = requireContext().applicationContext as com.sbai.SbAiApp
        val dnsManager = app.dnsRuleManager
        dnsManager.load()
        val servers = dnsManager.servers.filter { it.enabled }
        servers.forEach { server ->
            val chip = Chip(requireContext()).apply {
                text = server.remarks.ifEmpty { server.address }
                isCheckable = true
                isChecked = editingGroup?.servers?.contains(server.tag ?: server.address) == true
                setOnClickListener {
                    isChecked = !isChecked
                }
            }
            serversChipGroup.addView(chip)
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { _: View ->
            val selectedStrategy = DnsStrategy.valuesList.find { it.label == strategySpinner.text.toString() } ?: DnsStrategy.DEFAULT
            val selectedServers = serversChipGroup.checkedChipIds.mapNotNull { id ->
                serversChipGroup.findViewById<Chip>(id.toInt())?.text?.toString()
            }
            val group = DnsGroup(
                id = editingGroup?.id ?: 0,
                name = nameInput.text?.toString() ?: "",
                servers = selectedServers,
                strategy = selectedStrategy.value,
                preferIPv4 = preferIPv4Switch.isChecked,
                preferIPv6 = preferIPv6Switch.isChecked
            )
            onSaveListener(group)
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }
    }
}
