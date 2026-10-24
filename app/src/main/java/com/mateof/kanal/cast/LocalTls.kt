package com.mateof.kanal.cast

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS that accepts any certificate, for talking to a television on the home
 * network and nothing else.
 *
 * Chromecasts and LG televisions present self-signed certificates on their
 * control ports; there is no authority that could vouch for them. What goes
 * over these sockets is play/pause/seek and the address of a stream, which
 * travels to the television in the clear over DLNA anyway.
 */
object LocalTls {

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
    }
}
