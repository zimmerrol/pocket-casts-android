package au.com.shiftyjelly.pocketcasts.profile.accountmanager

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import au.com.shiftyjelly.pocketcasts.account.AccountActivity
import au.com.shiftyjelly.pocketcasts.account.AccountAuth
import au.com.shiftyjelly.pocketcasts.account.SignInSource
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.getSignInType
import au.com.shiftyjelly.pocketcasts.preferences.peekAccessToken
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class PocketCastsAccountAuthenticator(val context: Context, private val accountAuth: AccountAuth) : AbstractAccountAuthenticator(context) {
    override fun getAuthTokenLabel(authTokenType: String?): String {
        return AccountConstants.TOKEN_TYPE
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle {
        return Bundle()
    }

    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle {
        return Bundle()
    }

    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle {
        val accountManager = AccountManager.get(context)

        var accessToken = if (account != null && authTokenType != null) {
            accountManager.peekAccessToken(account, authTokenType)
        } else null

        if (accessToken != null && account != null) {
            runBlocking {
                Timber.d("Refreshing the access token")
                try {
                    accessToken = accountAuth.accessToken(
                        email = account.name,
                        refreshTokenOrPassword = accountManager.getPassword(account),
                        signInType = accountManager.getSignInType(account),
                        signInSource = SignInSource.AccountAuthenticator,
                        account = account,
                        accountManager = accountManager
                    )
                } catch (ex: Exception) {
                    LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, ex, "Unable to refresh token.")
                }
            }
        }

        accessToken?.value?.let { tokenValue ->
            if (account != null) {
                return bundleOf(
                    AccountManager.KEY_ACCOUNT_NAME to account.name,
                    AccountManager.KEY_ACCOUNT_TYPE to account.type,
                    AccountManager.KEY_AUTHTOKEN to tokenValue
                )
            }
        }

        // Could not get auth token so display sign in sign up screens
        val intent = Intent(context, AccountActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return bundleOf(AccountManager.KEY_INTENT to intent)
    }

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle {
        return Bundle()
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle {
        return Bundle()
    }

    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?): Bundle {
        val intent = Intent(context, AccountActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return bundleOf(AccountManager.KEY_INTENT to intent)
    }
}
