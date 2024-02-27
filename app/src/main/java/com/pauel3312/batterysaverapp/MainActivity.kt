package com.pauel3312.batterysaverapp

import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.pauel3312.batterysaverapp.ui.theme.BatterySaverAppTheme
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import android.Manifest

const val REQUEST_ENABLE_BT = 1
const val chargeRateMinutePerPercent = 2
val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
var bluetoothAdapter: BluetoothAdapter? = null
var bluetoothStreamThread: BluetoothConnectedThread? = null

var isCharging: Boolean = false
var forceCharge: Boolean = false
var isTimeSet: Boolean = false


class MainActivity :AppCompatActivity() {

    private var batteryStatus: Intent? = null
    private var batteryPct: MutableState<Int> = mutableStateOf(0)

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mCalendar = Calendar.getInstance()
            mCalendar.time = Date.from(LocalDateTime.now().toInstant(ZoneOffset.systemDefault().rules.getOffset(Instant.now())))
            val mHour = mCalendar[Calendar.HOUR_OF_DAY]
            val mMinute = mCalendar[Calendar.MINUTE]
            val mTime = remember { mutableStateOf("") }
            val mOverrideOn = remember { mutableStateOf(false) }
            val mOverrideState = remember { mutableStateOf(false) }



            this.batteryStatus = getBatteryStatusReceiver()
            this.batteryPct = remember {
                mutableStateOf(0)
            }
            var sliderRange by remember { mutableStateOf(75f..80f) }

            var battery by remember { mutableStateOf(0) }

            var currentInstant = getCurrentInstant()
            lateinit var chargeStartDateTime: Date
            offsetDay(currentInstant, mCalendar)

            SystemBroadcastReceiver(Intent.ACTION_BATTERY_CHANGED) { batteryStatus ->
                val batteryPct: Float? = batteryStatus?.let { intent ->
                    val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    level * 100 / scale.toFloat()
                }
                battery = batteryPct!!.toInt()
                currentInstant = getCurrentInstant()
                chargeStartDateTime = getChargeStartTime(currentInstant, battery)
                offsetDay(currentInstant, mCalendar)
                val result = getChargerStatus(sliderRange, battery, mCalendar.time, chargeStartDateTime,
                    mOverrideOn.value, mOverrideState.value)
                bluetoothStreamThread?.write(result.toByteArray())
            }

            val mTimePickerDialog = TimePickerDialog(peekAvailableContext(),
                {_,cHour : Int, cMinute : Int ->
                    mCalendar[Calendar.HOUR_OF_DAY] = cHour
                    mCalendar[Calendar.MINUTE] = cMinute
                }, mHour, mMinute, true)
                mTimePickerDialog.setOnDismissListener {
                    currentInstant = getCurrentInstant()
                    chargeStartDateTime = getChargeStartTime(currentInstant, battery)
                    offsetDay(currentInstant, mCalendar)
                    mTime.value = "${mCalendar[Calendar.HOUR_OF_DAY]}:${mCalendar[Calendar.MINUTE]}"
                    isTimeSet = true
                }
            BatterySaverAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                    Column(Modifier.padding(16.dp)) {
                        DisplayBatteryPercentage(batteryPercentage = battery)
                        val sliderValues = rangeToIntList(sliderRange)
                        Text(text = "battery charge will always stay between: " +
                                    "\n${sliderValues[0]} and ${sliderValues[1]}% while plugged in")
                        RangeSlider(values = sliderRange,
                            onValueChange = { sliderRange = it },
                            valueRange = 0f..100f)
                        Text(text = "at what time should your phone be 100% charged?\n" +
                                "currently: ${mTime.value}")
                        Button(onClick = { mTimePickerDialog.show()
                        }) {
                            Text(text = "pick time")
                        }
                        Row {
                            Switch(checked = mOverrideOn.value, onCheckedChange = {mOverrideOn.value = it})
                            Text(text = "Manual Override", modifier = Modifier.padding(1.dp, 12.dp))
                        }
                        Row {
                            Switch(checked = mOverrideState.value, onCheckedChange = {mOverrideState.value = it})
                            Text(text = "Manual override state", modifier = Modifier.padding(1.dp, 12.dp))
                        }
                    }
                }
            }
        }
        when {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED -> {
                val bluetoothManager: BluetoothManager =
                    getSystemService(peekAvailableContext()!!, BluetoothManager::class.java)!!
                bluetoothAdapter = bluetoothManager.adapter
                if (bluetoothAdapter == null) {
                    // Device doesn't support Bluetooth
                    Log.e(TAG, "Device does not support bluetooth")
                }
                bluetoothAdapter?.cancelDiscovery()
                if (bluetoothAdapter?.isEnabled == false) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    Log.d(TAG, "Request bluetooth enabling")
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                    while (!bluetoothAdapter!!.isEnabled) {
                        Thread.sleep(100)
                    }
                }
                val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
                pairedDevices?.forEach { device ->
                    val deviceName = device.name
                    val deviceHardwareAddress = device.address // MAC address
                    if (deviceName == "Chargeur Intelligent") {
                        Log.d(TAG, "detected $deviceName with address $deviceHardwareAddress")
                        ConnectThread(device).start()
                    }

                }
            }
        }
    }
}



