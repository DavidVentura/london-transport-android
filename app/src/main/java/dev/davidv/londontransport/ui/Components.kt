package dev.davidv.londontransport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One tap target. A screen offers at most one primary (filled) and one secondary (text) action. */
data class UiAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

enum class Tone { Positive, Neutral, Negative }

/**
 * A screen pushed over the map. Back always steps back through the flow; [backEnabled] holds it
 * while something irreversible is in flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
  title: String,
  onBack: () -> Unit,
  backEnabled: Boolean = true,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  BackHandler { if (backEnabled) onBack() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack, enabled = backEnabled) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = actions,
      )
    },
    content = content,
  )
}

@Composable
fun CenteredProgress(label: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator()
    Text(
      label,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 20.dp),
    )
  }
}

/** The outcome of a flow, centred on its own screen, with what to do next underneath. */
@Composable
fun StatusMessage(
  icon: ImageVector,
  tone: Tone,
  title: String,
  message: String,
  primary: UiAction?,
  secondary: UiAction? = null,
  modifier: Modifier = Modifier,
  extra: @Composable ColumnScope.() -> Unit = {},
) {
  val (container, content) = toneColours(tone)
  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = Modifier.size(72.dp)) {
      Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp)) }
    }
    Text(
      title,
      style = MaterialTheme.typography.headlineSmall,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 24.dp),
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 12.dp),
    )
    extra()
    Spacer(Modifier.height(32.dp))
    Column(Modifier.widthIn(max = 360.dp)) { ActionButtons(primary, secondary) }
  }
}

@Composable
private fun toneColours(tone: Tone): Pair<Color, Color> {
  val scheme = MaterialTheme.colorScheme
  return when (tone) {
    Tone.Positive -> scheme.primaryContainer to scheme.onPrimaryContainer
    Tone.Neutral -> scheme.secondaryContainer to scheme.onSecondaryContainer
    Tone.Negative -> scheme.errorContainer to scheme.onErrorContainer
  }
}

/** Primary filled on top, secondary as text underneath, both full width. */
@Composable
fun ActionButtons(primary: UiAction?, secondary: UiAction? = null, busy: Boolean = false, busyLabel: String? = null) {
  primary?.let { action ->
    Button(onClick = action.onClick, enabled = action.enabled && !busy, modifier = Modifier.fillMaxWidth()) {
      if (busy) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = LocalContentColor.current, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
      }
      Text(if (busy && busyLabel != null) busyLabel else action.label)
    }
  }
  secondary?.let { action ->
    TextButton(onClick = action.onClick, enabled = action.enabled && !busy, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
      Text(action.label)
    }
  }
}

/** A problem that applies to the whole screen, with the fix one tap away. */
@Composable
fun ErrorBanner(message: String, action: UiAction?, modifier: Modifier = Modifier) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
      Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
      Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
      action?.let { TextButton(onClick = it.onClick, enabled = it.enabled) { Text(it.label) } }
    }
  }
}

/** A problem with what was just entered or attempted, shown next to it. */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Icon(
      Icons.Outlined.ErrorOutline,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.error,
      modifier = Modifier.size(18.dp).padding(top = 1.dp),
    )
    Text(
      message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(start = 8.dp),
    )
  }
}

/** The shared layout of every bottom sheet: headline, optional supporting line, then content. */
@Composable
fun SheetBody(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    subtitle?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    Spacer(Modifier.height(20.dp))
    content()
  }
}

@Composable
fun LabelledRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
