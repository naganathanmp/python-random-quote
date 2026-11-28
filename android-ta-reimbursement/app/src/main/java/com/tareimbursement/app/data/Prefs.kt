package com.tareimbursement.app.data

import android.content.Context
import com.tareimbursement.app.core.EmployeeInfo
import com.tareimbursement.app.core.ExpenseCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Profile(
    val name: String = "",
    val employeeCode: String = "",
    val designation: String = "",
    val department: String = "",
    val baseLocation: String = "",
    val organisation: String = "",
    val email: String = "",
    val approverEmail: String = "",
    val ccEmail: String = "",
) {
    val isComplete: Boolean get() = name.isNotBlank() && employeeCode.isNotBlank()
    fun toEmployee() = EmployeeInfo(name.trim(), employeeCode.trim(), designation.trim(), department.trim(), baseLocation.trim(), organisation.trim(), email.trim())
}

data class UiPrefs(
    /** LIGHT, DARK or SYSTEM. */
    val theme: String = "SYSTEM",
    /** YELLOW, SAFFRON, TEAL, BLUE, VIOLET or WALLPAPER (Android 12+). */
    val accent: String = "YELLOW",
    /** 0.9 small … 1.3 extra large; only inside the app. */
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val indianGrouping: Boolean = true,
    val showPaise: Boolean = true,
    val defaultCurrency: String = "INR",
    val homeCity: String = "",
    /** After each photo, open the camera again for the next bill. */
    val batchScan: Boolean = true,
    val reminders: Boolean = true,
    /** Folder (SAF tree URI) for the weekly automatic backup; empty = off. */
    val backupFolder: String = "",
    val lastAutoBackup: Long = 0L,
)

/** Small key-value store for the profile and app settings (all on-device). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ta_prefs", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<Profile> = _profile
    private val _limits = MutableStateFlow(readLimits())
    /** Optional per-bill policy limits by accounting head; bills above the limit are flagged. */
    val limits: StateFlow<Map<ExpenseCategory, Double>> = _limits

    private fun readProfile() = Profile(
        sp.getString("name", "")!!, sp.getString("code", "")!!, sp.getString("designation", "")!!,
        sp.getString("department", "")!!, sp.getString("base", "")!!, sp.getString("org", "")!!,
        sp.getString("email", "")!!, sp.getString("approver", "")!!, sp.getString("cc", "")!!,
    )

    fun saveProfile(p: Profile) {
        sp.edit().putString("name", p.name.trim()).putString("code", p.employeeCode.trim()).putString("designation", p.designation.trim())
            .putString("department", p.department.trim()).putString("base", p.baseLocation.trim()).putString("org", p.organisation.trim())
            .putString("email", p.email.trim()).putString("approver", p.approverEmail.trim()).putString("cc", p.ccEmail.trim()).apply()
        _profile.value = readProfile()
    }

    private fun readLimits(): Map<ExpenseCategory, Double> = ExpenseCategory.entries.mapNotNull { c ->
        val v = sp.getFloat("limit_${c.name}", 0f)
        if (v > 0f) c to v.toDouble() else null
    }.toMap()

    fun saveLimit(c: ExpenseCategory, value: Double?) {
        sp.edit().apply { if (value == null || value <= 0) remove("limit_${c.name}") else putFloat("limit_${c.name}", value.toFloat()) }.apply()
        _limits.value = readLimits()
    }

    /** On-device AI reads new bills after OCR (when a model is installed). */
    var aiEnabled: Boolean
        get() = sp.getBoolean("ai_enabled", true)
        set(v) { sp.edit().putBoolean("ai_enabled", v).apply() }

    /** Download the AI model only on Wi-Fi. */
    var aiWifiOnly: Boolean
        get() = sp.getBoolean("ai_wifi_only", true)
        set(v) { sp.edit().putBoolean("ai_wifi_only", v).apply() }

    /** Team sync server (Supabase project URL + public anon key). Empty = team features off. */
    var serverUrl: String
        get() = sp.getString("server_url", "")!!
        set(v) { sp.edit().putString("server_url", v.trim().trimEnd('/')).apply() }
    var serverKey: String
        get() = sp.getString("server_key", "")!!
        set(v) { sp.edit().putString("server_key", v.trim()).apply() }

    /** Default own-vehicle reimbursement rate per km (₹). */
    var mileageRate: Double
        get() = sp.getFloat("mileage_rate", 7.5f).toDouble()
        set(v) { sp.edit().putFloat("mileage_rate", v.toFloat()).apply() }

    // ------------------------------------------------------------ v2.1 customisation
    private fun readUi() = UiPrefs(
        theme = sp.getString("ui_theme", "SYSTEM")!!, accent = sp.getString("ui_accent", "YELLOW")!!,
        textScale = sp.getFloat("ui_text_scale", 1f), reduceMotion = sp.getBoolean("ui_reduce_motion", false),
        indianGrouping = sp.getBoolean("ui_indian", true), showPaise = sp.getBoolean("ui_paise", true),
        defaultCurrency = sp.getString("ui_currency", "INR")!!, homeCity = sp.getString("ui_home_city", "")!!,
        batchScan = sp.getBoolean("ui_batch_scan", true), reminders = sp.getBoolean("ui_reminders", true),
        backupFolder = sp.getString("ui_backup_folder", "")!!, lastAutoBackup = sp.getLong("ui_last_backup", 0L),
    )
    private val _ui = MutableStateFlow(readUi())
    /** Look & feel and defaults chosen in Settings. */
    val ui: StateFlow<UiPrefs> = _ui

    fun updateUi(change: (UiPrefs) -> UiPrefs) {
        val n = change(_ui.value)
        sp.edit().putString("ui_theme", n.theme).putString("ui_accent", n.accent).putFloat("ui_text_scale", n.textScale)
            .putBoolean("ui_reduce_motion", n.reduceMotion).putBoolean("ui_indian", n.indianGrouping).putBoolean("ui_paise", n.showPaise)
            .putString("ui_currency", n.defaultCurrency).putString("ui_home_city", n.homeCity.trim()).putBoolean("ui_batch_scan", n.batchScan)
            .putBoolean("ui_reminders", n.reminders).putString("ui_backup_folder", n.backupFolder).putLong("ui_last_backup", n.lastAutoBackup).apply()
        _ui.value = readUi()
        applyNumberStyle()
    }

    /** Number style is used by core formatters (screens, PDF, Excel). */
    fun applyNumberStyle() {
        com.tareimbursement.app.core.AmountWords.indianGrouping = _ui.value.indianGrouping
        com.tareimbursement.app.core.AmountWords.showPaise = _ui.value.showPaise
    }

    init { applyNumberStyle() }

    fun reload() { _profile.value = readProfile(); _limits.value = readLimits(); _ui.value = readUi(); applyNumberStyle() }
}
