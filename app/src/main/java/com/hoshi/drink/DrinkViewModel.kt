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

    private fun mutate(resetReminder: Boolean = true, action: () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val next = withContext(Dispatchers.IO) {
                        action()
                        DrinkState(repo.all(), repo.settings(), true).also {
                            Reminders.schedule(getApplication(), it.settings, it.drinks, reset = resetReminder)
                        }
                    }
                    mutableState.value = next
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(loaded = true, error = "保存失败，请重试")
                }
            }
        }
    }
    fun refresh() = mutate(resetReminder = false) { }
    fun add(amount: Int) = mutate {
        repo.add(amount)
        Reminders.stopVibration(getApplication())
    }
    fun delete(drink: Drink) = mutate { repo.delete(drink.id) }
    fun restore(drink: Drink) = mutate { repo.restore(drink) }
    fun settings(value: DrinkSettings) {
        val previous = mutableState.value.settings
        val affectsTime = previous.copy(reminderStyle = value.reminderStyle, preciseReminders = value.preciseReminders,
            repeatVibration = value.repeatVibration) != value
        mutate(resetReminder = affectsTime) {
            repo.save(value)
            if (!value.repeatVibration || value.reminderStyle != ReminderStyle.VIBRATE ||
                (previous.enabled && !value.enabled)) {
                Reminders.stopVibration(getApplication())
            }
        }
    }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
    override fun onCleared() { repo.close() }
}
