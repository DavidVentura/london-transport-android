package dev.davidv.londoncycles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.davidv.londoncycles.ui.CyclesApp
import dev.davidv.londoncycles.ui.CyclesViewModel
import dev.davidv.londoncycles.ui.RoutesViewModel
import dev.davidv.londoncycles.ui.LondonCyclesTheme

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

    setContent {
      LondonCyclesTheme {
        CyclesApp(viewModel(factory = cycles), viewModel(factory = routes))
      }
    }
  }
}
