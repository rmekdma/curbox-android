package neth.iecal.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubjectPackagesTest {
    @Test fun defaultAndInjectedPackagesAgreeInBothDirections() {
        val production = SubjectPackages()
        assertEquals(Subject.MATH, production.subjectFor("com.enuma.todomath"))
        assertNull(production.subjectFor("neth.iecal.curbox.apitester"))
        val probe = SubjectPackages(math = "neth.iecal.curbox.apitester")
        assertEquals(Subject.MATH, probe.subjectFor("neth.iecal.curbox.apitester"))
        assertEquals("neth.iecal.curbox.apitester", probe.packageFor(Subject.MATH))
        assertNull(probe.subjectFor("com.enuma.todomath"))
    }
}
