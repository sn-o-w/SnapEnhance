package me.rhunk.snapenhance.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rhunk.snapenhance.bridge.ConfigStateListener
import me.rhunk.snapenhance.bridge.SyncCallback
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ReceiversConfig
import me.rhunk.snapenhance.common.action.EnumAction
import me.rhunk.snapenhance.common.data.FriendStreaks
import me.rhunk.snapenhance.common.data.MessagingFriendInfo
import me.rhunk.snapenhance.common.data.MessagingGroupInfo
import me.rhunk.snapenhance.common.util.toSerialized
import me.rhunk.snapenhance.core.bridge.BridgeClient
import me.rhunk.snapenhance.core.bridge.loadFromBridge
import me.rhunk.snapenhance.core.data.SnapClassCache
import me.rhunk.snapenhance.core.event.events.impl.NativeUnaryCallEvent
import me.rhunk.snapenhance.core.event.events.impl.SnapWidgetBroadcastReceiveEvent
import me.rhunk.snapenhance.core.ui.InAppOverlay
import me.rhunk.snapenhance.core.util.LSPatchUpdater
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.findRestrictedMethod
import me.rhunk.snapenhance.core.util.hook.hook
import kotlin.system.measureTimeMillis


class SnapEnhance {
    companion object {
        lateinit var classLoader: ClassLoader
            private set
        val classCache by lazy {
            SnapClassCache(classLoader)
        }
    }
    private lateinit var appContext: ModContext
    private var isBridgeInitialized = false

    private fun hookMainActivity(methodName: String, stage: HookStage = HookStage.AFTER, block: Activity.(param: HookAdapter) -> Unit) {
        Activity::class.java.hook(methodName, stage, { isBridgeInitialized }) { param ->
            val activity = param.thisObject() as Activity
            if (!activity.packageName.equals(Constants.SNAPCHAT_PACKAGE_NAME)) return@hook
            block(activity, param)
        }
    }

    init {
        Application::class.java.hook("attach", HookStage.BEFORE) { param ->
            appContext = ModContext(
                androidContext = param.arg<Context>(0).also { classLoader = it.classLoader }
            )
            appContext.apply {
                bridgeClient = BridgeClient(this)
                bridgeClient.apply {
                    connect(
                        onFailure = {
                            InAppOverlay.showCrashOverlay(
                                "Snapchat can't connect to the SnapEnhance app. Make sure you have the latest version installed on your device. You can download the latest stable version on github.com/rhunk/SnapEnhance",
                                throwable = it
                            )
                        }
                    ) { bridgeResult ->
                        if (!bridgeResult) {
                            InAppOverlay.showCrashOverlay(
                                "Snapchat timed out while trying to connect to the SnapEnhance app. Make sure you have disabled any battery optimizations for SnapEnhance."
                            )
                            logCritical("Cannot connect to the SnapEnhance app")
                            return@connect
                        }
                        runCatching {
                            LSPatchUpdater.onBridgeConnected(appContext, bridgeClient)
                        }.onFailure {
                            log.error("Failed to init LSPatchUpdater", it)
                        }
                        runCatching {
                            measureTimeMillis {
                                runBlocking {
                                    init(this)
                                }
                            }.also {
                                appContext.log.verbose("init took ${it}ms")
                            }
                        }.onSuccess {
                            isBridgeInitialized = true
                        }.onFailure {
                            logCritical("Failed to initialize bridge", it)
                            InAppOverlay.showCrashOverlay("SnapEnhance failed to initialize. Please check logs for more details.")
                        }
                    }
                }
            }
        }

        hookMainActivity("onCreate") {
            val isMainActivityNotNull = appContext.mainActivity != null
            appContext.mainActivity = this
            if (isMainActivityNotNull || !appContext.mappings.isMappingsLoaded) return@hookMainActivity
            appContext.isMainActivityPaused = false
            onActivityCreate()
            jetpackComposeResourceHook()
            appContext.actionManager.onNewIntent(intent)
        }

        hookMainActivity("onPause") {
            appContext.bridgeClient.closeSettingsOverlay()
            appContext.isMainActivityPaused = true
        }

        hookMainActivity("onNewIntent") { param ->
            appContext.actionManager.onNewIntent(param.argNullable(0))
        }

        hookMainActivity("onResume") {
            if (appContext.isMainActivityPaused.also {
                appContext.isMainActivityPaused = false
            }) {
                appContext.reloadConfig()
                appContext.executeAsync {
                    syncRemote()
                }
            }
        }
    }

    private fun init(scope: CoroutineScope) {
        with(appContext) {
            Thread::class.java.hook("dispatchUncaughtException", HookStage.BEFORE) { param ->
                runCatching {
                    val throwable = param.argNullable(0) ?: Throwable()
                    logCritical(null, throwable)
                }
            }

            reloadConfig()
            initConfigListener()
            initWidgetListener()
            initNative()
            scope.launch(Dispatchers.IO) {
                runCatching {
                    syncRemote()
                }.onFailure {
                    log.error("Failed to sync remote", it)
                }
                translation.userLocale = getConfigLocale()
                translation.loadFromCallback { locale ->
                    bridgeClient.fetchLocales(locale)
                }
            }

            mappings.loadFromBridge(bridgeClient)
            mappings.init(androidContext)
            database.init()
            eventDispatcher.init()
            //if mappings aren't loaded, we can't initialize features
            if (!mappings.isMappingsLoaded) return
            bridgeClient.registerMessagingBridge(messagingBridge)
            features.init()
            scriptRuntime.connect(bridgeClient.getScriptingInterface())
            scriptRuntime.eachModule { callFunction("module.onSnapApplicationLoad", androidContext) }
        }
    }

