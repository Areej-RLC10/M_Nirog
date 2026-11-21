package com.example.m_nirog.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.m_nirog.R
import com.example.m_nirog.core.NirogNotifications
import com.example.m_nirog.core.SessionStore
import com.example.m_nirog.core.SupportHelper
import com.example.m_nirog.data.RemoteBookingRepository
import com.example.m_nirog.ui.screens.*
import com.example.m_nirog.ui.viewmodels.*

object Routes {
    const val LOGIN = "login"
    const val CONSENT = "consent"
    const val PACKAGES = "packages"
    const val SLOT = "slot"
    const val SUMMARY = "summary"
    const val STATUS = "status"

    const val VERIFYING_PAYMENT = "verifying_payment"
    const val PAYMENT_FAILED = "payment_failed"
    const val MY_BOOKINGS = "my_bookings"
    const val PROFILE = "profile"
    const val HEALTH_RECORDS = "health_records"
    const val ADD_RECORD = "add_record"
    const val NEW_SAMPLE_NEEDED = "new_sample_needed"
    const val PICKUP_MISSED = "pickup_missed"

    const val VERIFY_RESULTS = "verify_results/{bookingId}"
    const val REPORT_DETAIL = "report_detail/{bookingId}"
    const val URGENT_RESULT = "urgent_result/{bookingId}"

    const val PICKER_LIST = "picker_list"
    const val PICKER_DETAIL = "picker_detail/{pickupId}"

    fun verifyResults(bookingId: String) = "verify_results/$bookingId"
    fun reportDetail(bookingId: String) = "report_detail/$bookingId"
    fun urgentResult(bookingId: String) = "urgent_result/$bookingId"
    fun pickerDetail(pickupId: String) = "picker_detail/$pickupId"
}

