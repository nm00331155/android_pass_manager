package com.securevault.app.service.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SecureVaultAutofillService : AutofillService() {

    @Inject
    lateinit var credentialRepository: CredentialRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onConnected() {
        Log.i(TAG, "Autofill service connected")
    }

    override fun onDisconnected() {
        Log.i(TAG, "Autofill service disconnected")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val packageName = structure.activityComponent?.packageName.orEmpty()
        val targets = findAutofillTargets(structure)

        if (targets.usernameId == null && targets.passwordId == null) {
            callback.onSuccess(null)
            return
        }

        Log.d(TAG, "onFillRequest package=$packageName")

        val job = serviceScope.launch {
            val credentials = runCatching {
                resolveCredentials(packageName, targets.urlHint)
            }.getOrElse { throwable ->
                Log.w(TAG, "Failed to resolve autofill credentials", throwable)
                emptyList()
            }

            val response = buildFillResponse(targets, credentials)
            withContext(Dispatchers.Main) {
                callback.onSuccess(response)
            }
        }

        cancellationSignal.setOnCancelListener {
            job.cancel()
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val packageName = structure?.activityComponent?.packageName ?: "unknown"
        Log.d(TAG, "onSaveRequest from package=$packageName")
        callback.onSuccess()
    }

    private suspend fun resolveCredentials(packageName: String, urlHint: String?): List<Credential> {
        val fromPackage = if (packageName.isNotBlank()) {
            credentialRepository.findByPackageName(packageName)
        } else {
            emptyList()
        }
        if (fromPackage.isNotEmpty()) {
            return fromPackage.take(MAX_DATASET_ITEMS)
        }

        val fromUrl = if (!urlHint.isNullOrBlank()) {
            credentialRepository.findByUrl(urlHint)
        } else {
            emptyList()
        }
        if (fromUrl.isNotEmpty()) {
            return fromUrl.take(MAX_DATASET_ITEMS)
        }

        return credentialRepository.getAll().first().take(MAX_DATASET_ITEMS)
    }

    private fun buildFillResponse(targets: AutofillTargets, credentials: List<Credential>): FillResponse? {
        var hasDataset = false
        val responseBuilder = FillResponse.Builder()

        credentials.forEach { credential ->
            val presentation = createPresentation(credential.serviceName)
            val datasetBuilder = Dataset.Builder(presentation)
            var hasValue = false

            targets.usernameId?.let { usernameId ->
                datasetBuilder.setValue(
                    usernameId,
                    AutofillValue.forText(credential.username),
                    createPresentation("${credential.serviceName} / ${credential.username}")
                )
                hasValue = true
            }

            targets.passwordId?.let { passwordId ->
                datasetBuilder.setValue(
                    passwordId,
                    AutofillValue.forText(credential.password),
                    createPresentation("${credential.serviceName} / ******")
                )
                hasValue = true
            }

            if (hasValue) {
                responseBuilder.addDataset(datasetBuilder.build())
                hasDataset = true
            }
        }

        return if (hasDataset) responseBuilder.build() else null
    }

    private fun createPresentation(text: String): RemoteViews {
        return RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, text)
        }
    }

    private fun findAutofillTargets(structure: AssistStructure): AutofillTargets {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var urlHint: String? = null

        fun traverse(node: AssistStructure.ViewNode) {
            val hints = node.autofillHints?.map { it.lowercase() }.orEmpty()
            val idEntry = node.idEntry?.lowercase().orEmpty()
            val hint = node.hint?.lowercase().orEmpty()

            if (usernameId == null) {
                val usernameMatched = hints.any {
                    it.contains("username") || it.contains("email") || it.contains("login")
                } || idEntry.contains("user") || idEntry.contains("mail") || hint.contains("user")

                if (usernameMatched) {
                    usernameId = node.autofillId
                }
            }

            if (passwordId == null) {
                val passwordMatched = hints.any {
                    it.contains("password") || it.contains("pass")
                } || idEntry.contains("password") || idEntry.contains("pass") || hint.contains("pass")

                if (passwordMatched) {
                    passwordId = node.autofillId
                }
            }

            if (urlHint.isNullOrBlank()) {
                val domain = runCatching { node.webDomain?.toString() }.getOrNull()
                if (!domain.isNullOrBlank()) {
                    urlHint = domain
                }
            }

            for (index in 0 until node.childCount) {
                traverse(node.getChildAt(index))
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(windowIndex).rootViewNode
            traverse(root)
        }

        return AutofillTargets(
            usernameId = usernameId,
            passwordId = passwordId,
            urlHint = urlHint
        )
    }

    private data class AutofillTargets(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val urlHint: String?
    )

    private companion object {
        const val TAG = "SecureVaultAutofill"
        const val MAX_DATASET_ITEMS = 8
    }
}
