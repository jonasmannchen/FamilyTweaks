package com.jonas.familytweaks.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.jonas.familytweaks.R
import com.jonas.familytweaks.data.AppInfo
import com.jonas.familytweaks.data.AppRepository
import com.jonas.familytweaks.data.AdminStatus
import com.jonas.familytweaks.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single-activity UI. The two tabs share one RecyclerView whose adapter is
 * swapped from the matching StateFlow; this keeps memory small and avoids
 * fragment complexity while staying perfectly readable.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: AppListViewModel
    private lateinit var repository: AppRepository

    /** Launches Android's official "activate device admin" screen. */
    private lateinit var addAdminLauncher: ActivityResultLauncher<Intent>

    /** Package currently awaiting user confirmation in the system dialog. */
    private var pendingActivationPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[AppListViewModel::class.java]
        repository = AppRepository.get(this)

        addAdminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* Result is irrelevant: the truth is re-read from the framework onResume. */ }

        setupTabsAndList()
        setupSearch()
        setupOwnAdminCard()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        repository.registerReceivers()
        refreshOwnAdminStatus()
        viewModel.refresh()
    }

    override fun onStop() {
        super.onStop()
        // Receivers are only needed while visible; the ViewModel survives.
        repository.unregisterReceivers()
    }

    // ---- own admin card -----------------------------------------------------

    private fun setupOwnAdminCard() {
        binding.btnOwnAdmin.setOnClickListener {
            if (repository.isSelfAdminActiveNow()) {
                // Already active: open Android's device-admin settings where
                // the user can manage/deactivate it.
                repository.openAdminSettings()
            } else {
                val component = ComponentName(
                    packageName,
                    "com.jonas.familytweaks.admin.FamilyTweaksAdminReceiver"
                )
                try {
                    addAdminLauncher.launch(
                        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                component
                            )
                            .putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                getString(R.string.own_admin_explanation)
                            )
                    )
                } catch (e: Exception) {
                    // No settings screen on this ROM: fall back to plain admin settings.
                    repository.openAdminSettings()
                }
            }
        }
    }

    /** Re-reads the REAL state from DevicePolicyManager – never a cached flag. */
    private fun refreshOwnAdminStatus() {
        runOnUiThread {
            val active = repository.isSelfAdminActiveNow()
            binding.tvOwnAdminStatus.setText(
                if (active) R.string.own_admin_active else R.string.own_admin_inactive
            )
            binding.tvOwnAdminStatus.setTextColor(
                getColor(if (active) R.color.status_green else R.color.status_red)
            )
            binding.btnOwnAdmin.setText(
                if (active) R.string.button_manage_admin else R.string.button_activate_admin
            )
        }
    }

    // ---- tabs & list --------------------------------------------------------

    private var currentTabIsSystem = false

    private fun setupTabsAndList() {
        binding.appList.layoutManager = LinearLayoutManager(this)
        val adapter = AppListAdapter(
            onClick = { app -> showActionDialog(app) },
            onSelectionToggled = { app, _ -> viewModel.toggleSelection(app.packageName) }
        )
        binding.appList.adapter = adapter
        boundAdapter = adapter

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_user_apps))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_system_apps))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTabIsSystem = tab.position == 1
                pushCurrentTabState()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private var boundAdapter: AppListAdapter? = null
    private var lastUserState: TabUiState? = null
    private var lastSystemState: TabUiState? = null

    private fun pushCurrentTabState() {
        val state = if (currentTabIsSystem) lastSystemState else lastUserState
        state?.let { applyState(it) }
    }

    private fun applyState(state: TabUiState) {
        boundAdapter?.submitList(state.apps)
        binding.progress.isVisible = state.isLoading && state.apps.isEmpty()
        binding.emptyHint.isVisible = !state.isLoading && state.apps.isEmpty()
        binding.tabSummary.text = getString(
            R.string.tab_summary,
            state.selectionCount,
            state.activeAdminCount,
            state.availableAdminCount
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.userTab.collect {
                        lastUserState = it
                        if (!currentTabIsSystem) applyState(it)
                    }
                }
                launch {
                    viewModel.systemTab.collect {
                        lastSystemState = it
                        if (currentTabIsSystem) applyState(it)
                    }
                }
                // Keep the own-admin card in sync with every scan result too.
                launch {
                    combine(viewModel.userTab, viewModel.systemTab) { u, s -> u to s }
                        .collect { refreshOwnAdminStatus() }
                }
            }
        }
    }

    // ---- search -------------------------------------------------------------

    private fun setupSearch() {
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                viewModel.setQuery(s?.toString()?.lowercase().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ---- per-app action -----------------------------------------------------

    /**
     * Honest protection check: FamilyTweaks inspects the TARGET package's own
     * manifest for a real BIND_DEVICE_ADMIN receiver. Only then is Android's
     * official activation screen opened for that exact component. If no such
     * receiver exists, the dialog says so plainly – nothing is faked.
     */
    private fun showActionDialog(app: AppInfo) {
        val sb = StringBuilder()
        sb.append(getString(R.string.dialog_header, app.label)).append('\n')
        sb.append(app.packageName).append("\n\n")

        when (app.adminStatus) {
            AdminStatus.ADMIN_ACTIVE -> {
                sb.append(getString(R.string.dialog_active_body))
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            AdminStatus.ADMIN_AVAILABLE -> {
                sb.append(getString(R.string.dialog_available_body))
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title)
                    .setMessage(sb.toString())
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.dialog_activate) { _, _ ->
                        requestActivationFor(app)
                    }
                    .show()
            }
            AdminStatus.NO_ADMIN -> {
                sb.append(getString(R.string.dialog_none_body))
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun requestActivationFor(app: AppInfo) {
        when (val result = repository.requestProtectionCheck(app.packageName)) {
            is AppRepository.ProtectResult.CanActivate -> {
                pendingActivationPackage = app.packageName
                try {
                    addAdminLauncher.launch(
                        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                result.component
                            )
                            .putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                getString(R.string.foreign_admin_explanation, app.label)
                            )
                    )
                } catch (e: Exception) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_title)
                        .setMessage(R.string.error_no_settings_screen)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            AppRepository.ProtectResult.AlreadyActive -> refreshOwnAdminStatus()
            AppRepository.ProtectResult.NoAdminAvailable ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title)
                    .setMessage(R.string.dialog_none_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
        }
    }
}
