package dev.davidv.londontransport.ui

import android.content.Context
import androidx.compose.runtime.Composable
import dev.davidv.londontransport.santander.HireResult
import kotlinx.coroutines.CoroutineScope

fun createNativePaymentGraph(context: Context, scope: CoroutineScope) = Unit

@Composable
fun NativeAccessPurchase(flow: HireFlow.NeedsAccess, onCompleted: (HireResult) -> Unit) = Unit
