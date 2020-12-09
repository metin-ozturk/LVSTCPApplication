package com.lvs.lvstcpapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.recyclerview.widget.AsyncListUtil
import com.google.common.primitives.Bytes
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.AsyncSocket
import com.koushikdutta.async.callback.CompletedCallback
import com.koushikdutta.async.callback.DataCallback
import com.koushikdutta.async.future.Cancellable
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import kotlin.experimental.inv

class LVSCameraClient(private val context: Context?) {
    private val HOST = "192.168.10.123"
    private val PORT = 7060
    private val FRAME_SIZE = 1024 * 1024
    private val START_TAG = byteArrayOf(66, 111, 117, 110, 100, 97, 114, 121, 83)
    private val END_TAG = byteArrayOf(66, 111, 117, 110, 100, 97, 114, 121, 69)
    private val bitmapOptions: BitmapFactory.Options
    var isCapturing = false
    private var cancellable: Cancellable? = null
    private var cameraNetworkListener: OnCameraNetworkListener? = null
    var jpg_count = 0
    var status = 0
    var data_copy_flag = false
    var frameBuf = ByteArray(1024 * 1024)
    lateinit var lastImageData: ByteArray
    lateinit var lastUsefulImageData: ByteArray
    var lastSuccessfullBitmap: Bitmap? = null
    var tryCount = 0
    var cameraTryCount = 0
    var foundCamera: String? = null
    var selectedCamera: String? = "new" //new or old
    var checkNewCount = 0
    var checkOldCount = 0
    var tryCountForCamera = 30
    var isOnline = false

    fun setOnCameraNetworkListener(listener: OnCameraNetworkListener?) {
        cameraNetworkListener = listener
    }

    fun startCapture() {
        resetTryCounts(true)
        cancellable =
                AsyncServer.getDefault().connectSocket(InetSocketAddress(HOST, PORT)) { ex, socket ->
                    handleConnectCompleted(ex, socket)
                    if (ex == null) {
                        isCapturing = true
                        isOnline = true
                        cameraNetworkListener!!.onConnect()
                    } else {
                        Log.e("LVSRND", "startCapture -> " + ex.localizedMessage)
                    }
                }
    }

    fun stopCapture() {
        if (AsyncServer.getDefault() != null && AsyncServer.getDefault().isRunning) {
            AsyncServer.getDefault().stop()
        }
        isCapturing = false
        cameraNetworkListener!!.onClose()
    }

    fun cancelConnection(): Boolean = cancellable!!.cancel()

    fun parseIt(tmp: ByteArray) {
        if (selectedCamera != null) {
            if (selectedCamera == "new") {
                newCameraParse(tmp)
            } else {
                oldCameraParse(tmp)
            }
        } else {
            if (checkNewCount <= tryCountForCamera) {
                checkNewCount++
                newCameraParse(tmp)
            } else if (checkOldCount <= tryCountForCamera) {
                checkOldCount++
                oldCameraParse(tmp)
            }
        }
    }

    private fun handleConnectCompleted(ex: Exception?, socket: AsyncSocket?) {
        if (ex != null) {
            cameraNetworkListener!!.cantConnect()
            return
        }
        if (socket != null) {
            socket.dataCallback = DataCallback { emitter, bb -> parseIt(bb.allByteArray) }
            socket.closedCallback = object : CompletedCallback {
                override fun onCompleted(ex: Exception) {
                    Log.e(
                            "LVSRND",
                            "handleConnectCompleted - setClosedCallback -> " + ex.localizedMessage
                    )
                    isCapturing = false
                    isOnline = false
                    cameraNetworkListener!!.onDisconnect()
                }
            }
            socket.endCallback = object : CompletedCallback {
                override fun onCompleted(ex: Exception) {
                    Log.e(
                            "LVSRND",
                            "handleConnectCompleted - setEndCallback -> " + ex.localizedMessage
                    )
                    //  if(ex != null) throw new RuntimeException(ex);
                    isCapturing = false
                    isOnline = false
                    cameraNetworkListener!!.onClose()
                }
            }
        }
    }

    private fun newCameraParse2(buf_tmp: ByteArray) {
        val startIndex = Bytes.indexOf(buf_tmp, START_TAG)
        val endIndex = Bytes.indexOf(buf_tmp, END_TAG)
        for (i in buf_tmp.indices) {
            if (!data_copy_flag && i == startIndex + 9) {
                jpg_count = 0
                data_copy_flag = true
            }
            if (data_copy_flag) {
                frameBuf[jpg_count++] = buf_tmp[i]
            }
            if (data_copy_flag && i == endIndex) {
                data_copy_flag = false
                frameBuf[(jpg_count - 32) / 2 + 32] =
                        frameBuf[(jpg_count - 32) / 2 + 32].inv() as Byte
                handleFinalBytes(Arrays.copyOfRange(frameBuf, 32, jpg_count))
                frameBuf = ByteArray(1024 * 1024)
                jpg_count = 0
            }
        }
    }

