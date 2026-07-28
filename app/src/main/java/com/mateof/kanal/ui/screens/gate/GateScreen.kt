package com.mateof.kanal.ui.screens.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.R
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GateViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {
    val hasSources = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch {
            hasSources.value = prefs.sources.first().isNotEmpty()
        }
    }
}

/** Decides between onboarding and the app proper before anything is drawn. */
@Composable
fun GateScreen(onNeedsSetup: () -> Unit, onReady: () -> Unit) {
    val vm: GateViewModel = hiltViewModel()

    LaunchedEffect(Unit) {
        val ready = vm.hasSources.first { it != null } == true
        if (ready) onReady() else onNeedsSetup()
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_kanal_mark),
            contentDescription = "Kanal",
            tint = Color.Unspecified,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text("Kanal", style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = KanalColors.Accent, strokeWidth = 3.dp)
    }
}
