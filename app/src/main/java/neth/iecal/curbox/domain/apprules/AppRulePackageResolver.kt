package neth.iecal.curbox.domain.apprules

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.provider.Settings
import neth.iecal.curbox.Constants

/** Public seam for independently refreshing launchable and always safe package sets. */
class AppRulePackageScopeReader(
    private val launchableReader: () -> Set<String>,
    private val essentialReader: () -> Set<String>
) {
    fun readLaunchablePackages(): Set<String> = launchableReader().normalizedPackages()

    fun readEssentialPackages(): Set<String> = essentialReader().normalizedPackages()

    companion object {
        fun fromContext(context: Context): AppRulePackageScopeReader {
            val appContext = context.applicationContext
            return AppRulePackageScopeReader(
                launchableReader = { AppRuleLaunchablePackages.fromContext(appContext) },
                essentialReader = {
                    AppRuleEssentialPackages.fromContext(appContext).all +
                        appContext.packageName + Constants.SYSTEM_UI_PACKAGE_NAME
                }
            )
        }
    }
}

private fun Set<String>.normalizedPackages(): Set<String> =
    map(String::trim).filter(String::isNotEmpty).toSet()

/** The packages that every rule removes after resolving its configured include union. */
data class AppRuleEssentialPackages(
    val ownPackage: String,
    val launcherPackages: Set<String>,
    val systemUiPackage: String = Constants.SYSTEM_UI_PACKAGE_NAME,
    val currentInputMethodPackage: String? = null
) {
    val all: Set<String>
        get() = buildSet {
            add(ownPackage)
            addAll(launcherPackages)
            add(systemUiPackage)
            currentInputMethodPackage?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        }

    companion object {
        fun fromContext(context: Context): AppRuleEssentialPackages {
            val appContext = context.applicationContext
            val launcherPackages = runCatching {
                val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                }
                appContext.packageManager.queryIntentActivities(
                    homeIntent,
                    PackageManager.MATCH_DEFAULT_ONLY
                ).mapNotNull { it.activityInfo?.packageName }.toSet()
            }.getOrDefault(emptySet())
            val inputMethod = runCatching {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/')
            }.getOrNull()
            return AppRuleEssentialPackages(
                ownPackage = appContext.packageName,
                launcherPackages = launcherPackages,
                currentInputMethodPackage = inputMethod
            )
        }
    }
}

/** Current launcher-visible package set. It intentionally excludes custom/non-launchable apps. */
object AppRuleLaunchablePackages {
    fun fromContext(context: Context): Set<String> {
        val appContext = context.applicationContext
        return runCatching {
            val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? LauncherApps ?: return@runCatching emptySet()
            launcherApps.profiles.flatMap { profile ->
                launcherApps.getActivityList(null, profile).mapNotNull {
                    it.applicationInfo?.packageName
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }
}
