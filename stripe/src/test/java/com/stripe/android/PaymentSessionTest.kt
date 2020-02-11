package com.stripe.android

import android.app.Activity
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.KArgumentCaptor
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.stripe.android.model.Customer
import com.stripe.android.model.CustomerFixtures
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.model.PaymentMethodFixtures
import com.stripe.android.testharness.TestEphemeralKeyProvider
import com.stripe.android.view.ActivityScenarioFactory
import com.stripe.android.view.ActivityStarter
import com.stripe.android.view.BillingAddressFields
import com.stripe.android.view.PaymentFlowActivity
import com.stripe.android.view.PaymentFlowActivityStarter
import com.stripe.android.view.PaymentMethodsActivity
import com.stripe.android.view.PaymentMethodsActivityStarter
import java.util.concurrent.ThreadPoolExecutor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Test class for [PaymentSession]
 */
@RunWith(RobolectricTestRunner::class)
class PaymentSessionTest {

    private val ephemeralKeyProvider = TestEphemeralKeyProvider()

    private val threadPoolExecutor: ThreadPoolExecutor = mock()
    private val paymentSessionListener: PaymentSession.PaymentSessionListener = mock()
    private val customerSession: CustomerSession = mock()
    private val paymentMethodsActivityStarter:
        ActivityStarter<PaymentMethodsActivity, PaymentMethodsActivityStarter.Args> = mock()
    private val paymentFlowActivityStarter:
        ActivityStarter<PaymentFlowActivity, PaymentFlowActivityStarter.Args> = mock()
    private val paymentSessionPrefs: PaymentSessionPrefs = mock()

    private val paymentSessionDataArgumentCaptor: KArgumentCaptor<PaymentSessionData> = argumentCaptor()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activityScenarioFactory = ActivityScenarioFactory(context)

