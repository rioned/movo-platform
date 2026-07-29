package com.movo.customer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
  override fun onCreate(state: Bundle?) { super.onCreate(state); setContent { CustomerHome() } }
}
@Composable fun CustomerHome() {
  var sheet by remember { mutableStateOf(false) }
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0A6847))) {
    Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFFF2F7F4))) {
      Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column { Text("MOVO", style = MaterialTheme.typography.headlineLarge, color = Color(0xFF0A6847)); Text("Deliver with confidence", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(24.dp)); Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(20.dp)) { Text("Where are we collecting from?", style = MaterialTheme.typography.titleMedium); Text("Choose pickup and destination on the map", color = Color.Gray) } } }
        Button(onClick = { sheet = true }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Send a parcel") }
      }
      if (sheet) AlertDialog(onDismissRequest = { sheet = false }, title = { Text("Request delivery") }, text = { Text("Parcel and document quotes will use ${BuildConfig.API_BASE_URL}") }, confirmButton = { TextButton(onClick = { sheet = false }) { Text("Continue") } })
    } }
  }
}
