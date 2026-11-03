package com.hoshi.drink

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DrinkState(val drinks: List<Drink> = emptyList(), val settings: DrinkSettings = DrinkSettings(), val loaded: Boolean = false, val error: String? = null)

class DrinkViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = DrinkRepository(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DrinkState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    private fun mutate(operation: String, resetReminder: Boolean = true, action: () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                ReminderDiagnostics.record(getApplication(), "数据操作开始", fields = mapOf("operation" to operation, "resetReminder" to resetReminder))
                try {
                    val next = withContext(Dispatchers.IO) {
                        action()
                        DrinkState(repo.all(), repo.settings(), true).also {
                            Reminders.schedule(getApplication(), it.settings, it.drinks, reset = resetReminder)
                        }
                    }
                    mutableState.value = next
                    ReminderDiagnostics.record(getApplication(), "数据操作完成", operation)
                } catch (error: Exception) {
                    ReminderDiagnostics.record(getApplication(), "数据操作失败", operation, error = error)
                    mutableState.value = mutableState.value.copy(loaded = true, error = "保存失败，请重试")
                }
            }
        }
    }
    fun refresh() = mutate("刷新", resetReminder = false) { }
    fun add(amount: Int) = mutate("记录饮水") {
        repo.add(amount)
        Reminders.stopVibration(getApplication(), "已记录饮水")
    }
    fun delete(drink: Drink) = mutate("删除记录") { repo.delete(drink.id) }
    fun restore(drink: Drink) = mutate("恢复记录") { repo.restore(drink) }
    fun settings(value: DrinkSettings) {
        val previous = mutableState.value.settings
        val affectsTime = previous.copy(reminderStyle = value.reminderStyle, preciseReminders = value.preciseReminders,
            repeatVibration = value.repeatVibration) != value
        mutate("修改设置", resetReminder = affectsTime) {
            repo.save(value)
            val changed = buildList {
                if (previous.enabled != value.enabled) add("enabled")
                if (previous.start != value.start) add("start")
                if (previous.end != value.end) add("end")
                if (previous.interval != value.interval) add("interval")
                if (previous.goal != value.goal) add("goal")
                if (previous.stopAtGoal != value.stopAtGoal) add("stopAtGoal")
                if (previous.reminderStyle != value.reminderStyle) add("style")
                if (previous.preciseReminders != value.preciseReminders) add("preciseReminders")
                if (previous.repeatVibration != value.repeatVibration) add("repeatVibration")
            }
            ReminderDiagnostics.record(getApplication(), "设置已保存", fields = mapOf("changedFields" to changed.joinToString(",")))
            ReminderDiagnostics.snapshot(getApplication(), "设置变更")
            if (!value.repeatVibration || value.reminderStyle != ReminderStyle.VIBRATE ||
                (previous.enabled && !value.enabled)) {
                Reminders.stopVibration(getApplication(), "提醒设置变更")
            }
        }
    }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
    override fun onCleared() { repo.close() }
}
