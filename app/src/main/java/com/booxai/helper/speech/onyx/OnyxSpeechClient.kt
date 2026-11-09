package com.booxai.helper.speech.onyx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.onyx.android.sdk.speech.client.service.SpeechOutputArgs
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 复用文石系统自带的 ASR：
 *   bind -> com.onyx.android.ksync/.service.KCommonSpeechService
 *   ISpeechService.start(configJson, callback)
 *   ISpeechService.appendSpeechBuf(16kHz mono PCM16)
 *   ISpeechService.stop(3)
 *
 * 证书/配额由 ksync 用设备上的文石账号管理，本 App 不需要自己的 ASR Key。
 * 这是未公开的 OEM 接口，固件升级可能失效；失败时回退到键盘麦克风。
 */
class OnyxSpeechClient(context: Context) {

    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingAudio = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var recording = false

    @Volatile
    private var started = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var gotFinal = false

    @Volatile
    private var released = false

    @Volatile
    private var startSent = false

    @Volatile
    private var listener: Listener? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var startTimeout: Runnable? = null
    private var stopTimeout: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "KCommonSpeechService connected")
            if (released) {
                try {
                    appContext.unbindService(this)
                } catch (_: Exception) {
                }
                return
            }
            binder = service
            if (recording && !startSent) {
                sendStart()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "KCommonSpeechService disconnected")
            binder = null
            started = false
            startSent = false
        }
    }

    fun start(listener: Listener) {
        if (released) return
        this.listener = listener
        gotFinal = false
        started = false
        stopRequested = false
        startSent = false
        pendingAudio.clear()

        if (binder == null) {
            val intent = Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
            val ok = try {
                appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "bindService failed", e)
                false
            }
            if (!ok) {
                postError("系统语音服务不可用")
                return
            }
        }

        startRecording()
        if (binder != null) {
            sendStart()
        }

        startTimeout?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (!started && !gotFinal && listener != null) {
                postError("系统语音服务未响应")
                cancelInternal()
            }
        }
        startTimeout = timeout
        mainHandler.postDelayed(timeout, 8000L)
    }

    fun stop() {
        if (!recording && !started) return
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            recordThread?.join(400)
        } catch (_: Exception) {
        }
        recordThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        if (started) {
            flushPending()
            sendStop()
            startTimeout?.let { mainHandler.removeCallbacks(it) }
            stopTimeout?.let { mainHandler.removeCallbacks(it) }
            val timeout = Runnable {
                if (!gotFinal && listener != null) {
                    postError("没有识别到语音")
                }
            }
            stopTimeout = timeout
            mainHandler.postDelayed(timeout, 6000L)
        } else {
            stopRequested = true
            stopTimeout?.let { mainHandler.removeCallbacks(it) }
            val timeout = Runnable {
                if (!started && !gotFinal && listener != null) {
                    postError("系统语音服务未就绪")
                }
            }
            stopTimeout = timeout
            mainHandler.postDelayed(timeout, 4000L)
        }
    }

    fun cancel() {
        cancelInternal()
    }

    fun release() {
        released = true
        cancelInternal()
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        binder = null
        listener = null
    }

    private fun cancelInternal() {
        recording = false
        stopRequested = false
        startTimeout?.let { mainHandler.removeCallbacks(it) }
        stopTimeout?.let { mainHandler.removeCallbacks(it) }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        recordThread = null
        pendingAudio.clear()
        if (binder != null) {
            sendCancel()
            sendQuit()
        }
        started = false
        startSent = false
    }

    private fun startRecording() {
        if (recording) return
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10 * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channel,
                format,
                bufferSize * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            postError("无法创建录音器")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            postError("麦克风不可用")
            return
        }
        audioRecord = record
        recording = true
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recording = false
            record.release()
            audioRecord = null
            postError("麦克风启动失败")
            return
        }

        val thread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    -1
                }
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    if (started && binder != null) {
                        sendAudio(chunk)
                    } else if (pendingAudio.size < 300) {
                        pendingAudio.add(chunk)
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read=$read")
                }
            }
        }
        recordThread = thread
        thread.start()
        mainHandler.post { listener?.onListening() }
    }

    private fun configJson(): String {
        return "{" +
            "\"acceptAudioVolume\":true," +
            "\"recorderType\":\"Stream\"," +
            "\"showPunctuation\":true," +
            "\"endPointTimeOut\":0," +
            "\"isFarMode\":true," +
            "\"farMode\":true," +
            "\"autoStop\":false," +
            "\"speechRecType\":0," +
            "\"maxSpeechDuration\":60000," +
            "\"source\":\"" + appContext.packageName + "\"," +
            "\"needAudioFocus\":true," +
            "\"singleAsrResult\":true," +
            "\"useITN\":true," +
            "\"showUtterances\":true," +
            "\"autoAddSpace\":true," +
            "\"action\":0" +
            "}"
    }

    private fun sendStart() {
        val service = binder ?: return
        if (startSent) return
        startSent = true
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(configJson())
            data.writeStrongBinder(callbackBinder)
            service.transact(TRANSACTION_START, data, null, IBinder.FLAG_ONEWAY)
            Log.i(TAG, "start sent")
        } catch (e: Exception) {
            Log.e(TAG, "start transact failed", e)
            startSent = false
            postError("启动语音识别失败")
        } finally {
            data.recycle()
        }
    }

    private fun sendAudio(chunk: ByteArray) {
        val service = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeByteArray(chunk)
            service.transact(TRANSACTION_APPEND, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private fun flushPending() {
        while (true) {
            val chunk = pendingAudio.poll() ?: break
            sendAudio(chunk)
        }
    }

    private fun sendStop() {
        val service = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeInt(3)
            service.transact(TRANSACTION_STOP, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private fun sendCancel() {
        val service = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            service.transact(TRANSACTION_CANCEL, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private fun sendQuit() {
        val service = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            service.transact(TRANSACTION_QUIT, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            data.recycle()
        }
    }

    private fun handleOutput(args: SpeechOutputArgs) {
        Log.i(TAG, "output event=${args.eventType} code=${args.errorCode} text=${args.recResults}")
        when (args.eventType) {
            SpeechOutputArgs.EVENT_ASR_STATE -> {
                started = true
                startTimeout?.let { mainHandler.removeCallbacks(it) }
                flushPending()
                if (stopRequested) {
                    stopRequested = false
                    sendStop()
                }
            }

            SpeechOutputArgs.EVENT_ASR_PARTIAL -> {
                if (!started) {
                    started = true
                    flushPending()
                }
                val text = args.recResults?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    mainHandler.post { listener?.onPartial(text) }
                }
            }

            SpeechOutputArgs.EVENT_ASR_FINISH -> {
                gotFinal = true
                started = false
                stopTimeout?.let { mainHandler.removeCallbacks(it) }
                val text = args.recResults?.joinToString("").orEmpty().trim()
                mainHandler.post { listener?.onFinal(text) }
            }

            SpeechOutputArgs.EVENT_ASR_ERROR -> {
                started = false
                postError(errorMessage(args.errorCode))
            }

            SpeechOutputArgs.EVENT_PERMISSION_ERROR -> {
                started = false
                postError(permissionMessage(args.errorCode))
            }

            SpeechOutputArgs.EVENT_ASR_EXIT -> {
                if (!gotFinal) {
                    gotFinal = true
                    started = false
                    stopTimeout?.let { mainHandler.removeCallbacks(it) }
                    mainHandler.post { listener?.onFinal("") }
                }
            }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        4 -> "网络连接失败，请检查网络/代理"
        7, 8 -> "没有检测到语音"
        else -> "语音识别失败（$code）"
    }

    private fun permissionMessage(code: Int): String = when (code) {
        3 -> "系统语音额度已用完"
        4 -> "未登录文石账号或没有语音权限"
        else -> "系统语音权限不可用（$code）"
    }

    private fun postError(message: String) {
        mainHandler.post {
            listener?.onError(message)
        }
    }

    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                1 -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val args = if (data.readInt() != 0) {
                        SpeechOutputArgs.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    reply?.writeNoException()
                    if (args != null && !released) {
                        handleOutput(args)
                    }
                    true
                }

                2 -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    reply?.writeNoException()
                    true
                }

                1598968902 -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    companion object {
        private const val TAG = "ReadAskOnyx"
        private const val SERVICE_PACKAGE = "com.onyx.android.ksync"
        private const val SERVICE_CLASS = "com.onyx.android.ksync.service.KCommonSpeechService"
        private const val SERVICE_DESCRIPTOR =
            "com.onyx.android.sdk.speech.client.service.ISpeechService"
        private const val CALLBACK_DESCRIPTOR =
            "com.onyx.android.sdk.speech.client.service.ISpeechOutputCallback"
        private const val TRANSACTION_START = 2
        private const val TRANSACTION_APPEND = 3
        private const val TRANSACTION_STOP = 4
        private const val TRANSACTION_CANCEL = 5
        private const val TRANSACTION_QUIT = 6
        private const val SAMPLE_RATE = 16000
    }
}
