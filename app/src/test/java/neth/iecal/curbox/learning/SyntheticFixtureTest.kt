package neth.iecal.curbox.learning

import neth.iecal.todo.LearningFailure
import neth.iecal.todo.Subject
import org.junit.Assert.*
import org.junit.Test

class SyntheticFixtureTest {
    @Test fun `seconds are optional and must agree with displayed minutes`() {
        val prefix = """{"revision":"r","children":[{"name":"Child1","users":{"MATH":"user1"}}],"reports":[{"user":"user1","subject":"MATH","date":"20261106","count":2,"minutes":1"""
        val exact = SyntheticFixture.parse(prefix + ""","seconds":40}]}""")
        assertEquals(40.0, exact.report("user1", neth.iecal.todo.Subject.MATH, "20261106").seconds!!, 0.0)
        assertEquals(60.0, SyntheticFixture.parse(prefix + "}]}").report("user1", neth.iecal.todo.Subject.MATH, "20261106").seconds!!, 0.0)
        assertTrue(runCatching { SyntheticFixture.parse(prefix + ""","seconds":61}]}""") }.isFailure)
    }

    @Test fun configuredReportsAndFailuresAreDistinct() {
        val fixture = SyntheticFixture.parse("""{"revision":"run1","children":[{"name":"Child1","users":{"MATH":"user1"}}],"reports":[{"user":"user1","subject":"MATH","date":"20261106","count":2,"minutes":4},{"user":"user1","subject":"MATH","date":"20261107","failure":"NETWORK"}]}""")
        assertEquals(2, fixture.report("user1", Subject.MATH, "20261106").count)
        assertEquals(LearningFailure.NETWORK, runCatching { fixture.report("user1", Subject.MATH, "20261107") }.exceptionOrNull().let { (it as neth.iecal.todo.TodoApiException).kind })
        assertTrue(runCatching { fixture.report("user1", Subject.MATH, "20261108") }.isFailure)
        assertTrue(runCatching { SyntheticFixture.parse("{}") }.isFailure)
    }
}
