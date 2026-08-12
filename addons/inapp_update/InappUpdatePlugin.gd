@tool
extends EditorPlugin

const PLUGIN_NAME: String = "InappUpdatePlugin"

# Google Play dependencies pulled into the app's gradle build on export.
const ANDROID_DEPENDENCIES: Array = [
	"com.google.android.play:app-update:2.1.0",
	"com.google.android.play:app-update-ktx:2.1.0",
]

var android_export_plugin: AndroidExportPlugin


func _enter_tree() -> void:
	android_export_plugin = AndroidExportPlugin.new()
	add_export_plugin(android_export_plugin)


func _exit_tree() -> void:
	remove_export_plugin(android_export_plugin)
	android_export_plugin = null


class AndroidExportPlugin extends EditorExportPlugin:
	var _plugin_name = PLUGIN_NAME

	func _supports_platform(platform: EditorExportPlatform) -> bool:
		return platform is EditorExportPlatformAndroid

	func _get_android_libraries(platform: EditorExportPlatform, debug: bool) -> PackedStringArray:
		if debug:
			return PackedStringArray(["inapp_update/bin/debug/InappUpdatePlugin-debug.aar"])
		else:
			return PackedStringArray(["inapp_update/bin/release/InappUpdatePlugin-release.aar"])

	func _get_android_dependencies(platform: EditorExportPlatform, debug: bool) -> PackedStringArray:
		return PackedStringArray(ANDROID_DEPENDENCIES)

	func _get_name() -> String:
		return _plugin_name