@Composable
fun AppNav() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val bookingRepo = remember(context) { RemoteBookingRepository(SessionStore(context)) }
    val auth: AuthViewModel = viewModel()
    val authState by auth.state.collectAsState()
    val booking: BookingViewModel = viewModel { BookingViewModel(bookingRepo) }
    val bookingsVm: BookingsViewModel = viewModel { BookingsViewModel(bookingRepo) }
    val reportVm: ReportViewModel = viewModel()
    val profileVm: ProfileViewModel = viewModel()
    val pickerVm: PickerViewModel = viewModel()
    val pendingDeepLink by NirogNotifications.pendingDeepLink.collectAsState()

    val isStaffRole = authState.role == "PICKER" || authState.role == "ADMIN"

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            NirogNotifications.syncTokenIfLoggedIn(context)
        }
    }

    LaunchedEffect(authState.loggedIn) {
        if (authState.loggedIn) {
            NirogNotifications.syncTokenIfLoggedIn(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val start = remember {
        val a = auth.state.value
        when {
            !a.loggedIn -> Routes.LOGIN
            a.role == "PICKER" || a.role == "ADMIN" -> Routes.PICKER_LIST
            !a.consented -> Routes.CONSENT
            else -> Routes.PACKAGES
        }
    }

    val performSignOut: () -> Unit = {
        val accessToken = SessionStore(context).accessToken()
        NirogNotifications.clearTokenForAccessToken(accessToken)
        auth.logout()
        booking.reset()
        bookingsVm.reset()
        profileVm.reset()
        pickerVm.reset()
        bookingRepo.clearCache()
        nav.navigate(Routes.LOGIN) {
            popUpTo(0) { inclusive = true }
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in setOf(
        Routes.PACKAGES,
        Routes.MY_BOOKINGS,
        Routes.PROFILE,
    )

    LaunchedEffect(pendingDeepLink, authState.loggedIn, currentRoute) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (!authState.loggedIn || currentRoute == null || currentRoute == Routes.LOGIN || currentRoute == Routes.CONSENT) {
            return@LaunchedEffect
        }
        NirogNotifications.consumeDeepLink()
        val bId = link.bookingId
        if (!isStaffRole && !bId.isNullOrBlank()) {
            booking.openBooking(bId)
            when (link.category) {
                "URGENT_RESULT" -> nav.navigate(Routes.urgentResult(bId))
                "NO_SHOW" -> nav.navigate(Routes.PICKUP_MISSED)
                "RECOLLECTION" -> nav.navigate(Routes.NEW_SAMPLE_NEEDED)
                else -> nav.navigate(Routes.STATUS) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NirogBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { target ->
                        nav.navigate(target) {
                            popUpTo(Routes.PACKAGES) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(auth) {
                    val a = auth.state.value
                    val next = when {
                        a.role == "PICKER" || a.role == "ADMIN" -> Routes.PICKER_LIST
                        a.consented -> Routes.PACKAGES
                        else -> Routes.CONSENT
                    }
                    nav.navigate(next) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }
            composable(Routes.CONSENT) {
                ConsentScreen(auth) {
                    nav.navigate(Routes.PACKAGES) { popUpTo(Routes.CONSENT) { inclusive = true } }
                }
            }
            composable(Routes.PACKAGES) {
                PackagesScreen(booking) { nav.navigate(Routes.SLOT) }
            }
            composable(Routes.SLOT) {
                SlotAddressScreen(booking, profileVm, onBack = { nav.popBackStack() }) {
                    nav.navigate(Routes.SUMMARY)
                }
            }
            composable(Routes.SUMMARY) {
                OrderSummaryScreen(
                    vm = booking,
                    onBack = { nav.popBackStack() },
                    onVerifyingPayment = { nav.navigate(Routes.VERIFYING_PAYMENT) },
                ) {
                    nav.navigate(Routes.STATUS) { popUpTo(Routes.PACKAGES) }
                }
            }
            composable(Routes.STATUS) {
                StatusScreen(
                    vm = booking,
                    onVerifyingPayment = { nav.navigate(Routes.VERIFYING_PAYMENT) },
                    onVerifyResults = { id ->
                        nav.navigate(Routes.verifyResults(id.ifBlank { "demo-1" }))
                    },
                    onViewReport = { id ->
                        nav.navigate(Routes.reportDetail(id.ifBlank { "demo-1" }))
                    },
                    onNewSampleNeeded = {
                        nav.navigate(Routes.NEW_SAMPLE_NEEDED)
                    },
                    onUrgentResult = { id ->
                        nav.navigate(Routes.urgentResult(id.ifBlank { "demo-1" }))
                    },
                    onBookAgain = {
                        nav.navigate(Routes.PACKAGES) { popUpTo(Routes.PACKAGES) { inclusive = true } }
                    },
                )
            }
            composable(Routes.VERIFYING_PAYMENT) {
                VerifyingPaymentScreen(
                    vm = booking,
                    onRefresh = { booking.refreshOrVerifyKhalti() },
                    onPaid = { nav.navigate(Routes.STATUS) { popUpTo(Routes.PACKAGES) } },
                    onPaymentFailed = { nav.navigate(Routes.PAYMENT_FAILED) },
                )
            }
            composable(Routes.PAYMENT_FAILED) {
                PaymentFailedScreen(
                    vm = booking,
                    onTryAgain = { booking.consumePaymentFailed(); nav.popBackStack() },
                    onChooseAnotherSlot = {
                        nav.navigate(Routes.SLOT) { popUpTo(Routes.PACKAGES) }
                    },
                )
            }
            composable(Routes.MY_BOOKINGS) {
                MyBookingsScreen(
                    vm = bookingsVm,
                    onBookCheckup = { nav.navigate(Routes.PACKAGES) },
                    onOpenBooking = { id ->
                        booking.openBooking(id)
                        nav.navigate(Routes.STATUS)
                    },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    vm = profileVm,
                    onOpenHealthRecords = { nav.navigate(Routes.HEALTH_RECORDS) },
                    onOpenPickerMode = { nav.navigate(Routes.PICKER_LIST) },
                    onSignOut = performSignOut,
                )
            }
            composable(Routes.HEALTH_RECORDS) {
                HealthRecordsScreen(
                    vm = profileVm,
                    onBack = { nav.popBackStack() },
                    onOpenRecord = { },
                    onAddRecord = { nav.navigate(Routes.ADD_RECORD) },
                )
            }
            composable(Routes.ADD_RECORD) {
                AddRecordScreen(
                    vm = profileVm,
                    onBack = { nav.popBackStack() },
                    onScanWithCamera = { },
                    onChooseFile = { },
                )
            }
            composable(Routes.NEW_SAMPLE_NEEDED) {
                val bookingState by booking.state.collectAsState()
                NewSampleNeededScreen(
                    labReason = bookingState.booking?.recollectionReason ?: "Sample unusable",
                    onBack = { nav.popBackStack() },
                    onViewBooking = {
                        if (!nav.popBackStack(Routes.STATUS, inclusive = false)) {
                            nav.navigate(Routes.STATUS)
                        }
                    },
                )
            }
            composable(Routes.PICKUP_MISSED) {
                PickupMissedScreen(
                    onBack = { nav.popBackStack() },
                    onBookAgain = {
                        nav.navigate(Routes.PACKAGES) { popUpTo(Routes.PACKAGES) { inclusive = true } }
                    },
                    onContactSupport = { SupportHelper.contactSupport(context) },
                )
            }
            composable(
                route = Routes.VERIFY_RESULTS,
                arguments = listOf(navArgument("bookingId") { type = NavType.StringType }),
            ) { entry ->
                val bookingId = entry.arguments?.getString("bookingId").orEmpty()
                VerifyResultsScreen(
                    bookingId = bookingId,
                    vm = reportVm,
                    onBack = { nav.popBackStack() },
                    onDone = { _ ->
                        booking.refresh()
                        nav.popBackStack()
                    },
                )
            }
            composable(
                route = Routes.REPORT_DETAIL,
                arguments = listOf(navArgument("bookingId") { type = NavType.StringType }),
            ) { entry ->
                val bookingId = entry.arguments?.getString("bookingId").orEmpty()
                ReportAndAdviceScreen(
                    bookingId = bookingId,
                    vm = reportVm,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.URGENT_RESULT,
                arguments = listOf(navArgument("bookingId") { type = NavType.StringType }),
            ) { entry ->
                val bookingId = entry.arguments?.getString("bookingId").orEmpty()
                UrgentResultScreen(
                    onOpenReport = {
                        nav.navigate(Routes.reportDetail(bookingId.ifBlank { "demo-1" })) {
                            popUpTo(Routes.URGENT_RESULT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.PICKER_LIST) {
                PickerPickupsScreen(
                    vm = pickerVm,
                    isStaffSession = isStaffRole,
                    onOpenPickup = { id -> nav.navigate(Routes.pickerDetail(id)) },
                    onBackToPatient = { nav.popBackStack() },
                    onSignOut = performSignOut,
                )
            }
            composable(
                route = Routes.PICKER_DETAIL,
                arguments = listOf(navArgument("pickupId") { type = NavType.StringType }),
            ) { entry ->
                val pickupId = entry.arguments?.getString("pickupId").orEmpty()
                PickerPickupDetailScreen(
                    pickupId = pickupId,
                    vm = pickerVm,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun NirogBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomTab(stringResource(R.string.tab_checkup), currentRoute == Routes.PACKAGES) { onSelect(Routes.PACKAGES) }
                BottomTab(stringResource(R.string.tab_bookings), currentRoute == Routes.MY_BOOKINGS) { onSelect(Routes.MY_BOOKINGS) }
                BottomTab(stringResource(R.string.tab_profile), currentRoute == Routes.PROFILE) { onSelect(Routes.PROFILE) }
            }
        }
    }
}

@Composable
private fun BottomTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
