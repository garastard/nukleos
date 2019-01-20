package me.cyber.nukleos.myosensor

import android.bluetooth.*
import android.content.Context
import android.util.Log
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.processors.PublishProcessor
import io.reactivex.subjects.BehaviorSubject
import me.cyber.nukleos.utils.isStartStreamingCommand
import me.cyber.nukleos.utils.isStopStreamingCommand
import java.util.*
import java.util.concurrent.TimeUnit


class Myo(private val device: BluetoothDevice) : BluetoothGattCallback() {

    val name: String
        get() = device.name

    val address: String
        get() = device.address

    var frequency: Int = 0
        set(value) {
            field = if (value >= MYO_MAX_FREQUENCY) 0 else value
        }

    var keepAlive = true
    private var lastKeepAlive = 0L

    private val connectionStatusSubject: BehaviorSubject<MyoStatus> = BehaviorSubject.createDefault(MyoStatus.DISCONNECTED)
    private val controlStatusSubject: BehaviorSubject<MyoControlStatus> = BehaviorSubject.createDefault(MyoControlStatus.NOT_STREAMING)
    private val dataProcessor: PublishProcessor<FloatArray> = PublishProcessor.create()

    private var gatt: BluetoothGatt? = null
    private var byteReader = ByteReader()

    private var serviceControl: BluetoothGattService? = null
    private var characteristicCommand: BluetoothGattCharacteristic? = null
    private var characteristicInfo: BluetoothGattCharacteristic? = null
    private var serviceEmg: BluetoothGattService? = null
    private var characteristicEmg0: BluetoothGattCharacteristic? = null
    private var characteristicEmg1: BluetoothGattCharacteristic? = null
    private var characteristicEmg2: BluetoothGattCharacteristic? = null
    private var characteristicEmg3: BluetoothGattCharacteristic? = null
    private var characteristicBattery: BluetoothGattCharacteristic? = null
    private var serviceBattery: BluetoothGattService? = null

    private val writeQueue: LinkedList<BluetoothGattDescriptor> = LinkedList()
    private val readQueue: LinkedList<BluetoothGattCharacteristic> = LinkedList()

    fun connect(context: Context) {
        connectionStatusSubject.onNext(MyoStatus.CONNECTING)
        gatt = device.connectGatt(context, false, this)
    }

    fun disconnect() {
        gatt?.close()
        controlStatusSubject.onNext(MyoControlStatus.NOT_STREAMING)
        connectionStatusSubject.onNext(MyoStatus.DISCONNECTED)
    }

    fun isConnected() = connectionStatusSubject.value == MyoStatus.CONNECTED || connectionStatusSubject.value == MyoStatus.READY

    fun isStreaming() = controlStatusSubject.value == MyoControlStatus.STREAMING

    fun statusObservable(): Observable<MyoStatus> = connectionStatusSubject

    fun controlObservable(): Observable<MyoControlStatus> = controlStatusSubject

    fun dataFlowable(): Flowable<FloatArray> {
        return if (frequency == 0) {
            dataProcessor.onBackpressureDrop()
        } else {
            dataProcessor.sample((1000 / frequency).toLong(), TimeUnit.MILLISECONDS).onBackpressureDrop()
        }
    }

