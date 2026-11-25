package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.TopicManualState
import com.example.data.repository.XRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InterestInspectorViewModel(
    private val repository: XRepository,
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = repository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val topics: StateFlow<List<TopicInterestEntity>> = repository.topicInterestsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val creators: StateFlow<List<AuthorAffinityEntity>> = repository.affinitiesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seenCount: StateFlow<Int> = repository.seenCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val dismissedCount: StateFlow<Int> = repository.dismissedCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val riskySuppressedCount: StateFlow<Int> = repository.riskySuppressedCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.rankingSignals.ensureInitialized()
        }
    }

    fun addTopic(rawInput: String) {
        viewModelScope.launch {
            repository.addManualTopic(rawInput)
                .onSuccess { added ->
                    _events.tryEmit("Boosted interest: ${added.displayLabel}")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Unable to add topic")
                }
        }
    }

    fun toggleBoostTopic(topic: TopicInterestEntity) {
        val next = if (topic.manualState == TopicManualState.BOOSTED) {
            TopicManualState.AUTO
        } else {
            TopicManualState.BOOSTED
        }
        viewModelScope.launch {
            repository.setTopicManualState(topic.tokenLower, next)
        }
    }

    fun toggleDemoteTopic(topic: TopicInterestEntity) {
        val next = if (topic.manualState == TopicManualState.DEMOTED) {
            TopicManualState.AUTO
        } else {
            TopicManualState.DEMOTED
        }
        viewModelScope.launch {
            repository.setTopicManualState(topic.tokenLower, next)
        }
    }

    fun removeTopic(tokenLower: String) {
        viewModelScope.launch {
            repository.deleteTopic(tokenLower)
        }
    }

    fun togglePinCreator(creator: AuthorAffinityEntity) {
        viewModelScope.launch {
            repository.togglePinCreatorBoost(creator.handleLower, creator.displayName)
        }
    }

    fun toggleShowLessCreator(creator: AuthorAffinityEntity) {
        viewModelScope.launch {
            repository.setAuthorSoftDemoted(creator.handleLower, !creator.isSoftDemoted)
        }
    }

    fun resetCreator(handleLower: String) {
        viewModelScope.launch {
            repository.resetAuthorAffinity(handleLower)
            _events.tryEmit("Cleared local signals for @$handleLower")
        }
    }

    fun setDemoteSeenPosts(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferencesRepository.setDemoteSeenPosts(enabled)
        }
    }

    fun setBlendMultiSourceForYou(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferencesRepository.setBlendMultiSourceForYou(enabled)
        }
    }

    fun setExcludeRiskyAndNsfw(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferencesRepository.setExcludeRiskyAndNsfw(enabled)
            _events.tryEmit(
                if (enabled) "Risky & NSFW Firewall enabled"
                else "Risky & NSFW Firewall paused"
            )
        }
    }

    fun clearSeenHistory() {
        viewModelScope.launch {
            repository.clearSeenHistory()
            _events.tryEmit("Seen post history cleared")
        }
    }

    fun resetAllSafeSignals() {
        viewModelScope.launch {
            repository.resetLocalForYouModel()
            _events.tryEmit("Reset all on-device topics, creator scores & seen history")
        }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                InterestInspectorViewModel(repository) as T
        }
    }
}
