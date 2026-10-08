package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

class AppDirectory(private val context: Context) {

    // Common app aliases and canonical package names
    private val staticAliases = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "chrome" to listOf("com.android.chrome"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "maps" to listOf("com.google.android.apps.maps"),
        "spotify" to listOf("com.spotify.music"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "gmail" to listOf("com.google.android.gm"),
        "whatsapp" to listOf("com.whatsapp"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "calendar" to listOf("com.google.android.calendar")
    )

    fun resolvePackage(query: String): String? {
        val cleanQuery = query.trim().lowercase()

        // 1. Check known aliases
        for ((alias, packages) in staticAliases) {
            if (cleanQuery.contains(alias)) {
                for (pkg in packages) {
                    if (isPackageInstalled(pkg)) return pkg
                }
            }
        }

        // 2. Dynamic query through PackageManager
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val pkgAppsList: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in pkgAppsList) {
                val appLabel = resolveInfo.loadLabel(pm).toString().lowercase()
                val pkgName = resolveInfo.activityInfo.packageName.lowercase()

                if (appLabel.contains(cleanQuery) || cleanQuery.contains(appLabel) || pkgName.contains(cleanQuery)) {
                    return resolveInfo.activityInfo.packageName
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
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
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