    fun sendCommand(command: Command): Boolean {
        characteristicCommand?.apply {
            this.value = command
            if (this.properties == BluetoothGattCharacteristic.PROPERTY_WRITE) {
                if (command.isStartStreamingCommand()) {
                    controlStatusSubject.onNext(MyoControlStatus.STREAMING)
                } else if (command.isStopStreamingCommand()) {
                    controlStatusSubject.onNext(MyoControlStatus.NOT_STREAMING)
                }
                gatt?.writeCharacteristic(this)
                return true
            }
        }
        return false
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        Log.d(TAG, "onConnectionStateChange: $status -> $newState")
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "Bluetooth Connected")
            connectionStatusSubject.onNext(MyoStatus.CONNECTED)
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // Calling disconnect() here will cause to release the GATT resources.
            disconnect()
            Log.d(TAG, "Bluetooth Disconnected")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        Log.d(TAG, "onServicesDiscovered received: $status")

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return
        }
        serviceEmg = gatt.getService(SERVICE_EMG_DATA_ID)
        serviceEmg?.apply {
            characteristicEmg0 = serviceEmg?.getCharacteristic(CHAR_EMG_0_ID)
            characteristicEmg1 = serviceEmg?.getCharacteristic(CHAR_EMG_1_ID)
            characteristicEmg2 = serviceEmg?.getCharacteristic(CHAR_EMG_2_ID)
            characteristicEmg3 = serviceEmg?.getCharacteristic(CHAR_EMG_3_ID)

            listOf(characteristicEmg0,
                    characteristicEmg1,
                    characteristicEmg2,
                    characteristicEmg3).forEach { emgCharacteristic ->
                emgCharacteristic?.apply {
                    if (gatt.setCharacteristicNotification(emgCharacteristic, true)) {
                        val descriptor = emgCharacteristic.getDescriptor(CHAR_CLIENT_CONFIG)
                        descriptor?.apply {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            writeDescriptor(gatt, descriptor)
                        }
                    }
                }
            }
        }

        // Find GATT Service Control
        serviceControl = gatt.getService(SERVICE_CONTROL_ID)
        serviceControl?.apply {
            characteristicInfo = this.getCharacteristic(CHAR_INFO_ID)
            characteristicInfo?.apply {
                // if there is only 1 item in the queue, then read it.  If more than 1, we handle asynchronously in the
                // callback. GIVE PRECEDENCE to descriptor writes. They must all finish first.
                readQueue.add(this)
                if (readQueue.size == 1 && writeQueue.size == 0) {
                    gatt.readCharacteristic(this)
                }
            }
            characteristicCommand = this.getCharacteristic(CHAR_COMMAND_ID)
            characteristicCommand?.apply {
                lastKeepAlive = System.currentTimeMillis()
                sendCommand(CommandList.unSleep())
                // We send the ready event as soon as the characteristicCommand is ready.
                connectionStatusSubject.onNext(MyoStatus.READY)
            }
        }

        serviceBattery = gatt.getService(BATTERY_LEVEL_SERVICE)
        serviceBattery?.apply {
            characteristicBattery = this.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC)
        }
    }

    internal fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        writeQueue.add(descriptor)
        // When writing, if the queue is empty, write immediately.
        if (writeQueue.size == 1) {
            gatt.writeDescriptor(descriptor)
        }
    }


    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        super.onDescriptorWrite(gatt, descriptor, status)
        Log.d(TAG, "onDescriptorWrite status: $status")
        writeQueue.remove()
        //if there is more to write, do it!
        if (writeQueue.size > 0)
            gatt.writeDescriptor(writeQueue.element())
        else if (readQueue.size > 0)
            gatt.readCharacteristic(readQueue.element())
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicRead(gatt, characteristic, status)
        readQueue.remove()
        Log.d(TAG, "onCharacteristicRead status: $status ${characteristic.uuid}")

        if (CHAR_INFO_ID == characteristic.uuid) {
            // Myo Device Information
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                val byteReader = ByteReader()
                byteReader.byteData = data
                // TODO We might expose these to the public
                val callbackMsg = String.format("Serial Number     : %02x:%02x:%02x:%02x:%02x:%02x",
                        byteReader.byte, byteReader.byte, byteReader.byte,
                        byteReader.byte, byteReader.byte, byteReader.byte) +
                        '\n'.toString() + String.format("Unlock            : %d", byteReader.short) +
                        '\n'.toString() + String.format("Classifier builtin:%d active:%d (have:%d)",
                        byteReader.byte, byteReader.byte, byteReader.byte) +
                        '\n'.toString() + String.format("Stream Type       : %d", byteReader.byte)
                Log.d(TAG, "MYO info string: $callbackMsg")
            }
        }

        if (readQueue.size > 0)
            gatt.readCharacteristic(readQueue.element())
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        super.onCharacteristicChanged(gatt, characteristic)

        if (characteristic.uuid.toString().endsWith(CHAR_EMG_POSTFIX)) {
            val emgData = characteristic.value
            byteReader.byteData = emgData

            // We receive 16 bytes of data. Let's cut them in 2 and deliver both of them.
            dataProcessor.onNext(byteReader.getBytes(EMG_ARRAY_SIZE / 2))
            dataProcessor.onNext(byteReader.getBytes(EMG_ARRAY_SIZE / 2))
        }

        // Finally check if keep alive makes sense.
        val currentTimeMillis = System.currentTimeMillis()
        if (keepAlive && currentTimeMillis > lastKeepAlive + KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAlive = currentTimeMillis
            sendCommand(CommandList.unSleep())
        }
    }
}

enum class MyoStatus {
    CONNECTING,
    READY,
    CONNECTED,
    DISCONNECTED
}

enum class MyoControlStatus {
    STREAMING,
    NOT_STREAMING
}
