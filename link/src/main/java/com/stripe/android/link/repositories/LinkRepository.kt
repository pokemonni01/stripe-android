package com.stripe.android.link.repositories

import com.stripe.android.link.LinkPaymentDetails
import com.stripe.android.model.ConsumerPaymentDetails
import com.stripe.android.model.ConsumerPaymentDetailsUpdateParams
import com.stripe.android.model.ConsumerSession
import com.stripe.android.model.ConsumerSessionLookup
import com.stripe.android.model.ConsumerSignUpConsentAction
import com.stripe.android.model.FinancialConnectionsSession
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.model.StripeIntent

/**
 * Interface for a repository that interacts with Link services.
 */
internal interface LinkRepository {

    /**
     * Check if the email already has a link account.
     */
    suspend fun lookupConsumer(
        email: String?,
        authSessionCookie: String?
    ): Result<ConsumerSessionLookup>

    /**
     * Sign up for a new Link account.
     */
    suspend fun consumerSignUp(
        email: String,
        phone: String,
        country: String,
        name: String?,
        authSessionCookie: String?,
        consentAction: ConsumerSignUpConsentAction
    ): Result<ConsumerSession>

    /**
     * Start an SMS verification.
     */
    suspend fun startVerification(
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?,
        authSessionCookie: String?
    ): Result<ConsumerSession>

    /**
     * Confirm an SMS verification code.
     */
    suspend fun confirmVerification(
        verificationCode: String,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?,
        authSessionCookie: String?
    ): Result<ConsumerSession>

    /**
     * Logs out the current consumer.
     */
    suspend fun logout(
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?,
        authSessionCookie: String?
    ): Result<ConsumerSession>

    /**
     * Fetch all saved payment methods for the consumer.
     */
    suspend fun listPaymentDetails(
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<ConsumerPaymentDetails>

    /**
     * Create a new [FinancialConnectionsSession], used to link a bank account using the Financial
     * Connections SDK.
     */
    suspend fun createFinancialConnectionsSession(
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<FinancialConnectionsSession>

    /**
     * Create a new linked bank account payment method in the consumer account.
     */
    suspend fun createBankAccountPaymentDetails(
        financialConnectionsAccountId: String,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<ConsumerPaymentDetails.BankAccount>

    /**
     * Create a new card payment method in the consumer account.
     */
    suspend fun createCardPaymentDetails(
        paymentMethodCreateParams: PaymentMethodCreateParams,
        userEmail: String,
        stripeIntent: StripeIntent,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<LinkPaymentDetails.New>

    /**
     * Update an existing payment method in the consumer account.
     */
    suspend fun updatePaymentDetails(
        updateParams: ConsumerPaymentDetailsUpdateParams,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<ConsumerPaymentDetails>

    /**
     * Delete the payment method from the consumer account.
     */
    suspend fun deletePaymentDetails(
        paymentDetailsId: String,
        consumerSessionClientSecret: String,
        consumerPublishableKey: String?
    ): Result<Unit>
}
