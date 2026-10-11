package dev.davidv.londontransport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.davidv.londontransport.data.StoredPayment
import dev.davidv.londontransport.domain.PaymentMethodId
import dev.davidv.londontransport.domain.SaveRequest
import dev.davidv.londontransport.domain.SavedPaymentMethod
import dev.davidv.londontransport.santander.BillingAddress
import dev.davidv.londontransport.santander.CardDetails
import dev.davidv.londontransport.santander.CardType
import dev.davidv.londontransport.santander.Cvv
import dev.davidv.londontransport.santander.PaymentForm
import dev.davidv.londontransport.santander.PaymentMethod
import kotlinx.coroutines.launch

private const val CLEARED =
  "Saved payment methods were removed because this phone's screen lock or fingerprints changed."

private sealed interface Choice {
  data class Saved(val method: SavedPaymentMethod) : Choice

  data object New : Choice
}

/**
 * Card fields sit in plain `remember`: `rememberSaveable` would write the card number into the
 * saved-instance bundle the system keeps on disk.
 */
@Composable
fun PaymentSheet(
  form: PaymentForm,
  busy: Boolean,
  errors: List<String>,
  save: SaveRequest?,
  savedAs: PaymentMethodId?,
  draft: StoredPayment?,
  saved: List<SavedPaymentMethod>,
  unlocker: PaymentUnlocker,
  onPay: (CardDetails, SaveRequest?) -> Unit,
  onEdit: (SavedPaymentMethod, StoredPayment) -> Unit,
  onStopEditing: () -> Unit,
  onForget: (PaymentMethodId) -> Unit,
  onForgetAll: () -> Unit,
) {
  val canSave = remember { unlocker.available() }
  val offered = if (canSave) saved else emptyList()
  val editing = savedAs?.let { id -> offered.firstOrNull { it.id == id } }
  var choice by remember { mutableStateOf(offered.firstOrNull()?.let(Choice::Saved) ?: Choice.New) }
  val current = choice.takeIf { it !is Choice.Saved || it.method in offered } ?: Choice.New
  var unlocking by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }
  var askSave by remember { mutableStateOf<CardDetails?>(null) }
  val scope = rememberCoroutineScope()
  val working = busy || unlocking

  fun unlock(method: SavedPaymentMethod, then: (StoredPayment) -> Unit) {
    scope.launch {
      unlocking = true
      notice = null
      when (val opened = unlocker.open(method)) {
        is Unlocked.Done -> then(opened.value)
        is Unlocked.Declined -> notice = opened.message
        Unlocked.Invalidated -> {
          onForgetAll()
          notice = CLEARED
        }
      }
      unlocking = false
    }
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
  ) {
    Text(form.title, style = MaterialTheme.typography.headlineSmall)

    (errors + listOfNotNull(notice)).forEach { error ->
      Text(
        error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
      )
    }

    if (editing != null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      ) {
        Text(
          "Editing “${editing.name}”",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStopEditing, enabled = !working) { Text("Done") }
      }
      key(editing.id) {
        NewPayment(
          form = form,
          draft = draft,
          working = working,
          onPay = { card -> onPay(card, save) },
        )
      }
      return@Column
    }

    if (offered.isNotEmpty()) {
      offered.forEach { method ->
        ChoiceRow(
          selected = current == Choice.Saved(method),
          enabled = !working,
          onSelect = { choice = Choice.Saved(method) },
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(method.name, style = MaterialTheme.typography.bodyLarge)
            Text(
              if (method.cvvSaved) "${method.summary} · CVV saved" else method.summary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(
            onClick = { unlock(method) { opened -> onEdit(method, opened) } },
            enabled = !working,
          ) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${method.name}")
          }
          IconButton(onClick = { onForget(method.id) }, enabled = !working) {
            Icon(Icons.Outlined.Delete, contentDescription = "Forget ${method.name}")
          }
        }
      }
      ChoiceRow(
        selected = current == Choice.New,
        enabled = !working,
        onSelect = { choice = Choice.New },
      ) {
        Text(
          "New payment method",
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.weight(1f),
        )
      }
    }

    when (current) {
      is Choice.Saved ->
        SavedPayment(
          method = current.method,
          working = working,
          onPay = { typed ->
            unlock(current.method) { opened ->
              onPay(CardDetails(opened.method, opened.cvv ?: checkNotNull(typed)), null)
            }
          },
        )
      Choice.New ->
        NewPayment(
          form = form,
          draft = draft,
          working = working,
          onPay = { card -> if (canSave) askSave = card else onPay(card, null) },
        )
    }
  }

  askSave?.let { card ->
    SaveDialog(
      summary = card.method.summary,
      onSave = { name, keepCvv ->
        askSave = null
        onPay(card, SaveRequest(name.trim(), keepCvv))
      },
      onSkip = {
        askSave = null
        onPay(card, null)
      },
      onDismiss = { askSave = null },
    )
  }
}

@Composable
private fun ChoiceRow(
  selected: Boolean,
  enabled: Boolean,
  onSelect: () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(top = 8.dp),
  ) {
    RadioButton(selected = selected, onClick = null, enabled = enabled)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f).padding(start = 12.dp),
      content = content,
    )
  }
}