    private fun onActivityCreate() {
        measureTimeMillis {
            with(appContext) {
                features.onActivityCreate()
                inAppOverlay.onActivityCreate(mainActivity!!)
                scriptRuntime.eachModule { callFunction("module.onSnapMainActivityCreate", mainActivity!!) }
                actionManager.onActivityCreate()
            }
        }.also { time ->
            appContext.log.verbose("onActivityCreate took $time")
        }
    }

    private fun initNative() {
        // don't initialize native when not logged in
        if (
            appContext.androidContext.getSharedPreferences("user_session_shared_pref", 0).getString("key_user_id", null) == null &&
            appContext.bridgeClient.getDebugProp("force_native_load", null) != "true"
        ) return
        if (appContext.config.experimental.nativeHooks.globalState != true) return

        lateinit var unhook: () -> Unit
        Runtime::class.java.findRestrictedMethod {
            it.name == "loadLibrary0" && it.parameterTypes.contentEquals(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Class::class.java, String::class.java)
                else arrayOf(ClassLoader::class.java, String::class.java)
            )
        }!!.hook(HookStage.AFTER) { param ->
            val libName = param.arg<String>(1)
            if (libName != "client") return@hook
            unhook()
            appContext.native.initOnce {
                nativeUnaryCallCallback = { request ->
                    appContext.event.post(NativeUnaryCallEvent(request.uri, request.buffer)) {
                        request.buffer = buffer
                        request.canceled = canceled
                    }
                }
                appContext.reloadNativeConfig()
            }
        }.also { unhook = { it.unhook() } }
    }

    private fun initConfigListener() {
        val tasks = linkedSetOf<() -> Unit>()
        hookMainActivity("onResume") {
            tasks.forEach { it() }
        }

        fun runLater(task: () -> Unit) {
            if (appContext.isMainActivityPaused) {
                tasks.add(task)
            } else {
                task()
            }
        }

        appContext.executeAsync {
            bridgeClient.registerConfigStateListener(object: ConfigStateListener.Stub() {
                override fun onConfigChanged() {
                    log.verbose("onConfigChanged")
                    reloadConfig()
                }

                override fun onRestartRequired() {
                    log.verbose("onRestartRequired")
                    runLater {
                        log.verbose("softRestart")
                        softRestartApp(saveSettings = false)
                    }
                }

                override fun onCleanCacheRequired() {
                    log.verbose("onCleanCacheRequired")
                    tasks.clear()
                    runLater {
                        log.verbose("cleanCache")
                        actionManager.execute(EnumAction.CLEAN_CACHE)
                    }
                }
            })
        }
    }

    private fun initWidgetListener() {
        appContext.event.subscribe(SnapWidgetBroadcastReceiveEvent::class) { event ->
            if (event.action != ReceiversConfig.BRIDGE_SYNC_ACTION) return@subscribe
            event.canceled = true
            val feedEntries = appContext.database.getFeedEntries(Int.MAX_VALUE)

            val groups = feedEntries.filter { it.friendUserId == null }.map {
                MessagingGroupInfo(
                    it.key!!,
                    it.feedDisplayName!!,
                    it.participantsSize
                )
            }

            val friends = feedEntries.filter { it.friendUserId != null }.map {
                MessagingFriendInfo(
                    it.friendUserId!!,
                    appContext.database.getConversationLinkFromUserId(it.friendUserId!!)?.clientConversationId,
                    it.friendDisplayName,
                    it.friendDisplayUsername!!.split("|")[1],
                    it.bitmojiAvatarId,
                    it.bitmojiSelfieId,
                    streaks = null
                )
            }

            appContext.bridgeClient.passGroupsAndFriends(groups, friends)
        }
    }

    private fun syncRemote() {
        appContext.bridgeClient.sync(object : SyncCallback.Stub() {
            override fun syncFriend(uuid: String): String? {
                return appContext.database.getFriendInfo(uuid)?.let {
                    MessagingFriendInfo(
                        userId = it.userId!!,
                        dmConversationId = appContext.database.getConversationLinkFromUserId(it.userId!!)?.clientConversationId,
                        displayName = it.displayName,
                        mutableUsername = it.mutableUsername!!,
                        bitmojiId = it.bitmojiAvatarId,
                        selfieId = it.bitmojiSelfieId,
                        streaks = if (it.streakLength > 0) {
                            FriendStreaks(
                                expirationTimestamp = it.streakExpirationTimestamp,
                                length = it.streakLength
                            )
                        } else null
                    ).toSerialized()
                }
            }

            override fun syncGroup(uuid: String): String? {
                return appContext.database.getFeedEntryByConversationId(uuid)?.let {
                    MessagingGroupInfo(
                        it.key!!,
                        it.feedDisplayName!!,
                        it.participantsSize
                    ).toSerialized()
                }
            }
        })
    }

    private fun jetpackComposeResourceHook() {
        val material3RString = try {
            Class.forName("androidx.compose.material3.R\$string")
        } catch (e: ClassNotFoundException) {
            return
        }

        val stringResources = material3RString.fields.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
        }.associate { it.getInt(null) to it.name }

        Resources::class.java.getMethod("getString", Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
            val key = param.arg<Int>(0)
            val name = stringResources[key] ?: return@hook
            // FIXME: prevent blank string in translations
            if (name == "date_range_input_title") {
                param.setResult("")
                return@hook
            }
            param.setResult(appContext.translation.getOrNull("material3_strings.$name") ?: return@hook)
        }
    }
}