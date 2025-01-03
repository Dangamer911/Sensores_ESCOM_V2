package ovh.gabrielhuav.sensores_escom_v2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothGameManager private constructor() {

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val connectedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private var connectionListener: ConnectionListener? = null
    private val handler = Handler(Looper.getMainLooper())

    interface ConnectionListener {
        fun onDeviceConnected(device: BluetoothDevice)
        fun onPositionReceived(device: BluetoothDevice, x: Int, y: Int)
        fun onConnectionComplete()
        fun onConnectionFailed(message: String)
    }

    fun setConnectionListener(listener: ConnectionListener) {
        connectionListener = listener
    }

    fun startServer() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "Faltan permisos de Bluetooth para iniciar el servidor.")
            handler.post { connectionListener?.onConnectionFailed("Faltan permisos para iniciar el servidor.") }
            return
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth no es compatible con este dispositivo.")
            handler.post { connectionListener?.onConnectionFailed("Bluetooth no es compatible con este dispositivo.") }
            return
        }

        Thread {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID)
                Log.d(TAG, "@string/ServSucWConn")
                clientSocket = serverSocket!!.accept()

                val remoteDevice = clientSocket!!.remoteDevice
                setupStreams(clientSocket!!)

                connectedDevices.add(remoteDevice)
                handler.post {
                    connectionListener?.onDeviceConnected(remoteDevice)
                    connectionListener?.onConnectionComplete()
                }

                Log.d(TAG, "@string/connDeviBlt ${getDeviceName(remoteDevice)}")

                receiveData { data ->
                    processReceivedData(data, remoteDevice)
                }
            } catch (e: IOException) {
                Log.e(TAG, "@string/ServStrErr ${e.message}")
                handler.post { connectionListener?.onConnectionFailed("@string/ServStrErr") }
            } finally {
                closeServerSocket()
            }
        }.start()
    }

    fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "@string/BltAllMiss")
            handler.post { connectionListener?.onConnectionFailed("@string/BltAllMiss") }
            return
        }

        Thread {
            try {
                clientSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                Log.d(TAG, "@string/tryToConnect ${getDeviceName(device)}...")
                clientSocket!!.connect()
                setupStreams(clientSocket!!)

                connectedDevices.add(device)
                handler.post {
                    connectionListener?.onDeviceConnected(device)
                    connectionListener?.onConnectionComplete()
                }

                Log.d(TAG, "@string/connDeviBlt: ${getDeviceName(device)}")

                receiveData { data ->
                    processReceivedData(data, device)
                }
            } catch (e: IOException) {
                Log.e(TAG, "@string/connFailureBlt: ${e.message}")
                handler.post { connectionListener?.onConnectionFailed("@string/connFailureBlt ${getDeviceName(device)}") }
            }
        }.start()
    }

    private fun setupStreams(socket: BluetoothSocket) {
        inputStream = socket.inputStream
        outputStream = socket.outputStream
    }

    fun sendPlayerPosition(x: Int, y: Int) {
        try {
            val positionData = JSONObject().apply {
                put("x", x)
                put("y", y)
            }
            outputStream?.write(positionData.toString().toByteArray())
        } catch (e: IOException) {
            Log.e(TAG, "@string/ErrDataPos: ${e.message}")
        }
    }

    private fun receiveData(callback: (String) -> Unit) {
        Thread {
            try {
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = inputStream?.read(buffer) ?: break
                    val receivedData = String(buffer, 0, bytes)
                    callback(receivedData)
                }
            } catch (e: IOException) {
                Log.e(TAG, "@string/ErrRecvData: ${e.message}")
            }
        }.start()
    }

    private fun processReceivedData(data: String, device: BluetoothDevice) {
        try {
            val jsonData = JSONObject(data)
            val x = jsonData.getInt("x")
            val y = jsonData.getInt("y")
            handler.post {
                connectionListener?.onPositionReceived(device, x, y)
            }
        } catch (e: Exception) {
            Log.e(TAG, "@string/ErrProcData ${e.message}")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDeviceName(device: BluetoothDevice?): String {
        return if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            device?.name ?: "@string/unkDevi"
        } else {
            "@string/NotPermit"
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "@string/ErrCloSock: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BluetoothGameManager"
        private const val NAME = "BluetoothGame"
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        lateinit var appContext: Context

        @Volatile
        private var INSTANCE: BluetoothGameManager? = null

        fun getInstance(): BluetoothGameManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothGameManager().also { INSTANCE = it }
            }
        }
    }
}
