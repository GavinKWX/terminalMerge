package com.sc.mf919.kotlin.helper_common

import iso.HostCertStore
import java.io.InputStream

/**
 * This app's side of [HostCertStore].
 *
 * Both values are app-owned: the certificate is a raw resource, and the keystore directory comes
 * from this app's own ServiceHolder. `:core` holds neither, which is the whole reason the seam
 * exists. Registered once per process in MF919.onCreate.
 */
object Mf919CertStore : HostCertStore {

	override fun openCertificate(certId: Int): InputStream =
		ServiceHolder.getContext().resources.openRawResource(certId)

	override fun keystoreDir(): String? = ServiceHolder.getInternalFilesPaths()
}
