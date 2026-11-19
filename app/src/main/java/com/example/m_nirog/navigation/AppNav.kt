package com.example.m_nirog.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.m_nirog.ui.screens.*
import com.example.m_nirog.ui.viewmodels.*

object Routes {
    const val LOGIN = "login"
    const val CONSENT = "consent"
    const val PACKAGES = "packages"
    const val SLOT = "slot"
    const val SUMMARY = "summary"
    const val STATUS = "status"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val auth: AuthViewModel = viewModel()
    val booking: BookingViewModel = viewModel()

    // Returning users skip login and consent.
    val start = remember {
        val a = auth.state.value
        when {
            !a.loggedIn -> Routes.LOGIN
            !a.consented -> Routes.CONSENT
            else -> Routes.PACKAGES
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(auth) {
                val next = if (auth.state.value.consented) Routes.PACKAGES else Routes.CONSENT
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
            SlotAddressScreen(booking, onBack = { nav.popBackStack() }) { nav.navigate(Routes.SUMMARY) }
        }
        composable(Routes.SUMMARY) {
            OrderSummaryScreen(booking, onBack = { nav.popBackStack() }) {
                nav.navigate(Routes.STATUS) { popUpTo(Routes.PACKAGES) }
            }
        }
        composable(Routes.STATUS) {
            StatusScreen(booking) {
                nav.navigate(Routes.PACKAGES) { popUpTo(Routes.PACKAGES) { inclusive = true } }
            }
        }
    }
}
