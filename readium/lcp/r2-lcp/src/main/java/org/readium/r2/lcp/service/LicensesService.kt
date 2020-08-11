/*
 * Module: r2-lcp-kotlin
 * Developers: Aferdita Muriqi
 *
 * Copyright (c) 2019. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.lcp.service

import android.content.Context
import kotlinx.coroutines.*
import org.readium.r2.lcp.*
import org.readium.r2.lcp.BuildConfig.DEBUG
import org.readium.r2.lcp.license.License
import org.readium.r2.lcp.license.LicenseValidation
import org.readium.r2.lcp.license.container.BytesLicenseContainer
import org.readium.r2.lcp.license.container.LicenseContainer
import org.readium.r2.lcp.license.container.createLicenseContainer
import org.readium.r2.lcp.license.model.LicenseDocument
import org.readium.r2.shared.format.Format
import java.io.File
import org.readium.r2.shared.util.Try
import timber.log.Timber
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.resume


internal class LicensesService(
    private val licenses: LicensesRepository,
    private val crl: CRLService,
    private val device: DeviceService,
    private val network: NetworkService,
    private val passphrases: PassphrasesService,
    private val context: Context
) : LcpService, LCPService, CoroutineScope by MainScope() {

    override suspend fun importPublication(lcpl: ByteArray): Try<LcpService.ImportedPublication, LcpException> =
        try {
            val licenseDocument = LicenseDocument(lcpl)
            if (DEBUG) Timber.d("license ${licenseDocument.json}")
            fetchPublication(licenseDocument, context).let { Try.success(it) }
        } catch (e:Exception) {
           Try.failure(LcpException.wrap(e))
        }

    override fun importPublication(lcpl: ByteArray, authentication: LCPAuthenticating?, completion: (LCPImportedPublication?, LCPError?) -> Unit) {
        val container = BytesLicenseContainer(lcpl)
        try {
            retrieveLicense(container, authentication.toLcpAuthenticating(), allowUserInteraction = true, sender = null) { license ->
                if (license != null) {
                    launch {
                        try {
                            completion(fetchPublication(license.license, context).toLCPImportedPublication(), null)
                        } catch (e: Exception) {
                            completion(null, LCPError.wrap(e))
                        }
                    }
                } else {
                    completion(null, null)
                }
            }
        } catch (e:Exception) {
            completion(null, LCPError.wrap(e))
        }
    }

    override suspend fun retrieveLicense(file: File, authentication: LcpAuthenticating?, allowUserInteraction: Boolean, sender: Any?): Try<LcpLicense, LcpException>? =
        try {
            val container = createLicenseContainer(file.path)
            // WARNING: Using the Default dispatcher in the state machine code is critical. If we were using the Main Dispatcher,
            // calling runBlocking in LicenseValidation.handle would block the main thread and cause a severe issue
            // with LcpAuthenticating.retrievePassphrase. Specifically, the interaction of runBlocking and suspendCoroutine
            // blocks the current thread before the passphrase popup has been showed until some button not yet showed is clicked.
            val license = withContext(Dispatchers.Default) { retrieveLicense(container, authentication, allowUserInteraction, sender) }
            Timber.d("license retrieved ${license?.license}")

            license?.let { Try.success(it) }
        } catch (e: Exception) {
            Try.failure(LcpException.wrap(e))
        }

    override fun retrieveLicense(publication: String, authentication: LCPAuthenticating?, completion: (LCPLicense?, LCPError?) -> Unit) {
        try {
            val container = runBlocking { createLicenseContainer(publication) }
            retrieveLicense(container, authentication.toLcpAuthenticating(), true, null) { license ->
                Timber.d("license retrieved ${license?.license}")
                completion(license, null)
            }
        } catch (e:Exception) {
            completion(null, LCPError.wrap(e))
        }
    }

    private suspend fun retrieveLicense(container: LicenseContainer, authentication: LcpAuthenticating?, allowUserInteraction: Boolean, sender: Any?): License? =
        suspendCancellableCoroutine { cont ->
            retrieveLicense(container, authentication, allowUserInteraction, sender) { license ->
                if (cont.isActive) {
                    cont.resume(license)
                }
            }
        }

    private fun retrieveLicense(container: LicenseContainer, authentication: LcpAuthenticating?, allowUserInteraction: Boolean, sender: Any?, completion: (License?) -> Unit) {

        var initialData = container.read()
        if (DEBUG) Timber.d("license ${LicenseDocument(data = initialData).json}")

        val validation = LicenseValidation(authentication = authentication, crl = this.crl,
                device = this.device, network = this.network, passphrases = this.passphrases, context = this.context,
                allowUserInteraction = allowUserInteraction, sender = sender) { licenseDocument ->
            try {
                this.licenses.addLicense(licenseDocument)
            } catch (error: Error) {
                if (DEBUG) Timber.d("Failed to add the LCP License to the local database: $error")
            }
            if (!licenseDocument.data.contentEquals(initialData)) {
                try {
                    container.write(licenseDocument)
                    if (DEBUG) Timber.d("licenseDocument ${licenseDocument.json}")

                    initialData = container.read()
                    if (DEBUG) Timber.d("license ${LicenseDocument(data = initialData).json}")
                    if (DEBUG) Timber.d("Wrote updated License Document in container")
                } catch (error: Error) {
                    if (DEBUG) Timber.d("Failed to write updated License Document in container: $error")
                }
            }

        }

        validation.validate(LicenseValidation.Document.license(initialData)) { documents, error ->
            documents?.let {
                if (DEBUG) Timber.d("validated documents $it")
                try {
                    documents.getContext()
                    completion( License(documents = it, validation = validation, licenses = this.licenses, device = this.device, network = this.network) )
                } catch (e:Exception) {
                    throw e
                }
            }
            error?.let { throw error }

            if (documents == null && error == null) {
                completion(null)
            }
        }
    }

    internal suspend fun fetchPublication(license: LicenseDocument, context: Context): LcpService.ImportedPublication {
        val link = license.link(LicenseDocument.Rel.publication)
        val url = link?.url
            ?: throw LcpException.Parsing.Url(rel = LicenseDocument.Rel.publication.rawValue)

        val properties =  Properties()
        withContext(Dispatchers.IO) {
            context.assets.open("configs/config.properties").let { properties.load(it) }
        }
        val useExternalFileDir = properties.getProperty("useExternalFileDir", "false")!!.toBoolean()

        val rootDir: String =  if (useExternalFileDir) {
            context.getExternalFilesDir(null)?.path + "/"
        } else {
            context.filesDir.path + "/"
        }

        val fileName = UUID.randomUUID().toString()
        val destination = File(rootDir, fileName)
        if (DEBUG) Timber.i("LCP destination $destination")

        val format = network.download(url, destination) ?: Format.of(mediaType = link.type) ?: Format.EPUB

        // Saves the License Document into the downloaded publication
        val container = createLicenseContainer(destination.path, format)
        container.write(license)

        return LcpService.ImportedPublication(
            localURL = destination.path,
            suggestedFilename = "${license.id}.${format.fileExtension}"
        )
    }

}
