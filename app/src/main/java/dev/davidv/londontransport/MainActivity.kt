package dev.davidv.londontransport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.davidv.londontransport.ui.CyclesApp
import dev.davidv.londontransport.ui.CyclesViewModel
import dev.davidv.londontransport.ui.HireViewModel
import dev.davidv.londontransport.ui.RoutesViewModel
import dev.davidv.londontransport.ui.LondonTransportTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val graph = (application as CyclesApplication).graph
    val cycles = viewModelFactory {
      initializer { CyclesViewModel(graph.layer, graph.favourites, graph.pins, graph.settings) }
    }
    val routes = viewModelFactory {
      initializer {
        RoutesViewModel(graph.lineClient, graph.savedLines, graph.routeCache)
      }
    }
    val hire = viewModelFactory {
      initializer {
        HireViewModel(graph.santander, graph.account, graph.activeHire, graph.workouts)
      }
    }

    setContent {
      LondonTransportTheme {
        CyclesApp(
          viewModel(factory = cycles),
          viewModel(factory = routes),
          viewModel(factory = hire),
        )
      }
    }
  }
}
