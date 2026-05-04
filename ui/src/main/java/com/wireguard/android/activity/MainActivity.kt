/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelListFragment
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }
        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()
        handleDeeplinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeeplinkIntent(intent)
    }

    private fun handleDeeplinkIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "wgturn" || uri.host != "import") return

        // Два формата deep-link'а:
        //   wgturn://import?url=<full_url>      — провайдер-агностик (новый формат)
        //   wgturn://import?token=<short_uuid>  — legacy (key.shadowgate.online)
        val explicitUrl = uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }

        val configUrl = explicitUrl
            ?: token?.let { "https://key.shadowgate.online/api/config/$it" }
            ?: run {
                Toast.makeText(this, "Deeplink: укажите ?url=... или ?token=...", Toast.LENGTH_SHORT).show()
                return
            }

        lifecycleScope.launch {
            try {
                val configText = withContext(Dispatchers.IO) {
                    fetchConfig(configUrl)
                }

                if (!configText.contains("[Interface]") || !configText.contains("[Peer]")) {
                    throw IllegalArgumentException("Сервер вернул некорректный конфиг")
                }

                val config = Config.parse(configText.byteInputStream())
                val tunnelManager = Application.getTunnelManager()
                val existing = tunnelManager.getTunnels().firstOrNull { it.name == TUNNEL_NAME }
                val isUpdate = existing != null

                if (existing?.state == Tunnel.State.UP) {
                    existing.setStateAsync(Tunnel.State.DOWN)
                }
                existing?.deleteAsync()
                tunnelManager.create(TUNNEL_NAME, config)

                val message = if (isUpdate) "Конфиг обновлён" else "Конфиг сохранён"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()

                val fragment = supportFragmentManager.findFragmentById(R.id.list_detail_container)
                if (fragment is TunnelListFragment) {
                    fragment.refreshState()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Ошибка импорта: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Persistent device ID — сохраняется в SharedPreferences.
     * При переустановке приложения / Clear Data — генерится новый
     * (это норма: сервер увидит как новое устройство).
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("wgturn_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_hwid", null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString("device_hwid", id).apply()
            id
        }
    }

    @Throws(Exception::class)
    private fun fetchConfig(configUrl: String): String {
        val url = URL(configUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            // Идемпотентный provision на стороне сервера: тот же HWID = тот же peer.
            setRequestProperty("X-Device-HWID", getOrCreateDeviceId())
            setRequestProperty("X-Device-OS", "Android")
            setRequestProperty("X-Device-Model", Build.MODEL ?: "")
            setRequestProperty("X-OS-Version", Build.VERSION.RELEASE ?: "")
            setRequestProperty("User-Agent", "wgturn/1.0 (Android ${Build.VERSION.RELEASE})")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IllegalStateException("HTTP $code")
            }
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")

            val json = JSONObject(body)
            if (!json.getBoolean("ok")) {
                throw IllegalStateException("Server error: ${json.optString("error")}")
            }
            json.getString("config").trim()
        } finally {
            connection.disconnect()
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) return false

        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            fragmentManager.commit {
                add(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    companion object {
        private const val TUNNEL_NAME = "wgturn"
    }
}
