package com.teledrive.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PlaybackQueue @Inject constructor() {
    private val _state = MutableStateFlow(PlaybackQueueState())
    val state: StateFlow<PlaybackQueueState> = _state.asStateFlow()

    fun setQueue(items: List<PlaybackItem>, startIndex: Int = 0) {
        _state.value = PlaybackQueueState(
            items = items,
            currentIndex = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1),
            repeatMode = _state.value.repeatMode,
            shuffleMode = _state.value.shuffleMode,
            shuffledOrder = if (_state.value.shuffleMode == ShuffleMode.ON) buildShuffled(items.size, startIndex) else emptyList()
        )
    }

    fun addToQueue(item: PlaybackItem, playNext: Boolean = false) {
        _state.update { current ->
            if (current.items.any { it.file.stableId == item.file.stableId }) return@update current
            val mutable = current.items.toMutableList()
            val insertAt = if (playNext && current.currentIndex >= 0) current.currentIndex + 1 else mutable.size
            mutable.add(insertAt.coerceIn(0, mutable.size), item)
            val newIndex = if (current.currentIndex == -1) 0 else current.currentIndex
            current.copy(items = mutable, currentIndex = newIndex)
        }
    }

    fun addAll(items: List<PlaybackItem>) {
        _state.update { current ->
            val existingIds = current.items.map { it.file.stableId }.toSet()
            val newItems = items.filterNot { it.file.stableId in existingIds }
            if (newItems.isEmpty()) return@update current
            val combined = current.items + newItems
            val newIndex = if (current.currentIndex == -1 && combined.isNotEmpty()) 0 else current.currentIndex
            current.copy(items = combined, currentIndex = newIndex)
        }
    }

    fun removeAt(index: Int) {
        _state.update { current ->
            if (index !in current.items.indices) return@update current
            val mutable = current.items.toMutableList().apply { removeAt(index) }
            val newIndex = when {
                mutable.isEmpty() -> -1
                index < current.currentIndex -> current.currentIndex - 1
                index == current.currentIndex -> if (current.currentIndex >= mutable.size) mutable.size - 1 else current.currentIndex
                else -> current.currentIndex
            }
            current.copy(items = mutable, currentIndex = newIndex)
        }
    }

    fun removeById(stableId: String) {
        val idx = _state.value.items.indexOfFirst { it.file.stableId == stableId }
        if (idx >= 0) removeAt(idx)
    }

    fun move(from: Int, to: Int) {
        _state.update { current ->
            if (from !in current.items.indices || to !in current.items.indices) return@update current
            val mutable = current.items.toMutableList()
            val item = mutable.removeAt(from)
            mutable.add(to, item)
            val newCurrent = when {
                current.currentIndex == from -> to
                from < current.currentIndex && to >= current.currentIndex -> current.currentIndex - 1
                from > current.currentIndex && to <= current.currentIndex -> current.currentIndex + 1
                else -> current.currentIndex
            }
            current.copy(items = mutable, currentIndex = newCurrent)
        }
    }

    fun next(): PlaybackItem? {
        val current = _state.value
        if (current.items.isEmpty()) return null
        val nextIndex = when {
            current.shuffleMode == ShuffleMode.ON -> {
                val order = current.shuffledOrder
                if (order.isEmpty()) return null
                val pos = order.indexOf(current.currentIndex)
                if (pos == -1) order.firstOrNull()?.let { return current.items.getOrNull(it) } else if (pos + 1 < order.size) order[pos + 1] else if (current.repeatMode == RepeatMode.ALL) order.first() else return null
                order[pos + 1]
            }
            current.currentIndex + 1 < current.items.size -> current.currentIndex + 1
            current.repeatMode == RepeatMode.ALL -> 0
            else -> return null
        }
        _state.update { it.copy(currentIndex = nextIndex) }
        return _state.value.currentItem
    }

    fun previous(): PlaybackItem? {
        val current = _state.value
        if (current.items.isEmpty()) return null
        val prevIndex = when {
            current.shuffleMode == ShuffleMode.ON -> {
                val order = current.shuffledOrder
                val pos = order.indexOf(current.currentIndex)
                if (pos <= 0) return if (current.repeatMode == RepeatMode.ALL) order.lastOrNull()?.let { current.items.getOrNull(it); it } else null else order[pos - 1]
            }
            current.currentIndex > 0 -> current.currentIndex - 1
            current.repeatMode == RepeatMode.ALL -> current.items.size - 1
            else -> return null
        }
        _state.update { it.copy(currentIndex = prevIndex) }
        return _state.value.currentItem
    }

    fun select(index: Int): PlaybackItem? {
        if (index !in _state.value.items.indices) return null
        _state.update { it.copy(currentIndex = index) }
        return _state.value.currentItem
    }

    fun current(): PlaybackItem? = _state.value.currentItem

    fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeatMode = mode) }
    }

    fun toggleRepeat(): RepeatMode {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(next)
        return next
    }

    fun setShuffle(enabled: Boolean) {
        _state.update { current ->
            if (enabled) {
                val order = buildShuffled(current.items.size, current.currentIndex)
                current.copy(shuffleMode = ShuffleMode.ON, shuffledOrder = order)
            } else {
                current.copy(shuffleMode = ShuffleMode.OFF, shuffledOrder = emptyList())
            }
        }
    }

    fun toggleShuffle(): ShuffleMode {
        val enabled = _state.value.shuffleMode == ShuffleMode.OFF
        setShuffle(enabled)
        return _state.value.shuffleMode
    }

    fun clear() {
        _state.value = PlaybackQueueState()
    }

    fun updateItem(updated: PlaybackItem) {
        _state.update { current ->
            val idx = current.items.indexOfFirst { it.file.stableId == updated.file.stableId }
            if (idx == -1) return@update current
            val mutable = current.items.toMutableList()
            mutable[idx] = updated
            current.copy(items = mutable)
        }
    }

    private fun buildShuffled(size: Int, currentIndex: Int): List<Int> {
        if (size <= 1) return (0 until size).toList()
        val remaining = (0 until size).filter { it != currentIndex }.shuffled(Random(System.currentTimeMillis()))
        return listOf(currentIndex) + remaining
    }
}
