@tool
class_name InappUpdate
extends Node
##
## Godot wrapper around the Google Play In-App Update API.
##
## Add this node to an autoload/singleton scene, call [method check_for_update]
## after boot, then react to the signals below. Works only on a real device with
## the app installed from Google Play (or via internal-app-sharing / internal
## testing track). Does nothing in the editor or on other platforms.
##

## The build's own versionCode is not exposed here; `version_code` is the
## versionCode of the update available on Play.
signal update_available(version_code: int, priority: int)
signal update_not_available()
signal update_check_failed(reason: String)

## Emitted while a FLEXIBLE update downloads in the background.
signal update_download_progress(bytes_downloaded: int, total_bytes: int)
## FLEXIBLE update finished downloading — prompt the user, then call
## [method complete_flexible_update] to install and restart.
signal update_downloaded()

## User dismissed the Play update dialog.
signal update_flow_canceled()
## The update flow or install failed; see [param reason].
signal update_flow_failed(reason: String)
## Raw Play InstallStatus int (see InstallStatus constants below).
signal install_status_changed(status: int)

const PLUGIN_SINGLETON_NAME: String = "InappUpdatePlugin"

## Result of [method get_required_update_type] / [method recommended_update_type].
enum UpdateType {
	NONE, ## No update, or none of the allowed types apply.
	FLEXIBLE, ## Non-blocking background update.
	IMMEDIATE, ## Blocking full-screen update.
}

## Play `updatePriority` (0..5) at/above which an update is treated as IMMEDIATE
## by [method recommended_update_type]. Priority is set via the Play Developer
## API at release time; without it priority is always 0.
var immediate_priority_threshold: int = 4
## Days an available update may sit before [method recommended_update_type]
## escalates it to IMMEDIATE. -1 disables the staleness rule.
var immediate_staleness_days: int = 14

# Mirrors com.google.android.play.core.install.model.InstallStatus
const INSTALL_STATUS_UNKNOWN := 0
const INSTALL_STATUS_PENDING := 1
const INSTALL_STATUS_DOWNLOADING := 2
const INSTALL_STATUS_INSTALLING := 3
const INSTALL_STATUS_INSTALLED := 4
const INSTALL_STATUS_FAILED := 5
const INSTALL_STATUS_CANCELED := 6
const INSTALL_STATUS_DOWNLOADED := 11

var _plugin: Object


func _ready() -> void:
	_bind()


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_RESUMED:
		_bind()


func _bind() -> void:
	if _plugin != null:
		return
	if Engine.has_singleton(PLUGIN_SINGLETON_NAME):
		print("%s init" % PLUGIN_SINGLETON_NAME)
		_plugin = Engine.get_singleton(PLUGIN_SINGLETON_NAME)
		_plugin.connect("update_available", _on_update_available)
		_plugin.connect("update_not_available", _on_update_not_available)
		_plugin.connect("update_check_failed", _on_update_check_failed)
		_plugin.connect("update_download_progress", _on_update_download_progress)
		_plugin.connect("update_downloaded", _on_update_downloaded)
		_plugin.connect("update_flow_canceled", _on_update_flow_canceled)
		_plugin.connect("update_flow_failed", _on_update_flow_failed)
		_plugin.connect("install_status_changed", _on_install_status_changed)
	elif not Engine.is_editor_hint():
		push_warning("%s singleton not found (expected off-Android or missing AAR)." % PLUGIN_SINGLETON_NAME)


func is_available() -> bool:
	return _plugin != null

# --- Synchronous queries (valid after check_for_update() succeeds) ------------


## True if Play reports an update available for this app.
func is_update_available() -> bool:
	return _plugin != null and _plugin.isUpdateAvailable()


## True if a FLEXIBLE update is permitted for the available update.
func is_flexible_allowed() -> bool:
	return _plugin != null and _plugin.isFlexibleAllowed()


## True if an IMMEDIATE update is permitted for the available update.
func is_immediate_allowed() -> bool:
	return _plugin != null and _plugin.isImmediateAllowed()


## versionCode of the available update, or -1 if unknown.
func get_available_version_code() -> int:
	return _plugin.getAvailableVersionCode() if _plugin != null else -1


## Play update priority 0..5 (needs Play Developer API to be non-zero).
func get_update_priority() -> int:
	return _plugin.getUpdatePriority() if _plugin != null else 0


## Days the update has been available on this device, or -1 if unknown.
func get_client_version_staleness_days() -> int:
	return _plugin.getClientVersionStalenessDays() if _plugin != null else -1


## Raw Play InstallStatus (see INSTALL_STATUS_* constants).
func get_install_status() -> int:
	return _plugin.getInstallStatus() if _plugin != null else INSTALL_STATUS_UNKNOWN


## Decide which update to run, purely from code (no need to wait on the
## [signal update_available] handler). Escalates to IMMEDIATE when the update's
## priority or staleness crosses the configured thresholds and IMMEDIATE is
## allowed; otherwise FLEXIBLE if allowed; otherwise NONE.
## Returns an [enum UpdateType].
func recommended_update_type() -> UpdateType:
	if not is_update_available():
		return UpdateType.NONE

	var priority := get_update_priority()
	var staleness := get_client_version_staleness_days()
	var wants_immediate := priority >= immediate_priority_threshold \
			or (immediate_staleness_days >= 0 and staleness >= immediate_staleness_days)

	if wants_immediate and is_immediate_allowed():
		return UpdateType.IMMEDIATE
	if is_flexible_allowed():
		return UpdateType.FLEXIBLE
	if is_immediate_allowed():
		return UpdateType.IMMEDIATE
	return UpdateType.NONE


## Run whichever update [method recommended_update_type] selects. Returns the
## type that was started (NONE if nothing was launched).
func start_recommended_update() -> UpdateType:
	var kind := recommended_update_type()
	match kind:
		UpdateType.IMMEDIATE:
			start_immediate_update()
		UpdateType.FLEXIBLE:
			start_flexible_update()
	return kind


## Ask Play whether an update exists. Emits [signal update_available] /
## [signal update_not_available] / [signal update_check_failed].
func check_for_update() -> void:
	if _plugin == null:
		_bind()
	if _plugin != null:
		_plugin.checkForUpdate()
	elif not Engine.is_editor_hint():
		push_error("%s: cannot check — singleton not bound" % PLUGIN_SINGLETON_NAME)


## Background download; the app keeps running. Follow with
## [method complete_flexible_update] on [signal update_downloaded].
func start_flexible_update() -> void:
	if _plugin != null:
		_plugin.startFlexibleUpdate()


## Full-screen blocking update; Play restarts the app itself on success.
func start_immediate_update() -> void:
	if _plugin != null:
		_plugin.startImmediateUpdate()


## Install a downloaded FLEXIBLE update and restart the app.
func complete_flexible_update() -> void:
	if _plugin != null:
		_plugin.completeFlexibleUpdate()


func _on_update_available(version_code: int, priority: int) -> void:
	update_available.emit(version_code, priority)


func _on_update_not_available() -> void:
	update_not_available.emit()


func _on_update_check_failed(reason: String) -> void:
	update_check_failed.emit(reason)


func _on_update_download_progress(bytes_downloaded: int, total_bytes: int) -> void:
	update_download_progress.emit(bytes_downloaded, total_bytes)


func _on_update_downloaded() -> void:
	update_downloaded.emit()


func _on_update_flow_canceled() -> void:
	update_flow_canceled.emit()


func _on_update_flow_failed(reason: String) -> void:
	update_flow_failed.emit(reason)


func _on_install_status_changed(status: int) -> void:
	install_status_changed.emit(status)