    @BeforeTest
    fun setup() {
        PaymentConfiguration.init(context, ApiKeyFixtures.FAKE_PUBLISHABLE_KEY)

        doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`<ThreadPoolExecutor>(threadPoolExecutor).execute(any())
    }

    @Test
    fun init_addsPaymentSessionToken_andFetchesCustomer() {
        val customerSession = createCustomerSession()
        CustomerSession.instance = customerSession

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)

            assertTrue(customerSession.productUsageTokens
                .contains(PaymentSession.TOKEN_PAYMENT_SESSION))

            verify(paymentSessionListener).onCommunicatingStateChanged(eq(true))
        }
    }

    @Test
    fun init_whenEphemeralKeyProviderContinues_fetchesCustomerAndNotifiesListener() {
        ephemeralKeyProvider
            .setNextRawEphemeralKey(EphemeralKeyFixtures.FIRST_JSON)
        CustomerSession.instance = createCustomerSession()

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)
            verify(paymentSessionListener)
                .onCommunicatingStateChanged(eq(true))
            verify(paymentSessionListener)
                .onPaymentSessionDataChanged(any())
            verify(paymentSessionListener)
                .onCommunicatingStateChanged(eq(false))
        }
    }

    @Test
    fun handlePaymentData_whenPaymentMethodSelected_notifiesListenerAndFetchesCustomer() {
        CustomerSession.instance = createCustomerSession()

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)

            // We have already tested the functionality up to here.
            reset(paymentSessionListener)

            val result = PaymentMethodsActivityStarter.Result(
                paymentMethod = PaymentMethodFixtures.CARD_PAYMENT_METHOD
            )
            val handled = paymentSession.handlePaymentData(
                PaymentMethodsActivityStarter.REQUEST_CODE, RESULT_OK,
                Intent().putExtras(result.toBundle()))
            assertTrue(handled)

            verify(paymentSessionListener)
                .onPaymentSessionDataChanged(paymentSessionDataArgumentCaptor.capture())
            val data = paymentSessionDataArgumentCaptor.firstValue
            assertEquals(PaymentMethodFixtures.CARD_PAYMENT_METHOD, data.paymentMethod)
            assertFalse(data.useGooglePay)
        }
    }

    @Test
    fun handlePaymentData_whenGooglePaySelected_notifiesListenerAndFetchesCustomer() {
        CustomerSession.instance = createCustomerSession()

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)

            // We have already tested the functionality up to here.
            reset(paymentSessionListener)

            val result = PaymentMethodsActivityStarter.Result(
                useGooglePay = true
            )
            val handled = paymentSession.handlePaymentData(
                PaymentMethodsActivityStarter.REQUEST_CODE, RESULT_OK,
                Intent().putExtras(result.toBundle()))
            assertTrue(handled)

            verify(paymentSessionListener)
                .onPaymentSessionDataChanged(paymentSessionDataArgumentCaptor.capture())
            val data = paymentSessionDataArgumentCaptor.firstValue
            assertNull(data.paymentMethod)
            assertTrue(data.useGooglePay)
        }
    }

    @Test
    fun selectPaymentMethod_launchesPaymentMethodsActivityWithLog() {
        CustomerSession.instance = createCustomerSession()

        createActivity { activity ->
            val paymentSession = PaymentSession(activity, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)
            paymentSession.presentPaymentMethodSelection()

            val nextStartedActivityForResult =
                Shadows.shadowOf(activity).nextStartedActivityForResult
            val intent = nextStartedActivityForResult.intent

            assertThat(nextStartedActivityForResult.requestCode)
                .isEqualTo(PaymentMethodsActivityStarter.REQUEST_CODE)
            assertThat(intent.component?.className)
                .isEqualTo(PaymentMethodsActivity::class.java.name)

            val args =
                PaymentMethodsActivityStarter.Args.create(intent)
            assertEquals(BillingAddressFields.Full, args.billingAddressFields)
        }
    }

    @Test
    fun presentPaymentMethodSelection_withShouldRequirePostalCode_shouldPassInIntent() {
        CustomerSession.instance = createCustomerSession()

        createActivity { activity ->
            val paymentSession = PaymentSession(
                activity,
                PaymentSessionConfig.Builder()
                    .setShippingMethodsRequired(false)
                    .setBillingAddressFields(BillingAddressFields.PostalCode)
                    .build()
            )
            paymentSession.init(paymentSessionListener)
            paymentSession.presentPaymentMethodSelection()

            val nextStartedActivityForResult =
                Shadows.shadowOf(activity).nextStartedActivityForResult
            val intent = nextStartedActivityForResult.intent

            assertThat(nextStartedActivityForResult.requestCode)
                .isEqualTo(PaymentMethodsActivityStarter.REQUEST_CODE)
            assertThat(intent.component?.className)
                .isEqualTo(PaymentMethodsActivity::class.java.name)

            val args =
                PaymentMethodsActivityStarter.Args.create(nextStartedActivityForResult.intent)
            assertThat(args.billingAddressFields)
                .isEqualTo(BillingAddressFields.PostalCode)
        }
    }

    @Test
    fun getSelectedPaymentMethodId_whenPrefsNotSet_returnsNull() {
        `when`<Customer>(customerSession.cachedCustomer).thenReturn(FIRST_CUSTOMER)
        CustomerSession.instance = customerSession
        assertNull(createPaymentSession().getSelectedPaymentMethodId(null))
    }

    @Test
    fun getSelectedPaymentMethodId_whenHasPaymentSessionData_returnsExpectedId() {
        val paymentSession = createPaymentSession(
            paymentSessionData = PaymentSessionFixtures.PAYMENT_SESSION_DATA.copy(
                paymentMethod = PaymentMethodFixtures.CARD_PAYMENT_METHOD
            )
        )
        assertEquals(
            "pm_123456789",
            paymentSession.getSelectedPaymentMethodId(null)
        )
    }

    @Test
    fun getSelectedPaymentMethodId_whenHasPrefsSet_returnsExpectedId() {
        val customerId = requireNotNull(FIRST_CUSTOMER.id)
        `when`<String>(paymentSessionPrefs.getSelectedPaymentMethodId(customerId))
            .thenReturn("pm_12345")

        `when`<Customer>(customerSession.cachedCustomer).thenReturn(FIRST_CUSTOMER)
        CustomerSession.instance = customerSession

        assertEquals("pm_12345",
            createPaymentSession().getSelectedPaymentMethodId(null))
    }

    @Test
    fun getSelectedPaymentMethodId_whenHasUserSpecifiedPaymentMethod_returnsExpectedId() {
        val paymentSession = createPaymentSession(
            paymentSessionData = PaymentSessionFixtures.PAYMENT_SESSION_DATA.copy(
                paymentMethod = PaymentMethodFixtures.CARD_PAYMENT_METHOD
            )
        )
        assertEquals("pm_987",
            paymentSession.getSelectedPaymentMethodId("pm_987"))
    }

    @Test
    fun init_withoutSavedState_clearsLoggingTokensAndStartsWithPaymentSession() {
        val customerSession = createCustomerSession()
        CustomerSession.instance = customerSession
        customerSession
            .addProductUsageTokenIfValid(PaymentMethodsActivity.TOKEN_PAYMENT_METHODS_ACTIVITY)
        assertEquals(1, customerSession.productUsageTokens.size)

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)

            // The init removes PaymentMethodsActivity, but then adds PaymentSession
            val loggingTokens = customerSession.productUsageTokens
            assertEquals(1, loggingTokens.size)
            assertFalse(loggingTokens.contains(PaymentMethodsActivity.TOKEN_PAYMENT_METHODS_ACTIVITY))
            assertTrue(loggingTokens.contains(PaymentSession.TOKEN_PAYMENT_SESSION))
        }
    }

    @Test
    fun init_withSavedStateBundle_doesNotClearLoggingTokens() {
        val customerSession = createCustomerSession()
        CustomerSession.instance = customerSession
        customerSession
            .addProductUsageTokenIfValid(PaymentMethodsActivity.TOKEN_PAYMENT_METHODS_ACTIVITY)
        assertEquals(1, customerSession.productUsageTokens.size)

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            // If it is given any saved state at all, the tokens are not cleared out.
            paymentSession.init(paymentSessionListener, Bundle())

            val loggingTokens = customerSession.productUsageTokens
            assertEquals(2, loggingTokens.size)
            assertTrue(loggingTokens.contains(PaymentMethodsActivity.TOKEN_PAYMENT_METHODS_ACTIVITY))
            assertTrue(loggingTokens.contains(PaymentSession.TOKEN_PAYMENT_SESSION))
        }
    }

    @Test
    fun completePayment_withLoggedActions_clearsLoggingTokensAndSetsResult() {
        val customerSession = createCustomerSession()
        CustomerSession.instance = customerSession
        customerSession
            .addProductUsageTokenIfValid(PaymentMethodsActivity.TOKEN_PAYMENT_METHODS_ACTIVITY)
        assertEquals(1, customerSession.productUsageTokens.size)

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            // If it is given any saved state at all, the tokens are not cleared out.
            paymentSession.init(paymentSessionListener, Bundle())

            val loggingTokens = customerSession.productUsageTokens
            assertEquals(2, loggingTokens.size)

            reset(paymentSessionListener)

            paymentSession.onCompleted()
            assertTrue(customerSession.productUsageTokens.isEmpty())
        }
    }

    @Test
    fun init_withSavedState_setsPaymentSessionData() {
        ephemeralKeyProvider.setNextRawEphemeralKey(EphemeralKeyFixtures.FIRST_JSON)
        CustomerSession.instance = createCustomerSession()

        createActivity {
            val paymentSession = PaymentSession(it, DEFAULT_CONFIG)
            paymentSession.init(paymentSessionListener)
            paymentSession.setCartTotal(300L)

            verify(paymentSessionListener)
                .onPaymentSessionDataChanged(paymentSessionDataArgumentCaptor.capture())
            val bundle = Bundle()
            paymentSession.savePaymentSessionInstanceState(bundle)
            val firstPaymentSessionData = paymentSessionDataArgumentCaptor.firstValue

            val secondListener =
                mock(PaymentSession.PaymentSessionListener::class.java)

            paymentSession.init(secondListener, bundle)
            verify(secondListener)
                .onPaymentSessionDataChanged(paymentSessionDataArgumentCaptor.capture())

            val secondPaymentSessionData = paymentSessionDataArgumentCaptor.firstValue
            assertEquals(firstPaymentSessionData.cartTotal,
                secondPaymentSessionData.cartTotal)
            assertEquals(firstPaymentSessionData.paymentMethod,
                secondPaymentSessionData.paymentMethod)
        }
    }

    @Test
    fun handlePaymentData_withInvalidRequestCode_aborts() {
        val paymentSession = createPaymentSession()
        assertFalse(paymentSession.handlePaymentData(-1, RESULT_CANCELED, Intent()))
        verify(customerSession, never()).retrieveCurrentCustomer(any())
    }

    @Test
    fun handlePaymentData_withPaymentMethodsActivityRequestCodeAndCanceledResult_doesNotRetrieveCustomer() {
        val paymentSession = createPaymentSession()
        assertFalse(paymentSession.handlePaymentData(PaymentMethodsActivityStarter.REQUEST_CODE,
            RESULT_CANCELED, Intent()))
        verify(customerSession, never()).retrieveCurrentCustomer(any())
    }

    @Test
    fun handlePaymentData_withPaymentFlowActivityRequestCodeAndCanceledResult_retrievesCustomer() {
        val paymentSession = createPaymentSession()
        assertFalse(paymentSession.handlePaymentData(PaymentFlowActivityStarter.REQUEST_CODE,
            RESULT_CANCELED, Intent()))
        verify(customerSession).retrieveCurrentCustomer(any())
    }

    private fun createPaymentSession(
        config: PaymentSessionConfig = DEFAULT_CONFIG,
        paymentSessionData: PaymentSessionData = PaymentSessionData(config)
    ): PaymentSession {
        return PaymentSession(
            context,
            config,
            customerSession,
            paymentMethodsActivityStarter,
            paymentFlowActivityStarter,
            paymentSessionPrefs,
            paymentSessionData
        )
    }

    private fun createCustomerSession(): CustomerSession {
        return CustomerSession(
            context,
            FakeStripeRepository(),
            ApiKeyFixtures.FAKE_PUBLISHABLE_KEY,
            "acct_abc123",
            threadPoolExecutor = threadPoolExecutor,
            ephemeralKeyManagerFactory = EphemeralKeyManager.Factory.Default(
                keyProvider = ephemeralKeyProvider,
                shouldPrefetchEphemeralKey = true
            )
        )
    }

    private fun createActivity(callback: (Activity) -> Unit) {
        // start an arbitrary Activity
        activityScenarioFactory.create<PaymentMethodsActivity>(
            PaymentMethodsActivityStarter.Args.DEFAULT
        ).use { activityScenario ->
            activityScenario.onActivity(callback)
        }
    }

    private class FakeStripeRepository : AbsFakeStripeRepository() {
        override fun createPaymentMethod(
            paymentMethodCreateParams: PaymentMethodCreateParams,
            options: ApiRequest.Options
        ): PaymentMethod {
            return PaymentMethodFixtures.CARD_PAYMENT_METHOD
        }

        override fun setDefaultCustomerSource(
            customerId: String,
            publishableKey: String,
            productUsageTokens: Set<String>,
            sourceId: String,
            sourceType: String,
            requestOptions: ApiRequest.Options
        ): Customer {
            return SECOND_CUSTOMER
        }

        override fun retrieveCustomer(
            customerId: String,
            requestOptions: ApiRequest.Options
        ): Customer {
            return FIRST_CUSTOMER
        }
    }

    private companion object {
        private val FIRST_CUSTOMER = CustomerFixtures.CUSTOMER
        private val SECOND_CUSTOMER = CustomerFixtures.OTHER_CUSTOMER

        private val DEFAULT_CONFIG = PaymentSessionFixtures.CONFIG
    }
}
