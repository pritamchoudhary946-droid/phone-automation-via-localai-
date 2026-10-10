package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

class AppDirectory(private val context: Context) {

    // Common app aliases and canonical package names
    private val staticAliases = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "youtube music" to listOf("com.google.android.apps.youtube.music"),
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "maps" to listOf("com.google.android.apps.maps"),
        "spotify" to listOf("com.spotify.music"),
        "music" to listOf("com.spotify.music", "com.google.android.apps.youtube.music"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "gmail" to listOf("com.google.android.gm"),
        "email" to listOf("com.google.android.gm"),
        "whatsapp" to listOf("com.whatsapp"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "calendar" to listOf("com.google.android.calendar"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "dialer" to listOf("com.google.android.dialer", "com.android.dialer"),
        "contacts" to listOf("com.google.android.contacts", "com.android.contacts"),
        "google photos" to listOf("com.google.android.apps.photos"),
        "photos" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
        "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
        "playstore" to listOf("com.android.vending"),
        "play store" to listOf("com.android.vending")
    )

    fun resolvePackage(query: String): String? {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return null

        // 1. Check known aliases. Pick the LONGEST alias contained in the query so
        //    e.g. "play store" wins over "store" instead of matching a shorter alias
        //    that merely appears as a substring.
        val matchedAlias = staticAliases.keys
            .filter { alias -> cleanQuery.contains(alias) }
            .maxByOrNull { it.length }
        if (matchedAlias != null) {
            for (pkg in staticAliases.getValue(matchedAlias)) {
                if (isPackageInstalled(pkg)) return pkg
            }
            // Never return an unverified package name.
            return null
        }

        // 2. Dynamic query through PackageManager
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val pkgAppsList: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

            // Prefer a label match, then a package-name match. Use non-localized labels
            // where possible so a resolved app is not missed under a non-English locale.
            var packageNameMatch: String? = null
            for (resolveInfo in pkgAppsList) {
                val info = resolveInfo.activityInfo ?: continue
                val appLabel = resolveInfo.loadLabel(pm)?.toString()?.lowercase()
                val nonLocalized = info.applicationInfo?.nonLocalizedLabel?.toString()?.lowercase()
                val pkgName = info.packageName.lowercase()

                val labelMatches = (appLabel != null &&
                    (appLabel.contains(cleanQuery) || cleanQuery.contains(appLabel))) ||
                    (nonLocalized != null &&
                        (nonLocalized.contains(cleanQuery) || cleanQuery.contains(nonLocalized)))
                if (labelMatches) {
                    return info.packageName
                }
                if (packageNameMatch == null && pkgName.contains(cleanQuery)) {
                    packageNameMatch = info.packageName
                }
            }
            return packageNameMatch
        } catch (e: Exception) {
            Log.w("AppDirectory", "Dynamic app resolution failed for '$query': ${e.message}")
        }

        return null
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            if (!isPackageInstalled(packageName)) {
                Log.w("AppDirectory", "Package is not installed: $packageName")
                return false
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null && launchIntent.resolveActivity(context.packageManager) != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("AppDirectory", "Failed to launch app $packageName: ${e.message}", e)
            false
        }
    }

    fun launchSearch(query: String, packageName: String? = null): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                if (packageName != null) setPackage(packageName)
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w("AppDirectory", "Search intent did not resolve for package=$packageName")
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppDirectory", "Search intent failed for package=$packageName: ${e.message}", e)
            false
        }
    }

    /**
     * Opens an http(s) [url] in whatever app handles ACTION_VIEW (the default
     * browser). Never falls back to a bare ACTION_VIEW with an unsupported scheme.
     */
    fun openUrl(url: String): Boolean {
        val clean = url.trim()
        if (!clean.lowercase().startsWith("http")) {
            Log.w("AppDirectory", "openUrl rejected non-http url: $clean")
            return false
        }
        return startViewIntent(clean)
    }

    /**
     * Opens an app-specific deep link (e.g. "spotify:artist:..." or "maps://...").
     * Any scheme other than http(s) is allowed here because the caller explicitly
     * requested it; an unhandled scheme fails cleanly instead of crashing.
     */
    fun openDeepLink(uri: String): Boolean {
        val clean = uri.trim()
        if (clean.isBlank()) return false
        return startViewIntent(clean)
    }

    private fun startViewIntent(uriString: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w("AppDirectory", "ACTION_VIEW did not resolve for $uriString")
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppDirectory", "ACTION_VIEW failed for $uriString: ${e.message}", e)
            false
        }
    }
}
