package dev.davidv.londontransport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.santander.RideTutorial

/** The unlock only resumes from the last page, so every page is seen before a bike is released. */
@Composable
fun RideTutorialSheet(tutorial: RideTutorial, busy: Boolean, error: String?, onContinue: () -> Unit) {
  var page by rememberSaveable(tutorial) { mutableIntStateOf(0) }
  val current = tutorial.pages[page]
  val last = page == tutorial.pages.lastIndex
  BackHandler(enabled = busy || page > 0) { if (!busy) page-- }
  Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
    Text("Before your first ride", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Column(Modifier.heightIn(min = 180.dp).padding(top = 12.dp)) {
      Text(current.title, style = MaterialTheme.typography.headlineSmall)
      Text(
        current.body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
      )
    }
    error?.let { InlineError(it, Modifier.padding(top = 16.dp)) }
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
      PageDots(count = tutorial.pages.size, current = page)
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
      if (page > 0) TextButton(onClick = { page-- }, enabled = !busy) { Text("Back") }
      Spacer(Modifier.weight(1f))
      Button(onClick = { if (last) onContinue() else page++ }, enabled = !busy) {
        if (busy) {
          CircularProgressIndicator(strokeWidth = 2.dp, color = LocalContentColor.current, modifier = Modifier.size(18.dp))
          Spacer(Modifier.size(8.dp))
        }
        Text(if (busy) "Unlocking…" else if (last) "Continue and unlock" else "Next")
      }
    }
  }
}

@Composable
private fun PageDots(count: Int, current: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    repeat(count) { index ->
      Box(
        Modifier.size(8.dp).background(
          if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
          CircleShape,
        )
      )
    }
  }
}
