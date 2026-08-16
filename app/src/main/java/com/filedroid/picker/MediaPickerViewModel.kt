package com.filedroid.picker

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map

class MediaPickerViewModel : ViewModel() {

    private val _selectedPaths = MutableLiveData<MutableSet<String>>(mutableSetOf())
    val selectedPaths: LiveData<MutableSet<String>> = _selectedPaths

    val selectedCount: LiveData<Int> = _selectedPaths.map { it.size }

    fun toggle(path: String) {
        val set = _selectedPaths.value ?: mutableSetOf()
        if (set.contains(path)) {
            set.remove(path)
        } else {
            set.add(path)
        }
        _selectedPaths.value = set
    }

    fun isSelected(path: String): Boolean {
        return _selectedPaths.value?.contains(path) == true
    }

    fun selectAll(paths: List<String>) {
        val set = _selectedPaths.value ?: mutableSetOf()
        set.addAll(paths)
        _selectedPaths.value = set
    }

    fun deselectAll(paths: List<String>) {
        val set = _selectedPaths.value ?: mutableSetOf()
        set.removeAll(paths.toSet())
        _selectedPaths.value = set
    }

    fun clearAll() {
        _selectedPaths.value = mutableSetOf()
    }

    fun getSelectedPaths(): List<String> {
        return _selectedPaths.value?.toList() ?: emptyList()
    }
}