    private fun newCameraParse(buf_tmp: ByteArray) {
        for (i in buf_tmp.indices) {
            if (data_copy_flag) {
                frameBuf[jpg_count++] = buf_tmp[i]
            }
            when (status) {
                0 -> if (buf_tmp[i].toChar() == 'B') status++ else status = 0
                1 -> if (buf_tmp[i].toChar() == 'o') status++ else status = 0
                2 -> if (buf_tmp[i].toChar() == 'u') status++ else status = 0
                3 -> if (buf_tmp[i].toChar() == 'n') status++ else status = 0
                4 -> if (buf_tmp[i].toChar() == 'd') status++ else status = 0
                5 -> if (buf_tmp[i].toChar() == 'a') status++ else status = 0
                6, 8 -> if (buf_tmp[i].toChar() == 'E') {
                    status = 0
                    data_copy_flag = false
                    frameBuf[(jpg_count - 41) / 2 + 32] =
                            frameBuf[(jpg_count - 41) / 2 + 32].inv() as Byte
                    lastImageData = Arrays.copyOfRange(frameBuf, 32, jpg_count)
                    if (selectedCamera != null) {
                        handleFinalBytes(lastImageData)
                    } else {
                        handleFinalBytes(lastImageData, "new")
                    }
                    frameBuf = ByteArray(1024 * 1024)
                    jpg_count = 0
                } else if (buf_tmp[i].toChar() == 'S') {
                    status = 0
                    data_copy_flag = true
                    jpg_count = 0
                }
                else -> {
                }
            }
        }
    }

    private fun oldCameraParse(buf_tmp: ByteArray) {
        for (i in buf_tmp.indices) {
            when (status) {
                0 -> {
                    if (buf_tmp[i] == 0xff.toByte()) {
                        status++
                    }
                    jpg_count = 0
                    frameBuf[jpg_count++] = buf_tmp[i]
                }
                1 -> if (buf_tmp[i] == 0xd8.toByte()) {
                    status++
                    frameBuf[jpg_count++] = buf_tmp[i]
                } else {
                    status = 0
                }
                2 -> {
                    frameBuf[jpg_count++] = buf_tmp[i]
                    if (buf_tmp[i] == 0xff.toByte()) status++
                    if (jpg_count >= FRAME_SIZE) status = 0
                }
                3 -> {
                    frameBuf[jpg_count++] = buf_tmp[i]
                    if (buf_tmp[i] == 0xd9.toByte()) {
                        if (selectedCamera != null) {
                            handleFinalBytes(Arrays.copyOfRange(frameBuf, 0, jpg_count))
                        } else {
                            handleFinalBytes(Arrays.copyOfRange(frameBuf, 0, jpg_count), "old")
                        }
                        frameBuf = ByteArray(1024 * 1024)
                        jpg_count = 0
                        status = 0
                    } else {
                        status = 2
                    }
                }
                else -> {
                }
            }
        }
    }

    private fun handleFinalBytes(bytes: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
        if (bmp != null) {
            lastUsefulImageData = bytes
            cameraNetworkListener!!.onGetData(bytes, bmp)
        }
    }

    private fun handleFinalBytes(bytes: ByteArray, cameraType: String) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
        if (bmp != null) {
            if (selectedCamera == null) {
                selectedCamera = cameraType
                //cameraNetworkListener.onCameraSelected();
                //resetTryCounts(false);
            }
            lastUsefulImageData = bytes
            cameraNetworkListener!!.onGetData(bytes, bmp)
        }
    }

    fun resetTryCounts(resetSelectedCamera: Boolean) {
        checkOldCount = 0
        checkNewCount = 0
        if (resetSelectedCamera) {
            selectedCamera = null
        }
    }

    private fun ByteArrayToBitmap(byteArray: ByteArray): Bitmap {
        val arrayInputStream = ByteArrayInputStream(byteArray)
        return BitmapFactory.decodeStream(arrayInputStream)
    }

    interface OnCameraNetworkListener {
        fun onConnect()
        fun onDisconnect()
        fun onGetData(data: ByteArray?, bmp: Bitmap?)
        fun onClose()
        fun cantConnect()
        fun cantReceiveImage()
        fun onCameraSelected()
    }

    companion object {
        fun connectToCamera(c: Context?): LVSCameraClient {
            return LVSCameraClient(c)
        }

        fun bytesToString(bytes: ByteArray?): String {
            return String(bytes!!)
        }

        /*public static void connectAndSetChannel(int channel, UDPSocketListener listener) {
        Thread thread = null;
        thread = new Thread(new ClientSendAndListen(channel, listener));
        thread.start();
        */
        /*Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Server server = new Server("192.168.10.123",50000);
            }
        });

        Thread clientThread = new Thread(new Runnable() {
            @Override
            public void run() {
                UDPClient client = new UDPClient("192.168.10.123", 50000);
                client.sendSetChannel(5);
            }
        });
        clientThread.start();
        serverThread.start();*/
        /*

     */
        /*new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                    sendBytes(ClientSendAndListen.sendSetChannel(7));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();*/
        /*


    }*/
        @JvmOverloads
        @Throws(IOException::class)
        fun sendBytes(myByteArray: ByteArray, start: Int = 0, len: Int = myByteArray.size) {
            val socket = Socket("192.168.10.123", 50000)
            require(len >= 0) { "Negative length not allowed" }
            if (start < 0 || start >= myByteArray.size) throw IndexOutOfBoundsException("Out of bounds: $start")
            // Other checks if needed.

            // May be better to save the streams in the support class;
            // just like the socket variable.
            val out = socket.getOutputStream()
            val dos = DataOutputStream(out)
            dos.writeInt(len)
            if (len > 0) {
                dos.write(myByteArray, start, len)
            }
            val `in` = socket.getInputStream()
            val dis = DataInputStream(`in`)
            val leng = dis.readInt()
            val data = ByteArray(len)
            if (leng > 0) {
                dis.readFully(data)
            }
            Log.d("KCE", data.toString())
        }
    }

    init {
        bitmapOptions = BitmapFactory.Options()
        if (Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("amazon")) {
            bitmapOptions.inSampleSize = 2
        }
    }
}