/** A saved CVV means one tap and an unlock; without one, the CVV is the only thing to type. */
@Composable
private fun SavedPayment(method: SavedPaymentMethod, working: Boolean, onPay: (Cvv?) -> Unit) {
  var cvv by remember(method.id) { mutableStateOf("") }
  if (!method.cvvSaved) {
    CardField(
      "CVV",
      cvv,
      { cvv = it.filter(Char::isDigit).take(4) },
      working,
      keyboard = KeyboardType.NumberPassword,
      visual = PasswordVisualTransformation(),
    )
  }
  PayButton(
    label = "Pay with ${method.name}",
    enabled = method.cvvSaved || cvv.length >= 3,
    working = working,
    onClick = { onPay(if (method.cvvSaved) null else Cvv(cvv)) },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewPayment(
  form: PaymentForm,
  draft: StoredPayment?,
  working: Boolean,
  onPay: (CardDetails) -> Unit,
) {
  val method = draft?.method
  var cardholder by remember { mutableStateOf(method?.cardholder ?: form.cardholder) }
  var type by remember { mutableStateOf(method?.type?.takeIf { it in form.cardTypes }) }
  var number by remember { mutableStateOf(method?.number.orEmpty()) }
  var expiry by remember { mutableStateOf(method?.let { it.expiryMonth + it.expiryYear }.orEmpty()) }
  var cvv by remember { mutableStateOf(draft?.cvv?.value.orEmpty()) }
  var nameOrNumber by remember { mutableStateOf(method?.address?.nameOrNumber.orEmpty()) }
  var street by remember { mutableStateOf(method?.address?.street.orEmpty()) }
  var city by remember { mutableStateOf(method?.address?.city.orEmpty()) }
  var county by remember { mutableStateOf(method?.address?.county.orEmpty()) }
  var country by remember { mutableStateOf(method?.address?.country.orEmpty()) }
  var postcode by remember { mutableStateOf(method?.address?.postcode.orEmpty()) }
  var autopay by remember { mutableStateOf(method?.autopay ?: form.autopay) }

  val chosenType = type
  val complete =
    chosenType != null &&
      listOf(cardholder, number, nameOrNumber, street, city, country, postcode).all(String::isNotBlank) &&
      expiry.length == 4 &&
      cvv.length >= 3

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(top = 12.dp),
  ) {
    form.cardTypes.forEach { option ->
      FilterChip(
        selected = option == type,
        onClick = { type = option },
        label = { Text(option.label) },
        enabled = !working,
      )
    }
  }

  CardField("Cardholder name", cardholder, { cardholder = it }, working)
  CardField(
    "Card number",
    number,
    { number = it.filter(Char::isDigit).take(19) },
    working,
    keyboard = KeyboardType.Number,
  )
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CardField(
      "Expiration (MM/YY)",
      expiry,
      { expiry = it.filter(Char::isDigit).take(4) },
      working,
      keyboard = KeyboardType.Number,
      visual = ExpirySlash,
      modifier = Modifier.weight(2f),
    )
    CardField(
      "CVV",
      cvv,
      { cvv = it.filter(Char::isDigit).take(4) },
      working,
      keyboard = KeyboardType.NumberPassword,
      visual = PasswordVisualTransformation(),
      modifier = Modifier.weight(1f),
    )
  }

  Text(
    "Billing address",
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 20.dp),
  )
  CardField("Address name / number", nameOrNumber, { nameOrNumber = it }, working)
  CardField("Street", street, { street = it }, working)
  CardField("City", city, { city = it }, working)
  CardField("County (optional)", county, { county = it }, working)
  CardField("Country", country, { country = it }, working)
  CardField(
    "Postcode",
    postcode,
    { postcode = it.uppercase() },
    working,
    capitalization = KeyboardCapitalization.Characters,
  )

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
  ) {
    Text("Auto pay", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked = autopay, onCheckedChange = { autopay = it }, enabled = !working)
  }

  PayButton(
    label = "Pay",
    enabled = complete,
    working = working,
    onClick = {
      onPay(
        CardDetails(
          method =
            PaymentMethod(
              cardholder = cardholder.trim(),
              type = checkNotNull(chosenType),
              number = number,
              expiryMonth = expiry.take(2),
              expiryYear = expiry.drop(2),
              address =
                BillingAddress(
                  nameOrNumber = nameOrNumber.trim(),
                  street = street.trim(),
                  city = city.trim(),
                  county = county.trim(),
                  country = country.trim(),
                  postcode = postcode.trim(),
                ),
              autopay = autopay,
            ),
          cvv = Cvv(cvv),
        )
      )
    },
  )
}

/** Shows the four typed digits as MM/YY; the slash is display only and never part of the value. */
private object ExpirySlash : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val digits = text.text
    val shown = if (digits.length > 2) digits.take(2) + "/" + digits.drop(2) else digits
    val offsets =
      object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int = if (offset > 2) offset + 1 else offset

        override fun transformedToOriginal(offset: Int): Int =
          if (offset > 2) offset - 1 else offset
      }
    return TransformedText(AnnotatedString(shown), offsets)
  }
}

@Composable
private fun SaveDialog(
  summary: String,
  onSave: (name: String, keepCvv: Boolean) -> Unit,
  onSkip: () -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(summary) }
  var keepCvv by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Save for next time?") },
    text = {
      Column {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = 12.dp),
        ) {
          Checkbox(checked = keepCvv, onCheckedChange = { keepCvv = it })
          Text("Also save the CVV", style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, keepCvv) }, enabled = name.isNotBlank()) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onSkip) { Text("Don't save") } },
  )
}

@Composable
private fun PayButton(label: String, enabled: Boolean, working: Boolean, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = enabled && !working,
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
  ) {
    if (working) {
      CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(16.dp),
      )
    }
    Text(
      if (working) "Processing payment…" else label,
      modifier = Modifier.padding(start = if (working) 12.dp else 0.dp),
    )
  }
}

@Composable
private fun CardField(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  working: Boolean,
  modifier: Modifier = Modifier.fillMaxWidth(),
  keyboard: KeyboardType = KeyboardType.Text,
  visual: VisualTransformation = VisualTransformation.None,
  capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    enabled = !working,
    visualTransformation = visual,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = capitalization),
    modifier = modifier.padding(top = 8.dp),
  )
}
