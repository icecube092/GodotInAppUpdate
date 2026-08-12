package com.aintdevs.inappupdate

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

/**
 * Godot 4 Android plugin wrapping the Google Play In-App Update API.
 *
 * Discovery is done through the `org.godotengine.plugin.v2.InappUpdate`
 * meta-data entry in AndroidManifest.xml — the plugin name below must match
 * the meta-data key suffix, and the Godot-side singleton name.
 */
class InappUpdatePlugin(godot: Godot) : GodotPlugin(godot) {

	companion object {
		private const val TAG = "InappUpdatePlugin"
		private const val PLUGIN_NAME = "InappUpdatePlugin"

		// Any value not otherwise used by the app's activities.
		private const val UPDATE_REQUEST_CODE = 54321

		private const val SIGNAL_UPDATE_AVAILABLE = "update_available"
		private const val SIGNAL_UPDATE_NOT_AVAILABLE = "update_not_available"
		private const val SIGNAL_UPDATE_CHECK_FAILED = "update_check_failed"
		private const val SIGNAL_DOWNLOAD_PROGRESS = "update_download_progress"
		private const val SIGNAL_UPDATE_DOWNLOADED = "update_downloaded"
		private const val SIGNAL_UPDATE_FLOW_CANCELED = "update_flow_canceled"
		private const val SIGNAL_UPDATE_FLOW_FAILED = "update_flow_failed"
		private const val SIGNAL_INSTALL_STATUS = "install_status_changed"
	}

	private val appUpdateManager: AppUpdateManager by lazy {
		AppUpdateManagerFactory.create(activity!!.applicationContext)
	}

	private var pendingUpdateInfo: AppUpdateInfo? = null
	private var flexibleListenerRegistered = false

	private val installListener = InstallStateUpdatedListener { state: InstallState ->
		emitSignal(SIGNAL_INSTALL_STATUS, state.installStatus())
		when (state.installStatus()) {
			InstallStatus.DOWNLOADING -> emitSignal(
				SIGNAL_DOWNLOAD_PROGRESS,
				state.bytesDownloaded().toInt(),
				state.totalBytesToDownload().toInt()
			)
			InstallStatus.DOWNLOADED -> {
				emitSignal(SIGNAL_UPDATE_DOWNLOADED)
				unregisterFlexibleListener()
			}
			InstallStatus.FAILED -> {
				emitSignal(SIGNAL_UPDATE_FLOW_FAILED, "install_failed:${state.installErrorCode()}")
				unregisterFlexibleListener()
			}
			InstallStatus.CANCELED -> {
				emitSignal(SIGNAL_UPDATE_FLOW_CANCELED)
				unregisterFlexibleListener()
			}
			else -> {}
		}
	}

	override fun getPluginName(): String = PLUGIN_NAME

	override fun getPluginSignals(): MutableSet<SignalInfo> = mutableSetOf(
		SignalInfo(SIGNAL_UPDATE_AVAILABLE, Integer::class.java, Integer::class.java),
		SignalInfo(SIGNAL_UPDATE_NOT_AVAILABLE),
		SignalInfo(SIGNAL_UPDATE_CHECK_FAILED, String::class.java),
		SignalInfo(SIGNAL_DOWNLOAD_PROGRESS, Integer::class.java, Integer::class.java),
		SignalInfo(SIGNAL_UPDATE_DOWNLOADED),
		SignalInfo(SIGNAL_UPDATE_FLOW_CANCELED),
		SignalInfo(SIGNAL_UPDATE_FLOW_FAILED, String::class.java),
		SignalInfo(SIGNAL_INSTALL_STATUS, Integer::class.java)
	)

