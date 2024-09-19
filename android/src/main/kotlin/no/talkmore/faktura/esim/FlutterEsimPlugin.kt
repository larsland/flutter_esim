package no.talkmore.faktura.esim

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.EUICC_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender.SendIntentException 
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentSanitizer;
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccManager
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.lang.ref.WeakReference


/** FlutterEsimPlugin */
class FlutterEsimPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {

        private fun <T, C : MutableCollection<WeakReference<T>>> C.reapCollection(): C {
            this.removeAll {
                it.get() == null
            }
            return this
        }

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterEsimPlugin

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableListOf<WeakReference<EventCallbackHandler>>()

        fun sendEvent(event: String, body: Map<String, Any>) {
            eventHandlers.reapCollection().forEach {
                it.get()?.send(event, body)
            }
        }

        fun initSharedInstance(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
            if (!::instance.isInitialized) {
                instance = FlutterEsimPlugin()
                instance.context = flutterPluginBinding.applicationContext
            }

            val channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_esim")
            methodChannels[flutterPluginBinding.binaryMessenger] = channel
            channel.setMethodCallHandler(instance)

            val events = EventChannel(flutterPluginBinding.binaryMessenger, "flutter_esim_events")
            val handler = EventCallbackHandler()
            eventHandlers.add(WeakReference(handler))
            events.setStreamHandler(handler)
            eventChannels[flutterPluginBinding.binaryMessenger] = events
        }
    }

    private var activity: Activity? = null
    private var context: Context? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        initSharedInstance(flutterPluginBinding)
    }

    private val REQUEST_CODE_INSTALL = 0
    private val ACTION_DOWNLOAD_SUBSCRIPTION = "download_subscription"
    private val LPA_DECLARED_PERMISSION = "no.talkmore.faktura.lpa.permission.BROADCAST";
    private val ALLOWED_PACKAGE = "no.talkmore.faktura";

    private var mgr: EuiccManager? = null


    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_DOWNLOAD_SUBSCRIPTION != intent.action) {
                return
            }

            if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR && mgr != null) {
                handleResolvableError(intent)
            } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                sendEvent("1", hashMapOf("resultCode" to resultCode, "message" to "Successfully installed ESIM"))
            } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR) {
                val resultCode = getResultCode()
                val resultData = getResultData()
                val resultExtras = getResultExtras(false)

                val detailsBody = hashMapOf("resultCode" to resultCode, "message" to "failed to install ESIM")
                sendEvent("3", detailsBody)
            } else {
                sendEvent("4", hashMapOf("resultCode" to resultCode, "message" to "an unknown error occured"))
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "isSupportESim" -> {
                    Log.d("isSupportESim", "")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        result.success(mgr?.isEnabled)
                    }else {
                        result.success(false)
                    }
                }

                "installEsimProfile" -> {



                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        sendEvent("5", hashMapOf("message" to "unsupported os or device"))
                        return
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mgr != null && !mgr!!.isEnabled) {
                        sendEvent("5", hashMapOf("message" to "unsupported os or device"))
                        return
                    }

                    val filter = IntentFilter(ACTION_DOWNLOAD_SUBSCRIPTION)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context?.registerReceiver(
                            receiver,
                            filter,
                            null,
                            null,
                            Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        ContextCompat.registerReceiver(
                            context!!,
                            receiver,
                            filter, 
                            null,
                            null, 
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        val activationCode = (call.arguments as HashMap<*, *>)["profile"] as String
                        val sub = DownloadableSubscription.forActivationCode(activationCode);
                        val intent = Intent(ACTION_DOWNLOAD_SUBSCRIPTION).setPackage(context?.getPackageName());
                        val callbackIntent = PendingIntent.getBroadcast(
                            context, 
                            REQUEST_CODE_INSTALL, 
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        mgr?.downloadSubscription(sub, true, callbackIntent)
                    } else {
                        sendEvent("5", hashMapOf("message" to "unsupported os or device"))
                    }
                }
            }
        } catch (error: Exception) {
            result.notImplemented()
        }
    }


    private fun handleResolvableError(intent: Intent) {
        val safeIntent = IntentSanitizer.Builder()
            .allowAnyComponent()
            .allowPackage(ALLOWED_PACKAGE)
            .allowFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES)
            .allowExtra("android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT", PendingIntent::class.java)
            .allowAction(ACTION_DOWNLOAD_SUBSCRIPTION)
            .build()
            .sanitizeByThrowing(intent);

        val callbackIntent = PendingIntent.getBroadcast(
            context, 
            REQUEST_CODE_INSTALL, 
            safeIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            mgr?.startResolutionActivity(
                activity,
                REQUEST_CODE_INSTALL,
                intent,
                callbackIntent
            )
        } catch (e: Exception) {
            sendEvent("2", hashMapOf("message" to "failed to resolve resolvable error", "error" to e.message!!))
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)
    }


    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            instance.mgr = instance.context?.getSystemService(EUICC_SERVICE) as EuiccManager
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {

    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            instance.mgr = instance.context?.getSystemService(EUICC_SERVICE) as EuiccManager
        }
    }

    override fun onDetachedFromActivity() {

    }


    class EventCallbackHandler : EventChannel.StreamHandler {

        private var eventSink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
        }

        fun send(event: String, body: Map<String, Any>) {
            val data = mapOf(
                "event" to event,
                "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.success(data)
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }


}