package com.evdoctor.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private val SPP_UUID: UUID =
    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    private val adapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothAdapter::class.java)
    }
    private var permissionResultAction: (() -> Unit)? = null
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionResultAction?.invoke()
            permissionResultAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EvDoctorScreen(
                    bondedDevices = { listBondedDevices() },
                    askPermissions = { onResult -> requestBluetoothPermissions(onResult) },
                    connect = { device -> connectAndProbe(device) }
                )
            }
        }
    }

    private fun requestBluetoothPermissions(onResult: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onResult()
            return
        }
        permissionResultAction = onResult
        val requested = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionRequest.launch(requested)
    }

    private fun listBondedDevices(): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return try {
            adapter?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    private suspend fun connectAndProbe(device: BluetoothDevice): String =
        withContext(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                val input = socket.inputStream
                val output = socket.outputStream
                fun command(cmd: String): String {
                    output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val response = StringBuilder()
                    val endAt = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < endAt) {
                        while (input.available() > 0) {
                            val value = input.read()
                            if (value >= 0) {
                                val ch = value.toChar()
                                if (ch == '>') return response.toString().trim()
                                response.append(ch)
                            }
                        }
                        Thread.sleep(25)
                    }
                    return response.toString().trim()
                }
                val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
                val result = commands.joinToString("\n\n") { cmd ->
                    "$ $cmd\n${command(cmd).ifBlank { "(нет ответа)" }}"
                }
                "Bluetooth-соединение установлено.\n\nПроверка ELM327:\n$result"
            } catch (e: IOException) {
                "Ошибка подключения: ${e.message ?: "Bluetooth I/O"}"
            } catch (e: SecurityException) {
                "Нет разрешения Bluetooth. Разрешите доступ в настройках Android."
            } finally {
                try { socket?.close() } catch (_: IOException) { }
            }
        }
}

@Composable
private fun EvDoctorScreen(
    bondedDevices: () -> List<BluetoothDevice>,
    askPermissions: (() -> Unit) -> Unit,
    connect: suspend (BluetoothDevice) -> String
) {
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var status by remember {
        mutableStateOf("Подключите адаптер к OBD-разъёму и выполните сопряжение в настройках Bluetooth Android.")
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("EV BATTERY DOCTOR", style = MaterialTheme.typography.headlineSmall)
                Text("Профессиональная диагностика электромобилей · Alpha 0.1.0",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("СОСТОЯНИЕ СИСТЕМЫ", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Button(onClick = {
                    askPermissions {
                        devices = bondedDevices()
                        status = if (devices.isEmpty()) {
                            "Сопряжённые устройства не найдены. Проверьте разрешение «Устройства поблизости» и сопряжение CHX в настройках Bluetooth телефона."
                        } else "Найдено устройств: ${devices.size}. Выберите адаптер."
                    }
                }) { Text("Обновить устройства") }
            }
            item { Text("СОПРЯЖЁННЫЕ АДАПТЕРЫ", style = MaterialTheme.typography.titleMedium) }
            if (devices.isEmpty()) {
                item { Text("Список пока пуст. Нажмите «Обновить устройства».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(devices, key = { it.address }) { device ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name ?: "OBD2 устройство")
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            status = "Подключение к адаптеру…"
                            scope.launch {
                                status = connect(device)
                                busy = false
                            }
                        }) { Text("Проверить") }
                    }
                }
            }
            item {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Ограничения этой сборки: поддерживается первичная проверка Bluetooth Classic SPP и базовых AT-команд ELM327. BLE, Wi‑Fi, CAN/UDS-профили конкретных автомобилей и параметры BMS будут добавляться после проверки оборудования и протоколов. SOH и напряжения ячеек пока не рассчитываются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