	@UsedByGodot
	fun checkForUpdate() {
		Log.i(TAG, "checkForUpdate() called")
		appUpdateManager.appUpdateInfo
			.addOnSuccessListener { info ->
				pendingUpdateInfo = info
				Log.i(
					TAG,
					"onSuccess: availability=${info.updateAvailability()}" +
						" availableVersionCode=${info.availableVersionCode()}" +
						" priority=${info.updatePriority()}" +
						" flexibleAllowed=${info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}" +
						" immediateAllowed=${info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)}"
				)
				if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
					emitSignal(SIGNAL_UPDATE_AVAILABLE, info.availableVersionCode(), info.updatePriority())
				} else {
					emitSignal(SIGNAL_UPDATE_NOT_AVAILABLE)
				}
			}
			.addOnFailureListener { e ->
				Log.e(TAG, "checkForUpdate failed", e)
				emitSignal(SIGNAL_UPDATE_CHECK_FAILED, e.message ?: "unknown")
			}
	}

	// --- Synchronous queries over the last checkForUpdate() result ---------
	// All read the cached AppUpdateInfo, so they are only meaningful after a
	// successful checkForUpdate(). Safe (return defaults) before that.

	@UsedByGodot
	fun isUpdateAvailable(): Boolean =
		pendingUpdateInfo?.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE

	@UsedByGodot
	fun isFlexibleAllowed(): Boolean =
		pendingUpdateInfo?.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ?: false

	@UsedByGodot
	fun isImmediateAllowed(): Boolean =
		pendingUpdateInfo?.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ?: false

	@UsedByGodot
	fun getAvailableVersionCode(): Int =
		pendingUpdateInfo?.availableVersionCode() ?: -1

	@UsedByGodot
	fun getUpdatePriority(): Int =
		pendingUpdateInfo?.updatePriority() ?: 0

	/** Days since the update became available on the device, or -1 if unknown. */
	@UsedByGodot
	fun getClientVersionStalenessDays(): Int =
		pendingUpdateInfo?.clientVersionStalenessDays() ?: -1

	/** Raw Play InstallStatus, or 0 (UNKNOWN) if no info yet. */
	@UsedByGodot
	fun getInstallStatus(): Int =
		pendingUpdateInfo?.installStatus() ?: InstallStatus.UNKNOWN

	@UsedByGodot
	fun startFlexibleUpdate() = startUpdate(AppUpdateType.FLEXIBLE)

	@UsedByGodot
	fun startImmediateUpdate() = startUpdate(AppUpdateType.IMMEDIATE)

	@UsedByGodot
	fun completeFlexibleUpdate() {
		appUpdateManager.completeUpdate()
	}

	private fun startUpdate(type: Int) {
		val info = pendingUpdateInfo
		val act = activity
		if (info == null || act == null) {
			emitSignal(SIGNAL_UPDATE_FLOW_FAILED, "no_pending_update_info")
			return
		}
		if (!info.isUpdateTypeAllowed(type)) {
			emitSignal(SIGNAL_UPDATE_FLOW_FAILED, "update_type_not_allowed")
			return
		}
		if (type == AppUpdateType.FLEXIBLE && !flexibleListenerRegistered) {
			appUpdateManager.registerListener(installListener)
			flexibleListenerRegistered = true
		}
		try {
			appUpdateManager.startUpdateFlowForResult(
				info,
				act,
				AppUpdateOptions.newBuilder(type).build(),
				UPDATE_REQUEST_CODE
			)
		} catch (e: Exception) {
			Log.e(TAG, "startUpdateFlowForResult failed", e)
			unregisterFlexibleListener()
			emitSignal(SIGNAL_UPDATE_FLOW_FAILED, e.message ?: "start_failed")
		}
	}

	private fun unregisterFlexibleListener() {
		if (flexibleListenerRegistered) {
			appUpdateManager.unregisterListener(installListener)
			flexibleListenerRegistered = false
		}
	}

	override fun onMainActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		if (requestCode != UPDATE_REQUEST_CODE) return
		when (resultCode) {
			Activity.RESULT_OK -> { /* Accepted. Flexible progress continues via installListener. */ }
			Activity.RESULT_CANCELED -> {
				unregisterFlexibleListener()
				emitSignal(SIGNAL_UPDATE_FLOW_CANCELED)
			}
			else -> {
				// ActivityResult.RESULT_IN_APP_UPDATE_FAILED == 1
				unregisterFlexibleListener()
				emitSignal(SIGNAL_UPDATE_FLOW_FAILED, "result_code:$resultCode")
			}
		}
	}

	override fun onMainResume() {
		// Two recovery cases Google requires us to handle on resume:
		//  1. A flexible update finished downloading while we were backgrounded.
		//  2. An immediate update was interrupted and must be resumed.
		appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
			pendingUpdateInfo = info
			if (info.installStatus() == InstallStatus.DOWNLOADED) {
				emitSignal(SIGNAL_UPDATE_DOWNLOADED)
			}
			if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
				activity?.let { act ->
					try {
						appUpdateManager.startUpdateFlowForResult(
							info,
							act,
							AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
							UPDATE_REQUEST_CODE
						)
					} catch (e: Exception) {
						Log.e(TAG, "resume immediate update failed", e)
					}
				}
			}
		}
	}
}
