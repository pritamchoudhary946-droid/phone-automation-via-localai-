package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.onDispose
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

/**
 * Optional Firebase Google sign-in, with explicit diagnostics for missing Firebase/OAuth
 * configuration. The rest of Jarvis stays usable without Firebase configuration.
 */
@Composable
fun GoogleAccountCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val firebaseConfigured = remember(context) {
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }
    val auth = remember(firebaseConfigured) {
        if (firebaseConfigured) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null
    }
    val webClientId = remember(context) {
        runCatching {
            val resourceId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName
            )
            if (resourceId != 0) context.getString(resourceId).trim() else ""
        }.getOrDefault("")
    }
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()

    var accountLabel by remember { mutableStateOf(auth?.currentUser?.email ?: auth?.currentUser?.displayName) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            accountLabel = user?.email ?: user?.displayName
        }
        auth?.addAuthStateListener(listener)
        onDispose {
            auth?.removeAuthStateListener(listener)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Google account", fontWeight = FontWeight.SemiBold)
            Text(
                "Optional sign-in. Jarvis's local automation and LLM API settings do not require a Google account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                auth == null -> {
                    Text(
                        "Google sign-in is not configured in this APK. Add app/google-services.json for package com.aistudio.omniagent.kjqvlm, then rebuild.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                webClientId.isBlank() -> {
                    Text(
                        "Firebase loaded, but the Web OAuth client ID is missing. In Firebase Console, enable Google sign-in and add a Web OAuth client, then download a fresh google-services.json.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                accountLabel != null -> {
                    Text(
                        "Signed in as $accountLabel",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            auth.signOut()
                            errorMessage = null
                            successMessage = "Signed out."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign out")
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                errorMessage = null
                                successMessage = null
                                try {
                                    val googleOption = GetGoogleIdOption.Builder()
                                        .setFilterByAuthorizedAccounts(false)
                                        .setServerClientId(webClientId)
                                        .setAutoSelectEnabled(false)
                                        .build()
                                    val request = GetCredentialRequest.Builder()
                                        .addCredentialOption(googleOption)
                                        .build()
                                    val result = credentialManager.getCredential(
                                        context = context,
                                        request = request
                                    )
                                    val credential = result.credential
                                    if (credential !is CustomCredential ||
                                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                    ) {
                                        isBusy = false
                                        errorMessage = "Google returned an unsupported credential. Update Google Play services and try again."
                                        return@launch
                                    }

                                    val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
                                    val firebaseCredential = GoogleAuthProvider.getCredential(
                                        googleToken.idToken,
                                        null
                                    )
                                    auth.signInWithCredential(firebaseCredential)
                                        .addOnCompleteListener { task ->
                                            isBusy = false
                                            if (task.isSuccessful) {
                                                errorMessage = null
                                                val user = task.result?.user
                                                successMessage = "Signed in as " + (user?.email ?: "your Google account") + "."
                                            } else {
                                                errorMessage = explainGoogleSignInFailure(task.exception)
                                            }
                                        }
                                } catch (_: GetCredentialCancellationException) {
                                    isBusy = false
                                    successMessage = "Sign-in cancelled."
                                } catch (e: Exception) {
                                    isBusy = false
                                    errorMessage = explainGoogleSignInFailure(e)
                                }
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Connecting…" else "Continue with Google")
                    }
                }
            }

            errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            successMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun explainGoogleSignInFailure(error: Throwable?): String {
    val detail = error?.message.orEmpty()
    val lower = detail.lowercase()
    return when {
        lower.contains("developer_error") ||
            lower.contains("status code: 10") ||
            lower.contains("code 10") ||
            lower.contains("invalid_audience") ||
            (lower.contains("audience") && lower.contains("token")) ->
            "Google OAuth configuration mismatch. Confirm the app package is com.aistudio.omniagent.kjqvlm, the Web client ID belongs to this Firebase project, and the installed APK's signing SHA-1 is registered in Firebase."
        lower.contains("network") || lower.contains("timeout") ->
            "Sign-in could not reach Google/Firebase. Check your internet connection and try again."
        lower.contains("operation not allowed") || lower.contains("provider is disabled") ->
            "Google sign-in is disabled for this Firebase project. Enable the Google provider in Firebase Console > Authentication > Sign-in method."
        else ->
            "Google sign-in failed" + (if (detail.isNotBlank()) ": " + detail.take(220) else ".") +
                " Check the Firebase Google provider, Web OAuth client ID, package ID, and signing SHA-1."
    }
}
