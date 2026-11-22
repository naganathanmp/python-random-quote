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

    fun reload() { _profile.value = readProfile(); _limits.value = readLimits() }
}
