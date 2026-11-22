package com.msp1974.vacompanion.device

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.VACADeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * What the app does when it is the device owner of a dedicated satellite (provisioned once with
 * `adb shell dpm set-device-owner com.msp1974.vacompanion/.VACADeviceAdminReceiver`):
 * grants itself the satellite's permissions, stays the home app, and installs updates silently
 * ([installUpdate], from a URL HA or the add-on gives). [release] undoes it without wiping the
 * phone: only the owner app itself can, so it is reachable from HA (action release_device_owner)
 * and from ADB (OwnerCommandReceiver). On a phone that isn't managed every call does nothing.
 */
object ManagedDevice {

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, VACADeviceAdminReceiver::class.java)

    fun isOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Applies the owner policies; safe to call on every start. */
    fun apply(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        for (permission in permissions) {
            runCatching {
                dpm.setPermissionGrantState(admin, context.packageName, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.onFailure { Timber.w("Managed device: $permission not granted: ${it.message}") }
        }
        runCatching {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        }.onFailure { Timber.w(it, "Managed device: home/lock task not set") }
        Timber.i("Managed device: owner policies applied")
    }

    // Account types a dedicated satellite must not have: Google, Samsung, Xiaomi.
    private val BLOCKED_ACCOUNT_TYPES = listOf(
        "com.google", "com.osp.app.signin", "com.samsung.android.mobileservice",
        "com.samsung.android.coreapps", "com.xiaomi",
    )

    /**
     * A dedicated satellite has no personal accounts, for the user's safety: removes them and
     * stops new ones being added (release undoes the block). [accounts] lists "type:name" pairs
     * (as ADB reads them from dumpsys), for accounts this app can't see; otherwise every account
     * visible to it is removed. Returns how many were removed.
     */
    fun removeAccounts(context: Context, accounts: List<String> = emptyList()): Int {
        if (!isOwner(context)) return 0
        val manager = AccountManager.get(context)
        val targets = accounts.mapNotNull { entry ->
            val type = entry.substringBefore(':', "")
            val name = entry.substringAfter(':', "")
            if (type.isEmpty() || name.isEmpty()) null else Account(name, type)
        }.ifEmpty { manager.accounts.toList() }
        var removed = 0
        for (account in targets) {
            runCatching { manager.removeAccount(account, null, null, null).result }
                .onSuccess { ok -> if (ok.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)) removed++ }
                .onFailure { Timber.w("Managed device: account of type ${account.type} not removed: ${it.message}") }
        }
        blockAccounts(context, true)
        Timber.i("Managed device: removed $removed of ${targets.size} account(s)")
        return removed
    }

    private fun blockAccounts(context: Context, blocked: Boolean) {
        val dpm = dpm(context)
        val admin = admin(context)
        for (type in BLOCKED_ACCOUNT_TYPES) {
            runCatching { dpm.setAccountManagementDisabled(admin, type, blocked) }
        }
    }

    /** Gives up device ownership, so the phone is an ordinary phone again (no wipe needed). */
    fun release(context: Context): Boolean {
        if (!isOwner(context)) return false
        val dpm = dpm(context)
        val admin = admin(context)
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { blockAccounts(context, false) }
        return runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
            .onSuccess { Timber.i("Managed device: device owner released") }
            .onFailure { Timber.e(it, "Managed device: release failed") }
            .isSuccess
    }

    /**
     * Downloads the APK at [url], checks it against [sha256] (hex) and installs it silently.
     * Android then restarts the app (UpdateRestartReceiver reopens it). Only as device owner.
     */
    suspend fun installUpdate(context: Context, url: String, sha256: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(isOwner(context)) { "not the device owner" }
            require(sha256.length == 64) { "sha256 missing" }
            val apk = File(context.cacheDir, "update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            check(got.equals(sha256, ignoreCase = true)) { "sha256 mismatch: $got" }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val status = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                Timber.i("Managed device: installing update from $url")
                session.commit(status.intentSender)
            }
            apk.delete()
            Unit
        }.onFailure { Timber.e(it, "Managed device: update not installed") }
    }
}

/** Logs how a silent install ended. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) Timber.i("Managed device: update installed")
        else Timber.e("Managed device: update failed ($status): $message")
    }
}

/**
 * ADB-only commands for a managed satellite (protected by the DUMP permission, which the adb shell
 * holds and ordinary apps don't):
 *   adb shell am broadcast -a com.msp1974.vacompanion.RELEASE_OWNER -n com.msp1974.vacompanion/.device.OwnerCommandReceiver
 *   adb shell am broadcast -a com.msp1974.vacompanion.INSTALL_UPDATE --es url <url> --es sha256 <hex> -n ...
 *   adb shell am broadcast -a com.msp1974.vacompanion.REMOVE_ACCOUNTS [--es accounts type:name,...] -n ...
 */
class OwnerCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RELEASE -> ManagedDevice.release(context)
            ACTION_REMOVE_ACCOUNTS -> {
                val accounts = intent.getStringExtra("accounts")?.split(',')?.map { it.trim() }.orEmpty()
                // AccountManager futures must not run on the main thread.
                Thread { ManagedDevice.removeAccounts(context, accounts) }.start()
            }
            ACTION_INSTALL -> {
                val url = intent.getStringExtra("url") ?: return
                val sha = intent.getStringExtra("sha256") ?: return
                // The satellite's foreground service keeps the process alive meanwhile.
                Thread { kotlinx.coroutines.runBlocking { ManagedDevice.installUpdate(context, url, sha) } }.start()
            }
        }
    }

    companion object {
        const val ACTION_RELEASE = "com.msp1974.vacompanion.RELEASE_OWNER"
        const val ACTION_INSTALL = "com.msp1974.vacompanion.INSTALL_UPDATE"
        const val ACTION_REMOVE_ACCOUNTS = "com.msp1974.vacompanion.REMOVE_ACCOUNTS"
    }
}
