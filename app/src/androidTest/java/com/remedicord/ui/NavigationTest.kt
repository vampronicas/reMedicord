package com.remedicord.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.ui.navigation.DetailRoute
import com.remedicord.ui.navigation.MedicationsRoute
import com.remedicord.ui.navigation.openDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** v1.4.1 bug: jumping between an upgraded medicine and its replacement stacked copies of the same pages. */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun start() = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = MedicationsRoute) {
            composable<MedicationsRoute> { Text("list") }
            composable<DetailRoute> { Text("detail") }
        }
    }

    @Test fun jumpingBackAndForthReturnsToTheExistingPage() {
        start()
        rule.runOnIdle {
            nav.openDetail(1)          // list → old medicine
            nav.openDetail(2)          // old → "Upgraded to" new medicine
            nav.openDetail(1)          // new → "Upgraded from" old: goes BACK, no new copy
            nav.openDetail(2)
            nav.openDetail(1)
        }
        rule.runOnIdle {
            assertEquals(1L, nav.currentBackStackEntry!!.toRoute<DetailRoute>().id)
            // The page below must be the medicines list (nothing stacked in between).
            assertTrue(nav.previousBackStackEntry!!.destination.hasRoute(MedicationsRoute::class))
            assertTrue(nav.popBackStack())       // one Back → list
            assertTrue(nav.currentBackStackEntry!!.destination.hasRoute(MedicationsRoute::class))
        }
    }

    @Test fun doubleTapDoesNotOpenTheSamePageTwice() {
        start()
        rule.runOnIdle { nav.openDetail(5); nav.openDetail(5); nav.openDetail(5) }
        rule.runOnIdle {
            assertEquals(5L, nav.currentBackStackEntry!!.toRoute<DetailRoute>().id)
            assertTrue(nav.previousBackStackEntry!!.destination.hasRoute(MedicationsRoute::class))
            assertTrue(nav.popBackStack()) // one Back must land on the list; a stacked copy would show page 5 again
            assertTrue(nav.currentBackStackEntry!!.destination.hasRoute(MedicationsRoute::class))
        }
    }
}
