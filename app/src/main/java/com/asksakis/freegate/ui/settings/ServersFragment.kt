package com.asksakis.freegate.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.ServerProfile
import com.asksakis.freegate.auth.ServerProfileStore
import com.asksakis.freegate.notifications.FrigateAlertService
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

/**
 * Server picker: list of configured Frigate deployments with active-state radio,
 * tap-to-switch, rename and delete from a per-row overflow menu, and an
 * extended-FAB "Add server" action.
 *
 * Source of truth is [ServerProfileStore]. The fragment never talks to the flat
 * SharedPreferences directly — switching active triggers a swap there, plus a
 * background-service restart so the WebSocket reconnects to the new server.
 */
class ServersFragment : Fragment(R.layout.fragment_servers) {

    private lateinit var store: ServerProfileStore
    private lateinit var adapter: ServerAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = ServerProfileStore.getInstance(requireContext())
        // Capture whatever the user has tweaked in Connection back into the active
        // profile before we render the list — otherwise stale snapshot data would
        // win over what's in the live flat keys.
        store.commitFlatStateToActive()

        adapter = ServerAdapter(
            onSelect = ::switchActive,
            onRename = ::promptRename,
            onDelete = ::confirmDelete,
        )
        val recycler = view.findViewById<RecyclerView>(R.id.servers_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        adapter.submit(store.getAll(), store.getActiveId())

        view.findViewById<ExtendedFloatingActionButton>(R.id.add_server_fab)
            .setOnClickListener { promptAdd() }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case another screen mutated the store (defensive — most edits
        // come through this fragment, but settings restore / process death could
        // surface stale data otherwise).
        if (::adapter.isInitialized) {
            adapter.submit(store.getAll(), store.getActiveId())
        }
    }

    // ---- Actions -----------------------------------------------------------

    private fun switchActive(profile: ServerProfile) {
        if (profile.id == store.getActiveId()) return
        store.setActive(profile.id)
        // Force a service teardown so onCreate runs against the new profile's
        // flat state — without this, the existing service's `lastBaseUrl ==
        // baseUrl` guard would let the WebSocket keep talking to the previous
        // server when two profiles happen to share a host but differ in
        // credentials / mTLS.
        FrigateAlertService.updateForContext(requireContext(), forceRestart = true)
        adapter.submit(store.getAll(), store.getActiveId())
        Toast.makeText(requireContext(), getString(R.string.servers_switched, profile.name), Toast.LENGTH_SHORT).show()
    }

    private fun promptAdd() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.servers_name_hint)
            isSingleLine = true
        }
        com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle(R.string.servers_add)
            .setMessage(R.string.servers_add_message)
            .setView(wrapDialogInput(input))
            .setPositiveButton(R.string.action_add) { _, _ ->
                val typed = input.text?.toString()?.trim().orEmpty()
                val name = typed.ifEmpty { uniqueDefaultName() }
                val created = store.add(name)
                store.setActive(created.id)
                FrigateAlertService.updateForContext(requireContext(), forceRestart = true)
                adapter.submit(store.getAll(), store.getActiveId())
                Toast.makeText(
                    requireContext(),
                    getString(R.string.servers_added, created.name),
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Picks a "New server", "New server 2", "New server 3"… name avoiding collisions. */
    private fun uniqueDefaultName(): String {
        val taken = store.getAll().map { it.name }.toSet()
        val base = getString(R.string.servers_default_name)
        if (base !in taken) return base
        var i = 2
        while (getString(R.string.servers_default_name_numbered, i) in taken) i++
        return getString(R.string.servers_default_name_numbered, i)
    }

    private fun promptRename(profile: ServerProfile) {
        val input = EditText(requireContext()).apply {
            setText(profile.name)
            setSelection(profile.name.length)
            isSingleLine = true
        }
        com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle(R.string.servers_rename)
            .setView(wrapDialogInput(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) return@setPositiveButton
                store.rename(profile.id, newName)
                adapter.submit(store.getAll(), store.getActiveId())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: ServerProfile) {
        val isLast = store.getAll().size <= 1
        val message = getString(
            if (isLast) R.string.servers_delete_last_message else R.string.servers_delete_message
        )
        com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle(getString(R.string.servers_delete_title, profile.name))
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wasActive = profile.id == store.getActiveId()
                store.delete(profile.id)
                // Only restart the listener when we deleted the active profile.
                // `store.delete` auto-promotes the next profile (or drops to the
                // unconfigured state when it was the last one), so the flat keys
                // have moved.
                FrigateAlertService.updateForContext(requireContext(), forceRestart = wasActive)
                if (isLast) {
                    // No servers left: pop straight back to Home, which now shows the
                    // setup empty state (matches the dialog's "returns to setup" promise).
                    findNavController().popBackStack(R.id.nav_home, false)
                } else {
                    adapter.submit(store.getAll(), store.getActiveId())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wrapDialogInput(input: EditText): View {
        val padding = (resources.displayMetrics.density * 24).toInt()
        return android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
    }

    // ---- Adapter -----------------------------------------------------------

    private class ServerAdapter(
        private val onSelect: (ServerProfile) -> Unit,
        private val onRename: (ServerProfile) -> Unit,
        private val onDelete: (ServerProfile) -> Unit,
    ) : RecyclerView.Adapter<ServerAdapter.Holder>() {

        private val items = mutableListOf<ServerProfile>()
        private var activeId: String? = null

        fun submit(newItems: List<ServerProfile>, newActiveId: String?) {
            items.clear()
            items.addAll(newItems)
            activeId = newActiveId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server_row, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val profile = items[position]
            val isActive = profile.id == activeId
            holder.radio.setImageResource(
                if (isActive) R.drawable.ic_radio_filled else R.drawable.ic_radio_empty,
            )
            holder.title.text = profile.name
            holder.subtitle.text =
                profile.internalUrl ?: profile.externalUrl ?: "Not configured"
            holder.root.setOnClickListener { onSelect(profile) }
            holder.overflow.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, 1, 0, "Rename")
                    menu.add(0, 2, 1, "Delete")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { onRename(profile); true }
                            2 -> { onDelete(profile); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.server_row_root)
            val radio: ImageView = view.findViewById(R.id.server_row_radio)
            val title: TextView = view.findViewById(R.id.server_row_title)
            val subtitle: TextView = view.findViewById(R.id.server_row_subtitle)
            val overflow: ImageButton = view.findViewById(R.id.server_row_overflow)
        }
    }
}
