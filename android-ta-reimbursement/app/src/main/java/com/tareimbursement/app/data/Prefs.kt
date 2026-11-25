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

    fun reload() { _profile.value = readProfile(); _limits.value = readLimits() }
}
