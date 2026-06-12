package acr.browser.lightning.ssl

import acr.browser.lightning.R
import acr.browser.lightning.certimark.CertimarkStatus
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * Creates the proper [Drawable] to represent the [SslState].
 * Shows a red shield for HTTP, grey shield for HTTPS (before Certimark check).
 */
fun Context.createSslDrawableForState(sslState: SslState): Drawable? = when (sslState) {
    is SslState.None -> ContextCompat.getDrawable(this, R.drawable.ic_shield_red)
    is SslState.Valid -> ContextCompat.getDrawable(this, R.drawable.ic_shield_grey)
    is SslState.Invalid -> ContextCompat.getDrawable(this, R.drawable.ic_shield_red)
}

/**
 * Creates the proper [Drawable] to represent a [CertimarkStatus].
 */
fun Context.createCertimarkDrawable(status: CertimarkStatus): Drawable? = when (status) {
    CertimarkStatus.MATCH_TOP -> ContextCompat.getDrawable(this, R.drawable.ic_shield_green)
    CertimarkStatus.MATCH_OTHER -> ContextCompat.getDrawable(this, R.drawable.ic_shield_yellow)
    CertimarkStatus.NO_MATCH -> ContextCompat.getDrawable(this, R.drawable.ic_shield_red)
    CertimarkStatus.NOT_MARKED -> ContextCompat.getDrawable(this, R.drawable.ic_shield_grey)
    CertimarkStatus.ERROR -> ContextCompat.getDrawable(this, R.drawable.ic_shield_grey)
    CertimarkStatus.HTTP_INSECURE -> ContextCompat.getDrawable(this, R.drawable.ic_shield_red)
    CertimarkStatus.ONION_SECURE -> ContextCompat.getDrawable(this, R.drawable.ic_shield_green)
    CertimarkStatus.SIGNED_TRUSTED -> ContextCompat.getDrawable(this, R.drawable.ic_shield_green)
    CertimarkStatus.TRUSTED -> ContextCompat.getDrawable(this, R.drawable.ic_shield_green)
}
