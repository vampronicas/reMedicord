package com.remedicord.domain.usecase

import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.schedule.TaperBuilder
import java.math.BigDecimal
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormValidatorTest {
    private val ok = MedicationForm(name = "Amoxicillin", rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0))))

    @Test fun validFormPasses() = assertEquals(emptyList<String>(), FormValidator.validate(ok))

    @Test fun nameRules() {
        assertTrue(FormValidator.validate(ok.copy(name = "  ")).contains("Enter a name"))
        assertTrue(FormValidator.validate(ok.copy(name = "x".repeat(81))).any { it.startsWith("Name is too long") })
        assertTrue(FormValidator.validate(ok.copy(name = "Bad\u0000name")).contains("Name contains invalid characters"))
        // Right-to-left override characters (used to disguise text) are rejected.
        assertTrue(FormValidator.validate(ok.copy(name = "abc" + Char(0x202E) + "def")).contains("Name contains invalid characters"))
        assertEquals(emptyList<String>(), FormValidator.validate(ok.copy(name = "Paracétamol 500 mg (generic) ✓")))
    }

    @Test fun textAndSiteLimits() {
        assertTrue(FormValidator.validate(ok.copy(instructions = "x".repeat(501))).isNotEmpty())
        assertEquals(emptyList<String>(), FormValidator.validate(ok.copy(instructions = "line1\nline2")))
        assertTrue(FormValidator.validate(ok.copy(bodyLocation = "x".repeat(61))).isNotEmpty())
        assertTrue(FormValidator.validate(ok.copy(sites = List(13) { "s$it" })).contains("Invalid site list"))
        assertTrue(FormValidator.validate(ok.copy(sites = listOf(" "))).contains("Invalid site list"))
    }

    @Test fun eyeAndEarNeedASide() {
        assertTrue(FormValidator.validate(ok.copy(type = MedicationType.EYE_DROP)).contains("Choose left, right or both"))
        assertTrue(FormValidator.validate(ok.copy(type = MedicationType.EAR_DROP)).contains("Choose left, right or both"))
        assertEquals(emptyList<String>(), FormValidator.validate(ok.copy(type = MedicationType.EYE_DROP, side = BodySide.BOTH)))
    }

    @Test fun doseAndScheduleRules() {
        assertTrue(FormValidator.validate(ok.copy(rule = null)).contains("Choose a schedule"))
        assertTrue(FormValidator.validate(ok.copy(amount = BigDecimal.ZERO)).contains("Enter a dose amount above 0"))
        assertTrue(FormValidator.validate(ok.copy(amount = BigDecimal(10_001))).contains("Enter a dose amount above 0"))
        val step = TaperBuilder.Step(ScheduleRule.FixedTimes(listOf(LocalTime.NOON)), BigDecimal.ONE, DoseUnit.DROP, 7)
        assertEquals(emptyList<String>(), FormValidator.validate(ok.copy(rule = null, taperSteps = listOf(step))))
        assertTrue(FormValidator.validate(ok.copy(taperSteps = listOf(step.copy(days = 0)))).isNotEmpty())
        assertTrue(FormValidator.validate(ok.copy(taperSteps = List(21) { step })).isNotEmpty())
    }

    @Test fun courseRules() {
        assertTrue(FormValidator.validate(ok.copy(mustComplete = true)).contains("A must-complete course needs a length"))
        assertTrue(FormValidator.validate(ok.copy(courseMode = CourseMode.DAYS, courseLength = null)).any { it.startsWith("Course length") })
        assertTrue(FormValidator.validate(ok.copy(courseMode = CourseMode.DAYS, courseLength = 366)).any { it.startsWith("Course length") })
        assertEquals(emptyList<String>(), FormValidator.validate(ok.copy(courseMode = CourseMode.DOSES, courseLength = 1000, mustComplete = true)))
        assertTrue(FormValidator.validate(ok.copy(rule = ScheduleRule.AsNeeded(60, 4), courseMode = CourseMode.DOSES, courseLength = 5)).isNotEmpty())
    }
}
