package dev.davidv.londontransport.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.davidv.londontransport.CyclesApplication
import dev.davidv.londontransport.data.PaymentMethodStore
import dev.davidv.londontransport.data.PaymentVault
import dev.davidv.londontransport.santander.HireResult
import dev.davidv.londontransport.santander.SANTANDER_BASE
import dev.davidv.londontransport.threeds.PaymentRequestContext
import dev.davidv.londontransport.threeds.PaymentRuntime
import dev.davidv.londontransport.threeds.PaymentTrace
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl

class NativePaymentGraph(val methods: PaymentMethodStore, val vault: PaymentVault)

fun createNativePaymentGraph(context: Context, scope: CoroutineScope): NativePaymentGraph = NativePaymentGraph(
  PaymentMethodStore(PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("payment_methods") }),
  PaymentVault(context),
)

@Composable
fun NativeAccessPurchase(flow: HireFlow.NeedsAccess, onCompleted: (HireResult) -> Unit) {
  val activity = checkNotNull(LocalActivity.current) as FragmentActivity
  val graph = (activity.application as CyclesApplication).graph
  key(flow.purchaseId) {
    val modelKey = "native-payment-${flow.purchaseId.value}"
    val factory = remember {
      val clock = Clock.systemUTC()
      val trace = PaymentTrace.Local(File(activity.noBackupFilesDir, "payment-logs/${flow.purchaseId.value}"))
      val api = RecordedNativePaymentApi(AndroidNativePaymentApi(activity.applicationContext,
        PaymentRequestContext(SANTANDER_BASE.toHttpUrl(), graph.deviceId, flow.credentials), PaymentRuntime.create(clock, trace), clock, trace), trace)
      viewModelFactory { initializer { NativePaymentViewModel(api, graph.nativePayments.methods, graph.nativePayments.vault, flow.options) } }
    }
    val model = viewModel<NativePaymentViewModel>(key = modelKey, factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val unlocker = remember(activity) { AndroidPaymentUnlocker(activity, graph.nativePayments.vault) }
    OutlinedButton(onClick = model::open, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
      Text("Try in-app payment (3DS prototype)")
    }
    val finished = state.flow as? NativePaymentFlow.Finished
    LaunchedEffect(finished) {
      finished?.let {
        if (it.loggingFault != null) Toast.makeText(activity, "Payment completed, but recording the final outcome failed.", Toast.LENGTH_LONG).show()
        onCompleted(it.result)
      }
    }
    if (state.flow != NativePaymentFlow.Closed && finished == null) {
      Dialog(onDismissRequest = model::dismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
          NativePaymentContent(state, flow.options, model, unlocker)
        }
      }
    }
  }
}

@Composable
private fun NativePaymentContent(
  state: NativePaymentState,
  options: List<dev.davidv.londontransport.santander.AccessOption>,
  model: NativePaymentViewModel,
  unlocker: PaymentUnlocker,
) {
  Column(modifier = Modifier.imePadding()) {
    Text("Experimental in-app payment", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp))
    when (val flow = state.flow) {
      NativePaymentFlow.Picking -> Column(modifier = Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
        Text("Pay can charge your card and return a bike release code. OTP is supported; banking-app and HTML challenges are not.")
        options.forEach { option ->
          Button(onClick = { model.choose(option) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("${option.name} · ${option.price}")
          }
          option.terms.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
      }
      NativePaymentFlow.Loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
      is NativePaymentFlow.Card -> PaymentSheet(flow.form, flow.busy, flow.errors, flow.save, flow.savedAs, flow.draft,
        state.saved, unlocker, model::pay, model::edit, model::stopEditing, model::forget, model::forgetAll)
      is NativePaymentFlow.Challenge -> {
        var answer by remember(flow.session) { mutableStateOf("") }
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
          Text(flow.prompt.heading, style = MaterialTheme.typography.titleLarge)
          Text(flow.prompt.information)
          OutlinedTextField(answer, { answer = it.take(45) }, label = { Text(flow.prompt.label) },
            enabled = !flow.busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
          Button(onClick = { model.answer(answer); answer = "" }, enabled = !flow.busy && answer.isNotBlank()) { Text("Verify") }
          if (flow.busy) CircularProgressIndicator()
        }
      }
      is NativePaymentFlow.Stopped -> Text(flow.message, modifier = Modifier.padding(horizontal = 24.dp))
      NativePaymentFlow.Closed, is NativePaymentFlow.Finished -> Unit
    }
    TextButton(onClick = model::dismiss, enabled = !state.busy, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
      Text(if (state.flow is NativePaymentFlow.Challenge) "Cancel verification" else "Close")
    }
  }
}
