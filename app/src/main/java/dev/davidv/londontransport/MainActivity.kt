package dev.davidv.londontransport

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import dev.davidv.londontransport.ui.PurchaseViewModel
import dev.davidv.londontransport.ui.AccountViewModel
import androidx.fragment.app.FragmentActivity
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

class MainActivity : FragmentActivity() {
  private lateinit var purchases: PurchaseViewModel
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
        HireViewModel(graph.santander, graph.account, graph.workouts)
      }
    }

    val purchaseFactory = viewModelFactory {
      initializer { PurchaseViewModel(graph.purchaseApi, graph.account, graph.paymentMethods, graph.paymentVault, graph.pendingPurchases) }
    }
    purchases = ViewModelProvider(this, purchaseFactory)[PurchaseViewModel::class.java]
    val accountFactory = viewModelFactory {
      initializer { AccountViewModel(graph.accountApi, graph.purchaseApi, graph.account) }
    }
    receivePaymentReturn(intent)

    setContent {
      LondonTransportTheme {
        CyclesApp(
          viewModel(factory = cycles),
          viewModel(factory = routes),
          viewModel(factory = hire),
          purchases,
          graph.paymentVault,
          viewModel(factory = accountFactory),
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    receivePaymentReturn(intent)
  }

  private fun receivePaymentReturn(intent: Intent) {
    val url = intent.data ?: return
    if (url.scheme == "londontransport" && url.host == "hpp_purchase") purchases.returned(url.toString())
  }

}