private class ConnectThread(device: BluetoothDevice) : Thread() {

    private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
        device.createRfcommSocketToServiceRecord(BT_UUID)
    }

    override fun run() {
        // Cancel discovery because it otherwise slows down the connection.
        Log.d(TAG, "ConnectThread running")
        bluetoothAdapter?.cancelDiscovery()

        mmSocket?.let { socket ->
            // Connect to the remote device through the socket. This call blocks
            // until it succeeds or throws an exception.
            var failureCounter = 0
            var isConnected = false
            while (!isConnected) {
                try {
                    socket.connect()
                } catch (e: IOException) {
                    failureCounter++
                    Log.w(TAG, "connecting failed $failureCounter times.")
                    if (failureCounter == 5) {
                        Log.w(TAG, "Bluetooth device failed to connect, error $e")
                        break
                    } else {
                        continue
                    }
                }
                isConnected = true
            }
            if (isConnected) {
                bluetoothStreamThread = BluetoothConnectedThread(socket)
            }
        }
    }

    // Closes the client socket and causes the thread to finish.
    fun cancel() {
        try {
            mmSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the client socket", e)
        }
    }
}


@Composable
fun getBatteryStatusReceiver(): Intent? {
    val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
        LocalContext.current.applicationContext.registerReceiver(null, ifilter)
    }
    return batteryStatus
}
@Composable
fun DisplayBatteryPercentage(batteryPercentage: Int) {
        Text("battery is at ${batteryPercentage}%")
}

fun rangeToIntList(range: ClosedRange<Float>): List<Int> {
    val rangeStr = range.toString()
    val rangeList = rangeStr.split('.')
    return listOf(rangeList[0].toInt(), rangeList[3].toInt())

}


@Composable
fun SystemBroadcastReceiver(
    systemAction: String,
    onSystemEvent: (intent: Intent?) -> Unit
) {
    // Grab the current context in this part of the UI tree
    val context = LocalContext.current

    // Safely use the latest onSystemEvent lambda passed to the function
    val currentOnSystemEvent by rememberUpdatedState(onSystemEvent)

    // If either context or systemAction changes, unregister and register again
    DisposableEffect(context, systemAction) {
        val intentFilter = IntentFilter(systemAction)
        val broadcast = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                currentOnSystemEvent(intent)
            }
        }

        context.registerReceiver(broadcast, intentFilter)

        // When the effect leaves the Composition, remove the callback
        onDispose {
            context.unregisterReceiver(broadcast)
        }
    }
}




fun getChargerStatus(
    sliderRange: ClosedFloatingPointRange<Float>,
    batteryPercentage: Int,
    chargeEndTime: Date,
    chargeStartTime: Date,
    overrideOn: Boolean,
    overrideMode: Boolean
): String {
    if (overrideOn) { return if (overrideMode) "1\n" else "2\n" }
    val sliderValues = rangeToIntList(sliderRange)

    if (forceCharge) {
        return "1\n"
    }

    if (chargeStartTime.after(chargeEndTime) && isTimeSet) {
        isCharging = true
        forceCharge = true
        return "1\n"
    }

    if (!(sliderValues[0] < batteryPercentage
                && sliderValues[1] > batteryPercentage)
    ) {
        isCharging = sliderValues[0] >= batteryPercentage
    }
    return if (isCharging) "1\n" else "2\n"
}

class BluetoothConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
    private val mmOutStream: OutputStream = mmSocket.outputStream
    private val mmInStream: InputStream = mmSocket.inputStream
    private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream


    override fun run() {
        var numBytes: Int // bytes returned from read()

        // Keep listening to the InputStream until an exception occurs.
        while (true) {
            // Read from the InputStream.
            numBytes = try {
                mmInStream.read(mmBuffer)
            } catch (e: IOException) {
                Log.d(TAG, "Input stream was disconnected", e)
                break
            }
        }
    }
    fun write(bytes: ByteArray) {
        try {
            mmOutStream.write(bytes)
        } catch (e: IOException) {
            Log.e(TAG, "Error occurred when sending data", e)
        }
        return
    }

    fun cancel() {
        try {
            mmSocket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the connect socket", e)
        }
    }
}

fun getCurrentInstant():Instant{
    return LocalDateTime.now().toInstant(ZoneOffset.systemDefault().rules.getOffset(Instant.now()))
}

fun offsetDay(currentInstant: Instant, mCalendar: Calendar) {
    if (mCalendar.time.before(Date.from(currentInstant)))
    {mCalendar[Calendar.DAY_OF_YEAR] += 1}
    if (mCalendar.time.after(Date.from(currentInstant.plusSeconds(24*3600))))
    {mCalendar[Calendar.DAY_OF_YEAR] -= 1}
}

fun getChargeStartTime(currentInstant: Instant, battery: Int): Date {
    return Date.from(currentInstant.plusSeconds(60*(chargeRateMinutePerPercent)*(100-battery).toLong()))
}
